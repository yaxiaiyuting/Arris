/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · C（hotfix 4）：QQ 互联扫码登录的 HTTP 部分。
 * v2.1.1：轮询结果改成三态（[QqQrLogin.PollResult]），不再把 403 与网络异常混成 null。
 */

package com.takahashirinta.ncrust.qq

import android.util.Log
import com.takahashirinta.ncrust.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * QQ 互联扫码登录的 HTTP 通道（v2.1.0 · C）。
 *
 * 两步，都是纯 HTTP：
 * 1. [requestQr] 取二维码 PNG 与 `qrsig`（`qrsig` 是算 `ptqrtoken` 的种子，必须留着）；
 * 2. [poll] 轮询扫码状态，直到 `ptuiCB` 给出 `0`（确认成功）与下一步的跳转地址。
 *
 * ## 独立的 OkHttp 客户端 + 内存 CookieJar
 *
 * 扫码流程**依赖 cookie**（`qrsig` 在 `ptqrshow` 的 Set-Cookie 里，轮询时必须带回去），
 * 而 [QqClient] 那条通道是手动拼 `Cookie` 头的、没有 jar。这里用一个**只属于登录流程**的
 * 客户端与内存 jar：登录结束后它们就没有用了，不需要持久化，也不会污染业务请求的 cookie。
 *
 * ## 超时
 *
 * 轮询是「长轮询」，服务端可能压着不返回，所以读超时给到 30 秒；连接超时短一些（10 秒），
 * 连不上就快速失败，让界面能立刻提示「网络不通」而不是干等。
 */
object QqQrClient {

    private const val TAG = "QqQrClient"

    private val cookieJar = SimpleCookieJar()

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 二维码图片 + 用于计算 `ptqrtoken` 的 `qrsig`。 */
    data class QrCode(val png: ByteArray, val qrsig: String) {
        // ByteArray 在 data class 里的 equals/hashCode 是引用语义，显式覆盖掉，
        // 免得将来有人拿它做比较时得到「同一个二维码却不相等」的诡异结果。
        override fun equals(other: Any?): Boolean =
            this === other || (other is QrCode && qrsig == other.qrsig && png.contentEquals(other.png))

        override fun hashCode(): Int = 31 * png.contentHashCode() + qrsig.hashCode()
    }

    /**
     * 申请一张二维码。失败返回 null（界面据此显示「二维码获取失败，请重试」）。
     *
     * 每次都先清空 jar：上一张二维码的 `qrsig` 留着会让新二维码的 token 算错
     * （`ptqrtoken` 必须与**当前这张**二维码的 `qrsig` 配对）。
     */
    suspend fun requestQr(): QrCode? = withContext(Dispatchers.IO) {
        cookieJar.clear()
        val url = QqQrLogin.qrShowUrl(Math.random(), QqQrLogin.QR_SIZE_PARAM)
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "https://xui.ptlogin2.qq.com/")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "ptqrshow http=${response.code}")
                    return@use null
                }
                val png = response.body?.bytes() ?: return@use null
                // PNG 头校验：拿到 HTML 错误页时不至于把它当二维码画出来
                if (png.size < 8 || png[0] != 0x89.toByte() || png[1] != 'P'.code.toByte()) {
                    Log.w(TAG, "ptqrshow 返回的不是 PNG（${png.size} 字节）")
                    return@use null
                }
                val qrsig = cookieJar.value("qrsig")
                if (qrsig.isNullOrEmpty()) {
                    Log.w(TAG, "ptqrshow 没有返回 qrsig")
                    return@use null
                }
                if (BuildConfig.DEBUG) Log.d(TAG, "ptqrshow ok png=${png.size} qrsig=${qrsig.length} 字符")
                QrCode(png, qrsig)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ptqrshow failed", e)
            null
        }
    }

    /**
     * 轮询一次。
     *
     * 三种结局分得很清楚（见 [QqQrLogin.PollResult]）：
     * - [QqQrLogin.PollResult.Status]：拿到可解析的 `ptuiCB`；
     * - [QqQrLogin.PollResult.Unavailable]：服务端拒绝（403/空 body/不是 ptuiCB 的 200）——
     *   **重试没有意义**，调用方应当累计到阈值后引导用户改用网页登录；
     * - [QqQrLogin.PollResult.NetworkError]：请求本身失败 —— 下一轮继续。
     *
     * v2.1.0 这三种都返回 `null`，界面因此把恒定的 403 显示成「网络不稳定，仍在重试…」。
     * 归类逻辑抽在 [QqQrLogin.classifyPollResponse]（纯函数、有单测），这里只负责 IO。
     */
    suspend fun poll(qrsig: String): QqQrLogin.PollResult = withContext(Dispatchers.IO) {
        val token = QqQrLogin.ptqrToken(qrsig)
        val url = QqQrLogin.qrLoginUrl(token, System.currentTimeMillis())
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "https://xui.ptlogin2.qq.com/")
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string()
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "ptqrlogin http=${response.code} body=${text?.take(80)}")
                }
                // 403/其它非 200：把 body 也打出来（有些环境会返回一段说明）
                if (!response.isSuccessful) {
                    Log.w(TAG, "ptqrlogin http=${response.code} body=${text?.take(160)}")
                }
                QqQrLogin.classifyPollResponse(response.code, text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ptqrlogin failed", e)
            QqQrLogin.PollResult.NetworkError
        }
    }

    /**
     * 把 cookie jar 里的 cookie 拼成字符串。
     *
     * 登录成功后要给 WebView 用（把它注入 WebView 的 cookie store，让 `y.qq.com`
     * 自己的脚本完成音乐票据交换 —— 见 `QqQrLoginDialog` 的说明）。
     */
    fun cookies(): String = cookieJar.all()

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    /** 极简内存 CookieJar：只要「存下 Set-Cookie、请求时带回去」，不处理域/路径细节之外的东西。 */
    private class SimpleCookieJar : okhttp3.CookieJar {
        private val store = LinkedHashMap<String, okhttp3.Cookie>()

        @Synchronized
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            for (c in cookies) {
                // 同名 cookie 以最后一次为准（ptlogin 会在不同子域重复下发 uin 等）
                store[c.name] = c
            }
        }

        @Synchronized
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
            store.values.filter { it.matches(url) }

        @Synchronized
        fun value(name: String): String? = store[name]?.value

        @Synchronized
        fun all(): String = store.values.joinToString("; ") { it.name + "=" + it.value }

        @Synchronized
        fun clear() = store.clear()
    }
}
