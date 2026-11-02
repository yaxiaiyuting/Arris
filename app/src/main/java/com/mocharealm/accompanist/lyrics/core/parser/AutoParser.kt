/*
 * NeriPlayer 歌词模块（Apache-2.0）—— 移植自 https://github.com/cwuom/accompanist-lyrics-core
 * 与原项目 https://github.com/cwuom/accompanist-lyrics-ui（Apache License 2.0）。
 * 本文件在 Arris（GPLv3）中的修改：KMP → 单平台 Android、去除 material3 依赖。
 * 原版权：Copyright (c) NeriPlayer contributors，Apache License 2.0。
 */

package com.mocharealm.accompanist.lyrics.core.parser

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.utils.PhoneticProvider

/**
 * A smart parser that automatically detects the lyrics format and uses the appropriate parser.
 *
 * This class combines the functionality of all individual parsers (`EnhancedLrcParser`,
 * `TTMLParser`, and `LyricifySyllableParser`) into a single, easy-to-use interface. It uses
 * `LyricsFormatGuesser` to determine the most likely format and then delegates the parsing task.
 *
 * This parser is extensible. You can register custom formats and their corresponding parsers.
 */
class AutoParser(
    private val fallbackPhoneticProvider: PhoneticProvider? = null,
    private val parsers: List<ILyricsParser> = listOf(
        TTMLParser(fallbackPhoneticProvider = fallbackPhoneticProvider),
        NeteaseYrcParser,
        LyricifySyllableParser,
        EnhancedLrcParser,
        KugouKrcParser,
    )
) : ILyricsParser {

    override fun canParse(content: String): Boolean =
        parsers.any { it.canParse(content) }

    override fun parse(content: String): SyncedLyrics {
        val parser = parsers.firstOrNull { it.canParse(content) }
        return parser?.parse(content) ?: SyncedLyrics(emptyList())
    }
}
