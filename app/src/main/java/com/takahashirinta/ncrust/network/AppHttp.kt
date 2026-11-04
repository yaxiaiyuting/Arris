/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.network

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/**
 * **全应用唯一的 `OkHttpClient` 入口**（v3.8.16）。
 *
 * ## 为什么要有这一层
 *
 * 「本机 Wi-Fi 双栈，但 IPv6 到外网完全不通（IPv4 200 / IPv6 15s 超时）」这个事实，
 * 我们已经逐条手动补了六次 `dns(AddressPreference.ipv4First)`：
 * ncm 取链 → 音频流 → B站 → 检查更新 → 应用内下载 → 封面/艺人/专辑图。
 *
 * 每次都是「有人新建了一个 `OkHttpClient.Builder()`，忘了加 dns」。这类错误不报错、
 * 单测也抓不到（每个客户端只测自己的逻辑），只能靠人记 —— 显然记不住。
 *
 * 所以把「默认值」收进来：所有客户端从 [builder] 出发，DNS / 超时 / 连接池 / 重试
 * 都是**已经设好的**，各调用点只覆盖自己需要的差异（比如 MQTT 要 readTimeout=0）。
 *
 * 约定由 `HttpClientDnsAuditTest` 守着：`app/src/main` 里**不允许**再出现裸的
 * `OkHttpClient.Builder()`，只能 `AppHttp.builder()`。
 *
 * ## 为什么不是「全局 Hook」
 *
 * OkHttp 没有全局默认 Builder 这种东西（构造器是死的，`Dns`/`Interceptor` 都是实例级）。
 * 能做到「接管」的最接近形态就是：**唯一工厂 + 源码扫描测试**。
 */
object AppHttp {

    /** 真机实测：坏 IPv6 地址会在连接阶段吃掉整个预算，所以单地址 8 秒必须让位。 */
    private const val CONNECT_TIMEOUT_S = 8L

    /** 普通 API 的读超时；长连接（MQTT / 流式下载）自己覆盖。 */
    private const val READ_TIMEOUT_S = 15L

    /** 进程级共享连接池：网络切换时 [evictAll] 一次就能清干净。 */
    private val pool = ConnectionPool(16, 30, TimeUnit.SECONDS)

    /**
     * 建一个「默认就对」的客户端 Builder。
     *
     * 已包含：IPv4 优先的 DNS、连接 8s / 读 15s、`retryOnConnectionFailure`、共享连接池。
     */
    fun builder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .dns(AddressPreference.ipv4First)
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(pool.also { pools.add(it) })

    /** 直接要一个成品（默认配置）。 */
    fun client(): OkHttpClient = builder().build()

    /** 丢掉所有池化连接（网络切换 / 代理开关 / 回到前台时调用）。 */
    // 3.7.26 · I07：派生池也登记，切网一起清。
    private val pools: MutableSet<ConnectionPool> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun evictAll() {
        val snapshot = pools.toList() + pool
        Thread({ snapshot.forEach { p -> runCatching { p.evictAll() } } }, "ncrust-evict-apphttp").start()
    }
}
