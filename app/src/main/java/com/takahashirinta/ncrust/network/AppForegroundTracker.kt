/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.network

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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

    /**
     * 超过这个时长没在前台就做一次轻量清理（清连接池 + DNS）。
     *
     * 早先取 90s，实测反馈「还是太久」：手机锁屏看眼消息再回来通常只有 10–30s，
     * 这段时间足以让后台网络被冻结、池里的 socket 变半死。10s 是个不打扰的界线
     * ——清一次连接池的成本只有几十微秒。
     */
    const val IDLE_CLEANUP_MS = 10_000L

    /** 回到前台时向外广播「离开时长」，播放层据此做刷新。 */
    private val _events = MutableSharedFlow<Long>(replay = 0, extraBufferCapacity = 4)
    val events: SharedFlow<Long> = _events.asSharedFlow()

    @Volatile
    private var lastForegroundAt = 0L

    @Volatile
    private var lastBackgroundAt = 0L

    /** 进前台：返回这次是不是「离开够久、做过清理」。 */
    fun onForeground(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val idle = if (lastForegroundAt > 0L) now - lastForegroundAt else 0L
        lastForegroundAt = now
        val longIdle = lastBackgroundAt > 0L && idle >= IDLE_CLEANUP_MS
        if (longIdle) {
            Log.i(TAG, "back after ${idle / 1000}s idle — dropping pooled connections + DNS cache")
            RetrofitClient.evictConnections()
            DnsCache.clear()
        }
        // 无论多久都通知一次：播放层据此「回来就刷新」，让按播放不用等取链。
        _events.tryEmit(idle)
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
