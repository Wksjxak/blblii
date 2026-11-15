package blbl.cat3399.core.api

import blbl.cat3399.core.model.DynamicFeedItem
import blbl.cat3399.core.model.VideoCard

/**
 * UP 主动态流过滤。纯函数、不依赖 Android 框架，便于单测。
 *
 * 判定按卡片归属（major 类型 + 作者 mid），不再用投稿第一页 30 条 BV 集合做全局匹配。
 * 原因分桶：
 * - [Result.archiveHidden]：hideArchiveVideo=true 且为 host 本人归档视频；
 * - [Result.videoHidden]：showVideo=false 时丢弃的视频类卡片（归档/合集/附加 ugc）；
 * - [Result.dup]：stableKey 已在本页之前出现过（翻页去重）。
 */
internal object DynamicFeedFilter {

    data class Result(
        val fresh: List<DynamicFeedItem>,
        val archiveHidden: Int,
        val videoHidden: Int,
        val dup: Int,
    ) {
        val filteredTotal: Int get() = archiveHidden + videoHidden + dup
    }

    fun filter(
        items: List<DynamicFeedItem>,
        hostMid: Long,
        hideArchiveVideo: Boolean,
        showVideo: Boolean,
        seen: MutableSet<String>,
    ): Result {
        val fresh = ArrayList<DynamicFeedItem>()
        var archiveHidden = 0
        var videoHidden = 0
        var dup = 0

        for (item in items) {
            val card = (item as? DynamicFeedItem.Video)?.card
            val isArchive = card != null && card.isArchiveVideo
            val isVideo = card != null && card.isVideoCard
            // 空间动态里作者 mid 缺失时按 host 本人处理；非空则必须与 hostMid 一致才算本人归档。
            val ownerMatchesHost = card == null || card.ownerMid == null || card.ownerMid == hostMid

            when {
                hideArchiveVideo && isArchive && ownerMatchesHost -> archiveHidden++
                !showVideo && isVideo -> videoHidden++
                !seen.add(item.stableKey) -> dup++
                else -> fresh.add(item)
            }
        }

        return Result(fresh, archiveHidden, videoHidden, dup)
    }

    /** 调试用：保留下来的视频卡片 BV（仅 debug 包打印）。 */
    fun keptBvids(fresh: List<DynamicFeedItem>): List<String> =
        fresh.filterIsInstance<DynamicFeedItem.Video>().map { it.card.bvid }

    /** 供解析器复用的类型常量判断：直播推荐卡片（major.live_rcmd）不进列表。 */
    fun isLiveRcmd(majorType: String?): Boolean = majorType == "MAJOR_TYPE_LIVE_RCMD"
}
