package blbl.cat3399.feature.following

import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.feature.dynamic.PinyinSearch
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UP 主主页「投稿」标签页里的站内视频搜索。
 *
 * 两条路径，按关键词形态自动选择：
 * 1. 关键词含汉字 → 走 B 站空间搜索接口（keyword 参数），覆盖整个投稿列表，按发布时间倒序分页，
 *    语义与网页版「在 TA 的视频中搜索」一致，不会漏后几页；
 * 2. 关键词只有字母/数字（TV 端遥控器敲拼音的常见形态）→ 服务器不认拼音，改为先秒出已加载视频里
 *    原文 / 全拼 / 首字母 / 纯汉字声母的命中，再在后台按 50 条一页把投稿列表扫一遍做拼音匹配，
 *    边扫边出结果，最多扫 2000 条避免把弱性能设备拖垮。
 *
 * 所有过滤（拼音归一化、ICU 转写）都在 Dispatchers.Default 上跑，主线程只做 adapter 提交。
 */
class UpArchiveSearchController(
    private val scope: CoroutineScope,
    private val mid: Long,
    private val onUi: (Ui) -> Unit,
) {
    data class Ui(
        val query: String,
        val items: List<VideoCard>,
        /** 服务器返回的投稿总数（local 模式是投稿总数，不是命中数）。 */
        val totalCount: Int?,
        /** local 模式已扫描过的视频条数。 */
        val scannedCount: Int,
        val scanning: Boolean,
        /** local 模式因为条数上限提前停下。 */
        val cappedByScanLimit: Boolean,
        /** 是否还有下一页可以继续加载（仅 server 模式有意义）。 */
        val canLoadMore: Boolean,
        val serverMode: Boolean,
    )

    private companion object {
        const val SERVER_PAGE_SIZE = 30
        const val LOCAL_PAGE_SIZE = 50
        const val LOCAL_MAX_SCANNED = 2000
        const val LOCAL_PAGE_GAP_MS = 150L
        const val NETWORK_DELAY_MS = 700L
        const val CACHE_MAX_ENTRIES = 8
        const val TAG = "UpArchiveSearch"
    }

    private var job: Job? = null
    private var loadMoreJob: Job? = null
    private var token = 0

    var query: String = ""
        private set

    /** 已搜索完成的结果缓存：同词二次搜索直接复用，零请求。按访问顺序做 LRU。 */
    private data class CachedResult(
        val items: List<VideoCard>,
        val scannedCount: Int,
        val cappedByScanLimit: Boolean,
        val totalCount: Int?,
        val serverMode: Boolean,
    )

    private val resultCache =
        object : LinkedHashMap<String, CachedResult>(CACHE_MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedResult>?): Boolean =
                size > CACHE_MAX_ENTRIES
        }

    private val resultItems = ArrayList<VideoCard>()
    private val resultKeys = HashSet<String>()

    private var serverMode = false
    private var nextServerPage = 1
    private var endReached = false
    private var totalCount: Int? = null
    private var scannedCount = 0
    private var scanning = false
    private var cappedByScanLimit = false

    fun isActive(): Boolean = query.isNotBlank()

    fun items(): List<VideoCard> = ArrayList(resultItems)

    fun start(rawQuery: String, preloaded: List<VideoCard>, scanStartPage: Int = 1) {
        val q = rawQuery.trim()
        if (q.isEmpty()) {
            cancel()
            return
        }
        cancel()
        query = q
        val tk = token
        // 服务器 keyword 搜索只认单个词，带空格时语义不可靠，退回本地拼音过滤
        serverMode = q.any { isHanChar(it) } && q.none { it.isWhitespace() }
        resultItems.clear()
        resultKeys.clear()
        nextServerPage = 1
        endReached = false
        totalCount = null
        scannedCount = 0
        scanning = true
        cappedByScanLimit = false

        // 先把「已经加载过的投稿」里的命中立刻摆出来，等网络那一段不至于白屏。
        job =
            scope.launch {
                val instant =
                    withContext(Dispatchers.Default) {
                        val matcher = PinyinSearch.matcher(q)
                        preloaded.filter { matcher(it.title) }
                    }
                if (token != tk) return@launch
                instant.forEach { addResult(it) }
                emit()

                val cached = resultCache[q]
                if (cached != null && cached.serverMode == serverMode) {
                    // 同词搜过且已搜完：直接复用，不再发任何请求
                    resultItems.clear()
                    resultKeys.clear()
                    cached.items.forEach { addResult(it) }
                    scannedCount = cached.scannedCount
                    cappedByScanLimit = cached.cappedByScanLimit
                    totalCount = cached.totalCount
                    endReached = true
                    scanning = false
                    emit()
                    return@launch
                }

                // 用户还在逐字母输入时不要开始扫页：停顿够久才发请求
                delay(NETWORK_DELAY_MS)
                if (token != tk) return@launch
                if (serverMode) {
                    runServerSearch(tk, scanStartPage.coerceAtLeast(1))
                } else {
                    runLocalScan(tk, scanStartPage.coerceAtLeast(1))
                }
            }
    }

    fun restart(preloaded: List<VideoCard>, scanStartPage: Int = 1) {
        if (query.isBlank()) return
        query = query.trim()
        start(query, preloaded, scanStartPage)
    }

    /** 搜索结果滚到底时继续取下一页（local 模式由后台扫描自己推进，这里只处理 server 模式）。 */
    fun loadMore() {
        if (query.isBlank() || scanning || !serverMode || endReached) return
        if (loadMoreJob?.isActive == true) return
        val tk = token
        scanning = true
        emit()
        loadMoreJob =
            scope.launch {
                try {
                    fetchServerPage(tk)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    AppLog.w(TAG, "loadMore failed mid=$mid query=$query", t)
                } finally {
                    if (token == tk) {
                        scanning = false
                        emit()
                    }
                }
            }
    }

    fun cancel() {
        job?.cancel()
        loadMoreJob?.cancel()
        job = null
        loadMoreJob = null
        token++
        query = ""
        resultItems.clear()
        resultKeys.clear()
        scanning = false
        endReached = false
        totalCount = null
        scannedCount = 0
        cappedByScanLimit = false
    }

    private suspend fun runServerSearch(tk: Int, scanStartPage: Int) {
        try {
            fetchServerPage(tk)
            // 兜底：如果服务器把 keyword 当成无效参数、返回的是投稿总数而不是命中数
            //（表现为结果为空但 count 很大），退回全量拼音扫描，宁可慢一点也不出现「搜不到」
            if (token == tk && resultItems.isEmpty() && endReached && (totalCount ?: 0) > 0) {
                AppLog.i(TAG, "keyword search returned nothing for mid=$mid query=$query, fallback to local scan")
                runLocalScan(tk, scanStartPage)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppLog.w(TAG, "server search failed mid=$mid query=$query", t)
        } finally {
            if (token == tk) {
                scanning = false
                emit()
            }
        }
    }

    private suspend fun fetchServerPage(tk: Int): Boolean {
        val pageNum = nextServerPage.coerceAtLeast(1)
        val page = BiliApi.spaceArcSearchPage(mid = mid, pn = pageNum, ps = SERVER_PAGE_SIZE, keyword = query)
        if (token != tk) return false
        nextServerPage = pageNum + 1
        totalCount = page.total.takeIf { it > 0 } ?: totalCount
        endReached = !page.hasMore || page.items.isEmpty()
        page.items.forEach { addResult(it) }
        if (endReached) resultCache[query] = snapshotForCache()
        emit()
        return !endReached
    }

    /**
     * 拼音搜索：按 50 条一页把投稿列表扫完。
     * 不因为「已经命中几条」就停下 —— 目标可能在很后面，提前收敛等于永远搜不到。
     */
    private suspend fun runLocalScan(tk: Int, startPage: Int) {
        // 已经从浏览列表里筛过一遍的整页不再重复请求，从下一个未覆盖的页开始扫
        var pageNum = startPage.coerceAtLeast(1)
        var scannedCards = (pageNum - 1) * LOCAL_PAGE_SIZE
        try {
            while (scope.isActive && token == tk) {
                val page = BiliApi.spaceArcSearchPage(mid = mid, pn = pageNum, ps = LOCAL_PAGE_SIZE)
                if (token != tk) return
                pageNum++
                totalCount = page.total.takeIf { it > 0 } ?: totalCount
                scannedCards += page.items.size
                scannedCount = scannedCards

                val matches =
                    withContext(Dispatchers.Default) {
                        val matcher = PinyinSearch.matcher(query)
                        page.items.filter { matcher(it.title) }
                    }
                if (token != tk) return
                matches.forEach { addResult(it) }
                emit()

                if (!page.hasMore || page.items.isEmpty()) {
                    endReached = true
                    break
                }
                if (scannedCards >= LOCAL_MAX_SCANNED) {
                    cappedByScanLimit = true
                    break
                }
                delay(LOCAL_PAGE_GAP_MS)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppLog.w(TAG, "local scan failed mid=$mid query=$query", t)
        } finally {
            if (token == tk) {
                scanning = false
                if (endReached && !cappedByScanLimit) {
                    resultCache[query] = snapshotForCache()
                }
                emit()
            }
        }
    }

    private fun snapshotForCache(): CachedResult =
        CachedResult(
            items = ArrayList(resultItems),
            scannedCount = scannedCount,
            cappedByScanLimit = cappedByScanLimit,
            totalCount = totalCount,
            serverMode = serverMode,
        )

    private fun addResult(card: VideoCard) {
        val key = card.stableKey()
        if (!resultKeys.add(key)) return
        resultItems.add(card)
    }

    private fun emit() {
        if (query.isBlank()) return
        onUi(
            Ui(
                query = query,
                items = ArrayList(resultItems),
                totalCount = totalCount,
                scannedCount = scannedCount,
                scanning = scanning,
                cappedByScanLimit = cappedByScanLimit,
                canLoadMore = serverMode && !endReached,
                serverMode = serverMode,
            ),
        )
    }

    private fun isHanChar(ch: Char): Boolean =
        ch.code in 0x3400..0x9FFF || ch.code in 0xF900..0xFAFF
}
