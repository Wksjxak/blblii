package blbl.cat3399.feature.player.danmaku.model

internal sealed interface DanmakuInlineSegment {
    data class Text(
        val start: Int,
        val end: Int,
    ) : DanmakuInlineSegment

    data class Emote(
        val url: String,
    ) : DanmakuInlineSegment

    /** 合并计数角标（如「×3」），用独立等宽字体渲染。 */
    data class CountBadge(
        val start: Int,
        val end: Int,
    ) : DanmakuInlineSegment

    data object HighLikeIcon : DanmakuInlineSegment
}
