/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import kotlin.math.roundToLong

/**
 * 纯 Kotlin 的 AMLL TTML 扫描器，线上和 JVM 测试共用同一套容错行为。
 *
 * 行内空白按文档顺序保留，只裁整行首尾。叶子元素提供逐字区间，译文和注音按父行时间
 * 进入独立轨道。背景人声留在正文里，时间倒退的词只参与整行显示。
 *
 * 帧时间按文档帧率或 30 fps 解释，裸数字按秒解释。坏行单独跳过。空、超长或截断的文档
 * 返回 null，调用方可继续尝试其他歌词源。未知实体保留原文。
 */
internal object TtmlScanner {

    /** 单份文档的字符上限。真实 AMLL 文件约 25 KB，这里留了 160 倍余量；再大就当拉到了 HTML 错误页。 */
    private const val MAX_INPUT_CHARS = 4 * 1024 * 1024

    /** 元素总数上限。真实文件约 500 个元素；上限防的是「几十万个空标签」。 */
    private const val MAX_ELEMENTS = 100_000

    /** span 嵌套深度上限。真实文件 ≤ 4 层。 */
    private const val MAX_DEPTH = 24

    /** TTML1 规定的默认帧率。 */
    private const val DEFAULT_FRAME_RATE = 30

    /** 时间上限（≈31.7 年）。超过它一定是脏数据，顺便保证 Double→Long 不溢出。 */
    private const val MAX_MS = 1.0E12

    private const val ROLE_TRANSLATION = "x-translation"
    private const val ROLE_ROMAN = "x-roman"
    private val byTime = Comparator<LrcLine> { first, second -> first.timeMs.compareTo(second.timeMs) }

    /**
     * 解析 TTML 原文，失败返回 `null`。
     *
     * 整个解析包在 `catch (Throwable)` 里：契约要求「绝不抛」。畸形输入只应让调用方回退到下一级
     * 歌词源，不该让播放器崩；入口已经有长度/元素数/深度三重上限，这里只是最后一道保险。
     */
    fun parse(xml: String): TtmlDoc? = try {
        parseOrNull(xml)
    } catch (ignored: Throwable) {
        null
    }

    // ---------------------------------------------------------------- 解析主体

    private fun parseOrNull(xml: String): TtmlDoc? {
        if (xml.isEmpty() || xml.length > MAX_INPUT_CHARS) return null

        val document = buildTree(xml) ?: return null
        val root = document.children.firstOrNull { it is Node } as? Node ?: return null
        val frameRate = root.attr("frameRate")?.trim()?.toIntOrNull()?.takeIf { it in 1..240 }
            ?: DEFAULT_FRAME_RATE

        val meta = readMeta(root)

        // <p> 只可能出现在 <body> 下（含没有 <div> 包裹的形态）；没有 <body> 时退回全树扫描。
        val scope = collectElements(root, "body", limit = 1).firstOrNull() ?: root
        val paragraphs = collectElements(scope, "p")

        val lines = ArrayList<LrcLine>(paragraphs.size)
        val translations = ArrayList<LrcLine>()
        val romans = ArrayList<LrcLine>()
        for (p in paragraphs) {
            val parsed = buildLine(p, frameRate) ?: continue   // 坏行只跳过它自己
            lines.add(parsed.line)
            for ((role, line) in parsed.roles) {
                when (role) {
                    ROLE_TRANSLATION -> translations.add(line)
                    ROLE_ROMAN -> romans.add(line)
                }
            }
        }
        if (lines.isEmpty()) return null

        // 原地稳定排序，同刻行保持文档顺序。
        lines.sortWith(byTime)
        translations.sortWith(byTime)
        romans.sortWith(byTime)
        return TtmlDoc(
            lines = lines,
            translations = translations,
            romans = romans,
            meta = meta,
        )
    }

    /** 一行正文 + 它带出来的角色轨道行。 */
    private class ParsedLine(val line: LrcLine, val roles: List<Pair<String, LrcLine>>)

