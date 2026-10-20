/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 内容高度档，与「窄屏 / 宽屏」判定**正交**（后者只看宽度）。
 *
 * 矮屏上满宽方形封面会盖住进度条、歌名甚至播控键，根因就是所有响应式分支只看宽度。
 * 高度分档后播放器才能按可用高度收缩封面、压缩控件。
 *
 * 阈值：Regular >= 720dp（主流手机竖屏）/ Compact 560~719dp（小屏手机、分屏）/
 * Tiny < 560dp（超小屏、横屏手机被强制竖屏布局时的矮窗口）。
 */
enum class HeightClass { Regular, Compact, Tiny }

/** 纯函数，JVM 可单测（[com.takahashirinta.ncrust.ui.HeightClassTest]）。 */
fun heightClassFor(contentHeightDp: Float): HeightClass = when {
    contentHeightDp >= 720f -> HeightClass.Regular
    contentHeightDp >= 560f -> HeightClass.Compact
    else -> HeightClass.Tiny
}

/**
 * 手机沿用 `screenHeightDp`；车机沿用 collapsedOffsetY 的根布局实测高度
 * （CarSystemUI 不下发 WindowInsets，只有实测内容区才准）。
 */
val LocalHeightClass = staticCompositionLocalOf { HeightClass.Regular }
