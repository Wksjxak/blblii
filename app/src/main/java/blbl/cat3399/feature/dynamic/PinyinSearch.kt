package blbl.cat3399.feature.dynamic

import android.os.Build
import java.text.Normalizer

/**
 * UP 主搜索用的拼音匹配工具。
 *
 * 支持三种命中方式：
 * 1. 原文包含（不区分大小写）；
 * 2. 全拼包含，如「小天」匹配 xiaotian / xiao / tian；
 * 3. 首字母包含，如「小天」匹配 xt / xiaotian。
 *
 * 拼音转换依赖 android.icu（API 24+），低版本仅做原文匹配并优雅降级。
 * 转换结果按名称缓存，避免每次输入重复计算。
 */
object PinyinSearch {
    private data class PinyinData(
        val full: String,
        val initials: String,
    )

    private val cache = HashMap<String, PinyinData?>()
    private val cacheLock = Any()

    /** 名称是否与查询词模糊匹配（原文 / 全拼 / 首字母）。 */
    fun matches(name: String, rawQuery: String): Boolean {
        val query = rawQuery.trim()
        if (query.isEmpty()) return true
        if (name.contains(query, ignoreCase = true)) return true

        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return false

        val data = dataFor(name) ?: return false
        return data.full.contains(normalizedQuery) || data.initials.contains(normalizedQuery)
    }

    /** 预计算并缓存一批名称的拼音（可在后台线程调用）。 */
    fun prewarm(names: List<String>) {
        for (name in names) dataFor(name)
    }

    private fun dataFor(name: String): PinyinData? {
        synchronized(cacheLock) { if (cache.containsKey(name)) return cache[name] }
        val data = compute(name)
        synchronized(cacheLock) { cache[name] = data }
        return data
    }

    private fun compute(name: String): PinyinData? {
        val transliterated = IcuPinyin.transliterate(name) ?: return null
        val syllables =
            stripTones(transliterated)
                .lowercase()
                .split(Regex("\\s+"))
                .map { it.filter { ch -> ch.isLetterOrDigit() } }
                .filter { it.isNotEmpty() }
        if (syllables.isEmpty()) return null
        val full = syllables.joinToString("")
        val initials = buildString(syllables.size) { syllables.forEach { append(it.first()) } }
        return PinyinData(full = full, initials = initials)
    }

    private fun normalize(text: String): String =
        stripTones(text).lowercase().filter { it.isLetterOrDigit() }

    private fun stripTones(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /**
     * ICU 汉字转拼音。单独成对象，避免在 API 21-23 上加载 android.icu 类
     * （该类自 API 24 起才存在），从而安全降级。
     */
    private object IcuPinyin {
        private val transliterator: android.icu.text.Transliterator? by lazy {
            runCatching { android.icu.text.Transliterator.getInstance("Han-Latin") }
                .recoverCatching { android.icu.text.Transliterator.getInstance("Any-Latin") }
                .getOrNull()
        }

        fun transliterate(text: String): String? {
            if (Build.VERSION.SDK_INT < 24) return null
            return runCatching { transliterator?.transliterate(text) }.getOrNull()
        }
    }
}
