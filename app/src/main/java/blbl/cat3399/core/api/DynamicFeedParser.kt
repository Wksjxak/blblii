package blbl.cat3399.core.api

import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.DynamicFeedItem
import blbl.cat3399.core.model.DynamicParagraph
import blbl.cat3399.core.model.VideoCard
import org.json.JSONArray
import org.json.JSONObject

/**
 * 动态 feed 列表的 JSON → 卡片解析。
 * 从 BiliApi 抽出，避免 BiliApi 继续膨胀；具体每种 major 类型
 * （archive / ugc_season / additional.ugc / opus / draw / article / live_reverse）
 * 都在这里分发。
 */
internal object DynamicFeedParser {

    private const val TAG = "BiliApi"

    fun parse(bili: BiliApi, items: JSONArray): List<DynamicFeedItem> {
        val cards = ArrayList<DynamicFeedItem>()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            try {
                parseItem(bili, it)?.let { c -> cards.add(c) }
            } catch (e: Exception) {
                AppLog.w(TAG, "parseDynamicFeedItems skip item $i: ${e.message}")
            }
        }
        return cards
    }

    private fun parseItem(bili: BiliApi, it: JSONObject): DynamicFeedItem? {
        val trackId = it.optString("track_id", it.optString("trackid", "")).trim().takeIf { v -> v.isNotBlank() }
        val modules = it.optJSONObject("modules") ?: return null
        val moduleDynamic = modules.optJSONObject("module_dynamic") ?: return null
        val major = moduleDynamic.optJSONObject("major")

        val author = modules.optJSONObject("module_author")
        val ownerName = author?.optString("name", "") ?: ""
        val ownerFace = author?.optString("face")?.takeIf { f -> f.isNotBlank() }
        val ownerMid = author?.optLong("mid")?.takeIf { v -> v > 0 }
        val authorPubTs = author?.optLong("pub_ts")?.takeIf { v -> v > 0 }

        // 视频类：投稿/合集/充电视频
        parseArchiveVideo(bili, major, ownerName, ownerFace, ownerMid, authorPubTs, trackId)?.let { return it }
        parseUgcSeasonVideo(bili, major, ownerName, ownerFace, ownerMid, authorPubTs, trackId)?.let { return it }
        parseAdditionalUgcVideo(bili, moduleDynamic, ownerName, ownerFace, ownerMid, authorPubTs, trackId)?.let { return it }

        // 图文 / 纯文字动态
        val desc = moduleDynamic.optJSONObject("desc")
        var text = desc?.optString("text", "").orEmpty().trim()
        val images = ArrayList<String>()
        var title: String? = null
        var isOpus = false
        var opusId: Long? = null
        val paragraphs = ArrayList<DynamicParagraph>()

        major?.let { m ->
            title = m.optString("title", "").trim()
                .ifBlank { m.optString("jump_title", "").trim() }
                .ifBlank { m.optJSONObject("display")?.optString("title", "")?.trim().orEmpty() }
                .ifBlank { null }

            val opus = m.optJSONObject("opus")
            if (opus != null) {
                isOpus = true
                if (title == null) {
                    title = opus.optString("title", "").trim().ifBlank {
                        opus.optJSONObject("display")?.optString("title", "")?.trim().orEmpty()
                    }.ifBlank { null }
                }
                opusId = opus.optLong("opus_id", 0L).takeIf { v -> v > 0L }
                    ?: opus.optLong("id", 0L).takeIf { v -> v > 0L }
                opus.optJSONArray("pics")?.let { arr ->
                    for (k in 0 until arr.length()) {
                        val u = arr.optJSONObject(k)?.optString("url", "")
                        if (!u.isNullOrBlank()) images.add(u)
                    }
                }
                paragraphs.addAll(DynamicBodyParser.parseOpusParagraphs(opus.optJSONArray("paragraphs")))
                // 列表接口对短图文动态不带 paragraphs，文字直接挂在 summary.text 上
                if (text.isBlank()) {
                    text = opus.optJSONObject("summary")?.optString("text", "").orEmpty().trim()
                }
            }

            m.optJSONObject("draw")?.let { draw ->
                draw.optJSONArray("items")?.let { arr ->
                    for (k in 0 until arr.length()) {
                        val u = arr.optJSONObject(k)?.optString("src", "")
                        if (!u.isNullOrBlank()) images.add(u)
                    }
                }
            }

            // 老动态类型兜底：专栏(cv)、直播回放/预约
            m.optJSONObject("article")?.let { art ->
                if (title == null) {
                    title = art.optString("title", "").trim()
                        .ifBlank { art.optString("jump_title", "").trim() }
                        .ifBlank { null }
                }
                art.optString("cover", "").trim().takeIf { c -> c.isNotBlank() }?.let { images.add(it) }
                if (text.isBlank()) text = art.optString("desc", "").trim()
            }
            m.optJSONObject("live_reverse")?.let { lv ->
                if (title == null) {
                    title = lv.optString("title", "").trim()
                        .ifBlank { lv.optString("desc", "").trim() }
                        .ifBlank { null }
                }
                lv.optString("cover", "").trim().takeIf { c -> c.isNotBlank() }?.let { images.add(it) }
            }
        }

        if (paragraphs.isNotEmpty()) {
            val joined = paragraphs.filterIsInstance<DynamicParagraph.Text>()
                .joinToString("\n") { p -> p.text }
                .trim()
            if (joined.isNotBlank()) text = joined
            AppLog.d(
                TAG,
                "opus paragraphs dyn=${it.optString("id_str", "")} total=${paragraphs.size} " +
                    "text=${paragraphs.count { p -> p is DynamicParagraph.Text }} " +
                    "img=${paragraphs.count { p -> p is DynamicParagraph.Image }}",
            )
        }

        if (text.isBlank() && images.isEmpty() && title.isNullOrBlank()) {
            logFeedDrop(it, modules, major, moduleDynamic)
            return null
        }

        val rawId = it.optString("id_str", it.optString("id", "")).ifBlank {
            trackId ?: "rich_${ownerName}_${authorPubTs ?: 0}"
        }
        val basic = it.optJSONObject("basic")
        val commentOid = basic?.optString("comment_id_str", "")?.toLongOrNull()
            ?: basic?.optLong("comment_id", 0L)?.takeIf { v -> v > 0L }
        val commentTypeFromBasic = basic?.optInt("comment_type", 0)?.takeIf { v -> v > 0 }
        val finalCommentType = commentTypeFromBasic ?: (if (isOpus) 12 else 17)
        val finalOid = commentOid ?: runCatching {
            desc?.optLong("dyn_id", 0L)?.takeIf { v -> v > 0L }
                ?: desc?.optString("dyn_str", "")?.toLongOrNull()
                ?: it.optLong("id", 0L).takeIf { v -> v > 0L }
                ?: it.optString("id_str", "").toLongOrNull()
        }.getOrNull() ?: opusId

        // 转发原动态摘要
        var origAuthor: String? = null
        var origTitle: String? = null
        var origText: String? = null
        var origCover: String? = null
        var origDynId: String? = null
        val orig = moduleDynamic.optJSONObject("orig") ?: it.optJSONObject("orig")
        if (orig != null) {
            origDynId = orig.optString("id_str", "").trim().ifBlank { null }
                ?: orig.optJSONObject("basic")?.optString("id_str", "")?.trim()?.ifBlank { null }
                ?: orig.optString("id", "").trim().ifBlank { null }
                ?: orig.optJSONObject("modules")?.optJSONObject("module_dynamic")
                    ?.optJSONObject("desc")?.optString("dyn_id_str", "")?.trim()?.ifBlank { null }
            val origModules = orig.optJSONObject("modules")
            origModules?.optJSONObject("module_author")?.let { a ->
                origAuthor = a.optString("name", "").trim().ifBlank { null }
            }
            val origDynamic = origModules?.optJSONObject("module_dynamic")
            val origDesc = origDynamic?.optJSONObject("desc")
            origText = origDesc?.optString("text", "")?.trim()?.ifBlank { null }
            val origMajor = origDynamic?.optJSONObject("major")
            origMajor?.optJSONObject("archive")?.let { arc ->
                origTitle = arc.optString("title", "").trim().ifBlank { null }
                origCover = arc.optString("pic", "").trim().ifBlank { null }
            }
            if (origTitle == null) {
                origMajor?.optJSONObject("opus")?.let { op ->
                    origTitle = op.optString("title", "").trim().ifBlank { null }
                    if (origCover == null) {
                        origCover = op.optJSONArray("pics")?.optJSONObject(0)?.optString("url", "")?.trim()?.ifBlank { null }
                    }
                }
            }
            if (origCover == null) {
                origMajor?.optJSONObject("draw")?.let { dr ->
                    origCover = dr.optJSONArray("items")?.optJSONObject(0)?.optString("src", "")?.trim()?.ifBlank { null }
                }
            }
        }

        return DynamicFeedItem.Rich(
            dynId = rawId,
            dynOid = finalOid,
            commentType = finalCommentType,
            text = text,
            title = title,
            images = images.take(3),
            authorName = ownerName,
            authorFace = ownerFace,
            pubTs = authorPubTs,
            origAuthor = origAuthor,
            origTitle = origTitle,
            origText = origText,
            origCover = origCover,
            origDynId = origDynId,
            paragraphs = paragraphs,
        )
    }

    private fun parseArchiveVideo(
        bili: BiliApi,
        major: JSONObject?,
        ownerName: String,
        ownerFace: String?,
        ownerMid: Long?,
        authorPubTs: Long?,
        trackId: String?,
    ): DynamicFeedItem.Video? {
        val archive = major?.optJSONObject("archive") ?: return null
        val bvid = archive.optString("bvid", "")
        if (bvid.isBlank()) return null
        val stat = archive.optJSONObject("stat") ?: JSONObject()
        val aid = archive.optLong("aid").takeIf { v -> v > 0 }
            ?: archive.optLong("avid").takeIf { v -> v > 0 }
        val charging = bili.isChargingArc(
            isChargingArc = archive.optBoolean("is_charging_arc", false),
            elecArcType = archive.optInt("elec_arc_type", 0),
            badgeText = archive.optJSONObject("badge")?.optString("text", ""),
        )
        val pubDate = archive.optLong("pubdate").takeIf { v -> v > 0 } ?: authorPubTs
        return DynamicFeedItem.Video(
            VideoCard(
                bvid = bvid,
                cid = null,
                aid = aid,
                title = archive.optString("title", ""),
                coverUrl = archive.optString("cover", ""),
                durationSec = bili.parseDuration(archive.optString("duration_text", "0:00")),
                ownerName = ownerName,
                ownerFace = ownerFace,
                ownerMid = ownerMid,
                view = bili.parseCountText(stat.optString("play", "")),
                danmaku = bili.parseCountText(stat.optString("danmaku", "")),
                pubDate = pubDate,
                pubDateText = null,
                isChargingArc = charging,
                trackId = trackId,
            ),
        )
    }

    private fun parseUgcSeasonVideo(
        bili: BiliApi,
        major: JSONObject?,
        ownerName: String,
        ownerFace: String?,
        ownerMid: Long?,
        authorPubTs: Long?,
        trackId: String?,
    ): DynamicFeedItem.Video? {
        val ugcSeason = major?.optJSONObject("ugc_season") ?: return null
        val jumpUrl = ugcSeason.optString("jump_url", "").takeIf { u -> u.isNotBlank() }
        val aid = ugcSeason.optLong("aid").takeIf { v -> v > 0 }
        val bvid = bili.parseBvidFromJumpUrl(jumpUrl) ?: (aid?.let { bili.avToBv(it) }) ?: ""
        if (bvid.isBlank()) return null
        val stat = ugcSeason.optJSONObject("stat") ?: JSONObject()
        val charging = bili.isChargingArc(
            isChargingArc = ugcSeason.optBoolean("is_charging_arc", false),
            elecArcType = ugcSeason.optInt("elec_arc_type", 0),
            badgeText = ugcSeason.optJSONObject("badge")?.optString("text", ""),
        )
        return DynamicFeedItem.Video(
            VideoCard(
                bvid = bvid,
                cid = null,
                aid = aid,
                title = ugcSeason.optString("title", ""),
                coverUrl = ugcSeason.optString("cover", ""),
                durationSec = bili.parseDuration(ugcSeason.optString("duration_text", "0:00")),
                ownerName = ownerName,
                ownerFace = ownerFace,
                ownerMid = ownerMid,
                view = bili.parseCountText(stat.optString("play", "")),
                danmaku = bili.parseCountText(stat.optString("danmaku", "")),
                pubDate = authorPubTs,
                pubDateText = null,
                isChargingArc = charging,
                trackId = trackId,
            ),
        )
    }

    private fun parseAdditionalUgcVideo(
        bili: BiliApi,
        moduleDynamic: JSONObject,
        ownerName: String,
        ownerFace: String?,
        ownerMid: Long?,
        authorPubTs: Long?,
        trackId: String?,
    ): DynamicFeedItem.Video? {
        val additionalUgc = moduleDynamic.optJSONObject("additional")?.optJSONObject("ugc") ?: return null
        val jumpUrl = additionalUgc.optString("jump_url", "").takeIf { u -> u.isNotBlank() }
        val aid = additionalUgc.optString("id_str", "").toLongOrNull()?.takeIf { v -> v > 0 }
        val bvid = bili.parseBvidFromJumpUrl(jumpUrl) ?: (aid?.let { bili.avToBv(it) }) ?: ""
        if (bvid.isBlank()) return null
        val charging = bili.isChargingArc(
            isChargingArc = additionalUgc.optBoolean("is_charging_arc", false),
            elecArcType = additionalUgc.optInt("elec_arc_type", 0),
            badgeText = additionalUgc.optString("head_text", ""),
        )
        return DynamicFeedItem.Video(
            VideoCard(
                bvid = bvid,
                cid = null,
                aid = aid,
                title = additionalUgc.optString("title", ""),
                coverUrl = additionalUgc.optString("cover", ""),
                durationSec = bili.parseDuration(additionalUgc.optString("duration", "0:00")),
                ownerName = ownerName,
                ownerFace = ownerFace,
                ownerMid = ownerMid,
                view = null,
                danmaku = null,
                pubDate = authorPubTs,
                pubDateText = null,
                isChargingArc = charging,
                trackId = trackId,
            ),
        )
    }

    private fun logFeedDrop(it: JSONObject, modules: JSONObject, major: JSONObject?, moduleDynamic: JSONObject) {
        val draw = major?.optJSONObject("draw")
        val drawItems = draw?.optJSONArray("items")?.length() ?: -1
        val drawSample = draw?.optJSONArray("items")?.optJSONObject(0)?.keys()?.asSequence()?.joinToString(",").orEmpty()
        val opusKeys = major?.optJSONObject("opus")?.keys()?.asSequence()?.joinToString(",").orEmpty()
        val majorBreakdown = StringBuilder()
        if (major != null) {
            val keys = major.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = major.opt(k)
                val d = when (v) {
                    is JSONObject -> "obj{${v.keys().asSequence().take(8).joinToString(",")}}"
                    is JSONArray -> "arr[${v.length()}]"
                    is String -> "str(len=${v.length})"
                    is Number -> "num($v)"
                    else -> v?.javaClass?.simpleName ?: "null"
                }
                if (majorBreakdown.isNotEmpty()) majorBreakdown.append(' ')
                majorBreakdown.append("$k=$d")
            }
        }
        val descFull = moduleDynamic.optJSONObject("desc")?.optString("text", "").orEmpty()
            .let { t -> if (t.length > 500) t.take(500) + "…(${t.length})" else t }
        val majorDump = major?.toString().orEmpty().let { s ->
            if (s.length > 1500) s.take(1500) + "…(${s.length})" else s
        }
        AppLog.d(
            TAG,
            "feedDrop type=${it.optString("type", "")} " +
                "modulesKeys=${modules.keys().asSequence().joinToString(",")} " +
                "drawItems=$drawItems drawItemKeys=[$drawSample] opusKeys=[$opusKeys]\n" +
                "majorBreakdown: $majorBreakdown\n" +
                "descText: [$descFull]\n" +
                "majorDump: $majorDump",
        )
    }
}
