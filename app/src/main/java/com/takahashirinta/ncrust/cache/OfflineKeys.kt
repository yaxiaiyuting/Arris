/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
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
    private const val QUERY_PARAMETER = "$QUERY_KEY="
    private const val SONG_PREFIX = "song:"

    /** \`song:123:lossless\`。 */
    fun key(songId: Long, level: String): String = SONG_PREFIX + songId + ":" + level

    /** 把 key 挂到 URL 上（已有 query 用 & 连接）。 */
    fun withKey(url: String, songId: Long, level: String): String {
        if (url.isEmpty() || songId <= 0L || level.isEmpty()) return url
        if (url.contains(QUERY_PARAMETER)) return url
        val sep = if (url.contains("?")) "&" else "?"
        return url + sep + QUERY_PARAMETER + key(songId, level)
    }

    /** Separate full/trial bytes from legacy unknown files, retaining the song/quality grammar. */
    fun withContentKey(url: String, songId: Long, level: String, content: OfflineContent): String =
        if (content.variant.isEmpty()) withKey(url, songId, level)
        else withKey(url, songId, level + "~" + content.variant)

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
            if (end - i >= QUERY_PARAMETER.length && url.startsWith(QUERY_PARAMETER, i)) {
                return url.substring(i + QUERY_PARAMETER.length, end).ifEmpty { null }
            }
            i = end + 1
        }
        return null
    }

    /** 从 key 里解出档位；解不出返回 null。 */
    fun levelOf(key: String): String? {
        val separator = qualitySeparator(key)
        if (separator < 0) return null
        val start = separator + 1
        val suffix = key.indexOf('~', start)
        val end = if (suffix >= 0) suffix else key.length
        return key.substring(start, end).ifEmpty { null }
    }

    /** 从 key 里解出歌曲 id；解不出返回 null。 */
    fun songIdOf(key: String): Long? {
        val separator = qualitySeparator(key)
        if (separator <= SONG_PREFIX.length) return null
        val first = SONG_PREFIX.length
        val start = if (key[first] == '+') first + 1 else first
        var id = 0L
        for (index in start until separator) {
            val ch = key[index]
            val digit = if (ch in '0'..'9') ch - '0' else Character.digit(ch, 10)
            if (digit < 0 || id > Long.MAX_VALUE / 10 ||
                (id == Long.MAX_VALUE / 10 && digit > Long.MAX_VALUE % 10)
            ) return null
            id = id * 10 + digit
        }
        return if (id > 0L) id else null
    }

    private fun qualitySeparator(key: String): Int {
        if (!key.startsWith(SONG_PREFIX)) return -1
        val separator = key.indexOf(':', SONG_PREFIX.length)
        return if (separator >= 0 && key.indexOf(':', separator + 1) < 0) separator else -1
    }

    /** 按歌曲累加实际量到的字节。没有可测片段的歌曲不写入结果。 */
    internal inline fun sumBytesBySong(
        keys: Iterable<String>,
        songIds: Set<Long>,
        bytesForKey: (String) -> Long?,
    ): Map<Long, Long> {
        val totals = HashMap<Long, Long>()
        for (key in keys) {
            val songId = songIdOf(key) ?: continue
            if (songId !in songIds) continue
            val bytes = bytesForKey(key) ?: continue
            totals[songId] = (totals[songId] ?: 0L) + bytes
        }
        return totals
    }

    /** Check Media3's signed range lengths. The uncached tail must extend beyond all cached bytes. */
    internal inline fun coversStart(cachedLength: (offset: Long, limit: Long) -> Long): Boolean {
        val prefix = cachedLength(0L, Long.MAX_VALUE)
        if (prefix <= 0L) return false
        val tail = Long.MAX_VALUE - prefix
        return tail == 0L || cachedLength(prefix, tail) == -tail
    }
}
