/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

/** 实际流的平均码率；绝不以 PCM 采样率×位深或请求音质档位冒充文件码率。 */
object StreamBitrate {
    fun resolve(formatBitrate: Int, contentBytes: Long, durationMs: Long): Long {
        if (formatBitrate > 0) return formatBitrate.toLong()
        if (contentBytes <= 0L || durationMs <= 0L) return 0L
        return (contentBytes.toDouble() * 8000.0 / durationMs).toLong().coerceAtLeast(0L)
    }
}
