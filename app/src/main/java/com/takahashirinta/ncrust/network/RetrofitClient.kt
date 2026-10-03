package com.takahashirinta.ncrust.network

import android.content.Context
import com.takahashirinta.ncrust.BuildConfig
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.crypto.EapiCrypto
import com.takahashirinta.ncrust.network.crypto.WeapiCrypto
import okhttp3.*
import org.json.JSONObject
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val BASE_URL = "https://music.163.com"
    private const val INTERFACE_URL = "https://interface3.music.163.com"
    // eapi 客户端接口域：官方/参考实现全部 eapi 打到 interface.music.163.com（非 music.163.com）。
    // 读接口在 music 上宽松可通,但写操作(如 /eapi/radio/like)发错 host 会被判 -460 风险。
    private const val API_URL = "https://interface.music.163.com"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val IOS_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"

    private var currentCookie: String? = null

    fun init(context: Context) {
        currentCookie = CookieManager.getCookie(context)
    }

    fun updateCookie(cookie: String?) {
        currentCookie = cookie
    }

    fun getCookie(): String? = currentCookie

    private val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // v2.5.6 · P1：被动计时（真 TTFB / 响应体读完）。见 HttpTimingListener 的 KDoc ——
            // 它是纯观测，不改变请求行为；不加这一行，「TTFB 花在哪」在 release 包里无从取证。
            .eventListenerFactory(HttpTimingListener.factory)
            .build()
    }

    /**
     * `api` 的基座（v2.5.6 从 [api] 里提出来，只是为了让下面那段「撤销注释」有个落脚点；
     * 行为与 v2.5.5 逐字节相同）。
     */
    private val restClient: OkHttpClient by lazy {
        // BASIC 日志只在 debug 装：release 每次请求省一次 chain.proceed 拦截 + logcat 序列化。
        // 低端机上 CPU 敏感，能省则省。
        OkHttpClient.Builder().apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            }
            addInterceptor(CookieInterceptor())
            connectTimeout(30, TimeUnit.SECONDS)
            readTimeout(30, TimeUnit.SECONDS)
            eventListenerFactory(HttpTimingListener.factory)
        }.build()
    }

    private fun retrofitFor(client: OkHttpClient): NcmApi = Retrofit.Builder()
        .baseUrl("$BASE_URL/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(NcmApi::class.java)

    val api: NcmApi by lazy { retrofitFor(restClient) }

    /**
     * v2.5.6 · P1（**已撤销**）：这里曾经有一个只给搜索用的 "searchApi"，
     * 唯一区别是加了 `callTimeout(20, SECONDS)`。它在真机上**造成了回归，已删除**。
     *
     * ## 为什么撤销（真机证据，PLC110 / API 36 / v2.5.6 vc47）
     *
     * 加上它之后，ncm 搜索**从「慢」变成「永远 0 首」**：
     *
     * ```
     * NcrustHttpTiming: path=/api/cloudsearch/pc ttfb=-1ms body=-1ms \
     *                   total=20002ms failed=InterruptedIOException
     * SearchViewModel:  Caused by: java.io.IOException: Canceled
     * SearchViewModel:  aggregate query='love' netease=0 qq=30 qqTimedOut=false elapsed=20012ms
     * NcrustSearchLatency: dispatch->netease_done=20009ms dispatch->qq_done=3493ms
     * ```
     *
     * `ttfb=-1` ⇒ **请求头一个字节都没发出去**，却在 20002ms（正好是 `callTimeout`）被杀。
     * 同一份 logcat 里，其它走**没有** `callTimeout` 的请求长这样：
     *
     * ```
     * path=/eapi/v2/discovery/recommend/songs ttfb=240ms body=59ms total=30566ms
     * path=/eapi/w/nuser/account/get          ttfb=151ms body=1ms  total=30355ms
     * ```
     *
     * 即在这台设备的当前网络下，**每通请求都要 ~30 秒才把请求头送出去**（连接建立/排队，
     * 与 `ttfb` 无关 —— `ttfb` 只有 179~240ms），然后传输只要几毫秒。
     *
     * ⇒ **20s 的 `callTimeout` 卡在这条 30s 的必经路径下面**，
     * 于是它拦掉的不是「挂死的请求」，而是**本来会成功的请求**。
     *
     * ## 教训（已写进 `AGENTS.md` 新铁律 22 的配套条款）
     *
     * `callTimeout` 覆盖**整通**请求（含连接建立与排队），而 `connectTimeout`/`readTimeout`
     * 是**分阶段空闲**超时。在一个「连接建立就要 30s」的环境里，把一个更短的
     * `callTimeout` 加上去，等于把一条能用的路径改成必然失败 ——
     * **加超时前必须先量一次「这个环境里正常请求要多久」，`ttfb` 快不代表整通快。**
     *
     * 而且本版**根本不需要**这个熔断：用户可见的收益（首帧不再等 ncm）
     * 完全来自 `SearchViewModel` 的「先到先发布」，与超时无关。
     *
     * 保留本注释而不是默默删掉：下一个想「顺手加个超时」的人应该先看到这段。
     */


    fun eapiPost(
        path: String,
        payload: Map<String, String>,
        useInterface: Boolean = false,
        // A1：追加到用户 cookie 之后的客户端身份字段（见 ClientIdentity）。服务端按 Cookie 里
        // 的客户端身份判定音质上限，不带身份时取链的 hires 请求会被静默封顶成 lossless。
        // 默认 null —— 其余调用点（写接口等）行为完全不变，风控面收敛在取链这一条路径。
        extraCookie: String? = null
    ): Response {        val host = if (useInterface) INTERFACE_URL else API_URL
        val fullUrl = host + path
        val anyPayload = payload.mapValues { it.value as Any }
        val params = EapiCrypto.encryptParams(fullUrl, anyPayload)

        val requestBody = FormBody.Builder()
            .add("params", params)
            .build()

        val cookieHeader = listOfNotNull(
            currentCookie?.takeIf { it.isNotBlank() },
            extraCookie?.takeIf { it.isNotBlank() }
        ).joinToString("; ")

        val request = Request.Builder()
            .url(fullUrl)
            .post(requestBody)
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", cookieHeader)
            .build()

        return plainClient.newCall(request).execute()
    }

    /**
     * 逐行对齐官方/参考实现的 eapi 请求：
     *  - 发到 interface.music.163.com/eapi/...
     *  - 设备字段(osver/deviceId/os/appver/.../__csrf/MUSIC_U)以 **Cookie** 形式发送
     *    (createHeaderCookie 同款),非独立 HTTP 头
     *  - iOS UA；data.header 同时写入加密 body
     * 用于写接口(如 /eapi/radio/like)以彻底对齐客户端指纹,排除 -460 风控的字段形态差异。
     */
    fun eapiPostOfficial(path: String, payload: Map<String, String>): Response {
        val fullUrl = API_URL + path
        val csrf = getCsrfToken().orEmpty()
        val mus: Map<String, String> = (currentCookie ?: "")
            .split(';')
            .asSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .map { it.split('=', limit = 2) }
            .associate { it[0].trim() to it.getOrElse(1) { "" } }
        val header = LinkedHashMap<String, String>()
        header["os"] = "iphone"
        header["appver"] = "8.9.60"
        header["deviceId"] = mus["deviceId"] ?: run {
            (0 until 20).joinToString("") { "0123456789abcdef"[(Math.random() * 16).toInt()].toString() }
        }
        header["osver"] = mus["osver"] ?: "16.0"
        header["versioncode"] = "140"
        header["mobilename"] = ""
        header["buildver"] = (System.currentTimeMillis() / 1000).toString().take(10)
        header["resolution"] = "1920x1080"
        header["__csrf"] = csrf
        header["channel"] = "yykj"
        header["requestId"] = (20_000_000..30_000_000).random().toString()
        mus["MUSIC_U"]?.let { header["MUSIC_U"] = it }
        mus["MUSIC_A"]?.let { header["MUSIC_A"] = it }

        val cookieStr = header.entries.joinToString(";") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }

        val data = payload.toMutableMap()
        val headerJson = JSONObject()
        header.forEach { (k, v) -> headerJson.put(k, v) }
        data["header"] = headerJson.toString()
        val params = EapiCrypto.encryptParams(fullUrl, data)

        val requestBody = FormBody.Builder().add("params", params).build()

        val request = Request.Builder()
            .url(fullUrl)
            .post(requestBody)
            .header("User-Agent", IOS_UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", cookieStr)
            .build()

        return plainClient.newCall(request).execute()
    }

    /**
     * weapi 加密 POST。用于受保护但走 weapi 的接口。
     * 参考官方 weapi：POST 路径为 `/weapi/<去掉 /api/ 前缀>`（并非 /api/…），payload 需
     * 注入 `csrf_token`（来自 Cookie 的 __csrf）。session Cookie 由上层传入。
     */
    fun weapiPost(
        path: String,
        payloadJson: String,
        extraCookies: String? = null
    ): okhttp3.Response {
        val rawPayload = try { JSONObject(payloadJson) } catch (_: Exception) { JSONObject() }
        getCsrfToken()?.let {
            if (it.isNotEmpty() && !rawPayload.has("csrf_token")) rawPayload.put("csrf_token", it)
        }
        val (params, encSecKey) = WeapiCrypto.encryptParams(rawPayload.toString())
        val weapiPath = if (path.startsWith("/api/")) "/weapi/" + path.removePrefix("/api/") else path
        val fullUrl = "https://music.163.com" + weapiPath
        val requestBody = FormBody.Builder()
            .add("params", params)
            .add("encSecKey", encSecKey)
            .build()
        val builder = Request.Builder()
            .url(fullUrl)
            .post(requestBody)
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Origin", "https://music.163.com")
        // 未登录时不发空 Cookie 头, 与官方网页/参考实现一致；extraCookies 用于
        // 扫码登录等需要携带会话(反风控/sDeviceId)cookie 的场景。
        val cookieHeader = listOfNotNull(
            currentCookie?.takeIf { it.isNotBlank() },
            extraCookies?.takeIf { it.isNotBlank() }
        ).joinToString("; ")
        if (cookieHeader.isNotEmpty()) builder.header("Cookie", cookieHeader)
        return plainClient.newCall(builder.build()).execute()
    }

    fun get(path: String, useInterface: Boolean = false): String {
        val host = if (useInterface) INTERFACE_URL else BASE_URL
        val request = Request.Builder()
            .url(host + path)
            .get()
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", currentCookie ?: "")
            .build()

        return plainClient.newCall(request).execute().body?.string() ?: throw Exception("empty response")
    }

    /**
     * 播放行为上报(webLog)。与官方 web 播放器一致,POST 到 clientlogusf 日志域,
     * body 为表单 `logs=<JSON 数组>`,并携带 session Cookie。
     *
     * 这条链路不走 eapi/weapi 加密,只做普通表单 POST + csrf_token。
     */
    fun postWeblog(weblogUrl: String, logsJson: String): okhttp3.Response {
        val request = Request.Builder()
            .url(weblogUrl)
            .post(FormBody.Builder().add("logs", logsJson).build())
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", currentCookie ?: "")
            .build()
        return plainClient.newCall(request).execute()
    }

    /** 从当前 Cookie 串中提取 __csrf token,用于 weblog 上报。 */
    fun getCsrfToken(): String? {
        val cookie = currentCookie ?: return null
        return cookie.split(';')
            .asSequence()
            .map { it.trim() }
            .filter { it.startsWith("__csrf=") }
            .map { it.removePrefix("__csrf=") }
            .firstOrNull()
    }

    private class CookieInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val cookie = currentCookie
            val newRequest = if (!cookie.isNullOrBlank()) {
                originalRequest.newBuilder()
                    .header("Cookie", cookie)
                    .header("User-Agent", UA)
                    .header("Referer", "https://music.163.com/")
                    .build()
            } else {
                originalRequest
            }
            return chain.proceed(newRequest)
        }
    }
}
