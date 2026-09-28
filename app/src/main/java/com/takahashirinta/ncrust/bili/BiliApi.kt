/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · B：B 站音源的网络层。
 */

package com.takahashirinta.ncrust.bili

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

/**
 * B 站接口（v3.1.0 · B）。
 *
 * ## ⚠️ 为什么它有**自己的** `OkHttpClient`，而不复用 `RetrofitClient`
 *
 * 这不是洁癖，是**实测**逼出来的：探针在 S6 与模拟器上做了 A/B 对照
 * （`docs/verification/v3.1.0/net-research/EVIDENCE-S6.md` §4）——
 *
 * ```
 * variant=A_netease_referer  referer=https://music.163.com/      http=403  body=<!DOCTYPE HTML ...
 * variant=B_bili_referer     referer=https://www.bilibili.com/   http=200  body={"code":-101,"message":"账号未登录",...}
 * variant=C_no_referer       （不带 Referer）                     http=200  body={"code":-101,...}
 * ```
 *
 * 也就是说：**拿网易云的 `Referer` 去请求 B 站，会被 B 站的 WAF 直接挡在 403**，
 * 而 `RetrofitClient` 的 `CookieInterceptor` 与 `eapiPost` / `get` 全都**无条件**注入
 * 网易云的 Referer + UA + Cookie。复用那套等于「B 站音源永远 403」。
 *
 * 顺带还买到两件事：
 * - **B 站不会收到网易云的 Cookie**（隐私上的正确做法：两个平台的登录态不该互相外泄）；
 * - B 站的 412 / -352 风控不会影响网易云那条链路（铁律 27：不得破坏现有音源行为）。
 *
 * ## 音频区**没有搜索接口**（实测证伪的任务前提，见下）
 *
 * 任务书假设「音频区（au）可以搜索」。探针把这条路走到底了：
 *
 * | 试探 | 结果 |
 * |---|---|
 * | `/audio/music-service-c/web/song/search` | **404** |
 * | `/audio/music-service-c/web/search` | **404** |
 * | `/audio/music-service-c/web/song/search/type` | **404** |
 * | `/audio/music-service-c/web/menu/search` | 200 但 `data:null`（不是搜索接口） |
 * | `x/web-interface/wbi/search/type?search_type=audio|music|au|song` | `code:-1200 被降级过滤的请求` |
 * | `search_type=video` + `tids=3`（音乐区） | 200，但 30/30 条都是 `type:"video"`，**没有 `audio` 条目** |
 * | `song/info?bvid=` / `?aid=`（视频→音频的桥） | `code:4511001 音频未找到或已下架` |
 *
 * ⇒ **B 站上没有「按关键词搜到音频区曲目」这件事。** 能搜到的只有视频，
 * 而视频只有 DASH 音轨可提取。所以本实现是两条腿（见 [BiliTrack] 的 KDoc）：
 * 搜索走视频、取词走音频区，各自都是平台上真实存在的能力，**没有编造**。
 *
 * ## Wbi 签名（铁律 25）
 *
 * `/x/web-interface/wbi/search/type` 不带签名时返回 **HTTP 412**（实测），
 * 所以签名是必需的，实现在 [BiliWbi]。密钥来自 `nav` 的 `wbi_img`，
 * 服务端每天轮换 ⇒ 本类按 [WBI_KEY_TTL_MS] 缓存，并在 412 / -403 时**强制刷新一次**。
 *
 * ## ⚠️ v3.2.0 · P0-C：**每一个对外方法都是 `suspend`，且内部换到 [Dispatchers.IO]**
 *
 * 这一条修的是一个**只有真机才暴露**的缺陷（v3.1.0 的 P0-C「B 站搜不到歌」）：
 *
 * - v3.1.0 的对外方法全是普通函数，内部 `client.newCall(request).execute()`（同步阻塞）；
 * - 而它们的调用点有**至少三处落在主线程上**：
 *   `SearchViewModel.searchByType`（`viewModelScope` = `Dispatchers.Main.immediate` 里的 `async`）、
 *   `PlayerViewModel.startAuxiliaryLoad`（`viewModelScope.launch`，无 dispatcher）、
 *   `PlaybackService.onAddMediaItems`（`scope = Dispatchers.Main + SupervisorJob()`）；
 * - Android 对 `targetSdk >= 11` 的进程在主线程上启用 `StrictMode.enableDeathOnNetwork()` ⇒
 *   主线程上任何 socket 操作直接抛 `NetworkOnMainThreadException`。
 *   而 [wbiKeys] 里那句 `runCatching { get(NAV_URL) }.getOrNull()` 会把它**静默吞掉**，
 *   于是表现为「B站 0 首、无任何错误」——一个不会报错、只会静默失效的实现。
 *
 * ### 为什么是「改 suspend + 内部 `withContext(IO)`」，而不是别的三种做法
 *
 * | 方案 | 为什么不行 |
 * |---|---|
 * | 在**调用点**各自 `withContext(IO)` | 调用点有 9 处以上（见 `probe-bili-search.md` §4 的矩阵），**漏一处就复发**；本 P0 的成因正是「有一个调用点没想到」 |
 * | 保持普通函数，内部 `runBlocking { withContext(IO) }` | **照样阻塞调用线程**，主线程上还会把事件循环一起卡住（`runBlocking` 在主线程上跑自己的事件循环，但调用它的那一帧仍然被占住）|
 * | 新加一套 `…Async()` 方法 | 同一个能力有两条路 = 两条路都要维护，且旧的那条永远有人调 |
 *
 * 改成 `suspend` 之后有两条**结构性**收益：① 阻塞的那段代码物理上只存在于
 * `withContext(Dispatchers.IO)` 的块里，任何调用者（包括现在写错的、将来新写的）
 * 都不可能让它跑在主线程上；② 编译器会把**每一个**调用点都指出来（含 androidTest 探针），
 * 「忘了改调用点」不可能悄悄通过。
 *
 * ### 取消语义（与调度同源的一条）
 *
 * 换线程之后，`withContext` 是一个**真的挂起点** ⇒ 调用方（`withTimeoutOrNull(4000)`、
 * `searchJob.cancel()`）的取消终于能生效。所以每个 `catch` 都必须**原样抛出**
 * [CancellationException]，否则「超时」会被吞成「真的 0 条」——
 * 那正是 v2.5.5 花了一整版修出来的「PENDING/TIMEOUT 不能显示成 DONE+0」的同一件事。
 */
