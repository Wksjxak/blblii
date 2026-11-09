package blbl.cat3399.core.model

/**
 * Up 主「动态」Tab 的混合条目：视频类动态复用 [VideoCard]，图文/纯文字动态用 [Rich]。
 */
sealed class DynamicFeedItem {
    abstract val stableKey: String

    data class Video(val card: VideoCard) : DynamicFeedItem() {
        override val stableKey: String get() = card.stableKey()
    }

    data class Rich(
        val dynId: String,
        val dynOid: Long?,
        val commentType: Int,
        val text: String,
        val title: String?,
        val images: List<String>,
        val authorName: String,
        val authorFace: String?,
        val pubTs: Long?,
        val origAuthor: String? = null,
        val origTitle: String? = null,
        val origText: String? = null,
        val origCover: String? = null,
    ) : DynamicFeedItem() {
        override val stableKey: String get() = "rich:$dynId"
    }
}

/**
 * 动态详情正文的有序段落，用于图文混排渲染。
 */
sealed class DynamicParagraph {
    /** 文字段落，isHeading=true 表示标题/小标题 */
    data class Text(val text: String, val isHeading: Boolean = false) : DynamicParagraph()

    /** 图片段落 */
    data class Image(val url: String) : DynamicParagraph()

    /** 分割线 */
    object Separator : DynamicParagraph()
}
