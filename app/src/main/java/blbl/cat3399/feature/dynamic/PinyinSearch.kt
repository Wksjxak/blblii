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
    private val NON_WORD_RE = Regex("[^A-Za-z0-9]+")

    private data class PinyinData(
        val full: String,
        // 全部片段的声母（英文词也算一段，取首字母）：MeetFood觅食 -> mms
        val initials: String,
        // 只取汉字声母：MeetFood觅食 -> ms
        val hanInitials: String,
    ) {
        /** 全拼、全部声母、纯汉字声母，任一命中即算匹配。 */
        fun matches(normalizedQuery: String): Boolean =
            full.contains(normalizedQuery) ||
                initials.contains(normalizedQuery) ||
                (hanInitials.isNotEmpty() && hanInitials.contains(normalizedQuery))
    }

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
        return data.matches(normalizedQuery)
    }

    /**
     * 预先归一化一次查询词，返回可复用的判定函数。
     * 列表里几百个名字逐个匹配时不用反复做 NFC/去声调归一化。
     */
    fun matcher(rawQuery: String): (String) -> Boolean {
        val query = rawQuery.trim()
        val normalizedQuery = normalize(query)
        return { name ->
            name.contains(query, ignoreCase = true) ||
                (
                    normalizedQuery.isNotEmpty() &&
                        dataFor(name)?.matches(normalizedQuery) == true
                    )
        }
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
        val allSyllables = ArrayList<String>()
        val hanSyllables = ArrayList<String>()

        for (segment in segments(name)) {
            if (segment.han) {
                // 汉字按连续片段整体转写，多音字的上下文不丢
                val transliterated = IcuPinyin.transliterate(segment.text) ?: continue
                for (syllable in splitSyllables(transliterated)) {
                    allSyllables += syllable
                    hanSyllables += syllable
                }
            } else {
                // 非汉字片段：按空白/符号切成词，每个词算一个音节
                for (word in segment.text.split(NON_WORD_RE)) {
                    val cleaned = stripTones(word).lowercase().filter { it.isLetterOrDigit() }
                    if (cleaned.isNotEmpty()) allSyllables += cleaned
                }
            }
        }

        if (allSyllables.isEmpty()) return null
        return PinyinData(
            full = allSyllables.joinToString(""),
            initials = initialsOf(allSyllables),
            hanInitials = initialsOf(hanSyllables),
        )
    }

    private fun initialsOf(syllables: List<String>): String =
        buildString(syllables.size) { syllables.forEach { append(it.first()) } }

    private fun splitSyllables(transliterated: String): List<String> =
        stripTones(transliterated)
            .lowercase()
            .split(Regex("\\s+"))
            .map { it.filter { ch -> ch.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }

    private data class Segment(val text: String, val han: Boolean)

    /** 把名称切成「汉字段」和「非汉字段」。 */
    private fun segments(name: String): List<Segment> {
        val result = ArrayList<Segment>(4)
        var start = 0
        var currentHan = false
        var started = false
        for (i in name.indices) {
            val han = isHanChar(name[i])
            if (!started) {
                started = true
                currentHan = han
                start = i
                continue
            }
            if (han != currentHan) {
                result += Segment(name.substring(start, i), currentHan)
                currentHan = han
                start = i
            }
        }
        if (started) result += Segment(name.substring(start), currentHan)
        return result
    }

    private fun isHanChar(ch: Char): Boolean {
        if (Build.VERSION.SDK_INT < 24) return ch.code in 0x4E00..0x9FFF
        return runCatching {
            Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN
        }.getOrElse { ch.code in 0x4E00..0x9FFF }
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