object BiliApi {

    private const val TAG = "BiliApi"

    private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"
    private const val SEARCH_URL = "https://api.bilibili.com/x/web-interface/wbi/search/type"
    private const val VIEW_URL = "https://api.bilibili.com/x/web-interface/view"
    /**
     * ⚠️ **必须用旧路径 `/x/player/playurl`，不能用 `/x/player/wbi/playurl`。**
     *
     * 实测（2026-09-28，直连，同一 bvid/cid）：
     * - `/x/player/wbi/playurl` + **正确签名** `w_rid` ⇒ **HTTP 412**（3286 字节 HTML）；
     *   无签名 / 伪造签名 / 不带 Cookie 四种组合**全部 412**，带正确签名的那次还直接挂住不返回
     *   ⇒ 这是**路径级封禁**，不是签名写错；
     * - `/x/player/playurl` + `fnval=4048` ⇒ `code:0` + 完整 DASH（3 条 audio，
     *   带宽 43962 / 102931 / 203786）。
     *
     * `fnval=4048` 与 `fnval=16` 的区别是它同时请求 DASH + 各种特性位；对音频提取没有副作用，
     * 而旧路径**不接受** wbi 签名（带了也无害，见 [videoAudioStream] 走的是普通 GET）。
     */
    private const val PLAYURL_URL = "https://api.bilibili.com/x/player/playurl"

