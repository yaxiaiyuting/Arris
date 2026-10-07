/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.cache

import android.content.Context
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.source.SourceIds

/**
 * 离线曲目索引的一条记录（v2.0.0 · T3 · 离线缓存 Phase 2）。**纯数据**，JVM 可单测。
 *
 * ## 为什么需要它
 *
 * Phase 1（v1.6.0 · D1）只交付了「缓存字节」与「URL 清单」两样东西：
 * [OfflineAudioCache] 只回答 contains / sizeBytes / clear，[OfflineUrlStore] 的清单是
 * `key → url` 的私有表，两者都**没有歌曲元数据**（标题 / 歌手 / 封面 / 档位）。
 * 结果就是「缓存里到底有哪些歌、各占多少」在 UI 上无从回答 —— Phase 2 的清单来源就是这张表。
 *
 * ## 字段与迁移（沿用 v1.9.3 固化的「加字段 = 加迁移逻辑 = 加单测」契约）
 *
 * 这张表同样是**跨版本存活**的，所以：
 *
 * 1. 除主键 [songId] 外，**每个字段都可空 + 有默认值** —— Gson 走 Unsafe 反序列化、
 *    不调用构造函数，老条目缺 key 时字段就是 null / 0，读取处一律按「字段缺失」处理；
 * 2. 判「老条目」只看**字段缺失**（null），不看空串 —— 空串是「服务端/上游确实没有这份数据」；
 * 3. 缺字段的条目**不丢弃**（它不是缓存数据，只是 UI 元数据）：能显示多少显示多少，
 *    缓存是否可播的权威判据永远是 [OfflineAudioCache.contains]，不是这张表；
 * 4. 主键非法（[songId] <= 0）的条目在装载时丢弃 —— 那是坏 JSON 或人为改坏了 prefs。
 */
internal data class OfflineTrack(
    /** 主键。网易歌曲 id。 */
    val songId: Long = 0L,
    /** 歌名。老条目 / 元数据缺失时为 null（UI 回落成「未知曲目」）。 */
    val name: String? = null,
    /** 歌手。 */
    val artist: String? = null,
    /** 封面 URL（列表里只用来画一个色块，取不到就回落纯色块）。 */
    val albumPicUrl: String? = null,
    /** 时长（ms）。0 / null = 不知道（[OfflineLibrary.record] 在时长首次可知时补写一次）。 */
    val durationMs: Long? = null,
    /** 实际授予的档位（exhigh / lossless / …）。 */
    val level: String? = null,
    /** 缓存的 key（`song:<id>:<level>`），与 [OfflineKeys] 同源；删曲目时按它删 span。 */
    val cacheKey: String? = null,
    /**
     * 该曲缓存占用的近似字节数。**当前实现不写它**（默认 null）：
     * UI 每次打开都从 SimpleCache 现场量（[OfflineAudioCache.bytesForSong]），
     * 存一份快照只会在用户删片段 / LRU 淘汰后变成一个说谎的数字。
     */
    val approxBytes: Long? = null,
    /** 首次可离线播放的时刻（epoch ms）。重播**不刷新**它，见 [OfflineLibraryIndex.upsert]。 */
    val completedAt: Long? = null,
) {
    /**
     * v3.3.0 · 用户建议：**离线列表里的行要能直接点播**。
     *
     * 把这条索引记录还原成一个可以进队列的 [SongItem]。
     *
     * ## 音源从 id 反推，**不加新的落盘字段**
     *
     * 直觉做法是给 [OfflineTrack] 加一个 `source: String?` 字段。**不需要** ——
     * 本仓库的数字 id 自带音源标志位（QQ 用位 62、B 站用位 61，见 [SourceIds]），
     * 而 [SourceIds.sourceOfId] 就是为「id 单独存在时也能反推音源」而存在的
     * （v3.1.0 的 KDoc 明确写了「id 自带的标志位不会丢」）。
     *
     * 少加一个字段 = 少一次迁移逻辑 = 少一处会在老条目上变成 null 的地方 ——
     * 这正是 v1.9.3 固化的那条契约（「能不加字段就不加」）。
     *
     * ## 为什么 `level` 不参与
     *
     * 档位不进 `SongItem`：它由**取链**决定，而离线兜底
     * （`PlayerViewModel.recallOfflineCache`）本来就会退化成「这首歌的任意档位」。
     * 把某一档写进 `SongItem` 反而会在换档之后变成谎报。
     *
     * @return 可直接交给播放链路的 [SongItem]；[songId] 非法时返回 null（不猜）。
     */
    fun toSongItem(): SongItem? {
        if (songId <= 0L) return null
        val source = SourceIds.sourceOfId(songId)
        return SongItem(
            id = songId,
            // 歌名缺失时给一个可读占位：`SongItem.name` 是非空类型，
            // 而离线索引允许它为 null（老条目 / 元数据没补上）。
            name = name?.takeIf { it.isNotBlank() } ?: "",
            artists = artist?.takeIf { it.isNotBlank() }
                ?.let { listOf(ArtistItem(id = null, name = it)) },
            album = albumPicUrl?.takeIf { it.isNotBlank() }
                ?.let { AlbumItem(id = null, name = null, picUrl = it) },
            duration = durationMs?.takeIf { it > 0L },
            // 显式写音源 key（而不是留 null）：留 null 的语义是「v2.1.0 之前的老数据 ⇒ ncm」，
            // 对 QQ / B 站曲目是错的，会让取链走错源。
            source = source.key,
        )
    }
}

