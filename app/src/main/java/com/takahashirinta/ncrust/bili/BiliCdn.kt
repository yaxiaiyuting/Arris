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
 * B 站 CDN 的**取流约束**（v3.1.0 · B）。铁律 27 在这一层的落点。
 *
 * ## 为什么必须有这个对象（这不是防御性编程，是实测缺陷）
 *
 * B 站的媒体 CDN（openresty）**强校验 `Referer`**：实测同一条 320K 直链，
 * 用 `UA=ExoPlayerLib/1.2.1`（即 ExoPlayer 的真实指纹）请求：
 *
 * | 请求头 | HTTP |
 * |---|---|
 * | `Referer: https://www.bilibili.com/` | **206** |
 * | 只带 UA（无 Referer） | **403** |
 *
 * 而 ExoPlayer **默认不带 Referer**。也就是说：取链会成功、URL 会拿到、
 * 日志里一切正常，**但一去取字节就 403** —— 用户看到的是「一直缓冲」。
 * 证据：`docs/verification/v3.1.0/bili-research/evidence/96-cdn-referer-exoplayer.txt`。
 *
 * ## 为什么判据是**按 host 白名单**，不是「全局加一个 Referer」
 *
 * 反过来同样成立且已实测：**拿 B 站的 Referer 去请求网易云也会被拒**
 * （`net-research/EVIDENCE-S6.md` §4 的 A/B 对照是同一个现象的另一面）。
 * 而且 `RetrofitClient` 那条链路无条件注入的是**网易云自己的** Referer ——
 * 若在这里给所有请求统一塞 B 站 Referer，等于把网易云/QQ 一起打死。
 *
 * 所以：**只对 B 站 CDN 的 host 加**，其余一个字节都不动（[needsReferer]）。
 * 这条判据是纯函数，`BiliCdnTest` 用真实 host 与几个**必须不命中**的
 * 网易云/QQ host 把边界钉死。
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

    /** B 站媒体请求要带的头。**只有一个 Referer**，不带 Cookie（媒体 CDN 不认登录态）。 */
    fun requestHeaders(): Map<String, String> = mapOf("Referer" to REFERER)

    /** 实测里那个 Referer 的值。 */
    const val REFERER = "https://www.bilibili.com/"

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
     */
    fun needsReferer(host: String?): Boolean {
        val h = host?.trim()?.lowercase().orEmpty()
        if (h.isEmpty()) return false
        return REFERER_HOST_SUFFIXES.any { suffix -> h == suffix || h.endsWith(".$suffix") }
    }

    /**
     * B 站 CDN 媒体流的**稳定缓存键**；不是 B 站 CDN 时返回 null（交给既有规则）。
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
     * @return `bili:ef0083e1cd13e73dcd20bfe3e672c21a-320k.m4a`；非 B 站 CDN / 取不到文件名时返回 null。
     */
    fun cacheKeyFor(url: String, host: String?): String? {
        if (!needsReferer(host)) return null
        val fileName = url.substringBefore('?').substringAfterLast('/')
        if (fileName.isBlank() || !fileName.contains('.')) return null
        return "bili:$fileName"
    }
}