    /**
     * DASH 的 `fnval`。**4048 = DASH + 一堆特性位**，是社区与实测都拿到完整
     * `dash.audio[]` 的取值；`fnval=16`（纯 DASH）在旧路径上同样可用，
     * 但 4048 是实测过的那个，不留"看起来等价"的第二个选择。
     */
    private const val PLAYURL_FNVAL = "4048"
    private const val AUDIO_INFO_URL = "https://www.bilibili.com/audio/music-service-c/web/song/info"
    /**
     * ⚠️ **必须用 APP 端点 `/audio/music-service-c/url`，不是 web 端点 `/web/url`。**
     *
     * 实测（2026-09-28，同一首 au39）：
     *
     * | 端点 | 请求 | 结果 |
     * |---|---|---|
     * | `/web/url?sid=39&quality=0..3` | qn 四个取值 | **全部返回同一个 `-192k.m4a`**（忽略 quality） |
     * | `/url?songid=39&quality=2&platform=pc` | 匿名 | **320K**（`type:2`，`size` 10374528，ffprobe 实测 321584 bps） |
     *
     * 主键参数名也不同：APP 端点是 **`songid`**（传 `sid` 会得到
     * `{"code":72000000,"msg":"param missing error: songid"}`）。
     * 响应里还带一张完整的 `qualities[]`（320k/192k/128k，`require` 全 0，
     * **没有 type:3** ⇒ 匿名拿不到 FLAC，`quality=3` 被静默降级成 320K）。
     */
    private const val AUDIO_URL = "https://api.bilibili.com/audio/music-service-c/url"

    /** 匿名指纹接口：`data.b_3` 就是 `buvid3`。**只给旧版 playurl 用**（见 [playUrlFor]）。 */
    private const val FINGER_URL = "https://api.bilibili.com/x/frontend/finger/spi"
    private const val AUDIO_LYRIC_URL = "https://www.bilibili.com/audio/music-service-c/web/song/lyric"

    /** 手机端 Web UA。用 PC 的桌面 UA 会被某些风控策略区别对待（实测 nav 无差别，取保守值）。 */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** B 站自己的 Referer。**绝不能是网易云那个** —— 见类文档的 A/B 对照。 */
    private const val REFERER = "https://www.bilibili.com/"

    /** wbi 密钥的缓存时长。服务端每天轮换，取 6 小时（一天内至少刷新一次，且不会每通请求都问）。 */
    private const val WBI_KEY_TTL_MS = 6 * 60 * 60 * 1000L

    /**
     * 独立客户端。
     *
     * 超时与 `RetrofitClient` **同口径**（connect 30s / read 30s / **无 callTimeout**）：
     * 铁律 19/20 —— 这套数字是本项目在真机上验证过的，B 站这条链路没有必要引入第二套。
     * 但**没有** `CookieInterceptor`、**没有** `eventListenerFactory`（B 站的耗时不在本版
     * 的性能结论里，少一个观测点就少一份开销）。
     */
    private val realClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 只给单测/探针：换掉传输层（默认 `null` = 真的 OkHttp）。
     *
     * ## 为什么需要它，而不是「测不动就不测」
     *
     * P0-C 的判据是「**阻塞的那段代码不在调用线程上跑**」。这件事只有两种验证方式：
     * ① 源码扫描（断言 `suspend` + `withContext(Dispatchers.IO)` 的形状）；
     * ② **行为**验证 —— 注入一个什么都不做的假传输层，记录它被调用时所在的线程名。
     *
     * ② 比 ① 强，因为它测的是「真的换线程了」而不是「代码长成那个样子」，
     * 而且完全离线（假传输层直接返回罐头响应，不发一个字节）。所以留这个口子。
     *
     * 与 [clearWbiKeysForTest] / [clearBuvidForTest] 同一类：**只给单测**，
     * 生产代码里没有任何写入点（`clientForTest` 只在 `app/src/test` 与 `app/src/androidTest` 里被赋值）。
     */
    @Volatile
    internal var clientForTest: OkHttpClient? = null

    private val client: OkHttpClient get() = clientForTest ?: realClient

    // ---------------------------------------------------------------- wbi 密钥缓存 ----

    @Volatile private var wbiImgUrl: String? = null
    @Volatile private var wbiSubUrl: String? = null
    @Volatile private var wbiFetchedAtMs: Long = 0L

    /** 只给单测/诊断用：当前是否有一份可用的 wbi 密钥。 */
    fun hasWbiKeys(): Boolean = !wbiImgUrl.isNullOrEmpty() && !wbiSubUrl.isNullOrEmpty()

