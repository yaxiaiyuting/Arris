/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.4.0 · E：跨源匹配缓存的读写与容量裁剪。
 */

package com.takahashirinta.ncrust.crosssource

import android.content.Context
import android.util.Log
import com.takahashirinta.ncrust.source.MusicSource

/**
 * 跨源匹配结果的持久缓存（v2.4.0 · E）。
 *
 * ## 为什么用 SharedPreferences 而不是内存缓存
 *
 * 匹配的代价不低：艺人匹配要拉两侧**各 100 张专辑**，专辑匹配要拉两侧曲目表。
 * 而这些是**目录数据**，一周之内不会变。放进 `ContentCache`（进程内、`onTrimMemory` 就清）
 * 会让「搜周杰伦 → 进艺人页 → 返回 → 再进」每次都重跑一遍匹配。
 *
 * ## 这个缓存**只存「匹配」**，绝不存「可用性」
 *
 * | 数据 | 变化速度 | 存哪 |
 * |---|---|---|
 * | 两条记录**是不是同一个**艺人/专辑/单曲 | 慢（目录结构） | **这里**（TTL 7 天） |
 * | 某首歌**现在能不能播** | 快（VIP 到期、版权下架、地区） | **不落盘**，只活在页面状态里 |
 *
 * 把可用性落盘一定会出现「上周探测过能播、今天显示可播放、点下去 404」，
 * 那正是本版要消灭的那类体验。
 *
 * ## 写入规则（三条，都由单测钉住）
 *
 * 1. **只写 `confidence >= MEDIUM`**（铁律 17 + 任务书 3.4「匹配失败不缓存，下次重试」）；
 * 2. **写入时裁剪**，不在读路径上裁剪（读时裁剪会让「超限」这个事实静默消失，
 *    与 `LocalPlaylistStore` 同一条约定）；
 * 3. **读失败一律当「没有」**，绝不抛异常、绝不删数据（下次重进页面自然会重算并覆盖）。
 */
object MatchCacheStore {

    const val PREFS = "ncrust_match_cache"

    /** 水位标记（可观测性）：与 `QqPlaylistStore` 一样额外写一个裸 int。 */
    internal const val KEY_SCHEMA = "schema_version"

    /** 容量上限。一条 ~300 字节，400 条 ≈ 120KB，对 SharedPreferences 是安全量级。 */
    internal const val MAX_ENTRIES = 400

    private const val TAG = "MatchCache"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 艺人 ----

    /** 读一条**新鲜且可合并**的艺人匹配；其余情况一律返回 null（调用方去重算）。 */
    fun artist(context: Context, key: ArtistKey): MergedArtist? {
        val read = MatchCacheCodec.decode(raw(context, MatchCacheKeys.artist(key)), now())
        return read.asOk()?.let { ok ->
            MergedArtist(
                primary = key,
                aliases = ok.aliases.mapNotNull { MatchCacheCodec.artistAlias(it) }
                    .filter { it.source != key.source },
                name = key.name,
                confidence = ok.confidence,
                reason = ok.reason,
                albumOverlap = ok.overlap,
            )
        }
    }

    fun putArtist(context: Context, merged: MergedArtist) {
        if (!merged.confidence.mergeable || merged.aliases.isEmpty()) return
        write(
            context,
            MatchCacheKeys.artist(merged.primary),
            MatchCacheCodec.encode(
                confidence = merged.confidence,
                reason = merged.reason,
                aliases = merged.aliases.map {
                    MatchCacheCodec.aliasOf(it.source, it.id, it.name)
                },
                overlap = merged.albumOverlap,
                savedAt = now(),
            ),
        )
    }

    // ------------------------------------------------------------------ 专辑 ----

    fun album(context: Context, key: AlbumKey): MergedAlbum? {
        val read = MatchCacheCodec.decode(raw(context, MatchCacheKeys.album(key)), now())
        return read.asOk()?.let { ok ->
            MergedAlbum(
                primary = key,
                aliases = ok.aliases.mapNotNull { MatchCacheCodec.albumAlias(it) }
                    .filter { it.source != key.source },
                name = key.name,
                confidence = ok.confidence,
                reason = ok.reason,
                trackOverlap = ok.overlap,
            )
        }
    }

    fun putAlbum(context: Context, merged: MergedAlbum) {
        if (!merged.confidence.mergeable || merged.aliases.isEmpty()) return
        write(
            context,
            MatchCacheKeys.album(merged.primary),
            MatchCacheCodec.encode(
                confidence = merged.confidence,
                reason = merged.reason,
                aliases = merged.aliases.map {
                    MatchCacheCodec.aliasOf(it.source, it.id, it.name)
                },
                overlap = merged.trackOverlap,
                savedAt = now(),
            ),
        )
    }

    // ------------------------------------------------------------------ 单曲 ----

