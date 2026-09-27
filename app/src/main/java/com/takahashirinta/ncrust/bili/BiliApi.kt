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
    private const val AUDIO_URL = "https://www.bilibili.com/audio/music-service-c/web/url"
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
        if (text.isBlank()) return true
        if (!text.trimStart().startsWith("{")) return true
        val code = runCatching { org.json.JSONObject(text).optInt("code", 0) }.getOrDefault(0)
        return code == -352 || code == -403 || code == -1200
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
     */
    fun audioLyric(auid: Long): String? {
        if (auid <= 0L) return null
        val body = runCatching { get("$AUDIO_LYRIC_URL?sid=$auid") }.getOrNull() ?: return null
        return BiliParse.parseAudioLyric(body)
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
        // **普通 GET，不签名** —— 见 [PLAYURL_URL] 的实测说明（wbi 路径是封禁的）。
        return try {
            val body = get(playUrlFor(bvid, cid))
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

    /** 只给诊断用：B 站是否可达（一次极轻量的 nav 请求）。**不在任何热路径上。** */
    fun probeReachable(): Boolean = runCatching { get(NAV_URL).isNotBlank() }.getOrDefault(false)
}