    /** 只给单测用：清掉缓存。 */
    internal fun clearWbiKeysForTest() {
        wbiImgUrl = null
        wbiSubUrl = null
        wbiFetchedAtMs = 0L
    }

    /**
     * 拿一份可用的 `(img_url, sub_url)`。
     *
     * ⚠️ **只能在 `withContext(Dispatchers.IO)` 的块里调用**（v3.2.0 · P0-C）——
     * 它会走一次真实网络（[get]）。本类的每一个对外方法都已经这样包好了，
     * 新增调用点时请照抄那七处的形状。
     *
     * @param force 忽略 TTL 强制刷新（412 / -403 之后调用一次）。
     */
    private fun wbiKeys(force: Boolean = false): Pair<String, String>? {
        val fresh = System.currentTimeMillis() - wbiFetchedAtMs < WBI_KEY_TTL_MS
        if (!force && fresh && hasWbiKeys()) return wbiImgUrl!! to wbiSubUrl!!
        // ⚠️ v3.2.0 · P0-C：这里的 `runCatching` 是 v3.1.0「B站 0 首」的最后一环 ——
        // 它把「主线程不允许做网络」的 NetworkOnMainThreadException 与「nav 没给 wbi_img」
        // 折叠成同一个 null，调用方再也看不出区别。现在调度已经在 [withContext] 里解决，
        // 这里**保留**兜底（契约是绝不抛），但把异常**打出来**而不是无声吞掉。
        val body = try {
            get(NAV_URL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "nav 请求失败（拿不到 wbi 密钥，本轮搜索不会发出）", e)
            return null
        }
        val keys = BiliParse.parseNavWbiKeys(body)
        if (keys == null) {
            Log.w(TAG, "nav 没有返回 wbi_img（响应长度=${body.length}）")
            return null
        }
        wbiImgUrl = keys.first
        wbiSubUrl = keys.second
        wbiFetchedAtMs = System.currentTimeMillis()
        Log.i(TAG, "wbi keys refreshed: mixin=${BiliWbi.mixinKey(keys.first, keys.second).take(8)}…")
        return keys
    }

    // ---------------------------------------------------------------- 基础请求 ----

    /**
     * 一次同步 GET。
     *
     * ⚠️ **只能在 `withContext(Dispatchers.IO)` 的块里调用**（v3.2.0 · P0-C）：
     * `execute()` 是**同步阻塞**的，落在主线程上会被 Android 的
     * `StrictMode.enableDeathOnNetwork()` 直接拒绝（`NetworkOnMainThreadException`）。
     * 它是 `private` 的，就是为了让「谁在什么线程上调它」在本文件里一眼看完。
     */
    private fun get(url: String, cookie: String? = null): String {
        // v3.2.0 · P1：**登录之后每一个 B 站请求都带上 SESSDATA**。
        // 这是登录唯一有意义的落点 —— 音频区取流按身份给档位（大会员才有 FLAC），
        // 收藏夹/投币那类端点按身份返回。凭据来自 `BiliAuthStore` 的进程内镜像，
        // 而镜像为空（未登录 / 未 init）时 `requestCookieHeader()` 返回 null ⇒
        // 这条链路的**匿名行为与 v3.1.0 逐字一致**（一个 Cookie 都不带）。
        val cookieHeader = BiliAuthStore.mergeCookieHeaders(
            BiliAuthStore.requestCookieHeader(),
            cookie?.takeIf { it.isNotBlank() }?.let { "buvid3=$it" },
        )
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .header("Origin", "https://www.bilibili.com")
        // 只有旧版 playurl 需要指纹 Cookie（见 [buvid3]）；匿名时其余请求一个 Cookie 都不带 ——
        // 带上一个孤立的指纹只会让指纹在不同接口间不一致。
        if (cookieHeader != null) builder.header("Cookie", cookieHeader)
        val request = builder.build()
        client.newCall(request).execute().use { resp ->
            // 失败也要把正文带出去：B 站的错误是**结构化**的（`code` 字段），
            // 只看 HTTP 状态码会把「-1200 被降级过滤」和「网络挂了」混成一件事故。
            return resp.body?.string().orEmpty()
        }
    }

