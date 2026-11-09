package blbl.cat3399.feature.following

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.api.video.VideoCollectionKind
import blbl.cat3399.core.api.video.VideoCollectionSection
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.tv.RemoteKeys
import blbl.cat3399.core.ui.ActivityStackLimiter
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.GridSpanPolicy
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.ThemeColor
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.core.ui.enableDpadTabFocus
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.util.Format
import blbl.cat3399.databinding.ActivityUpDetailBinding
import blbl.cat3399.feature.login.QrLoginActivity
import blbl.cat3399.feature.player.PlayerPlaylistContinuation
import blbl.cat3399.feature.player.VideoCardPlaylistPage
import blbl.cat3399.feature.player.buildFreshVideoCardPlaylistContinuation
import blbl.cat3399.feature.video.VideoCardActionController
import blbl.cat3399.feature.video.VideoCardAdapter
import blbl.cat3399.feature.video.VideoCardDismissBehavior
import blbl.cat3399.feature.video.VideoCardVisibilityFilter
import blbl.cat3399.feature.video.buildPagedVideoCardPlaybackHandle
import blbl.cat3399.feature.video.buildVideoCardPlaybackHandle
import blbl.cat3399.feature.video.defaultVideoCardPlaylistItem
import blbl.cat3399.feature.video.openVideoDetailFromPlaybackHandle
import blbl.cat3399.feature.video.openVideoFromPlaybackHandle
import blbl.cat3399.feature.video.removeVideoCardAndRestoreFocus
import com.google.android.material.R as MaterialR
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UpDetailActivity : BaseActivity() {
    private lateinit var binding: ActivityUpDetailBinding

    private lateinit var archiveAdapter: VideoCardAdapter
    private lateinit var dynamicAdapter: VideoCardAdapter
    private lateinit var sectionAdapter: UpDetailSectionAdapter

    private lateinit var archiveLayoutManager: GridLayoutManager
    private lateinit var sectionsLayoutManager: LinearLayoutManager

    private val mid: Long by lazy { intent.getLongExtra(EXTRA_MID, 0L) }

    private enum class UpTab { ARCHIVE, DYNAMIC, COLLECTION_SERIES }

    private var currentTab: UpTab = UpTab.ARCHIVE

    private var isFollowed: Boolean = false
    private var followActionInFlight: Boolean = false
    private var loadedInitialInfo: Boolean = false
    private var headerRequestToken: Int = 0
    private var appBarVerticalOffset: Int = 0
    private var pendingFocusHeaderAfterExpand: Boolean = false
    private var tabSwitchRequestToken: Int = 0

    private val loadedArchiveStableKeys = HashSet<String>()
    private var archiveIsLoadingMore: Boolean = false
    private var archiveEndReached: Boolean = false
    private var archiveNextPage: Int = 1
    private var archiveRequestToken: Int = 0

    // 动态栏：offset 分页，单次只拉一页（maxPages=1），滚动到底再拉下一页
    private val loadedDynamicStableKeys = HashSet<String>()
    private var dynamicIsLoadingMore: Boolean = false
    private var dynamicEndReached: Boolean = false
    private var dynamicNextOffset: String? = null
    private var dynamicRequestToken: Int = 0
    private var pendingFocusFirstDynamicItemAfterLoad: Boolean = false

    // 浏览态（未搜索）的原始投稿与展示列表，搜索退出时用来还原列表
    private val archiveBrowseItems = ArrayList<VideoCard>()
    private val archiveBrowseKeys = HashSet<String>()
    private val archiveVisibleItems = ArrayList<VideoCard>()

    private var archiveSearchQuery: String = ""
    private var archiveSearchDebounceJob: Job? = null
    private var archiveSearchController: UpArchiveSearchController? = null
    private var archiveSearchUi: UpArchiveSearchController.Ui? = null
    private var archiveSearchSubmittedQuery: String = ""
    private var archiveSearchSubmittedCount: Int = 0

    private val seasonsSeriesSections = ArrayList<UpDetailSection>()
    private val loadedSectionStableIds = HashSet<String>()
    private var seasonsSeriesIsLoadingMore: Boolean = false
    private var seasonsSeriesEndReached: Boolean = false
    private var seasonsSeriesNextPage: Int = 1
    private var seasonsSeriesRequestToken: Int = 0

    private data class SectionPagingState(
        var nextPage: Int = 1,
        var isLoading: Boolean = false,
        var endReached: Boolean = false,
        var totalCount: Int? = null,
        val loadedBvids: HashSet<String> = HashSet(),
    )

    private val sectionPagingStates = HashMap<String, SectionPagingState>()
    private val sectionPendingAdvanceIndex = HashMap<String, Int>()

    private var pendingFocusFirstArchiveItemAfterLoad: Boolean = false
    private var pendingFocusFirstSectionItemAfterLoad: Boolean = false
    private var forceFocusFirstContentAfterRefresh: Boolean = false
    private var archiveGridController: DpadGridController? = null
    private var focusListener: android.view.ViewTreeObserver.OnGlobalFocusChangeListener? = null
    private var didRequestInitialTab0Focus: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityStackLimiter.register(group = ACTIVITY_STACK_GROUP, activity = this, maxDepth = ACTIVITY_STACK_MAX_DEPTH)
        binding = ActivityUpDetailBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        binding.swipeRefresh.isNestedScrollingEnabled = true

        if (mid <= 0L) {
            AppToast.show(this, "无效的 UP 主 mid")
            finish()
            return
        }

        archiveLayoutManager = GridLayoutManager(this, spanCountForWidth())
        sectionsLayoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)

        setupHeaderButtons()
        prefillHeaderFromIntent()
        setupAppBar()
        setupAdapters()
        setupTabs()
        setupArchiveSearch()

        binding.recycler.setHasFixedSize(true)
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        applyRecyclerForCurrentTab()
        installFocusListener()

        binding.swipeRefresh.setOnRefreshListener { refreshCurrentTab() }
        refreshCurrentTab(initial = true)
    }

    override fun onDestroy() {
        uninstallFocusListener()
        archiveGridController?.release()
        archiveGridController = null
        ActivityStackLimiter.unregister(group = ACTIVITY_STACK_GROUP, activity = this)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        archiveAdapter.invalidateSizing()
        sectionAdapter.invalidateSizing()

        if (currentTab == UpTab.ARCHIVE) {
            archiveLayoutManager.spanCount = spanCountForWidth()
            if (archiveSearchQuery.isBlank() &&
                !binding.swipeRefresh.isRefreshing &&
                archiveAdapter.itemCount == 0
            ) {
                resetAndLoadArchive()
            }
        } else if (currentTab == UpTab.DYNAMIC) {
            archiveLayoutManager.spanCount = spanCountForWidth()
            if (!binding.swipeRefresh.isRefreshing && dynamicAdapter.itemCount == 0) {
                resetAndLoadDynamic()
            }
        } else {
            if (!binding.swipeRefresh.isRefreshing && sectionAdapter.itemCount == 0) {
                ensureSeasonsSeriesLoadedIfNeeded()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        // Avoid showing focus highlight when user entered via touch; rely on first DPAD nav key to
        // establish focus in that case (see dispatchKeyEvent()).
        if (hasFocus && !binding.root.isInTouchMode) {
            scheduleInitialTab0Focus()
            ensureInitialFocus()
        }
        if (hasFocus) maybeConsumePendingFocusFirstContentAfterLoad()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 搜索态按一次返回：先清空搜索回到正常列表，再按才退出页面
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            event.keyCode == KeyEvent.KEYCODE_BACK &&
            currentTab == UpTab.ARCHIVE &&
            archiveSearchQuery.isNotBlank()
        ) {
            clearArchiveSearch()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && RemoteKeys.isRefreshKey(event.keyCode)) {
            if (binding.swipeRefresh.isRefreshing) return true
            refreshCurrentTab()
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            val focused = currentFocus
            if (focused != null && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
                val next = focused.focusSearch(android.view.View.FOCUS_UP)
                if (next == null || !FocusTreeUtils.isDescendantOf(next, binding.recycler)) {
                    // Only consume when we really moved focus; otherwise let the framework attempt
                    // default focus-search (better than trapping DPAD_UP inside the grid).
                    return focusTabsFromContentEdge()
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null && isNavKey(event.keyCode)) {
            ensureInitialFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setupHeaderButtons() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return@setOnKeyListener false
            focusSelectedTab()
            true
        }

        binding.btnFollow.setOnClickListener { onFollowClicked() }
        binding.btnFollow.isFocusableInTouchMode = true
        binding.btnFollow.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return@setOnKeyListener false
            focusSelectedTab()
            true
        }
    }

    private fun setupAppBar() {
        binding.appBar.addOnOffsetChangedListener(
            AppBarLayout.OnOffsetChangedListener { _, verticalOffset ->
                appBarVerticalOffset = verticalOffset
                if (pendingFocusHeaderAfterExpand && verticalOffset == 0) {
                    pendingFocusHeaderAfterExpand = false
                    val focused = currentFocus
                    if (focused == null || FocusTreeUtils.isDescendantOf(focused, binding.tabLayout)) {
                        focusHeaderPreferred()
                    }
                }
            },
        )
    }

    private fun installFocusListener() {
        if (focusListener != null) return
        focusListener =
            android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus == null) return@OnGlobalFocusChangeListener
                if (FocusTreeUtils.isDescendantOf(newFocus, binding.recycler)) {
                    collapseHeaderForContent()
                }
            }.also { binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(it) }
    }

    private fun uninstallFocusListener() {
        focusListener?.let { binding.root.viewTreeObserver.removeOnGlobalFocusChangeListener(it) }
        focusListener = null
    }

    private fun focusHeaderPreferred() {
        if (binding.btnFollow.isVisible) {
            binding.btnFollow.requestFocus()
        } else {
            binding.btnBack.requestFocus()
        }
    }

    private fun requestExpandHeaderAndFocus() {
        if (appBarVerticalOffset == 0) {
            focusHeaderPreferred()
            return
        }
        pendingFocusHeaderAfterExpand = true
        binding.appBar.setExpanded(true, true)
    }

    private fun collapseHeaderForContent() {
        val total = binding.appBar.totalScrollRange
        if (total <= 0) return
        val fullyCollapsedOffset = -total
        if (appBarVerticalOffset > fullyCollapsedOffset) binding.appBar.setExpanded(false, true)
    }

    private fun prefillHeaderFromIntent() {
        binding.tvName.text = intent.getStringExtra(EXTRA_NAME).orEmpty()
        binding.tvSign.text = intent.getStringExtra(EXTRA_SIGN).orEmpty()
        binding.tvSign.isVisible = binding.tvSign.text.isNotBlank()
        binding.tvFansCount.text = "-"
        binding.tvLikesCount.text = "-"
        binding.tvPlayCount.text = "-"
        val avatar = intent.getStringExtra(EXTRA_AVATAR)
        blbl.cat3399.core.image.ImageLoader.loadInto(binding.ivAvatar, blbl.cat3399.core.image.ImageUrl.avatar(avatar))
    }

    private fun setupAdapters() {
        val archiveActionController =
            VideoCardActionController(
                context = this,
                scope = lifecycleScope,
                dismissBehavior = VideoCardDismissBehavior.LocalNotInterested,
                onOpenDetail = { _, pos ->
                    openVideoDetailFromPlaybackHandle(
                        playbackHandle = archivePlaybackHandle(),
                        position = pos,
                    )
                },
                onOpenUp = { card -> openUpDetailForCard(card) },
                onCardRemoved = { stableKey ->
                    binding.recycler.removeVideoCardAndRestoreFocus(
                        adapter = archiveAdapter,
                        stableKey = stableKey,
                        isAlive = { !isFinishing && !isDestroyed && currentTab == UpTab.ARCHIVE },
                    )
                },
            )
        archiveAdapter =
            VideoCardAdapter(
                onClick = { _, pos ->
                    openVideoFromPlaybackHandle(
                        playbackHandle = archivePlaybackHandle(),
                        position = pos,
                        openDetailBeforePlay = BiliClient.prefs.playerOpenDetailBeforePlay,
                    )
                },
                actionDelegate = archiveActionController,
            )

        val dynamicActionController =
            VideoCardActionController(
                context = this,
                scope = lifecycleScope,
                dismissBehavior = VideoCardDismissBehavior.LocalNotInterested,
                onOpenDetail = { _, pos ->
                    openVideoDetailFromPlaybackHandle(
                        playbackHandle = dynamicPlaybackHandle(),
                        position = pos,
                    )
                },
                onOpenUp = { card -> openUpDetailForCard(card) },
                onCardRemoved = { stableKey ->
                    binding.recycler.removeVideoCardAndRestoreFocus(
                        adapter = dynamicAdapter,
                        stableKey = stableKey,
                        isAlive = { !isFinishing && !isDestroyed && currentTab == UpTab.DYNAMIC },
                    )
                },
            )
        dynamicAdapter =
            VideoCardAdapter(
                onClick = { _, pos ->
                    openVideoFromPlaybackHandle(
                        playbackHandle = dynamicPlaybackHandle(),
                        position = pos,
                        openDetailBeforePlay = BiliClient.prefs.playerOpenDetailBeforePlay,
                    )
                },
                actionDelegate = dynamicActionController,
            )

        sectionAdapter =
            UpDetailSectionAdapter(
                onVideoClick = { section, _, index ->
                    openVideoFromPlaybackHandle(
                        playbackHandle = buildSectionPlaybackHandle(section),
                        position = index,
                        openDetailBeforePlay = BiliClient.prefs.playerOpenDetailBeforePlay,
                    )
                },
                onRequestLoadMore = { section, requestedNextIndex ->
                    onSectionLoadMoreRequested(section, requestedNextIndex)
                },
                onRequestMoveToTabs = { focusTabsFromContentEdge() },
            )
    }

    private fun setupTabs() {
        binding.tabLayout.removeAllTabs()
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.up_tab_archive))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.up_tab_dynamic))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.up_tab_collection_series))

        binding.tabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    val next =
                        when (tab.position) {
                            1 -> UpTab.DYNAMIC
                            2 -> UpTab.COLLECTION_SERIES
                            else -> UpTab.ARCHIVE
                        }
                    switchTab(next)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            },
        )

        val tabLayout = binding.tabLayout
        tabLayout.postIfAlive(isAlive = { !isFinishing && !isDestroyed }) {
            tabLayout.enableDpadTabFocus(selectOnFocusProvider = { BiliClient.prefs.tabSwitchFollowsFocus })
            val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return@postIfAlive
            for (i in 0 until tabStrip.childCount) {
                val tabView = tabStrip.getChildAt(i)
                tabView.isFocusableInTouchMode = true
                tabView.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            requestExpandHeaderAndFocus()
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            collapseHeaderForContent()
                            requestFocusFirstContentFromTab()
                            true
                        }

                        else -> false
                    }
                }
            }
        }
    }

    private fun setupArchiveSearch() {
        archiveSearchController =
            UpArchiveSearchController(
                scope = lifecycleScope,
                mid = mid,
                onUi = ::onArchiveSearchUi,
            )

        binding.etArchiveSearch.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString().orEmpty()
                    if (q == archiveSearchQuery) return
                    archiveSearchQuery = q
                    binding.btnArchiveSearchClear.isVisible = q.isNotEmpty()
                    scheduleArchiveSearch(q)
                }
            },
        )
        binding.etArchiveSearch.setOnEditorActionListener { _, _, _ ->
            hideArchiveSearchKeyboard()
            focusArchiveFirstItemOrGrid()
            true
        }
        binding.etArchiveSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    focusSelectedTab()
                    true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> focusArchiveFirstItemOrGrid() || false

                else -> false
            }
        }

        binding.btnArchiveSearchClear.isFocusable = true
        binding.btnArchiveSearchClear.setOnClickListener { clearArchiveSearch() }
        binding.btnArchiveSearchClear.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    focusSelectedTab()
                    true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> focusArchiveFirstItemOrGrid() || false

                else -> false
            }
        }

        binding.llArchiveSearch.isVisible = currentTab == UpTab.ARCHIVE
    }

    private fun clearArchiveSearch() {
        // setText 会同步触发 TextWatcher，清空与退出搜索都在那条路径里完成
        if (binding.etArchiveSearch.text.toString().isEmpty()) {
            exitArchiveSearch()
        } else {
            binding.etArchiveSearch.setText("")
        }
        binding.etArchiveSearch.requestFocus()
    }

    private fun focusArchiveFirstItemOrGrid(): Boolean {
        if (archiveAdapter.itemCount <= 0) return false
        focusGridAt(0)
        return true
    }

    private fun hideArchiveSearchKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etArchiveSearch.windowToken, 0)
    }

    /** 输入防抖：停手 220ms 才真正发起搜索，避免遥控器每敲一个字母就打一次接口。 */
    private fun scheduleArchiveSearch(rawQuery: String) {
        archiveSearchDebounceJob?.cancel()
        archiveSearchDebounceJob = null
        val q = rawQuery.trim()
        if (q.isEmpty()) {
            exitArchiveSearch()
            return
        }
        binding.tvArchiveSearchStatus.isVisible = true
        binding.tvArchiveSearchStatus.text = "正在搜索…"
        archiveSearchDebounceJob =
            lifecycleScope.launch {
                delay(220)
                if (q != archiveSearchQuery.trim()) return@launch
                startArchiveSearch(q)
            }
    }

    private fun startArchiveSearch(query: String) {
        // 已加载的投稿先交给控制器做「秒出」过滤，网络结果回来再合并；
        // 拼音模式从「已覆盖过的整页」之后开始扫，避免重复请求同一批投稿
        val preloaded = archiveBrowseItems.toList()
        val coveredPages = preloaded.size / 50
        archiveSearchController?.start(query, preloaded, scanStartPage = coveredPages + 1)
        archiveGridController?.clearPendingFocusAfterLoadMore()
    }

    private fun exitArchiveSearch() {
        archiveSearchController?.cancel()
        archiveSearchUi = null
        archiveSearchSubmittedQuery = ""
        archiveSearchSubmittedCount = 0
        binding.tvArchiveSearchStatus.isVisible = false
        binding.tvArchiveSearchStatus.text = ""
        if (currentTab != UpTab.ARCHIVE) return
        archiveAdapter.submit(archiveVisibleItems.toList())
        if (archiveVisibleItems.isEmpty() && !binding.swipeRefresh.isRefreshing) {
            resetAndLoadArchive()
        }
    }

    private fun onArchiveSearchUi(ui: UpArchiveSearchController.Ui) {
        if (ui.query != archiveSearchQuery.trim()) return
        archiveSearchUi = ui
        if (currentTab != UpTab.ARCHIVE) return
        applyArchiveSearchItems(ui)
        archiveGridController?.clearPendingFocusAfterLoadMore()
        binding.btnArchiveSearchClear.isVisible = true
        binding.tvArchiveSearchStatus.isVisible = true
        binding.tvArchiveSearchStatus.text = archiveSearchStatusText(ui)
    }

    /**
     * 搜索结果落地：同一次搜索里结果只增不减，用 append 增量插入，
     * 避免每翻一页 submit 一次把 TV 焦点和滚动位置顶回顶部。
     */
    private fun applyArchiveSearchItems(ui: UpArchiveSearchController.Ui) {
        val sameQuery = ui.query == archiveSearchSubmittedQuery
        val submitted = archiveSearchSubmittedCount
        when {
            !sameQuery -> {
                archiveSearchSubmittedQuery = ui.query
                archiveAdapter.submit(ui.items)
                archiveSearchSubmittedCount = ui.items.size
            }
            ui.items.size > submitted -> {
                archiveAdapter.append(ui.items.subList(submitted, ui.items.size))
                archiveSearchSubmittedCount = ui.items.size
            }
            ui.items.size < submitted -> {
                archiveAdapter.submit(ui.items)
                archiveSearchSubmittedCount = ui.items.size
            }
        }
    }

    private fun archiveSearchStatusText(ui: UpArchiveSearchController.Ui): String {
        val found = ui.items.size
        if (ui.serverMode) {
            return when {
                ui.scanning && found == 0 -> "正在搜索该 UP 的全部投稿…"
                found == 0 -> "没有找到匹配的视频"
                ui.canLoadMore -> "已找到 $found 个（继续下滑查看更多）"
                else -> "共 $found 个匹配视频"
            }
        }
        return when {
            ui.scanning -> "正在搜索…已找到 $found 个（已扫描 ${ui.scannedCount} 个视频）"
            ui.cappedByScanLimit -> "已找到 $found 个（只扫描了前 ${ui.scannedCount} 个视频）"
            found == 0 -> "没有找到匹配的视频"
            else -> "已找到 $found 个（${ui.scannedCount} 个视频已全部扫描）"
        }
    }

    private fun scheduleInitialTab0Focus() {
        // Mirror VideoDetailActivity: post a single initial focus request after the first layout,
        // so focus lands on the primary entry control (Tab0) instead of the header button.
        val tabLayout = binding.tabLayout
        tabLayout.postIfAlive(isAlive = { !isFinishing && !isDestroyed }) {
            // Don't create focus highlight when entering via touch.
            if (binding.root.isInTouchMode) return@postIfAlive
            if (didRequestInitialTab0Focus) return@postIfAlive
            didRequestInitialTab0Focus = true

            tabLayout.getTabAt(0)?.select()
            requestFocusSelectedTabView()
        }
    }

    private fun switchTab(next: UpTab) {
        if (currentTab == next) return
        currentTab = next
        // Tab switching can be triggered by focus changes while RecyclerView is recycling children
        // (e.g. during generic motion scroll). Switching adapter/layout manager synchronously at that
        // moment can crash with "Cannot call removeView(At) within removeView(At)".
        val token = ++tabSwitchRequestToken
        binding.recycler.postIfAlive(isAlive = { !isFinishing && !isDestroyed }) {
            if (token != tabSwitchRequestToken) return@postIfAlive
            applyRecyclerForCurrentTab()
            ensureTabLoadedIfNeeded()
        }
    }

    private fun applyRecyclerForCurrentTab() {
        binding.recycler.clearOnScrollListeners()
        when (currentTab) {
            UpTab.ARCHIVE -> {
                binding.recycler.adapter = archiveAdapter
                binding.recycler.layoutManager = archiveLayoutManager
                archiveLayoutManager.spanCount = spanCountForWidth()
                binding.llArchiveSearch.isVisible = true
                // 从「合集」切回来时把搜索结果重新挂上，避免列表变成浏览态
                archiveSearchUi?.let { ui ->
                    if (archiveSearchQuery.isNotBlank()) {
                        archiveSearchSubmittedQuery = ""
                        archiveSearchSubmittedCount = 0
                        applyArchiveSearchItems(ui)
                    }
                }

                binding.recycler.addOnScrollListener(
                    object : RecyclerView.OnScrollListener() {
                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                            if (dy > 0) collapseHeaderForContent()
                            if (dy <= 0) return
                            val searching = archiveSearchQuery.isNotBlank()
                            if (!searching && (archiveIsLoadingMore || archiveEndReached)) return
                            val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                            val lastVisible = lm.findLastVisibleItemPosition()
                            val total = archiveAdapter.itemCount
                            if (total <= 0) return
                            if (total - lastVisible - 1 <= 8) {
                                if (searching) archiveSearchController?.loadMore() else loadMoreArchive()
                            }
                        }
                    },
                )

                installArchiveGridController()
            }

            UpTab.DYNAMIC -> {
                binding.llArchiveSearch.isVisible = false
                binding.recycler.adapter = dynamicAdapter
                binding.recycler.layoutManager = archiveLayoutManager
                archiveLayoutManager.spanCount = spanCountForWidth()

                binding.recycler.addOnScrollListener(
                    object : RecyclerView.OnScrollListener() {
                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                            if (dy > 0) collapseHeaderForContent()
                            if (dy <= 0) return
                            if (dynamicIsLoadingMore || dynamicEndReached) return
                            val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                            val lastVisible = lm.findLastVisibleItemPosition()
                            val total = dynamicAdapter.itemCount
                            if (total <= 0) return
                            if (total - lastVisible - 1 <= 8) loadMoreDynamic()
                        }
                    },
                )

                installArchiveGridController()
            }

            UpTab.COLLECTION_SERIES -> {
                binding.llArchiveSearch.isVisible = false
                binding.recycler.adapter = sectionAdapter
                binding.recycler.layoutManager = sectionsLayoutManager
                sectionAdapter.replaceAll(seasonsSeriesSections)
                installSeasonsSeriesScrollListener()
                releaseArchiveGridController()
            }
        }
    }

    private fun installSeasonsSeriesScrollListener() {
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy > 0) collapseHeaderForContent()
                    if (dy <= 0) return
                    if (seasonsSeriesIsLoadingMore || seasonsSeriesEndReached) return
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = recyclerView.adapter?.itemCount ?: 0
                    if (total <= 0) return
                    if (total - lastVisible - 1 <= 2) loadMoreSeasonsSeries()
                }
            },
        )
    }

    private fun ensureTabLoadedIfNeeded() {
        when (currentTab) {
            UpTab.ARCHIVE -> {
                if (archiveSearchQuery.isNotBlank()) return
                if (!binding.swipeRefresh.isRefreshing && archiveAdapter.itemCount == 0) {
                    resetAndLoadArchive()
                }
            }

            UpTab.DYNAMIC -> {
                if (!binding.swipeRefresh.isRefreshing && dynamicAdapter.itemCount == 0) {
                    resetAndLoadDynamic()
                }
            }

            UpTab.COLLECTION_SERIES -> ensureSeasonsSeriesLoadedIfNeeded()
        }
    }

    private fun ensureSeasonsSeriesLoadedIfNeeded() {
        if (seasonsSeriesIsLoadingMore) return
        if (seasonsSeriesSections.isNotEmpty()) return
        resetAndLoadSeasonsSeries()
    }

    private fun refreshCurrentTab(initial: Boolean = false) {
        // 搜索态下拉刷新：重跑这次搜索，不去动浏览列表
        if (currentTab == UpTab.ARCHIVE && archiveSearchQuery.isNotBlank()) {
            binding.swipeRefresh.isRefreshing = false
            val preloaded = archiveBrowseItems.toList()
            archiveSearchController?.restart(preloaded, scanStartPage = preloaded.size / 50 + 1)
            return
        }
        if (!initial) {
            forceFocusFirstContentAfterRefresh = true
            binding.swipeRefresh.isRefreshing = true
        }
        // Avoid framework fallback focus during adapter resets.
        if (forceFocusFirstContentAfterRefresh) {
            when (currentTab) {
                UpTab.ARCHIVE -> archiveGridController?.parkFocusForDataSetReset()
                UpTab.DYNAMIC -> {
                    pendingFocusFirstDynamicItemAfterLoad = true
                    parkFocusInRecyclerForDataSetReset()
                }
                UpTab.COLLECTION_SERIES -> parkFocusInRecyclerForDataSetReset()
            }
        }
        loadHeader()
        when (currentTab) {
            UpTab.ARCHIVE -> resetAndLoadArchive()
            UpTab.DYNAMIC -> resetAndLoadDynamic()
            UpTab.COLLECTION_SERIES -> resetAndLoadSeasonsSeries()
        }
    }

    private fun ensureInitialFocus() {
        if (currentFocus != null) return
        if (focusSelectedTab()) return
        if (requestFocusFirstContentIfPossible()) return
        if (appBarVerticalOffset == 0 && binding.btnFollow.isVisible) {
            binding.btnFollow.requestFocus()
            return
        }
        binding.btnBack.requestFocus()
    }

    private fun requestFocusSelectedTabView(): Boolean {
        val tabLayout = binding.tabLayout
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        if (tabStrip.childCount <= 0) return false
        val selected = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0

        fun tryFocusTabView(view: android.view.View?): Boolean {
            if (view == null) return false
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            return view.requestFocus()
        }

        if (tryFocusTabView(tabStrip.getChildAt(selected))) return true
        for (i in 0 until tabStrip.childCount) {
            if (tryFocusTabView(tabStrip.getChildAt(i))) return true
        }
        return false
    }

    private fun focusSelectedTab(): Boolean {
        if (requestFocusSelectedTabView()) return true
        return binding.btnBack.requestFocus()
    }

    private fun focusTabsFromContentEdge(): Boolean {
        return requestFocusSelectedTabView()
    }

    private fun requestFocusFirstContentFromTab(): Boolean {
        if (currentTab == UpTab.ARCHIVE && binding.llArchiveSearch.isVisible) {
            binding.etArchiveSearch.requestFocus()
            return true
        }
        when (currentTab) {
            UpTab.ARCHIVE -> {
                if (archiveAdapter.itemCount > 0) {
                    focusGridAt(0)
                    return true
                }
                pendingFocusFirstArchiveItemAfterLoad = true
                if (!binding.swipeRefresh.isRefreshing) resetAndLoadArchive()
                return true
            }

            UpTab.DYNAMIC -> {
                if (dynamicAdapter.itemCount > 0) {
                    focusGridAt(0)
                    return true
                }
                pendingFocusFirstDynamicItemAfterLoad = true
                if (!binding.swipeRefresh.isRefreshing) resetAndLoadDynamic()
                return true
            }

            UpTab.COLLECTION_SERIES -> {
                if (seasonsSeriesSections.isNotEmpty()) {
                    focusFirstSectionVideo()
                    return true
                }
                pendingFocusFirstSectionItemAfterLoad = true
                ensureSeasonsSeriesLoadedIfNeeded()
                return true
            }
        }
    }

    private fun requestFocusFirstContentIfPossible(): Boolean {
        return when (currentTab) {
            UpTab.ARCHIVE -> {
                if (archiveAdapter.itemCount <= 0) return false
                focusGridAt(0)
                true
            }

            UpTab.DYNAMIC -> {
                if (dynamicAdapter.itemCount <= 0) return false
                focusGridAt(0)
                true
            }

            UpTab.COLLECTION_SERIES -> {
                if (seasonsSeriesSections.isEmpty()) return false
                focusFirstSectionVideo()
                true
            }
        }
    }

    private fun loadHeader() {
        val token = ++headerRequestToken
        binding.tvFansCount.text = "-"
        binding.tvLikesCount.text = "-"
        binding.tvPlayCount.text = "-"
        lifecycleScope.launch {
            val info =
                try {
                    BiliApi.spaceAccInfo(mid)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    AppLog.w("UpDetail", "loadHeader failed mid=$mid", t)
                    null
                }
            if (token != headerRequestToken) return@launch

            if (info != null) {
                loadedInitialInfo = true
                binding.tvName.text = info.name
                binding.tvSign.text = info.sign.orEmpty()
                binding.tvSign.isVisible = !info.sign.isNullOrBlank()
                blbl.cat3399.core.image.ImageLoader.loadInto(binding.ivAvatar, blbl.cat3399.core.image.ImageUrl.avatar(info.faceUrl))
                isFollowed = info.isFollowed
                updateFollowUi()
            } else if (!loadedInitialInfo) {
                binding.tvName.text = binding.tvName.text.takeIf { it.isNotBlank() } ?: "加载失败"
            }

            val relation =
                try {
                    BiliApi.relationStat(mid)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    null
                }
            if (relation != null) {
                if (token != headerRequestToken) return@launch
                binding.tvFansCount.text = Format.count(relation.follower)
            }

            if (BiliClient.cookies.hasSessData()) {
                val upstat =
                    try {
                        BiliApi.spaceUpStat(mid)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        null
                    }
                if (upstat != null) {
                    if (token != headerRequestToken) return@launch
                    binding.tvLikesCount.text = Format.count(upstat.likes)
                    binding.tvPlayCount.text = Format.count(upstat.archiveView)
                }
            }
        }
    }

    private fun resetAndLoadArchive() {
        if (forceFocusFirstContentAfterRefresh) {
            pendingFocusFirstArchiveItemAfterLoad = true
            archiveGridController?.parkFocusForDataSetReset()
        } else {
            val focused = currentFocus
            if (focused != null && focused != binding.recycler && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
                pendingFocusFirstArchiveItemAfterLoad = true
            }
        }
        archiveRequestToken++
        loadedArchiveStableKeys.clear()
        archiveBrowseItems.clear()
        archiveBrowseKeys.clear()
        archiveVisibleItems.clear()
        archiveNextPage = 1
        archiveEndReached = false
        archiveIsLoadingMore = false
        archiveGridController?.clearPendingFocusAfterLoadMore()
        archiveAdapter.submit(emptyList())
        binding.swipeRefresh.isRefreshing = true
        loadMoreArchive(isRefresh = true)
    }

    private fun loadMoreArchive(isRefresh: Boolean = false) {
        if (archiveSearchQuery.isNotBlank()) {
            archiveSearchController?.loadMore()
            return
        }
        if (archiveIsLoadingMore || archiveEndReached) return
        val token = archiveRequestToken
        val targetPage = archiveNextPage.coerceAtLeast(1)
        archiveIsLoadingMore = true
        lifecycleScope.launch {
            try {
                val page =
                    BiliApi.spaceArcSearchPage(
                        mid = mid,
                        pn = targetPage,
                        ps = 30,
                    )
                if (token != archiveRequestToken) return@launch

                archiveNextPage = targetPage + 1
                archiveEndReached = page.items.isEmpty() || !page.hasMore

                val visibleItems = VideoCardVisibilityFilter.filterVisibleFresh(page.items, loadedArchiveStableKeys)
                visibleItems.forEach { loadedArchiveStableKeys.add(it.stableKey()) }
                // 原始投稿（含被屏蔽项）也留一份：搜索时拼音过滤需要完整标题集合
                if (isRefresh) archiveBrowseItems.clear()
                val newRaw = page.items.filter { archiveBrowseKeys.add(it.stableKey()) }
                archiveBrowseItems.addAll(newRaw)
                if (isRefresh) archiveVisibleItems.clear()
                archiveVisibleItems.addAll(visibleItems)
                if (isRefresh) archiveAdapter.submit(visibleItems) else archiveAdapter.append(visibleItems)

                binding.recycler.post {
                    maybeConsumePendingFocusFirstContentAfterLoad()
                    archiveGridController?.consumePendingFocusAfterLoadMore()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("UpDetail", "loadArchive failed mid=$mid page=$targetPage", t)
                AppToast.show(this@UpDetailActivity, "加载失败，可查看 Logcat(标签 BLBL)")
            } finally {
                if (token == archiveRequestToken) binding.swipeRefresh.isRefreshing = false
                archiveIsLoadingMore = false
            }
        }
    }

    private fun resetAndLoadDynamic() {
        if (forceFocusFirstContentAfterRefresh) {
            pendingFocusFirstDynamicItemAfterLoad = true
            parkFocusInRecyclerForDataSetReset()
        } else {
            val focused = currentFocus
            if (focused != null && focused != binding.recycler && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
                pendingFocusFirstDynamicItemAfterLoad = true
            }
        }
        dynamicRequestToken++
        loadedDynamicStableKeys.clear()
        dynamicNextOffset = null
        dynamicEndReached = false
        dynamicIsLoadingMore = false
        dynamicAdapter.submit(emptyList())
        binding.swipeRefresh.isRefreshing = true
        loadMoreDynamic(isRefresh = true)
    }

    private fun loadMoreDynamic(isRefresh: Boolean = false) {
        if (dynamicIsLoadingMore || dynamicEndReached) return
        val token = dynamicRequestToken
        val offset = dynamicNextOffset
        dynamicIsLoadingMore = true
        lifecycleScope.launch {
            try {
                // 单次只拉一页（maxPages=1）：滚动到底再拉下一页，避免一上来追好几页
                val page = BiliApi.dynamicSpaceVideo(
                    hostMid = mid,
                    offset = offset,
                    minCardCount = 0,
                    maxPages = 1,
                )
                if (token != dynamicRequestToken) return@launch

                dynamicNextOffset = page.nextOffset
                dynamicEndReached = page.nextOffset == null

                val fresh = VideoCardVisibilityFilter.filterVisibleFresh(page.items, loadedDynamicStableKeys)
                fresh.forEach { loadedDynamicStableKeys.add(it.stableKey()) }
                if (isRefresh) dynamicAdapter.submit(fresh) else dynamicAdapter.append(fresh)

                binding.recycler.post {
                    maybeConsumePendingFocusFirstContentAfterLoad()
                    archiveGridController?.consumePendingFocusAfterLoadMore()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("UpDetail", "loadDynamic failed mid=$mid", t)
                AppToast.show(this@UpDetailActivity, "加载失败，可查看 Logcat(标签 BLBL)")
            } finally {
                if (token == dynamicRequestToken) binding.swipeRefresh.isRefreshing = false
                dynamicIsLoadingMore = false
            }
        }
    }

    private fun resetAndLoadSeasonsSeries() {
        if (forceFocusFirstContentAfterRefresh) {
            pendingFocusFirstSectionItemAfterLoad = true
            parkFocusInRecyclerForDataSetReset()
        } else {
            val focused = currentFocus
            if (focused != null && focused != binding.recycler && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
                pendingFocusFirstSectionItemAfterLoad = true
            }
        }
        seasonsSeriesRequestToken++
        seasonsSeriesNextPage = 1
        seasonsSeriesEndReached = false
        seasonsSeriesIsLoadingMore = false
        loadedSectionStableIds.clear()
        seasonsSeriesSections.clear()
        sectionPagingStates.clear()
        sectionPendingAdvanceIndex.clear()
        binding.swipeRefresh.isRefreshing = true
        if (currentTab == UpTab.COLLECTION_SERIES) sectionAdapter.replaceAll(emptyList())
        loadMoreSeasonsSeries(isRefresh = true)
    }

    private fun ensureSectionPagingState(section: UpDetailSection): SectionPagingState {
        val key = section.stableId
        val state = sectionPagingStates.getOrPut(key) { SectionPagingState(totalCount = section.totalCount) }
        if (state.totalCount == null && section.totalCount != null && section.totalCount > 0) {
            state.totalCount = section.totalCount
        }
        for (v in section.videos) {
            val bvid = v.bvid.trim()
            if (bvid.isBlank()) continue
            state.loadedBvids.add(bvid)
        }
        val total = state.totalCount
        if (!state.endReached && total != null && total > 0 && state.loadedBvids.size >= total) {
            state.endReached = true
        }
        return state
    }

    private fun onSectionLoadMoreRequested(section: UpDetailSection, requestedNextIndex: Int) {
        val stableId = section.stableId
        val latest = seasonsSeriesSections.firstOrNull { it.stableId == stableId } ?: section
        val state = ensureSectionPagingState(latest)
        if (state.isLoading || state.endReached) return
        sectionPendingAdvanceIndex[stableId] = requestedNextIndex.coerceAtLeast(0)
        loadMoreSectionVideos(section = latest, state = state)
    }

    private data class SectionArchivesParsedPage(
        val totalCount: Int?,
        val videos: List<VideoCard>,
    )

    private fun List<VideoCard>.withOwnerFallback(ownerFallback: String): List<VideoCard> {
        if (ownerFallback.isBlank()) return this
        return map { card ->
            if (card.ownerName.isBlank()) card.copy(ownerName = ownerFallback) else card
        }
    }

    private fun VideoCollectionSection.toUpDetailSection(ownerFallback: String): UpDetailSection =
        UpDetailSection(
            kind =
                when (kind) {
                    VideoCollectionKind.SEASON -> UpDetailSectionKind.SEASON
                    VideoCollectionKind.SERIES -> UpDetailSectionKind.SERIES
                },
            id = id,
            title = title,
            totalCount = totalCount,
            videos = items.withOwnerFallback(ownerFallback),
        )

    private fun sectionArchivesPage(
        total: Int,
        videos: List<VideoCard>,
        ownerFallback: String,
    ): SectionArchivesParsedPage {
        return SectionArchivesParsedPage(
            totalCount = total.takeIf { it > 0 },
            videos = videos.withOwnerFallback(ownerFallback),
        )
    }

    private fun appendVideosToSectionInMemory(sectionStableId: String, videos: List<VideoCard>): UpDetailSection? {
        if (videos.isEmpty()) return null
        val idx = seasonsSeriesSections.indexOfFirst { it.stableId == sectionStableId }
        if (idx < 0) return null
        val old = seasonsSeriesSections[idx]
        val updated = old.copy(videos = old.videos + videos)
        seasonsSeriesSections[idx] = updated
        return updated
    }

    private fun loadMoreSectionVideos(section: UpDetailSection, state: SectionPagingState) {
        if (state.isLoading || state.endReached) return
        val token = seasonsSeriesRequestToken
        val targetPage = state.nextPage.coerceAtLeast(1)
        state.isLoading = true
        val ownerFallback = binding.tvName.text?.toString().orEmpty().trim()
        lifecycleScope.launch {
            try {
                val parsed =
                    when (section.kind) {
                        UpDetailSectionKind.SEASON -> {
                            val page =
                                BiliApi.ugcSeasonArchives(
                                    mid = mid,
                                    seasonId = section.id,
                                    pageNum = targetPage,
                                    pageSize = 30,
                                    sortReverse = false,
                                )
                            SectionArchivesParsedPage(
                                totalCount = page.totalCount,
                                videos = page.items.withOwnerFallback(ownerFallback),
                            )
                        }

                        UpDetailSectionKind.SERIES -> {
                            val page =
                                BiliApi.seriesArchives(
                                    mid = mid,
                                    seriesId = section.id,
                                    pageNum = targetPage,
                                    pageSize = 20,
                                    sort = "desc",
                                    onlyNormal = true,
                                )
                            sectionArchivesPage(
                                total = page.total,
                                videos = page.items,
                                ownerFallback = ownerFallback,
                            )
                        }
                    }
                if (token != seasonsSeriesRequestToken) return@launch

                val newVideos =
                    buildList {
                        for (v in parsed.videos) {
                            val bvid = v.bvid.trim()
                            if (bvid.isBlank()) continue
                            if (state.loadedBvids.add(bvid)) add(v)
                        }
                    }

                if (parsed.totalCount != null && parsed.totalCount > 0) {
                    state.totalCount = parsed.totalCount
                }

                state.nextPage = targetPage + 1
                if (parsed.videos.isEmpty()) state.endReached = true
                val total = state.totalCount
                if (!state.endReached && total != null && total > 0 && state.loadedBvids.size >= total) {
                    state.endReached = true
                }

                if (newVideos.isNotEmpty()) {
                    val stableId = section.stableId
                    appendVideosToSectionInMemory(stableId, newVideos)
                    sectionAdapter.appendVideos(stableId, newVideos)

                    val pendingIndex = sectionPendingAdvanceIndex.remove(stableId)
                    if (pendingIndex != null) {
                        binding.recycler.post { sectionAdapter.requestVideoFocus(stableId, pendingIndex) }
                    }
                } else {
                    sectionPendingAdvanceIndex.remove(section.stableId)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val msg =
                    (t as? BiliApiException)?.apiMessage?.trim()?.takeIf { it.isNotBlank() }
                        ?: t.message.orEmpty()
                AppLog.w("UpDetail", "loadSectionVideos failed mid=$mid section=${section.stableId} page=$targetPage", t)
                AppToast.show(this@UpDetailActivity, msg.ifBlank { "加载失败" })
                sectionPendingAdvanceIndex.remove(section.stableId)
            } finally {
                state.isLoading = false
            }
        }
    }

    private fun loadMoreSeasonsSeries(isRefresh: Boolean = false) {
        if (seasonsSeriesIsLoadingMore || seasonsSeriesEndReached) return
        val token = seasonsSeriesRequestToken
        val targetPage = seasonsSeriesNextPage.coerceAtLeast(1)
        seasonsSeriesIsLoadingMore = true
        val ownerFallback = binding.tvName.text?.toString().orEmpty().trim()
        lifecycleScope.launch {
            try {
                val parsed = BiliApi.collectionSections(mid = mid, pageNum = targetPage, pageSize = 10)
                if (token != seasonsSeriesRequestToken) return@launch

                val hasAny = parsed.sections.isNotEmpty()
                seasonsSeriesNextPage = targetPage + 1
                seasonsSeriesEndReached =
                    when {
                        parsed.totalPages > 0 -> targetPage >= parsed.totalPages
                        else -> !hasAny
                    }

                val inserted = ArrayList<UpDetailSection>(parsed.sections.size)
                for (apiSection in parsed.sections) {
                    val section = apiSection.toUpDetailSection(ownerFallback = ownerFallback)
                    if (!loadedSectionStableIds.add(section.stableId)) continue
                    seasonsSeriesSections.add(section)
                    inserted.add(section)
                    ensureSectionPagingState(section)
                }

                if (currentTab == UpTab.COLLECTION_SERIES) {
                    if (isRefresh) sectionAdapter.replaceAll(seasonsSeriesSections) else sectionAdapter.appendSections(inserted)
                }

                binding.recycler.post {
                    // Always run: this might be triggered by DPAD_DOWN on tabs, not only pull-to-refresh.
                    maybeConsumePendingFocusFirstContentAfterLoad()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("UpDetail", "loadSeasonsSeries failed mid=$mid page=$targetPage", t)
                val msg =
                    (t as? BiliApiException)?.apiMessage?.trim()?.takeIf { it.isNotBlank() }
                        ?: t.message.orEmpty()
                AppToast.show(this@UpDetailActivity, msg.ifBlank { "加载失败" })
            } finally {
                if (token == seasonsSeriesRequestToken) binding.swipeRefresh.isRefreshing = false
                seasonsSeriesIsLoadingMore = false
            }
        }
    }

    private fun onFollowClicked() {
        if (!BiliClient.cookies.hasSessData()) {
            startActivity(Intent(this, QrLoginActivity::class.java))
            AppToast.show(this, "登录后才能关注")
            return
        }
        if (followActionInFlight) return
        val selfMid = BiliClient.cookies.getCookieValue("DedeUserID")?.trim()?.toLongOrNull()
        if (selfMid != null && selfMid == mid) return

        val wantFollow = !isFollowed
        followActionInFlight = true
        updateFollowUi()
        lifecycleScope.launch {
            try {
                BiliApi.modifyRelation(fid = mid, act = if (wantFollow) 1 else 2, reSrc = 11)
                isFollowed = wantFollow
                AppToast.show(this@UpDetailActivity, if (wantFollow) "已关注" else "已取关")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.w("UpDetail", "modifyRelation failed mid=$mid wantFollow=$wantFollow", t)
                val raw =
                    (t as? BiliApiException)?.apiMessage?.takeIf { it.isNotBlank() }
                        ?: t.message.orEmpty()
                val msg =
                    when (raw) {
                        "missing_csrf" -> "登录态不完整，请重新登录"
                        else -> raw
                    }
                AppToast.show(this@UpDetailActivity, if (msg.isBlank()) "操作失败" else msg)
            } finally {
                followActionInFlight = false
                updateFollowUi()
                loadHeader()
            }
        }
    }

    private fun updateFollowUi() {
        val selfMid = BiliClient.cookies.getCookieValue("DedeUserID")?.trim()?.toLongOrNull()
        val isSelf = selfMid != null && selfMid == mid
        binding.btnFollow.isVisible = !isSelf
        if (isSelf) return

        binding.btnFollow.isEnabled = !followActionInFlight
        binding.btnFollow.text = if (isFollowed) "已关注" else "关注"

        val bg =
            if (isFollowed) {
                ThemeColor.resolve(this, MaterialR.attr.colorSurface, R.color.blbl_surface)
            } else {
                ThemeColor.resolve(this, R.attr.blblAccent, R.color.blbl_purple)
            }
        val fg =
            if (isFollowed) {
                ThemeColor.resolve(this, android.R.attr.textColorSecondary, R.color.blbl_text_secondary)
            } else {
                ThemeColor.resolve(this, MaterialR.attr.colorOnSecondary, R.color.blbl_text)
            }
        binding.btnFollow.backgroundTintList = ColorStateList.valueOf(bg)
        binding.btnFollow.setTextColor(fg)
    }

    private fun focusGridAt(position: Int) {
        val recycler = binding.recycler
        recycler.post outer@{
            if (isFinishing || isDestroyed) return@outer
            val vh = recycler.findViewHolderForAdapterPosition(position)
            if (vh != null) {
                vh.itemView.requestFocus()
                return@outer
            }
            recycler.scrollToPosition(position)
            recycler.post inner@{
                if (isFinishing || isDestroyed) return@inner
                recycler.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() ?: recycler.requestFocus()
            }
        }
    }

    private fun focusFirstSectionVideo() {
        val recycler = binding.recycler
        recycler.post outer@{
            if (isFinishing || isDestroyed) return@outer

            val outerVh = recycler.findViewHolderForAdapterPosition(0)
            val innerRecycler =
                outerVh?.itemView?.findViewById<RecyclerView>(R.id.recycler_videos)
            if (innerRecycler != null) {
                val innerVh = innerRecycler.findViewHolderForAdapterPosition(0)
                if (innerVh != null) {
                    innerVh.itemView.requestFocus()
                    return@outer
                }
                innerRecycler.scrollToPosition(0)
                innerRecycler.post {
                    if (isFinishing || isDestroyed) return@post
                    innerRecycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: innerRecycler.requestFocus()
                }
                return@outer
            }

            recycler.scrollToPosition(0)
            recycler.post inner@{
                if (isFinishing || isDestroyed) return@inner
                val vh = recycler.findViewHolderForAdapterPosition(0)
                val inner =
                    vh?.itemView?.findViewById<RecyclerView>(R.id.recycler_videos)
                        ?: return@inner
                inner.scrollToPosition(0)
                inner.post {
                    if (isFinishing || isDestroyed) return@post
                    inner.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: inner.requestFocus()
                }
            }
        }
    }

    private fun maybeConsumePendingFocusFirstContentAfterLoad() {
        if (!hasWindowFocus()) return

        val force = forceFocusFirstContentAfterRefresh
        if (!force) {
            val focused = currentFocus
            if (focused != null && focused != binding.recycler && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
                pendingFocusFirstArchiveItemAfterLoad = false
                pendingFocusFirstSectionItemAfterLoad = false
                return
            }
        }

        when (currentTab) {
            UpTab.ARCHIVE -> {
                if (!force && !pendingFocusFirstArchiveItemAfterLoad) return
                if (archiveAdapter.itemCount <= 0) return
                pendingFocusFirstArchiveItemAfterLoad = false
                forceFocusFirstContentAfterRefresh = false
                focusGridAt(0)
            }

            UpTab.DYNAMIC -> {
                if (!force && !pendingFocusFirstDynamicItemAfterLoad) return
                if (dynamicAdapter.itemCount <= 0) return
                pendingFocusFirstDynamicItemAfterLoad = false
                forceFocusFirstContentAfterRefresh = false
                focusGridAt(0)
            }

            UpTab.COLLECTION_SERIES -> {
                if (!force && !pendingFocusFirstSectionItemAfterLoad) return
                if (seasonsSeriesSections.isEmpty()) return
                pendingFocusFirstSectionItemAfterLoad = false
                forceFocusFirstContentAfterRefresh = false
                focusFirstSectionVideo()
            }
        }
    }

    private fun parkFocusInRecyclerForDataSetReset() {
        val focused = currentFocus
        if (focused != null && focused != binding.recycler && !FocusTreeUtils.isDescendantOf(focused, binding.recycler)) return
        val recycler = binding.recycler
        val original = recycler.descendantFocusability
        recycler.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        recycler.requestFocus()
        recycler.descendantFocusability = original
    }

    private fun installArchiveGridController() {
        if (archiveGridController != null) return
        archiveGridController?.release()
        archiveGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            return focusTabsFromContentEdge()
                        }

                        override fun onLeftEdge(): Boolean {
                            return switchToPrevTabFromContentEdge()
                        }

                        override fun onRightEdge() {
                            switchToNextTabFromContentEdge()
                        }

                        override fun canLoadMore(): Boolean =
                            when (currentTab) {
                                UpTab.ARCHIVE ->
                                    if (archiveSearchQuery.isNotBlank()) archiveSearchUi?.canLoadMore == true else !archiveEndReached
                                UpTab.DYNAMIC -> !dynamicEndReached
                                UpTab.COLLECTION_SERIES -> false
                            }

                        override fun loadMore() {
                            when (currentTab) {
                                UpTab.ARCHIVE ->
                                    if (archiveSearchQuery.isNotBlank()) archiveSearchController?.loadMore() else loadMoreArchive()
                                UpTab.DYNAMIC -> loadMoreDynamic()
                                UpTab.COLLECTION_SERIES -> Unit
                            }
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = {
                            !isFinishing && !isDestroyed &&
                                (currentTab == UpTab.ARCHIVE || currentTab == UpTab.DYNAMIC)
                        },
                        enableCenterLongPressToLongClick = true,
                        // Let DPAD_UP bubble if we couldn't move focus to the tab strip. Consuming
                        // unconditionally creates a focus trap (can't reach header/tab).
                        consumeUpAtTopEdge = false,
                    ),
            ).also { it.install() }
    }

    private fun releaseArchiveGridController() {
        archiveGridController?.release()
        archiveGridController = null
    }

    private fun switchToNextTabFromContentEdge(): Boolean {
        val cur = binding.tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        val next = cur + 1
        if (next >= binding.tabLayout.tabCount) return false
        binding.tabLayout.getTabAt(next)?.select() ?: return false
        binding.recycler.postIfAlive(isAlive = { !isFinishing && !isDestroyed }) { requestFocusFirstContentFromTab() }
        return true
    }

    private fun switchToPrevTabFromContentEdge(): Boolean {
        val cur = binding.tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        val prev = cur - 1
        if (prev < 0) return false
        binding.tabLayout.getTabAt(prev)?.select() ?: return false
        binding.recycler.postIfAlive(isAlive = { !isFinishing && !isDestroyed }) { requestFocusFirstContentFromTab() }
        return true
    }

    private fun archivePlaybackHandle() =
        buildPagedVideoCardPlaybackHandle(
            source = "UpDetail:$mid:archive",
            cardsProvider = {
                if (archiveSearchQuery.isNotBlank()) {
                    archiveSearchController?.items() ?: emptyList()
                } else {
                    archiveAdapter.snapshot()
                }
            },
            nextCursorProvider = { archiveNextPage },
            hasMoreProvider = { archiveSearchQuery.isBlank() && !archiveEndReached },
        ) { pageNum ->
            val targetPage = pageNum.coerceAtLeast(1)
            val page = BiliApi.spaceArcSearchPage(mid = mid, pn = targetPage, ps = 30)
            VideoCardPlaylistPage(
                cards = page.items,
                nextCursor = targetPage + 1,
                hasMore = page.hasMore,
                canAdvance = page.hasMore && page.items.isNotEmpty(),
            )
        }

    private fun dynamicPlaybackHandle() =
        buildPagedVideoCardPlaybackHandle<String?>(
            source = "UpDetail:$mid:dynamic",
            cardsProvider = { dynamicAdapter.snapshot() },
            nextCursorProvider = { dynamicNextOffset },
            hasMoreProvider = { !dynamicEndReached },
        ) { cursor ->
            val page = BiliApi.dynamicSpaceVideo(hostMid = mid, offset = cursor, minCardCount = 0, maxPages = 1)
            VideoCardPlaylistPage(
                cards = page.items,
                nextCursor = page.nextOffset,
                hasMore = page.nextOffset != null,
                canAdvance = page.nextOffset != null && page.items.isNotEmpty(),
            )
        }

    private fun buildSectionPlaybackHandle(section: UpDetailSection) =
        buildVideoCardPlaybackHandle(
            source = "UpDetail:$mid:${section.stableId}",
            cardsProvider = { section.videos },
        ) { cards ->
            buildSectionPlaylistContinuation(section, cards)
        }

    private fun buildSectionPlaylistContinuation(
        section: UpDetailSection,
        cards: List<VideoCard>,
    ): PlayerPlaylistContinuation? {
        val state = sectionPagingStates[section.stableId] ?: return null
        val pageSize = if (section.kind == UpDetailSectionKind.SEASON) 30 else 20
        val ownerFallback = binding.tvName.text?.toString().orEmpty().trim()
        return buildFreshVideoCardPlaylistContinuation(
            seedCards = cards,
            nextCursor = state.nextPage,
            hasMore = !state.endReached,
            playlistItemFactory = ::defaultVideoCardPlaylistItem,
        ) { pageNum ->
            val safePageNum = pageNum.coerceAtLeast(1)
            val parsedPage =
                when (section.kind) {
                    UpDetailSectionKind.SEASON -> {
                        val page =
                            BiliApi.ugcSeasonArchives(
                                mid = mid,
                                seasonId = section.id,
                                pageNum = safePageNum,
                                pageSize = pageSize,
                                sortReverse = false,
                            )
                        SectionArchivesParsedPage(
                            totalCount = page.totalCount,
                            videos = page.items.withOwnerFallback(ownerFallback),
                        )
                    }

                    UpDetailSectionKind.SERIES -> {
                        val page =
                            BiliApi.seriesArchives(
                                mid = mid,
                                seriesId = section.id,
                                pageNum = safePageNum,
                                pageSize = pageSize,
                                sort = "desc",
                                onlyNormal = true,
                            )
                        sectionArchivesPage(
                            total = page.total,
                            videos = page.items,
                            ownerFallback = ownerFallback,
                        )
                    }
                }
            val totalCount = parsedPage.totalCount ?: state.totalCount
            val hasMore =
                totalCount?.let { safePageNum * pageSize < it }
                    ?: (parsedPage.videos.size >= pageSize)
            VideoCardPlaylistPage(
                cards = parsedPage.videos,
                nextCursor = safePageNum + 1,
                hasMore = hasMore,
                canAdvance = hasMore && parsedPage.videos.isNotEmpty(),
            )
        }
    }

    private fun openUpDetailForCard(card: blbl.cat3399.core.model.VideoCard) {
        val targetMid = card.ownerMid?.takeIf { it > 0L } ?: mid
        if (targetMid == mid) return
        startActivity(
            Intent(this, UpDetailActivity::class.java)
                .putExtra(EXTRA_MID, targetMid)
                .apply {
                    card.ownerName.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_NAME, it) }
                    card.ownerFace?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_AVATAR, it) }
                },
        )
    }

    private fun spanCountForWidth(): Int {
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return GridSpanPolicy.fixedSpanCountForWidthDp(
            widthDp = widthDp,
            overrideSpanCount = BiliClient.prefs.gridSpanCount,
        )
    }

    private fun isNavKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB,
            -> true

            else -> false
        }
    }

    companion object {
        const val EXTRA_MID: String = "mid"
        const val EXTRA_NAME: String = "name"
        const val EXTRA_AVATAR: String = "avatar"
        const val EXTRA_SIGN: String = "sign"
        private const val ACTIVITY_STACK_GROUP: String = "player_up_flow"
        private const val ACTIVITY_STACK_MAX_DEPTH: Int = 3
    }
}
