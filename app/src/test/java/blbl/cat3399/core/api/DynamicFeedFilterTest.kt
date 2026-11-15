package blbl.cat3399.core.api

import blbl.cat3399.core.model.DynamicFeedItem
import blbl.cat3399.core.model.VideoCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicFeedFilterTest {

    private val hostMid = 343799430L
    private val otherMid = 496848037L

    private fun video(
        bvid: String,
        majorType: String?,
        ownerMid: Long? = hostMid,
    ) = DynamicFeedItem.Video(
        VideoCard(
            bvid = bvid,
            cid = null,
            title = "t-$bvid",
            coverUrl = "",
            durationSec = 0,
            ownerName = "up",
            ownerFace = null,
            ownerMid = ownerMid,
            view = null,
            danmaku = null,
            pubDate = null,
            pubDateText = null,
            majorType = majorType,
        ),
    )

    private fun rich(dynId: String) = DynamicFeedItem.Rich(
        dynId = dynId,
        dynOid = null,
        commentType = 17,
        text = "图文 $dynId",
        title = null,
        images = emptyList(),
        authorName = "up",
        authorFace = null,
        pubTs = null,
    )

    @Test
    fun `hide host archive counts archive and drops it`() {
        val items = listOf(video("BV1A", VideoCard.MAJOR_TYPE_ARCHIVE, hostMid))
        val r = DynamicFeedFilter.filter(items, hostMid, hideArchiveVideo = true, showVideo = true, HashSet())

        assertEquals(1, r.archiveHidden)
        assertEquals(0, r.videoHidden)
        assertTrue(r.fresh.isEmpty())
    }

    @Test
    fun `archive from other author is kept when hiding host archive`() {
        val items = listOf(video("BV1B", VideoCard.MAJOR_TYPE_ARCHIVE, otherMid))
        val r = DynamicFeedFilter.filter(items, hostMid, hideArchiveVideo = true, showVideo = true, HashSet())

        assertEquals(0, r.archiveHidden)
        assertEquals(1, r.fresh.size)
    }

    @Test
    fun `archive with missing owner mid is treated as host`() {
        val items = listOf(video("BV1C", VideoCard.MAJOR_TYPE_ARCHIVE, ownerMid = null))
        val r = DynamicFeedFilter.filter(items, hostMid, hideArchiveVideo = true, showVideo = true, HashSet())

        assertEquals(1, r.archiveHidden)
        assertTrue(r.fresh.isEmpty())
    }

    @Test
    fun `showVideo false drops every video type but keeps rich`() {
        val items = listOf(
            video("BV1D", VideoCard.MAJOR_TYPE_ARCHIVE),
            video("BV1E", VideoCard.MAJOR_TYPE_UGC_SEASON),
            video("BV1F", VideoCard.ADDITIONAL_UGC),
            rich("rich-1"),
        )
        val r = DynamicFeedFilter.filter(items, hostMid, hideArchiveVideo = false, showVideo = false, HashSet())

        assertEquals(3, r.videoHidden)
        assertEquals(0, r.archiveHidden)
        assertEquals(1, r.fresh.size)
        assertTrue(r.fresh.first() is DynamicFeedItem.Rich)
        assertEquals(1, r.rawArchive)
        assertEquals(1, r.rawSeason)
        assertEquals(1, r.rawExtra)
        assertEquals(1, r.rawRich)
    }

    @Test
    fun `showVideo true keeps season and additional ugc`() {
        val items = listOf(
            video("BV1G", VideoCard.MAJOR_TYPE_UGC_SEASON),
            video("BV1H", VideoCard.ADDITIONAL_UGC),
        )
        val r = DynamicFeedFilter.filter(items, hostMid, hideArchiveVideo = false, showVideo = true, HashSet())

        assertEquals(0, r.videoHidden)
        assertEquals(2, r.fresh.size)
    }

    @Test
    fun `duplicate stable key is counted as dup`() {
        val first = video("BV1I", VideoCard.ADDITIONAL_UGC)
        val second = video("BV1I", VideoCard.ADDITIONAL_UGC)
        val r = DynamicFeedFilter.filter(listOf(first, second), hostMid, false, true, HashSet())

        assertEquals(1, r.dup)
        assertEquals(1, r.fresh.size)
    }

    @Test
    fun `kept bvids only lists surviving video cards`() {
        val items = listOf(
            video("BV1J", VideoCard.MAJOR_TYPE_ARCHIVE, otherMid),
            rich("rich-2"),
        )
        val r = DynamicFeedFilter.filter(items, hostMid, false, true, HashSet())

        assertEquals(listOf("BV1J"), DynamicFeedFilter.keptBvids(r.fresh))
        assertFalse(r.fresh.none { it is DynamicFeedItem.Video })
    }
}
