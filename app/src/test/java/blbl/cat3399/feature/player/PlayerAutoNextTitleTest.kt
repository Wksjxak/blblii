package blbl.cat3399.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerAutoNextTitleTest {
    @Test
    fun keepsShortTitlesAfterWhitespaceNormalization() {
        assertEquals("第12话", formatAutoNextHintTitle("  第12话  ", fallbackTitle = "推荐视频"))
    }

    @Test
    fun truncatesTitlesToEighteenCharactersBeforeEllipsis() {
        // 固定 18，长度被改动时这条用例要能报警。
        assertEquals("123456789012345678...", formatAutoNextHintTitle("12345678901234567890", fallbackTitle = "推荐视频"))
    }

    @Test
    fun keepsExactLengthTitlesUntouched() {
        val raw = "1".repeat(18)
        assertEquals(raw, formatAutoNextHintTitle(raw, fallbackTitle = "推荐视频"))
    }

    @Test
    fun neverSplitsSurrogatePairsWhenTruncating() {
        // 每个 emoji 占两个 UTF-16 单元，按 code point 截断时不能把它切成半个字符。
        val raw = "🎬".repeat(22)
        val formatted = formatAutoNextHintTitle(raw, fallbackTitle = "推荐视频")
        assertTrue(formatted.endsWith("..."))
        val truncated = formatted.removeSuffix("...")
        assertEquals(0, truncated.length % 2)
        assertEquals("🎬".repeat(18), truncated)
    }

    @Test
    fun fallsBackWhenTitleIsBlank() {
        assertEquals("推荐视频", formatAutoNextHintTitle("  \n\t  ", fallbackTitle = "推荐视频"))
    }
}
