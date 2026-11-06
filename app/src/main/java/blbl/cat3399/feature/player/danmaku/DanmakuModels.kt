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
    val mergeDuplicateEnabled: Boolean = true,
    val mergeStrength: Int = AppPrefs.DANMAKU_MERGE_STRENGTH_DEFAULT,
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
    val areaIncludeScroll: Boolean = true,
    val areaIncludeTop: Boolean = true,
    val areaIncludeBottom: Boolean = true,
    val areaIncludeColor: Boolean = true,
    val mergeDuplicateEnabled: Boolean = true,
    val mergeStrength: Int = AppPrefs.DANMAKU_MERGE_STRENGTH_DEFAULT,
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
            mergeDuplicateEnabled = mergeDuplicateEnabled,
            mergeStrength = mergeStrength,
        )
}

/**
 * 定制版：高级/特殊弹幕（mode 7~9）在数据源与引擎两处均被过滤，不再渲染；
 * 普通弹幕即使文本包含「前方高能」等关键词，也不再放大字号或加底色，统一按普通弹幕显示。
 */
