/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 *
 * 修改说明：
 *   - v3.9.0：迟到生日祝福界面彻底重构。
 *     · 采用“深林夜色时光信笺”卡片设计，告别原本简陋的纯黑弹窗
 *     · 统一使用药丸胶囊交互组件 (CelebrationPillButton)
 *     · 优雅排版与精致的香槟微光边框
 */

package com.takahashirinta.ncrust.celebration

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay

@Composable
internal fun BelatedBirthdayOverlay(days: Int, onClose: () -> Unit, onVisible: () -> Unit) {
    var start by remember(days) { mutableStateOf(false) }
    val visible by rememberUpdatedState(onVisible)
    val close by rememberUpdatedState(onClose)

    if (start) {
        CelebrationOverlay(CelebrationKind.BIRTHDAY, onClose, onVisible)
        return
    }

    LaunchedEffect(days) { delay(4_200); start = true }

    val s = LocalStrings.current.celebration
    val pal = CelebrationPalette

    // An interrupted intro remains eligible; an explicit dismissal respects the user's choice.
    val dismiss = { visible(); close() }

    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(pal.backgroundBrush)
                .safeDrawingPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .clip(AppShapes.extraLarge)
                    .background(pal.glassCardBgElevated, AppShapes.extraLarge)
                    .border(1.dp, pal.glassCardBorder, AppShapes.extraLarge)
                    .padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 顶部温暖生日蛋糕图标
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(AppShapes.full)
                        .background(pal.glassCardBg, AppShapes.full)
                        .border(1.dp, pal.glassCardBorderSubtle, AppShapes.full),
                    contentAlignment = Alignment.Center
                ) {
                    MetroIcon(
                        Icons.Default.Cake,
                        contentDescription = null,
                        tint = pal.textWarmHighlight,
                        sizeDp = 26.dp
                    )
                }

                Spacer(Modifier.height(18.dp))

                // 标题
                MetroText(
                    text = s.lateTitle,
                    color = pal.textTitle,
                    style = TextStyle(
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        letterSpacing = 0.5.sp
                    )
                )

                Spacer(Modifier.height(14.dp))

                // 正文
                MetroText(
                    text = s.lateMessage(days),
                    color = pal.textBody,
                    style = TextStyle(
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp,
                        letterSpacing = 0.2.sp
                    )
                )

                Spacer(Modifier.height(28.dp))

                // 操作按钮
                CelebrationPillButton(
                    text = s.lateContinue,
                    onClick = { start = true },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                CelebrationPillButton(
                    text = s.close,
                    onClick = dismiss,
                    primary = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
