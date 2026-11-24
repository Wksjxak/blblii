package blbl.cat3399.core.api

import blbl.cat3399.core.model.DynamicFeedItem
import blbl.cat3399.core.model.VideoCard

/**
 * UP 主动态流过滤。纯函数、不依赖 Android 框架，便于单测。
 *
 * 判定按卡片归属（major 类型 + 作者 mid），不再用投稿第一页 30 条 BV 集合做全局匹配。
 * 原因分桶（与日志 reason 对齐）：
 * - [Result.archiveHidden]：hideArchiveVideo=true 且为 host 本人「投稿视频」（reason=archive）；
 *   动态视频（pub_action=发布了动态视频）不属于投稿，永远不在这里隐藏；
 * - [Result.dynamicVideoHidden]：hideDynamicVideo=true 且为 host 本人「动态视频」（reason=dynamicvideo_hidden）；
 * - [Result.videoHidden]：showVideo=false 时丢弃的其它视频类卡片（reason=showvideo_hidden）；
 * - [Result.dup]：stableKey 已在本页之前出现过（reason=dup，翻页去重）。
 *
 * 语义（与设置文案一致）：
 * - up_dynamic_hide_archive_video：只隐藏 UP 本人投稿视频（和投稿 Tab 重复的那部分）；
 * - up_dynamic_hide_dynamic_video：只隐藏 UP 本人动态视频（发布了动态视频）；
 *   两项都不隐藏合集、附加分享或转发他人视频；
 * - up_dynamic_show_video：视频类动态总开关，false 时归档/合集/附加 ugc 一律不进列表。
 */
internal object DynamicFeedFilter {

    const val REASON_ARCHIVE = "archive"
    const val REASON_DYNAMIC_VIDEO_HIDDEN = "dynamicvideo_hidden"
    const val REASON_VIDEO_HIDDEN = "showvideo_hidden"
    const val REASON_DUP = "dup"

    data class Result(
        val fresh: List<DynamicFeedItem>,
        val archiveHidden: Int,
        val dynamicVideoHidden: Int,
        val videoHidden: Int,
        val dup: Int,
        /** 本页原始构成：归档视频数。 */
        val rawArchive: Int = 0,
        /** 本页原始构成：合集/系列视频数。 */
        val rawSeason: Int = 0,
        /** 本页原始构成：附加分享 ugc 视频数。 */
        val rawExtra: Int = 0,
        /** 本页原始构成：动态视频数（pub_action=发布了动态视频）。 */
        val rawDynamicVideo: Int = 0,
        /** 本页原始构成：图文/纯文字动态数。 */
        val rawRich: Int = 0,
    ) {
        val filteredTotal: Int get() = archiveHidden + dynamicVideoHidden + videoHidden + dup
    }

    fun filter(
        items: List<DynamicFeedItem>,
        hostMid: Long,
        hideArchiveVideo: Boolean,
        showVideo: Boolean,
        seen: MutableSet<String>,
        hideDynamicVideo: Boolean = false,
    ): Result {
        val fresh = ArrayList<DynamicFeedItem>()
        var archiveHidden = 0
        var dynamicVideoHidden = 0
        var videoHidden = 0
        var dup = 0
        var rawArchive = 0
        var rawSeason = 0
        var rawExtra = 0
        var rawDynamicVideo = 0
        var rawRich = 0

        for (item in items) {
            val card = (item as? DynamicFeedItem.Video)?.card
            when {
                card == null -> rawRich++
                card.majorType == VideoCard.MAJOR_TYPE_UGC_SEASON -> rawSeason++
                card.majorType == VideoCard.ADDITIONAL_UGC -> rawExtra++
                card.isDynamicVideo -> rawDynamicVideo++
                else -> rawArchive++
            }

            val isSubmissionArchive = card != null && card.isArchiveVideo && card.isSubmissionVideo && !card.isDynamicVideo
            val isDynamicArchive = card != null && card.isArchiveVideo && card.isDynamicVideo && !card.isSubmissionVideo
            val isVideo = card != null && card.isVideoCard
            // 作者 mid 缺失时无法确认归属，按保守策略保留，避免误隐藏动态视频或他人视频。
            val ownerMatchesHost = card != null && card.ownerMid == hostMid

            when {
                hideArchiveVideo && isSubmissionArchive && ownerMatchesHost -> archiveHidden++
                hideDynamicVideo && isDynamicArchive && ownerMatchesHost -> dynamicVideoHidden++
                !showVideo && isVideo -> videoHidden++
                !seen.add(item.stableKey) -> dup++
                else -> fresh.add(item)
            }
        }

        return Result(
            fresh,
            archiveHidden,
            dynamicVideoHidden,
            videoHidden,
            dup,
            rawArchive,
            rawSeason,
            rawExtra,
            rawDynamicVideo,
            rawRich,
        )
    }

    /** 调试用：保留下来的视频卡片 BV（仅 debug 包打印）。 */
    fun keptBvids(fresh: List<DynamicFeedItem>): List<String> =
        fresh.filterIsInstance<DynamicFeedItem.Video>().map { it.card.bvid }

    /** 供解析器复用的类型常量判断：直播推荐卡片（major.live_rcmd）不进列表。 */
    fun isLiveRcmd(majorType: String?): Boolean = majorType == "MAJOR_TYPE_LIVE_RCMD"
}
