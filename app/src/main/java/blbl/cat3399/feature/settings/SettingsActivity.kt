package blbl.cat3399.feature.settings

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.io.CreateDocumentContract
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.hideImeReliable
import blbl.cat3399.core.ui.showImeReliable
import blbl.cat3399.core.ui.popup.PopupHost
import blbl.cat3399.databinding.ActivitySettingsBinding

class SettingsActivity : BaseActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val state = SettingsState()
    private lateinit var leftAdapter: SettingsLeftAdapter
    private lateinit var rightAdapter: SettingsEntryAdapter
    private lateinit var renderer: SettingsRenderer
    private lateinit var interactionHandler: SettingsInteractionHandler
    private val focusKeyState = SettingsFocusKeyState()
    private val settingsSearchHandler = Handler(Looper.getMainLooper())
    private val settingsSearchRunnable = Runnable { runSettingsSearch() }
    private var suppressSearchWatcher = false

    private val gaiaVgateLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (!this::interactionHandler.isInitialized) return@registerForActivityResult
            interactionHandler.onGaiaVgateResult(result)
        }

    private val exportDocumentLauncher =
        registerForActivityResult(CreateDocumentContract()) { uri ->
            if (!this::interactionHandler.isInitialized) return@registerForActivityResult
            interactionHandler.onExportDocumentSelected(uri)
        }

    private val importConfigLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (!this::interactionHandler.isInitialized) return@registerForActivityResult
            interactionHandler.onImportConfigSelected(uri)
        }

    private val sections =
        listOf(
            "通用设置",
            "页面设置",
            "播放设置",
            "弹幕设置",
            "关于应用",
            "设备信息",
            "其他设置",
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        interactionHandler =
            SettingsInteractionHandler(
                activity = this,
                state = state,
                gaiaVgateLauncher = gaiaVgateLauncher,
                exportDocumentLauncher = exportDocumentLauncher,
                importConfigLauncher = importConfigLauncher,
            )

        binding.btnBack.setOnClickListener { finish() }

        leftAdapter = SettingsLeftAdapter { index ->
            closeSettingsSearch(restoreSection = false)
            renderer.showSection(index, keepScroll = false)
        }
        binding.recyclerLeft.layoutManager = LinearLayoutManager(this)
        binding.recyclerLeft.itemAnimator = null
        binding.recyclerLeft.adapter = leftAdapter
        leftAdapter.submit(sections, selected = 0)

        binding.recyclerRight.layoutManager = LinearLayoutManager(this)
        binding.recyclerRight.itemAnimator = null
        rightAdapter =
            SettingsEntryAdapter { entry ->
                if (this::renderer.isInitialized && renderer.isSearching() && entry.sectionIndex >= 0) {
                    // 搜索结果只负责跳转，不直接触发设置动作（否则会把开关点翻）。
                    closeSettingsSearch(restoreSection = false)
                    renderer.showSection(entry.sectionIndex, keepScroll = false, focusId = entry.id)
                    return@SettingsEntryAdapter
                }
                interactionHandler.onEntryClicked(entry)
            }
        binding.recyclerRight.adapter = rightAdapter

        renderer =
            SettingsRenderer(
                activity = this,
                binding = binding,
                state = state,
                sections = sections,
                leftAdapter = leftAdapter,
                rightAdapter = rightAdapter,
                onSectionShown = { sectionName -> interactionHandler.onSectionShown(sectionName) },
            )
        interactionHandler.renderer = renderer
        binding.btnSearch.setOnClickListener { openSettingsSearch() }
        setupSettingsSearchBar()

        renderer.installFocusListener()
        renderer.showSection(0)
    }

    override fun onResume() {
        super.onResume()
        // 搜索栏可见时不要抢走输入框焦点（否则刚点开搜索就被列表抢回去）。
        if (!isSettingsSearchVisible()) renderer.ensureInitialFocus()
    }

    override fun onDestroy() {
        settingsSearchHandler.removeCallbacks(settingsSearchRunnable)
        renderer.uninstallFocusListener()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        if (hasFocus && !isSettingsSearchVisible()) renderer.restorePendingFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!this::renderer.isInitialized || !this::binding.isInitialized) return super.dispatchKeyEvent(event)

        val popupHost = PopupHost.peek(this)
        if (popupHost != null && popupHost.consumeBackLikeKeyEventIfNeeded(event)) {
            return true
        }

        val keyCode = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && isBackLikeKey(keyCode) && isSettingsSearchVisible()) {
            closeSettingsSearch(restoreSection = true)
            return true
        }
        if (handleExplicitFocusKey(event)) return true
        if (isBackLikeKey(keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val focused = currentFocus
                val focusInContent = focused != null && FocusTreeUtils.isDescendantOf(focused, binding.recyclerRight)
                if (focusInContent) {
                    renderer.focusActiveSectionTab()
                } else {
                    finish()
                }
            }
            return true
        }

        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null && renderer.isNavKey(keyCode)) {
            renderer.ensureInitialFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleExplicitFocusKey(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val isDpadDirection =
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!isDpadDirection) return false

        return when (event.action) {
            KeyEvent.ACTION_DOWN ->
                focusKeyState.onDown(keyCode, event.repeatCount) {
                    if (event.isCanceled) return@onDown false
                    handleFocusKeyDown(keyCode)
                }

            KeyEvent.ACTION_UP -> focusKeyState.onUp(keyCode)
            else -> false
        }
    }

    private fun handleFocusKeyDown(keyCode: Int): Boolean {
        val focused = currentFocus ?: return false
        if (focused == binding.btnSearch) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    binding.btnBack.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (isSettingsSearchVisible()) {
                        binding.etSettingsSearch.requestFocus()
                        binding.etSettingsSearch.showImeReliable()
                        true
                    } else {
                        renderer.focusActiveSectionContent()
                    }
                else -> false
            }
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && focused == binding.btnBack) {
            binding.btnSearch.requestFocus()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focused == binding.btnBack) {
            return renderer.focusLastSectionTab()
        }

        if (FocusTreeUtils.isDescendantOf(focused, binding.recyclerLeft)) {
            val leftPosition = focusedAdapterPosition(binding.recyclerLeft, focused)
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    when (leftPosition) {
                        RecyclerView.NO_POSITION -> true
                        0 -> binding.btnBack.requestFocus()
                        else -> false
                    }
                }

                KeyEvent.KEYCODE_DPAD_DOWN ->
                    shouldStopSettingsVerticalFocus(
                        position = leftPosition,
                        itemCount = leftAdapter.itemCount,
                        direction = SettingsVerticalDirection.Down,
                    )

                KeyEvent.KEYCODE_DPAD_RIGHT -> renderer.focusActiveSectionContent()
                else -> false
            }
        }

        if (FocusTreeUtils.isDescendantOf(focused, binding.recyclerRight)) {
            val rightPosition = focusedAdapterPosition(binding.recyclerRight, focused)
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP ->
                    shouldStopSettingsVerticalFocus(
                        position = rightPosition,
                        itemCount = rightAdapter.itemCount,
                        direction = SettingsVerticalDirection.Up,
                    )

                KeyEvent.KEYCODE_DPAD_DOWN ->
                    shouldStopSettingsVerticalFocus(
                        position = rightPosition,
                        itemCount = rightAdapter.itemCount,
                        direction = SettingsVerticalDirection.Down,
                    )

                KeyEvent.KEYCODE_DPAD_LEFT -> renderer.focusActiveSectionTab()
                else -> false
            }
        }

        return false
    }

    private fun setupSettingsSearchBar() {
        binding.etSettingsSearch.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(s: Editable?) {
                    if (suppressSearchWatcher) return
                    scheduleSettingsSearch(s?.toString().orEmpty())
                }
            },
        )
        binding.etSettingsSearch.setOnEditorActionListener { _, _, _ ->
            binding.etSettingsSearch.hideImeReliable()
            true
        }
        binding.etSettingsSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                renderer.focusActiveSectionContent()
                true
            } else {
                false
            }
        }
        binding.btnSettingsSearchClear.setOnClickListener {
            if (binding.etSettingsSearch.text.isNullOrEmpty()) {
                closeSettingsSearch(restoreSection = true)
            } else {
                binding.etSettingsSearch.setText("")
            }
        }
    }

    private fun openSettingsSearch() {
        if (!this::renderer.isInitialized) return
        binding.llSettingsSearch.visibility = View.VISIBLE
        // 等搜索栏完成一次布局再要焦点，否则部分机型会把焦点/软键盘丢掉。
        binding.etSettingsSearch.post {
            binding.etSettingsSearch.requestFocus()
            binding.etSettingsSearch.setSelection(binding.etSettingsSearch.text?.length ?: 0)
            binding.etSettingsSearch.showImeReliable()
        }
    }

    private fun closeSettingsSearch(restoreSection: Boolean) {
        if (binding.llSettingsSearch.visibility != View.VISIBLE) return
        settingsSearchHandler.removeCallbacks(settingsSearchRunnable)
        binding.etSettingsSearch.hideImeReliable()
        binding.llSettingsSearch.visibility = View.GONE
        binding.tvSettingsSearchStatus.visibility = View.GONE
        if (binding.etSettingsSearch.text?.isNotEmpty() == true) {
            // 清空输入会触发 TextWatcher，这里屏蔽掉，避免多重建一次列表。
            suppressSearchWatcher = true
            binding.etSettingsSearch.setText("")
            suppressSearchWatcher = false
        }
        if (restoreSection && this::renderer.isInitialized && renderer.isSearching()) {
            renderer.exitSearch()
        }
    }

    private fun scheduleSettingsSearch(raw: String) {
        settingsSearchHandler.removeCallbacks(settingsSearchRunnable)
        val query = raw.trim()
        if (query.isEmpty()) {
            binding.tvSettingsSearchStatus.visibility = View.GONE
            if (this::renderer.isInitialized && renderer.isSearching()) renderer.exitSearch()
            return
        }
        settingsSearchHandler.postDelayed(settingsSearchRunnable, 120L)
    }

    private fun runSettingsSearch() {
        if (!this::renderer.isInitialized) return
        val query = binding.etSettingsSearch.text?.toString().orEmpty().trim()
        if (query.isEmpty()) return
        val count = renderer.showSearchResults(query)
        binding.tvSettingsSearchStatus.visibility = View.VISIBLE
        binding.tvSettingsSearchStatus.text =
            if (count > 0) {
                getString(R.string.settings_search_count_fmt, count)
            } else {
                getString(R.string.settings_search_empty)
            }
        binding.recyclerRight.scrollToPosition(0)
    }

    private fun isSettingsSearchVisible(): Boolean = binding.llSettingsSearch.visibility == View.VISIBLE

    private fun focusedAdapterPosition(recyclerView: RecyclerView, focused: View): Int {
        return recyclerView.findContainingViewHolder(focused)?.bindingAdapterPosition
            ?: RecyclerView.NO_POSITION
    }

    private fun isBackLikeKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_ESCAPE ||
            keyCode == KeyEvent.KEYCODE_BUTTON_B
    }
}
