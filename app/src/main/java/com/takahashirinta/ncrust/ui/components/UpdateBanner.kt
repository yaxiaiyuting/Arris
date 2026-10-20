/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 移植自上游 Ncrust c14b1be。适配点：Arris 底部导航是 5 格（用户在第 4 格 / index 3），
 * 不是上游的 4 格；文案由 Strings.update 提供。
 */

package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.update.UpdateService
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 「发现新版本 X.Y.Z · 查看」横幅（用户页顶部）。
 *
 * 与登录失效横幅（[SessionExpiredBanner]）同一套视觉语言：直角、无圆角、左右贴边、
 * 主题色底 + 主题色文字。区别只在图标与语气——更新是好事，不该用警告图标。
 *
 * 这一行就是「不打扰」的落点：不弹窗、不发通知，只在用户自己打开用户页时出现一行。
 */
@Composable
fun UpdateAvailableBanner(
    version: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current.update
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(LocalMetroColors.current.primary.copy(alpha = 0.16f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroIcon(
            Icons.Default.SystemUpdate,
            contentDescription = null,
            tint = LocalMetroColors.current.primary,
            sizeDp = 18.dp
        )
        Spacer(Modifier.width(10.dp))
        // 不设 maxLines=1：8 种语言的这句话长度差很大，宁可折行也不能被截成
        // 「发现新版本 1.4…」——截断后用户读不到版本号。
        MetroText(
            strings.availableBanner(version),
            color = LocalMetroColors.current.primary,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 底部导航「用户」图标右上角的小圆点（Arris 底部导航共 5 格）。
 *
 * 只画圆点、不碰 Kanesumi 的 `MetroBottomNav`（`MetroBottomNavItem` 没有角标字段，
 * 且库对 Arris 只读）：这里叠在底部导航那一层 Box 上，按 5 等分算出用户那一格（index 3）的位置。
 *
 * 位置假设（与库的布局一致）：56dp 高的导航里，图标 22dp + 间隔 2dp + label 一行
 * （11sp / 行高 14sp）共 38dp 竖直居中 → 图标上沿 9dp、中心 20dp，圆点落在图标右上角。
 * 注意：字号缩放（fontScale）调大时 label 变高、图标整体上移，圆点不会跟着走
 * ——这里刻意不引额外的布局依赖，真机上按需再校。
 *
 * 状态由本组件自己订阅：更新状态不该读进 MainScreen 的重组作用域，
 * 否则每次检查（冷启动后一次）都会重组整个主界面。
 */
@Composable
fun UpdateNavDot(
    tabCount: Int,
    userTabIndex: Int,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current.update
    val state by UpdateService.state.collectAsState()
    if (!state.shouldNotify) return
    val accent = LocalMetroColors.current.primary
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = strings.dotDesc }
    ) {
        val safeCount = tabCount.coerceAtLeast(1)
        val cellWidth = size.width / safeCount
        // 用户页那一格的中心，再往图标右上方偏一点。Arris 有 5 个 tab，用户在第 4 格。
        val centerX = cellWidth * (userTabIndex + 0.5f) + 9.dp.toPx()
        drawCircle(
            color = accent,
            radius = 3.5.dp.toPx(),
            center = Offset(centerX, 10.dp.toPx())
        )
    }
}
