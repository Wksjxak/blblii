package blbl.cat3399.feature.player

import android.os.SystemClock
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import blbl.cat3399.R
import blbl.cat3399.core.ui.AppToast
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 定时退出播放器（独立侧边面板）。
 *
 * 支持两种退出方式：
 *  1. 时长模式：设置 N 分钟后退出，倒计时实时刷新，到点后调用 [finish] 退出播放器。
 *  2. 看完当前视频后退出：当前视频播放结束（STATE_ENDED）时退出。
 *
 * 定时在面板关闭后仍持续生效，退出动作与普通返回一致（走完整的 exit cleanup）。
 */
internal object PlayerTimerKeys {
    const val STATUS = "timer_status"
    const val OFF = "timer_off"
    const val AFTER_CURRENT = "timer_after_current"
    const val PREFIX_MIN = "timer_min_"
}

internal val PLAYER_TIMER_CHOICES_MINUTES = intArrayOf(10, 15, 20, 30, 45, 60, 90, 120)

internal fun minutesTimerKey(minutes: Int): String = PlayerTimerKeys.PREFIX_MIN + minutes

internal fun PlayerActivity.isTimerPanelVisible(): Boolean = binding.timerPanel.visibility == View.VISIBLE

internal fun PlayerActivity.initTimerPanel() {
    val adapter = PlayerSettingsAdapter { item -> handleTimerItemClick(item.key) }
    timerSettingsAdapter = adapter
    binding.recyclerTimer.layoutManager = LinearLayoutManager(this)
    binding.recyclerTimer.adapter = adapter
    binding.btTimerCustomStart.setOnClickListener {
        val h = binding.etTimerCustomH.text.toString().toIntOrNull() ?: 0
        val m = binding.etTimerCustomM.text.toString().toIntOrNull() ?: 0
        val s = binding.etTimerCustomS.text.toString().toIntOrNull() ?: 0
        startCustomTimerExit(h, m, s)
    }
    refreshTimerItems()
}

internal fun PlayerActivity.handleTimerItemClick(key: String) {
    when (key) {
        PlayerTimerKeys.OFF -> cancelTimerExit()
        PlayerTimerKeys.AFTER_CURRENT -> startTimerExitAfterCurrentVideo()
        PlayerTimerKeys.STATUS -> Unit
        else -> {
            val minutes =
                PLAYER_TIMER_CHOICES_MINUTES.firstOrNull { minutesTimerKey(it) == key }
            if (minutes != null) startTimerExit(minutes)
        }
    }
}

/** 当前倒计时剩余毫秒；未设置返回 0。 */
internal fun PlayerActivity.timerRemainingMs(): Long {
    if (timerExitEndAtElapsedMs <= 0L) return 0L
    return timerExitEndAtElapsedMs - SystemClock.elapsedRealtime()
}

/** 定时状态文案：用于设置面板入口与定时面板状态行的副标题。 */
internal fun PlayerActivity.timerStatusSubtitle(): String {
    if (timerExitAfterCurrentVideo) {
        return getString(R.string.player_timer_after_current)
    }
    val remainingMs = timerRemainingMs()
    if (remainingMs <= 0L) return getString(R.string.player_timer_status_off)
    return getString(R.string.player_timer_remaining_fmt, formatTimerClock(remainingMs))
}

internal fun PlayerActivity.formatTimerClock(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) + 999L) / 1000L
    val hours = totalSec / 3600L
    val minutes = (totalSec % 3600L) / 60L
    val seconds = totalSec % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

internal fun PlayerActivity.toggleTimerPanel() {
    if (isTimerPanelVisible()) hideTimerPanel() else showTimerPanel()
}

internal fun PlayerActivity.showTimerPanel(openedFromMenuKey: Boolean = false) {
    onOverlayPanelShown(openedFromMenuKey = openedFromMenuKey)
    hideBottomCardPanel(finalizeOverlaySession = false)
    if (isCommentsPanelVisible()) {
        videoCommentsController?.clearTransientUi()
    }
    binding.settingsPanel.visibility = View.GONE
    binding.commentsPanel.visibility = View.GONE
    binding.areaIncludePanel.visibility = View.GONE
    binding.timerPanel.visibility = View.VISIBLE
    setControlsVisible(false)
    refreshTimerItems()
    focusTimerPanel()
    syncPlayerInfoPanelVisibility()
}

