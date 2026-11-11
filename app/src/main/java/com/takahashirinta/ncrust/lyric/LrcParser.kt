/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import androidx.compose.runtime.Immutable
import java.util.regex.Pattern

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

    private val TIME_STAMP = Pattern.compile("""\[(\d{2,}):(\d{2})\.(\d{2,3})\]""")
    private val LEADING_TIME_STAMPS =
        Pattern.compile("""^((?:\[\d{2,}:\d{2}\.\d{2,3}\][ \t]*)+)(.*)""")

    /**
     * 展开行首时间戳，保留正文中的方括号。分钟可以超过 99。
     * 多时间戳的行按时间归并，相同时间继续按原文件顺序排列。
     */
    fun parse(lrcText: String): List<LrcLine> {
        val out = ArrayList<LrcLine>()
        val lineMatcher = LEADING_TIME_STAMPS.matcher("")
        val stampMatcher = TIME_STAMP.matcher("")
        var inOrder = true
        var lastTime = Long.MIN_VALUE

        for (raw in lrcText.lineSequence()) {
            val line = raw.trim()
            if (!lineMatcher.reset(line).find()) continue
            val text = lineMatcher.group(2).orEmpty().trim()
            if (text.isEmpty()) continue
            stampMatcher.reset(line).region(lineMatcher.start(1), lineMatcher.end(1))
            while (stampMatcher.find()) {
                val minutes = stampMatcher.group(1)!!.toLong()
                val seconds = stampMatcher.group(2)!!.toLong()
                val fraction = stampMatcher.group(3)!!
                val millis = fraction.toLong() * if (fraction.length == 2) 10 else 1
                val timeMs = minutes * 60_000 + seconds * 1_000 + millis
                if (timeMs < lastTime) inOrder = false
                lastTime = timeMs
                out.add(LrcLine(timeMs, text))
            }
        }

        if (!inOrder) out.sortBy { it.timeMs }
        return out
    }
}
