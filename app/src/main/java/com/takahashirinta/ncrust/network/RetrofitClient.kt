package com.takahashirinta.ncrust.network

import android.content.Context
import android.util.Log
import com.takahashirinta.ncrust.BuildConfig
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.auth.NeteaseSessionSnapshot
import com.takahashirinta.ncrust.auth.QrLoginProtocol
import com.takahashirinta.ncrust.network.crypto.EapiCrypto
import com.takahashirinta.ncrust.network.crypto.WeapiCrypto
import com.takahashirinta.ncrust.network.crypto.XeapiCrypto
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val TAG = "RetrofitClient"
    private const val XEAPI_URL = "https://interface3.music.163.com"
    internal const val BASE_URL = "https://music.163.com"
    internal const val INTERFACE_URL = "https://interface3.music.163.com"
    // eapi 客户端接口域：官方/参考实现全部 eapi 打到 interface.music.163.com（非 music.163.com）。
    // 读接口在 music 上宽松可通,但写操作(如 /eapi/radio/like)发错 host 会被判 -460 风险。
    internal const val API_URL = "https://interface.music.163.com"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val IOS_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
    // 扫码登录的 eapi 只认 PC 客户端身份：host 用 interfacepc，UA 用桌面客户端（协议见
    // spec/api/endpoints.md「eapi · PC 客户端指纹」）。
    private const val INTERFACE_PC_URL = "https://interfacepc.music.163.com"
    private const val PC_UA = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.29.205117"
    // 播放上报（听歌打卡）的日志域。旧通道 clientlogusf 回 200 但不入账，只有这里的
    // clientlog + eapi + os=osx 身份入账（依据 NeteaseCloudMusicApiEnhanced/api-enhanced#167，
    // 协议见 spec/api/endpoints.md 的「播放上报 / 听歌打卡」）。
    private const val CLIENT_LOG_URL = "https://clientlog.music.163.com"
    // os=osx 身份专用的 Mac Chrome UA：参考实现的 eapi 分支里 `cookie.os === 'osx'` 时才用它。
    private const val OSX_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    // 官方 Android 9.5.61 的 UA 与设备字段，xeapi 的公钥请求与业务请求共用同一套指纹。
    private const val ANDROID_UA = "NeteaseMusic/9.5.61.260802021928(9005061);Dalvik/2.1.0 (Linux; U; Android 12; HBN-AL00 Build/cd737a2.0)"
    private const val XEAPI_OS = "android"
    private const val XEAPI_OSVER = "16"
    private const val XEAPI_APPVER = "9.1.65"
    private const val ANTI_CHEAT_URL = "https://ac.dun.163yun.com/v3/b?pn=YD00000558929251"
    private val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded;charset=utf-8".toMediaType()

    /** 易盾响应形如 `null([200,<n>,"<token>"])`（xeapi.md §5）。 */
    private val ANTI_CHEAT_RE = Regex("""null\(\[(\d+),\d+,"([^"]+)"\]\)""")

    private val currentCookie: String? get() = CookieManager.snapshot().cookie

    fun init(context: Context) {
        CookieManager.init(context)
        // deviceId / 公钥状态 / xeapi 会话；只读盘，不发网络（首次业务请求时才取公钥）。
        XeapiState.init(context)
    }

    /**
     * 丢掉连接池里的所有连接。
     *
     * 服务端在 HTTP/2 上回 `REFUSED_STREAM`（或半关闭）时，连接本身看起来仍是"可用"的，
     * OkHttp 会继续把新请求复用到这条已经不可用的连接上 —— 表现是**每一次**请求都立刻失败，
     * 直到某次恰好换了新连接。POST 不是幂等请求，OkHttp 不会自动重试，所以由调用方在
     * 识别到传输层失败后主动弃连接、在新连接上重试一次。
     */
    /** Debug 探针专用：拿一份与 [plainClient] 同配置、但协议可指定的客户端。 */
    internal fun probeClientWithProtocols(protocols: List<okhttp3.Protocol>): OkHttpClient =
        plainClient.newBuilder().protocols(protocols).build()

    /**
     * 清空连接池（切账号 / 换身份后让旧连接立刻失效）。
     *
     * ⚠️ **必须在后台线程执行**：`evictAll()` 会关闭底层的 TLS socket，
     * 而 Conscrypt 在关闭时要写 close_notify —— 这是网络 I/O。
     * 真机实测（PCL110 / Android 16）：调用方从主线程的协程恢复时进这里，
     * 直接 `FATAL EXCEPTION: main android.os.NetworkOnMainThreadException`，
     * 表现为「房主端用着用着就闪退、房间跟着没了、成员收不到任何快照」。
     *
     * 所以这里自己做线程切换，让调用方不必关心自己在哪个线程。
     */
    fun evictConnections() {
        // 单独开一条线程：不依赖任何 OkHttp 实例的 Dispatcher 是否还在就绪状态，
        // 也不会因为调用方在主线程而抛 NetworkOnMainThreadException。
        runCatching {
            Thread({ runCatching { sharedConnectionPool.evictAll() } }, "ncrust-evict-conns").start()
        }
    }

    fun updateCookie(cookie: String?) {
        // 换登录态后旧的内存会话不再属于当前 cookie，清掉重取。
        XeapiState.clearSession()
    }

    fun getCookie(): String? = currentCookie

    /**
     * **全应用唯一的连接池**。
     *
     * OkHttp 的 `ConnectionPool` 是 `OkHttpClient` 实例的私有字段，不是全局的：
     * 不显式共享时每个 `OkHttpClient.Builder().build()` 都带一个自己的池子，
     * 连接复用（`ConnectionPool.transmitterAcquirePooledConnection`）只在**同一个池子**里找。
     *
     * `ConnectionWarmup` 从 v3.1.0 起就形同虚设，第一个原因就是它自建了一个第三方客户端：
     * 它把 `interface3.music.163.com` 的连接建在自己的池子里，而真正取链的是 [plainClient]、
     * 取歌词的是 [restClient] —— 两个池子它都没碰到。
     *
     * 池子参数用 OkHttp 默认（5 个空闲连接 / 5 分钟保活）：本应用同时活跃的 host 不超过 4 个。
     */
    // 保活从默认 5 分钟收紧到 30 秒：运营商/NAT 会静默丢弃长期存活的连接。
    internal val sharedConnectionPool: ConnectionPool = ConnectionPool(5, 30, TimeUnit.SECONDS)

    /**
     * **所有 OkHttpClient 的唯一基座**（v3.3.2）。
     *
     * ## 为什么光有共享池还不够（这是本次最容易踩空的一格）
     *
     * 只把同一个 [sharedConnectionPool] 传给两个**各自独立 build** 的客户端，
     * 连接**仍然不会**跨客户端复用。原因在 `RealConnection.isEligible`：
     * 它先比 `Address`，而 `Address.equalsNonHost` 的字段里包含
     * `sslSocketFactory` 与 `certificatePinner` —— `OkHttpClient.Builder.build()`
     * 在调用方没有显式指定这两者时，会**给每个 client 各造一份**
     * （`build()` 里 `sslSocketFactoryOrNull` 为 null 时现建一个 TrustManager 与工厂）。
     * 于是两个 client 的 `Address` 永不相等，池子里明明有同一 host 的空闲连接也匹配不上。
     *
     * 实测（OkHttp 4.12.0，真实打 `https://interface3.music.163.com/`，
     * `conn+queue = requestHeadersEnd − callStart`，与 [HttpTimingListener] 同口径）：
     *
     * | 形态 | 预热通 | 业务通 |
     * |---|---|---|
     * | 各自 build + 共享池 | connect=true, 134ms | **connect=true, 131ms**（又建一次） |
     * | 同一 client 两通（对照） | connect=true, 335ms | connect=false, **0ms** |
     * | **全部从本基座 newBuilder() 派生** | connect=true, 315ms | connect=false, **0ms** |
     *
     * 所以「共享」必须做到**基座同源**：`newBuilder()` 会原样沿用已建好的
     * `sslSocketFactory` / `certificatePinner` 实例，因此派生出来的客户端 Address 兼容，
     * 池子里的连接才真的能被下一条业务请求拿走。
     *
     * 注意 `newBuilder()` **会**复制 `interceptors` 与 `eventListenerFactory`，
     * 所以各客户端只在自己那一层加需要的东西（见 [plainClient] / [restClient]）。
     */
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // 保持协议默认协商（HTTP/2 优先）。2026-10-25 的对照实验证明 h2 本身可用：
            // 同样的 client 配置强制 h2 时，取链接口 200 / 300 ms，与 h1 逐字节一致。
            // 之前观察到的 `REFUSED_STREAM` 是**请求风暴限流**（一次点击 6 档阶梯、取消不掉、
            // 失败又触发自动跳歌 → 53 秒 2064 个请求），不是协议问题。真正的修法在
            // SongUrlFetcher（可取消、传输失败即停、全局冷却）与失败分类（NETWORK 不跳歌）。
            // h2 **连接级保活**：多路复用连接被中间设备黑洞时，没有"每次请求新建连接"的兜底，
            // 只能等读超时才知情（实测：真实取链 8 s 超时，同一时刻新建连接 190 ms 成功）。
            // PING 让 OkHttp 在连接失效时及时换新连接。
            .pingInterval(15, TimeUnit.SECONDS)
            .connectionPool(sharedConnectionPool)
            // 多地址回退：真机实测（docs/verification/v3.8.1/address-family.md）
            // DNS 首个 IPv6 地址不回 TCP，而 30s 的单地址连接等待远大于调用截止，
            // 结果是在尝试 IPv4 之前就被取消 —— 表现为 8s 级超时、ttfb=-1。
            // 两处配合：① IPv4 优先（IPv6 保底，纯 IPv6 网络不受影响）
            //          ② 单地址连接预算 30s → 8s，让坏地址能在截止前让位给下一个地址
            .dns(AddressPreference.ipv4First)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 预热专用客户端的工厂：**与业务客户端同源**（Address 兼容 ⇒ 连接真能被复用），
     * 但保留自己的短超时，且**不挂** [HttpTimingListener]。
     *
     * 不挂计时器是有意的：预热打的是 `GET /`，它不是业务请求；
     * 让它进 `NcrustHttpTiming` 只会给「TTFB 花在哪」这本账添噪声。
     *
     * @param connectTimeoutSeconds 预热自己的超时上限（见 `ConnectionWarmup.TIMEOUT_SECONDS`）。
     */
    internal fun warmupDerivedClient(
        connectTimeoutSeconds: Long,
        readTimeoutSeconds: Long = connectTimeoutSeconds,
    ): OkHttpClient = baseClient.newBuilder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    private val plainClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            // 每主机并发从默认 5 提到 12：播放取链、歌词、资料、封面会同时打同一台主机，
            // 5 条槽位一旦被几条慢请求占住，后续请求就只能在队列里干等（实测单请求 total
            // 30 s、而 ttfb 只有 96 ms —— 时间全花在排队）。上限仍在，不会无限并发。
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 12
            })
            // v2.5.6 · P1：被动计时（真 TTFB / 响应体读完）。见 HttpTimingListener 的 KDoc ——
            // 它是纯观测，不改变请求行为；不加这一行，「TTFB 花在哪」在 release 包里无从取证。
            .eventListenerFactory(HttpTimingListener.factory)
            .build()
    }

    /** Playback read fallback only: do not reuse an h2 route rejected by the remote peer. */
    private val playbackHttp1Client: OkHttpClient by lazy {
        plainClient.newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(2, 30, TimeUnit.SECONDS))
            .build()
    }

    /** 易盾 token 与业务客户端共用连接池；获取失败发空串，避免阻塞收藏。 */
    private val antiCheatClient: OkHttpClient by lazy {
        plainClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * `api` 的基座（v2.5.6 从 [api] 里提出来，只是为了让下面那段「撤销注释」有个落脚点；
     * 行为与 v2.5.5 逐字节相同）。
     */
    private val restClient: OkHttpClient by lazy {
        baseClient.newBuilder().apply {
            // BASIC 日志只在 debug 装：release 每次请求省一次 chain.proceed 拦截 + logcat 序列化。
            // 低端机上 CPU 敏感，能省则省。
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            }
            addInterceptor(CookieInterceptor())
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
        extraCookie: String? = null,
        // 扫码确认后校验尚未持久化的会话；其余调用保留原来的身份追加方式。
        cookieOverride: String? = null,
        pinnedSession: NeteaseSessionSnapshot? = null,
    ): Response {
        val session = pinnedSession ?: CookieManager.snapshot()
        if (pinnedSession != null && CookieManager.snapshot().loginGeneration != session.loginGeneration) {
            throw java.io.IOException("session changed before eapi write")
        }
        val host = if (useInterface) INTERFACE_URL else API_URL
        val fullUrl = host + path
        val anyPayload = payload.mapValues { it.value as Any }
        val params = EapiCrypto.encryptParams(fullUrl, anyPayload)

        val requestBody = FormBody.Builder()
            .add("params", params)
            .build()

        val cookieHeader = listOfNotNull(
            (cookieOverride ?: session.cookie)?.takeIf { it.isNotBlank() },
            extraCookie?.takeIf { it.isNotBlank() }
        ).joinToString("; ")

        val request = Request.Builder().tag(NeteaseSessionSnapshot::class.java, if (cookieOverride == null) session else null)
            .url(fullUrl)
            .post(requestBody)
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", cookieHeader)
            .build()

        return plainClient.newCall(request).execute()
    }

    /**
     * [eapiPost] 的**可取消**版本：协程被取消时真正 `Call.cancel()`，而不是等阻塞的
     * `execute()` 自己跑完。
     *
     * 为什么必须有它：切歌时 `playJob.cancel()` 只结束协程，已发出的 HTTP 调用仍会跑完，
     * 于是「连点下一首」会累积成一串并发请求；服务端对播放取链接口有并发/频率阈值，
     * 超了就用 `REFUSED_STREAM` 拒流（实测 6 次点击 = 36 个请求，直接换来 53 秒的全面拒流）。
     */
    suspend fun eapiPostCancellable(
        path: String,
        payload: Map<String, String>,
        useInterface: Boolean = false,
        extraCookie: String? = null,
    ): Response {
        val session = CookieManager.snapshot()
        val host = if (useInterface) INTERFACE_URL else API_URL
        val fullUrl = host + path
        val params = EapiCrypto.encryptParams(fullUrl, payload.mapValues { it.value as Any })
        val request = Request.Builder()
            .tag(NeteaseSessionSnapshot::class.java, session)
            .url(fullUrl)
            .post(FormBody.Builder().add("params", params).build())
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", listOfNotNull(
                session.cookie?.takeIf { it.isNotBlank() },
                extraCookie?.takeIf { it.isNotBlank() },
            ).joinToString("; "))
            .build()
        // 播放取链是用户正在等的请求：单次最多等 8 秒就判失败，让上层走「有界重试」，
        // 而不是把 30 秒的读超时耗完（那 30 秒里所有同主机请求都在排队）。
        val call = plainClient.newCall(request).apply {
            timeout().timeout(8, TimeUnit.SECONDS)
        }
        return if (request.url.encodedPath == "/eapi/song/enhance/player/url/v1") {
            call.awaitPlaybackRead {
                playbackHttp1Client.newCall(request).apply { timeout().timeout(8, TimeUnit.SECONDS) }
            }
        } else call.awaitBufferedResponse()
    }

    /**
     * eapi + **PC 客户端身份** POST（打 interfacepc）。扫码登录专用：host、PC UA、设备字段
     * （按 `encodeURIComponent` 同时作 Cookie）与业务里的 `type=3` 缺一都拿不到 unikey——
     * 老通道 weapi + `type=1` 现在直接回 `-462`（协议与依据见 `spec/api/endpoints.md`）。
     *
     * 请求头只有 UA 与 Cookie（参考实现没带 Referer）；设备字段与信封的构造在
     * [QrLoginProtocol] 里，是纯函数、有单测。
     *
     * @param path `/api/…` 形态，内部改写成 `/eapi/…`。
     * @param fields 业务字段，值必须给原生类型（`type` 是数值）。
     */
    fun eapiPostPc(path: String, fields: Map<String, Any>): Response {
        val header = QrLoginProtocol.pcHeader(
            deviceId = pcDeviceId(),
            nowMs = System.currentTimeMillis(),
            requestSuffix = (0..999).random()
        )
        return eapiPostWithIdentity(INTERFACE_PC_URL, PC_UA, path, fields, header)
    }

    /** Token refresh uses the same PC envelope as the reference client's default eapi path. */
    internal fun refreshNeteaseSession(cookie: String): Response {
        val header = QrLoginProtocol.pcHeader(pcDeviceId(), System.currentTimeMillis(), (0..999).random()).toMutableMap()
        val saved = cookieValues(cookie)
        listOf("MUSIC_U", "MUSIC_A", "__csrf").forEach { key -> saved[key]?.let { header[key] = it } }
        return eapiPostWithIdentity(INTERFACE_PC_URL, PC_UA, "/api/login/token/refresh", emptyMap(), header)
    }

    /**
     * eapi + **os=osx 身份** POST（打 clientlog 日志域）。播放上报专用：听歌打卡只有
     * 「clientlog + eapi + os=osx」这个组合入账，android / iphone / pc 身份的同名请求会被丢弃
     * （依据 NeteaseCloudMusicApiEnhanced/api-enhanced#167，协议见 `spec/api/endpoints.md`）。
     *
     * 请求头只有 UA 与 Cookie（参考实现这条路径不带 Referer）；字段与顺序由 [osxHeader] 负责，
     * 是纯函数、有单测。
     *
     * @param path `/api/…` 形态，内部改写成 `/eapi/…`。
     * @param fields 业务字段（播放上报只有一个 `logs`，值是 JSON 文本）。
     */
    fun eapiPostOsx(
        path: String,
        fields: Map<String, Any>,
        /**
         * H03：调用方钉住的凭据（连同登录代次）。异步上报线程绝不能在排队之后
         * 重新读一次 CookieManager —— 那会把 A 账号的收听记录发给 B。
         */
        pinnedCookie: String? = null,
        pinnedGeneration: Long? = null,
    ): Response {
        if (pinnedGeneration != null) {
            val now = CookieManager.snapshot()
            if (now.loginGeneration != pinnedGeneration) {
                throw IOException("session changed before scrobble $path")
            }
        }
        val cookie = cookieValues(pinnedCookie ?: currentCookie)
        val header = osxHeader(
            deviceId = pcDeviceId(),
            csrf = getCsrfToken(pinnedCookie ?: currentCookie).orEmpty(),
            musicU = cookie["MUSIC_U"],
            musicA = cookie["MUSIC_A"],
            nowMs = System.currentTimeMillis(),
            requestSuffix = (0..999).random()
        )
        return eapiPostWithIdentity(CLIENT_LOG_URL, OSX_UA, path, fields, header)
    }

    /**
     * os=osx 身份的设备字段，**顺序即协议**（逐项对齐参考实现 util/request.js 的 eapi 分支）。
     *
     * 同一份既按 [QrLoginProtocol.cookieHeader] 作请求 Cookie，也整体进加密 body 的 `header`；
     * 服务端就是靠这组字段认出「桌面客户端」的 —— 换成 android / iphone / pc 身份，请求照回 200
     * 但不会入账。纯函数（deviceId 与时间由调用方给），JVM 单测直接断言字段与顺序。
     *
     * @param nowMs 毫秒时间戳：`buildver` 取它的前 10 位（等价于秒级时间戳），
     *   `requestId` 是 `<毫秒>_<4 位随机>`。
     */
    fun osxHeader(
        deviceId: String,
        csrf: String,
        musicU: String?,
        musicA: String?,
        nowMs: Long,
        requestSuffix: Int,
    ): LinkedHashMap<String, String> {
        val header = linkedMapOf(
            "osver" to "15.5",
            "deviceId" to deviceId,
            "os" to "osx",
            "appver" to "3.1.10.5100",
            "versioncode" to "140",
            "mobilename" to "",
            "buildver" to (nowMs / 1000).toString(),
            "resolution" to "1920x1080",
            "__csrf" to csrf,
            "channel" to "netease",
            "requestId" to "${nowMs}_${requestSuffix.toString().padStart(4, '0')}",
        )
        if (!musicU.isNullOrEmpty()) header["MUSIC_U"] = musicU
        if (!musicA.isNullOrEmpty()) header["MUSIC_A"] = musicA
        return header
    }

    /**
     * eapi + 客户端身份 POST 的公共拼装（PC 扫码 / osx 播报共用）：这两条通道除了 host 与 UA
     * （身份差异，作参数传入）之外形态完全一致 —— 设备字段按 [QrLoginProtocol.cookieHeader] 拼成
     * Cookie，同一份再进加密 body 的 `header`，信封是「业务字段 → `e_r:false` → `header`」。
     *
     * （第三条身份通道 [eapiPostOfficial] 是 iPhone 身份、拼法另有历史，不并入这里。）
     */
    private fun eapiPostWithIdentity(
        host: String,
        ua: String,
        path: String,
        fields: Map<String, Any>,
        header: Map<String, String>
    ): Response {
        val fullUrl = host + "/eapi/" + path.removePrefix("/api/")
        val params = EapiCrypto.encryptParams(fullUrl, QrLoginProtocol.envelope(fields, header))

        val request = Request.Builder()
            .url(fullUrl)
            .post(FormBody.Builder().add("params", params).build())
            .header("User-Agent", ua)
            .header("Cookie", QrLoginProtocol.cookieHeader(header))
            .build()

        return plainClient.newCall(request).execute()
    }

    /**
     * PC 身份用的设备 id：复用 xeapi 的持久设备 id（一台设备一个 id）。
     * 没初始化 [XeapiState] 时兜底随机，但**进程内只生成一次**——逐请求换 deviceId 正是
     * 风控画像里「非真实设备」的特征（`spec/api/xeapi.md` §3.3）。
     */
    private val fallbackPcDeviceId: String by lazy {
        buildString(32) { repeat(32) { append("0123456789abcdef"[(0..15).random()]) } }
    }

    private fun pcDeviceId(): String =
        runCatching { XeapiState.deviceId() }.getOrNull() ?: fallbackPcDeviceId

    /** 登录 Cookie 串 → 键值对（同名后者覆盖前者）；eapi 身份构造要按字段取值。 */
    private fun cookieValues(cookie: String?): Map<String, String> =
        (cookie ?: "")
            .split(';')
            .asSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .map { it.split('=', limit = 2) }
            .associate { it[0].trim() to it.getOrElse(1) { "" } }

    /**
     * 逐行对齐官方/参考实现的 eapi 请求：
     *  - 发到 interface.music.163.com/eapi/...
     *  - 设备字段(osver/deviceId/os/appver/.../__csrf/MUSIC_U)以 **Cookie** 形式发送
     *    (createHeaderCookie 同款),非独立 HTTP 头
     *  - iOS UA；data.header 同时写入加密 body
     * 用于写接口(如 /eapi/radio/like)以彻底对齐客户端指纹,排除 -460 风控的字段形态差异。
     */
    fun eapiPostOfficial(
        path: String,
        payload: Map<String, String>,
        /** 非 null = 用调用方钉住的凭据（异步任务绑定原会话，禁止拿新账号兜底）。 */
        pinnedCookie: String? = null,
        pinnedGeneration: Long? = null,
    ): Response {
        val session = CookieManager.snapshot()
        if (pinnedGeneration != null && session.loginGeneration != pinnedGeneration) {
            throw IOException("session changed before eapi official $path")
        }
        val fullUrl = API_URL + path
        val effectiveCookie = pinnedCookie ?: session.cookie
        val csrf = getCsrfToken(effectiveCookie).orEmpty()
        val mus: Map<String, String> = (effectiveCookie ?: "")
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

        val request = Request.Builder().tag(NeteaseSessionSnapshot::class.java, session)
            .url(fullUrl)
            .post(requestBody)
            .header("User-Agent", IOS_UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", cookieStr)
            .build()

        return plainClient.newCall(request).execute()
    }

    /**
     * xeapi 公钥请求（明文表单，不走加密层）；解析、验签、落盘由 [XeapiState] 负责。
     * Cookie 只带 deviceId —— 这一步不涉及登录态，也不该把登录 cookie 掺进来。
     */
    internal fun xeapiPublicKeyRequest(formBody: String, deviceId: String): Response {
        // 公钥接口在 interface（API_URL），业务请求才在 interface3（spec/api/xeapi.md §2）。
        val request = Request.Builder()
            .url("$API_URL/api/bsr/sk/get")
            .post(formBody.toRequestBody(FORM_CONTENT_TYPE))
            .header("User-Agent", ANDROID_UA)
            .header("Cookie", "deviceId=$deviceId")
            .build()
        return plainClient.newCall(request).execute()
    }

    /**
     * xeapi 加密 POST —— 官方 Android 9.x 的请求层（协议全文 `spec/api/xeapi.md`）。
     *
     * 收藏写入走这里：老通道（weapi/eapi 的 `/api/radio/like`）写入的收藏会被服务端事后回滚
     * （「收藏隔天消失」，见 xeapi.md 末节）。
     *
     * 阻塞，调用方自己在 IO 线程调用。成功返回**已解密**的响应 JSON 文本；网络失败、公钥取不到
     * 或解密失败抛异常，由调用方决定是否回退到老通道。
     *
     * @param uri `/api/...` 形式的接口路径，实际打到 `interface3/xeapi/<去掉 /api/ 的路径>`。
     * @param pairs 业务字段，**顺序即协议**：明文信封的 `content` 按这个顺序做表单编码。
     * @param antiCheat 是否现取易盾 token 放 `X-antiCheatToken`（写入类接口需要；取不到发空串）。
     */
    fun xeapiPost(
        uri: String,
        pairs: List<Pair<String, String>>,
        antiCheat: Boolean,
        /** 非 null = 钉住的登录代次；与当前会话不一致时拒绝发送（见 S18）。 */
        pinnedGeneration: Long? = null,
    ): String {
        val session = CookieManager.snapshot()
        if (pinnedGeneration != null && session.loginGeneration != pinnedGeneration) {
            throw IOException("session changed before xeapi $uri")
        }
        val url = XEAPI_URL + "/xeapi/" + uri.removePrefix("/api/")
        // 易盾 token 每次写入现取、不缓存（xeapi.md §5）。
        val antiCheatToken = if (antiCheat) fetchAntiCheatToken() else null

        val bodyPairs = if (antiCheatToken != null) pairs + ("checkToken" to antiCheatToken) else pairs
        val first = executeXeapi(url, bodyPairs, XeapiState.ensureKeyState(), antiCheatToken, session)
        decryptXeapiBody(first)?.let { return it }

        // 解不开通常意味着服务端换了公钥：按「旧 version」重取一次再试。只重试一次——
        // 写入接口的重放本身就是风控特征，不能在这里死磕。
        if (!CookieManager.isCurrent(session)) throw IOException("xeapi session changed")
        Log.w(TAG, "xeapi response decrypt failed, refreshing public key (uri=$uri)")
        XeapiState.invalidateKeyState()
        val second = executeXeapi(url, bodyPairs, XeapiState.ensureKeyState(), antiCheatToken, session)
        return decryptXeapiBody(second) ?: throw IOException("xeapi response decrypt failed: $uri")
    }

    private fun executeXeapi(
        url: String,
        pairs: List<Pair<String, String>>,
        keyState: XeapiCrypto.KeyState,
        antiCheatToken: String?,
        session: NeteaseSessionSnapshot,
    ): ByteArray {
        val deviceId = XeapiState.deviceId()
        val buildver = (System.currentTimeMillis() / 1000).toString().take(10)
        val body = XeapiCrypto.encryptRequest(
            pairs = pairs,
            keyState = keyState,
            os = XEAPI_OS,
            session = XeapiState.session(),
            rng = XeapiState.newRng()
        ).body

        // 设备字段要「同名以后者为准」地压过登录 cookie 里的旧值：先收成 map 再覆盖，
        // 同一个键只出现一次，免得服务端读到登录时留下的旧 os/appver/deviceId。
        val cookiePairs = LinkedHashMap<String, String>()
        (session.cookie ?: "").split(';').forEach { part ->
            val sep = part.indexOf('=')
            if (sep > 0) cookiePairs[part.substring(0, sep).trim()] = part.substring(sep + 1).trim()
        }
        val musicU = cookiePairs["MUSIC_U"]
        cookiePairs["os"] = XEAPI_OS
        cookiePairs["osver"] = XEAPI_OSVER
        cookiePairs["appver"] = XEAPI_APPVER
        cookiePairs["buildver"] = buildver
        cookiePairs["deviceId"] = deviceId
        cookiePairs["sDeviceId"] = deviceId

        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(FORM_CONTENT_TYPE))
            .header("User-Agent", ANDROID_UA)
            .header("X-Client-Enc-State", "ENCRYPTED")
            .header("x-aeapi", "true")
            .header("x-deviceid", deviceId)
            .header("x-sdeviceid", deviceId)
            .header("x-os", XEAPI_OS)
            .header("x-osver", XEAPI_OSVER)
            .header("x-appver", XEAPI_APPVER)
            .header("x-buildver", buildver)
            .header("Cookie", cookiePairs.entries.joinToString("; ") { "${it.key}=${it.value}" })
        // 已登录时 x-music-u 与 Cookie 里的 MUSIC_U 同值（xeapi.md §3.3）。
        if (!musicU.isNullOrEmpty()) builder.header("x-music-u", musicU)
        if (antiCheatToken != null) builder.header("X-antiCheatToken", antiCheatToken)

        return plainClient.newCall(builder.build()).execute().use { response ->
            // 响应头带 x-encr-ssid/x-encr-sskey 时记下，之后的请求走会话模式（只存内存）。
            CookieManager.ifCurrent(session) {
                XeapiState.updateSession(response.header("x-encr-ssid"), response.header("x-encr-sskey"))
            }
            val raw = response.body?.bytes() ?: ByteArray(0)
            // 脱敏日志：只记路径、HTTP 码与长度，不记 cookie / 易盾 token / 加密体。
            Log.i(TAG, "xeapi $url http=${response.code} bytes=${raw.size}")
            raw
        }
    }

    /**
     * 解密 xeapi 响应体。解不开时若原文本身就是 JSON（服务端在参数错 / 风控时会直接回未加密
     * JSON），照原样返回让调用方拿得到业务码；否则返回 null，交给上层刷新公钥后重试。
     */
    private fun decryptXeapiBody(raw: ByteArray): String? {
        if (raw.isEmpty()) return null
        runCatching { XeapiCrypto.decryptResponse(raw) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val plain = String(raw, Charsets.UTF_8).trim()
        return plain.takeIf { it.startsWith("{") || it.startsWith("[") }
    }

    /**
     * 易盾反作弊 token：`GET .../v3/b?pn=...` → `null([200,<n>,"<token>"])`，取第三项。
     * 取不到返回空串——缺 token 只是少一层客户端指纹，不该阻断写入。
     */
    private fun fetchAntiCheatToken(): String {
        return try {
            val request = Request.Builder().url(ANTI_CHEAT_URL).header("User-Agent", ANDROID_UA).build()
            antiCheatClient.newCall(request).execute().use { response ->
                val match = ANTI_CHEAT_RE.find(response.body?.string().orEmpty())
                when {
                    match == null -> {
                        Log.w(TAG, "antiCheat token unparseable (http=${response.code})")
                        ""
                    }
                    match.groupValues[1] != "200" -> {
                        Log.w(TAG, "antiCheat code=${match.groupValues[1]}")
                        ""
                    }
                    else -> match.groupValues[2]
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "antiCheat token request failed: ${e.message}")
            ""
        }
    }

    /**
     * weapi 加密 POST。用于受保护但走 weapi 的接口。
     * 参考官方 weapi：POST 路径为 `/weapi/<去掉 /api/ 前缀>`（并非 /api/…），payload 需
     * 注入 `csrf_token`（来自 Cookie 的 __csrf）。session Cookie 由上层传入。
     */
    fun weapiPost(
        path: String,
        payloadJson: String,
        extraCookies: String? = null,
        cookieOverride: String? = null
    ): okhttp3.Response {
        val session = CookieManager.snapshot()
        val credential = cookieOverride ?: session.cookie
        val rawPayload = try { JSONObject(payloadJson) } catch (_: Exception) { JSONObject() }
        getCsrfToken(credential)?.let {
            if (it.isNotEmpty() && !rawPayload.has("csrf_token")) rawPayload.put("csrf_token", it)
        }
        val (params, encSecKey) = WeapiCrypto.encryptParams(rawPayload.toString())
        val weapiPath = if (path.startsWith("/api/")) "/weapi/" + path.removePrefix("/api/") else path
        val fullUrl = "https://music.163.com" + weapiPath
        val requestBody = FormBody.Builder()
            .add("params", params)
            .add("encSecKey", encSecKey)
            .build()
        val builder = Request.Builder().tag(NeteaseSessionSnapshot::class.java, if (cookieOverride == null) session else null)
            .url(fullUrl)
            .post(requestBody)
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Origin", "https://music.163.com")
        // 未登录时不发空 Cookie 头, 与官方网页/参考实现一致；extraCookies 供需要额外
        // 会话 cookie 的接口追加字段（扫码登录已改走 eapiPostPc, 不再用这条通道）。
        val cookieHeader = listOfNotNull(
            credential?.takeIf { it.isNotBlank() },
            extraCookies?.takeIf { it.isNotBlank() }
        ).joinToString("; ")
        if (cookieHeader.isNotEmpty()) builder.header("Cookie", cookieHeader)
        return plainClient.newCall(builder.build()).execute()
    }

    fun get(path: String, useInterface: Boolean = false, cookieOverride: String? = null): String {
        val session = CookieManager.snapshot()
        val host = if (useInterface) INTERFACE_URL else BASE_URL
        val request = Request.Builder().tag(NeteaseSessionSnapshot::class.java, if (cookieOverride == null) session else null)
            .url(host + path)
            .get()
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", cookieOverride ?: session.cookie.orEmpty())
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
    fun getCsrfToken(cookie: String? = currentCookie): String? {
        cookie ?: return null
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
            val session = CookieManager.snapshot()
            val cookie = session.cookie
            val newRequest = if (!cookie.isNullOrBlank()) {
                originalRequest.newBuilder()
                    .tag(NeteaseSessionSnapshot::class.java, session)
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
