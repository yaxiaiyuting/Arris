/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · C：qm cookie 的纯逻辑处理（解析 / 合并 / 关键字段提取）。
 */

package com.takahashirinta.ncrust.qq

import org.json.JSONObject

/**
 * qm cookie 的纯字符串处理（v2.1.0 · C）。**无 Android 依赖，JVM 可单测。**
 *
 * 为什么值得单独抽一个对象：登录态是**跨版本存活**的数据，而它的判据比看上去脆 ——
 * - qm 的登录凭证不止一个名字（`qqmusic_key` 与 `qm_keyst` 在不同登录路径下出现）；
 * - 微信登录拿到的 `uin` 带 `o` 前缀（腾迅用它区分 openid 与 QQ 号），
 *   而 `musicu.fcg` 的 `comm.uin` 要的是纯数字；
 * - 二维码登录与 WebView 登录拿到的 cookie **字段集不同**，必须做合并而不是替换。
 *
 * 这三件事踩错任何一件，表现都是「登录成功了但一取链就说没权限」，而且只在真机上出现。
 */
object QqCookie {

    /** 主登录票据。 */
    const val KEY_MUSIC_KEY = "qqmusic_key"

    /** 备用票据（部分登录路径只给这个）。 */
    const val KEY_MUSIC_KEY_ALT = "qm_keyst"

    const val KEY_UIN = "uin"
    const val KEY_WXUIN = "wxuin"

