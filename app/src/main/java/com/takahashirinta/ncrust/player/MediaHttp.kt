/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.network.AddressPreference
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 音频流专用的 OkHttp 客户端（v3.8.9）。
 *
 * ## 为什么不能用 media3 默认的 `DefaultHttpDataSource`
 *
 * 它底层是 `HttpURLConnection`，带来三个问题，正好凑成「取链 700ms、播放却卡十几秒」：
 *
 * 1. **不认我们的 DNS 策略** —— 代理的 fake-ip / 双栈的坏 IPv6 地址照样往上连；
 * 2. **没有读超时** —— 连接半死时只能等系统 TCP 超时（分钟级），界面就是一直转圈；
 * 3. **连接池不归我们管** —— 网络切换后 `evictConnections()` 清不到它，旧连接继续被复用。
 *
 * 换成 OkHttp 之后这三件事都能自己定：IPv4 优先、连接 6s / 读 10s、
 * 连接池与业务客户端同源，`evictConnections()` 一并清掉。
 */
internal object MediaHttp {

    private const val TAG = "MediaHttp"

    /** 连接超时：坏地址要在 ExoPlayer 的重试预算内及时让位。 */
    private const val CONNECT_TIMEOUT_S = 6L

    /** 读超时：网络切换后半死的连接必须在这里报错，交给 ExoPlayer 重试换新连接。 */
    private const val READ_TIMEOUT_S = 10L

    /** 独立连接池（与 Retrofit 的分开计数，但同样受 [evict] 管理）。 */
    private val pool = ConnectionPool(8, 30, TimeUnit.SECONDS)

    val client: OkHttpClient = com.takahashirinta.ncrust.network.AppHttp.builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(pool)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    /** 丢掉池里的连接（网络变化 / 回到前台 / 代理开关时调用）。 */
    fun evict() {
        android.util.Log.i(TAG, "evicting audio connections")
        Thread({ runCatching { pool.evictAll() } }, "ncrust-evict-media").start()
    }
}
