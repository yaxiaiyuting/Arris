/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · C（hotfix 4）：QQ 互联扫码登录的**纯逻辑**部分。
 */

package com.takahashirinta.ncrust.qq

/**
 * QQ 互联（`ssl.ptlogin2.qq.com`）扫码登录的纯逻辑：token 计算、`ptuiCB` 解析、状态映射。
 * **无 IO、无 Android 依赖、JVM 可单测。**
 *
 * ## 为什么走 QQ 互联而不是「qm 客户端扫码」
 *
 * 后者的接口（`CreateQRCode` / `GetQRCodeStatus`）实测匿名可用，但**扫码确认后的最后一跳
 * 在官方实现里只能是 MQTT 推送**（调研穷举 48 个候选方法后确认：HTTP 侧没有「把登录态
 * 交给发起方」的接口，`QRCodeLogin` 要的是**扫码方**的 OAuth code + 有效 Cookie）。
 * 要支持它就得自己实现 MQTT 5.0 over WSS 的握手与订阅，成本远超本版范围。
 *
 * QQ 互联这条是**纯 HTTP 长轮询**，官方 Web 端现役：二维码用**手机 QQ**扫，
 * `ptqrlogin` 直接返回下一步的跳转地址。
 *
 * ## 验证边界（v2.1.1 重写 —— 上一版这一段是错的）
 *
 * v2.1.0 这里写的是「`ptqrlogin` 在本机出口 IP 被 WAF 恒定 403，判定为 IP 级风控，
 * 这一步没有实测过」。**那个判定是错的**：403 不是 IP 风控，是 [ptqrToken] 算错
 * （hash33 初值用了 g_tk 的 5381）导致的**畸形请求被 WAF 拒绝**。v2.1.1 换成正确初值后，
 * 同一台机器、同一个出口 IP、同一个 `qrsig` 立刻拿到 `HTTP 200 + ptuiCB('66',...)`（3/3 复现）。
 *
 * - `ptqrshow`（取二维码）：实测可用（HTTP 200 + 真实 PNG + `qrsig` cookie）。
 * - `ptqrlogin`（轮询）：**已实测可用**（HTTP 200 + 可解析的 `ptuiCB`）。见 [ptqrToken]。
 * - 剩下的唯一未实测项是「扫码确认成功那一跳」（`ptuiCB('0',...)` → WebView 换票），
 *   因为那需要一个真实 QQ 账号去扫，开发环境没有。
 */
object QqQrLogin {

    /** 二维码 PNG 的边长级别。实测 `s=3` 是 111×111，`s=6` 是 222×222（显示时不做平滑缩放即可扫）。 */
    const val QR_SIZE_PARAM = 6

    /** 二维码有效期（服务端 `expiresIn` 的实测值是 900 秒，这里留出刷新余量）。 */
    const val QR_TTL_SECONDS = 840

    /**
     * `ptqrtoken = hash33(qrsig)`：官方 JS 的算法。**初值是 0，不是 5381。**
     *
     * 证据是腾讯生产环境的 `ptlogin/js/c_login_2.js`（2026-09 抓取）：
     * ```js
     * "hash33": function (t) {
     *     for (var e = 0, n = 0, o = t.length; n < o; ++n) e += (e << 5) + t.charCodeAt(n);
     *     return 2147483647 & e
     * }
     * i.ptqrtoken = q["default"].str.hash33(q["default"].cookie.get("qrsig"))
     * ```
     * 初值 `e = 0`；同一个 `hash33` 在 `g_tk` 那边是 **带 seed 调用**的
     * （`hash33(p_skey, 5381)`）—— v2.1.0 把 g_tk 的 5381 错搬到了这里。
     *
     * 代价不是「token 略有偏差」，而是**每一次轮询都被 WAF 判成异常请求直接 403**。
     * 实测（同一 `qrsig`、同一时刻、同一出口 IP、同一套 Header，唯一变量是初值）：
     *
     * | 初值 | HTTP | 响应体 |
     * |---|---|---|
     * | `0`（官方） | **200** | `ptuiCB('66','0','','0','二维码未失效。', '')` |
     * | `5381`（v2.1.0） | **403** | 空 |
     *
     * 3/3 复现；真实向量钉在 [QqQrLoginTest] 里。
     *
     * 结果恒为非负 —— 这一点在 Kotlin 里要显式做（`Int` 会溢出成负数，
     * 而请求参数里必须是正数，否则服务端判 token 错）。
     */
    fun ptqrToken(qrsig: String): Long {
        var h = 0L
        for (c in qrsig) {
            h = (h shl 5) + h + c.code
            // 只保留低 32 位，避免长字符串把 h 推到天上去（官方 JS 是 32 位整数溢出语义）
            h = h and 0xFFFFFFFFL
        }
        return h and 0x7FFFFFFFL
    }

