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
        /** 评论 oid/type 是否直接取自接口 `basic.comment_id_str/comment_type`；
         *  简版图文接口不带 basic，需要打开评论时再从详情接口补一次路由。 */
        val commentRouted: Boolean = false,
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
        /** 被转发原动态的 id，用于拉取其完整图文正文 */
        val origDynId: String? = null,
        val paragraphs: List<DynamicParagraph> = emptyList(),
    ) : DynamicFeedItem() {
        override val stableKey: String get() = "rich:$dynId"

        /** 是否为转发动态（带原动态信息） */
        val isForward: Boolean
            get() = !origAuthor.isNullOrBlank() || !origTitle.isNullOrBlank() ||
                !origText.isNullOrBlank() || !origCover.isNullOrBlank()
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