    private fun buildLine(p: Node, frameRate: Int): ParsedLine? {
        val beginRaw = p.attr("begin")
        // begin 缺席按 0 处理（保住这句歌词）；写了但解析不出说明这行是脏数据，整行跳过。
        val lineBegin = if (beginRaw == null) 0L else parseTime(beginRaw, frameRate) ?: return null
        val lineEnd = p.attr("end")?.let { parseTime(it, frameRate) }

        val textBuilder = StringBuilder()
        val drafts = ArrayList<WordDraft>()
        val roleTexts = ArrayList<Pair<String, String>>()
        walk(p.children, 0, lineBegin, frameRate, textBuilder, drafts, roleTexts)

        val raw = textBuilder.toString()
        val from = raw.indexOfFirst { !it.isWhitespace() }
        if (from < 0) return null   // 整行只有空白（含空的 <p>）：没有可展示的内容
        val to = raw.indexOfLast { !it.isWhitespace() } + 1
        val text = raw.substring(from, to)

        val words = ArrayList<LrcWord>(drafts.size)
        fun appendWord(draft: WordDraft, next: WordDraft?) {
            val start = draft.charStart.coerceAtLeast(from) - from
            val end = draft.charEndExclusive.coerceAtMost(to) - from
            words.add(
                LrcWord(
                    startMs = draft.startMs,
                    durationMs = durationOf(draft, next, lineEnd),
                    text = text.substring(start, end),
                    charStart = start,
                    charEndExclusive = end,
                )
            )
        }
        // 背景人声的时间可能早于前面的正文。保留正文，跳过会导致高亮倒退的词。
        var previous: WordDraft? = null
        for (draft in drafts) {
            if (draft.charEndExclusive.coerceAtMost(to) <= draft.charStart.coerceAtLeast(from)) continue
            if (previous != null) {
                if (draft.startMs < previous.startMs) continue
                appendWord(previous, draft)
            }
            previous = draft
        }
        if (previous != null) appendWord(previous, null)

        return ParsedLine(
            line = LrcLine(timeMs = lineBegin, text = text, words = words, endMs = lineEnd),
            roles = roleTexts.map { (role, t) -> role to LrcLine(timeMs = lineBegin, text = t, endMs = lineEnd) },
        )
    }

    /** 词草稿：区间是相对「未裁剪的原始行文本」的，裁完再平移。 */
    private class WordDraft(
        val startMs: Long,
        val endMs: Long?,
        val charStart: Int,
        val charEndExclusive: Int,
    )

    private fun durationOf(d: WordDraft, next: WordDraft?, lineEnd: Long?): Long {
        val end = d.endMs
        if (end != null && end > d.startMs) return end - d.startMs
        val nextBegin = next?.startMs
        if (nextBegin != null && nextBegin > d.startMs) return nextBegin - d.startMs
        if (lineEnd != null && lineEnd > d.startMs) return lineEnd - d.startMs
        return 0L
    }

