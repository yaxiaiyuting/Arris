/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 自建歌单的封面占位。
 *
 * 为什么需要：新建的云歌单服务端**不给封面**（`coverUrl` 为空），而详情页与列表格原先
 * 直接 `AsyncImage(CoverUrls.large(""))` —— 结果是一块纯灰底板，看起来像「封面没加载出来」。
 * 这里用歌单名派生一块稳定底色 + 首字，空歌单也有东西可认。
 *
 * 取色规则刻意简单：色相来自名字哈希，饱和度固定，明度按当前背景亮度取一档。同一歌单
 * 每次进页面颜色一致（不随重组或主题切换跳变），首字用 onSurfaceVariant 保证可读。
 */
@Composable
fun PlaylistCoverPlaceholder(
    name: String,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    letterSize: TextUnit = 28.sp,
) {
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    val seed = name.trim().ifEmpty { "♪" }
    val hue = ((seed.hashCode() % 360) + 360) % 360
    // 深色主题下底色压暗、浅色主题下提亮：只差明度，色相不变。
    val dark = colors.background.luminance() < 0.4f
    val tint = Color.hsv(hue.toFloat(), 0.30f, if (dark) 0.34f else 0.90f)
    Box(
        modifier = modifier.background(Brush.linearGradient(listOf(tint, colors.surfaceVariant))),
        contentAlignment = Alignment.Center,
    ) {
        MetroText(
            text = seed.take(1),
            color = colors.onSurfaceVariant,
            style = typography.titleLarge.copy(fontSize = letterSize),
        )
    }
}
