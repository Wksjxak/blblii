package blbl.cat3399.core.api

import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.DynamicParagraph
import org.json.JSONArray
import org.json.JSONObject

/**
 * 动态正文（opus / 老动态 / 转发 orig）的 JSON → 段落树解析。
 * 纯 JSON 变换、不发请求；从 BiliApi 抽出，便于复查和以后加单测。
 */
internal object DynamicBodyParser {

    /**
     * 解析 paragraphs：按原始顺序产出文字段/图片段/分割线。
     * 优先看 para_type（1文字 2图片 3分割线 4引用 5列表 7代码），缺失时按字段存在性判断。
     * 图片段在 opus 里是 pic.pics[]，部分接口是 pics.pics[]。
     */
    internal fun parseOpusParagraphs(arr: org.json.JSONArray?): List<DynamicParagraph> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<DynamicParagraph>()

        fun readNodes(nodes: org.json.JSONArray?): String {
            if (nodes == null || nodes.length() == 0) return ""
            val sb = StringBuilder()
            for (n in 0 until nodes.length()) {
                val node = nodes.optJSONObject(n) ?: continue
                when (node.optString("type", "")) {
                    "TEXT_NODE_TYPE_WORD" -> sb.append(node.optJSONObject("word")?.optString("words", "") ?: "")
                    "TEXT_NODE_TYPE_RICH" -> sb.append(node.optJSONObject("rich")?.optString("text", "") ?: "")
                }
            }
            return sb.toString()
        }

        fun readPics(container: JSONObject?): List<String> {
            if (container == null) return emptyList()
            val urls = ArrayList<String>()

            fun fromArray(arr: JSONArray?) {
                arr ?: return
                for (i in 0 until arr.length()) {
                    when (val el = arr.opt(i)) {
                        is String -> if (el.startsWith("http")) urls.add(el)
                        is JSONObject -> {
                            val u = el.optString("url", "")
                                .ifBlank { el.optString("src", "") }
                                .ifBlank { el.optString("item", "") }
                                .ifBlank { el.optString("img_src", "") }
                            if (u.isNotBlank()) urls.add(u)
                        }
                    }
                }
            }

            fromArray(container.optJSONArray("pics"))
            fromArray(container.optJSONArray("images"))
            fromArray(container.optJSONArray("urls"))

            if (urls.isEmpty()) {
                val u = container.optString("url", "")
                    .ifBlank { container.optString("src", "") }
                    .ifBlank { container.optString("img_src", "") }
                if (u.isNotBlank()) urls.add(u)
            }
            if (urls.isEmpty()) {
                container.optString("image_url", "").takeIf { it.startsWith("http") }?.let { urls.add(it) }
            }
            if (urls.isEmpty()) {
                container.optString("cover", "").takeIf { it.startsWith("http") }?.let { urls.add(it) }
            }
            return urls
        }

