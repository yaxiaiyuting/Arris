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
 * 一条副文本轨（译文 / 音译）**最终由哪些源拼成**（v1.9.2）。
 *
 * [TTML] / [NETEASE] 表示整条轨只有这一个源；[MIXED] = TTML 的轨只覆盖了一部分行，
 * 缺口由 ncm 那一份补上（两源文本在同一条轨里共存）。它只是**观测字段**：
 * 写进歌词缓存供归因，不参与任何决策 —— 显示哪一份永远由 [LyricTrackMerge] 当场算。
 */
enum class LyricTrackSource { TTML, NETEASE, MIXED }

/** 写进 [LyricsCache] 的稳定标记（枚举名）。缓存侧只当观测字段读，不反解。 */
val LyricTrackSource.cacheTag: String get() = name

/**
 * 合并后的一条轨道：内容 + 来源；[lines] 为空时 [source] 必为 null。
 *
 * [lines] 里的每一行都是可以直接交给渲染层的 [LrcLine]：**时间戳已经落在主轨的时间轴上**
 * （见 [LyricTrackMerge]），所以 LyricsView 那句 `translatedLyrics.associateBy { it.timeMs }`
 * 不需要任何改动就能把译文挂到正确的原句下面。
 */
@Immutable
data class LyricTrack(
    val lines: List<LrcLine> = emptyList(),
    val source: LyricTrackSource? = null,
)

internal data class LyricTracks(val translation: LyricTrack, val roman: LyricTrack)

/** 副文本优先用 TTML 同刻行，缺口按两份主轨的行序匹配网易云副文本。 */
object LyricTrackMerge {
    internal fun mergeTracks(
        doc: TtmlDoc,
        neteaseMain: List<LrcLine>,
        translations: List<LrcLine>,
        romans: List<LrcLine>,
    ): LyricTracks {
        val alignment = Alignment(doc.lines, neteaseMain)
        return LyricTracks(
            translation = alignment.merge(doc.translations, translations),
            roman = alignment.merge(doc.romans, romans),
        )
    }

    fun merge(
        main: List<LrcLine>,
        ttmlTrack: List<LrcLine>,
        neteaseMain: List<LrcLine>,
        neteaseTrack: List<LrcLine>,
    ): LyricTrack = Alignment(main, neteaseMain).merge(ttmlTrack, neteaseTrack)

    private class Alignment(val main: List<LrcLine>, val neteaseMain: List<LrcLine>) {
        private val mainTimes by lazy(LazyThreadSafetyMode.NONE) {
            main.mapTo(HashSet(main.size * 2)) { it.timeMs }
        }
        // 两条副轨共用匹配。完整的 TTML 轨无需计算 LCS。
        private val pairs by lazy(LazyThreadSafetyMode.NONE) {
            YrcAligner.lcsPairs(main.map { it.text }, neteaseMain.map { it.text })
        }

        fun merge(ttmlTrack: List<LrcLine>, neteaseTrack: List<LrcLine>): LyricTrack {
            if (main.isEmpty() || (ttmlTrack.isEmpty() && neteaseTrack.isEmpty())) return LyricTrack()

            val result = ArrayList<LrcLine>()
            val covered = HashSet<Long>()
            for (line in ttmlTrack) {
                if (line.timeMs in mainTimes) {
                    result.add(line)
                    covered.add(line.timeMs)
                }
            }
            val ttmlCount = result.size
            if (covered.size >= mainTimes.size || neteaseTrack.isEmpty() || neteaseMain.isEmpty()) {
                return if (result.isEmpty()) LyricTrack() else LyricTrack(result, LyricTrackSource.TTML)
            }

            val neteaseTextByTime = HashMap<Long, String>(neteaseTrack.size * 2)
            for (line in neteaseTrack) {
                if (line.text.isNotEmpty()) neteaseTextByTime[line.timeMs] = line.text
            }
            for ((mainIndex, neteaseIndex) in pairs) {
                val mainLine = main.getOrNull(mainIndex) ?: continue
                // 同刻重复主行以首个配对为准，缺副文本的配对也占用该时间戳。
                if (!covered.add(mainLine.timeMs)) continue
                val neteaseLine = neteaseMain.getOrNull(neteaseIndex) ?: continue
                val text = neteaseTextByTime[neteaseLine.timeMs] ?: continue
                if (text.isNotBlank()) result.add(LrcLine(timeMs = mainLine.timeMs, text = text))
            }

            if (result.isEmpty()) return LyricTrack()
            if (ttmlCount > 0) result.sortWith(byTime)
            val source = when {
                result.size == ttmlCount -> LyricTrackSource.TTML
                ttmlCount == 0 -> LyricTrackSource.NETEASE
                else -> LyricTrackSource.MIXED
            }
            return LyricTrack(result, source)
        }
    }

    private val byTime = Comparator<LrcLine> { first, second -> first.timeMs.compareTo(second.timeMs) }
}
