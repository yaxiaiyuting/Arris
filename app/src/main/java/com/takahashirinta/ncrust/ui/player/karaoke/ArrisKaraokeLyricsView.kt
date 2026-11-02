/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.ui.player.karaoke

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.ModernKaraokeLyricsView
import com.takahashirinta.ncrust.lyric.LrcLine
import kotlinx.coroutines.flow.StateFlow

/**
 * 卡拉 OK 歌词渲染（4.0.0）—— NeriPlayer 的渲染器 + 我们的数据与配色。
 *
 * 为什么包一层而不是直接在播放页用它：
 *  · 它要 `currentPosition: () -> Int` 这种**同步读**接口，我们手里是 StateFlow；
 *  · 它的字号没有"倍率"概念，而我们的设置项是倍数（0.7~1.5）；
 *  · **锚点不同**：上游把当前行摆在列表 padding 处（默认 32dp），我们的面板是"视口 36% 处"
 *    —— 这里按 `BoxWithConstraints` 实测高度换算成同一个比例，切换引擎时位置不跳；
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

    BoxWithConstraints(modifier) {
        // 与自研面板同一个锚点：当前行落在视口 36% 高处（LyricsPanelScroll.LEAD_FRACTION）。
        val leadOffset = maxHeight * 0.36f
        ModernKaraokeLyricsView(
            listState = listState,
            lyrics = synced,
            currentPosition = { position },
            onLineClicked = { line -> line.start.toLong().takeIf { it > 0 }?.let(onSeekToMs) },
            onLinePressed = {},
            modifier = Modifier.fillMaxSize(),
            normalLineTextStyle = TextStyle(
                fontSize = baseSize,
                fontWeight = FontWeight.SemiBold,
                lineHeight = baseSize * 1.35f,
                textMotion = TextMotion.Animated,
            ),
            accompanimentLineTextStyle = TextStyle(
                fontSize = baseSize * 0.72f,
                fontWeight = FontWeight.Normal,
                lineHeight = baseSize * 1.1f,
                textMotion = TextMotion.Animated,
            ),
            textColor = textColor,
            showTranslation = showTranslation,
            showPhonetic = showRomanization,
            // 行与行之间的"滑过去"——上游默认关，由外层打开
            animateViewportScroll = true,
            useBlurEffect = true,
            // 我们的背景是深色渐变，Plus 混合会把文字洗白
            useAdditiveBlend = false,
            offset = leadOffset,
        )
    }
}