/**
 * 离线曲目索引（纯逻辑）。有界 + LRU：重播会把条目移到队尾，超过上限时淘汰最久未 upsert 的一条。
 *
 * 两条顺序口径**故意不同**，不要「统一」掉：
 * - **淘汰顺序** = 最近一次 upsert（写入序），LRU 的语义就是「最久没被碰过的先走」；
 * - **[list] 的展示顺序** = [OfflineTrack.completedAt] 倒序（首次缓存时间），稳定的，
 *   所以同毫秒的条目保持写入先后。
 */
internal class OfflineLibraryIndex(private val maxEntries: Int = MAX_ENTRIES) {

    // LinkedHashMap 的迭代顺序 = 插入顺序，put 前先 remove 即得到 LRU。
    private val map = LinkedHashMap<Long, OfflineTrack>()

    /** 写入 / 更新一条记录。返回 false 表示主键非法、被忽略。 */
    fun upsert(track: OfflineTrack): Boolean {
        if (track.songId <= 0L) return false
        val prev = map.remove(track.songId)
        // completedAt 是「首次可离线播放的时刻」：重播只更新元数据，不刷新时间戳。
        // 否则「按时间倒序」会退化成「按最近播放排序」，与字段语义不符。
        val merged = if (prev != null) {
            track.copy(completedAt = prev.completedAt ?: track.completedAt)
        } else {
            track
        }
        map[track.songId] = merged
        while (map.size > maxEntries) {
            val it = map.keys.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            } else {
                break
            }
        }
        return true
    }

    /** 删一条。返回被删掉的记录（本来就没有则 null）。 */
    fun remove(songId: Long): OfflineTrack? = map.remove(songId)

    /**
     * 删曲目 **+ 同步删 URL 清单**（纯逻辑，供 [OfflineLibrary.remove] 与单测共用）。
     *
     * 两条清单必须一起删：只删曲目会留下指向「已删缓存」的 URL 死条目，
     * 而离线兜底 [OfflineUrlStore.recall] 只认 URL 清单 —— 留着它就会拿一个
     * 空缓存去起播（v1.3.0 明令禁止的坏链接形态）。
     * 反过来也删：URL 清单里有、曲目表里没有的孤儿条目同样要清掉，
     * 所以即使 [songId] 不在曲目表里，这里也照样清 URL。
     */
    fun removeWithUrls(songId: Long, urls: OfflineUrlIndex): OfflineTrack? {
        val removed = map.remove(songId)
        urls.removeSong(songId)
        return removed
    }

    /** 删掉所有 [keepSongIds] 之外的条目（对账用：比如 URL 清单被 LRU 淘汰后留下的死条目）。 */
    fun retainSongIds(keepSongIds: Set<Long>): Int {
        var dropped = 0
        val it = map.keys.iterator()
        while (it.hasNext()) {
            if (it.next() !in keepSongIds) {
                it.remove()
                dropped++
            }
        }
        return dropped
    }

    fun get(songId: Long): OfflineTrack? = map[songId]

    /** 按 [OfflineTrack.completedAt] 倒序（同时刻保持写入顺序）。 */
    fun list(): List<OfflineTrack> = map.values.sortedByDescending { it.completedAt ?: 0L }

    fun size(): Int = map.size

    /** 索引里所有歌曲的 id。 */
    fun songIds(): Set<Long> = map.keys.toSet()

    /** [OfflineTrack.approxBytes] 之和（拿不到就是 0）。 */
    fun totalBytes(): Long = map.values.sumOf { it.approxBytes ?: 0L }

    fun clear() = map.clear()

    /**
     * v2.5.5 · A：写路径只经 [OfflineTrackCodec] 的 DTO，每个字段显式
     * `@SerializedName("<稳定名字>")` —— 落盘 key 不再由 R8 的字段名决定。
     */
    fun toJson(): String = OfflineTrackCodec.encode(map.values.toList())

    companion object {
        /** 与 [OfflineUrlIndex.MAX_ENTRIES] 同值：两张表覆盖同一批歌，条数上限就该一致。 */
        const val MAX_ENTRIES = 300

        fun fromJson(json: String?): OfflineLibraryIndex {
            val idx = OfflineLibraryIndex()
            OfflineTrackCodec.decode(json).forEach { idx.upsert(it) }
            return idx
        }
    }
}

