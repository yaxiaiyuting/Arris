/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0：ncm cookie 的**合并、规范化与登录判据**。纯逻辑，JVM 可单测。
 */

package com.takahashirinta.ncrust.auth

/** 网易云会话字段的解析、合并和有效性判定。 */
object NeteaseCookie {

    /** 会话票据。**它是登录的必要条件，但不是充分条件**（见 [REQUIRED_KEYS]）。 */
    const val KEY_MUSIC_U = "MUSIC_U"

    /**
     * 写操作（歌单增删改、收藏）必需的 CSRF token。
     *
     * 缺它的后果不是「少个字段」，而是**所有写操作 403 `illegal request!`**
     * —— 这是 AGENTS.md 里 Playlist 写操作一节实测过的结论。
     */
    const val KEY_CSRF = "__csrf"

    /**
     * 判定「这是一份可用的登录态」所要求的键。
     *
     * 取两个而不是一个：`MUSIC_U` 单独存在时，用户会看到「已登录」但收藏/歌单编辑
     * 全部 403 —— 一个**看起来成功、用起来失败**的状态，比直接显示未登录更难排查。
     *
     * ⚠️ **不要**把 `MUSIC_A`、`NMTID`、`__remember_me` 也加进来：
     * 它们不是所有登录路径都会给（扫码路径实测只回增量），
     * 加进来会把「能用的会话」判成未登录 —— 那是与本节要修的缺陷**相反方向**的错。
     */
    val REQUIRED_KEYS = listOf(KEY_MUSIC_U, KEY_CSRF)

    /**
     * 把 cookie 串解析成有序键值表。
     *
     * 容错点（每一条都对应一种真实输入）：
     * - 分隔符同时接受 `;` 与换行（WebView 给的是一行，`Set-Cookie` 逐条给的是多行）；
     * - **值里允许出现 `=`**（只按第一个 `=` 切分）—— `MUSIC_U` 的真实值里就带 `=` 填充；
     * - 空白两侧 trim；空 key 丢弃。
     */
    fun parse(raw: String?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return out
        for (part in text.split(';', '\n', '\r')) {
            val piece = part.trim()
            if (piece.isEmpty()) continue
            val eq = piece.indexOf('=')
            if (eq <= 0) continue
            val key = piece.substring(0, eq).trim()
            val value = piece.substring(eq + 1).trim()
            if (key.isEmpty()) continue
            out[key] = value
        }
        return out
    }

    /** 保留既有键的顺序。同名字段采用新值，空值也参与覆盖。 */
    fun merge(old: String?, new: String?): String {
        val map = parse(old)
        map.putAll(parse(new))
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 这是一份**可用的**登录态吗。
     *
     * 判据是 [REQUIRED_KEYS] 全部存在且**非空**（空串 `__csrf=` 与缺失等价：
     * 服务端拿到空 csrf 照样 403）。
     */
    fun isUsable(raw: String?): Boolean {
        return hasRequiredKeys(parse(raw))
    }

    private fun hasRequiredKeys(values: Map<String, String>): Boolean =
        REQUIRED_KEYS.all { !values[it].isNullOrEmpty() }

    /** 会话票据是否存在（**不足以**判定可用，见 [isUsable]）。 */
    fun hasSession(raw: String?): Boolean = !parse(raw)[KEY_MUSIC_U].isNullOrEmpty()

    /**
     * 这份新拿到的 cookie 是否**值得替换**当前保存的那份。
     *
     * 存在的理由：轮询会反复读到同一份 cookie，而轮询期间可能读到
     * 「页面还没写入 `__csrf`」的中间态。若无条件写回，轮询会**把完整的会话覆盖成半截的**
     * —— 那正好是本节要修的缺陷的镜像。
     *
     * 规则：
     * - 新 cookie 可用 ⇒ 值得（哪怕旧的也可用：新的是更新鲜的）；
     * - 新 cookie 不可用但旧的可用了 ⇒ **不值**（不许降级）；
     * - 两者都不可用 ⇒ 值得记下来（至少留下 `MUSIC_U`，让「已登录但写操作会 403」
     *   这个状态可见，而不是完全空白）。
     */
    fun shouldReplace(old: String?, new: String?): Boolean {
        val incoming = parse(new)
        if (hasRequiredKeys(incoming)) return true
        return !incoming[KEY_MUSIC_U].isNullOrEmpty() && !isUsable(old)
    }

    /** 只打键名与长度，**绝不打值**（凭据泄漏面：这些值能直接冒充用户）。 */
    fun describe(raw: String?): String {
        val map = parse(raw)
        if (map.isEmpty()) return "(空)"
        return map.entries.joinToString(", ") { "${it.key}(len=${it.value.length})" }
    }
}
