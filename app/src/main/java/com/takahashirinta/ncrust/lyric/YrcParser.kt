/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import android.util.Log
import kotlin.math.abs

/**
 * 逐字歌词（网易 `yrc` 字段）解析与对齐（v1.5.0 · B，对齐在 v1.6.0 · D4 重做）。
 *
 * ## 格式（2026-09 实测，非文档推断）
 *
 * 每行 = `[行起始ms,行时长ms]` 后跟若干 `(词起始ms,词时长ms,0)词文本`：
 *
 * ```
 * [24080,5080](24080,280,0)半(24360,210,0)夜(24570,560,0)睡(25130,300,0)不(25430,350,0)着
 * [18500,5840](18500,400,0)和(18900,240,0)你(19140,380,0)有(19520,2760,0)关
 * [0,1000](0,1000,0) 作曲 : 周杰伦
 * [0,0](0,0,0) 作词 : 五月天 阿信
 * ```
 *
 * 三条从真实响应核实出来的规则：
 *  1. **词起始是绝对毫秒**（相对整首歌开头），不是相对行首 —— `[18500,5840]` 的第一个词
 *     就是 `(18500,...)`，最后一个词 `23340+1000 = 24340 = 18500+5840`，首尾都对得上；
 *  2. 括号里第三个数字恒为 0，无语义，忽略；
 *  3. 词文本从 `)` 之后一直取到下一个 `(` 或行尾，**可以含空格与标点**（`曲 `、``,
 *     ` 作曲 : 周杰伦` 都是真实样本）。
 *
 * ## 对齐（这一节是踩坑记录，别改成按时间戳匹配）
 *
 * 直觉做法是「按时间戳把 yrc 行挂到 lrc 行上」，**实测不成立**：同一首歌《屋顶》55 行里
 * 只有 3 行时间戳完全相同，其余相差 20–310 ms（yrc 是逐字轨，本来就更精确）。
 *
 * 但实测同时确认了另一件更强的事实 —— **yrc 与 lrc 的「行」在文本上是对应的**：
 *
 * | 歌曲 | lrc 行数 | yrc 行数 | 按文本命中 | 时间差 |
 * |---|---|---|---|---|
 * | 屋顶 `5257138` | 55 | 55 | 55/55 | +20…+310 ms |
 * | 遇见 `287035` | 28 | 28 | 11/28（其余只差空格） | −269…+530 ms |
 *
 * ## v1.6.0 · D4：对齐不再是「行数必须相等」
 *
 * v1.5.x 按**行序**对齐，前提是「行数相等」；一旦 yrc 少一行（间奏没有逐字轨）或多几行
 * （重复段），整首放弃逐字。2026-09 用真实收藏库 100 首实测：**43 首有 yrc，但只有 37 首
 * 的逐字能真正挂上去**（行级挂载率 77.9%）。覆盖率真正的瓶颈在这里，不在接口
 * （同一批歌换 eapi/iPhone/Android 身份、换 `/api/song/lyric/v1` 都不多给一个 yrc 字段）。
 *
 * 现在改为**两条路各算一遍、取挂得多的那条**（保证对 v1.5.x 只增不减）：
 *
 *  1. `index`：行数相等 + 时间漂移抽样达标时，按行序 i↔i 挂（v1.5.x 的原行为，逐字节保留）；
 *  2. `lcs`：按**归一化文本**做最长公共子序列匹配（[YrcAligner]），再按时间漂移逐对过滤。
 *
 * 实测同一份样本：可用歌数 37 → **42**，行级挂载率 77.9% → **90.0%**，
 * 收益全部来自「yrc 少一行/多几行」的那些歌（如《不潮不用花钱》0→56 行、《曹操》0→41 行）。
 * 时间戳仍然**只**用于事后漂移过滤，不参与匹配。
 *
 * 文本也要对齐：yrc 拼出来的行是「词的原始拼接」，LRC 那份是「补回空格的美化版」
 * （`听见冬天的离开` vs `听见 冬天的离开`）。展示文本仍用 LRC 的（与 v1.4.1 完全一致），
 * 词的字符区间则用「游标 + indexOf」映射到 LRC 文本上；任何一个词定位失败就放弃该行的
 * 逐字（同样是不给错位高亮）。
 */
