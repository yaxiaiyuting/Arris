/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.cache

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 播放 URL 的有界索引（v1.6.0 · D1）。**纯逻辑**，JVM 可单测。
 *
 * 缓存了音频字节还不够 —— 断网时客户端**先要拿到一个 URL** 才会去问 CacheDataSource，
 * 而网易的 URL 20 分钟就过期、每次都变。所以必须把「最后一次成功播过的 URL」按
 * [OfflineKeys] 的 key 存下来：离线时直接拿它去播，CacheDataSource 命中缓存即无需网络。
 */
internal class OfflineUrlIndex(private val maxEntries: Int = MAX_ENTRIES) {
    // LinkedHashMap 的迭代顺序 = 插入顺序，put 前先 remove 即得到 LRU。
    private val map = LinkedHashMap<String, OfflineUrlRecord>()

    /**
     * 「本会话已经证明播不出来」的 key（v3.7.38 · 回移自主线 4.1.5）。
     *
     * ## 为什么要有它
     *
     * 离线兜底会**退化成同一首歌的任意档位**。一条已经死掉的 URL（403 过期、CDN 换了
     * 签名）如果留在清单里，每次取链失败都会把它重新喂回播放器，形成一个固定点：
     * 请求 exhigh → 兜底给 lossless 的旧链 → 播不出 → 又请求 exhigh → 又是同一条。
     * 播放侧的降档重试有次数上限，这个环路本身不会无限转，但每一圈都是一次真实的
     * 「点了播放，没声音」。
     *
     * ## 为什么**不**落盘
     *
     * 它描述的是「这个进程在这一次尝试里亲眼看到了失败」——一次性事实。落盘会把一次
     * 网络抖动写成跨重启的长期禁令，而重启后重新取链本来就会写进新的 URL（[put] 里
     * 「URL 变了就解除」那条）。所以它只活在内存里，与清单本身（key → URL）的持久化
     * 生命周期无关：清单能从盘上重建，标记不能。
     */
    private val failedKeys = HashSet<String>()

    fun put(key: String, url: String, content: OfflineContent = OfflineContent()) {
        if (key.isEmpty() || url.isEmpty()) return
        val old = map.remove(key)
        // URL 变了就是换了文件：上一个 URL 上「播不出来」的结论不适用于新条目。
        // 同一条 URL 再写一次则**保留**标记 —— 那正是上面说的固定点闸门：重新取链
        // 仍然拿到同一条坏链时，离线兜底不许再把这条喂回播放器。
        if (old?.url != url) failedKeys.remove(key)
        map[key] = OfflineUrlRecord(url, if (!content.known && old?.url == url) old.content else content)
        while (map.size > maxEntries) {
            val it = map.keys.iterator()
            if (it.hasNext()) {
                val evicted = it.next()
                it.remove()
                // 条目被淘汰了，标记就必须跟着走：否则同名 key 以后重新写入时，
                // 一条全新的 URL 会继承上一个条目的「不可播」结论（冤案）。
                failedKeys.remove(evicted)
            } else {
                break
            }
        }
    }

    fun get(key: String): String? = map[key]?.url
    fun record(key: String): OfflineUrlRecord? = map[key]

    /**
     * 标记/解除「这条 URL 能播」（v3.7.38 · 回移自主线 4.1.5）。
     *
     * @param url 实际**起播过**的那个 URL，不是 key。为什么必须带 url：key 只到「歌 + 档位」，
     *   而同一个 key 上的 URL 会随服务端轮换；失败结论只对**这一条**成立，换了一条 URL
     *   就该重新判。传进来的 url 与索引里存的那条不一致时直接忽略 —— 那说明索引已经被
     *   新 URL 覆盖，旧结论已经没有意义。
     */
    fun setPlaybackUsable(key: String, url: String, usable: Boolean) {
        if (map[key]?.url != url) return
        if (usable) failedKeys.remove(key) else failedKeys.add(key)
    }

    /**
     * 换号 / 登出时作废所有失败标记。
     *
     * 为什么跟着账号走：URL 上的 403 大多是**会员/版权判定**的结果，换一个账号再试同一条
     * 离线条目是完全合理的；把上一个账号的结论留着，等于让新账号的离线兜底一直少一档。
     */
    fun clearFailures() {
        failedKeys.clear()
    }

    /** 已经判定播不出来的 key 数（对账 / 单测用，不暴露集合本身）。 */
    fun failureCount(): Int = failedKeys.size

