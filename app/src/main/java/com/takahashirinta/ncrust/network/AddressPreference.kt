/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import okhttp3.Dns

/**
 * 多地址解析策略（v3.8.1）。
 *
 * ## 为什么需要它（真机实测，不是推测）
 *
 * 2026-10-30 的同设备对照实验（证据见 `docs/verification/v3.8.1/address-family.md`）：
 *
 * | 条件 | 只读请求 | 可播响应 |
 * |---|---:|---:|
 * | 系统 DNS 顺序，单地址连接等待 30s | 12 | **0** |
 * | 仅 IPv4 | 12 | **12** |
 * | 系统顺序 + 连接阶段压到 2s | 12 | **12** |
 *
 * 失败发生在 **TLS 之前**：DNS 先返回一个不回应的 IPv6 地址，30s 的连接超时远大于调用截止，
 * 于是调用在尝试后面的 IPv4 之前就被取消。ttfb=-1、耗时≈截止值，就是这一层的指纹。
 *
 * ## 策略
 *
 * **IPv4 优先、IPv6 保底**：把 `Inet4Address` 排到前面，其余原样跟在后面。
 * 不丢弃 IPv6 —— 纯 IPv6 网络（没有 A 记录）仍然只有 IPv6 可用，这时列表保持不变。
 *
 * 注意这与"全局禁用 IPv6"是两回事：后者会让纯 IPv6 环境直接不可用，
 * 而这里只是改**尝试顺序**，配合 [com.takahashirinta.ncrust.network.RetrofitClient]
 * 里收短的单地址连接预算，让"首个地址无响应"能在截止前完成回退。
 */
object AddressPreference {

    /**
     * 纯函数：**只要有 IPv4 就只返回 IPv4**；没有 A 记录时才返回原始列表（纯 IPv6 网络）。
     *
     * 为什么不是"IPv4 排前面、IPv6 跟上"：OkHttp 对单个地址的连接预算是独立计时的，
     * 而手机网络对 Cloudflare 的 IPv6 **完全不回包**（实测 `2606:4700:…` 8 秒零响应），
     * 于是第一个地址就把整通预算吃干净，IPv4 根本没机会被尝试 —— 日志里只能看到
     * `failed to connect to …/2606:4700:…`，看起来像"域名不通"，其实是地址族选择问题。
     *
     * 丢掉 IPv6 会不会影响可达性？不会：真正的纯 IPv6 网络（NAT64/仅 AAAA）里
     * 通常没有 A 记录，此时这里原样返回 IPv6；而"有 A 也有 AAAA"的网络，v4 一定能用。
     */
    fun order(all: List<InetAddress>): List<InetAddress> {
        if (all.isEmpty()) return all
        val v4 = all.filterIsInstance<Inet4Address>()
        return if (v4.isNotEmpty()) v4 else all
    }

