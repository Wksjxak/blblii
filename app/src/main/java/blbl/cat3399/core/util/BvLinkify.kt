package blbl.cat3399.core.util

import android.content.Context
import android.content.Intent
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import blbl.cat3399.feature.video.VideoDetailActivity

/**
 * 正文中的 BV 号识别：
 * - 命中的 BV 染成主题蓝；
 * - 整段文字可点（TV 遥控器 OK / 鼠标点击）即跳到对应视频。
 */
object BvLinkify {
    // BV + 10 位 base58 字符，前后不贴身字符，避免把长链接里的片段误截
    private val BV_REGEX = Regex("(?<![A-Za-z0-9])(BV[0-9A-Za-z]{10})(?![A-Za-z0-9])")

    fun firstBv(text: String): String? = BV_REGEX.find(text)?.value

    /** 把 text 里的 BV 染蓝。 */
    fun highlight(text: String, linkColor: Int): Spannable {
        val sp = SpannableString(text)
        for (m in BV_REGEX.findAll(text)) {
            sp.setSpan(
                ForegroundColorSpan(linkColor),
                m.range.first,
                m.range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return sp
    }

    /** 跳转到视频详情。 */
    fun jump(context: Context, bvid: String) {
        val intent = Intent(context, VideoDetailActivity::class.java)
            .putExtra(VideoDetailActivity.EXTRA_BVID, bvid)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * 一键给 TextView 上色 + 点击跳第一个 BV。
     * @return 文本里有没有 BV
     */
    fun apply(tv: TextView, text: String, linkColor: Int): Boolean {
        val bv = firstBv(text) ?: return false
        tv.text = highlight(text, linkColor)
        tv.setOnClickListener { jump(tv.context, bv) }
        return true
    }
}
