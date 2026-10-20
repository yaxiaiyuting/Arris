/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import androidx.compose.runtime.Composable

/**
 * 读取一个阈值布尔的最小重组作用域。
 *
 * `derivedStateOf` 只在阈值穿越时通知，但若在 `PlayerCard` 顶层读，每次穿越仍会把
 * 整张卡片（歌词 / 队列 / 控件）重组一遍。这里把读取放进一个**非 inline** 的
 * Composable —— 它自带重组边界，状态翻转时只重组 [content]，不拖调用方一起重组。
 *
 * 传 `() -> Boolean` 而不是 `State<Boolean>`：调用方可以继续用 `by derivedStateOf`
 * 的委托属性，读取行为与原来逐字相同（只是位置从调用方挪到本节点）。
 */
@Composable
internal fun ThresholdScope(read: () -> Boolean, content: @Composable (Boolean) -> Unit) {
    content(read())
}
