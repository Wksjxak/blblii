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
        val text: String,
        val title: String?,
        val images: List<String>,
        val authorName: String,
        val authorFace: String?,
        val pubTs: Long?,
    ) : DynamicFeedItem() {
        override val stableKey: String get() = "rich:$dynId"
    }
}
