/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.network

import android.os.SystemClock
import android.util.Log

/**
 * 记录「应用回到前台」的时刻，并据此判断这次回来是不是**长时间挂后台之后**。
 *
 * ## 为什么需要它（v3.8.5）
 *
 * 现象：没在放歌、把应用丢后台很久，回来点播放，取链会卡住直到超时。
 *
 * 后台期间系统会冻结进程的网络，运营商 / 代理侧的 NAT 映射也会过期：
 * 连接池里那些「看起来还活着」的 socket 其实已经半死，DNS 解析结果也可能属于
 * 上一个网络形态。回来后的第一次取链正好踩在这些残留上。
 *
 * 所以进前台时如果距离上次在前台已经超过 [LONG_IDLE_MS]，就把连接池和 DNS 缓存
 * 都清一遍——成本几乎为零，但能避免第一次取链挂在死连接上。
 */
object AppForegroundTracker {

    private const val TAG = "AppForeground"

    /** 超过这个时长没在前台，就认为连接与解析结果都可能过期。 */
    const val LONG_IDLE_MS = 90_000L

    @Volatile
    private var lastForegroundAt = 0L

    @Volatile
    private var lastBackgroundAt = 0L

    /** 进前台：返回这次是不是「长时间挂后台之后」的第一次回来。 */
    fun onForeground(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val idle = if (lastForegroundAt > 0L) now - lastForegroundAt else 0L
        lastForegroundAt = now
        val longIdle = lastBackgroundAt > 0L && idle >= LONG_IDLE_MS
        if (longIdle) {
            Log.i(TAG, "back after ${idle / 1000}s idle — dropping pooled connections + DNS cache")
            RetrofitClient.evictConnections()
            DnsCache.clear()
        }
        return longIdle
    }

    fun onBackground() {
        lastBackgroundAt = SystemClock.elapsedRealtime()
    }

    /** 距上次在前台的毫秒数；从未进过前台时返回 [Long.MAX_VALUE]。 */
    fun sinceForegroundMs(): Long {
        val at = lastForegroundAt
        return if (at <= 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - at
    }
}
