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
import com.google.gson.JsonParser

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
    private val keysBySong = HashMap<Long, LinkedHashSet<String>>()
    private var encoded: String? = null

    fun put(key: String, url: String, content: OfflineContent = OfflineContent()) {
        if (key.isEmpty() || url.isEmpty()) return
        val old = map[key]
        val updated = OfflineUrlRecord(url, if (!content.known && old?.url == url) old.content else content)
        // 取链和实际起播可能先后记录同一份 URL，末条的顺序也相同。
        if (updated == old && map.keys.lastOrNull() == key) return
        encoded = null
        map.remove(key)
        map[key] = updated
        OfflineKeys.songIdOf(key)?.let { songId ->
            val keys = keysBySong.getOrPut(songId) { LinkedHashSet() }
            keys.remove(key)
            keys.add(key)
        }
        while (map.size > maxEntries) {
            val oldest = map.keys.firstOrNull() ?: break
            map.remove(oldest)
            val songId = OfflineKeys.songIdOf(oldest) ?: continue
            val keys = keysBySong[songId] ?: continue
            keys.remove(oldest)
            if (keys.isEmpty()) keysBySong.remove(songId)
        }
    }

    fun get(key: String): String? = map[key]?.url
    fun record(key: String): OfflineUrlRecord? = map[key]

    /**
     * 删掉这首曲子在清单里的**所有档位**条目（v2.0.0 · T3 联动删除）。返回删掉的条数。
     *
     * 与 [OfflineLibrary.removeWithUrls] 成对使用：曲目表删了而这里留着，
     * 离线兜底就会拿一个指向空缓存的死条目去起播。
     */
    fun removeSong(songId: Long): Int {
        if (songId <= 0L) return 0
        val keys = keysBySong.remove(songId) ?: return 0
        keys.forEach { map.remove(it) }
        encoded = null
        return keys.size
    }

    /** 排序后的 key 快照（对账用，不暴露内部 map）。 */
    fun keys(): List<String> = map.keys.toList()

    fun clear() {
        if (map.isEmpty()) return
        map.clear()
        keysBySong.clear()
        encoded = null
    }

    /**
     * 离线兜底用：先按 [preferredKeys] 的顺序找，再退化成「这首歌的**任意**档位」。
     * 离线时能放出来比「档位严格一致」重要得多（缓存里是什么档就放什么档）。
     */
    fun recall(preferredKeys: List<String>, songId: Long): Pair<String, String>? =
        recallRecord(preferredKeys, songId) { true }?.let { it.first to it.second.url }

    fun recallRecord(preferredKeys: List<String>, songId: Long, usable: (String) -> Boolean): Pair<String, OfflineUrlRecord>? {
        val songKeys = keysBySong[songId] ?: return null
        if (songKeys.size == 1) {
            val key = songKeys.first()
            val record = map.getValue(key)
            return if (usable(key)) key to record else null
        }
        val levels = preferredKeys.mapNotNull(OfflineKeys::levelOf)
        val candidates = ArrayList<RecallCandidate>(songKeys.size)
        for (key in songKeys) {
            val preferred = levels.indexOf(OfflineKeys.levelOf(key))
            candidates.add(RecallCandidate(key, map.getValue(key), if (preferred < 0) levels.size else preferred))
        }
        candidates.sortWith(recallOrder)
        val hit = candidates.firstOrNull { usable(it.key) } ?: return null
        return hit.key to hit.record
    }

    private class RecallCandidate(val key: String, val record: OfflineUrlRecord, val levelRank: Int) {
        val contentRank: Int = when {
            !record.content.known -> 2
            record.content.kind == OfflineContent.FULL -> 0
            record.content.kind == OfflineContent.TRIAL -> 1
            else -> 2
        }
    }

    fun size(): Int = map.size

    fun toJson(): String = encoded ?: gson.toJson(map).also { encoded = it }

    companion object {
        const val MAX_ENTRIES = 300

        private val gson = Gson()
        private val recallOrder = Comparator<RecallCandidate> { first, second ->
            val byLevel = first.levelRank.compareTo(second.levelRank)
            if (byLevel == 0) first.contentRank.compareTo(second.contentRank) else byLevel
        }

        fun fromJson(json: String?): OfflineUrlIndex {
            val idx = OfflineUrlIndex()
            if (json.isNullOrEmpty()) return idx
            runCatching {
                val root = JsonParser.parseString(json).asJsonObject
                root.entrySet().forEach { (k, v) ->
                    runCatching {
                        if (v.isJsonPrimitive && v.asJsonPrimitive.isString) idx.put(k, v.asString)
                        else {
                            val record = gson.fromJson(v, OfflineUrlRecord::class.java)
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

    /** 从播放 URL 上取下 key 并记录（服务端收到 URL 时调用，URL 上已带 key）。 */
    fun rememberFromUrl(context: Context, url: String) {
        val key = OfflineKeys.keyOf(url) ?: return
        mutate(context) { it.put(key, url) }
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