    /**
     * 展开一个 `<p>` 的子节点：文本按文档顺序进 [textBuilder]（游标即 `textBuilder.length`），
     * 叶子 span 成词，角色 span 走 [roleTexts] 不进正文。
     */
    private fun walk(
        nodes: List<Any>,
        depth: Int,
        lineBegin: Long,
        frameRate: Int,
        textBuilder: StringBuilder,
        words: MutableList<WordDraft>,
        roleTexts: MutableList<Pair<String, String>>,
    ) {
        for (node in nodes) {
            if (node is String) {
                textBuilder.append(node)
                continue
            }
            val el = node as Node

            val role = el.attr("role")
            if (role == ROLE_TRANSLATION || role == ROLE_ROMAN) {
                // 角色子树完全不参与正文与游标（规则 4）。实测《海阔天空》P5 有嵌在 x-bg 里的 x-roman，
                // 所以这里必须在任意深度判断，不能只看 <p> 的直接子元素。
                val roleText = collectText(el, 0).trim()
                if (roleText.isNotEmpty()) roleTexts.add(role to roleText)
                continue
            }

            if (depth >= MAX_DEPTH) continue

            // 只有叶子元素的文本连续，可以用同一个词区间表示。
            if (el.children.any { it is Node }) {
                walk(el.children, depth + 1, lineBegin, frameRate, textBuilder, words, roleTexts)
                continue
            }
            val entry = textBuilder.length
            var hasText = false
            for (child in el.children) {
                val text = child as String
                textBuilder.append(text)
                if (!hasText && text.isNotBlank()) hasText = true
            }
            if (!hasText) continue
            val beginRaw = el.attr("begin")
            val startMs = if (beginRaw == null) {
                words.lastOrNull()?.endMs ?: lineBegin
            } else {
                parseTime(beginRaw, frameRate) ?: continue
            }
            val endMs = el.attr("end")?.let { parseTime(it, frameRate) }
            words.add(WordDraft(startMs, endMs, entry, textBuilder.length))
        }
    }

    // ---------------------------------------------------------------- 元数据

    private fun readMeta(root: Node): TtmlMeta {
        var meta = TtmlMeta()
        for (m in collectElements(root, "meta")) {
            val key = m.attr("key") ?: continue
            val value = m.attr("value")?.trim() ?: continue
            if (value.isEmpty()) continue
            meta = when (key) {
                "ncmMusicId" -> meta.copy(ncmMusicId = value)
                "musicName" -> meta.copy(musicName = value)
                "artists" -> meta.copy(artists = value)
                "album" -> meta.copy(album = value)
                "ttmlAuthorGithubLogin" -> meta.copy(author = value)
                else -> meta
            }
        }
        return meta
    }

    // ---------------------------------------------------------------- XML 扫描

    /** 一个元素：名字只保留 localName（命名空间前缀任意），属性原样保留（含前缀）。 */
    private class Node(val name: String, val attrs: List<Pair<String, String>>) {
        val children = ArrayList<Any>(0)

        /** 按 localName 找属性。xmlns 声明跳过，避免 `xmlns:foo` 被当成 `foo` 属性。 */
        fun attr(local: String): String? {
            for ((raw, value) in attrs) {
                if (raw == "xmlns" || raw.startsWith("xmlns:")) continue
                if (raw == local) return value
                if (raw.length > local.length && raw.endsWith(local) &&
                    raw[raw.length - local.length - 1] == ':'
                ) {
                    return value
                }
            }
            return null
        }
    }

    private class Tag(val node: Node, val selfClosing: Boolean, val end: Int)

    /**
     * 把原文扫成一棵轻量树；**结构不完整返回 null**（截断的输入就靠这里挡掉）。
     *
     * 用显式栈而不是递归，几十万层嵌套也爆不了 JVM 栈。
     */
    private fun buildTree(src: String): Node? {
        val document = Node("", emptyList())
        val stack = ArrayList<Node>()
        stack.add(document)
        var elements = 0
        var i = 0
        val n = src.length

        while (i < n) {
            val lt = src.indexOf('<', i)
            if (lt < 0) {
                stack.last().children.add(textNode(src, i, n))
                break
            }
            if (lt > i) stack.last().children.add(textNode(src, i, lt))
            i = lt

            when {
                src.startsWith("<!--", i) -> {
                    val end = src.indexOf("-->", i + 4)
                    if (end < 0) return null
                    i = end + 3
                }
                src.startsWith("<![CDATA[", i) -> {
                    val end = src.indexOf("]]>", i + 9)
                    if (end < 0) return null
                    stack.last().children.add(src.substring(i + 9, end))   // CDATA 内容不做实体解码
                    i = end + 3
                }
                src.startsWith("<!", i) -> {                                // DOCTYPE 等
                    val end = src.indexOf('>', i)
                    if (end < 0) return null
                    i = end + 1
                }
                src.startsWith("<?", i) -> {                                // XML 声明 / 处理指令
                    val end = src.indexOf("?>", i)
                    if (end < 0) return null
                    i = end + 2
                }
                src.startsWith("</", i) -> {
                    val end = src.indexOf('>', i)
                    if (end < 0) return null
                    val name = localName(src.substring(i + 2, end))
                    if (name.isNotEmpty()) {
                        // 结束标签按最近的同名开放元素配对；配对不上就忽略（多余标签不算致命）。
                        val index = stack.indexOfLast { it.name == name }
                        if (index > 0) while (stack.size > index) stack.removeAt(stack.lastIndex)
                    }
                    i = end + 1
                }
                else -> {
                    val tag = readStartTag(src, i) ?: return null
                    if (++elements > MAX_ELEMENTS) return null
                    stack.last().children.add(tag.node)
                    if (!tag.selfClosing) stack.add(tag.node)
                    i = tag.end
                }
            }
        }

        // 还有没闭合的元素 = 输入被截断（或压根不是一份完整 XML），整份作废。
        return if (stack.size == 1) document else null
    }

