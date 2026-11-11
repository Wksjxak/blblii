package blbl.cat3399.core.util

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import blbl.cat3399.BlblApp
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.feature.video.VideoDetailActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * 正文中的 BV 号识别：
 * - 命中的 BV 染成主题蓝；
 * - 异步拉一次视频信息，把 `BV1xxxx` 原地替换成视频标题（缓存）；
 * - TV 上「聚焦这段文字/这条评论，500ms 内连按两次 OK」跳第一个 BV；
 * - 第一次按不拦截，照常走原本的展开/楼中楼点击。
 */
object BvLinkify {
    private val BV_REGEX = Regex("(?<![A-Za-z0-9])(BV[0-9A-Za-z]{10})(?![A-Za-z0-9])")

    private const val DOUBLE_TAP_MS = 500L

    /** bvid -> 标题（null 表示已查但失败/空） */
    private val titleCache = ConcurrentHashMap<String, String?>()

    /** 用 raw text 当 tag，异步回来后只在文本没变时重绘，避免评论列表串位 */
    private val TAG_RAW_TEXT = blbl.cat3399.R.id.blbl_bv_raw_text

    fun findAll(text: String): List<String> =
        BV_REGEX.findAll(text).map { it.value }.distinct().toList()

    private fun render(text: String, titles: Map<String, String?>, color: Int): android.text.Spanned {
        // 先把每个 BV 替换成标题，再整体染色
        val replaced = findAll(text).fold(text) { acc, bv ->
            val title = titles[bv]?.takeIf { it.isNotBlank() }
            val label = title?.let { "《$it》" } ?: bv
            acc.replace(bv, label)
        }
        val sp = android.text.SpannableString(replaced)
        // 高亮：所有 BV/《标题》段都染蓝。简化处理——按原 BV/title 串再找一遍并染色
        for (bv in findAll(text)) {
            val title = titles[bv]?.takeIf { it.isNotBlank() }
            val label = title?.let { "《$it》" } ?: bv
            var idx = 0
            while (true) {
                val hit = replaced.indexOf(label, idx)
                if (hit < 0) break
                sp.setSpan(
                    android.text.style.ForegroundColorSpan(color),
                    hit,
                    hit + label.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                idx = hit + label.length
            }
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
     * 装「双击 OK 跳 BV」。返回是否命中 BV。
     */
    fun installDoubleTapJump(view: View, text: String, linkColor: Int): Boolean {
        val bvs = findAll(text)
        if (bvs.isEmpty()) return false
        view.setTag(TAG_RAW_TEXT, text)

        // 立即渲染（可能标题还没拉到）
        if (view is TextView) {
            view.text = render(text, titleCache.filterKeys { it in bvs }, linkColor)
        }
        // 拉缺失的标题，回来重绘
        val missing = bvs.filter { !titleCache.containsKey(it) }
        if (missing.isNotEmpty()) {
            BlblApp.launchIo {
                for (bv in missing) {
                    runCatching { BiliApi.videoDetail(bv) }
                        .onSuccess { titleCache[bv] = it.title }
                        .onFailure { titleCache[bv] = null }
                }
                (view as? TextView)?.post {
                    if (view.getTag(TAG_RAW_TEXT) == text) {
                        view.text = render(text, titleCache.filterKeys { it in bvs }, linkColor)
                    }
                }
            }
        }

        var lastTriggerMs = 0L
        val firstBv = bvs.first()
        view.setOnKeyListener { _, keyCode, event ->
            val isCenter = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (!isCenter) return@setOnKeyListener false
            if (event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastTriggerMs <= DOUBLE_TAP_MS) {
                lastTriggerMs = 0L
                jump(view.context, firstBv)
                true
            } else {
                lastTriggerMs = now
                false
            }
        }
        return true
    }
}
