package blbl.cat3399.feature.player

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings

/**
 * 屏幕占屏率是否包含 —— 独立侧边面板（二级菜单）。
 *
 * 勾选（计入）的类型受弹幕占屏比（area）约束，未勾选（不计入）的类型不受占屏比限制、
 * 按全屏区域排布显示。面板关闭后设置持续生效。
 */
internal object PlayerAreaIncludeKeys {
    const val SCROLL = "area_include_scroll"
    const val TOP = "area_include_top"
    const val BOTTOM = "area_include_bottom"
    const val COLOR = "area_include_color"
    const val SPECIAL = "area_include_special"
}

internal fun PlayerActivity.isAreaIncludePanelVisible(): Boolean =
    binding.areaIncludePanel.visibility == View.VISIBLE

internal fun PlayerActivity.initAreaIncludePanel() {
    val adapter = PlayerSettingsAdapter { item -> handleAreaIncludeItemClick(item.key) }
    areaIncludeSettingsAdapter = adapter
    binding.recyclerAreaInclude.layoutManager = LinearLayoutManager(this)
    binding.recyclerAreaInclude.adapter = adapter
    refreshAreaIncludeItems()
}

internal fun PlayerActivity.handleAreaIncludeItemClick(key: String) {
    val d = session.danmaku
    when (key) {
        PlayerAreaIncludeKeys.SCROLL ->
            toggleAreaInclude(d.areaIncludeScroll, { copy(areaIncludeScroll = it) }, { danmakuAreaIncludeScroll = it })
        PlayerAreaIncludeKeys.TOP ->
            toggleAreaInclude(d.areaIncludeTop, { copy(areaIncludeTop = it) }, { danmakuAreaIncludeTop = it })
        PlayerAreaIncludeKeys.BOTTOM ->
            toggleAreaInclude(d.areaIncludeBottom, { copy(areaIncludeBottom = it) }, { danmakuAreaIncludeBottom = it })
        PlayerAreaIncludeKeys.COLOR ->
            toggleAreaInclude(d.areaIncludeColor, { copy(areaIncludeColor = it) }, { danmakuAreaIncludeColor = it })
        PlayerAreaIncludeKeys.SPECIAL ->
            toggleAreaInclude(d.areaIncludeSpecial, { copy(areaIncludeSpecial = it) }, { danmakuAreaIncludeSpecial = it })
    }
}

private fun PlayerActivity.toggleAreaInclude(
    current: Boolean,
    updateDanmaku: DanmakuSessionSettings.(Boolean) -> DanmakuSessionSettings,
    syncToGlobal: AppPrefs.(Boolean) -> Unit,
) {
    toggleDanmakuReloadSettingFlag(
        current = current,
        updateDanmaku = updateDanmaku,
        syncToGlobal = syncToGlobal,
    )
    refreshAreaIncludeItems()
}

private fun PlayerActivity.areaIncludeSwitchText(included: Boolean): String =
    if (included) "计入" else "不计入"

internal fun PlayerActivity.refreshAreaIncludeItems() {
    val adapter = areaIncludeSettingsAdapter ?: return
    val d = session.danmaku
    val items =
        buildList {
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerAreaIncludeKeys.SCROLL,
                    title = "滚动弹幕",
                    subtitle = areaIncludeSwitchText(d.areaIncludeScroll),
                ),
            )
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerAreaIncludeKeys.TOP,
                    title = "顶部悬停弹幕",
                    subtitle = areaIncludeSwitchText(d.areaIncludeTop),
                ),
            )
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerAreaIncludeKeys.BOTTOM,
                    title = "底部悬停弹幕",
                    subtitle = areaIncludeSwitchText(d.areaIncludeBottom),
                ),
            )
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerAreaIncludeKeys.COLOR,
                    title = "彩色弹幕",
                    subtitle = areaIncludeSwitchText(d.areaIncludeColor),
                ),
            )
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerAreaIncludeKeys.SPECIAL,
                    title = "特殊弹幕",
                    subtitle = areaIncludeSwitchText(d.areaIncludeSpecial),
                ),
            )
        }
    adapter.submit(items)
}

internal fun PlayerActivity.showAreaIncludePanel() {
    onOverlayPanelShown(openedFromMenuKey = false)
    hideBottomCardPanel(finalizeOverlaySession = false)
    if (isCommentsPanelVisible()) {
        videoCommentsController?.clearTransientUi()
    }
    binding.settingsPanel.visibility = View.GONE
    binding.commentsPanel.visibility = View.GONE
    binding.timerPanel.visibility = View.GONE
    binding.areaIncludePanel.visibility = View.VISIBLE
    setControlsVisible(false)
    refreshAreaIncludeItems()
    focusAreaIncludePanel()
    syncPlayerInfoPanelVisibility()
}

internal fun PlayerActivity.hideAreaIncludePanel() {
    binding.areaIncludePanel.visibility = View.GONE
    syncPlayerInfoPanelVisibility()
    onLastOverlayPanelDismissed()
}

internal fun PlayerActivity.focusAreaIncludePanel() {
    binding.recyclerAreaInclude.post {
        val child = binding.recyclerAreaInclude.getChildAt(0)
        if (child != null) {
            child.requestFocus()
            return@post
        }
        binding.recyclerAreaInclude.scrollToPosition(0)
        binding.recyclerAreaInclude.post {
            (binding.recyclerAreaInclude.getChildAt(0) ?: binding.recyclerAreaInclude).requestFocus()
        }
    }
}
