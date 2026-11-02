/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.ui.player.karaoke

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.sp
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import com.takahashirinta.ncrust.lyric.LrcLine
import kotlinx.coroutines.flow.StateFlow

/**
 * 卡拉 OK 歌词渲染（4.0.0）—— NeriPlayer 的渲染器 + 我们的数据与配色。
 *
 * 为什么包一层而不是直接在播放页用它的 View：
 *  · 它要 `currentPosition: () -> Int` 这种**同步读**接口，我们手里是 StateFlow；
 *  · 它的字号/颜色没有"倍率"概念，而我们的设置项是倍数（0.7~1.5）；
 *  · 点行跳转要接我们的 `onSeekToMs`。
 */
@Composable
fun ArrisKaraokeLyricsView(
    lyrics: List<LrcLine>,
    translatedLyrics: List<LrcLine> = emptyList(),
    romanizedLyrics: List<LrcLine> = emptyList(),
    positionFlow: StateFlow<Long>,
    totalDurationMs: Long,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White,
    activeColor: Color = Color.White,
    fontScale: Float = 1f,
    showTranslation: Boolean = true,
    showRomanization: Boolean = true,
    onSeekToMs: (Long) -> Unit = {},
) {
    val synced = remember(lyrics, translatedLyrics, romanizedLyrics, totalDurationMs) {
        KaraokeBridge.build(lyrics, translatedLyrics, romanizedLyrics, totalDurationMs)
    }
    var position by remember { mutableIntStateOf(positionFlow.value.toInt()) }
    LaunchedEffect(positionFlow) {
        positionFlow.collect { position = it.toInt() }
    }
    val listState = rememberLazyListState()
    val baseSize = 22.sp * fontScale.coerceIn(0.7f, 1.5f)

    KaraokeLyricsView(
        listState = listState,
        lyrics = synced,
        currentPosition = { position },
        onLineClicked = { line -> line.start.toLong().takeIf { it > 0 }?.let(onSeekToMs) },
        onLinePressed = { _: ISyncedLine -> },
        modifier = modifier,
        normalLineTextStyle = TextStyle(
            fontSize = baseSize,
            fontWeight = FontWeight.SemiBold,
            textMotion = TextMotion.Animated,
        ),
        textColor = textColor,
    )
}
