/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.network

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/** API 客户端共享 DNS 策略、超时和连接池。需要专用池的调用方负责其生命周期。 */
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
        .connectionPool(pool)

    /** 直接要一个成品（默认配置）。 */
    fun client(): OkHttpClient = builder().build()

    /** 网络切换时异步清理共享池中的空闲连接。 */
    fun evictAll() {
        Thread({ runCatching { pool.evictAll() } }, "ncrust-evict-apphttp").start()
    }
}
