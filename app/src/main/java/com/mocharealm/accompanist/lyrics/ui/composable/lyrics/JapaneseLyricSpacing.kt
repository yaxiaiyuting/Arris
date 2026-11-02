/*
 * NeriPlayer 歌词模块（Apache-2.0）—— 移植自 https://github.com/cwuom/accompanist-lyrics-core
 * 与原项目 https://github.com/cwuom/accompanist-lyrics-ui（Apache License 2.0）。
 * 本文件在 Arris（GPLv3）中的修改：KMP → 单平台 Android、去除 material3 依赖。
 * 原版权：Copyright (c) NeriPlayer contributors，Apache License 2.0。
 */

package com.mocharealm.accompanist.lyrics.ui.composable.lyrics

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val JapaneseKanaRanges = listOf(
    '\u3040'..'\u30FF',
    '\u31F0'..'\u31FF',
    '\uFF66'..'\uFF9F'
)

internal fun containsJapaneseKana(text: String): Boolean {
    return text.any { char -> JapaneseKanaRanges.any { range -> char in range } }
}

internal fun resolveJapaneseLyricTranslationTopPadding(lyricText: String): Dp {
    return if (containsJapaneseKana(lyricText)) 3.dp else 0.dp
}
