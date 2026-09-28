/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · B：B 站 CDN 的取流约束。**纯逻辑，JVM 可单测。**
 */

package com.takahashirinta.ncrust.bili

/**
 * B 站 CDN 的**取流约束**（v3.1.0 · B，v3.2.4 修 UA 缺口）。铁律 27 在这一层的落点。
 *
 * ## 闸门有**两道**，v3.1.0 只补了第一道（这就是 v3.2.4 的 P0）
 *
 * B 站的媒体 CDN（openresty）对**每一条**取字节的请求同时校验：
 *
 * | 闸门 | 判据（实测） | v3.1.0 | v3.2.4 |
 * |---|---|---|---|
 * | ① `Referer` | 必须是 B 站域（`Origin` **不能**替代；`Referer: https://music.163.com/` ⇒ 403） | ✅ 已补 | 不变 |
 * | ② `User-Agent` | **必须存在**，且**不含黑名单子串**（至少 `android` / `dalvik` / `curl` / `python` / `vlc`，大小写不敏感） | ❌ **没管** | ✅ 本版补 |
 *
 * 也就是说：取链会成功、URL 会拿到、日志里一切正常，**但一去取字节就 403** ——
 * 用户看到的是「一直缓冲」。证据：`docs/verification/v3.2.4/probe-bili-playback.md`
 * 的完整 UA 矩阵（同一条直链、同一个 Referer，只换 UA：`ExoPlayerLib/1.5.0` ⇒ 206、
 * `Dalvik/2.1.0 (…Android 13…)` ⇒ 403）。
 *
 * ## ⚠️ 为什么 UA 必须在**工厂**上设，不能塞进 `DataSpec`
 *
 * media3 的 `DefaultHttpDataSource.makeConnection` 里，`userAgent` 字段是 `final`，
 * 而且**最后**才写进连接（bytecode 偏移 166-182，晚于 `dataSpec.httpRequestHeaders` 的 69-139）。
 * 所以「把 UA 放进 [requestHeaders] 再交给 `DataSpec.withRequestHeaders`」**是无效的** ——
 * 会被工厂的 UA 覆盖。本版两件事都做：`requestHeaders()` 里带上 UA（对其他数据源实现与
 * 未来的实现都是正确契约），同时由 `OfflineAudioCache` 按 URI 选一个
 * `DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT)`。
 *
 * ## 为什么判据是**「白名单 ∪ 取链见过的 host」**，不是「全局加头」
 *
 * 反过来同样成立且已实测：**拿 B 站的 Referer 去请求网易云也会被拒**
 * （`net-research/EVIDENCE-S6.md` §4 的 A/B 对照是同一个现象的另一面）。
 * 而且 `RetrofitClient` 那条链路无条件注入的是**网易云自己的** Referer ——
 * 若在这里给所有请求统一塞 B 站头，等于把网易云/QQ 一起打死。
 *
 * 所以只对 B 站媒体 URL 加头。判据两条取或（[isBiliMedia]）：
 *
 * 1. **host 后缀白名单**（[needsReferer]，v3.1.0 的判据，覆盖 `*.bilivideo.com` 等）；
 * 2. **本进程从 B 站取链响应里见过的 host**（[markStream] 学到的，有界 LRU）——
 *    这一条是 v3.2.4 补的：PCDN 边缘节点用的是**与 B 站无关的第三方域名**
 *    （v3.1.0 实测抓到 `b-…edge.mountaintoys.cn`），只靠后缀白名单会漏掉它们，
 *    而「这条 URL 是 B 站刚刚发给我们的」是一个**比域名更可靠**的事实。
 *
 * 两条判据都只可能命中「B 站自己的媒体」，网易云/QQ 的 host 一个都不会进。
 * `BiliCdnTest` 用真实 host 与几个**必须不命中**的网易云/QQ host 把边界钉死。
 */
object BiliCdn {

    /**
     * 需要 B 站 Referer 的 host 后缀白名单。
     *
     * 依据是实测抓到的那几条直链的 host 与 B 站公开的媒体域：
     * - `upos-sz-mirrorhw.bilivideo.com`（实测 320K/192K 直链）
     * - `b-baaa6b14dc82ptf2i9ztakm7g4wue.edge.mountaintoys.cn`（实测 DASH 音轨，
     *   PCDN 边缘节点，域名与 B 站无关 ⇒ **判据不能只靠后缀**，见 [needsReferer] 的兜底）
     * - `i0.hdslb.com` / `i1.hdslb.com`（封面，通常不需要，但带上无害）
     *
     * ⚠️ 白名单是「已知需要」的集合，不是「全部需要」的集合。PCDN 节点用的是
     * 第三方域名，所以 [needsReferer] 还有一条**不依赖 host** 的兜底判据。
     */
    val REFERER_HOST_SUFFIXES: List<String> = listOf(
        "bilivideo.com",
        "bilivideo.cn",
        "hdslb.com",
        "bilibili.com",
        "akamaized.net",
    )

