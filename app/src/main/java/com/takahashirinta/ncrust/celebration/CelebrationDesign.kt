/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 *
 * 庆祝视觉设计系统：
 *   - 规范全套生日/彩蛋界面的高级森系夜色与香槟流光调色板
 *   - 提供符合 Arris 设计规范的药丸形交互控件 (CelebrationPillButton)
 *   - 确保完全遵守 AppShapes 统一圆角规范 (AppShapes.full / AppShapes.extraLarge)
 */

package com.takahashirinta.ncrust.celebration

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 庆祝界面高级调色板：深夜森林与温暖香槟流光。
 */
internal object CelebrationPalette {
    // 沉浸深邃背景色阶
    val bgDeep = Color(0xFF07130D)
    val bgForest = Color(0xFF0C1E15)
    val bgAurora = Color(0xFF132B1F)

    // 背景全屏柔光渐变
    val backgroundBrush = Brush.verticalGradient(
        listOf(bgAurora, bgForest, bgDeep)
    )

    // 磨砂玻璃卡片层级
    val glassCardBg = Color(0x3310261A)
    val glassCardBgElevated = Color(0x52142E20)
    val glassCardBorder = Color(0x40E6C696)
    val glassCardBorderSubtle = Color(0x22FFFFFF)

    // 文字色彩梯度
    val textTitle = Color(0xFFFFF7EA)         // 尊贵暖白标题
    val textBody = Color(0xFFB4C8B8)          // 雾绿副文本
    val textWarmHighlight = Color(0xFFFFE8C2) // 核心愿望高亮金
    val textFeedback = Color(0xFFFFD59E)      // 交互反馈橙金
    val textMuted = Color(0xFF7E9484)         // 弱化说明文字

    // 香槟主按钮
    val btnPrimaryStart = Color(0xFFFFE9C8)
    val btnPrimaryEnd = Color(0xFFF3C486)
    val btnPrimaryText = Color(0xFF152A1E)

    // 烛光状态按钮
    val btnCandleBgStart = Color(0xFF261D12)
    val btnCandleBgEnd = Color(0xFF38291A)
    val btnCandleText = Color(0xFFFFE3B8)
    val btnCandleBorder = Color(0x60E8BE78)

    // 次要透明药丸按钮
    val btnSecondaryText = Color(0xFFD4E2D6)
    val btnSecondaryBorder = Color(0x30FFFFFF)
    val btnSecondaryHover = Color(0x18FFFFFF)

    // 品牌标识
    val brandDim = 0.12f
    val brandBright = 0.65f
}

/**
 * 统一药丸胶囊按钮，符合 Arris 全局设计规范 (AppShapes.full)。
 */
@Composable
internal fun CelebrationPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    candle: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val pal = CelebrationPalette
    val shape = AppShapes.full

    val bgBrush = when {
        primary && candle -> Brush.horizontalGradient(listOf(pal.btnCandleBgStart, pal.btnCandleBgEnd))
        primary -> Brush.horizontalGradient(listOf(pal.btnPrimaryStart, pal.btnPrimaryEnd))
        else -> Brush.horizontalGradient(listOf(pal.btnSecondaryHover, pal.btnSecondaryHover))
    }

    val borderModifier = when {
        primary && candle -> Modifier.border(1.dp, pal.btnCandleBorder, shape)
        !primary -> Modifier.border(1.dp, pal.btnSecondaryBorder, shape)
        else -> Modifier
    }

    val textColor = when {
        primary && candle -> pal.btnCandleText
        primary -> pal.btnPrimaryText
        else -> pal.btnSecondaryText
    }

    val contentAlpha = if (enabled) 1f else 0.45f

    Box(
        modifier = modifier
            .heightIn(min = 50.dp)
            .then(borderModifier)
            .clip(shape)
            .background(bgBrush, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp)
            .graphicsLayer { alpha = contentAlpha },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                MetroIcon(
                    leadingIcon,
                    contentDescription = null,
                    tint = textColor,
                    sizeDp = 18.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            MetroText(
                text = text,
                color = textColor,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.3.sp,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}

/**
 * 顶部精美圆形磨砂关闭按钮。
 */
@Composable
internal fun CelebrationCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(AppShapes.full)
            .background(Color(0x28FFFFFF), AppShapes.full)
            .border(1.dp, Color(0x35FFFFFF), AppShapes.full)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        MetroIcon(
            Icons.Default.Close,
            contentDescription = "Close",
            tint = CelebrationPalette.textTitle.copy(alpha = 0.85f),
            sizeDp = 18.dp
        )
    }
}

/**
 * 麦克风录音声波脉冲微光呼吸圈。
 */
@Composable
internal fun AcousticBreathingRing(
    armed: Boolean,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val ringColor = if (armed) Color(0xFFFFD59E) else Color(0xFF8DC09E)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // 外层脉冲呼吸光环
        Box(
            Modifier
                .size(52.dp)
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                    alpha = pulseAlpha
                }
                .clip(AppShapes.full)
                .background(ringColor.copy(alpha = 0.15f), AppShapes.full)
                .border(1.dp, ringColor.copy(alpha = 0.4f), AppShapes.full)
        )
        // 核心原点
        Box(
            Modifier
                .size(14.dp)
                .clip(AppShapes.full)
                .background(ringColor, AppShapes.full)
        )
    }
}
