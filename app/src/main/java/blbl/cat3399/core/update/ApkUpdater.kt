package blbl.cat3399.core.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.await
import blbl.cat3399.core.net.ipv4OnlyDns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

object ApkUpdater {
    // 定制版：更新源指向本仓库的 GitHub Releases（公开仓库，匿名可查）。
    private const val RELEASES_API_URL = "https://api.github.com/repos/Wksjxak/blblii/releases/latest"
    private const val RELEASES_LIST_URL = "https://api.github.com/repos/Wksjxak/blblii/releases?per_page=20"
    /** 测试通道标记：只有 tag 形如 test-0.1.38.1 的预发布包，测试版才会认。 */
    private const val TEST_TAG_PREFIX = "test-"
    private const val RELEASE_DOWNLOAD_BASE = "https://github.com/Wksjxak/blblii/releases/download"
    private const val DEBUG_APK_URL = "https://github.com/Wksjxak/blblii/releases/latest/download/blbl-android-latest-debug.apk"
    private const val RELEASE_APK_URL = "https://github.com/Wksjxak/blblii/releases/latest/download/blbl-android-latest-release.apk"
    private const val CHANGELOG_URL = "https://raw.githubusercontent.com/Wksjxak/blblii/master/CHANGELOG.md"
    /** 直连失败时套用的国内 GitHub 前缀代理（用于 API 与 APK 下载）。 */
    private const val GH_PROXY_PREFIX = "https://gh-proxy.com/"
    /** 下载线路候选（运行时测速择优，TV 上尤其明显）。 */
    private val DOWNLOAD_MIRRORS =
        listOf("", "https://gh-proxy.com/", "https://ghfast.top/", "https://ghproxy.net/")
    private const val DOWNLOAD_CONNECTIONS = 8
    private const val PROBE_BYTES = 256L * 1024L
    /** 检查更新的回退候选：直连、前缀代理、jsDelivr 上的 CHANGELOG。 */
    private const val CHANGELOG_MIRROR_URL = "https://cdn.jsdelivr.net/gh/Wksjxak/blblii@master/CHANGELOG.md"
    private const val CHANGELOG_PROXY_URL = GH_PROXY_PREFIX + "https://raw.githubusercontent.com/Wksjxak/blblii/master/CHANGELOG.md"
    val TEST_APK_URL: String
        get() = if (BuildConfig.DEBUG) DEBUG_APK_URL else RELEASE_APK_URL
    val TEST_CHANGELOG_URL: String
        get() = CHANGELOG_URL

    private const val COOLDOWN_MS = 5_000L

    @Volatile
    private var lastStartedAtMs: Long = 0L

