/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.qq

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** QQ 服务端日期按北京时间解释；保留完整时分秒，禁止宽松解析截断日期。 */
object QqMembershipExpiry {
    fun parse(text: String?): Long {
        val value = text?.trim().orEmpty()
        for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")) {
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            }
            val cursor = ParsePosition(0)
            val parsed = format.parse(value, cursor)
            if (parsed != null && cursor.index == value.length) {
                val endOfDate = if (pattern == "yyyy-MM-dd") 86_399L else 0L
                return (parsed.time / 1000L + endOfDate).coerceAtLeast(0L)
            }
        }
        val numeric = value.toLongOrNull() ?: return 0L
        return when {
            numeric >= 1_000_000_000_000L -> numeric / 1000L
            numeric >= 1_000_000_000L -> numeric
            else -> 0L
        }
    }
}
