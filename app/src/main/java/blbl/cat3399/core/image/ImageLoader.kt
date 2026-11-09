package blbl.cat3399.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import androidx.collection.LruCache
import blbl.cat3399.R
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.WeakHashMap

object ImageLoader {
    private const val TAG = "ImageLoader"
    private const val SMALL_BUCKET_MAX = 480
    private const val LARGE_BUCKET_MAX = 1280
    private val placeholder = ColorDrawable(0xFF2A2A2A.toInt())
    private val inFlight = WeakHashMap<ImageView, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val cache = object : LruCache<String, Bitmap>(maxCacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun loadInto(view: ImageView, url: String?, allowAnimated: Boolean = false) {
        val normalized = normalizeImageUrl(url)

        // 动图不主动加载：只留占位，不发起请求，避免自动下载一屏 GIF
        if (!allowAnimated && normalized != null && isAnimatedUrl(normalized)) {
            view.setTag(R.id.tag_image_loader_url, null)
            inFlight.remove(view)?.cancel()
            if (view.drawable !== placeholder) view.setImageDrawable(placeholder)
            AppLog.d(TAG, "skip animated url=$normalized")
            return
        }

        if (normalized == null) {
            view.setTag(R.id.tag_image_loader_url, null)
            inFlight.remove(view)?.cancel()
            if (view.drawable !== placeholder) view.setImageDrawable(placeholder)
            return
        }

        val lastUrl = view.getTag(R.id.tag_image_loader_url) as? String
        if (lastUrl == normalized) {
            // If we already have a non-placeholder image for the same URL, keep it to prevent
            // flicker on rebind (e.g. switching tabs triggers notifyItemRangeChanged).
            val drawable = view.drawable
            if (drawable != null && drawable !== placeholder) {
                inFlight.remove(view)?.cancel()
                return
            }
            // If the same URL is already loading, keep the current placeholder.
            val inFlightJob = inFlight[view]
            if (inFlightJob != null && inFlightJob.isActive) return
        } else {
            view.setTag(R.id.tag_image_loader_url, normalized)
            inFlight.remove(view)?.cancel()
        }

        val bucket = decodeBucket(view)
        val cacheKey = "$normalized@$bucket"
        val cached = cache.get(cacheKey)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }

        if (view.drawable !== placeholder) view.setImageDrawable(placeholder)
        val job = scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { BiliClient.getBytes(normalized) }
                // 按控件实际尺寸降采样：电视上整张原图解码既慢又吃内存，滚动时最容易被 GC 卡一下
                val bmp = withContext(Dispatchers.Default) { decodeSampled(bytes, bucket) }
                if (bmp != null) {
                    cache.put(cacheKey, bmp)
                    if ((view.getTag(R.id.tag_image_loader_url) as? String) == normalized) {
                        view.setImageBitmap(bmp)
                    }
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "load failed url=$normalized", t)
            }
        }
        inFlight[view] = job
    }

    /** 控件目标边长分档：小卡片按 480，大图/详情页按 1280，避免按原图尺寸解码。 */
    private fun decodeBucket(view: ImageView): Int {
        val width = if (view.width > 0) view.width else view.layoutParams?.width?.takeIf { it > 0 } ?: 0
        val height = if (view.height > 0) view.height else view.layoutParams?.height?.takeIf { it > 0 } ?: 0
        val maxSide = maxOf(width, height)
        return if (maxSide in 1..SMALL_BUCKET_MAX) SMALL_BUCKET_MAX else LARGE_BUCKET_MAX
    }

    private fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        val longerSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longerSide / (sampleSize * 2) >= maxSide) sampleSize *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** 动图判定：B 站动图 CDN 路径以 .gif 结尾，偶尔带 @ 缩放后缀 */
    fun isAnimatedUrl(url: String): Boolean {
        val path = url.toHttpUrlOrNull()?.encodedPath?.lowercase() ?: return false
        val noQuery = path.substringBefore('@')
        return noQuery.endsWith(".gif")
    }

    private fun normalizeImageUrl(url: String?): String? {
        val raw = url?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        if (raw.startsWith("//")) return "https:$raw"
        if (!raw.startsWith("http://")) return raw

        val host = raw.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        val isBiliCdn =
            host == "hdslb.com" ||
                host.endsWith(".hdslb.com") ||
                host == "bilibili.com" ||
                host.endsWith(".bilibili.com") ||
                host == "bilivideo.com" ||
                host.endsWith(".bilivideo.com") ||
                host == "bilivideo.cn" ||
                host.endsWith(".bilivideo.cn")
        return if (isBiliCdn) raw.replaceFirst("http://", "https://") else raw
    }

    private fun maxCacheBytes(): Int {
        val maxMemory = Runtime.getRuntime().maxMemory().toInt()
        // 降采样后单张位图小了很多，缓存可以放大到 1/8，减少滚回去时的重复解码
        return maxMemory / 8
    }
}
