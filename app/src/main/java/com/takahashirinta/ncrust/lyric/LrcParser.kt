/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import androidx.compose.runtime.Immutable

/**
 * 逐字歌词里的一个词（v1.5.0 · B）。
 *
 * [startMs]/[durationMs] 是「相对整首歌开头」的绝对毫秒（yrc 的原生语义）；
 * [charStart]/[charEndExclusive] 是该词在本行 [LrcLine.text] 里的**字符区间**，已经被
 * 对齐到 LRC 的文本上（yrc 的行文本会比 LRC 少一些空格，见 [YrcParser]），渲染时直接
 * 用它去 TextLayoutResult 取路径，不需要再猜。
 */
@Immutable
data class LrcWord(
    val startMs: Long,
    val durationMs: Long,
    val text: String,
    val charStart: Int,
    val charEndExclusive: Int
)

/**
 * 一行歌词。
 *
 * [words] 为空 = 这首歌没有逐字数据（或用户关掉了逐字开关），按普通 LRC 渲染；
 * 非空时每个词的起始时间由 yrc 提供，供逐字高亮使用。
 * [endMs] 是该行的结束时刻（yrc 的行 duration 换算而来）；null 表示未知。
 */
@Immutable
data class LrcLine(
    val timeMs: Long,
    val text: String,
    val words: List<LrcWord> = emptyList(),
    val endMs: Long? = null
)

object LrcParser {

    /** 时间戳本体（不含行首锚点）：`[01:06.45]` 或 `[00:33.260]`。 */
    private val TIME_STAMP = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\]""")

    /**
     * 行首**连续的若干个**时间戳 + 其后的正文。
     *
     * 用 `find`（不是 `matchEntire`）以保持旧实现的宽容度：只要行首是时间戳就算数，
     * `(.*)` 允许正文里再出现方括号（旧实现同样接受 `[00:01.00]文本[by:x]`）。
     *
     * 两个刻意的宽容点：
     * - 时间戳之间允许**水平空白**（`[01:06.45] [02:44.32] 文本`）—— 人工整理过的 LRC
     *   会这么写，而旧实现对此的产出是「正文里带一个方括号字面量」，同样属于本版要修的形态。
     * - 锚点 `^`：**正文**里的 `[` 不是时间戳语法，绝不能被剥掉，所以不做全局替换。
     */
    private val LEADING_TIME_STAMPS =
        Regex("""^((?:\[\d{2}:\d{2}\.\d{2,3}\][ \t]*)+)(.*)""")

    /**
     * 解析 LRC 正文。
     *
     * ## 一行多时间戳（v3.3.0 修复的 P0）
     *
     * LRC 允许同一句歌词在多个时间点重复出现（副歌），写法是**一行挂多个时间戳**：
     * ```
     * [01:37.57][03:15.56][03:50.79][04:06.51][04:47.01]和我在成都的街头走一走
     * ```
     * 旧实现用 `regex.find(line)` **只取行首第一个**时间戳，并把其后的 `[03:15.56]`
     * 原样留在歌词正文里 ⇒ 两个用户可见的症状：
     * ① 屏上出现 `[03:15.56]` 这样的方括号字面量；
     * ② 副歌在 03:15 / 03:50 / 04:06 / 04:47 **整段消失**（那几秒屏幕上没有任何歌词）。
     *
     * 真实样本（B 站音频区 au39《成都》，见
     * `docs/verification/v3.1.0/bili-research/evidence/21-lyric-au39.txt`）：22 行里
     * **13 行是多戳、共 44 个时间点**，旧实现只产出 22 条 ⇒ 丢 21 个时间点。
     *
     * 这不是 B 站独有的问题：ncm 与 QQ 的 LRC 走的是同一个函数
     * （`QqApi.kt` 的译文解析、`PlayerViewModel` 的译文/音译解析），所以**所有音源**都受影响。
     *
     * 修法：先收集行首的**全部**时间戳，逐个展开成 [LrcLine]，正文只取最后一个时间戳之后。
     *
     * ## 向后兼容（硬约束，不是礼貌）
     *
     * 单时间戳行的产出与旧实现**逐字节一致** —— 不能顺手 normalize 文本，
     * 因为 [YrcAligner] 是按「行序 + `line.text` 内容」把逐字数据挂到 LRC 行上的，
     * 文本一变就会整首放弃逐字（v1.5.0 为空格差异踩过一次）。多戳行展开出的多条
     * 文本彼此相同，也不影响那条对齐。
     *
     * ## 既有语义（保持不变）
     *
     * - 文本 trim 后为空 ⇒ **不产出**条目（`[00:10.00]` 与 `[00:10.00]   ` 都跳过）；
     * - 元信息行（`[ti:]`/`[ar:]`/`[by:]`/`[offset:]`）不含合法时间戳 ⇒ 不产出；
     * - 结果按时间升序：多戳行展开后时间天然不单调，必须重排。
     */
    fun parse(lrcText: String): List<LrcLine> {
        val out = mutableListOf<LrcLine>()

        for (raw in lrcText.lines()) {
            val lead = LEADING_TIME_STAMPS.find(raw.trim()) ?: continue
            val text = lead.groupValues[2].trim()
            // 整行只有时间戳、没有可显示内容 ⇒ 跳过（与旧实现一致）。
            if (text.isEmpty()) continue
            // 逐个时间戳展开：同一句歌词在每一个时间点都要出现。
            for (ts in TIME_STAMP.findAll(lead.groupValues[1])) {
                val min = ts.groupValues[1].toLong()
                val sec = ts.groupValues[2].toLong()
                var ms = ts.groupValues[3].toLong()
                // 2 位是厘秒（`[00:33.26]` = 33.260s），3 位是毫秒。
                if (ts.groupValues[3].length == 2) ms *= 10
                out.add(LrcLine(min * 60_000 + sec * 1_000 + ms, text))
            }
        }

        return out.sortedBy { it.timeMs }
    }
}