/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.network

import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 传输层失败的**取证日志**（v4.1.9）。
 *
 * ## 它解决的问题
 *
 * 真机现场只留下 `total=6997ms failed=SocketException` 一行 —— 目标是哪个 host、
 * 解析到了哪些地址、走的是 v4 还是 v6、异常 message 是什么（`Connection reset` /
 * `Software caused connection abort` / `timeout`），全都没有。于是排查只能靠猜。
 *
 * 这里补的就是这四个字段，并且**只在失败时**打（成功路径一行不加）：
 *
 * ```
 * fail host=interface3.music.163.com path=/eapi/enhance/player/url/v1 \
 *      addr=117.135.207.67(v4),2409:8c6a:b021:1400:0:4:0:3(v6) err=SocketException msg=...
 * ```
 *
 * ## 为什么 release 也保留
 *
 * 这类故障只在**特定运营商 / 特定时刻**出现，debug 包装不出来。代价用两道闸控制：
 * 同一 host 5s 内只一条，全局 5 分钟最多 20 条 —— 即使整条阶梯全失败也只有几行。
 *
 * 地址解析只在真正要打日志时才做（先过限流），结果缓存 30s，不在失败路径上反复解析。
 */
internal object NetworkDiagnostics {

    private const val TAG = "NcrustNetDiag"
    private const val HOST_INTERVAL_MS = 5_000L
    private const val BURST_WINDOW_MS = 5 * 60_000L
    private const val BURST_LIMIT = 20
    private const val ADDRESS_TTL_MS = 30_000L

    private val lastLogAt = ConcurrentHashMap<String, Long>()
    private val burstWindowStart = AtomicLong(0L)
    private val burstCount = AtomicInteger(0)
    private val addressCache = ConcurrentHashMap<String, CachedAddresses>()

    private data class CachedAddresses(val atMs: Long, val summary: String)

    /** `117.135.207.67(v4),2409:...:3(v6)`；解析失败也返回可读结论，不抛。 */
    fun describeHost(host: String, nowMs: Long = AddressPreference.nowMs()): String {
        addressCache[host]?.let { cached ->
            if (nowMs - cached.atMs < ADDRESS_TTL_MS) return cached.summary
        }
        val summary = runCatching {
            okhttp3.Dns.SYSTEM.lookup(host)
                .joinToString(",") { ip -> "${ip.hostAddress}(${family(ip)})" }
        }.getOrElse { "dns-failed:${it.javaClass.simpleName}" }.ifEmpty { "empty" }
        addressCache[host] = CachedAddresses(nowMs, summary)
        return summary
    }

    private fun family(ip: InetAddress): String = when (ip) {
        is Inet4Address -> "v4"
        is Inet6Address -> "v6"
        else -> "?"
    }

    /** 限流：同 host 5s 一条 + 全局 5 分钟 20 条。返回本次是否允许打印。 */
    fun shouldLog(host: String, nowMs: Long = AddressPreference.nowMs()): Boolean {
        val last = lastLogAt[host]
        if (last != null && nowMs - last < HOST_INTERVAL_MS) return false
        val start = burstWindowStart.get()
        if (start == 0L || nowMs - start >= BURST_WINDOW_MS) {
            burstWindowStart.set(nowMs)
            burstCount.set(0)
        }
        if (burstCount.get() >= BURST_LIMIT) return false
        lastLogAt[host] = nowMs
        burstCount.incrementAndGet()
        return true
    }

    /**
     * 一条失败行。[extra] 用来挂分段时间（`dns=1ms conn=8003ms tls=-1ms`）——
     * 那是判定「DNS / TCP / TLS 哪一层」的直接证据。
     */
    fun logFailure(path: String, host: String, e: Throwable?, extra: String = "") {
        if (!shouldLog(host)) return
        val cls = e?.javaClass?.simpleName ?: "none"
        val msg = e?.message?.replace('\n', ' ')?.take(160).orEmpty()
        Log.w(
            TAG,
            "fail host=$host path=$path addr=${describeHost(host)} err=$cls msg=$msg" +
                if (extra.isEmpty()) "" else " $extra",
        )
    }

    /** 成功建连时的路由（地址族证据）；同样限流，避免每通请求一行。 */
    fun logRoute(host: String, detail: String) {
        if (!shouldLog(host)) return
        Log.i(TAG, "route host=$host $detail")
    }
}