    /**
     * 解析 `a=1; b=2` 形式的 cookie 串成 map。
     *
     * 容错规则（都来自真实响应的形状）：分号可有可无、值里可以含 `=`（只按第一个 `=` 切）、
     * 空段跳过、key 去空白但**值不 trim 尾部空格以外的东西** —— 票据是大小写与符号敏感的。
     */
    fun parse(cookie: String?): Map<String, String> {
        if (cookie.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (segment in cookie.split(';')) {
            val s = segment.trim()
            if (s.isEmpty()) continue
            val eq = s.indexOf('=')
            if (eq <= 0) continue
            val k = s.substring(0, eq).trim()
            val v = s.substring(eq + 1).trim()
            if (k.isEmpty()) continue
            out[k] = v
        }
        return out
    }

    /**
     * 合并两份 cookie：**新值覆盖同名的旧值，其余原样保留**。
     *
     * 登录流程必须用合并而不是替换：WebView / 扫码拿回来的往往只是**增量**字段集，
     * 直接替换会把上次登录留下的 `uin`（或 `wxuin`）丢掉，于是「刚登录完就变成未登录」。
     * 保留首次出现的顺序，方便真机抓包时对照。
     */
    fun merge(old: String?, new: String?): String {
        val result = LinkedHashMap<String, String>()
        result.putAll(parse(old))
        result.putAll(parse(new)) // 后写覆盖先写
        return result.entries.joinToString("; ") { it.key + "=" + it.value }
    }

    /**
     * 取数字 uin（`comm.uin` 用）。取不到返回 null（调用方按匿名 `"0"` 处理）。
     *
     * 微信登录的 cookie 里 `uin` 形如 `o1234567890` —— 开头的 `o` 是「这是 openid」的标记，
     * 必须剥掉；但**只剥一个且后面必须是数字**，否则 `abc` 这种值会被误解析。
     * 没有 `uin` 时回落 `wxuin`（纯数字）。
     */
    fun uinOf(cookie: String?): Long? {
        val map = parse(cookie)
        val raw = map[KEY_UIN]?.takeIf { it.isNotEmpty() } ?: map[KEY_WXUIN] ?: return null
        return digitsOf(raw)
    }

    private fun digitsOf(raw: String): Long? {
        val s = raw.trim()
        val candidate = if ((s.startsWith("o") || s.startsWith("O")) && s.length > 1 && s[1].isDigit()) {
            s.substring(1)
        } else {
            s
        }
        if (candidate.isEmpty() || !candidate.all { it.isDigit() }) return null
        return candidate.toLongOrNull()?.takeIf { it > 0L }
    }

    /** 主票据；没有就回落到备用票据。都没有返回 null。 */
    fun musicKeyOf(cookie: String?): String? {
        val map = parse(cookie)
        return map[KEY_MUSIC_KEY]?.takeIf { it.isNotEmpty() }
            ?: map[KEY_MUSIC_KEY_ALT]?.takeIf { it.isNotEmpty() }
    }

    /**
     * 注入 `comm.uin` / `comm.authst`（v2.1.4）。
     *
     * ## 为什么必须有这个函数
     *
     * PHASE0 报告 §2.3 写的是「**已登录时追加**：`comm.uin = <musicid 字符串>`，
     * `comm.authst = <musickey>`（同时也要带 Cookie）」，§7.2 的登录态示例也长这样：
     *
     * ```json
     * {"comm":{"ct":11,"cv":14090008,…,"uin":"<uin>","authst":"<key>"},
     *  "req":{"module":"music.vkey.GetVkey",…}}
     * ```
     *
     * 但 v2.1.0 只注入了 `uin`，`authst` **一个字节都没发过**。这个缺口之所以一直没被发现，
     * 是因为报告 §2.3 的 `comm` 实测表**全部是匿名态**（`uin=0`、无票据）跑出来的 ——
     * 匿名态本来就没有 `authst` 可发，于是「登录态下少了 authst 会怎样」根本没进过验证范围。
     *
     * 表现就是本次用户报的问题：**带 Cookie 与不带 Cookie 拿到的东西一样**
     * （付费档位全 `104003`），因为服务端在 `comm` 里没有看到任何登录身份，
     * 只把这次请求当成一个「碰巧带了票的匿名客户端」。
     *
     * @param cookie 原始 cookie（登录态的唯一来源）
     * @return 同一个 builder，按需追加 `uin` / `authst`；**未登录或票据缺失时原样返回**
     *   （绝不发半份身份 —— 那正是「看起来登录了但取链失权」这类问题最难查的形态）。
     */
    fun applyIdentity(builder: JSONObject, cookie: String?): JSONObject {
        val uin = uinOf(cookie) ?: return builder
        val key = musicKeyOf(cookie) ?: return builder
        return builder.put("uin", uin.toString()).put("authst", key)
    }

    /**
     * 诊断用：列出这份 cookie 里**有哪些字段**（v2.1.4）。
     *
     * 只给字段名，**绝不给值** —— 票据是账号凭证，任何一行日志都不该把它带出去。
     * 存在的意义是把「取链失权」分成几类：票丢了、票在但 uin 丢了、
     * 还是票和 uin 都在（那就不是登录态的问题，要往服务端权限方向查）。
     */
    fun fieldNamesOf(cookie: String?): String =
        parse(cookie).keys.joinToString(",").ifEmpty { "(none)" }

    /**
     * 是否已登录：**必须同时有 uin 与票据**。
     *
     * 只看票据不认 uin 会在「票据过期但仍留在 cookie 里」时误判成已登录，
     * 表现是「设置页显示已登录，一播放就跳歌」——比干脆显示未登录更难排查。
     */
    fun isLoggedIn(cookie: String?): Boolean =
        uinOf(cookie) != null && musicKeyOf(cookie) != null

    /**
     * 取出用于 `musicu.fcg` 请求的 cookie 串：只保留我们认识的字段，
     * **过滤掉与 qm 无关的第三方 cookie**（QQ 域下还会有一堆 `ptui_loginuin`、
     * `RK`、`ptcz` 之类），它们既没用又会在日志里泄露更多身份信息。
     *
     * 注意：过滤只发生在**发请求**这一侧，落盘的是完整原始 cookie
     * （下次登录可能需要其中的字段来续期）。
     */
    fun requestCookie(cookie: String?): String {
        val map = parse(cookie)
        val keep = listOf(
            KEY_UIN, KEY_WXUIN, KEY_MUSIC_KEY, KEY_MUSIC_KEY_ALT, "psrf_qqunionid",
            "psrf_qqopenid", "psrf_access_token_expiresAt", "wxrefresh_token", "refresh_token",
            "psrf_musickey_createtime", "euin", "psrf_qqrefresh_token",
            // v2.1.1：实测的请求 Cookie 里就有它（PHASE0-QQMUSIC-API.md §4.5.1
            // 的 `Cookie: uin=…; qqmusic_uin=…; qm_keyst=…; qqmusic_key=…`），
            // 手机号登录拿回来的凭证里也补了它。有就带、没有不影响。
            "qqmusic_uin",
        )
        val picked = keep.mapNotNull { k -> map[k]?.takeIf { it.isNotEmpty() }?.let { k to it } }
        return picked.joinToString("; ") { it.first + "=" + it.second }
    }
}