    /**
     * 删掉这首曲子在清单里的**所有档位**条目（v2.0.0 · T3 联动删除）。返回删掉的条数。
     *
     * 与 [OfflineLibrary.removeWithUrls] 成对使用：曲目表删了而这里留着，
     * 离线兜底就会拿一个指向空缓存的死条目去起播。
     */
    fun removeSong(songId: Long): Int {
        if (songId <= 0L) return 0
        var removed = 0
        val it = map.keys.iterator()
        while (it.hasNext()) {
            val k = it.next()
            if (OfflineKeys.songIdOf(k) == songId) {
                it.remove()
                // 条目没了，标记也必须跟着没 —— 留着只会在同名 key 被重新写入时误禁新 URL。
                failedKeys.remove(k)
                removed++
            }
        }
        return removed
    }

    /** 排序后的 key 快照（对账用，不暴露内部 map）。 */
    fun keys(): List<String> = map.keys.toList()

    fun clear() {
        map.clear()
        // 「清空离线缓存」也是失败标记的到期点：用户清完之后重新下载/重新起播，
        // 没有任何理由继续记着上一轮那些 URL 的坏账。
        failedKeys.clear()
    }

    /**
     * 离线兜底用：先按 [preferredKeys] 的顺序找，再退化成「这首歌的**任意**档位」。
     * 离线时能放出来比「档位严格一致」重要得多（缓存里是什么档就放什么档）。
     */
    fun recall(preferredKeys: List<String>, songId: Long): Pair<String, String>? =
        recallRecord(preferredKeys, songId) { true }?.let { it.first to it.second.url }

    fun recallRecord(preferredKeys: List<String>, songId: Long, usable: (String) -> Boolean): Pair<String, OfflineUrlRecord>? {
        val levels = preferredKeys.mapNotNull(OfflineKeys::levelOf)
        val candidates = map.entries.filter { OfflineKeys.songIdOf(it.key) == songId }
            .sortedWith(compareBy<Map.Entry<String, OfflineUrlRecord>> { e ->
                levels.indexOf(OfflineKeys.levelOf(e.key)).let { if (it < 0) levels.size else it }
            }.thenBy { if (!it.value.content.known) 2 else when(it.value.content.kind) { OfflineContent.FULL -> 0; OfflineContent.TRIAL -> 1; else -> 2 } })
        // `k !in failedKeys` 与 `usable(k)`（缓存里有没有开头的字节）是两道独立的闸：
        // 前者是「这条 URL 已经试过播不出来」，后者是「这份字节根本不在盘上」。
        for ((k, v) in candidates) if (k !in failedKeys && usable(k)) return k to v
        return null
    }

    fun size(): Int = map.size

    fun toJson(): String = Gson().toJson(map)

    companion object {
        const val MAX_ENTRIES = 300

        fun fromJson(json: String?): OfflineUrlIndex {
            val idx = OfflineUrlIndex()
            if (json.isNullOrEmpty()) return idx
            runCatching {
                val root = com.google.gson.JsonParser.parseString(json).asJsonObject
                root.entrySet().forEach { (k, v) ->
                    runCatching {
                        if (v.isJsonPrimitive && v.asJsonPrimitive.isString) idx.put(k, v.asString)
                        else {
                            val record = Gson().fromJson(v, OfflineUrlRecord::class.java)
                            val content = record.content ?: OfflineContent()
                            val epochPresent = v.asJsonObject.getAsJsonObject("content")?.has("file_epoch") == true
                            idx.put(k, record.url, if (epochPresent) content else content.copy(fileEpoch = 0))
                        }
                    }
                }
            }
            return idx
        }
    }
}

/**
 * 离线 URL 落盘（v1.6.0 · D1）。
 *
 * 只存「key → 最后一次成功播放的 URL」，**不存 cookie、不存音频字节**（字节由
 * [OfflineAudioCache] 管）。清单有上限（[OfflineUrlIndex.MAX_ENTRIES]）并按 LRU 淘汰，
 * 所以它不会无限涨。
 */
object OfflineUrlStore {
    private const val PREFS = "ncrust_offline"
    private const val KEY_URLS = "urls"

    @Volatile
    private var index: OfflineUrlIndex? = null

    private fun index(context: Context): OfflineUrlIndex {
        index?.let { return it }
        synchronized(this) {
            index?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val loaded = OfflineUrlIndex.fromJson(prefs.getString(KEY_URLS, null))
            index = loaded
            return loaded
        }
    }

