package blbl.cat3399.core.image

import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.ColorFilter
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import blbl.cat3399.core.log.AppLog
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * 动图解码：仅用于用户主动打开的全屏看图器。
 *
 * - API 28(P)+：[ImageDecoder] 直接解码 GIF / 动态 WebP / APNG 为 [AnimatedImageDrawable]。
 * - API 21~27：系统 [Movie] 只支持 GIF，其余动图类型退回首帧静态位图（由调用方兜底）。
 *
 * 列表/缩略图仍由 [ImageLoader] 按 URL 跳过，不在此处理。
 */
internal object AnimatedImageDecoder {
    private const val TAG = "AnimatedImageDecoder"

    private enum class Kind { GIF, ANIMATED_WEBP, APNG, STATIC }

    /**
     * 尝试把字节流解码成可播放的动图 Drawable；失败（静态图 / 老系统不支持的格式）返回 null，
     * 调用方应退回静态位图解码。
     *
     * [targetSidePx] 为期望的长边像素。动图按视图尺寸降采样解码，能显著减少每帧解码/上传开销，
     * 大尺寸 GIF/WebP 在全屏查看器里播放更顺滑；传 0 表示保持原始尺寸。
     */
    fun decodeAnimated(bytes: ByteArray, targetSidePx: Int = 0): Drawable? {
        val kind = detect(bytes)
        if (kind == Kind.STATIC) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                val drawable =
                    ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
                        // 只缩小不放大，避免小图被强行拉伸后变糊、变慢。
                        val maxSide = maxOf(info.size.width, info.size.height)
                        if (targetSidePx >= 1 && maxSide > targetSidePx) {
                            val scale = targetSidePx.toFloat() / maxSide.toFloat()
                            val targetWidth = (info.size.width * scale).roundToInt().coerceAtLeast(1)
                            val targetHeight = (info.size.height * scale).roundToInt().coerceAtLeast(1)
                            decoder.setTargetSize(targetWidth, targetHeight)
                        }
                    }
                if (drawable is AnimatedImageDrawable) {
                    // 全屏看图器里始终循环播放；部分动图文件只声明播放一次或有限次数。
                    drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    drawable.start()
                    AppLog.d(TAG, "animated via ImageDecoder kind=$kind target=$targetSidePx")
                    drawable
                } else {
                    null
                }
            }.onFailure { AppLog.w(TAG, "ImageDecoder failed kind=$kind", it) }.getOrNull()
        }

        // 老系统只兜底 GIF；动态 WebP / APNG 交回调用方取首帧。
        if (kind != Kind.GIF) return null
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (movie.width() <= 0 || movie.height() <= 0) return null
        if (movie.duration() <= 0) return null
        AppLog.d(TAG, "animated via Movie ${movie.width()}x${movie.height()}")
        return MovieDrawable(movie)
    }

    /** 停止正在播放的动图 Drawable，避免换图/关闭后还在后台跑帧。 */
    fun stopIfAnimated(drawable: Drawable?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) {
            runCatching { drawable.stop() }
        }
        if (drawable is MovieDrawable) drawable.stop()
    }

    private fun detect(bytes: ByteArray): Kind {
        if (bytes.size < 12) return Kind.STATIC

        // GIF87a / GIF89a
        if (bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        ) {
            return Kind.GIF
        }

        // PNG：找 acTL 块（APNG 标识）
        if (bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) {
            if (containsAsciiChunk(bytes, "acTL")) return Kind.APNG
            return Kind.STATIC
        }

        // WebP：RIFF....WEBP，VP8X 标志字节的动画位
        if (bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte() &&
            bytes.size >= 21 &&
            bytes[12] == 'X'.code.toByte()
        ) {
            val flags = bytes[20].toInt() and 0xFF
            if (flags and 0x02 != 0) return Kind.ANIMATED_WEBP
        }

        return Kind.STATIC
    }

    private fun containsAsciiChunk(bytes: ByteArray, chunk: String): Boolean {
        val pattern = chunk.toByteArray()
        outer@ for (i in 0..(bytes.size - pattern.size)) {
            for (j in pattern.indices) {
                if (bytes[i + j] != pattern[j]) continue@outer
            }
            return true
        }
        return false
    }

    /**
     * 老系统 GIF 兜底：用 [Movie] 按帧循环刷新。Drawable 自身驱动刷新，
     * 由 [Choreographer] 回调 invalidateSelf 触发 View 重绘。
     */
    internal class MovieDrawable(private val movie: Movie) : Drawable() {
        private val choreographer = Choreographer.getInstance()
        private var running = false
        private var startTime = 0L
        private val durationMs = movie.duration().coerceAtLeast(1)
        // 复用 Paint，避免每秒 60 次分配导致 GC 抖动。
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            isDither = true
        }

        private val frameCallback =
            object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (!running) return
                    movie.setTime(((SystemClock.uptimeMillis() - startTime) % durationMs).toInt())
                    invalidateSelf()
                    choreographer.postFrameCallback(this)
                }
            }

        fun start() {
            if (running) return
            running = true
            startTime = SystemClock.uptimeMillis()
            choreographer.postFrameCallback(frameCallback)
        }

        fun stop() {
            running = false
            choreographer.removeFrameCallback(frameCallback)
        }

        override fun getIntrinsicWidth(): Int = movie.width()

        override fun getIntrinsicHeight(): Int = movie.height()

        override fun draw(canvas: android.graphics.Canvas) {
            movie.draw(canvas, 0f, 0f, paint)
        }

        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            val changed = super.setVisible(visible, restart)
            // 页面不可见时停掉帧回调，避免后台空转抢占主线程。
            if (visible) {
                if (restart) start()
            } else {
                stop()
            }
            return changed
        }

        override fun setAlpha(alpha: Int) {
            // Movie 帧透明度随帧自身调色板，不单独叠加。
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
        }

        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
