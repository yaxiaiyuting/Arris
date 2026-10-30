/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.network

import java.net.Inet4Address
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

    /** 给 OkHttp 用的 DNS 实现。 */
    val ipv4First: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            order(Dns.SYSTEM.lookup(hostname))
    }
}
