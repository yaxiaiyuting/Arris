/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.1：qm「手机号 + 短信验证码」登录的**纯逻辑**部分。
 */

package com.takahashirinta.ncrust.qq

import org.json.JSONObject

/**
 * qm 手机号验证码登录的纯逻辑：号码/验证码规范化、响应码归类、凭证 → cookie。
 * **无 IO、无 Android 依赖、JVM 可单测**（`org.json` 由 testImplementation 提供）。
 *
 * ## 为什么要有这条路
 *
 * 网页登录里的**微信登录**必须是「网站应用扫码」（`open.weixin.qq.com/connect/qrconnect`），
 * 而那个二维码**只能被另一台设备的微信扫**：同一台手机上微信既扫不了自己的屏幕，
 * 相册识别登录码也被微信拒绝；官方 App 那种「一键微信登录」走的是微信开放平台
 * **移动应用 SDK**，要求 `appid` 与**签名**在腾讯侧配对注册 —— 本 fork 的签名必然不同，
 * 这条路在结构上就走不通。
 *
 * 而 qm 自己的手机号验证码登录是**纯 HTTP**（`music.login.LoginServer`），
 * 单机即可完成，不依赖 QQ 号、不依赖第二台设备、不依赖微信。这正是「微信用户没有 QQ 号」
 * 时唯一可用的登录方式。
 *
 * ## 分两步，两个端点
 *
 * 1. [QqRequests.sendPhoneAuthCode] 发短信（`SendPhoneAuthCode`）；
 * 2. [QqRequests.phoneLogin] 用验证码换凭证（`Login`，`loginMode=1`）。
 *
 * ## 实测证据（2026-09，`tools/probe-qq-phone-login.py`）
 *
 * 全部只用**明显非法的号码**探测（绝不向真实号码发短信），确认到的事实：
 *
 * | 观察 | 结论 |
 * |---|---|
 * | `areaCode` 传**字符串** `"86"` → `104400`（号码非法，正常校验路径） | 类型正确 |
 * | `areaCode` 传**数字** `86` → `10006`（请求本身畸形） | **`areaCode` 必须是字符串** |
 * | `param.tmeAppid` 换成 `"qqmusic_hd"` → `104400` + `unknown tmeAppID` | 该字段会被校验 |
 * | `comm` 里去掉 `tmeLoginMethod`，`Login` → `104400`；带着 → `1000` | **`Login` 必须要它** |
 * | 假验证码 + 非法号码 → `req.code=1000` + **完整 35 字段凭证骨架**（值全空） | `1000` = 登录失败 |
 * | 5 次快速连发 | HTTP 恒 200，未触发频率限制 |
 *
 * ## 仍然没有实测的部分（如实标注）
 *
 * **成功路径没跑过**：那需要给一个真实号码发短信并拿到真实验证码。
 * 所以 `req.code == 0` 之后凭证字段的**实际取值**（`musickey` 的长度与形状、
 * `musicid` 与 `uin` 的关系）是按 §3.2 的字段契约写的，未经真机验证。
 * [cookieFromCredential] 因此对每个关键字段都做了非空校验：**宁可让登录明确失败，
 * 也不要落一份缺票据的 cookie** —— 后者会表现为「显示已登录、一取链就说没权限」。
 */
object QqPhoneLogin {

    /** 中国大陆区号。 */
    const val AREA_CODE_CN = "86"

    /** 中国大陆手机号位数。 */
    private const val CN_DIGITS = 11

    /** 其它区号的合理长度区间（E.164 最长 15 位）。 */
    private const val INTL_MIN_DIGITS = 5
    private const val INTL_MAX_DIGITS = 15

    /** 短信验证码位数区间。腾讯实测发的是 6 位，但**不写死** —— 写死会在腾讯改长度时直接锁死用户。 */
    private const val CODE_MIN_DIGITS = 4
    private const val CODE_MAX_DIGITS = 8

    /**
     * 规范化手机号：只留数字，剥掉国家码与常见分隔符。**不合法返回 null**。
     *
     * 本地校验的意义不是「更安全」，而是**省掉一次注定失败的往返**：
     * 号码格式不对时服务端回 `104400`，用户白等一个 RTT 才知道自己少打了一位。
     *
     * 接受：`138 0013 8000`、`+86 138-0013-8000`、`8613800138000`、`13800138000`。
     */
    fun normalizePhone(raw: String, areaCode: String = AREA_CODE_CN): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val code = areaCode.filter { it.isDigit() }.trimStart('0').ifEmpty { AREA_CODE_CN }