    /** B 站媒体请求要带的头。**不带 Cookie**（媒体 CDN 不认登录态）。 */
    fun requestHeaders(): Map<String, String> =
        mapOf("Referer" to REFERER, "User-Agent" to USER_AGENT)

    /** 实测里那个 Referer 的值。 */
    const val REFERER = "https://www.bilibili.com/"

    /**
     * v3.2.4：**取流**必须用的 `User-Agent`（与 `BiliApi` 取链用的是同一个身份）。
     *
     * ## 为什么是这一串，而不是「自己编一个不会被封的」
     *
     * 实测（`docs/verification/v3.2.4/evidence/ua-android-hypothesis.txt`）：
     * CDN 对 UA 做**子串黑名单**，命中即 403 —— `android` / `dalvik` / `curl` /
     * `python` / `vlc` 全部被封（`Android`、`myandroidapp/1.0`、`CURL/1.0`、
     * `python-requests/2.31` 一样 403）。而**完全不带 UA 也 403**。
     *
     * 所以取一个**已实测 206 且身份诚实**的值：桌面 Chrome UA，
     * 与 [BiliApi] 的取链身份**逐字相同**。
     * 反面教材：`DefaultDataSource.Factory(Context)` 造的是裸
     * `DefaultHttpDataSource.Factory()`，`userAgent == null` ⇒ 交给 `HttpURLConnection`
     * 的默认值 `Dalvik/2.1.0 (Linux; U; Android …)` ⇒ 同时命中两个字样 ⇒ 403。
     *
     * ⚠️ 不要把这一串「顺手优化」成手机 UA：实测
     * `Mozilla/5.0 (Linux; Android 13; Pixel 7) … Mobile Safari/537.36` **是 403**
     * （里面那个 `Android` 就是原因）。
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 这个 host 的媒体请求要不要带 B 站 Referer。
     *
     * 两条判据取**或**：
     * 1. host 落在 [REFERER_HOST_SUFFIXES] 里（`bilivideo.com` 及其子域）；
     * 2. host 是本应用**已知的非 B 站域名**吗 —— 不是。这一条留给调用方：
     *    本函数只回答「是不是 B 站 CDN」，**不认识的一律返回 false**
     *    （宁可漏加 Referer 也不要给网易云/QQ 加上 —— 那会把能播的歌打死）。
     *
     * 也就是说 PCDN 那种第三方域名**不在**白名单里，会走「不带 Referer」的路。
     * 这是**有意的保守取舍**：漏加的症状是「部分 B 站歌放不出来」，
     * 误加的症状是「网易云和 QQ 全部放不出来」。前者可诊断，后者是灾难。
     *
     * ⚠️ v3.2.4：这个「保守取舍」的缺口由 [isBiliMedia] / [markStream] 补上 ——
     * **判据不再只看域名**，而是「域名 ∪ 这条 URL 是 B 站刚发给我们的」。
     * 本函数保留原语义（纯 host 白名单），供 [isBiliMedia] 的第一条判据与既有单测使用。
     */
    fun needsReferer(host: String?): Boolean {
        val h = host?.trim()?.lowercase().orEmpty()
        if (h.isEmpty()) return false
        return REFERER_HOST_SUFFIXES.any { suffix -> h == suffix || h.endsWith(".$suffix") }
    }

    // ------------------------------------------------- 取链学到的 host（v3.2.4） ----

    /**
     * 本进程从 B 站取链响应里**见过**的 host（有界 LRU，最多 [LEARNED_HOST_LIMIT] 个）。
     *
     * 为什么是 host 粒度而不是完整 URL：直链的 query 每次都变（签名 + `deadline`），
     * 按 URL 记等于记一堆命中不了的条目；而「B 站把这条流放在哪个域名上」是稳定的。
     *
     * 为什么有界：这是一个**长跑进程里会持续增长**的集合，不设上限就是内存泄漏的另一种写法。
     * 64 个 host 足够覆盖 B 站的 CDN 轮换（实测一次取链只出现 1~3 个域名）。
     * 淘汰最旧的：新学到的 host 才是当下在用的。
     */
    private const val LEARNED_HOST_LIMIT = 64

