/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.ui.player.karaoke

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.takahashirinta.ncrust.lyric.LrcLine

/**
 * Arris 歌词模型 ⇄ NeriPlayer 卡拉 OK 模型（4.0.0）。
 *
 * 我们这边一行是 [LrcLine]（行时间 + 逐词时间 + 译文/音译**分轨**给出）；
 * 移植过来的渲染层要的是 [SyncedLyrics]（行内音节 + 译文/音译**挂在行上**）。
 * 这个文件是两者之间唯一的转换点 —— 只做数据搬运，不做任何排版决策。
 */
internal object KaraokeBridge {

    /** 译文/音译按时间就近配对（两轨与主轨的行数经常不一致，不能按索引硬配）。 */
    fun build(
        lines: List<LrcLine>,
        translations: List<LrcLine>,
        romanizations: List<LrcLine>,
        totalDurationMs: Long,
    ): SyncedLyrics {
        if (lines.isEmpty()) return SyncedLyrics(emptyList())

        val translationByIndex = pairByTime(lines, translations)
        val romanizationByIndex = pairByTime(lines, romanizations)

        val converted = lines.mapIndexed { index, line ->
            val start = line.timeMs.coerceAtLeast(0L).toInt()
            // 行结束时刻：yrc 给了 endMs 就用它；否则用下一行的起点；最后一行用总时长兜底。
            val rawEnd = line.endMs
                ?: lines.getOrNull(index + 1)?.timeMs
                ?: (start + DEFAULT_LAST_LINE_MS)
            val end = rawEnd.coerceIn(start.toLong(), totalDurationMs.coerceAtLeast(start.toLong())).toInt()

            KaraokeLine.MainKaraokeLine(
                syllables = syllablesOf(line, start, end),
                translation = translationByIndex[index]?.text?.takeIf { it.isNotBlank() },
                alignment = KaraokeAlignment.Start,
                start = start,
                end = end,
                phonetic = romanizationByIndex[index]?.text?.takeIf { it.isNotBlank() },
            )
        }
        return SyncedLyrics(converted)
    }

    /**
     * 行内音节：有逐词数据就一五一十地搬（这正是"逐字歌词"的来源）；
     * 没有就退化成"整行一个音节"，渲染层照常按行高亮。
     */
    private fun syllablesOf(line: LrcLine, lineStart: Int, lineEnd: Int): List<KaraokeSyllable> {
        if (line.words.isEmpty()) {
            val text = line.text
            if (text.isEmpty()) return emptyList()
            return listOf(KaraokeSyllable(content = text, start = lineStart, end = lineEnd.coerceAtLeast(lineStart)))
        }
        return line.words.mapIndexed { index, word ->
            val start = word.startMs.coerceAtLeast(0L).toInt()
            val end = (word.startMs + word.durationMs).coerceAtLeast(word.startMs).toInt()
                .let { if (index == line.words.lastIndex && it <= start) lineEnd else it }
            // 4.0.0：**内容从原句按字符区间切**，不要用 word.text。
            // yrc 的英文逐词里，词与词之间的空格属于"前一个词的尾部"，我们自己的
            // LrcWord.text 把它裁掉了；直接拼 word.text 会得到 "Hestheguywhosthe..."。
            // charStart/charEndExclusive 是相对整行的，切出来天然带空格。
            val content = line.text.substring(
                word.charStart.coerceIn(0, line.text.length),
                word.charEndExclusive.coerceIn(0, line.text.length),
            ).ifEmpty { word.text }
            KaraokeSyllable(
                content = content,
                start = start,
                end = end.coerceAtLeast(start),
            )
        }
    }

    /** 把辅轨（译文 / 音译）按时间就近挂到主轨每一行上。 */
    private fun pairByTime(primary: List<LrcLine>, secondary: List<LrcLine>): Map<Int, LrcLine> {
        if (secondary.isEmpty()) return emptyMap()
        val result = HashMap<Int, LrcLine>(primary.size)
        var cursor = 0
        primary.forEachIndexed { index, line ->
            while (cursor + 1 < secondary.size &&
                kotlin.math.abs(secondary[cursor + 1].timeMs - line.timeMs) <
                kotlin.math.abs(secondary[cursor].timeMs - line.timeMs)
            ) {
                cursor++
            }
            val candidate = secondary[cursor]
            // 超过 1.5s 视为"对不上"，宁可空着也不要把别的句子贴上来。
            if (kotlin.math.abs(candidate.timeMs - line.timeMs) <= MAX_PAIR_GAP_MS) {
                result[index] = candidate
            }
        }
        return result
    }

    private const val DEFAULT_LAST_LINE_MS = 6_000L
    private const val MAX_PAIR_GAP_MS = 1_500L
}