    /**
     * 把某个域名固定解析到给定 IP 列表（用于绕开被污染的 DNS）。
     *
     * 只对 [host] 生效，其它域名走系统解析 —— 避免影响正常请求。
     * SNI/Host 仍是域名，TLS 证书校验照旧。
     */
    fun fixedFor(host: String, ips: List<String>): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname != host || ips.isEmpty()) return ipv4First.lookup(hostname)
            val parsed = ips.mapNotNull { ip ->
                runCatching { InetAddress.getByName(ip) }.getOrNull()
            }
            return if (parsed.isNotEmpty()) parsed else ipv4First.lookup(hostname)
        }
    }

    /**
     * 传输层失败后的**地址族回退**窗口（v4.1.9）。
     *
     * ## 为什么需要（真机实测，PCL110 / Android 17 / CMCC Wi-Fi，2026-11-22 23:2x）
     *
     * | 目标 | 地址 | 结果 |
     * |---|---|---|
     * | `interface3.music.163.com` | A `117.135.207.67` | `nc :443` **8/8 Timeout** |
     * | 同上 | AAAA `2409:8c6a:b021:1400:0:4:0:3` | `nc :443` **exit=0**（连上） |
     * | 同上 | ICMP v6 | 2/2 回包，208ms |
     *
     * [order] 的「有 v4 就只用 v4」在这里把客户端**硬钉在死掉的 v4 上**：每次取链
     * 先吃满 8s 调用截止（`InterruptedIOException("timeout")`，ttfb=-1），重试再吃一次，
     * 用户看到的就是「卡 15 秒然后播放失败」。所以失败一次之后必须允许换地址族重试。
     *
     * ## 为什么是「切换 + 自动切回」而不是永久偏置
     *
     * 反方向同样存在（v6 黑洞、v4 正常，就是 v3.8.1 当年的场景）。所以策略只在
     * **观察到一次传输层失败之后**生效，且窗口内再失败一次就切回 v4-only ——
     * 两个方向各最多付一次 8s，不会永久偏置到坏的地址族。
     *
     * 不用 `android.os.SystemClock`：纯 JVM 的 `nanoTime` 让这套状态机可以单测。
     */
    private const val IPV6_PREFERENCE_WINDOW_MS = 3 * 60_000L

    @Volatile
    private var ipv6PreferredUntilMs = 0L

    /** 单调时钟（毫秒）；单测可直接传时间。 */
    internal fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /** 纯函数：失败后该不该走 IPv6（窗口内）。 */
    internal fun isIpv6Preferred(nowMs: Long = nowMs()): Boolean =
        ipv6PreferredUntilMs > 0L && nowMs < ipv6PreferredUntilMs

    internal fun preferIpv6For(windowMs: Long = IPV6_PREFERENCE_WINDOW_MS, nowMs: Long = nowMs()) {
        ipv6PreferredUntilMs = nowMs + windowMs
    }

    fun clearIpv6Preference() {
        ipv6PreferredUntilMs = 0L
    }

    /**
     * 传输层失败（`SocketException` / 连接超时）时调用：**第一次切到 IPv6 优先，
     * IPv6 优先下再失败一次就退回 v4-only**。返回本次之后是否处于 IPv6 优先。
     *
     * 这样两个方向的故障各最多付一次超时，然后自动停在能通的那一族上。
     */
    fun onTransportFailure(nowMs: Long = nowMs()): Boolean = if (isIpv6Preferred(nowMs)) {
        clearIpv6Preference()
        false
    } else {
        preferIpv6For(nowMs = nowMs)
        true
    }

    /**
     * 纯函数：按当前偏好给出**尝试顺序**。
     *
     * - 默认（`preferIpv6 = false`）：与 [order] 完全一致，行为逐字节不变；
     * - 回退窗口内：IPv6 地址排前面、IPv4 跟在后面（保留 v4 作最后兜底），
     *   只有一族时原样返回那一族。
     */
    internal fun select(all: List<InetAddress>, preferIpv6: Boolean): List<InetAddress> {
        if (!preferIpv6) return order(all)
        if (all.isEmpty()) return all
        val v6 = all.filterIsInstance<Inet6Address>()
        val v4 = all.filterIsInstance<Inet4Address>()
        return when {
            v6.isEmpty() -> all
            else -> v6 + v4
        }
    }

    /**
     * 给 OkHttp 用的 DNS 实现：**默认有 IPv4 就只用 IPv4**；传输层失败后的回退窗口内
     * 改成 IPv6 优先（见 [onTransportFailure]）。
     *
     * 具名类（而不是匿名 object）是为了让 `HttpClientDnsAuditTest` 能断言
     * 「AppHttp 建出来的客户端确实用的是这条策略」。
     */
    class IPv4FirstDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            val preferIpv6 = isIpv6Preferred()
            val selected = select(all, preferIpv6)
            // 只在回退窗口内打一行（限流）：证明「换地址族」真的作用到了解析结果上，
            // 否则「切了策略但 OkHttp 仍然只试 v4」这种事故在日志里看不出来。
            if (preferIpv6 && NetworkDiagnostics.shouldLog(hostname)) {
                android.util.Log.i(
                    "NcrustNetDiag",
                    "dns host=$hostname preferIpv6=true all=" +
                        all.joinToString(",") { "${it.hostAddress}" } +
                        " -> " + selected.joinToString(",") { "${it.hostAddress}" },
                )
            }
            return selected
        }
    }

    val ipv4First: Dns = IPv4FirstDns()
}
