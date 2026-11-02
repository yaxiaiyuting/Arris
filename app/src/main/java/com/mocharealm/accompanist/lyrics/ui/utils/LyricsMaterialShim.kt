/*
 * NeriPlayer 歌词模块（Apache-2.0）的 Arris 适配层。
 *
 * 上游这两个 View 依赖 material3 的 `LocalTextStyle` 与 `Text`。Arris 的界面完全不用
 * material3（自研 Kanesumi 组件），所以这里用 Compose Foundation 的等价物补上：
 *  - `LocalTextStyle` → 本文件自带的 composition local（默认 TextStyle.Default）
 *  - `Text`           → `BasicText`（foundation 版，样式参数一致）
 *
 * 这样上游的歌词渲染代码可以保持原样，只是 import 换成这里。
 */

package com.mocharealm.accompanist.lyrics.ui.utils

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/** 取 material3 `LocalTextStyle` 的位置：由调用方（播放页）注入我们的排版样式。 */
val LocalTextStyle = compositionLocalOf { TextStyle.Default }

/** foundation 版 `Text`：只覆盖上游用到的那几个参数。 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.merge(
            TextStyle(color = color, textAlign = textAlign ?: TextAlign.Unspecified),
        ),
        overflow = overflow,
        maxLines = maxLines,
    )
}

/**
 * `graphicsLayer { blendMode = … }` 的等价物（Compose 1.8 的 GraphicsLayerScope 还没有该属性）。
 *
 * 做法与 Arris 自己的歌词渲染一致：`saveLayer` + 画笔 blendMode + 还原。
 * 只在非 `SrcOver` 时开图层，避免给每一行都多一次离屏合成。
 */
fun Modifier.lyricsBlendMode(mode: BlendMode): Modifier =
    if (mode == BlendMode.SrcOver) this
    else drawWithContent {
        val bounds = androidx.compose.ui.geometry.Rect(
            androidx.compose.ui.geometry.Offset.Zero,
            androidx.compose.ui.geometry.Size(size.width, size.height),
        )
        val paint = androidx.compose.ui.graphics.Paint().apply { blendMode = mode }
        drawContext.canvas.saveLayer(bounds, paint)
        drawContent()
        drawContext.canvas.restore()
    }