    private val okHttpLazy: Lazy<OkHttpClient> =
        lazy {
            OkHttpClient.Builder()
                .dns(ipv4OnlyDns { BiliClient.prefs.ipv4OnlyEnabled })
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

    private val okHttp: OkHttpClient
        get() = okHttpLazy.value

    private val probeClient: OkHttpClient by lazy {
        okHttp.newBuilder()
            .callTimeout(6, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    fun evictConnections() {
        if (okHttpLazy.isInitialized()) okHttp.connectionPool.evictAll()
    }

    sealed class Progress {
        data object Connecting : Progress()

        data class Downloading(
            val downloadedBytes: Long,
            val totalBytes: Long?,
            val bytesPerSecond: Long,
            val sourceLabel: String? = null,
        ) : Progress() {
            val percent: Int? =
                totalBytes?.takeIf { it > 0 }?.let { total ->
                    ((downloadedBytes.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                }

            val hint: String =
                buildString {
                    if (totalBytes != null && totalBytes > 0) {
                        append("${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}")
                    } else {
                        append(formatBytes(downloadedBytes))
                    }
                    if (bytesPerSecond > 0) append("（${formatBytes(bytesPerSecond)}/s）")
                    if (!sourceLabel.isNullOrBlank()) append(" · $sourceLabel")
                }
        }
    }

    data class RemoteUpdate(
        val versionName: String,
        val changelog: String,
        /** 该版本 APK 的下载直链（GitHub Releases 资产）；为空时按版本号推导。 */
        val apkUrl: String? = null,
        /** 发布说明里标注的国内 CDN 直链（形如 CDN: https://...），有就优先用。 */
        val cdnUrl: String? = null,
        /** 发布说明里的 Build: N 标记（= 正式包的 versionCode），用于同版本号重新出包时的判断。 */
        val buildNumber: Long = 0L,
        val versions: List<RemoteUpdate> = emptyList(),
    ) {
        val displayChangelog: String
            get() = sanitizeChangelogForDisplay(changelog)
    }

    /** 弹窗里直接显示纯文本：去掉 ### / ** 这类记号，也去掉给程序看的 CDN 行。 */
    fun sanitizeChangelogForDisplay(raw: String): String {
        if (raw.isBlank()) return "暂无更新日志"
        val lines =
            raw.lineSequence()
                .map { it.trimEnd() }
                .filterNot {
                    val t = it.trim()
                    t.startsWith("CDN:") || t.startsWith("CDN：")
                }
                .map { line ->
                    var text = line
                    text = text.replace(Regex("^#{1,6}\\s*"), "")
                    text = text.replace(Regex("^>\\s*"), "")
                    text = text.replace("**", "")
                    text = text.replace(Regex("^[-*]\\s+"), "· ")
                    text
                }
                .toList()
        val collapsed =
            buildString {
                var blankRun = 0
                for (line in lines) {
                    if (line.isBlank()) {
                        blankRun++
                        if (blankRun > 1) continue
                    } else {
                        blankRun = 0
                    }
                    append(line).append('\n')
                }
            }.trim()
        return collapsed.ifBlank { "暂无更新日志" }
    }

    fun markStarted(nowMs: Long = System.currentTimeMillis()) {
        lastStartedAtMs = nowMs
    }

    fun cooldownLeftMs(nowMs: Long = System.currentTimeMillis()): Long {
        val last = lastStartedAtMs
        val left = (last + COOLDOWN_MS) - nowMs
        return left.coerceAtLeast(0)
    }

    suspend fun fetchLatestUpdate(): RemoteUpdate {
        return withContext(Dispatchers.IO) {
            // 测试版（debug 包）只走测试通道，永远不下正式包，避免“测试版被升级成正式版”。
            if (BuildConfig.DEBUG) return@withContext fetchTestChannelUpdate()

            // 1) GitHub API 直连
            runCatching { retryFetch { fetchLatestReleaseOnce(RELEASES_API_URL) } }
                .getOrNull()
                ?.let { return@withContext withVersionHistory(it) }

            // 2) GitHub API 走国内前缀代理
            runCatching { retryFetch { fetchLatestReleaseOnce(GH_PROXY_PREFIX + RELEASES_API_URL) } }
                .getOrNull()
                ?.let { return@withContext withVersionHistory(it) }

            // 3) 只能拿到 CHANGELOG 时：版本号照旧可用，下载地址按版本号推导
            //    （国内直连 api.github.com 常被拦，这一步保证「检查得到新版本」）
            var changelogError: Throwable? = null
            for (url in listOf(CHANGELOG_PROXY_URL, CHANGELOG_MIRROR_URL, TEST_CHANGELOG_URL)) {
                val text = runCatching { fetchText(url) }.getOrElse { changelogError = it; null }
                    ?: continue
                runCatching { parseChangelog(text) .copy(apkUrl = null) }.getOrNull()?.let { update ->
                    return@withContext update.copy(apkUrl = apkUrlFor(update.versionName))
                }
            }
            throw changelogError ?: error("检查更新失败，请检查网络")
        }
    }

    /** 用仓库 CHANGELOG 补全历史版本，供弹窗「上一版 / 下一版」翻看；失败就算了，不影响升级。 */
    private fun withVersionHistory(update: RemoteUpdate): RemoteUpdate {
        if (update.versions.size > 1) return update
        val text =
            runCatching { fetchText(CHANGELOG_PROXY_URL) }.getOrNull()
                ?: runCatching { fetchText(CHANGELOG_MIRROR_URL) }.getOrNull()
                ?: return update
        val parsed = runCatching { parseChangelogVersions(text) }.getOrNull() ?: return update
        if (parsed.isEmpty()) return update
        return update.copy(versions = parsed)
    }

    private suspend fun <T> retryFetch(block: () -> T): T {
        var lastError: Throwable? = null
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                val shouldRetry =
                    attempt < maxAttempts &&
                        (t is IOException || t.message?.startsWith("HTTP ") == true)
                if (!shouldRetry) throw t
                delay(400L * attempt)
            }
        }
        throw lastError ?: error("检查更新失败")
    }

    /** 代理/边缘节点会把 GitHub API、raw 文件缓存住，加时间戳强制拿最新的。 */
    private fun cacheBusted(url: String): String {
        val sep = if (url.contains('?')) '&' else '?'
        return "$url${sep}_ts=${System.currentTimeMillis()}"
    }

    private fun fetchText(url: String): String {
        val req =
            Request.Builder()
                .url(cacheBusted(url))
                .header("Cache-Control", "no-cache, no-store")
                .header("Pragma", "no-cache")
                .header("User-Agent", "blbl-android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
        val res = okHttp.newCall(req).execute()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            return (r.body ?: error("empty body")).string()
        }
    }

    /**
     * 测试通道：读 releases 列表，取 tag 以 test- 开头、带 apk 资产的第一个预发布包。
     * 找不到就返回“当前版本”，界面上显示已是最新，绝不回落到正式渠道。
     */
    private suspend fun fetchTestChannelUpdate(): RemoteUpdate {
        var lastError: Throwable? = null
        for (base in listOf("", GH_PROXY_PREFIX)) {
            val text: String? =
                runCatching { retryFetch { fetchText(base + RELEASES_LIST_URL) } }
                    .getOrElse { lastError = it; null }
            if (text == null) continue
            val arr = runCatching { org.json.JSONArray(text) }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val update = runCatching { parseReleaseObject(obj, TEST_TAG_PREFIX) }.getOrNull() ?: continue
                return update
            }
            return RemoteUpdate(
                versionName = BuildConfig.VERSION_NAME,
                changelog = "测试通道暂时没有新的测试包。",
            )
        }
        throw lastError ?: error("检查更新失败，请检查网络")
    }

    /** 从 GitHub Releases API 取最新正式版：版本号、更新说明、APK 直链。 */
    private fun fetchLatestReleaseOnce(url: String): RemoteUpdate {
        val req =
            Request.Builder()
                .url(cacheBusted(url))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "blbl-android/${BuildConfig.VERSION_NAME}")
                .header("Cache-Control", "no-cache, no-store")
                .header("Pragma", "no-cache")
                .get()
                .build()
        val call = okHttp.newCall(req)
        val res = call.execute()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            return parseReleaseObject(org.json.JSONObject(body.string()), tagPrefix = null)
        }
    }

