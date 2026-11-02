/*
 * NeriPlayer 歌词模块（Apache-2.0）—— 移植自 https://github.com/cwuom/accompanist-lyrics-core
 * 与原项目 https://github.com/cwuom/accompanist-lyrics-ui（Apache License 2.0）。
 * 本文件在 Arris（GPLv3）中的修改：KMP → 单平台 Android、去除 material3 依赖。
 * 原版权：Copyright (c) NeriPlayer contributors，Apache License 2.0。
 */

package com.mocharealm.accompanist.lyrics.core.parser

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics

interface ILyricsParser {
    /**
     * Determine if the parser can parse the lyrics.
     */
    fun canParse(content: String): Boolean

    /**
     * Parses a list of strings into SyncedLyrics.
     * Has a default implementation that joins the list and calls the String version of parse.
     *
     * @param lines The lines to parse.
     * @return The parsed SyncedLyrics.
     */
    fun parse(lines: List<String>): SyncedLyrics {
        return parse(lines.joinToString("\n"))
    }

    /**
     * Parses a single string into SyncedLyrics.
     * Has a default implementation that splits the string by newlines and calls the List version of parse.
     *
     * @param content The string content to parse.
     * @return The parsed SyncedLyrics.
     */
    fun parse(content: String): SyncedLyrics {
        // Default implementation: split String by newline into List<String>, then call the other parse method.
        return parse(content.split('\n'))
    }
}