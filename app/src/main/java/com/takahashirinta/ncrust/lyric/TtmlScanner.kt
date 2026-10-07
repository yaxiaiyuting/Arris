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
 * AMLL TTML → [TtmlDoc] 的**纯 Kotlin 扫描器**（v1.9.0）。
 *
 * ## 为什么手写，而不是 android.util.Xml / XmlPullParser / kxml2
 *
 * 要求是「零依赖 + 无 Android 框架依赖 + JVM 可单测」三条同时成立，而这三条把现成的 XML 解析器
 * 全部排除了：
 *
 * - `android.util.Xml` / `XmlPullParser` 是 **Android 框架 API**。JVM 单测里它们只是抛异常的桩，
 *   `app/build.gradle.kts` 的 `unitTests.isReturnDefaultValues = true` 也只是让桩返回默认值，
 *   并不会真的解析 —— 用它就等于「线上能跑、单测跑不了」。
 * - kxml2 一类的纯 JVM 解析器能进单测，但那是**另一份实现**：单测验的是 kxml2 的行为，线上跑的是
 *   Android 框架的 XmlPullParser，两者对本任务书专门要求的容错输入（截断、多余结束标签、裸 `&`）
 *   处理并不一致，保真度反而更差。
 *
 * 所以这里手写：**同一份代码既被测又上线**。TTML 用到的 XML 子集很小（元素、属性、文本、CDATA、
 * 注释、实体），不需要通用 XML 的全部能力。
 *
 * ## 契约
 * - 输入是 AMLL TTML DB 的 `.ttml` 原文（UTF-8 字符串），输出 [TtmlDoc]；
 * - **绝不抛异常**：空串 / 非 XML / 结构被截断 / 超长（> [MAX_INPUT_CHARS]）→ `null`；
 *   一份文档里一行歌词都没有 → `null`（调用方据此回退到下一级歌词源）；
 * - 单个 `<p>` 的内容坏掉（`begin` 解析不出、整行只有空白）→ **只跳过这一行**，其余照常解析。
 *
 * ## 与渲染层的约定（[LrcLine] / [LrcWord] 的语义）
 * - `<p>` → 一行 [LrcLine]，叶子 `<span>` → 一个 [LrcWord]，两者复用既有模型，渲染层零改动。
 * - **行文本 = `<p>` 内所有文本节点的拼接，含 span 之间的裸空白**，只裁整行首尾。
 *   实测依据：ncm 自己的 LRC 就是 `[00:22.290]都 是勇敢的`（中间有空格），与 TTML 拼接结果逐字
 *   相同；吞掉这个空白会让 [LrcWord.charStart] 与行文本错位，逐字高亮直接错帧。
 *   （Kotlin 的 `isWhitespace` 认全角空格 U+3000 与 NBSP，行内空白一律原样保留。）
 * - **字符区间**以「行内已累积字符数」为游标：词的 `charStart` = 进入该 span 时的游标，
 *   `charEndExclusive` = `charStart` + 该 span 的直接文本长度。因此**容器型 span 不建词**
 *   （内部还有子元素的，实测只有 `ttm:role="x-bg"`）：它自己的「直接文本长度」对它并不成立 ——
 *   《孤勇者》L51 的 x-bg 容器直接文本只是两个子 span 之间那一个空格，照长度圈区间会圈到子 span
 *   的字符上。容器只递归子节点，子 span 照常成词。行内**字符区间单调不重叠**（与 yrc 对齐后的
 *   词同一不变量）。
 * - **`ttm:role="x-bg"`（背景人声）本版不单独建模**（规则 4 的取舍）：它的文本按普通词处理，
 *   于是背景人声与主唱混在同一行 —— 这是有意的，收益是背景人声也能逐字高亮。
 *   代价是**背景人声的时间经常与文本顺序相反**（实测《孤勇者》L51：主唱 去/吗/去/啊/以/最卑微/的/梦
 *   唱到 03:44.938，而文本序排在末尾的背景词 `(去吗` 是 03:42.019）。这种**时间倒退**的词会被
 *   [buildLine] 的单调过滤丢弃（行文本仍完整保留），所以 [LrcLine.words] 同时满足两条不变量：
 *   **字符区间沿文本顺序单调不重叠**，且 **`startMs` 沿文本顺序单调不减**。
 *   这两条都是给冻结的渲染层（`SweepTrack.build` 按 startMs 排序）兜底的 —— 少了任何一条，
 *   逐字扫过都会倒着扫。
 * - **`ttm:role="x-translation"` / `"x-roman"`** 是 `<p>` 的子元素（也可能嵌在 x-bg 里），
 *   整棵子树既不进入该行文本也不建词，而是各自升成独立的**行级**轨道
 *   （[TtmlDoc.translations] / [TtmlDoc.romans]）。行级轨道的时间戳取**父 `<p>` 的 begin**，
 *   这样调用方能直接按时间与 [TtmlDoc.lines] 配对（语义同 ncm 的 tlyric）。
 *   轨道**不带逐字词**：实测《海阔天空》347230 的 x-roman 连 begin/end 都没有（`<span
 *   ttm:role="x-roman">gam tin ngo  ...</span>`），给逐字只会是假信息。
 * - **`end` 缺失时**：行 [LrcLine.endMs] = `null`；词时长依次用「下一个词 begin − 本词 begin」、
 *   「行 end − 本词 begin」兜底，都没有则 0。
 * - 词 `begin` 缺失时接在上一词的 end 之后、再退回行首；词 `begin` 存在但解析不出，则该词
 *   不建（它的文本仍留在行里，不影响其余词）。
 *
 * ## 时间表达式
 * `MM:SS.fff`（分钟允许 1 位，实测《我记得》1974443814 用 `1:01.110`）、`HH:MM:SS.fff`、
 * offset-time（`12.3s` / `1500ms` / `10f` / `2m` / `1.5h`），另外**额外**接受裸数字（按秒）：
 * 同一个真实文件 1974443814 前 7 行用 `begin="28.571"`，与 ncm LRC 的 `[00:28.15]我带着比身体重的行李`
 * 逐句对齐 ⇒ 裸数字就是秒。帧数按 TTML1 默认 30 fps 换算（根元素带 `ttp:frameRate` 时以它为准）。
 * 小数秒用 roundToLong 而不是截断 —— `22.402 * 1000` 在 double 下是 22401.999…，截断会差 1 ms。
 *
 * ## 容错（规则 6/7）
 * 命名实体（`&amp; &lt; &gt; &quot; &apos;`）、十进制/十六进制数字实体、CDATA、注释、自闭合标签、
 * 单/双引号属性、任意属性顺序、`<p>` 直接挂在 `<body>` 下（或没有 `<div>`）、命名空间前缀任意
 * （**一律按 localName 匹配**）都能处理；未定义实体与非法码点原样保留。
 * 结构不完整（读到结尾还有未闭合元素 ⇒ 被截断）整份返回 `null`；多余的结束标签、未知元素、
 * 未知属性一律忽略。元素总数与嵌套深度都有上限，恶意输入不会吃光内存或爆栈。
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
        val scope = collectElements(root, "body").firstOrNull() ?: root
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

        // 文档顺序通常就是时间顺序；排序是稳定排序，同刻行保持文档顺序。
        return TtmlDoc(
            lines = lines.sortedBy { it.timeMs },
            translations = translations.sortedBy { it.timeMs },
            romans = romans.sortedBy { it.timeMs },
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

        // 行首尾裁掉多少，词的区间就整体左移多少；被裁掉的词直接丢弃。
        val kept = ArrayList<WordDraft>(drafts.size)
        for (d in drafts) {
            val cs = (d.charStart - from).coerceAtLeast(0)
            val ce = (d.charEndExclusive - from).coerceAtMost(text.length)
            if (ce > cs) kept.add(WordDraft(d.startMs, d.endMs, cs, ce))
        }
        // **词表必须满足「文本顺序 == 时间顺序」**，这是与渲染层之间的硬不变量：
        // 渲染路径里的 `SweepTrack.build` 会做
        // `words.sortedWith(compareBy({ it.startMs }, { it.charStart }))`（SweepTrack.kt:244），
        // 而那条路径**本版冻结、一行不许改**。只要有一个词的开始时间早于文本序在它前面的词，
        // 排序就会把它的字符区间搬到前面 —— 扫过光标表现为**倒着扫**。
        //
        // 现实里唯一的来源是 `ttm:role="x-bg"`（背景人声）：它的子 span 按文本顺序进本行，
        // 但背景人声的时间经常落在主唱之后、却排在文本末尾。实测《孤勇者》L51 —— 主唱词唱到
        // 03:44.938，而文本序排在最后的背景词是 03:42.019。这类**时间倒退**的词一律丢弃
        // （**行文本仍完整保留**，只是那半句不给逐字高亮）：宁可少高亮几个字，也不给一次倒扫。
        val ordered = ArrayList<WordDraft>(kept.size)
        var lastStart = Long.MIN_VALUE
        for (d in kept) {
            if (d.startMs < lastStart) continue
            ordered.add(d)
            lastStart = d.startMs
        }
        val words = ArrayList<LrcWord>(ordered.size)
        for ((index, d) in ordered.withIndex()) {
            words.add(
                LrcWord(
                    startMs = d.startMs,
                    durationMs = durationOf(d, ordered.getOrNull(index + 1), lineEnd),
                    // 词文本取行文本的切片，保证 text.substring(charStart, charEndExclusive) 恒等于词文本
                    text = text.substring(d.charStart, d.charEndExclusive),
                    charStart = d.charStart,
                    charEndExclusive = d.charEndExclusive,
                )
            )
        }

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

            // 只有叶子 span 才建词：容器型 span 的直接文本长度圈不出正确区间（见 KDoc）。
            if (el.children.none { it is Node }) {
                val direct = directText(el)
                if (direct.isNotBlank()) {
                    val beginRaw = el.attr("begin")
                    val startMs = if (beginRaw == null) {
                        words.lastOrNull()?.endMs ?: lineBegin
                    } else {
                        parseTime(beginRaw, frameRate)
                    }
                    if (startMs != null) {
                        val endMs = el.attr("end")?.let { parseTime(it, frameRate) }
                        val entry = textBuilder.length
                        words.add(WordDraft(startMs, endMs, entry, entry + direct.length))
                    }
                }
            }
            walk(el.children, depth + 1, lineBegin, frameRate, textBuilder, words, roleTexts)
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

    /** 元素的直接文本（只拼字符串子节点，不含嵌套元素）。 */
    private fun directText(el: Node): String {
        val sb = StringBuilder()
        for (c in el.children) if (c is String) sb.append(c)
        return sb.toString()
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
    private fun collectElements(root: Node, name: String): List<Node> {
        val out = ArrayList<Node>()
        val stack = ArrayList<Node>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.lastIndex)
            if (node.name == name) out.add(node)
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
        val raw = src.substring(from, to)
        return if (raw.indexOf('&') >= 0) decodeEntities(raw) else raw
    }

    /**
     * XML 实体解码。未定义实体（`&nbsp;`）与非法码点（代理区、超出 U+10FFFF、数字溢出）
     * 一律原样保留，不抛异常、不产生非法字符。
     */
    private fun decodeEntities(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val semi = raw.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 32) {   // 实体名最长 32 字符，超了就是裸 '&'
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