/**
 * 离线曲目索引的落盘（v2.0.0 · T3）。写入方式与 [OfflineUrlStore] 逐条对齐：
 * SharedPreferences + Gson JSON + 进程内单例 + 坏 JSON 容错。
 *
 * 它**只记「这台设备真的播过、因而可能有本地片段」的歌**（写入点在
 * `PlaybackService.updatePlaybackState`：起播后 500ms 内的第一次心跳，覆盖手动点播 /
 * gapless 自动接续 / 车机点播三条路径）。未被播放过的歌永远不会出现在这里。
 *
 * prefs 文件是 **ncrust_offline**（与 URL 清单同一个文件、不同 key），所以
 * 「清空全部缓存」只需清这两个 key。[clear] 与 [OfflineAudioCache.clear] 必须成对调用 ——
 * 统计口径与可清理范围一致是本 fork 的既有不变量（见 UserScreen 的注释）。
 */
object OfflineLibrary {
    private const val PREFS = "ncrust_offline"
    private const val KEY_TRACKS = "tracks"

    @Volatile
    private var index: OfflineLibraryIndex? = null

    private fun index(context: Context): OfflineLibraryIndex {
        index?.let { return it }
        synchronized(this) {
            index?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val loaded = OfflineLibraryIndex.fromJson(prefs.getString(KEY_TRACKS, null))
            index = loaded
            return loaded
        }
    }

    private fun persist(context: Context, idx: OfflineLibraryIndex) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_TRACKS, idx.toJson()).apply()
        }
    }

    /**
     * 记下一首「已经播起来」的歌。已存在则更新元数据、保留首次的 [OfflineTrack.completedAt]。
     *
     * 幂等：同一首歌连播十遍，索引里仍只有一条、时间戳仍是第一次那个。
     */
    fun record(
        context: Context,
        songId: Long,
        name: String?,
        artist: String?,
        albumPicUrl: String?,
        durationMs: Long = 0L,
        cacheKey: String?,
        level: String?,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (songId <= 0L) return false
        return synchronized(this) {
            val idx = index(context)
            val ok = idx.upsert(
                OfflineTrack(
                    songId = songId,
                    name = name,
                    artist = artist,
                    albumPicUrl = albumPicUrl,
                    durationMs = durationMs.takeIf { it > 0L },
                    level = level,
                    cacheKey = cacheKey,
                    completedAt = now,
                )
            )
            if (ok) persist(context, idx)
            ok
        }
    }

    /** 列表（按首次缓存时间倒序）。返回类型是 internal 的 [OfflineTrack]，所以本函数也是 internal。 */
    internal fun list(context: Context): List<OfflineTrack> = synchronized(this) { index(context).list() }

    internal fun get(context: Context, songId: Long): OfflineTrack? =
        synchronized(this) { index(context).get(songId) }

    fun size(context: Context): Int = synchronized(this) { index(context).size() }

    fun totalBytes(context: Context): Long = synchronized(this) { index(context).totalBytes() }

    /**
     * 删一首歌：曲目表与 URL 清单**一起删**（见 [OfflineLibraryIndex.removeWithUrls]）。
     * 音频 span 不在这里删 —— 那是 [OfflineAudioCache.removeSong] 的职责，
     * 这样「只删清单」与「连字节一起删」两种调用方都能复用同一套清单逻辑。
     */
    fun remove(context: Context, songId: Long): Boolean {
        if (songId <= 0L) return false
        val removed = synchronized(this) {
            val idx = index(context)
            // 联动删 URL：与单测共用同一个纯函数 [OfflineLibraryIndex.removeWithUrls]，
            // 「删曲目必删死条目」这条不变量才有测试盯着（OfflineUrlStore.mutate 负责落盘）。
            val track = OfflineUrlStore.mutate(context) { urls -> idx.removeWithUrls(songId, urls) }
            persist(context, idx)
            track
        }
        return removed != null
    }

    /**
     * 对账：丢掉 [keepSongIds] 之外的条目（v2.0.0 · T3，由
     * [OfflineAudioCache.reconcileLibrary] 调用）。返回丢掉的条数；没变化就不写盘。
     */
    internal fun retain(context: Context, keepSongIds: Set<Long>): Int = synchronized(this) {
        val idx = index(context)
        val dropped = idx.retainSongIds(keepSongIds)
        if (dropped > 0) persist(context, idx)
        dropped
    }

    fun clear(context: Context) {
        synchronized(this) {
            index = OfflineLibraryIndex()
            persist(context, index(context))
        }
    }
}