    /** 读一个开始标签（[start] 指向 '<'）。属性单/双引号、顺序任意；畸形返回 null。 */
    private fun readStartTag(src: String, start: Int): Tag? {
        val n = src.length
        var i = start + 1
        val nameFrom = i
        while (i < n && !src[i].isWhitespace() && src[i] != '/' && src[i] != '>') i++
        if (i == nameFrom) return null
        val name = localName(src.substring(nameFrom, i))
        val attrs = ArrayList<Pair<String, String>>(4)

        while (true) {
            while (i < n && src[i].isWhitespace()) i++
            if (i >= n) return null
            when (src[i]) {
                '>' -> return Tag(Node(name, attrs), selfClosing = false, end = i + 1)
                '/' -> {
                    if (i + 1 >= n || src[i + 1] != '>') return null
                    return Tag(Node(name, attrs), selfClosing = true, end = i + 2)
                }
            }

            val attrFrom = i
            while (i < n && !src[i].isWhitespace() && src[i] != '=' && src[i] != '/' && src[i] != '>') i++
            if (i == attrFrom) return null
            val attrName = src.substring(attrFrom, i)

            while (i < n && src[i].isWhitespace()) i++
            if (i < n && src[i] == '=') {
                i++
                while (i < n && src[i].isWhitespace()) i++
                if (i >= n) return null
                val quote = src[i]
                if (quote != '"' && quote != '\'') return null
                i++
                val valueFrom = i
                val valueTo = src.indexOf(quote, i)
                if (valueTo < 0) return null
                attrs.add(attrName to decodeEntities(src.substring(valueFrom, valueTo)))
                i = valueTo + 1
            } else {
                // 无值属性（HTML 风格残留）：值记空串，不影响按 localName 取值
                attrs.add(attrName to "")
            }
        }
    }

    private fun localName(raw: String): String {
        val name = raw.trim()
        val colon = name.lastIndexOf(':')
        return if (colon >= 0) name.substring(colon + 1) else name
    }

    /** 子树全部文本按文档顺序拼接（角色轨道用）。 */
    private fun collectText(el: Node, depth: Int): String {
        val sb = StringBuilder()
        appendText(el, sb, depth)
        return sb.toString()
    }

