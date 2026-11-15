package blbl.cat3399.core.model

data class VideoCard(
    val bvid: String,
    val cid: Long?,
    val aid: Long? = null,
    val epId: Long? = null,
    val business: String? = null,
    val subType: Int? = null,
    val title: String,
    val coverUrl: String,
    val durationSec: Int,
    val ownerName: String,
    val ownerFace: String?,
    val ownerMid: Long? = null,
    val view: Long?,
    val danmaku: Long?,
    val pubDate: Long?,
    val pubDateText: String?,
    val isChargingArc: Boolean = false,
    // Optional overlay text rendered on the cover (bottom-left). Used by PGC episode cards.
    val coverLeftBottomText: String? = null,
    // Optional access badge rendered on the cover (top-right). Used by PGC ("大会员"/"限免"/"付费") etc.
    val accessBadgeText: String? = null,
    // Optional season id (ssid). Used by PGC playback/reporting flows.
    val seasonId: Long? = null,
    // Optional last watched progress from listing APIs (seconds).
    val progressSec: Long? = null,
    // True when the listing API marks this item as fully watched.
    val progressFinished: Boolean = false,
    // Optional recommendation/search routing id used by web feedback/report flows.
    val trackId: String? = null,
    /**
     * 动态 major 来源类型，取值见 [MajorType]。用于动态流按卡片归属过滤；
     * 非动态接口构造的卡片留空（null），不参与归档判定。
     */
    val majorType: String? = null,
) {
    /** 是否为 UP 主自己投的归档视频（major.archive / DYNAMIC_TYPE_AV）。 */
    val isArchiveVideo: Boolean
        get() = majorType == MAJOR_TYPE_ARCHIVE

    fun stableKey(): String =
        when {
            bvid.isNotBlank() -> "bvid:$bvid"
            epId != null && epId > 0 -> "ep:$epId"
            seasonId != null && seasonId > 0 -> "sid:$seasonId"
            aid != null && aid > 0 -> "aid:$aid"
            cid != null && cid > 0 -> "cid:$cid"
            else -> "title:${title.hashCode()}"
        }

    companion object {
        /** 投稿归档视频（major.archive，等价 DYNAMIC_TYPE_AV / MAJOR_TYPE_ARCHIVE）。 */
        const val MAJOR_TYPE_ARCHIVE = "MAJOR_TYPE_ARCHIVE"

        /** 合集/系列视频（major.ugc_season）。 */
        const val MAJOR_TYPE_UGC_SEASON = "MAJOR_TYPE_UGC_SEASON"

        /** 用户在动态里附加分享的 ugc 视频（module_dynamic.additional.ugc）。 */
        const val ADDITIONAL_UGC = "ADDITIONAL_UGC"
    }
}
