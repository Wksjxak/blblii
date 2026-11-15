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
     */
    fun decodeAnimated(bytes: ByteArray): Drawable? {
        val kind = detect(bytes)
        if (kind == Kind.STATIC) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                val drawable = ImageDecoder.decodeDrawable(src)
                if (drawable is AnimatedImageDrawable) {
                    drawable.start()
                    AppLog.d(TAG, "animated via ImageDecoder kind=$kind")
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
            movie.draw(canvas, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        }

        override fun setAlpha(alpha: Int) {
            // Movie 帧透明度随帧自身调色板，不单独叠加。
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
        }

        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