    private fun appendText(el: Node, sb: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH) return
        for (c in el.children) {
            when (c) {
                is String -> sb.append(c)
                is Node -> appendText(c, sb, depth + 1)
            }
        }
    }

    /** 按文档顺序（前序）收集某 localName 的全部元素。 */
    private fun collectElements(root: Node, name: String, limit: Int = Int.MAX_VALUE): List<Node> {
        val out = ArrayList<Node>()
        val stack = ArrayList<Node>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.lastIndex)
            if (node.name == name) {
                out.add(node)
                if (out.size == limit) break
            }
            // 倒序入栈，弹出时才是文档顺序
            for (i in node.children.indices.reversed()) {
                val child = node.children[i]
                if (child is Node) stack.add(child)
            }
        }
        return out
    }

    private fun textNode(src: String, from: Int, to: Int): String {
        if (to <= from) return ""
        return decodeEntities(src.substring(from, to))
    }

    /**
     * XML 实体解码。未定义实体（`&nbsp;`）与非法码点（代理区、超出 U+10FFFF、数字溢出）
     * 一律原样保留，不抛异常、不产生非法字符。
     */
    private fun decodeEntities(raw: String): String {
        val firstEntity = raw.indexOf('&')
        if (firstEntity < 0) return raw
        val sb = StringBuilder(raw.length)
        sb.append(raw, 0, firstEntity)
        var i = firstEntity
        while (i < raw.length) {
            val c = raw[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val end = minOf(raw.length, i + 33)
            var semi = i + 1
            while (semi < end && raw[semi] != ';') semi++
            if (semi == end) {
                sb.append(c)
                i++
                continue
            }
            val body = raw.substring(i + 1, semi)
            val decoded = when (body) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> when {
                    body.startsWith("#x") || body.startsWith("#X") ->
                        codePointToString(body.substring(2).toIntOrNull(16))
                    body.startsWith("#") -> codePointToString(body.substring(1).toIntOrNull())
                    else -> null
                }
            }
            if (decoded == null) {
                sb.append(c)
                i++
            } else {
                sb.append(decoded)
                i = semi + 1
            }
        }
        return sb.toString()
    }

    private fun codePointToString(codePoint: Int?): String? {
        if (codePoint == null || codePoint < 0 || codePoint > 0x10FFFF) return null
        if (codePoint in 0xD800..0xDFFF) return null   // 代理区不是合法码点
        return String(Character.toChars(codePoint))
    }

    // ---------------------------------------------------------------- 时间

    /**
     * TTML 时间表达式 → 绝对毫秒。支持 `MM:SS.fff` / `HH:MM:SS.fff`（冒号段数 2–3，末段可带小数，
     * 逗号当小数点也算）/ offset-time（h、m、s、ms、f）/ 裸数字（按秒，见 KDoc 实测依据）。
     */
    private fun parseTime(raw: String, frameRate: Int): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null

        if (s.indexOf(':') >= 0) {
            val parts = s.split(':')
            if (parts.size !in 2..3) return null
            var seconds = 0.0
            for (part in parts) {
                val value = part.replace(',', '.').toDoubleOrNull() ?: return null
                if (value < 0) return null
                seconds = seconds * 60 + value
                if (seconds * 1000.0 > MAX_MS) return null
            }
            return secondsToMs(seconds)
        }

        val metricFrom = s.indexOfFirst { !it.isDigit() && it != '.' }
        if (metricFrom <= 0) {
            // 裸数字：实测就是秒（1974443814 的 begin="28.571" ↔ LRC [00:28.15]）
            val seconds = s.toDoubleOrNull() ?: return null
            return secondsToMs(seconds)
        }

        val number = s.substring(0, metricFrom).toDoubleOrNull() ?: return null
        if (number < 0) return null
        val seconds = when (s.substring(metricFrom)) {
            "h" -> number * 3600
            "m" -> number * 60
            "s" -> number
            "ms" -> number / 1000
            "f" -> number / frameRate
            else -> return null
        }
        return secondsToMs(seconds)
    }

    private fun secondsToMs(seconds: Double): Long? {
        if (seconds.isNaN() || seconds < 0) return null
        val ms = seconds * 1000.0
        if (ms > MAX_MS) return null
        // 必须四舍五入：22.402 * 1000 在 double 下是 22401.999…，截断会平白少 1 ms
        return ms.roundToLong()
    }
}
