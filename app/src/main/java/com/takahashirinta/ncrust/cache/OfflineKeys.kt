/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.cache

/**
 * 离线音频缓存的 key 规则（v1.6.0 · D1）。**纯逻辑**，无 Android 依赖，JVM 可单测。
 *
 * ## 为什么不能直接用 URL 当 cache key
 *
 * v1.5.0 的调研（AGENTS.md「离线下载」节）实测：网易的播放 URL **每次都变**
 * （host 在 m704/m804 间轮换、路径里带签发时间戳、20 分钟过期）。media3 的
 * SimpleCache 默认按 URL 做 key，那样缓存**永远不可能命中** —— 每次取链都是一个新 key，
 * 一首歌放十遍就存十份。
 *
 * 但同一个实测还给出了一条关键事实：**URL 的 query 参数是装饰**（删掉/篡改仍然 206），
 * CDN 只认路径里的签名。所以我们可以往 URL 上挂一个自己的参数当 key，既不影响播放，
 * 又能让「同一首歌 + 同一档位」的所有 URL 映射到同一份缓存。
 *
 * key 的形状是 \`song:<songId>:<level>\` —— 档位进 key 是**故意**的：
 * 无损和高解析是两个不同的文件，混在一起会播出错音频。
 */
object OfflineKeys {

    /** 挂在播放 URL 上的 query 参数名。小写无下划线，避免被某些 CDN 规则改写。 */
    const val QUERY_KEY = "ncrustkey"

    /** \`song:123:lossless\`。 */
    fun key(songId: Long, level: String): String = "song:" + songId + ":" + level

    /** 把 key 挂到 URL 上（已有 query 用 & 连接）。 */
    fun withKey(url: String, songId: Long, level: String): String {
        if (url.isEmpty() || songId <= 0L || level.isEmpty()) return url
        if (url.contains(QUERY_KEY + "=")) return url
        val sep = if (url.contains("?")) "&" else "?"
        return url + sep + QUERY_KEY + "=" + key(songId, level)
    }

    /**
     * 从 URL 里取回 key；没有就返回 null（调用方回落到「用 URL 当 key」的默认行为）。
     * 只做字符串处理，不引 java.net.URI —— 播放 URL 里的签名含 \`+\` \`/\` \`=\`，URL 解析器可能报错。
     */
    fun keyOf(url: String): String? {
        val q = url.indexOf('?')
        if (q < 0) return null
        var i = q + 1
        while (i < url.length) {
            var end = url.indexOf('&', i)
            if (end < 0) end = url.length
            val eq = url.indexOf('=', i)
            if (eq in i until end && url.substring(i, eq) == QUERY_KEY) {
                val v = url.substring(eq + 1, end)
                return v.ifEmpty { null }
            }
            i = end + 1
        }
        return null
    }

    /** 从 key 里解出档位；解不出返回 null。 */
    fun levelOf(key: String): String? {
        val parts = key.split(':')
        return if (parts.size == 3 && parts[0] == "song") parts[2].ifEmpty { null } else null
    }

    /** 从 key 里解出歌曲 id；解不出返回 null。 */
    fun songIdOf(key: String): Long? {
        val parts = key.split(':')
        if (parts.size != 3 || parts[0] != "song") return null
        return parts[1].toLongOrNull()?.takeIf { it > 0L }
    }

    /**
     * v3.3.0：判定「缓存里这一段能不能**从头播**」—— 即 position 0 是否被一段
     * **磁盘上真实存在**的片段覆盖。
     *
     * ## 为什么原来的判据不够（用户反馈第 1 条「离线播放不太行」的第二个缺陷）
     *
     * 原判据是 `SimpleCache.keys.contains(key)` —— 「这首歌**有任何片段**」。
     * 但播放器是**从 position 0 开始读**的，而缓存里可能只有中段：
     * seek 过去听了一段、或者上次只缓冲到一半就切歌了。此时：
     *
     * - 离线兜底放行 ⇒ 起播正常（0 那一段在缓存里）⇒ 播到洞的位置**突然卡死**，
     *   然后弹音质降级；用户读到的就是「播一半就断」；
     * - 观感比「直接说放不了」更糟：它给了用户一个「能放」的承诺。
     *
     * ## 为什么是「覆盖 0」而不是「覆盖整曲」
     *
     * 要求整曲会**过度收紧**：本应用没有下载功能，离线范围就是「本机真播过的片段」，
     * 而「播过的片段」通常是「从头到某个位置」。要求整曲会让绝大多数真实缓存被判不可用，
     * 把上一版的功能整体关掉。而「能从头开始播」正是缓存唯一能承诺的事 ——
     * 后面的洞由网络补，断网时才会露出边界（这一条如实写在这里，不假装已经解决）。
     *
     * ## 纯函数
     *
     * 输入是 `(position, length, isCached)` 三元组，因此可以在 JVM 里覆盖
     * 「空、只覆盖 0 那一点、覆盖 0 到中段、从中段开始、多个片段接上 0」等形状，
     * 不需要真的建一个 SimpleCache。
     *
     * @param spans 该 key 的全部 span（顺序无关，内部会排序）。
     * @return true = 存在一段从 0 起的连续**已缓存**覆盖。
     */
    fun coversStart(spans: List<CachedSpan>): Boolean {
        val cached = spans.filter { it.isCached && it.length > 0L }.sortedBy { it.position }
        if (cached.isEmpty()) return false
        // 第一段必须从 0 起（position <= 0 都算，SimpleCache 的 position 不会是负数，
        // 但写 <= 是为了让判据对「实现细节变了」保持健壮）。
        if (cached.first().position > 0L) return false
        var coveredUpTo = cached.first().let { it.position + it.length }
        for (span in cached.drop(1)) {
            // 允许相邻片段之间**不留缝**（后一段的起点 <= 已覆盖到的位置）。
            if (span.position > coveredUpTo) return false
            coveredUpTo = maxOf(coveredUpTo, span.position + span.length)
        }
        return coveredUpTo > 0L
    }

    /**
     * 一个缓存片段的最小形状。**只保留判据需要的两个字段** ——
     * 这样 [coversStart] 不必依赖 media3 的 `CacheSpan`（那是 Android 类型，
     * 会把这条纯逻辑拖进 instrumented test 才能跑的地界）。
     */
    data class CachedSpan(val position: Long, val length: Long, val isCached: Boolean)
}
