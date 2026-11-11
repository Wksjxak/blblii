package blbl.cat3399.core.util

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import blbl.cat3399.feature.video.VideoDetailActivity

/**
 * 正文中的 BV 号识别：
 * - 命中的 BV 染成主题蓝（视觉提示）；
 * - TV 上「聚焦这一段/这条评论，500ms 内连按两次 OK」才跳视频，
 *   第一次按不拦截，照常走原本的展开/楼中楼点击，避免冲突。
 */
object BvLinkify {
    private val BV_REGEX = Regex("(?<![A-Za-z0-9])(BV[0-9A-Za-z]{10})(?![A-Za-z0-9])")

    private const val DOUBLE_TAP_MS = 500L

    fun firstBv(text: String): String? = BV_REGEX.find(text)?.value

    fun highlight(text: String, linkColor: Int): android.text.Spannable {
        val sp = android.text.SpannableString(text)
        for (m in BV_REGEX.findAll(text)) {
            sp.setSpan(
                android.text.style.ForegroundColorSpan(linkColor),
                m.range.first,
                m.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return sp
    }

    fun jump(context: Context, bvid: String) {
        val intent = Intent(context, VideoDetailActivity::class.java)
            .putExtra(VideoDetailActivity.EXTRA_BVID, bvid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * 给 [view] 装「双击 OK 跳 BV」：
     * - 遥控器 DPAD_CENTER / ENTER 在 500ms 内第二次抬起时吃掉并跳视频；
     * - 第一次抬起不拦截，view 自己的 onClick 正常触发；
     * - 没有 BV 返回 false，不动 TextView 的文本。
     */
    fun installDoubleTapJump(view: View, text: String, linkColor: Int): Boolean {
        val bv = firstBv(text) ?: return false
        if (view is TextView) {
            view.text = highlight(text, linkColor)
        }
        var lastTriggerMs = 0L
        view.setOnKeyListener { _, keyCode, event ->
            val isCenter = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (!isCenter) return@setOnKeyListener false
            if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastTriggerMs <= DOUBLE_TAP_MS) {
                lastTriggerMs = 0L
                jump(view.context, bv)
                true
            } else {
                lastTriggerMs = now
                false
            }
        }
        return true
    }
}
