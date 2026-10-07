/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · D：QRC（qm 逐字歌词）解析。
 */

package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.lyric.LrcWord

/**
 * QRC 逐字歌词解析（v2.1.0 · D）。**纯逻辑、零依赖、JVM 可单测。**
 *
 * ## 格式（2026-09 对真实响应核实）
 *
 * 解密出来是一份 XML，正文整段塞在 `Lyric_1` 的 `LyricContent` **属性**里
 * （不是元素文本），属性值里带真实换行与 XML 实体转义：
 *
 * ```xml
 * <?xml version="1.0" encoding="utf-8"?>
 * <QrcInfos>
 * <QrcHeadInfo SaveTime="269" Version="100"/>
 * <LyricInfo LyricCount="1">
 * <Lyric_1 LyricType="1" LyricContent="[ti:晴天]
 * [ar:周杰伦]
 * [offset:0]
 * [0,2250]晴(0,160)天(160,160) (320,160)-(480,160) (640,160)周(800,160)杰(960,160)伦(1120,160)
 * [2250,2250]词(2250,450)：(2700,450)周(3150,450)杰(3600,450)伦(4050,450)"/>
 * </LyricInfo>
 * </QrcInfos>
 * ```
 *
 * 文法：
 * - 每行 `[行起始ms,行时长ms]` + 若干 `词文本(词起始ms,词时长ms)`；
 * - **词起始是绝对毫秒**（相对整首歌开头），与网易 `yrc` 同一口径 ——
 *   第二行 `[2250,...]` 的第一个词就是 `(2250,...)`，两处对得上；
 * - 词文本一直到下一个 `(` 为止，**可以只含空格**（`晴天` 与 `周杰伦` 之间那个
 *   `(320,160)` 的词文本就是一个空格）；
 * - 括号里只有**两个**数字（yrc 是三个，第三个恒 0 无语义）。
 *
 * ## 与网易 `YrcParser` 的关键差异：**不需要对齐**
 *
 * 网易那边 yrc 与 lrc 是两份独立资产、行文本还不一致（空格差异），所以要按行序/LCS 对齐、
 * 把词的字符区间映射回 lrc 文本。**QRC 自带文本**（行文本 = 各词文本拼接），
 * 因此这里直接构造 [LrcLine] 与 [LrcWord]，字符区间在拼接时就是准的，
 * 不存在对齐失败、也不存在「宁可没有逐字」的降级分支。
 */
object QrcParser {

    /** `[行起始ms,行时长ms]` 开头的内容行。元数据行（`[ti:…]`、`[offset:0]`）天然不匹配。 */
    private val LINE_REGEX = Regex("""^\[(\d+),(\d+)\](.*)$""")

    /** 行内的 `(词起始ms,词时长ms)`。 */
    private val WORD_REGEX = Regex("""\((\d+),(\d+)\)""")

    /**
     * 从解密后的 XML 里取出 `LyricContent` 属性的值。
     *
     * 属性值里可能出现 `&quot;`（真引号一定被转义过），所以「扫到下一个裸 `"`」是安全的。
     * 找不到 `LyricContent` 时返回 null —— 调用方据此走「没有歌词」，
     * 而不是拿整份 XML 去当歌词解析（那会得到 0 行，与「没有」其实是同一个结果，
     * 但日志里分得清是「格式变了」还是「服务端没给」）。
     */
    fun extractContent(xml: String?): String? {
        if (xml.isNullOrBlank()) return null
        val marker = "LyricContent=\""
        val start = xml.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val end = xml.indexOf('"', valueStart)
        if (end < 0) return null
        return unescapeXml(xml.substring(valueStart, end))
    }

    /** 解密后的 XML → [LrcLine] 列表。解析不出任何行时返回空列表（不是 null）。 */
    fun parseXml(xml: String?): List<LrcLine> = parseContent(extractContent(xml))

    /**
     * `LyricContent` 的**文本** → [LrcLine] 列表（已 [extractContent] 过的输入）。
     *
     * 保序输出（**不排序**）：QRC 的行本来就是按时间递增的，重排会掩盖服务端偶发的乱序，
     * 而渲染层是按列表顺序扫的。真出现乱序时由上层决定怎么办，解析器不擅自修数据。
     */
    fun parseContent(content: String?): List<LrcLine> {
        if (content.isNullOrBlank()) return emptyList()
        val lines = mutableListOf<LrcLine>()
        for (rawLine in content.split('\n')) {
            val line = rawLine.trim('\r')
            val match = LINE_REGEX.find(line.trim()) ?: continue
            val lineStart = match.groupValues[1].toLongOrNull() ?: continue
            val lineDuration = match.groupValues[2].toLongOrNull() ?: 0L
            val body = match.groupValues[3]
            val parsed = parseLineBody(body, lineStart) ?: continue
            lines.add(
                LrcLine(
                    timeMs = lineStart,
                    text = parsed.first,
                    words = parsed.second,
                    endMs = if (lineDuration > 0L) lineStart + lineDuration else null,
                )
            )
        }
        return lines
    }

