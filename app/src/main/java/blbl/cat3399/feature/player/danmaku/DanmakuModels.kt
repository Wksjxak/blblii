package blbl.cat3399.feature.player.danmaku

import android.graphics.Typeface
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.prefs.AppPrefs

enum class DanmakuLaneDensity(
    val prefValue: String,
    val laneHeightFactor: Float,
) {
    Sparse(AppPrefs.DANMAKU_LANE_DENSITY_SPARSE, 1.25f),
    Standard(AppPrefs.DANMAKU_LANE_DENSITY_STANDARD, 1.0f),
    Dense(AppPrefs.DANMAKU_LANE_DENSITY_DENSE, 0.85f),
    ;

    companion object {
        fun fromPrefValue(value: String): DanmakuLaneDensity =
            when (value.trim()) {
                AppPrefs.DANMAKU_LANE_DENSITY_SPARSE -> Sparse
                AppPrefs.DANMAKU_LANE_DENSITY_DENSE -> Dense
                else -> Standard
            }
    }
}

enum class DanmakuFontWeight(
    val prefValue: String,
    val typeface: Typeface,
) {
    Normal(AppPrefs.DANMAKU_FONT_WEIGHT_NORMAL, Typeface.DEFAULT),
    Bold(AppPrefs.DANMAKU_FONT_WEIGHT_BOLD, Typeface.DEFAULT_BOLD),
    ;

    companion object {
        fun fromPrefValue(value: String): DanmakuFontWeight =
            when (value.trim()) {
                AppPrefs.DANMAKU_FONT_WEIGHT_NORMAL -> Normal
                else -> Bold
            }
    }
}

data class DanmakuConfig(
    val enabled: Boolean,
    val opacity: Float,
    val textSizeSp: Float,
    val fontWeight: DanmakuFontWeight,
    val strokeWidthPx: Int,
    val speedLevel: Int,
    val area: Float,
    val laneDensity: DanmakuLaneDensity,
    val showHighLikeIcon: Boolean,
    val areaIncludeScroll: Boolean = true,
    val areaIncludeTop: Boolean = true,
    val areaIncludeBottom: Boolean = true,
    val areaIncludeColor: Boolean = true,
    val areaIncludeSpecial: Boolean = true,
    val mergeDuplicateEnabled: Boolean = true,
)

data class DanmakuSessionSettings(
    val enabled: Boolean,
    val opacity: Float,
    val textSizeSp: Float,
    val fontWeight: DanmakuFontWeight,
    val strokeWidthPx: Int,
    val speedLevel: Int,
    val area: Float,
    val laneDensity: DanmakuLaneDensity,
    val followBiliShield: Boolean = true,
    val showHighLikeIcon: Boolean = true,
    val aiShieldEnabled: Boolean = false,
    val aiShieldLevel: Int = 3,
    val allowScroll: Boolean = true,
    val allowTop: Boolean = true,
    val allowBottom: Boolean = true,
    val allowColor: Boolean = true,
    val allowSpecial: Boolean = true,
    val areaIncludeScroll: Boolean = true,
    val areaIncludeTop: Boolean = true,
    val areaIncludeBottom: Boolean = true,
    val areaIncludeColor: Boolean = true,
    val areaIncludeSpecial: Boolean = true,
    val mergeDuplicateEnabled: Boolean = true,
) {
    fun toConfig(): DanmakuConfig =
        DanmakuConfig(
            enabled = enabled,
            opacity = opacity,
            textSizeSp = textSizeSp,
            fontWeight = fontWeight,
            strokeWidthPx = strokeWidthPx,
            speedLevel = speedLevel,
            area = area,
            laneDensity = laneDensity,
            showHighLikeIcon = showHighLikeIcon,
            areaIncludeScroll = areaIncludeScroll,
            areaIncludeTop = areaIncludeTop,
            areaIncludeBottom = areaIncludeBottom,
            areaIncludeColor = areaIncludeColor,
            areaIncludeSpecial = areaIncludeSpecial,
            mergeDuplicateEnabled = mergeDuplicateEnabled,
        )
}

/** 特殊弹幕字号放大系数（接近 B 站「前方高能」等大字号弹幕观感）。 */
internal const val SPECIAL_DANMAKU_SCALE = 1.5f

/** 文本是否属于高能/预警类特殊弹幕。 */
internal fun isHighlightText(t: String): Boolean {
    val s = t.trim()
    if (s.isEmpty()) return false
    return s.contains("高能") ||
        s.contains("ohh", ignoreCase = true) ||
        s.contains("前方") ||
        s.contains("注意") ||
        s.contains("预警") ||
        s.contains("小心") ||
        s.contains("警报")
}

/** 高亮特殊弹幕：高级弹幕（mode 7-9）或文本含高能/预警类关键词。 */
internal fun isHighlightDanmaku(d: Danmaku): Boolean =
    d.mode in 7..9 || isHighlightText(d.text)
