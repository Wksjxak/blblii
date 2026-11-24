package blbl.cat3399.feature.settings

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.io.CreateDocumentContract
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.popup.AppPopup
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

        leftAdapter = SettingsLeftAdapter { index -> renderer.showSection(index, keepScroll = false) }
        binding.recyclerLeft.layoutManager = LinearLayoutManager(this)
        binding.recyclerLeft.itemAnimator = null
        binding.recyclerLeft.adapter = leftAdapter
        leftAdapter.submit(sections, selected = 0)

        binding.recyclerRight.layoutManager = LinearLayoutManager(this)
        binding.recyclerRight.itemAnimator = null
        rightAdapter =
            SettingsEntryAdapter { entry ->
                if (entry.sectionIndex >= 0 && this::renderer.isInitialized && renderer.isSearching()) {
                    renderer.showSection(entry.sectionIndex, focusId = entry.id)
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
        binding.btnSearch.setOnClickListener { showSettingsSearchDialog() }

        renderer.installFocusListener()
        renderer.showSection(0)
    }

    override fun onResume() {
        super.onResume()
        renderer.ensureInitialFocus()
    }

    override fun onDestroy() {
        renderer.uninstallFocusListener()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        if (hasFocus) renderer.restorePendingFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!this::renderer.isInitialized || !this::binding.isInitialized) return super.dispatchKeyEvent(event)

        val popupHost = PopupHost.peek(this)
        if (popupHost != null && popupHost.consumeBackLikeKeyEventIfNeeded(event)) {
            return true
        }

        val keyCode = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && isBackLikeKey(keyCode) && renderer.isSearching()) {
            renderer.exitSearch()
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
                KeyEvent.KEYCODE_DPAD_DOWN -> renderer.focusActiveSectionContent()
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

    private fun showSettingsSearchDialog() {
        if (!this::renderer.isInitialized) return
        AppPopup.input(
            context = this,
            title = "搜索设置",
            initial = renderer.currentSearchQuery().orEmpty(),
            hint = "输入设置名称，如弹幕、动态视频、播放模式",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
            positiveText = "搜索",
            negativeText = "取消",
            validate = { text ->
                if (text.trim().isBlank()) {
                    AppToast.show(this, "请输入搜索关键词")
                    false
                } else {
                    true
                }
            },
            onPositive = { text ->
                if (!renderer.showSearchResults(text)) {
                    AppToast.show(this, "没有找到匹配的设置")
                }
            },
        )
    }

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