    private fun post(url: String, body: String): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .header("Origin", "https://www.bilibili.com")
            .build()
        client.newCall(request).execute().use { resp ->
            return resp.body?.string().orEmpty()
        }
    }

    // ---------------------------------------------------------------- 原始应答（v3.2.0 · P1） ----

    /**
     * 一次 GET 的原始应答。**存在的唯一理由：扫码登录要读 `Set-Cookie`。**
     *
     * 轮询 `passport.bilibili.com/x/passport-login/web/qrcode/poll` 成功时，凭据在
     * **响应头**里（`Set-Cookie: SESSDATA=…`），而 [get] 只返回正文 —— 那不是"再解析一次"
     * 能补上的信息，headers 在 `resp.body.string()` 之后就没了。所以这里把三样一起带出来。
     */
    internal data class BiliRawResponse(
        val httpCode: Int,
        val body: String,
        /** 全部 `Set-Cookie` 头的原样文本（**没有**预先拆分/解码，见 `BiliAuthApi.credentialFromSetCookie`）。 */
        val setCookies: List<String>,
    )

    /**
     * 带**完整 Cookie 头**的 GET（v3.2.0 · P1 · 只给 `BiliAuthApi` 的扫码登录用）。
     *
     * 与 [get] 的区别只有两点，其余（独立 client / UA / Referer / Origin / 超时）**逐字相同** ——
     * 复用同一个 `client` 是硬要求：v3.1.0 实测「拿网易云 Referer 请求 B 站 ⇒ 403」，
     * 再建第二个 client 就是把那条已被证伪的路又铺一遍。
     *
     * @param cookieHeader 直接写进 `Cookie` 头的整串文本（`SESSDATA=…; bili_jct=…`）。
     *   为 null 时**一个 Cookie 都不带**。
     *
     * ⚠️ 与 [get] 的一处**有意的**不同：本方法**不注入登录态镜像**。扫码登录自己就是在
     * 建立一份新身份，把旧的 `SESSDATA` 发给 `passport.bilibili.com` 只会多一个泄露面，
     * 而且换账号登录时旧身份根本不适用。业务请求（搜索/取流/歌词/详情）走 [get]，那里才注入。
     */
    internal suspend fun getRaw(url: String, cookieHeader: String? = null): BiliRawResponse =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .header("Origin", "https://www.bilibili.com")
            if (!cookieHeader.isNullOrBlank()) builder.header("Cookie", cookieHeader)
            client.newCall(builder.build()).execute().use { resp ->
                BiliRawResponse(
                    httpCode = resp.code,
                    body = resp.body?.string().orEmpty(),
                    // 只把 `Set-Cookie` 挑出来：其它头（bili-trace-id 之类）与凭据无关。
                    setCookies = resp.headers.values("Set-Cookie"),
                )
            }
        }

    /** 带 wbi 签名的 GET；遇到 412 / -403 时**强制刷新一次密钥**再试一遍（失败处理有界：只重试一次）。
     *
     * ⚠️ **只能在 `withContext(Dispatchers.IO)` 的块里调用**（同 [get] 的理由）。 */
    private fun signedGet(baseUrl: String, params: Map<String, String>): String {
        fun attempt(force: Boolean): String {
            val keys = wbiKeys(force) ?: return ""
            val query = BiliWbi.buildQuery(params, keys.first, keys.second)
            return get("$baseUrl?$query")
        }
        var body = attempt(force = false)
        if (isSignatureRejected(body)) {
            Log.i(TAG, "wbi 被拒（412/-403），强制刷新密钥后重试一次")
            body = attempt(force = true)
        }
        return body
    }

    /**
     * 签名是否被服务端拒绝。
     *
     * 判据是**两类**，缺一不可：
     * 1. HTTP 层拿不到结构化响应 —— 412 的正文是 HTML，解析不出 `code`；
     * 2. 结构化响应里的 `code` 是 `-352`（**风控校验失败**，实测「缺签名 / 错签名」
     *    就是这一档，且 HTTP 状态是 200）、`-403`（访问权限不够）、`-1200`（被降级过滤）。
     *
     * ## 为什么 `-352` 必须在这里（漏了它的后果是静默的）
     *
     * wbi 密钥每天轮换。轮换之后**旧密钥仍然能算出一个 `w_rid`** —— 请求发得出去、
     * HTTP 也是 200，只是 `code:-352`、`data` 为空。若不认这一档，
     * 表现就是「B 站搜索从某一天起永远是 0 条」，而且要等到 6 小时的密钥 TTL
     * 自然到期才可能恢复。认了它，[signedGet] 会**立刻**强制刷新一次密钥再试。
     */
    internal fun isSignatureRejected(body: String?): Boolean {
        val text = body.orEmpty()
        // 非 JSON（412 的 HTML 错误页）**不算签名问题**：412 是**路径级封禁**，
        // 重签一次密钥不会有任何变化（实测四种签名/Cookie 组合全部 412）。
        // 把它归到签名失败只会白白多打一次 nav。
        if (text.isBlank() || !text.trimStart().startsWith("{")) return false
        val code = runCatching { org.json.JSONObject(text).optInt("code", 0) }.getOrDefault(0)
        // -352 = 风控校验失败（缺签名/错签名的真实表现，HTTP 200）；-403 = 访问权限不够。
        // `-1200`（被降级过滤）**不在此列**：实测非法 search_type 与越界翻页也返回它，
        // 那不是签名问题 —— 重签只会重复同一次失败。
        return code == -352 || code == -403
    }

    /**
     * 取音频区曲目的 **LRC 歌词正文**。
     *
     * ## 为什么不能直接用 `song/info` 的 `lyric` 字段
     *
     * 实测（`bili-research/evidence/02-songinfo-au39.txt`）：
     * `song/info` 的 `lyric` 是**一个 LRC 文件的 URL**
     * （`http://i0.hdslb.com/bfs/music/149994607539.lrc`），**不是歌词正文**；
     * 而且大部分曲目这个字段是空串。
     *
     * 把它当正文喂给 `LrcParser` 的结果是解析出 0 行 ⇒ 界面永远「暂无歌词」——
     * 一个不会报错、只会静默失效的实现。
     *
     * 真正的歌词在 `/audio/music-service-c/web/song/lyric?sid=`：实测 `data`
     * **直接就是 LRC 文本**（`[00:33.26]让我掉下眼泪的
…`）。
     *
     * @return LRC 正文；服务端没有这份数据时返回 **null**（「没有这个数据源」），
     *   有但为空时返回**空串**（「这首歌确实没有歌词」）—— 这个两义性与
     *   `LyricLoadCoordinator.State` 的 Empty / Error 之分是同一条纪律。
     *
     * ⚠️ v3.2.0 · P0-C：`suspend` + 内部换到 [Dispatchers.IO]（见类文档）。
     * 播放链的取词入口（`PlayerViewModel.startAuxiliaryLoad`）就在主线程上，
     * 这条曾经是 P0-C 的第二个落点。
     */
    suspend fun audioLyric(auid: Long): String? {
        if (auid <= 0L) return null
        val body = withContext(Dispatchers.IO) {
            try {
                get("$AUDIO_LYRIC_URL?sid=$auid")
            } catch (e: CancellationException) {
                // 取消是控制流不是失败：吞掉它会让「超时」显示成「这首歌没有歌词」。
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "lyric failed: $auid", e)
                null
            }
        } ?: return null
        return BiliParse.parseAudioLyric(body)
    }

    // ---------------------------------------------------------------- 搜索（视频） ----

    /**
     * 关键词搜索。**这是 B 站唯一可用的搜索**（音频区没有搜索接口，见类文档）。
     *
     * @param limit 期望条数。服务端一页最多 20/50，这里按 `page_size` 请求并按 [limit] 截断。
     *   只取**第一页** —— 本应用是「聚合搜索的一个补充源」，翻页不在本版范围内。
     *
     * ⚠️ v3.2.0 · P0-C：`suspend` + 内部换到 [Dispatchers.IO]。
     * 这是 P0-C 的**主犯**：v3.1.0 里它在 `SearchViewModel` 的 `Main.immediate` 作用域里被
     * **内联启动**，于是 `execute()` 直接在主线程上抛 `NetworkOnMainThreadException`，
     * 而异常被下面的 catch 吞成 `emptyList()` ⇒ 用户看到「B站 0 首」且没有任何错误。
     */
    suspend fun searchVideos(keyword: String, limit: Int): List<BiliTrack> {
        if (keyword.isBlank() || limit <= 0) return emptyList()
        val params = mapOf(
            "search_type" to "video",
            "keyword" to keyword,
            "page" to "1",
            "page_size" to limit.coerceIn(1, 50).toString(),
        )
        return withContext(Dispatchers.IO) {
            try {
                val body = signedGet(SEARCH_URL, params)
                val tracks = BiliParse.parseSearchTracks(body, limit)
                Log.i(TAG, "search '$keyword' -> ${tracks.size} 条（正文长度=${body.length}）")
                tracks
            } catch (e: CancellationException) {
                // ⚠️ 必须原样抛出：`withTimeoutOrNull(BILI_SEARCH_BUDGET_MS)` 的取消
                // 一旦被吞成 `emptyList()`，「超时」就会被记成「真的 0 条」——
                // 那正是 v2.5.5 修掉的「PENDING 被写成 DONE+0」的同一个形状。
                throw e
            } catch (e: Exception) {
                // 契约（MusicSourceProvider）：**绝不抛给调用方**。B 站挂掉不该让聚合搜索失败。
                Log.w(TAG, "search failed: $keyword", e)
                emptyList()
            }
        }
    }

    // ---------------------------------------------------------------- 视频音轨 ----

    /** `cid`（`playurl` 必需）。搜索结果里没有这个字段，要单独问一次 `view`。
     *
     * ⚠️ v3.2.0 · P0-C：`suspend` + 内部换到 [Dispatchers.IO]（车机点播路径曾在主线程上调它）。 */
    suspend fun videoCid(bvid: String): Long? = withContext(Dispatchers.IO) {
        try {
            BiliParse.parseCid(get("$VIEW_URL?bvid=$bvid"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "view failed: $bvid", e)
            null
        }
    }

    /**
     * 视频的**音频**流（DASH）。只取 `dash.audio[]`，永不取 `dash.video[]`。
     *
     * `fnval=4048` = 请求 DASH + 一组特性位（见 [PLAYURL_FNVAL]，实测过的那个取值）；
     * `fourk=1` 与画质无关（音轨只需要它不拒绝请求）。
     * **还要带匿名指纹**（[buvid3]）：实测这条旧路径不带 `buvid3` 会回 412。
     *
     * ⚠️ v3.2.0 · P0-C：`suspend` + 内部换到 [Dispatchers.IO]（见类文档的矩阵）。
     */
    suspend fun videoAudioStream(bvid: String, cid: Long): BiliStream? {
        if (bvid.isBlank() || cid <= 0L) return null
        // **普通 GET，不签名**，但要带匿名指纹 —— 见 [PLAYURL_URL] 与 [buvid3] 的实测说明。
        return withContext(Dispatchers.IO) {
            try {
                val body = get(playUrlFor(bvid, cid), cookie = buvid3())
                BiliParse.parseDashAudio(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "playurl failed: $bvid/$cid", e)
                null
            }
        }
    }

    // ---------------------------------------------------------------- 音频区 ----

    /** 音频区曲目详情（含 **LRC 歌词**）。失败/下架返回 null。
     *
     * ⚠️ v3.2.0 · P0-C：`suspend` + 内部换到 [Dispatchers.IO]（`au<auid>` 关键词搜索与
     * `songDetail` 都会走到它）。 */
    suspend fun audioInfo(auid: Long): BiliTrack? {
        if (auid <= 0L) return null
        return withContext(Dispatchers.IO) {
            try {
                BiliParse.parseAudioInfo(get("$AUDIO_INFO_URL?sid=$auid"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "audio info failed: $auid", e)
                null
            }
        }
    }

    /**
     * 音频区音频流。
     *
     * `quality` 就是 `qn`（0/1/2/3）。**换成 APP 端点之后阶梯是真的生效的**：
     * 实测匿名 qn=2 直接给 320K（`type:2`），qn=0/1 分别给 128K/192K，
     * 响应里的 `qualities[]` 三档 `require` 全为 0。
     *
     * FLAC（qn=3）匿名拿不到：`qualities[]` 里**没有 type:3** 条目，
     * 且 qn=3 会被服务端**静默降级**成 320K —— 这种降级由
     * `SongUrlResult.levelFromFile = true` 如实标出来（角标显示「已降级」），
     * 而不是按请求档位自欺。
     *
     * ⚠️ v3.2.0 · P0-C：`suspend` + 内部换到 [Dispatchers.IO]（见类文档的矩阵）。
     */
    suspend fun audioStream(auid: Long, qn: Int): BiliStream? {
        if (auid <= 0L) return null
        return withContext(Dispatchers.IO) {
            try {
                BiliParse.parseAudioStream(get(audioUrlFor(auid, qn)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "audio url failed: $auid qn=$qn", e)
                null
            }
        }
    }

    /**
     * 音频区取流的 URL。**抽出来是为了能被单测钉死**（同 [playUrlFor]）。
     *
     * 它防的是一次具体的、已经发生过的错：v3.1.0 的第一版用的是 web 端点
     * （`/web/url?sid=`），那个端点**完全忽略 `quality` 参数**、匿名一律给 192K ——
     * 于是「用户选了无损却永远只有 192K」，而且不报任何错。
     */
    internal fun audioUrlFor(auid: Long, qn: Int): String =
        "$AUDIO_URL?songid=$auid&quality=$qn&privilege=2&mid=0&platform=pc"

    /**
     * 播放地址请求的 URL。**抽出来是为了能被单测钉死。**
     *
     * 它防的是一次具体的、已经发生过的错：v3.1.0 的第一版走的是
     * `/x/player/wbi/playurl` + 签名，而那条路径实测**带正确签名也 412**
     * （见 [PLAYURL_URL] 的四种组合对照）。这种错不会让单测变红、只会让
     * 「B 站搜得到但放不出来」—— 所以把 URL 的形状本身变成一条断言。
     */
    internal fun playUrlFor(bvid: String, cid: Long): String =
        "$PLAYURL_URL?bvid=$bvid&cid=$cid&fnval=$PLAYURL_FNVAL&fnver=0&fourk=1"

    /**
     * 匿名指纹 `buvid3`（懒加载 + 缓存）。
     *
     * ## 为什么旧版 playurl **必须**带它
     *
     * 实测：`/x/player/playurl`（非 wbi 路径）不带 `buvid3` ⇒ **HTTP 412**；
     * 带上 ⇒ `code:0` + 3 条 DASH 音轨。它匿名就能取：
     * `GET /x/frontend/finger/spi` → `data.b_3`。
     *
     * 取不到时返回 null，请求照发（由服务端回 412）—— 那样失败是**可见的**；
     * 在这里静默放弃只会让「视频音轨放不出来」变成一个没有线索的现象。
     *
     * ⚠️ **只能在 `withContext(Dispatchers.IO)` 的块里调用**（v3.2.0 · P0-C，同 [get]）。
     */
    @Volatile private var buvid3: String? = null

    private fun buvid3(): String? {
        buvid3?.let { return it }
        val body = try {
            get(FINGER_URL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "finger failed", e)
            return null
        }
        val value = runCatching {
            org.json.JSONObject(body).optJSONObject("data")?.optString("b_3")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        buvid3 = value
        Log.i(TAG, "buvid3 acquired: ${value.take(8)}…")
        return value
    }

    /** 只给单测用：清掉指纹缓存。 */
    internal fun clearBuvidForTest() {
        buvid3 = null
    }

    /** 只给诊断用：B 站是否可达（一次极轻量的 nav 请求）。**不在任何热路径上。**
     *
     * ⚠️ v3.2.0 · P0-C：`suspend` + 内部换到 [Dispatchers.IO]。 */
    suspend fun probeReachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            get(NAV_URL).isNotBlank()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}
