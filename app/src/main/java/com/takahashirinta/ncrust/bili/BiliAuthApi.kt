/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P1：B 站扫码登录的三个接口 —— **纯转发 + 解析**。
 */

package com.takahashirinta.ncrust.bili

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 一张 B 站登录二维码：`url` 是**二维码内容**（渲染成二维码让手机扫），`qrcodeKey` 是轮询秘钥。 */
data class BiliQrCode(
    val url: String,
    val qrcodeKey: String,
)

/**
 * 一次轮询的结局。**密封类型，不是 `String?`**（任务书要求，也是本项目一贯的形状）。
 *
 * 与 `QqQrLogin.PollResult` 的分法同源：
 * - [Success] / [Scanned] / [Pending] / [Expired] 是**服务端明确回答**的四种状态；
 * - [Failed] 把「服务端回了一段看不懂的东西」与「请求本身没成功」分开（[Reason]），
 *   因为前者重试多少次都一样、后者下一轮大概率就好 —— v2.1.0 把两者折叠成 `null`，
 *   结果是一个恒定的 403 被显示成「网络不稳定，仍在重试…」。
 */
sealed class BiliQrPoll {

    /**
     * `data.code == 0`：**扫码并确认成功**。
     *
     * 凭据里已经合并了两个载体（`Set-Cookie` 与 `data.url` 的 query），见 [BiliCredential.merge]。
     * **不带跨域登录 URL**：`https://passport.biligame.com/crossDomain?…&SESSDATA=…&bili_jct=…`
     * 把两个凭据明文写在 query 里，把它留在内存里只会多一个泄露点，而本应用不做 WebView 跳转，
     * 用不到它（需要的话重新扫一次即可）。
     */
    data class Success(
        val credential: BiliCredential,
        /** 服务端给的登录时刻（毫秒）。未登录/旧版可能为 0。 */
        val timestampMs: Long = 0L,
    ) : BiliQrPoll()

    /** `86101`：还没被扫。 */
    data object Pending : BiliQrPoll()

    /** `86090`：已扫码，等手机端确认。 */
    data object Scanned : BiliQrPoll()

    /** `86038`：二维码已失效（**UI 必须能区分它并给「刷新二维码」**）。 */
    data object Expired : BiliQrPoll()

    /**
     * 没拿到可用状态。
     *
     * @property reason 决定「还要不要继续轮询」：见 [Reason]。
     * @property detail 给人看的诊断信息（**不含任何凭据**）。
     */
    data class Failed(val reason: Reason, val detail: String = "") : BiliQrPoll() {

        enum class Reason {
            /** 请求本身失败（超时/断网/DNS）：**下一轮值得再试**。 */
            NETWORK,

            /**
             * 服务端回了一段解析不出的东西（非 JSON、缺 `data`、缺 `data.code`）。
             * 重试没有意义 —— 与 `QqQrLogin.PollResult.Unavailable` 同一个桶。
             */
            MALFORMED,

            /**
             * 结构化响应但业务码不认识 / 顶层 `code != 0` / 成功态却没有 `SESSDATA`。
             * 也是**重试无意义**的一类。
             */
            SERVER,
        }
    }
}

/** `nav` 的 `data`（**匿名时只有 `isLogin` 与 `wbi_img`**，实测见探针文档 §6）。 */
data class BiliNav(
    val isLogin: Boolean,
    val uname: String? = null,
    val mid: Long = 0L,
    val vipType: Int = 0,
    val vipStatus: Int = 0,
    val wbiImgUrl: String? = null,
    val wbiSubUrl: String? = null,
) {
    fun profile(): BiliProfile = BiliProfile(uname = uname, mid = mid, vipType = vipType, vipStatus = vipStatus)
}

/** 一次 `nav` 的结局。**它只回答一件事：这份 cookie 现在还算不算登录态。** */
sealed class BiliNavResult {

    /** `code:0` 且 `data.isLogin:true`。 */
    data class LoggedIn(val nav: BiliNav) : BiliNavResult()

