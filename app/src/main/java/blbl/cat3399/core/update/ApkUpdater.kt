package blbl.cat3399.core.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.await
import blbl.cat3399.core.net.ipv4OnlyDns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object ApkUpdater {
    // 定制版：更新源指向本仓库的 GitHub Releases（公开仓库，匿名可查）。
    private const val RELEASES_API_URL = "https://api.github.com/repos/Wksjxak/blblii/releases/latest"
    private const val RELEASES_LIST_URL = "https://api.github.com/repos/Wksjxak/blblii/releases?per_page=20"
    /** 测试通道标记：只有 tag 形如 test-0.1.38.1 的预发布包，测试版才会认。 */
    private const val TEST_TAG_PREFIX = "test-"
    private const val RELEASE_DOWNLOAD_BASE = "https://github.com/Wksjxak/blblii/releases/download"
    private const val DEBUG_APK_URL = "https://github.com/Wksjxak/blblii/releases/latest/download/blbl-android-latest-debug.apk"
    private const val RELEASE_APK_URL = "https://github.com/Wksjxak/blblii/releases/latest/download/blbl-android-latest-release.apk"
    private const val CHANGELOG_URL = "https://raw.githubusercontent.com/Wksjxak/blblii/master/CHANGELOG.md"
    /** 直连失败时套用的国内 GitHub 前缀代理（用于 API 与 APK 下载）。 */
    private const val GH_PROXY_PREFIX = "https://gh-proxy.com/"
    /** 检查更新的回退候选：直连、前缀代理、jsDelivr 上的 CHANGELOG。 */
    private const val CHANGELOG_MIRROR_URL = "https://cdn.jsdelivr.net/gh/Wksjxak/blblii@master/CHANGELOG.md"
    private const val CHANGELOG_PROXY_URL = GH_PROXY_PREFIX + "https://raw.githubusercontent.com/Wksjxak/blblii/master/CHANGELOG.md"
    val TEST_APK_URL: String
        get() = if (BuildConfig.DEBUG) DEBUG_APK_URL else RELEASE_APK_URL
    val TEST_CHANGELOG_URL: String
        get() = CHANGELOG_URL

    private const val COOLDOWN_MS = 5_000L

    @Volatile
    private var lastStartedAtMs: Long = 0L

    private val okHttpLazy: Lazy<OkHttpClient> =
        lazy {
            OkHttpClient.Builder()
                .dns(ipv4OnlyDns { BiliClient.prefs.ipv4OnlyEnabled })
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

    private val okHttp: OkHttpClient
        get() = okHttpLazy.value

    fun evictConnections() {
        if (okHttpLazy.isInitialized()) okHttp.connectionPool.evictAll()
    }

    sealed class Progress {
        data object Connecting : Progress()

        data class Downloading(
            val downloadedBytes: Long,
            val totalBytes: Long?,
            val bytesPerSecond: Long,
        ) : Progress() {
            val percent: Int? =
                totalBytes?.takeIf { it > 0 }?.let { total ->
                    ((downloadedBytes.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                }

            val hint: String =
                buildString {
                    if (totalBytes != null && totalBytes > 0) {
                        append("${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}")
                    } else {
                        append(formatBytes(downloadedBytes))
                    }
                    if (bytesPerSecond > 0) append("（${formatBytes(bytesPerSecond)}/s）")
                }
        }
    }

    data class RemoteUpdate(
        val versionName: String,
        val changelog: String,
        /** 该版本 APK 的下载直链（GitHub Releases 资产）；为空时按版本号推导。 */
        val apkUrl: String? = null,
        val versions: List<RemoteUpdate> = emptyList(),
    ) {
        val displayChangelog: String
            get() = changelog.ifBlank { "暂无更新日志" }
    }

    fun markStarted(nowMs: Long = System.currentTimeMillis()) {
        lastStartedAtMs = nowMs
    }

    fun cooldownLeftMs(nowMs: Long = System.currentTimeMillis()): Long {
        val last = lastStartedAtMs
        val left = (last + COOLDOWN_MS) - nowMs
        return left.coerceAtLeast(0)
    }

    suspend fun fetchLatestUpdate(): RemoteUpdate {
        return withContext(Dispatchers.IO) {
            // 测试版（debug 包）只走测试通道，永远不下正式包，避免“测试版被升级成正式版”。
            if (BuildConfig.DEBUG) return@withContext fetchTestChannelUpdate()

            // 1) GitHub API 直连
            runCatching { retryFetch { fetchLatestReleaseOnce(RELEASES_API_URL) } }
                .getOrNull()
                ?.let { return@withContext it }

            // 2) GitHub API 走国内前缀代理
            runCatching { retryFetch { fetchLatestReleaseOnce(GH_PROXY_PREFIX + RELEASES_API_URL) } }
                .getOrNull()
                ?.let { return@withContext it }

            // 3) 只能拿到 CHANGELOG 时：版本号照旧可用，下载地址按版本号推导
            //    （国内直连 api.github.com 常被拦，这一步保证「检查得到新版本」）
            var changelogError: Throwable? = null
            for (url in listOf(CHANGELOG_PROXY_URL, CHANGELOG_MIRROR_URL, TEST_CHANGELOG_URL)) {
                val text = runCatching { fetchText(url) }.getOrElse { changelogError = it; null }
                    ?: continue
                runCatching { parseChangelog(text) .copy(apkUrl = null) }.getOrNull()?.let { update ->
                    return@withContext update.copy(apkUrl = apkUrlFor(update.versionName))
                }
            }
            throw changelogError ?: error("检查更新失败，请检查网络")
        }
    }

    private suspend fun <T> retryFetch(block: () -> T): T {
        var lastError: Throwable? = null
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                lastError = t
                val shouldRetry =
                    attempt < maxAttempts &&
                        (t is IOException || t.message?.startsWith("HTTP ") == true)
                if (!shouldRetry) throw t
                delay(400L * attempt)
            }
        }
        throw lastError ?: error("检查更新失败")
    }

    private fun fetchText(url: String): String {
        val req =
            Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "blbl-android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
        val res = okHttp.newCall(req).execute()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            return (r.body ?: error("empty body")).string()
        }
    }

    /**
     * 测试通道：读 releases 列表，取 tag 以 test- 开头、带 apk 资产的第一个预发布包。
     * 找不到就返回“当前版本”，界面上显示已是最新，绝不回落到正式渠道。
     */
    private suspend fun fetchTestChannelUpdate(): RemoteUpdate {
        var lastError: Throwable? = null
        for (base in listOf("", GH_PROXY_PREFIX)) {
            val text: String? =
                runCatching { retryFetch { fetchText(base + RELEASES_LIST_URL) } }
                    .getOrElse { lastError = it; null }
            if (text == null) continue
            val arr = runCatching { org.json.JSONArray(text) }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val update = runCatching { parseReleaseObject(obj, TEST_TAG_PREFIX) }.getOrNull() ?: continue
                return update
            }
            return RemoteUpdate(
                versionName = BuildConfig.VERSION_NAME,
                changelog = "测试通道暂时没有新的测试包。",
            )
        }
        throw lastError ?: error("检查更新失败，请检查网络")
    }

    /** 从 GitHub Releases API 取最新正式版：版本号、更新说明、APK 直链。 */
    private fun fetchLatestReleaseOnce(url: String): RemoteUpdate {
        val req =
            Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "blbl-android/${BuildConfig.VERSION_NAME}")
                .header("Cache-Control", "no-cache")
                .get()
                .build()
        val call = okHttp.newCall(req)
        val res = call.execute()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            return parseReleaseObject(org.json.JSONObject(body.string()), tagPrefix = null)
        }
    }

    /**
     * 解析单个 release JSON。tagPrefix 非空表示只认带该前缀的测试通道标签，
     * 版本号会先去掉前缀再去掉 v 前缀。
     */
    private fun parseReleaseObject(json: org.json.JSONObject, tagPrefix: String?): RemoteUpdate {
        val tag = json.optString("tag_name").trim()
        check(tag.isNotBlank()) { "发布信息缺少版本号" }
        if (tagPrefix != null) {
            if (!tag.startsWith(tagPrefix, ignoreCase = true)) error("不是测试通道的包")
        }
        val versionName = tag.removePrefix(tagPrefix ?: "").removePrefix("v").removePrefix("V").trim()
        check(versionName.isNotBlank()) { "发布信息缺少版本号" }
        val changelog = json.optString("body").trim()
        val assets = json.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                apkUrl = asset.optString("browser_download_url").trim().takeIf { it.isNotBlank() }
                if (apkUrl != null) break
            }
        }
        return RemoteUpdate(
            versionName = versionName,
            changelog = changelog,
            apkUrl = apkUrl ?: apkUrlFor(versionName),
        )
    }

    suspend fun fetchLatestUpdateFromChangelog(
        url: String = TEST_CHANGELOG_URL,
    ): RemoteUpdate {
        return withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            val maxAttempts = 3
            for (attempt in 1..maxAttempts) {
                ensureActive()
                try {
                    return@withContext fetchLatestUpdateOnce(url)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastError = t
                    val shouldRetry =
                        attempt < maxAttempts &&
                            (t is IOException || t.message?.startsWith("HTTP ") == true)
                    if (!shouldRetry) throw t
                    delay(400L * attempt)
                }
            }
            throw lastError ?: IllegalStateException("fetch latest version failed")
        }
    }

    private fun fetchLatestUpdateOnce(url: String): RemoteUpdate {
        val req =
            Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .get()
                .build()
        val call = okHttp.newCall(req)
        val res = call.execute()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            return parseChangelog(body.string())
        }
    }

    internal fun parseChangelog(raw: String): RemoteUpdate {
        val versions = parseChangelogVersions(raw)
        return versions.first().copy(versions = versions)
    }

    internal fun parseChangelogVersions(raw: String): List<RemoteUpdate> {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        check(normalized.isNotBlank()) { "更新日志为空" }

        val lines = normalized.lines()
        val allHeadings =
            lines.withIndex()
                .mapNotNull { (index, line) ->
                    parseVersionHeading(line)?.let { heading -> index to heading }
                }
        check(allHeadings.isNotEmpty()) { "未找到版本标题" }

        val versionLevel = allHeadings.first().second.level
        val headings = allHeadings.filter { (_, heading) -> heading.level == versionLevel }
        return headings.mapIndexed { index, (headingIndex, heading) ->
            val nextHeadingIndex = headings.getOrNull(index + 1)?.first ?: lines.size
            val sectionLines =
                lines.subList(headingIndex + 1, nextHeadingIndex)
                    .dropLastWhile { it.isBlank() }
            val changelog =
                sectionLines
                    .joinToString("\n")
                    .trim()

            RemoteUpdate(
                versionName = heading.versionName,
                changelog = changelog,
            )
        }
    }

    fun apkUrlFor(versionName: String): String {
        val cleanVersion = versionName.trim().removePrefix("v")
        val channel = if (BuildConfig.DEBUG) "debug" else "release"
        return "$RELEASE_DOWNLOAD_BASE/v$cleanVersion/blbl-android-$cleanVersion-$channel.apk"
    }

    private data class VersionHeading(
        val level: Int,
        val versionName: String,
    )

    private fun parseVersionHeading(line: String): VersionHeading? {
        val trimmed = line.trim()
        val match = Regex("""^(#{1,6})\s+\[?v?([0-9]+(?:\.[0-9]+)*(?:[-+][A-Za-z0-9_.-]+)?)\]?(?:[\s（(].*)?$""").matchEntire(trimmed)
            ?: return null
        val level = match.groupValues[1].length
        val versionName = match.groupValues[2].trim()
        if (parseVersion(versionName) == null) return null
        return VersionHeading(level = level, versionName = versionName)
    }

    fun isRemoteNewer(remoteVersionName: String, currentVersionName: String = BuildConfig.VERSION_NAME): Boolean {
        val remote = parseVersion(remoteVersionName) ?: return false
        val current = parseVersion(currentVersionName) ?: return remoteVersionName.trim() != currentVersionName.trim()
        return compareVersion(remote, current) > 0
    }

    suspend fun downloadApkToCache(
        context: Context,
        url: String = TEST_APK_URL,
        onProgress: (Progress) -> Unit,
    ): File {
        onProgress(Progress.Connecting)
        try {
            return downloadApkOnce(context, url, onProgress)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // GitHub 直连被墙时改用国内前缀代理重下一次
            if (url.startsWith(GH_PROXY_PREFIX)) throw t
            return downloadApkOnce(context, GH_PROXY_PREFIX + url, onProgress)
        }
    }

    private suspend fun downloadApkOnce(
        context: Context,
        url: String,
        onProgress: (Progress) -> Unit,
    ): File {
        val dir = File(context.cacheDir, "test_update").apply { mkdirs() }
        val part = File(dir, "update.apk.part")
        val target = File(dir, "update.apk")
        runCatching { part.delete() }
        runCatching { target.delete() }

        val req = Request.Builder().url(url).get().build()
        val call = okHttp.newCall(req)
        val res = call.await()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val total = body.contentLength().takeIf { it > 0 }
            withContext(Dispatchers.IO) {
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buf = ByteArray(32 * 1024)
                        var downloaded = 0L

                        var lastEmitAtMs = 0L
                        var speedAtMs = System.currentTimeMillis()
                        var speedBytes = 0L
                        var bytesPerSecond = 0L

                        while (true) {
                            ensureActive()
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read

                            // Speed estimate (1s window)
                            speedBytes += read
                            val nowMs = System.currentTimeMillis()
                            val speedElapsedMs = nowMs - speedAtMs
                            if (speedElapsedMs >= 1_000) {
                                bytesPerSecond = (speedBytes * 1_000L / speedElapsedMs.coerceAtLeast(1)).coerceAtLeast(0)
                                speedBytes = 0L
                                speedAtMs = nowMs
                            }

                            // UI progress: at most 5 updates per second.
                            if (nowMs - lastEmitAtMs >= 200) {
                                lastEmitAtMs = nowMs
                                onProgress(Progress.Downloading(downloadedBytes = downloaded, totalBytes = total, bytesPerSecond = bytesPerSecond))
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
        }

        check(part.exists() && part.length() > 0) { "downloaded file is empty" }
        check(part.renameTo(target)) { "rename failed" }
        return target
    }

    fun installApk(context: Context, apkFile: File) {
        val uri = installUriFor(context, apkFile)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        grantInstallerReadPermissions(context, intent, uri)
        context.startActivity(intent)
    }

    @SuppressLint("SetWorldReadable")
    private fun installUriFor(context: Context, apkFile: File): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val authority = "${context.packageName}.fileprovider"
            FileProvider.getUriForFile(context, authority, apkFile)
        } else {
            apkFile.setReadable(true, false)
            Uri.fromFile(apkFile)
        }
    }

    @Suppress("DEPRECATION")
    private fun grantInstallerReadPermissions(
        context: Context,
        intent: Intent,
        uri: Uri,
    ) {
        if (uri.scheme != "content") return

        val installers =
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        for (installer in installers) {
            val packageName = installer.activityInfo?.packageName ?: continue
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        if (b < 1024) return "${b}B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2fGB", gb)
    }

    private fun parseVersion(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("v")
        val digitsOnly =
            cleaned.takeWhile { ch ->
                ch.isDigit() || ch == '.'
            }
        if (digitsOnly.isBlank()) return null
        val parts = digitsOnly.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return nums
    }

    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }
}
