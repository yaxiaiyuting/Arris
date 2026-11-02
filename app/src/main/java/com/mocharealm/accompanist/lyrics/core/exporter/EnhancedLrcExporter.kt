/*
 * NeriPlayer 歌词模块（Apache-2.0）—— 移植自 https://github.com/cwuom/accompanist-lyrics-core
 * 与原项目 https://github.com/cwuom/accompanist-lyrics-ui（Apache License 2.0）。
 * 本文件在 Arris（GPLv3）中的修改：KMP → 单平台 Android、去除 material3 依赖。
 * 原版权：Copyright (c) NeriPlayer contributors，Apache License 2.0。
 */

package com.mocharealm.accompanist.lyrics.core.exporter

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.utils.toTimeFormattedString

/**
 * Exporter for the Enhanced LRC format.
 *
 * It converts [SyncedLyrics] back into a string representation in the Enhanced LRC format.
 * - Supports ID3 tags (`[ti:...]`, `[ar:...]`).
 * - Supports standard line timestamps `[mm:ss.xx]`.
 * - Supports syllable timing `<mm:ss.xx>`.
 * - Supports background lines `[bg:...]`.
 * - Supports translations as separate lines with the same timestamp.
 */
object EnhancedLrcExporter : ILyricsExporter {
    /**
     * Exports the given [SyncedLyrics] to a LRC formatted string.
     *
     * @param lyrics The [SyncedLyrics] object to export.
     * @return A string containing the LRC formatted lyrics.
     */
    override fun export(lyrics: SyncedLyrics): String {
        if (lyrics.lines.isEmpty()) return ""

        val builder = StringBuilder()

        if (lyrics.title.isNotBlank()) {
            builder.appendLine("[ti:${lyrics.title}]")
        }
        if (!lyrics.artists.isNullOrEmpty() && lyrics.artists.all { it.name.isNotBlank() }) {
            builder.appendLine(
                "[ar:${lyrics.artists.joinToString("/") { it.name }}]"
            )
        }

        lyrics.lines.forEach { line ->
            val timeTag = "[${line.start.toTimeFormattedString()}]"

            when (line) {
                is SyncedLine -> {
                    builder.appendLine("$timeTag${line.content}")
                    line.translation?.let { builder.appendLine("$timeTag$it") }
                }

                is KaraokeLine -> {
                    // Export Main Line with syllable timing
                    val syllablesStr = line.syllables.joinToString("") { s ->
                        "<${s.start.toTimeFormattedString()}>${s.content}"
                    } + "<${line.end.toTimeFormattedString()}>"
                    
                    builder.appendLine("$timeTag$syllablesStr")
                    line.translation?.let { builder.appendLine("$timeTag$it") }
                    
                    if (line is KaraokeLine.MainKaraokeLine) {
                        line.accompanimentLines?.forEach { bgLine ->
                            val bgSyllablesStr = bgLine.syllables.joinToString("") { s ->
                                "<${s.start.toTimeFormattedString()}>${s.content}"
                            } + "<${bgLine.end.toTimeFormattedString()}>"
                            
                            builder.appendLine("[bg:$bgSyllablesStr]")
                            
                            bgLine.translation?.let { trans ->
                                builder.appendLine("[bg:<${bgLine.start.toTimeFormattedString()}>$trans<${bgLine.end.toTimeFormattedString()}>]")
                            }
                        }
                    }
                }
            }
        }

        return builder.toString()
    }
}