    /**
     * 未登录 / 登录态已失效。**这是「需要重新扫码」的唯一信号**。
     *
     * 实测：匿名与**伪造/失效的 `SESSDATA`** 返回的形状完全一样 ——
     * `HTTP 200` + `code:-101` + `data.isLogin:false`（§6）。所以判据是
     * 「顶层 `code == -101`」或「`code == 0` 但 `isLogin == false`」。
     */
    data object NotLoggedIn : BiliNavResult()

    /** 其它业务码（`-352` 风控、`-403` 权限、412 的 HTML 走不到这里 …）。**不要当成「过期」**。 */
    data class Failed(val code: Int, val message: String) : BiliNavResult()

    /** 响应解析不出来（非 JSON）或请求本身失败。**同样不要当成「过期」**。 */
    data class Unknown(val detail: String) : BiliNavResult()
}

/**
 * 扫码登录需要的三个网络能力。
 *
 * 抽成接口**只为一件事**：让 [BiliQrLogin] 的状态机可以在 JVM 单测里被全速驱动
 * （注入一个不发一个字节的假实现），而不必真的去连 B 站 —— 那需要一台真的手机去扫码。
 */
interface BiliAuthEndpoints {

    /** 申请二维码。失败（网络/畸形响应）返回 null。 */
    suspend fun qrGenerate(): BiliQrCode?

    /** 轮询一次。 */
    suspend fun qrPoll(qrcodeKey: String): BiliQrPoll

    /** 用一份 Cookie 头问一次 `nav`，回答「这份 cookie 还算不算登录态」。 */
    suspend fun navWithCookie(cookie: String): BiliNavResult
}

/**
 * B 站扫码登录的三个接口（v3.2.0 · P1）。
 *
 * ## 协议形状（**实测**，逐条证据在 `docs/verification/v3.2.0/probe-bili-login.md`）
 *
 * | 步骤 | 端点 | 关键点 |
 * |---|---|---|
 * | 1. 申请二维码 | `GET passport.bilibili.com/x/passport-login/web/qrcode/generate` | `data.url`（二维码内容）+ `data.qrcode_key`（32 字符） |
 * | 2. 轮询 | `GET …/qrcode/poll?qrcode_key=…` | HTTP **恒 200**，业务码在 **`data.code`**（顶层 `code` 恒 0）：`0` 成功 / `86038` 失效 / `86090` 已扫未确认 / `86101` 未扫 |
 * | 3. 校验 | `GET api.bilibili.com/x/web-interface/nav`（带 Cookie） | `code:0` + `data.isLogin:true`；**未登录/失效 = `code:-101`** |
 *
 * ⚠️ **双层 `code` 是这条协议最容易写错的地方**：音频区 API 的业务码在顶层 `code`，
 * 而扫码轮询的在 `data.code`，且顶层恒为 0。写成顶层就会读到一个恒定的「成功」。
 *
 * ## 为什么复用 [BiliApi] 的客户端，而不是自己建一个 / 用 `RetrofitClient`
 *
 * v3.1.0 的 A/B 实测钉死了这条：拿网易云的 `Referer` 请求 B 站会 **403**，
 * 而 `RetrofitClient` 的每个请求都无条件注入网易云的 UA/Referer/Cookie。
 * 复用它的结果不是「不太优雅」，而是「B 站永远 403」。
 * 所以本类的每一个请求都走 [BiliApi] 的独立 client 与 UA/Referer 约定
 * （`BiliApi.getRaw`，见那里的 KDoc）。
 *
 * ## 线程（v3.2.0 · P0-C 的纪律，不能重犯）
 *
 * 三个方法**全部** `suspend` + 内部 `withContext(Dispatchers.IO)`。
 * P0-C 的根因就是「同步 `execute()` 被主线程上的 `viewModelScope` 调用」，
 * 而 `NetworkOnMainThreadException` 被 `runCatching` 吞成「0 条」。这里的每一个
 * `catch` 也**原样抛出** [CancellationException] —— 吞掉它会让「用户取消/超时」
 * 显示成「二维码获取失败」。
 *
 * ## 合规边界
 *
 * 只有**读**接口：申请二维码、轮询、看自己的 `nav`。**没有任何写操作**
 * （不投币、不收藏、不点赞、不发弹幕、不评论）—— 这是 v3.1.0 `RECOMMENDATIONS.md` §6
 * 的合规结论，本版不改。凭据只存本机、不上传任何服务器。
 */