internal fun PlayerActivity.hideTimerPanel() {
    binding.timerPanel.visibility = View.GONE
    syncPlayerInfoPanelVisibility()
    onLastOverlayPanelDismissed()
}

internal fun PlayerActivity.focusTimerPanel() {
    binding.recyclerTimer.post {
        val child = binding.recyclerTimer.getChildAt(0)
        if (child != null) {
            child.requestFocus()
            return@post
        }
        binding.recyclerTimer.scrollToPosition(0)
        binding.recyclerTimer.post {
            (binding.recyclerTimer.getChildAt(0) ?: binding.recyclerTimer).requestFocus()
        }
    }
}

internal fun PlayerActivity.startTimerExit(minutes: Int) {
    timerExitAfterCurrentVideo = false
    timerExitEndAtElapsedMs = SystemClock.elapsedRealtime() + minutes.coerceAtLeast(1) * 60_000L
    startTimerTick()
    AppToast.show(this, getString(R.string.player_timer_toast_set_fmt, minutes))
    refreshTimerItems()
}

/** 自定义时分秒启动定时退出；全为 0 时提示无效。 */
internal fun PlayerActivity.startCustomTimerExit(hours: Int, minutes: Int, seconds: Int) {
    val totalSec = hours.toLong() * 3600L + minutes.toLong() * 60L + seconds.toLong()
    if (totalSec < 1L) {
        AppToast.show(this, getString(R.string.player_timer_custom_invalid))
        return
    }
    timerExitAfterCurrentVideo = false
    timerExitEndAtElapsedMs = SystemClock.elapsedRealtime() + totalSec * 1000L
    startTimerTick()
    AppToast.show(this, getString(R.string.player_timer_toast_set_custom_fmt, hours, minutes, seconds))
    refreshTimerItems()
}

internal fun PlayerActivity.startTimerExitAfterCurrentVideo() {
    timerExitAfterCurrentVideo = true
    timerExitEndAtElapsedMs = 0L
    stopTimerTick()
    AppToast.show(this, getString(R.string.player_timer_toast_after_current))
    refreshTimerItems()
}

internal fun PlayerActivity.cancelTimerExit() {
    timerExitAfterCurrentVideo = false
    timerExitEndAtElapsedMs = 0L
    stopTimerTick()
    AppToast.show(this, getString(R.string.player_timer_toast_off))
    refreshTimerItems()
}

internal fun PlayerActivity.stopTimerTick() {
    timerExitTickJob?.cancel()
    timerExitTickJob = null
}

/** 倒计时到点后退出播放器；关闭面板不打断倒计时。 */
private fun PlayerActivity.startTimerTick() {
    if (timerExitTickJob?.isActive == true) return
    timerExitTickJob =
        lifecycleScope.launch {
            while (isActive) {
                if (timerExitAfterCurrentVideo) break
                val remaining = timerRemainingMs()
                if (remaining <= 0L) break
                refreshTimerItems()
                delay(1000L)
            }
            if (!isActive) return@launch
            refreshTimerItems()
            if (!timerExitAfterCurrentVideo && timerRemainingMs() <= 0L && timerExitEndAtElapsedMs > 0L) {
                timerExitEndAtElapsedMs = 0L
                finish()
            }
        }
}

internal fun PlayerActivity.refreshTimerItems(preferredFocusKey: String? = null) {
    val adapter = timerSettingsAdapter ?: return
    val items =
        buildList {
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerTimerKeys.STATUS,
                    title = getString(R.string.player_timer_status_label),
                    subtitle = timerStatusSubtitle(),
                ),
            )
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerTimerKeys.OFF,
                    title = getString(R.string.player_timer_off),
                    subtitle = null,
                ),
            )
            PLAYER_TIMER_CHOICES_MINUTES.forEach { minutes ->
                add(
                    PlayerSettingsAdapter.SettingItem(
                        key = minutesTimerKey(minutes),
                        title = getString(R.string.player_timer_minutes_fmt, minutes),
                        subtitle = null,
                    ),
                )
            }
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerTimerKeys.AFTER_CURRENT,
                    title = getString(R.string.player_timer_after_current),
                    subtitle = null,
                ),
            )
        }
    adapter.submit(items) {
        if (preferredFocusKey != null) {
            val pos = adapter.currentList.indexOfFirst { it.key == preferredFocusKey }
            if (pos in 0 until adapter.itemCount) {
                binding.recyclerTimer.scrollToPosition(pos)
                binding.recyclerTimer.post {
                    binding.recyclerTimer.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                }
            }
        }
    }
}