        if (code == AREA_CODE_CN) {
            // 11 位且以 1 开头（大陆手机号）
            if (digits.length == CN_DIGITS && digits[0] == '1') return digits
            // 带国家码：86 + 11 位
            if (digits.length == CN_DIGITS + 2 && digits.startsWith(AREA_CODE_CN)) {
                val rest = digits.substring(2)
                if (rest[0] == '1') return rest
            }
            return null
        }

        // 其它区号：只做长度校验。带不带国家码前缀都放行 ——
        // 猜错一个国家码的代价（打不出去）比让用户自己看着办更大。
        //
        // 剥前缀的条件：**剥完还得剩下一个像号码的东西**（≥ INTL_MIN_DIGITS）。
        // 不加这个条件的话 `"1234"` 会被剥成 `"234"`，而它本来就会因长度不足被拒 ——
        // 结论一样，但少一次无意义的猜测。注意 `areaCode` 是**单独发**的，
        // 所以 `phoneNo` 里不该再带国家码，剥掉才是对的。
        val stripped = if (digits.startsWith(code) && digits.length - code.length >= INTL_MIN_DIGITS) {
            digits.substring(code.length)
        } else {
            digits
        }
        return if (stripped.length in INTL_MIN_DIGITS..INTL_MAX_DIGITS) stripped else null
    }

    /** 规范化验证码：只留数字，长度不在 [CODE_MIN_DIGITS]..[CODE_MAX_DIGITS] 之间返回 null。 */
    fun normalizeCode(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        return digits.takeIf { it.length in CODE_MIN_DIGITS..CODE_MAX_DIGITS }
    }

    /** 发验证码的结局。 */
    enum class SendOutcome {
        /** `0`：短信已下发。 */
        SENT,

        /** `20276`：服务端要图形/滑块验证码 —— 手机号这条路暂时走不通。 */
        NEED_CAPTCHA,

        /** `100001`：请求过于频繁。 */
        TOO_FREQUENT,

        /** `104400`：号码或参数不合法。 */
        BAD_NUMBER,

        /** 其它（含 `10006` 这种「请求本身畸形」，那是本客户端的 bug）。 */
        FAILED,
    }

    /** 用验证码换凭证的结局。 */
    enum class LoginOutcome {
        /** `0`：成功，`data` 里是完整凭证。 */
        OK,

        /**
         * `1000` 或 `20261`/`20271`：**失败**。实测假验证码回的就是 `1000`，
         * `data` 里字段齐全但值全空；`20261`/`20271` 是参考实现记录的「登录参数错 / 验证码错」。
         *
         * ⚠️ 它不区分「验证码错」与「号码没注册」这类细分原因（实测 `errMsg` 是空串），
         * 所以界面文案只能取最常见的那一种，不能声称「一定是验证码错了」。
         */
        CODE_WRONG,

        /** `100001`/`104604`：过于频繁。 */
        TOO_FREQUENT,

        /** `20276`：要图形验证码。 */
        NEED_CAPTCHA,

        /** `20277`/`20278`/`20450`：账号受限或封禁。 */
        ACCOUNT_RESTRICTED,

        /** `20279`：登录设备数超限。 */
        DEVICE_LIMIT,

        /** `104400` 及其它：请求被拒。 */
        FAILED,
    }

    fun classifySend(reqCode: Int): SendOutcome = when (reqCode) {
        0 -> SendOutcome.SENT
        20276 -> SendOutcome.NEED_CAPTCHA
        100001, 104604 -> SendOutcome.TOO_FREQUENT
        104400 -> SendOutcome.BAD_NUMBER
        else -> SendOutcome.FAILED
    }

    /**
     * `Login` 的响应码归类。
     *
     * ⚠️ 除 `0` / `1000` / `20276` 之外的码来自参考实现的记录（`PHASE0-QQMUSIC-API.md` §8 错误码表），
     * **本仓库没有复现过**（复现它们需要真实账号走到那些状态）。所以：
     * - 归到「账号受限 / 设备超限」这类**明确但不常见**的桶里，是照表办事；
     * - 认不出来的码一律 [LoginOutcome.FAILED]，界面文案是**中性**的「登录失败，请稍后重试」——
     *   绝不再复用扫码那条「扫码登录失败」（v2.1.2 就是那么写的，用户看到后直接问
     *   「我短信登录为什么说扫码登录失败」，那句话在任何情况下都是错的）。
     */
    fun classifyLogin(reqCode: Int): LoginOutcome = when (reqCode) {
        0 -> LoginOutcome.OK
        1000, 20261, 20271 -> LoginOutcome.CODE_WRONG
        100001, 104604 -> LoginOutcome.TOO_FREQUENT
        20276 -> LoginOutcome.NEED_CAPTCHA
        20277, 20278, 20450 -> LoginOutcome.ACCOUNT_RESTRICTED
        20279 -> LoginOutcome.DEVICE_LIMIT
        else -> LoginOutcome.FAILED
    }

    /**
     * 从 `SendPhoneAuthCode` 的 `req` 对象里取图形验证码地址（`20276` 的 `data.securityURL`）。
     *
     * 字段名与位置不是猜的：实测的**失败**响应里 `data` 就是
     * `{"errMsg":"…","securityURL":"","errTip":""}` —— 也就是说这个字段一直在，
     * 只是成功/普通失败时是空串，只有要求验证码时才填上。
     *
     * 抽成纯函数是为了让它可被单测钉住：这个字段读错的表现是「服务端要求验证，
     * 界面却说验证走不通、只让你去网页登录」，而开发环境复现不出 `20276`（需要真实号码）。
     */
    fun securityUrlOf(req: JSONObject?): String? =
        req?.optJSONObject("data")?.optString("securityURL")?.takeIf { it.isNotEmpty() }

    /**
     * 把 `Login` 返回的凭证 JSON 拼成 cookie 串。**缺关键字段返回 null**。
     *
     * 三个必需字段（缺任何一个都不该落盘）：
     * - `musickey` → `qqmusic_key` / `qm_keyst`（[QqCookie.musicKeyOf] 认这两个名字）；
     * - `musicid`（回落 `str_musicid`）→ `uin` / `qqmusic_uin`（[QqCookie.uinOf] 认 `uin`）；
     * - 两者都在，[QqCookie.isLoggedIn] 才会判成已登录。
     *
     * `musickeyCreateTime` 有值就一起带上：它是票据签发时间，配合 `keyExpiresIn` 用于过期判定。
     * 实测失败响应里它是 `0`，所以按「0 = 未知」处理而不是写 0 进 cookie。
     *
     * ⚠️ `qqmusic_uin` 是实测请求 Cookie 里出现的字段（见 PHASE0-QQMUSIC-API.md §4.5.1
     * 的 `Cookie: uin=…; qqmusic_uin=…; qm_keyst=…; qqmusic_key=…`），所以一并写上。
     *
     * ## v3.4.9：这个函数同时服务于**登录**与**续期**
     *
     * 续期（[QqRequests.refreshCredential]）走的是同一个 `Login` 方法、回的是同一个响应形状，
     * 所以两边**共用这一个读取器**。[QqTokenRefresher] 直接调它，
     * 不另写一份字段映射 —— 两份映射迟早会分叉，而分叉的表现是
     * 「登录能拿到票、续期拿到半张票」，只在真机上偶发。
     *
     * ⚠️ **调用方必须先 [QqRefreshStore.clear]**：这个函数只产出 cookie，
     * 而续期凭证是另一半。换了账号却留着上一份凭证，下一次续期会把新账号的登录态
     * 换成旧账号的，且不报任何错（详见 [QqAuthStore.saveCookie] 的注释）。
     */
    fun cookieFromCredential(data: JSONObject?): String? {
        if (data == null) return null
        val key = data.optString("musickey").takeIf { it.isNotEmpty() } ?: return null
        val uid = data.optLong("musicid", 0L).takeIf { it > 0L }
            ?: data.optString("str_musicid").toLongOrNull()?.takeIf { it > 0L }
            ?: return null
        val created = data.optLong("musickeyCreateTime", 0L)

        val parts = mutableListOf(
            QqCookie.KEY_UIN + "=" + uid,
            "qqmusic_uin=" + uid,
            QqCookie.KEY_MUSIC_KEY + "=" + key,
            QqCookie.KEY_MUSIC_KEY_ALT + "=" + key,
        )
        if (created > 0L) parts.add("psrf_musickey_createtime=" + created)
        return parts.joinToString("; ")
    }
}