    /** 扫码状态。取值来自 `ptuiCB` 的第一个参数（QQ 互联的固定码表）。 */
    enum class QrStatus {
        /** `66`：二维码还没被扫（官方文案「二维码未失效」）。 */
        WAITING,

        /** `67`：已扫描，等手机端确认。 */
        SCANNED,

        /** `0`：确认成功，携带下一步跳转地址。 */
        CONFIRMED,

        /** `65`：二维码已过期，需要刷新。 */
        EXPIRED,

        /** `68`/`22005`/…：被拒绝或其它失败。 */
        FAILED,
    }

    /** `ptuiCB(...)` 的解析结果。 */
    data class PtuiCb(
        val code: Int,
        val url: String?,
        val message: String,
    ) {
        val status: QrStatus get() = statusOf(code)
    }

    /**
     * `ptuiCB` 的第一个参数 → 状态。
     *
     * 码表以腾讯生产 JS `c_login_2.js` 的 `ptuiCB` 分支为准（2026-09 抓取）：
     * ```js
     * case "65": return ... set_qrlogin_invalid()          // 二维码失效
     * case "66": return void log2cls("二维码未失效")        // 什么都不做 = 继续轮询
     * case "67": return ... go_qrlogin_step(2)             // 手机端扫码成功，等待确认
     * case "22005": case "68": ... onekeyVerify("hide")    // 拒绝
     * ```
     *
     * ⚠️ v2.1.0 把 **65 与 67 写反了**（65 当「已扫描」、67 当「已过期」）。后果不是
     * 「提示文字不好看」，而是**用户一扫，界面立刻说「二维码已过期」并停止轮询** ——
     * 永远走不到确认成功那一步。修 token 只是让轮询能通，这一条才决定扫码之后能不能成。
     */
    fun statusOf(code: Int): QrStatus = when (code) {
        0 -> QrStatus.CONFIRMED
        67 -> QrStatus.SCANNED
        66 -> QrStatus.WAITING
        65 -> QrStatus.EXPIRED
        else -> QrStatus.FAILED
    }

    /**
     * 一次轮询的结局。
     *
     * **必须把「服务端明确回绝」与「请求根本没成功」分开**：前者（403、空 body、
     * 不是 `ptuiCB` 的 200）重试多少次结果都一样，正确动作是停止重试并引导用户
     * 改用已验证可用的网页登录；后者（超时、连接被断）才是网络抖动，下一轮大概率就好。
     *
     * v2.1.0 把两者都归成 `null`，界面只能一律显示「网络不稳定，仍在重试…」——
     * 于是一个**恒定的 403** 被显示成网络问题，而且每 2 秒重试一次、连试 14 分钟不罢休。
     * 用户报告的那句提示就是这么来的。
     */
    sealed class PollResult {
        /** 拿到了可解析的 `ptuiCB`。 */
        data class Status(val cb: PtuiCb) : PollResult()