    private val learnedHosts = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > LEARNED_HOST_LIMIT
    }

    /**
     * 记下「这个 host 上的 URL 是 B 站刚发给我们的」。
     *
     * **唯一调用点是取链路径**（`BiliSourceProvider` 把 `BiliStream` 翻成 `SongUrlResult` 时），
     * 所以这个集合里的每一个 host 都有一条**可追溯的出处**：它来自 B 站自己的
     * `playurl` / `audio/music-service-c/url` 响应。绝不接受 UI、队列、持久化里来的 host ——
     * 那会让这条判据变成一个可以被外部输入影响的开关。
     *
     * 传入 null / 空 host / 非 http(s) 协议一律忽略（不抛异常：这是每首歌都会走的路径）。
     */
    fun markStream(url: String?) {
        val host = hostOf(url) ?: return
        if (host.isEmpty() || needsReferer(host)) return
        synchronized(learnedHosts) { learnedHosts[host] = true }
    }

    /** 单测用：清空学到集合，避免用例之间互相污染。生产代码里没有调用点。 */
    internal fun clearLearnedHostsForTest() {
        synchronized(learnedHosts) { learnedHosts.clear() }
    }

    /** 单测用：当前学到的 host 数（判「有界」）。 */
    internal fun learnedHostCountForTest(): Int = synchronized(learnedHosts) { learnedHosts.size }

    /**
     * v3.2.4：**这条媒体请求要不要按 B 站的方式加头**（Referer + UA）。
     *
     * 判据 = [needsReferer]（域名白名单）∪ [markStream] 学到的 host。
     * 两者都只可能命中 B 站媒体，网易云 / QQ / 本地文件一律 false。
     *
     * 这是数据源层**唯一**的判据入口：`OfflineAudioCache` 的选源与加头都问它，
     * 绝不允许两处各写一份（铁律 26：对称位置的保护必须对称）。
     */
    fun isBiliMedia(host: String?): Boolean {
        if (needsReferer(host)) return true
        val h = host?.trim()?.lowercase().orEmpty()
        if (h.isEmpty()) return false
        return synchronized(learnedHosts) { learnedHosts.containsKey(h) }
    }

    /** 从 URL 里取小写 host；取不到返回 null（不抛异常）。 */
    private fun hostOf(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (!raw.startsWith("http://", true) && !raw.startsWith("https://", true)) return null
        return runCatching { java.net.URI(raw).host?.lowercase() }.getOrNull()
    }

    /**
     * B 站 CDN 媒体流的**稳定缓存键**；不是 B 站媒体时返回 null（交给既有规则）。
     *
     * ## 为什么不能按完整 URL 做 key
     *
     * 直链的 `deadline` 恒为 `now + 7200`（实测），两小时后同一首歌会拿到一条
     * **签名完全不同**的 URL。按完整 URL 做 key，缓存里就只会堆一批永远命中不了的片段
     * —— 对用户是「白占磁盘」，对系统是「LRU 被无效条目挤爆」。
     *
     * ## 稳定在哪：B 站 CDN 的路径是**内容寻址**的
     *
     * 实测两条同曲不同时刻的直链：
     * `…/ugaxcode/0e35503a10eddafee44a1dfee6ff09f7-192k.m4a?e=…&deadline=…` 与
     * `…/ugaxcode/ef0083e1cd13e73dcd20bfe3e672c21a-320k.m4a?…`
     * —— **query 每次都不一样，路径里的那串哈希 + 档位后缀每次都一样**。
     * 所以键取「文件名」（`<hash>-<档位>.m4a`），并加 `bili:` 前缀与既有键空间隔离。
     *
     * v3.2.4：判据从 [needsReferer] 换成 [isBiliMedia] —— PCDN 那种第三方域名同样
     * 是内容寻址的（同一份实测），漏了它们就等于「这些歌每次播放都写一份新缓存」。
     *
     * @return `bili:ef0083e1cd13e73dcd20bfe3e672c21a-320k.m4a`；非 B 站媒体 / 取不到文件名时返回 null。
     */
    fun cacheKeyFor(url: String, host: String?): String? {
        if (!isBiliMedia(host ?: hostOf(url))) return null
        val fileName = url.substringBefore('?').substringAfterLast('/')
        if (fileName.isBlank() || !fileName.contains('.')) return null
        return "bili:$fileName"
    }
}