    /**
     * 解析单个 release JSON。tagPrefix 非空表示只认带该前缀的测试通道标签，
     * 版本号会先去掉前缀再去掉 v 前缀。
     */
    private fun parseReleaseObject(json: org.json.JSONObject, tagPrefix: String?): RemoteUpdate {
        val tag = json.optString("tag_name").trim()
        check(tag.isNotBlank()) { "发布信息缺少版本号" }
        if (tagPrefix != null) {
            if (!tag.startsWith(tagPrefix, ignoreCase = true)) error("不是测试通道的包")
        }
        val versionName = tag.removePrefix(tagPrefix ?: "").removePrefix("v").removePrefix("V").trim()
        check(versionName.isNotBlank()) { "发布信息缺少版本号" }
        val changelog = json.optString("body").trim()
        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                apkUrl = asset.optString("browser_download_url").trim().takeIf { it.isNotBlank() }
                if (apkUrl != null) break
            }
        }
        val buildNumber =
            Regex("""(?im)^\s*Build\s*[:：]\s*([0-9]+)""")
                .find(changelog)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?: 0L
        val cdnUrl =
            Regex("""(?im)^\s*CDN\s*[:：]\s*(https://[^\s)]+)""")
                .find(changelog)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        return RemoteUpdate(
            versionName = versionName,
            changelog = changelog,
            apkUrl = apkUrl ?: apkUrlFor(versionName),
            cdnUrl = cdnUrl,
            buildNumber = buildNumber,
        )
    }

    suspend fun fetchLatestUpdateFromChangelog(
        url: String = TEST_CHANGELOG_URL,
    ): RemoteUpdate {
        return withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            val maxAttempts = 3
            for (attempt in 1..maxAttempts) {
                ensureActive()
                try {
                    return@withContext fetchLatestUpdateOnce(url)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastError = t
                    val shouldRetry =
                        attempt < maxAttempts &&
                            (t is IOException || t.message?.startsWith("HTTP ") == true)
                    if (!shouldRetry) throw t
                    delay(400L * attempt)
                }
            }
            throw lastError ?: IllegalStateException("fetch latest version failed")
        }
    }

    private fun fetchLatestUpdateOnce(url: String): RemoteUpdate {
        val req =
            Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .get()
                .build()
        val call = okHttp.newCall(req)
        val res = call.execute()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            return parseChangelog(body.string())
        }
    }

    internal fun parseChangelog(raw: String): RemoteUpdate {
        val versions = parseChangelogVersions(raw)
        return versions.first().copy(versions = versions)
    }

    internal fun parseChangelogVersions(raw: String): List<RemoteUpdate> {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        check(normalized.isNotBlank()) { "更新日志为空" }

        val lines = normalized.lines()
        val allHeadings =
            lines.withIndex()
                .mapNotNull { (index, line) ->
                    parseVersionHeading(line)?.let { heading -> index to heading }
                }
        check(allHeadings.isNotEmpty()) { "未找到版本标题" }

        val versionLevel = allHeadings.first().second.level
        val headings = allHeadings.filter { (_, heading) -> heading.level == versionLevel }
        return headings.mapIndexed { index, (headingIndex, heading) ->
            val nextHeadingIndex = headings.getOrNull(index + 1)?.first ?: lines.size
            val sectionLines =
                lines.subList(headingIndex + 1, nextHeadingIndex)
                    .dropLastWhile { it.isBlank() }
            val changelog =
                sectionLines
                    .joinToString("\n")
                    .trim()

            RemoteUpdate(
                versionName = heading.versionName,
                changelog = changelog,
            )
        }
    }

    fun apkUrlFor(versionName: String): String {
        val cleanVersion = versionName.trim().removePrefix("v")
        val channel = if (BuildConfig.DEBUG) "debug" else "release"
        return "$RELEASE_DOWNLOAD_BASE/v$cleanVersion/blbl-android-$cleanVersion-$channel.apk"
    }

    private data class VersionHeading(
        val level: Int,
        val versionName: String,
    )

    private fun parseVersionHeading(line: String): VersionHeading? {
        val trimmed = line.trim()
        val match = Regex("""^(#{1,6})\s+\[?v?([0-9]+(?:\.[0-9]+)*(?:[-+][A-Za-z0-9_.-]+)?)\]?(?:[\s（(].*)?$""").matchEntire(trimmed)
            ?: return null
        val level = match.groupValues[1].length
        val versionName = match.groupValues[2].trim()
        if (parseVersion(versionName) == null) return null
        return VersionHeading(level = level, versionName = versionName)
    }

    fun isRemoteNewer(
        update: RemoteUpdate,
        currentVersionName: String = BuildConfig.VERSION_NAME,
        currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    ): Boolean {
        if (isRemoteNewer(update.versionName, currentVersionName)) return true
        // 同版本号重新出包（例如把修复并进当前版本）时，靠 Build 标记判断是不是更新的一包
        val remoteCode = update.buildNumber
        return remoteCode > 0L && remoteCode > currentVersionCode
    }

    fun isRemoteNewer(remoteVersionName: String, currentVersionName: String = BuildConfig.VERSION_NAME): Boolean {
        val remote = parseVersion(remoteVersionName) ?: return false
        val current = parseVersion(currentVersionName) ?: return remoteVersionName.trim() != currentVersionName.trim()
        return compareVersion(remote, current) > 0
    }

    suspend fun downloadApkToCache(
        context: Context,
        url: String = TEST_APK_URL,
        altUrls: List<String> = emptyList(),
        onProgress: (Progress) -> Unit,
    ): File {
        onProgress(Progress.Connecting)

        val candidates =
            buildList {
                altUrls.filter { it.isNotBlank() }.forEach { add(it) }
                if (DOWNLOAD_MIRRORS.any { it.isNotEmpty() && url.startsWith(it) }) {
                    add(url)
                } else {
                    DOWNLOAD_MIRRORS.forEach { prefix -> add(if (prefix.isEmpty()) url else prefix + url) }
                }
            }.distinct()

        // 先各线路抓一小段测速，选最快的线路，再决定走分段并发
        val probes = runCatching { probeMirrors(candidates) }.getOrDefault(emptyList())
        val best = probes.maxByOrNull { it.bytesPerSecond }
        val ordered =
            if (best == null) candidates else listOf(best.url) + candidates.filter { it != best.url }

        if (best != null && best.supportsRange && best.totalBytes > 0) {
            val parallelResult =
                runCatching { downloadApkParallel(context, best, onProgress, labelOf(best.url)) }
                    .getOrElse {
                        if (it is CancellationException) throw it
                        null
                    }
            if (parallelResult != null) return parallelResult
        }

        var lastError: Throwable? = null
        for (candidate in ordered) {
            val file = runCatching { downloadApkOnce(context, candidate, onProgress, labelOf(candidate) + " 单线程") }.getOrElse {
                if (it is CancellationException) throw it
                lastError = it
                null
            }
            if (file != null) return file
        }
        throw lastError ?: error("下载失败")
    }

    private data class MirrorProbe(
        val url: String,
        val totalBytes: Long,
        val supportsRange: Boolean,
        val bytesPerSecond: Long,
    )

    private fun labelOf(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

    /** 每个线路抓 256KB：既确认这条线路通，也顺便量出它的实际速度。 */
    private suspend fun probeMirrors(urls: List<String>): List<MirrorProbe> {
        val results = ArrayList<MirrorProbe>(urls.size)
        for (u in urls) {
            currentCoroutineContext().ensureActive()
            val probe =
                runCatching {
                    val req =
                        Request.Builder()
                            .url(u)
                            .header("Range", "bytes=0-$PROBE_BYTES")
                            .get()
                            .build()
                    val startedAt = System.currentTimeMillis()
                    probeClient.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) return@use null
                        val body = r.body ?: return@use null
                        var read = 0L
                        val buf = ByteArray(16 * 1024)
                        body.byteStream().use { input ->
                            while (read < PROBE_BYTES) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                read += n
                            }
                        }
                        if (read <= 0L) return@use null
                        val elapsedMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                        MirrorProbe(
                            url = u,
                            totalBytes = parseTotalBytes(r.header("Content-Range"), body.contentLength()),
                            supportsRange = r.code == 206,
                            bytesPerSecond = read * 1_000L / elapsedMs,
                        )
                    }
                }.getOrNull() ?: continue
            results.add(probe)
        }
        return results
    }

    private fun parseTotalBytes(contentRange: String?, contentLength: Long): Long {
        val fromRange = contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull()
        if (fromRange != null && fromRange > 0L) return fromRange
        return contentLength.coerceAtLeast(0L)
    }

    /** 分段并发下载：4 条连接各取一段，进度按总和汇报；任何异常都交给外层回退单线程。 */
    private suspend fun downloadApkParallel(
        context: Context,
        probe: MirrorProbe,
        onProgress: (Progress) -> Unit,
        sourceLabel: String? = null,
    ): File? {
        val total = probe.totalBytes
        if (total <= 0L) return null

        val dir = File(context.cacheDir, "test_update").apply { mkdirs() }
        val part = File(dir, "update.apk.part")
        val target = File(dir, "update.apk")
        runCatching { part.delete() }
        runCatching { target.delete() }
        part.createNewFile()
        RandomAccessFile(part, "rw").use { it.setLength(total) }

        val downloaded = AtomicLong(0L)
        val lastEmitAt = AtomicLong(0L)
        val startedAt = System.currentTimeMillis()
        val emitLock = Any()
        fun emitProgress() {
            val now = System.currentTimeMillis()
            synchronized(emitLock) {
                if (now - lastEmitAt.get() < 200L) return
                lastEmitAt.set(now)
            }
            val done = downloaded.get()
            val elapsed = (now - startedAt).coerceAtLeast(1L)
            onProgress(
                Progress.Downloading(
                    downloadedBytes = done,
                    totalBytes = total,
                    bytesPerSecond = done * 1_000L / elapsed,
                    sourceLabel = sourceLabel?.let { "$it ${DOWNLOAD_CONNECTIONS}线程" },
                )
            )
        }

        val connections = if (total >= 8L * 1024L * 1024L) DOWNLOAD_CONNECTIONS else 4
        coroutineScope {
            (0 until connections).map { index ->
                async(Dispatchers.IO) {
                    val start = total * index / connections
                    val end =
                        if (index == connections - 1) total - 1
                        else total * (index + 1) / connections - 1
                    if (end < start) return@async

                    val req =
                        Request.Builder()
                            .url(probe.url)
                            .header("Range", "bytes=$start-$end")
                            .get()
                            .build()
                    okHttp.newCall(req).execute().use { r ->
                        check(r.code == 206) { "该线路不支持分段下载（HTTP ${r.code}）" }
                        val body = r.body ?: error("empty body")
                        RandomAccessFile(part, "rw").use { raf ->
                            raf.seek(start)
                            body.byteStream().use { input ->
                                val buf = ByteArray(32 * 1024)
                                var pos = start
                                while (pos <= end) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    val writeLen = minOf(n.toLong(), end - pos + 1).toInt()
                                    raf.write(buf, 0, writeLen)
                                    pos += writeLen
                                    downloaded.addAndGet(writeLen.toLong())
                                    emitProgress()
                                }
                                check(pos == end + 1) { "分段未下载完整（$start-$end）" }
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        runCatching { FileOutputStream(part, true).use { it.fd.sync() } }
        check(part.length() == total) { "文件大小不符（${part.length()} / $total）" }
        check(part.renameTo(target)) { "rename failed" }
        return target
    }

    private suspend fun downloadApkOnce(
        context: Context,
        url: String,
        onProgress: (Progress) -> Unit,
        sourceLabel: String? = null,
    ): File {
        val dir = File(context.cacheDir, "test_update").apply { mkdirs() }
        val part = File(dir, "update.apk.part")
        val target = File(dir, "update.apk")
        runCatching { part.delete() }
        runCatching { target.delete() }

        val req = Request.Builder().url(url).get().build()
        val call = okHttp.newCall(req)
        val res = call.await()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val total = body.contentLength().takeIf { it > 0 }
            withContext(Dispatchers.IO) {
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buf = ByteArray(32 * 1024)
                        var downloaded = 0L

                        var lastEmitAtMs = 0L
                        var speedAtMs = System.currentTimeMillis()
                        var speedBytes = 0L
                        var bytesPerSecond = 0L

                        while (true) {
                            ensureActive()
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read

                            // Speed estimate (1s window)
                            speedBytes += read
                            val nowMs = System.currentTimeMillis()
                            val speedElapsedMs = nowMs - speedAtMs
                            if (speedElapsedMs >= 1_000) {
                                bytesPerSecond = (speedBytes * 1_000L / speedElapsedMs.coerceAtLeast(1)).coerceAtLeast(0)
                                speedBytes = 0L
                                speedAtMs = nowMs
                            }

                            // UI progress: at most 5 updates per second.
                            if (nowMs - lastEmitAtMs >= 200) {
                                lastEmitAtMs = nowMs
                                onProgress(
                                    Progress.Downloading(
                                        downloadedBytes = downloaded,
                                        totalBytes = total,
                                        bytesPerSecond = bytesPerSecond,
                                        sourceLabel = sourceLabel,
                                    )
                                )
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
        }

        check(part.exists() && part.length() > 0) { "downloaded file is empty" }
        check(part.renameTo(target)) { "rename failed" }
        return target
    }

    fun installApk(context: Context, apkFile: File) {
        val uri = installUriFor(context, apkFile)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        grantInstallerReadPermissions(context, intent, uri)
        context.startActivity(intent)
    }

    @SuppressLint("SetWorldReadable")
    private fun installUriFor(context: Context, apkFile: File): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val authority = "${context.packageName}.fileprovider"
            FileProvider.getUriForFile(context, authority, apkFile)
        } else {
            apkFile.setReadable(true, false)
            Uri.fromFile(apkFile)
        }
    }

    @Suppress("DEPRECATION")
    private fun grantInstallerReadPermissions(
        context: Context,
        intent: Intent,
        uri: Uri,
    ) {
        if (uri.scheme != "content") return

        val installers =
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        for (installer in installers) {
            val packageName = installer.activityInfo?.packageName ?: continue
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        if (b < 1024) return "${b}B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2fGB", gb)
    }

    private fun parseVersion(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("v")
        val digitsOnly =
            cleaned.takeWhile { ch ->
                ch.isDigit() || ch == '.'
            }
        if (digitsOnly.isBlank()) return null
        val parts = digitsOnly.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return nums
    }

    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }
}