        /** 服务端有应答但不接受：非 2xx，或 200 却回了一段解析不出的内容（风控说明页 / 空 body）。 */
        data class Unavailable(val httpCode: Int) : PollResult()

        /** 请求本身失败：超时、连接被断、DNS。这类下一轮值得再试。 */
        data object NetworkError : PollResult()
    }

    /**
     * 把一次 HTTP 应答归类。**纯函数，JVM 可单测**（IO 不在这里，见 `QqQrClient.poll`）。
     *
     * 非 2xx 一律算 [PollResult.Unavailable]：`ptqrlogin` 在 token 错或被风控时就是
     * `403 + 空 body`，那是服务端在拒绝，不是网络不通。3xx 由 OkHttp 自动跟随，走不到这里。
     */
    fun classifyPollResponse(httpCode: Int, body: String?): PollResult =
        if (httpCode in 200..299) {
            parsePtuiCb(body)?.let { PollResult.Status(it) } ?: PollResult.Unavailable(httpCode)
        } else {
            PollResult.Unavailable(httpCode)
        }

    /**
     * 解析 `ptuiCB('65','0','','0','二维码已扫描，请在手机上确认登录。', 'xxx');`
     *
     * **手写扫描而不是正则**：字段里会出现中文与转义引号，正则在这种输入上很容易
     * 贪婪吃掉后面的字段（一个字段读错，表现就是「永远停在等待扫码」）。
     * 解析失败返回 null，调用方按「这次轮询没结果」处理（下一轮继续），不要当失败终止。
     */
    fun parsePtuiCb(text: String?): PtuiCb? {
        if (text.isNullOrBlank()) return null
        val start = text.indexOf("ptuiCB(")
        if (start < 0) return null
        val fields = mutableListOf<String>()
        var i = start + "ptuiCB(".length
        val sb = StringBuilder()
        var inQuote = false
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '\'' -> {
                    inQuote = !inQuote
                    if (!inQuote) {
                        // 一个字段结束
                        fields.add(sb.toString())
                        sb.setLength(0)
                    }
                    i++
                }
                !inQuote && (ch == ',' || ch == ')') -> {
                    i++
                    if (fields.size >= 6) break
                }
                else -> {
                    sb.append(ch)
                    i++
                }
            }
        }
        if (fields.size < 5) return null
        val code = fields[0].trim().toIntOrNull() ?: return null
        val url = fields.getOrNull(2)?.takeIf { it.isNotBlank() }
        val message = fields.getOrNull(4).orEmpty()
        return PtuiCb(code, url, message)
    }

    /** `ptqrshow` 的 URL（取二维码图片）。`t` 是随机数，防缓存。 */
    fun qrShowUrl(random: Double, sizeParam: Int = QR_SIZE_PARAM): String =
        "https://ssl.ptlogin2.qq.com/ptqrshow" +
            "?appid=$APP_ID&e=2&l=M&s=$sizeParam&d=72&v=4&t=$random" +
            "&daid=$DAID&pt_3rd_aid=$PT_3RD_AID" +
            "&u1=" + encode(U1)

    /** `ptqrlogin` 的 URL（轮询扫码状态）。 */
    fun qrLoginUrl(token: Long, actionTs: Long): String =
        "https://ssl.ptlogin2.qq.com/ptqrlogin" +
            "?u1=" + encode(U1) +
            "&ptqrtoken=$token" +
            "&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052" +
            "&action=0-0-$actionTs&js_ver=20102616&js_type=1&pt_uistyle=40" +
            "&aid=$APP_ID&daid=$DAID&pt_3rd_aid=$PT_3RD_AID&"

    /** qm Web 端的固定 appid/daid（与扫码轮询链路上用的是同一组）。 */
    const val APP_ID = "716027609"
    const val DAID = "383"
    const val PT_3RD_AID = "100497308"

    /** 登录成功后的跳转目标（QQ 互联的登录跳板）。 */
    private const val U1 = "https://graph.qq.com/oauth2.0/login_jump"

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
