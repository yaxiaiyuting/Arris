/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.network

import java.time.Instant
import java.time.ZoneId

/** 日推以设备本地时间 06:00 为换批边界。 */
object DailyRecommendation {
    private const val REFRESH_HOUR = 6

    fun currentDayKey(nowMillis: Long = System.currentTimeMillis()): String {
        val local = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
        val date = local.toLocalDate()
        return (if (local.hour < REFRESH_HOUR) date.minusDays(1) else date).toString()
    }
}