object BiliAuthApi : BiliAuthEndpoints {

    private const val TAG = "BiliAuthApi"

    // 三个端点。全部 HTTPS；主机与 v3.1.0 实测过的一致。
    const val QR_GENERATE_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/generate"
    const val QR_POLL_URL = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll"
    const val NAV_URL = "https://api.bilibili.com/x/web-interface/nav"

    /**
     * `data.code` 的四个取值 —— **显式常量**，不在业务判断里写裸数字。
     *
     * 实测（§1）：`86101`（新 key）、`86038`（伪造 key / 不带 key / key 超时）都抓到了原始响应；
     * `0` 与 `86090` 需要真实扫码，本轮无可用账号，形状来自社区文档（BAC，见 §1 的引用）——
     * **这一点在探针文档里如实标注，没有包装成实测**。
     */
    const val POLL_CODE_SUCCESS = 0
    const val POLL_CODE_EXPIRED = 86038
    const val POLL_CODE_SCANNED = 86090
    const val POLL_CODE_PENDING = 86101

    /** `nav` 的「未登录 / 登录态失效」业务码（实测：匿名与伪造 cookie 都是它）。 */
    const val NAV_CODE_NOT_LOGIN = -101

    /**
     * 二维码有效期。**实测**：同一个 `qrcode_key` 从 +1s 一直轮询到 **+180s** 都是 `86101`，
     * 到点转 `86038`（见 §1 的时间序列）；社区文档写的也是「密钥超时为 180 秒」。
     */
    const val QR_TTL_SECONDS = 180

    // ---------------------------------------------------------------- 纯解析（JVM 可单测） ----

    private val COOKIE_FIELDS = listOf(
        "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid",
    )