        for (p in 0 until arr.length()) {
            val para = arr.optJSONObject(p) ?: continue
            when (para.optInt("para_type", 0)) {
                1, 4 -> {
                    val t = readNodes(para.optJSONObject("text")?.optJSONArray("nodes"))
                    if (t.isNotBlank()) out.add(DynamicParagraph.Text(t))
                }
                2 -> {
                    var imgs = readPics(para.optJSONObject("pic"))
                    if (imgs.isEmpty()) imgs = readPics(para.optJSONObject("pics"))
                    imgs.forEach { out.add(DynamicParagraph.Image(it)) }
                }
                3 -> out.add(DynamicParagraph.Separator)
                5 -> {
                    val listItems = para.optJSONObject("list")?.optJSONArray("items")
                    if (listItems != null) {
                        for (li in 0 until listItems.length()) {
                            val item = listItems.optJSONObject(li) ?: continue
                            val t = readNodes(item.optJSONObject("text")?.optJSONArray("nodes"))
                            if (t.isNotBlank()) out.add(DynamicParagraph.Text("· $t"))
                        }
                    }
                }
                7 -> {
                    val code = para.optJSONObject("code")?.optString("content", "").orEmpty()
                    if (code.isNotBlank()) out.add(DynamicParagraph.Text(code))
                }
                else -> {
                    // 兼容没有 para_type 的响应：文字读完还要继续尝试读图
                    val t = readNodes(para.optJSONObject("text")?.optJSONArray("nodes"))
                    if (t.isNotBlank()) out.add(DynamicParagraph.Text(t))
                    var imgs = readPics(para.optJSONObject("pic"))
                    if (imgs.isEmpty()) imgs = readPics(para.optJSONObject("pics"))
                    imgs.forEach { out.add(DynamicParagraph.Image(it)) }
                    if (t.isBlank() && imgs.isEmpty() && para.optJSONObject("line") != null) {
                        out.add(DynamicParagraph.Separator)
                    }
                }
            }
        }
        return out
    }

    /** 已知稳定路径的正文提取（opus/detail 的 modules 数组、feed/detail 的 modules 对象） */
    internal fun extractKnownBody(item: JSONObject): List<DynamicParagraph> {
        val out = ArrayList<DynamicParagraph>()
        val modulesNode = item.opt("modules")

        if (modulesNode is JSONArray) {
            // opus/detail 返回的 modules 是数组：文字段、图片段、分割线可能分散在不同模块里，
            // 必须全部遍历合并，不能在第一个非空模块就 return。
            val debugModules = ArrayList<String>()
            for (i in 0 until modulesNode.length()) {
                val m = modulesNode.optJSONObject(i) ?: continue
                val moduleType = m.optString("module_type", "?")
                val content = m.optJSONObject("module_content")
                debugModules.add(
                    "$moduleType{contentKeys=${content?.keys()?.asSequence()?.joinToString(",").orEmpty()}}",
                )
                if (content == null) continue
                content.optJSONArray("paragraphs")?.let { pars ->
                    out.addAll(parseOpusParagraphs(pars))
                }
                // 有些动态图片直接挂在 module_content.pics 上，不在 paragraphs 里
                readPicsDirect(content).let { out.addAll(it) }
            }
            if (out.isNotEmpty()) {
                // 诊断：如果正文只有 1 段文字、没有图片，说明图片在别的字段里
                val hasImage = out.any { it is DynamicParagraph.Image }
                if (!hasImage && out.count { it is DynamicParagraph.Text } <= 1) {
                    AppLog.d(
                        "DynamicBodyParser",
                        "arrayBodyNoImage modules=${debugModules.joinToString("|")}",
                    )
                }
                return out
            }
        }

        if (modulesNode is JSONObject) {
            modulesNode.optJSONObject("module_content")?.optJSONArray("paragraphs")?.let { pars ->
                out.addAll(parseOpusParagraphs(pars))
                if (out.isNotEmpty()) return out
            }
            val md = modulesNode.optJSONObject("module_dynamic")
            if (md != null) {
                md.optJSONObject("module_content")?.optJSONArray("paragraphs")?.let { pars ->
                    out.addAll(parseOpusParagraphs(pars))
                    if (out.isNotEmpty()) return out
                }
                val major = md.optJSONObject("major")
                major?.optJSONObject("opus")?.optJSONArray("paragraphs")?.let { pars ->
                    out.addAll(parseOpusParagraphs(pars))
                    if (out.isNotEmpty()) return out
                }
                val descText = md.optJSONObject("desc")?.optString("text", "").orEmpty()
                if (descText.isNotBlank()) out.add(DynamicParagraph.Text(descText))
                major?.optJSONObject("draw")?.optJSONArray("items")?.let { arr ->
                    for (k in 0 until arr.length()) {
                        val u = arr.optJSONObject(k)?.optString("src", "").orEmpty()
                        if (u.isNotBlank()) out.add(DynamicParagraph.Image(u))
                    }
                }
                if (out.none { it is DynamicParagraph.Image }) {
                    major?.optJSONObject("opus")?.optJSONArray("pics")?.let { arr ->
                        for (k in 0 until arr.length()) {
                            val u = arr.optJSONObject(k)?.optString("url", "").orEmpty()
                            if (u.isNotBlank()) out.add(DynamicParagraph.Image(u))
                        }
                    }
                }
            }
        }
        return out
    }

    /**
     * 从 module_content 直接读图片。
     * opus/detail 的数组形态下，图片可能挂在 pics / pic / image_url / urls 等不同字段，
     * 这里穷举常见字段 + 递归扫一层对象，避免漏图。
     */
    private fun readPicsDirect(content: JSONObject): List<DynamicParagraph.Image> {
        val out = ArrayList<DynamicParagraph.Image>()
        fun fromUrlsArray(arr: JSONArray?) {
            arr ?: return
            for (i in 0 until arr.length()) {
                when (val el = arr.opt(i)) {
                    is String -> if (el.startsWith("http")) out.add(DynamicParagraph.Image(el))
                    is JSONObject -> {
                        val u = el.optString("url", "").ifBlank { el.optString("src", "") }
                            .ifBlank { el.optString("image", "") }
                            .ifBlank { el.optString("img_src", "") }
                        if (u.isNotBlank()) out.add(DynamicParagraph.Image(u))
                    }
                }
            }
        }
        fromUrlsArray(content.optJSONArray("pics"))
        fromUrlsArray(content.optJSONArray("images"))
        fromUrlsArray(content.optJSONArray("urls"))
        content.optJSONObject("pic")?.let { pic ->
            val u = pic.optString("url", "").ifBlank { pic.optString("src", "") }
            if (u.isNotBlank()) out.add(DynamicParagraph.Image(u))
            else fromUrlsArray(pic.optJSONArray("urls"))
        }
        content.optJSONObject("image")?.let { img ->
            val u = img.optString("url", "").ifBlank { img.optString("src", "") }
            if (u.isNotBlank()) out.add(DynamicParagraph.Image(u))
        }
        content.optString("image_url", "").takeIf { it.startsWith("http") }?.let {
            out.add(DynamicParagraph.Image(it))
        }
        content.optString("cover", "").takeIf { it.startsWith("http") }?.let {
            out.add(DynamicParagraph.Image(it))
        }
        return out
    }

    /**
     * 递归找 paragraphs：正文在 module_content.paragraphs / major.opus.paragraphs，
     * 但不同接口/动态类型的嵌套深度不一致，固定路径会漏，所以按树搜索。
     * skipOrig=true 时不进入 orig 子树，避免把转发原内容当成自己的正文。
     */
    internal fun findParagraphsDeep(node: Any?, depth: Int, skipOrig: Boolean): JSONArray? {
        if (depth > 5) return null
        when (node) {
            is JSONObject -> {
                for (key in arrayOf("paragraphs", "module_content")) {
                    val arr = node.optJSONArray(key)
                    if (arr != null && arr.length() > 0) return arr
                }
                node.optJSONObject("module_content")?.optJSONArray("paragraphs")?.let {
                    if (it.length() > 0) return it
                }
                for (k in node.keys()) {
                    if (skipOrig && k == "orig") continue
                    val v = node.opt(k)
                    if (v is JSONObject || v is JSONArray) {
                        findParagraphsDeep(v, depth + 1, skipOrig)?.let { return it }
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findParagraphsDeep(node.opt(i), depth + 1, skipOrig)?.let { return it }
                }
            }
        }
        return null
    }

    /** 递归找 orig（转发原动态）：值可能是对象，也可能是数组；JSON null 视为没有 */
    internal fun findOrigDeep(node: Any?, depth: Int): JSONObject? {
        if (depth > 4) return null
        when (node) {
            is JSONObject -> {
                when (val o = node.opt("orig")) {
                    is JSONObject -> return o
                    is JSONArray -> {
                        for (i in 0 until o.length()) {
                            (o.opt(i) as? JSONObject)?.let { return it }
                        }
                    }
                }
                for (k in node.keys()) {
                    if (k == "orig") continue
                    val v = node.opt(k)
                    if (v is JSONObject || v is JSONArray) {
                        findOrigDeep(v, depth + 1)?.let { return it }
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findOrigDeep(node.opt(i), depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    /** 把 item 转成正文段落；自己的正文 + （若有）转发原动态正文 */
    internal fun extractParagraphsFromItem(item: JSONObject): List<DynamicParagraph> {
        val out = ArrayList<DynamicParagraph>()

        // 1) 自己的正文：先走已知稳定路径，拿不到再树搜索兜底
        val known = extractKnownBody(item)
        if (known.isNotEmpty()) {
            out.addAll(known)
        } else {
            findParagraphsDeep(item, 0, skipOrig = true)?.let { pars ->
                out.addAll(parseOpusParagraphs(pars))
            }
        }

        // 2) 转发原动态
        val orig = findOrigDeep(item, 0)
        if (orig != null) {
            val author =
                orig.optJSONObject("modules")?.optJSONObject("module_author")?.optString("name", "").orEmpty()
                    .ifBlank { orig.optJSONObject("user")?.optString("name", "").orEmpty() }
            out.add(DynamicParagraph.Separator)
            out.add(
                DynamicParagraph.Text(
                    if (author.isBlank()) "转发动态" else "转发自 @$author",
                    isHeading = true,
                ),
            )
            val origBody =
                extractKnownBody(orig).ifEmpty {
                    findParagraphsDeep(orig, 0, skipOrig = true)?.let { parseOpusParagraphs(it) } ?: emptyList()
                }
            if (origBody.isNotEmpty()) {
                out.addAll(origBody)
            } else {
                val origModules = orig.optJSONObject("modules")
                val origMd = origModules?.optJSONObject("module_dynamic")
                val oTitle = origMd?.optJSONObject("major")?.optString("title", "").orEmpty()
                if (oTitle.isNotBlank()) out.add(DynamicParagraph.Text(oTitle, isHeading = true))
                val oDesc = origMd?.optJSONObject("desc")?.optString("text", "").orEmpty()
                if (oDesc.isNotBlank()) out.add(DynamicParagraph.Text(oDesc))
                origMd?.optJSONObject("major")?.optJSONObject("opus")?.optJSONArray("pics")?.let { arr ->
                    for (k in 0 until arr.length()) {
                        val u = arr.optJSONObject(k)?.optString("url", "").orEmpty()
                        if (u.isNotBlank()) out.add(DynamicParagraph.Image(u))
                    }
                }
            }
        }

        // 3) 诊断：字段层级不固定，下一个日志能直接看出模块形态
        val modules = item.opt("modules")
        val modulesDesc =
            when (modules) {
                is JSONArray ->
                    "array[" +
                        (0 until modules.length()).joinToString("|") { i ->
                            modules.optJSONObject(i)?.keys()?.asSequence()?.joinToString(",").orEmpty()
                        } + "]"
                is JSONObject -> "object[" + modules.keys().asSequence().joinToString(",") + "]"
                else -> "none"
            }
        val itemKeys = item.keys().asSequence().joinToString(",")
        val bodyCount = out.count { it !is DynamicParagraph.Separator && !(it is DynamicParagraph.Text && it.isHeading) }
        AppLog.d(
            "DynamicBodyParser",
            "extract keys=[$itemKeys] modules=$modulesDesc body=$bodyCount total=${out.size}",
        )
        return out
    }
}