    private fun persist(context: Context, idx: OfflineUrlIndex) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_URLS, idx.toJson()).apply()
        }
    }

    /**
     * 在锁内改索引并**落盘**（v2.0.0 · T3 抽出）。所有写路径都必须走它，
     * 保证「内存里那份」与「磁盘上那份」永远是同一个状态 —— 之前每个写方法各写一遍
     * `synchronized + persist`，加一个写方法就多一次漏落盘的机会。
     */
    internal fun <T> mutate(context: Context, block: (OfflineUrlIndex) -> T): T = synchronized(this) {
        val idx = index(context)
        val result = block(idx)
        persist(context, idx)
        result
    }

    /** 记下「这个 key 最后一次成功播放的 URL」。 */
    fun remember(context: Context, key: String, url: String) {
        mutate(context) { it.put(key, url) }
    }

    /** 从播放 URL 上取下 key 并记录（服务端收到 URL 时调用，URL 上已带 key）。 */
    fun rememberFromUrl(context: Context, url: String) {
        OfflineKeys.keyOf(url)?.let { remember(context, it, url) }
    }

    /**
     * 标记/解除「这条 URL 能播」（v3.7.38 · 回移自主线 4.1.5）。**只动内存，不落盘**：
     * 清单的持久化语义是「最后一次成功播放的 URL」，失败标记是「本会话的观察结果」，
     * 两者生命周期不同（理由见 [OfflineUrlIndex.failedKeys]）。
     *
     * 清单还没加载进内存时是**无操作**（`index == null`）。这是有意为之：进程刚起来、
     * 一次离线兜底都还没发生过的时候，没有任何条目会被它污染；而为了打一个标记去读盘，
     * 会让播放失败这条热路径多一次主线程 I/O。
     */
    internal fun setPlaybackUsable(url: String, usable: Boolean) {
        val key = OfflineKeys.keyOf(url) ?: return
        synchronized(this) { index?.setPlaybackUsable(key, url, usable) }
    }

    /**
     * 换号 / 登出：作废所有「不可播」标记（v3.7.38 · 回移自主线 4.1.5）。
     *
     * 留在内存里的旧结论属于**上一个账号**的那批链接（403 多半是会员/版权判定），
     * 换了身份就该重新判一次；否则上一个账号的坏链会一直关着这个档位的离线兜底。
     */
    internal fun clearPlaybackFailures() {
        synchronized(this) { index?.clearFailures() }
    }

    /**
     * 联动删除（v2.0.0 · T3）：删掉这首歌在 URL 清单里的所有档位条目。返回删掉的条数。
     * 只有 [OfflineLibrary.remove] 会用 —— 曲目表与 URL 清单必须一起删。
     */
    internal fun forgetSong(context: Context, songId: Long): Int =
        mutate(context) { it.removeSong(songId) }

    /** 离线兜底：找回这首歌可用的 (key, url)。 */
    fun recall(context: Context, songId: Long, preferredLevels: List<String>): Pair<String, String>? =
        synchronized(this) {
            index(context).recall(preferredLevels.map { OfflineKeys.key(songId, it) }, songId)
        }

    fun rememberResult(context: Context, result: com.takahashirinta.ncrust.player.SongUrlResult): com.takahashirinta.ncrust.player.SongUrlResult {
        val key = OfflineKeys.keyOf(result.url) ?: return result
        return mutate(context) { idx ->
            val content = result.cachedContent ?: OfflineContent.from(result.playbackItem)
            val chosen = verifiedCacheKey(key, content, idx.record(key)?.content, OfflineAudioCache.contains(context, key))
            val url = if (chosen == key) result.url else
                result.url.replaceFirst(OfflineKeys.QUERY_KEY + "=" + key, OfflineKeys.QUERY_KEY + "=" + chosen)
            idx.put(chosen, url, content)
            if (url == result.url) result else result.copy(url = url)
        }
    }
    /** `null` = this file has no offline record (an online stream), which is NOT the same as
     *  a legacy record whose full/trial classification is still [OfflineContent.UNKNOWN]. */
    fun contentForUrl(context: Context, url: String): OfflineContent? = synchronized(this) {
        val key = OfflineKeys.keyOf(url) ?: return@synchronized null
        index(context).record(key)?.takeIf { it.url == url }?.content
    }
    fun recallRecord(context: Context, songId: Long, levels: List<String>, usable: (String) -> Boolean): Pair<String, OfflineUrlRecord>? =
        synchronized(this) { index(context).recallRecord(levels.map { OfflineKeys.key(songId, it) }, songId, usable) }

    fun clear(context: Context) {
        mutate(context) { it.clear() }
    }
}
