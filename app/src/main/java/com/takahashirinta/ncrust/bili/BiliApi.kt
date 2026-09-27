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
 */
object BiliApi {

    private const val TAG = "BiliApi"

    private const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"
    private const val SEARCH_URL = "https://api.bilibili.com/x/web-interface/wbi/search/type"
    private const val VIEW_URL = "https://api.bilibili.com/x/web-interface/view"
    private const val PLAYURL_URL = "https://api.bilibili.com/x/player/wbi/playurl"
    private const val AUDIO_INFO_URL = "https://www.bilibili.com/audio/music-service-c/web/song/info"
    private const val AUDIO_URL = "https://www.bilibili.com/audio/music-service-c/web/url"

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
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

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
     * @param force 忽略 TTL 强制刷新（412 / -403 之后调用一次）。
     */
    private fun wbiKeys(force: Boolean = false): Pair<String, String>? {
        val fresh = System.currentTimeMillis() - wbiFetchedAtMs < WBI_KEY_TTL_MS
        if (!force && fresh && hasWbiKeys()) return wbiImgUrl!! to wbiSubUrl!!
        val body = runCatching { get(NAV_URL) }.getOrNull() ?: return null
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

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .header("Origin", "https://www.bilibili.com")
            .build()
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

    /** 带 wbi 签名的 GET；遇到 412 / -403 时**强制刷新一次密钥**再试一遍（失败处理有界：只重试一次）。 */
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
     * 判据是**两个**：HTTP 层拿不到结构化响应（412 的正文是 HTML，解析不出 `code`），
     * 或结构化响应里的 `code` 是 `-403`（访问权限不够）/ `-1200`（被降级过滤）。
     * 只看其中一个都会漏掉另一半。
     */
    internal fun isSignatureRejected(body: String?): Boolean {
        val text = body.orEmpty()
        if (text.isBlank()) return true
        if (!text.trimStart().startsWith("{")) return true
        val code = runCatching { org.json.JSONObject(text).optInt("code", 0) }.getOrDefault(0)
        return code == -403 || code == -1200
    }

    // ---------------------------------------------------------------- 搜索（视频） ----

    /**
     * 关键词搜索。**这是 B 站唯一可用的搜索**（音频区没有搜索接口，见类文档）。
     *
     * @param limit 期望条数。服务端一页最多 20/50，这里按 `page_size` 请求并按 [limit] 截断。
     *   只取**第一页** —— 本应用是「聚合搜索的一个补充源」，翻页不在本版范围内。
     */
    fun searchVideos(keyword: String, limit: Int): List<BiliTrack> {
        if (keyword.isBlank() || limit <= 0) return emptyList()
        val params = mapOf(
            "search_type" to "video",
            "keyword" to keyword,
            "page" to "1",
            "page_size" to limit.coerceIn(1, 50).toString(),
        )
        return try {
            val body = signedGet(SEARCH_URL, params)
            val tracks = BiliParse.parseSearchTracks(body, limit)
            Log.i(TAG, "search '$keyword' -> ${tracks.size} 条（正文长度=${body.length}）")
            tracks
        } catch (e: Exception) {
            // 契约（MusicSourceProvider）：**绝不抛给调用方**。B 站挂掉不该让聚合搜索失败。
            Log.w(TAG, "search failed: $keyword", e)
            emptyList()
        }
    }

    // ---------------------------------------------------------------- 视频音轨 ----

    /** `cid`（`playurl` 必需）。搜索结果里没有这个字段，要单独问一次 `view`。 */
    fun videoCid(bvid: String): Long? =
        runCatching { BiliParse.parseCid(get("$VIEW_URL?bvid=$bvid")) }
            .onFailure { Log.w(TAG, "view failed: $bvid", it) }
            .getOrNull()

    /**
     * 视频的**音频**流（DASH）。只取 `dash.audio[]`，永不取 `dash.video[]`。
     *
     * `fnval=16` = 请求 DASH；`fourk=1` 与画质无关（音轨只需要它不拒绝请求）。
     */
    fun videoAudioStream(bvid: String, cid: Long): BiliStream? {
        if (bvid.isBlank() || cid <= 0L) return null
        val params = mapOf(
            "bvid" to bvid,
            "cid" to cid.toString(),
            "fnval" to "16",
            "fnver" to "0",
            "fourk" to "1",
        )
        return try {
            val body = signedGet(PLAYURL_URL, params)
            BiliParse.parseDashAudio(body)
        } catch (e: Exception) {
            Log.w(TAG, "playurl failed: $bvid/$cid", e)
            null
        }
    }

    // ---------------------------------------------------------------- 音频区 ----

    /** 音频区曲目详情（含 **LRC 歌词**）。失败/下架返回 null。 */
    fun audioInfo(auid: Long): BiliTrack? {
        if (auid <= 0L) return null
        return runCatching { BiliParse.parseAudioInfo(get("$AUDIO_INFO_URL?sid=$auid")) }
            .onFailure { Log.w(TAG, "audio info failed: $auid", it) }
            .getOrNull()
    }

    /**
     * 音频区音频流。
     *
     * `quality` 就是 `qn`（0/1/2/3）。实测**匿名一律拿到 192K**（请求 qn=0/1/2/3 返回同一个
     * `-192k.m4a`），所以 [BiliQuality.fallbackLadder] 的逐级下探在匿名下不会真正生效 ——
     * 它保留是为了将来接登录态时不需要改结构。
     */
    fun audioStream(auid: Long, qn: Int): BiliStream? {
        if (auid <= 0L) return null
        val url = "$AUDIO_URL?sid=$auid&quality=$qn&privilege=2&mid=0&platform=web"
        return runCatching { BiliParse.parseAudioStream(get(url)) }
            .onFailure { Log.w(TAG, "audio url failed: $auid qn=$qn", it) }
            .getOrNull()
    }

    /** 只给诊断用：B 站是否可达（一次极轻量的 nav 请求）。**不在任何热路径上。** */
    fun probeReachable(): Boolean = runCatching { get(NAV_URL).isNotBlank() }.getOrDefault(false)
}