    fun track(context: Context, key: com.takahashirinta.ncrust.source.TrackKey): MergedTrack? {
        val read = MatchCacheCodec.decode(raw(context, MatchCacheKeys.track(key)), now())
        return read.asOk()?.let { ok ->
            MergedTrack(
                primary = key,
                aliases = ok.aliases.mapNotNull { MatchCacheCodec.trackAlias(it) }
                    .filter { it.source != key.source },
                confidence = ok.confidence,
                reason = ok.reason,
            )
        }
    }

    fun putTrack(context: Context, merged: MergedTrack) {
        if (!merged.confidence.mergeable || merged.aliases.isEmpty()) return
        write(
            context,
            MatchCacheKeys.track(merged.primary),
            MatchCacheCodec.encode(
                confidence = merged.confidence,
                reason = merged.reason,
                aliases = merged.aliases.map { MatchCacheCodec.aliasOf(it) },
                overlap = 0,
                savedAt = now(),
            ),
        )
    }

    // ------------------------------------------------------------------ 通用 ----

    /** 清空整个匹配缓存（设置页的「清除缓存」与诊断用）。 */
    fun clear(context: Context) {
        runCatching { prefs(context).edit().clear().apply() }
    }

    /** 当前条目数（诊断 / 真机验证用）。 */
    fun entryCount(context: Context): Int = runCatching {
        prefs(context).all.keys.count { k ->
            MatchCacheKeys.allPrefixes.any { k.startsWith(it) }
        }
    }.getOrDefault(0)

    private fun now(): Long = System.currentTimeMillis()

    private fun raw(context: Context, key: String): String? =
        runCatching { prefs(context).getString(key, null) }.getOrNull()

    private fun MatchCacheCodec.Read.asOk(): MatchCacheCodec.Read.Ok? =
        (this as? MatchCacheCodec.Read.Ok)?.takeIf { it.fresh }

    private fun write(context: Context, key: String, value: String) {
        runCatching {
            val at = now()
            // ★ 裁剪计划里**必须**已经包含刚写入的这一条。
            //   `apply()` 是异步的，`saved(context)` 读到的还是写入前的快照；
            //   把新条目补进去，`existing.size` 才是「这次写完之后的真实条数」。
            //   少了这一步，超限时每写一条会删两条，而且 `planTrim` 里
            //   「不许删刚写的那条」的过滤会失去意义（它根本不在集合里）。
            val existing = HashMap(saved(context))
            existing[key] = at
            val editor = prefs(context).edit()
                .putString(key, value)
                .putInt(KEY_SCHEMA, MatchCacheCodec.SCHEMA_VERSION)
            planTrim(existing, key, MAX_ENTRIES).forEach { editor.remove(it) }
            editor.apply()
        }.onFailure { Log.w(TAG, "put failed key=$key", it) }
    }

    /** 现有条目的 (key → savedAt)。读不出来的条目按 0（最旧），下一轮就会被裁掉。 */
    private fun saved(context: Context): Map<String, Long> {
        val all = runCatching { prefs(context).all }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Long>(all.size)
        for ((k, v) in all) {
            if (MatchCacheKeys.allPrefixes.none { k.startsWith(it) }) continue
            val text = v as? String ?: continue
            val at = (MatchCacheCodec.decode(text, 0L) as? MatchCacheCodec.Read.Ok)?.savedAt ?: 0L
            out[k] = at
        }
        return out
    }

    /**
     * 裁剪计划（**纯函数**，单测直接钉）。
     *
     * @param existing 已有的 key → savedAt；**必须已经包含刚写入的那一条**
     *   （否则刚写进去的会被自己裁掉 —— 这是这种 LRU 最经典的 off-by-one）。
     * @return 需要删除的 key 列表（按最旧优先），可能为空。
     */
    internal fun planTrim(
        existing: Map<String, Long>,
        justWritten: String,
        max: Int = MAX_ENTRIES,
    ): List<String> {
        if (existing.size <= max) return emptyList()
        // 排序键是 (savedAt, key)：savedAt 相同时按 key 稳定排序，
        // 保证「同一份数据每次裁剪结果一样」，也保证刚写入的那条不会被随机选中。
        val ordered = existing.entries.sortedWith(compareBy({ it.value }, { it.key }))
        val overflow = existing.size - max
        return ordered.asSequence()
            .map { it.key }
            .filter { it != justWritten }
            .take(overflow)
            .toList()
    }

    /** 让 IDE / 调用方一眼看到「哪些音源进过这个缓存」，诊断用。 */
    internal fun sourcesPresent(context: Context): List<MusicSource> =
        saved(context).keys.mapNotNull { MatchCacheKeys.parse(it)?.second }
            .mapNotNull { key -> MusicSource.values().firstOrNull { it.key == key } }
            .distinct()
}
