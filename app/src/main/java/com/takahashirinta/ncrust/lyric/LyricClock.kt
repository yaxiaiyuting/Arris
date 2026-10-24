/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

/** 2Hz 位置采样间短暂外推；采样停止后最多走 750ms，不能把断网等待算成播放。 */
object LyricClock {
    fun positionAt(positionMs: Long, sampledNanos: Long, nowNanos: Long): Long =
        positionMs.coerceAtLeast(0L) + ((nowNanos - sampledNanos) / 1_000_000L).coerceIn(0L, 750L)
}