object YrcParser {
    private const val TAG = "YrcParser"

    private val LINE = Regex("""^\[(\d+),(\d+)](.*)$""")
    // 注意不能带 ^：词不是行首，而 matchAt(body, i) 的语义是「从 i 处开始匹配」，
    // 加上 ^ 会让第 2 个词开始全部匹配失败（只解析出第一个词的经典坑）。
    private val WORD = Regex("""\((\d+),(\d+),(\d+)\)""")

    /** 行对齐时允许的最大时间偏差；超过就不认这一对，或（index 路径下）整首放弃逐字。 */
    private const val MAX_LINE_DRIFT_MS = 2_000L
    /** index 路径的一致性门槛：达标才敢按顺序挂（v1.5.x 原判据）。 */
    private const val MIN_ALIGN_RATIO = 0.8

    /** yrc 里的一行：起始、时长、原始拼接文本、逐词时间轴。 */
    internal data class YrcLine(
        val startMs: Long,
        val durationMs: Long,
        val text: String,
        val words: List<YrcWord>
    )

    /** yrc 里的一个词：时间轴 + 在**本 yrc 行文本**里的字符区间。 */
    internal data class YrcWord(
        val startMs: Long,
        val durationMs: Long,
        val text: String,
        val charStart: Int,
        val charEndExclusive: Int
    )

    /** 把 yrc 原文解析成行列表（保持原始顺序；一行都解析不出时返回空表）。 */
    internal fun parse(yrcText: String): List<YrcLine> {
        val out = ArrayList<YrcLine>()
        for (raw in yrcText.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val m = LINE.find(line) ?: continue
            val startMs = m.groupValues[1].toLongOrNull() ?: continue
            val durationMs = m.groupValues[2].toLongOrNull() ?: 0L
            val body = m.groupValues[3]
            val words = ArrayList<YrcWord>()
            val sb = StringBuilder()
            var i = 0
            while (i < body.length) {
                if (body[i] != '(') { i++; continue }
                // matchAt 而不是 find(substring(i))：后者每轮都复制一遍剩余字符串，
                // 长行（有些 yrc 行几十个词）会退化成 O(n^2)。
                val wm = WORD.matchAt(body, i)
                if (wm == null) { i++; continue }
                val ws = wm.groupValues[1].toLongOrNull() ?: 0L
                val wd = wm.groupValues[2].toLongOrNull() ?: 0L
                i += wm.value.length
                val charStart = sb.length
                while (i < body.length && body[i] != '(') { sb.append(body[i]); i++ }
                words += YrcWord(ws, wd, sb.substring(charStart), charStart, sb.length)
            }
            val text = sb.toString().trim()
            if (text.isEmpty()) continue
            out += YrcLine(startMs, durationMs, text, words)
        }
        return out
    }

