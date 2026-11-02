/*
 * NeriPlayer 歌词模块（Apache-2.0）—— 移植自 https://github.com/cwuom/accompanist-lyrics-core
 * 与原项目 https://github.com/cwuom/accompanist-lyrics-ui（Apache License 2.0）。
 * 本文件在 Arris（GPLv3）中的修改：KMP → 单平台 Android、去除 material3 依赖。
 * 原版权：Copyright (c) NeriPlayer contributors，Apache License 2.0。
 */

package com.mocharealm.accompanist.lyrics.core.model.karaoke.mapper

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine


fun SyncedLine.toKaraokeLine(): KaraokeLine {
    return KaraokeLine.MainKaraokeLine(
        syllables = listOf(
            KaraokeSyllable(
                this.content,
                this.start,
                this.end
            )
        ),
        translation = this.translation,
        alignment = KaraokeAlignment.Unspecified,
        start = this.start,
        end = this.end
    )
}