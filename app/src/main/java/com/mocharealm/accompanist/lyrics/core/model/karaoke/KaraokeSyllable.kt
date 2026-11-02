/*
 * NeriPlayer 歌词模块（Apache-2.0）—— 移植自 https://github.com/cwuom/accompanist-lyrics-core
 * 与原项目 https://github.com/cwuom/accompanist-lyrics-ui（Apache License 2.0）。
 * 本文件在 Arris（GPLv3）中的修改：KMP → 单平台 Android、去除 material3 依赖。
 * 原版权：Copyright (c) NeriPlayer contributors，Apache License 2.0。
 */

package com.mocharealm.accompanist.lyrics.core.model.karaoke


data class KaraokeSyllable(
    val content: String,
    val start: Int,
    val end: Int,
    val phonetic: String? = null,
) {
    val duration = end - start

    init {
        require(end >= start)
    }

    fun progress(current: Int): Float {
        return when {
            current < start -> 0f
            current in start..end -> (current - start).toFloat() / duration
            current > end -> 1f
            else -> 0f
        }.coerceIn(0f, 1f)
    }
}