    /**
     * 把 yrc 的逐词时间轴挂到 [lines]（LRC 行列表）上。**不会增删任何一行**，
     * 也不会改动任何一行的文本与时间戳 —— 逐字开关关掉后渲染结果与 v1.4.1 逐字节一致。
     *
     * 挂不上（对齐不出配对 / 时间漂移过大 / 某个词定位不到）的行原样返回，
     * 渲染自动退化成普通 LRC 行。
     */
    fun attachWords(lines: List<LrcLine>, yrcText: String): List<LrcLine> {
        if (lines.isEmpty() || yrcText.isEmpty()) return lines
        val yrcLines = parse(yrcText)
        if (yrcLines.isEmpty()) return lines

        val candidates = ArrayList<Pair<String, List<YrcAligner.LinePair>>>(2)
        // 候选 1（index）：v1.5.x 的原行为 —— 行数相等 + 时间漂移抽样达标才按行序挂。
        if (yrcLines.size == lines.size) {
            val close = lines.indices.count { i ->
                abs(yrcLines[i].startMs - lines[i].timeMs) <= MAX_LINE_DRIFT_MS
            }
            if (close >= (lines.size * MIN_ALIGN_RATIO).toInt()) {
                candidates += "index" to YrcAligner.indexPairs(lines.size)
            }
        }
        // 候选 2（lcs）：按归一化文本做 LCS，再逐对过滤时间漂移。
        val lcs = YrcAligner.filterByDrift(
            YrcAligner.lcsPairs(lines.map { it.text }, yrcLines.map { it.text }),
            lines.map { it.timeMs },
            yrcLines.map { it.startMs },
            MAX_LINE_DRIFT_MS
        )
        if (lcs.isNotEmpty()) candidates += "lcs" to lcs

        if (candidates.isEmpty()) {
            Log.i(TAG, "attachWords: no alignable pairs lrc=${lines.size} yrc=${yrcLines.size}, skip")
            return lines
        }

        // 两条路都算出来，取「实际挂上逐字的行数」多的那条 —— 这保证对 v1.5.x 的结果**只增不减**：
        // 行数相等且能逐行定位时两条路等价，LCS 更差时自动回落到旧路径（实测《You Never Can Tell》
        // index=22 行 / lcs=17 行，就是靠这条规则保住不回归的）。
        var best: List<LrcLine> = lines
        var bestCount = -1
        var bestKind = ""
        for ((kind, pairs) in candidates) {
            val (merged, count) = applyPairs(lines, yrcLines, pairs)
            if (count > bestCount) {
                best = merged
                bestCount = count
                bestKind = kind
            }
        }
        Log.i(TAG, "attachWords: $bestCount/${lines.size} lines got word timing (yrc=${yrcLines.size}, $bestKind)")
        return if (bestCount <= 0) lines else best
    }

    /** 按 [pairs] 把逐词时间轴挂上去，返回 (新行表, 真正挂上的行数)。 */
    private fun applyPairs(
        lines: List<LrcLine>,
        yrcLines: List<YrcLine>,
        pairs: List<YrcAligner.LinePair>
    ): Pair<List<LrcLine>, Int> {
        if (pairs.isEmpty()) return lines to 0
        val byLrcIndex = HashMap<Int, YrcLine>(pairs.size)
        for (p in pairs) {
            val y = yrcLines.getOrNull(p.yrcIndex) ?: continue
            if (p.lrcIndex in lines.indices) byLrcIndex[p.lrcIndex] = y
        }
        var attached = 0
        val merged = lines.mapIndexed { i, line ->
            val y = byLrcIndex[i] ?: return@mapIndexed line
            val ranges = mapRanges(line.text, y.words) ?: return@mapIndexed line
            if (ranges.isEmpty()) return@mapIndexed line
            attached++
            line.copy(
                words = ranges,
                endMs = (y.startMs + y.durationMs).takeIf { y.durationMs > 0L },
            )
        }
        return merged to attached
    }

    /**
     * 把 yrc 的词映射到 LRC 的 [text] 上，返回带字符区间的词表。
     * 用「游标 + indexOf」而不是逐字符比对：两份文本的差异基本上只是 LRC 补回了空格，
     * indexOf 天然跳过这些插入的空格，且词序单调递增不会回头匹配。
     * 任何一个词定位不到就返回 null（该行放弃逐字，绝不给错位高亮）。
     */
    private fun mapRanges(text: String, words: List<YrcWord>): List<LrcWord>? {
        if (words.isEmpty()) return null
        val out = ArrayList<LrcWord>(words.size)
        var cursor = 0
        for (w in words) {
            // S6 真机踩到的坑：yrc 把空格**粘在前一段尾部**（`(0,1000,0) 作词 `、
            // `(1000,1000,0): `），而 LRC 那份是 trim 过的（`作词 : 易家扬`）。
            // 直接拿 `w.text` 去 indexOf 会带上首尾空格 → 找不到 → 整行放弃逐字。
            // 《修炼爱情》实测因此只挂上 47/70 行；改用 trim 后的词去定位即可。
            // 纯空白段（trim 后为空）直接跳过：它没有自己的字符区间，高亮由前一段覆盖。
            val t = w.text.trim()
            if (t.isEmpty()) continue
            val idx = text.indexOf(t, cursor)
            if (idx < 0) return null
            out += LrcWord(
                startMs = w.startMs,
                durationMs = w.durationMs,
                text = t,
                charStart = idx,
                charEndExclusive = idx + t.length,
            )
            cursor = idx + t.length
        }
        return out.ifEmpty { null }
    }
}
