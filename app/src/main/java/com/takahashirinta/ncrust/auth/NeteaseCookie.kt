/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0：ncm cookie 的**合并、规范化与登录判据**。纯逻辑，JVM 可单测。
 */

package com.takahashirinta.ncrust.auth

/**
 * ncm cookie 的纯逻辑部分。
 *
 * ## 为什么要有这个对象（用户反馈第 6 条：「需要很多次退出登录再登录才能播放 VIP 资源或者音质」）
 *
 * 那条反馈的症状是**概率性**的，而概率性通常意味着「多次掷骰子里有一次成功」。
 * 实测勘察确认了三条独立的机制，本对象负责其中的第一条与第二条：
 *
 * 1. **登录态靠 `onPageFinished` 一次性快照抓取**（真正的掷骰子）。
 *    ncm 登录页是 `https://music.163.com/#/login` 的 **hash 路由 SPA** ——
 *    登录成功后的跳转**不产生新的文档级导航**，因此常常**不触发** `onPageFinished`。
 *    抓取时机是否落在「cookie 已写入且页面回调恰好到来」这个窗口里，
 *    每次重登都是一次独立的伯努利试验 ⇒ 命中次数服从几何分布 ⇒
 *    「要试很多次才偶尔成功」。
 *    ⚠️ 同仓库的 **QQ 侧早已因为同一个坑改成轮询 cookie**
 *    （`QqLoginOverlay` 的 KDoc：「登录成功的唯一权威事实是 cookie，
 *    所以这里按固定间隔直接读 cookie，不依赖任何页面回调」）—— ncm 这条当年没跟着改。
 *    本版把轮询搬到 ncm 侧。
 *
 * 2. **写入是覆盖而不是合并，且完不完整的判据只有一条 `MUSIC_U`**。
 *    三条登录路径写入的形状不同（WebView 整串 / 扫码只取 803 响应的
 *    `Set-Cookie` 增量 / 局域网回传），覆盖写会让**先到的那一批键丢掉**。
 *    而 ncm 的会话**不止 `MUSIC_U`**：实测真机 cookie 有 20 个键，其中
 *    `MUSIC_U` 是会话票据、`__csrf` 是写操作的必需项（缺它写操作恒 403
 *    `illegal request!`，见 AGENTS.md 的 Playlist 写操作一节）。
 *    只判 `MUSIC_U` 会把「缺 `__csrf` 的半截会话」也当成登录成功。
 *
 * 3. **取链身份被用户 cookie 的同名键压制**（第三条机制，落点在 `ClientIdentity`，不在本文件）。
 *
 * ## 本对象的边界
 *
 * 只做**字符串层面的合并与判据**，不碰网络、不碰 SharedPreferences ——
 * 那两件事分别由调用方做。这样「合并会不会丢键」「半截会话算不算登录」
 * 都能在 JVM 里被逐条断言，而不用上真机重登十次去碰运气。
 */
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

    /**
     * 合并两份 cookie：**同名取新值，不同名全部保留**。
     *
     * 这是本版修「反复重登」的关键一步：登录过程中可能先拿到一批（如导航响应里的
     * `NMTID`），再拿到一批（登录接口的 `Set-Cookie`），覆盖写会丢掉先到的那批。
     *
     * ⚠️ **空值不覆盖已有值**。`parse` 会把 `appver=` 这种解析成空串，
     * 而「键存在但值为空」在服务端是**有效输入**（它会覆盖默认身份），
     * 所以这里必须原样保留空串（见 `ClientIdentity` 那条同源缺陷）。
     * 但要区分「值为空」与「键不存在」：前者保留键，后者不引入键。
     */
    fun merge(old: String?, new: String?): String {
        val map = LinkedHashMap<String, String>()
        map.putAll(parse(old))
        for ((k, v) in parse(new)) map[k] = v
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 这是一份**可用的**登录态吗。
     *
     * 判据是 [REQUIRED_KEYS] 全部存在且**非空**（空串 `__csrf=` 与缺失等价：
     * 服务端拿到空 csrf 照样 403）。
     */
    fun isUsable(raw: String?): Boolean {
        val map = parse(raw)
        return REQUIRED_KEYS.all { !map[it].isNullOrEmpty() }
    }

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
        if (isUsable(new)) return true
        if (isUsable(old)) return false
        return hasSession(new)
    }

    /** 只打键名与长度，**绝不打值**（凭据泄漏面：这些值能直接冒充用户）。 */
    fun describe(raw: String?): String {
        val map = parse(raw)
        if (map.isEmpty()) return "(空)"
        return map.entries.joinToString(", ") { "${it.key}(len=${it.value.length})" }
    }
}
