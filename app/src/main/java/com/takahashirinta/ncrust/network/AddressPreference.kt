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

    /** 纯函数：IPv4 前置，IPv6 保持原相对顺序。 */
    fun order(all: List<InetAddress>): List<InetAddress> {
        if (all.isEmpty()) return all
        val v4 = all.filterIsInstance<Inet4Address>()
        if (v4.isEmpty()) return all
        if (v4.size == all.size) return all
        return v4 + all.filterNot { it is Inet4Address }
    }

    /** 给 OkHttp 用的 DNS 实现。 */
    val ipv4First: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            order(Dns.SYSTEM.lookup(hostname))
    }
}