    /**
     * 解析一行的正文：`晴(0,160)天(160,160) ` → (`晴天 `, [两个词])。
     *
     * 返回 null 表示这一行**没有可显示文本**（空行、纯空白、只有空白词），
     * 与「解析失败」不是一回事 —— 空行在 QRC 里是真实存在的（间奏处 `[4000,0]`），
     * 上层的渲染链按「无文本行」处理即可。
     */
    private fun parseLineBody(body: String, lineStart: Long): Pair<String, List<LrcWord>>? {
        // 1. 先按「词」切。QRC 的文法是 `词文本(词起始,词时长)` ——
        //    文本写在它自己的时间元组**之前**，所以每个文本段要用**紧随其后**那个元组的时间。
        //    （写成「用前一个元组的时间」是很容易犯的错：`晴(0,160)天(160,160)` 里
        //     `天` 会被记成从 0 开始，整行逐字高亮提前一句。）
        data class RawWord(val text: String, val start: Long, val dur: Long)

        val raws = mutableListOf<RawWord>()
        var cursor = 0
        for (m in WORD_REGEX.findAll(body)) {
            val text = body.substring(cursor, m.range.first)
            cursor = m.range.last + 1
            if (text.isEmpty()) continue
            raws.add(
                RawWord(
                    text = text,
                    start = m.groupValues[1].toLongOrNull() ?: lineStart,
                    dur = m.groupValues[2].toLongOrNull() ?: 0L,
                )
            )
        }
        // 最后一个元组之后若还有尾文本（没有自己的时间戳），用行首时间兜底 ——
        // 真实样本里没出现过，但让它恒不高亮比让它整段丢失好。
        if (cursor < body.length) {
            val tail = body.substring(cursor)
            if (tail.isNotEmpty()) raws.add(RawWord(tail, lineStart, 0L))
        }
        if (raws.isEmpty()) return null

        // 2. 拼出整行文本，同时记下每个词的字符区间。
        val joined = buildString { raws.forEach { append(it.text) } }
        val leading = joined.indexOfFirst { !it.isWhitespace() }
        if (leading < 0) return null // 整行都是空白
        val trailing = joined.indexOfLast { !it.isWhitespace() }
        val text = joined.substring(leading, trailing + 1)

        val words = mutableListOf<LrcWord>()
        var charCursor = 0
        for (raw in raws) {
            val rawStart = charCursor
            charCursor += raw.text.length
            // 平移到裁剪后的坐标系，并夹到 [0, text.length]
            val start = (rawStart - leading).coerceIn(0, text.length)
            val end = (charCursor - leading).coerceIn(0, text.length)
            if (end <= start) continue // 落在被裁掉的空白里
            words.add(
                LrcWord(
                    startMs = raw.start,
                    durationMs = raw.dur,
                    // ⚠️ 用**裁剪后坐标系里的实际切片**，不是原始词文本。
                    // 行首行尾的空白被裁掉时，边界上的那个词会只留下一部分
                    // （真实样本里 `La ` 这种带尾空格的词就踩到了），
                    // 此时若还挂原始文本，`text.substring(charStart, charEndExclusive) != word.text`
                    // —— 渲染层按区间取版面路径，两者不一致就是「唱到别字」或串位。
                    // 这条不变量由 QrcParserTest 与真实响应探针共同守着。
                    text = text.substring(start, end),
                    charStart = start,
                    charEndExclusive = end,
                )
            )
        }
        return text to words
    }

    /**
     * XML 实体反转义。**`&amp;` 必须最后替换** —— 否则 `&amp;lt;` 会被先变成 `&lt;`
     * 再变成 `<`（双重解码），把服务端原文里的字面量 `&lt;` 吃掉。
     */
    fun unescapeXml(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '&') {
                sb.append(ch)
                i++
                continue
            }
            val semi = s.indexOf(';', i + 1)
            // 实体名最长 32 字符（&#x1F600; 之类），超过就不当实体处理，避免病态扫描。
            if (semi < 0 || semi - i > 32) {
                sb.append(ch)
                i++
                continue
            }
            val entity = s.substring(i + 1, semi)
            val decoded = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.substring(2).toIntOrNull(16)?.let { codePointToString(it) }
                entity.startsWith("#") ->
                    entity.substring(1).toIntOrNull()?.let { codePointToString(it) }
                else -> null
            }
            if (decoded == null) {
                sb.append(ch)
                i++
            } else {
                sb.append(decoded)
                i = semi + 1
            }
        }
        return sb.toString()
    }

    private fun codePointToString(codePoint: Int): String? {
        if (codePoint <= 0 || codePoint > 0x10FFFF) return null
        return String(Character.toChars(codePoint))
    }
}