    private fun jsonOrNull(body: String?): JSONObject? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty() || !text.startsWith("{")) return null
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            // 412 的正文是 HTML（实测），JSON 解析失败是**正常路径**而不是异常事故。
            null
        }
    }

    /**
     * 解析 `generate` 的响应。
     *
     * 真实形状（§1，逐字节抓取）：
     * ```json
     * {"code":0,"message":"OK","ttl":1,"data":{
     *   "url":"https://account.bilibili.com/h5/account-h5/auth/scan-web?navhide=1\u0026callback=close\u0026qrcode_key=…\u0026from=",
     *   "qrcode_key":"4789f53cc92fcb2191a94ba57aed76c3"}}
     * ```
     *
     * 缺 `url` / 缺 `qrcode_key` / 顶层 `code != 0` 一律返回 null（UI 显示「二维码获取失败」，
     * 而不是画一张内容为空的二维码让用户白扫）。
     */
    fun parseQrGenerate(body: String?): BiliQrCode? {
        val json = jsonOrNull(body) ?: return null
        if (json.optInt("code", Int.MIN_VALUE) != 0) return null
        val data = json.optJSONObject("data") ?: return null
        val key = data.optString("qrcode_key").trim()
        val url = data.optString("url").trim()
        if (key.isEmpty() || url.isEmpty()) return null
        return BiliQrCode(url = url, qrcodeKey = key)
    }

    /**
     * 解析轮询响应。**凭据的两个载体都要认**（[setCookieHeaders] 与 `data.url`）。
     *
     * @param setCookieHeaders 该次响应的**全部** `Set-Cookie` 头，原样传入（不要预先拆分/解码）。
     */
    fun parseQrPoll(body: String?, setCookieHeaders: List<String> = emptyList()): BiliQrPoll {
        val json = jsonOrNull(body) ?: return BiliQrPoll.Failed(BiliQrPoll.Failed.Reason.MALFORMED, "响应不是 JSON")
        val outer = json.optInt("code", Int.MIN_VALUE)
        if (outer != 0) {
            val msg = json.optString("message")
            return BiliQrPoll.Failed(BiliQrPoll.Failed.Reason.SERVER, "顶层 code=$outer $msg".trim())
        }
        val data = json.optJSONObject("data")
            ?: return BiliQrPoll.Failed(BiliQrPoll.Failed.Reason.MALFORMED, "缺少 data")
        if (!data.has("code")) {
            return BiliQrPoll.Failed(BiliQrPoll.Failed.Reason.MALFORMED, "缺少 data.code")
        }
        val code = data.optInt("code", Int.MIN_VALUE)
        return when (code) {
            POLL_CODE_SUCCESS -> {
                val credential = credentialFromSetCookie(setCookieHeaders)
                    .merge(credentialFromCrossDomainUrl(data.optString("url")))
                if (!credential.isUsable()) {
                    // 「成功态却没有 SESSDATA」落盘会得到一份**看起来已登录、一请求就 -101**
                    // 的凭据（与 QqPhoneLogin.cookieFromCredential 的同一条纪律：宁可明确失败）。
                    BiliQrPoll.Failed(
                        BiliQrPoll.Failed.Reason.SERVER,
                        "data.code=0 但两个载体里都没有 SESSDATA",
                    )
                } else {
                    BiliQrPoll.Success(credential, data.optLong("timestamp", 0L))
                }
            }
            POLL_CODE_EXPIRED -> BiliQrPoll.Expired
            POLL_CODE_SCANNED -> BiliQrPoll.Scanned
            POLL_CODE_PENDING -> BiliQrPoll.Pending
            else -> {
                val msg = data.optString("message")
                BiliQrPoll.Failed(BiliQrPoll.Failed.Reason.SERVER, "未知 data.code=$code $msg".trim())
            }
        }
    }

    /**
     * 解析 `nav`。
     *
     * 实测的两种真实形状（§5/§6）：
     * ```json
     * 匿名 : {"code":-101,"message":"账号未登录","ttl":1,"data":{"isLogin":false,
     *        "wbi_img":{"img_url":"…","sub_url":"…"},"ip_region":"CN"}}
     * ```
     * ⇒ **未登录时 `data` 里没有 `uname` / `mid` / `vipType` / `vipStatus`**，
     * 只有 `isLogin` + `wbi_img`。所以 [BiliNav] 的会员字段一律给默认值，绝不假设存在。
     *
     * 判据（**别把风控算成过期**）：
     * - `code == 0 && isLogin` → [BiliNavResult.LoggedIn]；
     * - `code == -101`（或 `code == 0 && !isLogin`）→ [BiliNavResult.NotLoggedIn]；
     * - 其它码 → [BiliNavResult.Failed]（`-352`/`-403` 是风控/权限，不代表 cookie 过期，
     *   把它们当成「需要重新登录」会让用户反复扫码却永远登不进去）。
     */
    fun parseNav(body: String?): BiliNavResult {
        val json = jsonOrNull(body)
            ?: return BiliNavResult.Unknown("响应不是 JSON（长度=${body?.length ?: 0}）")
        val code = json.optInt("code", Int.MIN_VALUE)
        val message = json.optString("message")
        val data = json.optJSONObject("data")
        val isLogin = data?.optBoolean("isLogin", false) ?: false

        if (code == 0 && isLogin) {
            val wbi = data?.optJSONObject("wbi_img")
            return BiliNavResult.LoggedIn(
                BiliNav(
                    isLogin = true,
                    uname = data?.optString("uname")?.takeIf { it.isNotBlank() },
                    mid = data?.optLong("mid", 0L) ?: 0L,
                    vipType = data?.optInt("vipType", 0) ?: 0,
                    vipStatus = data?.optInt("vipStatus", 0) ?: 0,
                    wbiImgUrl = wbi?.optString("img_url")?.takeIf { it.isNotBlank() },
                    wbiSubUrl = wbi?.optString("sub_url")?.takeIf { it.isNotBlank() },
                ),
            )
        }
        if (code == NAV_CODE_NOT_LOGIN || (code == 0 && !isLogin)) return BiliNavResult.NotLoggedIn
        return BiliNavResult.Failed(code, message)
    }

    /**
     * 从 `Set-Cookie` 头里取凭据。**只认白名单字段**（[COOKIE_FIELDS]）。
     *
     * 值**原样保留**（不 URL 解码）：真实 `SESSDATA` 里的逗号是 `%2C` 的形状，
     * 解码成 `,` 会改变凭据本身。只取第一个 `name=value` 段，后面的 `Path=/; HttpOnly; Secure`
     * 全部丢掉 —— 它们不是凭据，混进 `Cookie` 头会被服务端当成畸形 cookie。
     */
    fun credentialFromSetCookie(headers: List<String>): BiliCredential {
        val found = LinkedHashMap<String, String>()
        for (raw in headers) {
            val pair = raw.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (value.isEmpty()) continue
            val canonical = COOKIE_FIELDS.firstOrNull { it.equals(name, ignoreCase = true) } ?: continue
            found[canonical] = value
        }
        return credentialOf(found)
    }

    /**
     * 从成功态 `data.url`（`https://passport.biligame.com/crossDomain?…&SESSDATA=…&bili_jct=…`）
     * 的 query 里取凭据。**同样只认白名单、同样不解码**。
     *
     * 手写 query 拆分而不是 `URLDecoder`：后者会把 `%2C` 解成 `,`、把 `+` 解成空格，
     * 于是「存下来的 SESSDATA」与「服务端签发的那一个」不再是同一个字符串。
     */
    fun credentialFromCrossDomainUrl(url: String?): BiliCredential {
        val text = url?.trim().orEmpty()
        val q = text.indexOf('?')
        if (q < 0 || q == text.lastIndex) return BiliCredential()
        val found = LinkedHashMap<String, String>()
        for (part in text.substring(q + 1).substringBefore('#').split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (value.isEmpty()) continue
            val canonical = COOKIE_FIELDS.firstOrNull { it.equals(name, ignoreCase = true) } ?: continue
            found[canonical] = value
        }
        return credentialOf(found)
    }

    private fun credentialOf(fields: Map<String, String>) = BiliCredential(
        sessdata = fields["SESSDATA"].orEmpty(),
        biliJct = fields["bili_jct"].orEmpty(),
        dedeUserId = fields["DedeUserID"].orEmpty(),
        dedeUserIdCkMd5 = fields["DedeUserID__ckMd5"].orEmpty(),
        sid = fields["sid"].orEmpty(),
    )

    // ---------------------------------------------------------------- 网络（一律 IO 线程） ----

    /** 申请二维码。失败返回 null（UI 文案「二维码获取失败，请重试」）。 */
    override suspend fun qrGenerate(): BiliQrCode? = withContext(Dispatchers.IO) {
        try {
            val raw = BiliApi.getRaw(QR_GENERATE_URL)
            val code = parseQrGenerate(raw.body)
            if (code == null) {
                Log.w(TAG, "generate 解析失败 http=${raw.httpCode} 正文长度=${raw.body.length}")
            }
            code
        } catch (e: CancellationException) {
            // 取消是控制流不是失败：吞掉它会让「用户关掉弹窗」显示成「二维码获取失败」。
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "generate 请求失败", e)
            null
        }
    }

    /** 轮询一次。**HTTP 恒 200，状态在 `data.code`**（见类文档）。 */
    override suspend fun qrPoll(qrcodeKey: String): BiliQrPoll = withContext(Dispatchers.IO) {
        if (qrcodeKey.isBlank()) {
            return@withContext BiliQrPoll.Failed(BiliQrPoll.Failed.Reason.MALFORMED, "qrcode_key 为空")
        }
        try {
            val raw = BiliApi.getRaw("$QR_POLL_URL?qrcode_key=$qrcodeKey")
            parseQrPoll(raw.body, raw.setCookies)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BiliQrPoll.Failed(BiliQrPoll.Failed.Reason.NETWORK, e.javaClass.simpleName)
        }
    }

    /** 用一份 Cookie 头问一次 `nav`。**只读，不改任何服务端状态。** */
    override suspend fun navWithCookie(cookie: String): BiliNavResult = withContext(Dispatchers.IO) {
        // 没有 cookie 就不必问：带上空的 Cookie 头只会拿到一个恒定的 -101。
        if (cookie.isBlank()) return@withContext BiliNavResult.NotLoggedIn
        try {
            parseNav(BiliApi.getRaw(NAV_URL, cookieHeader = cookie).body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BiliNavResult.Unknown(e.javaClass.simpleName)
        }
    }
}
