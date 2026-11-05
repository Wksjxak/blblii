package blbl.cat3399.feature.player

import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.feature.player.danmaku.DanmakuFontWeight
import blbl.cat3399.feature.player.danmaku.DanmakuLaneDensity
import kotlin.math.abs
import kotlin.math.roundToInt

internal object PlaybackSettingChoices {
    val resolutionQns: List<Int> = listOf(16, 32, 64, 74, 80, 100, 112, 116, 120, 125, 126, 127, 129)
    val audioTrackIds: List<Int> = listOf(30251, 30250, 30280, 30232, 30216)
    val playbackSpeeds: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f, 2.75f)
    val extendedPlaybackSpeeds: List<Float> = playbackSpeeds + listOf(3.0f, 4.0f)
    /** 倍速 0.01 梯度细调（细致）：0.25x ~ 4.00x，步长 0.01。 */
    val finePlaybackSpeeds: List<Float> =
        ((AppPrefs.PLAYBACK_SPEED_MIN * 100).toInt()..(AppPrefs.PLAYBACK_SPEED_MAX * 100).toInt()).map { it / 100f }
    /** 倍速简单档位（简单）：0.25x ~ 4.00x，步长 0.25。 */
    val simplePlaybackSpeeds: List<Float> =
        ((AppPrefs.PLAYBACK_SPEED_MIN * 100).toInt()..(AppPrefs.PLAYBACK_SPEED_MAX * 100).toInt() step 25).map { it / 100f }

    /** 按「播放速度模式」返回对应档位列表。 */
    fun playbackSpeedsFor(mode: String): List<Float> =
        if (mode == AppPrefs.PLAYBACK_SPEED_MODE_SIMPLE) simplePlaybackSpeeds else finePlaybackSpeeds
    /** 进度手势灵敏度：0.5x ~ 2.0x，步长 0.1。 */
    val seekSensitivities: List<Float> = (5..20).map { it / 10f }
    val subtitleTextSizes: List<Int> = (10..60 step 2).toList()
    val subtitleBottomPaddingPercents: List<Int> = (0..30 step 2).toList()

    val subtitleBackgroundOpacities: List<Float> by lazy {
        val options = (20 downTo 0).map { it / 20f }.toMutableList()
        val defaultOpacity = 34f / 255f
        if (options.none { abs(it - defaultOpacity) < 0.005f }) options.add(defaultOpacity)
        options.distinct().sortedDescending()
    }

    val danmakuOpacities: List<Float> = (20 downTo 1).map { it / 20f }
    val danmakuTextSizes: List<Int> = subtitleTextSizes
    val danmakuAreas: List<Pair<Float, String>> = AppPrefs.DANMAKU_AREA_OPTIONS.map { it to "${(it * 100f).roundToInt()}%" }
    val danmakuStrokeWidths: List<Int> = listOf(0, 2, 4, 6)
    val danmakuFontWeights: List<DanmakuFontWeight> = listOf(DanmakuFontWeight.Normal, DanmakuFontWeight.Bold)
    val danmakuLaneDensities: List<DanmakuLaneDensity> =
        listOf(DanmakuLaneDensity.Sparse, DanmakuLaneDensity.Standard, DanmakuLaneDensity.Dense)
    val danmakuSpeeds: List<Int> = (1..10).toList()
    val aiShieldLevels: List<Int> = (1..10).toList()
    val danmakuMergeStrengths: List<Int> =
        (AppPrefs.DANMAKU_MERGE_STRENGTH_MIN..AppPrefs.DANMAKU_MERGE_STRENGTH_MAX).toList()

    /**
     * 弹幕字号可选档位：上限随「界面放大」(uiScale) 动态收紧，防止文字溢出。
     * 设计上限 60sp，界面放大越大则最大可选字号越小。
     */
    fun danmakuTextSizesFor(uiScale: Float): List<Int> {
        val base = AppPrefs.DANMAKU_TEXT_SIZE_SP_MAX
        val cap = (base / uiScale.coerceIn(AppPrefs.UI_SCALE_FACTOR_MIN, AppPrefs.UI_SCALE_FACTOR_MAX))
            .coerceIn(AppPrefs.DANMAKU_TEXT_SIZE_SP_MIN, base).toInt()
        return (AppPrefs.DANMAKU_TEXT_SIZE_SP_MIN.toInt()..cap step 2).toList().ifEmpty { listOf(AppPrefs.DANMAKU_TEXT_SIZE_SP_MIN.toInt()) }
    }

    /** 评论字号可选档位：上限同样随「界面放大」动态收紧（设计上限 40sp）。 */
    fun commentTextSizesFor(uiScale: Float): List<Int> {
        val base = AppPrefs.COMMENT_TEXT_SIZE_SP_MAX
        val cap = (base / uiScale.coerceIn(AppPrefs.UI_SCALE_FACTOR_MIN, AppPrefs.UI_SCALE_FACTOR_MAX))
            .coerceIn(AppPrefs.COMMENT_TEXT_SIZE_SP_MIN, base).toInt()
        return (AppPrefs.COMMENT_TEXT_SIZE_SP_MIN.toInt()..cap step 2).toList()
            .ifEmpty { listOf(AppPrefs.COMMENT_TEXT_SIZE_SP_MIN.toInt()) }
    }

    /** 界面字体可选档位：上限随「界面放大」动态收紧（合并值不超过 UI_EFFECTIVE_SCALE_MAX）。 */
    fun uiFontScalesFor(uiScaleFactor: Float): List<Float> {
        val minStep = Math.round(AppPrefs.UI_FONT_SCALE_MIN * 100f)
        val step = Math.round(AppPrefs.UI_FONT_SCALE_STEP * 100f).coerceAtLeast(1)
        val maxFontPct =
            (AppPrefs.UI_EFFECTIVE_SCALE_MAX /
                uiScaleFactor.coerceIn(AppPrefs.UI_SCALE_FACTOR_MIN, AppPrefs.UI_SCALE_FACTOR_MAX) * 100f)
                .roundToInt()
                .coerceIn(minStep, Math.round(AppPrefs.UI_FONT_SCALE_MAX * 100f))
        return (minStep..maxFontPct step step).map { it / 100f }
    }
}
