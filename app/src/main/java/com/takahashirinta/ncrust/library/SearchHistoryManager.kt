package com.takahashirinta.ncrust.library

import android.content.Context
import com.takahashirinta.ncrust.network.AlbumSearchItem
import com.takahashirinta.ncrust.network.ArtistSearchItem
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.musicSource

/**
 * 搜索历史（三段：单曲 / 专辑 / 歌手）。
 *
 * ## v2.5.4 · B：音源不再丢
 *
 * 本表原先只存裸 `id`，读回来重建的 `SongItem` 没有 `source` / `sourceId` / `mediaId`：
 * 一首 QQ 曲目会被**字符串口径**（[com.takahashirinta.ncrust.source.SongItem.musicSource]）
 * 认成 ncm（角标错、单曲页路由错、收藏与点赞会拿合成 id 去调 ncm 的接口），
 * 而真正取链时 bit62 又把它认回 QQ、却因为没有 songmid 必然失败
 * （`QqApi.kt:134` 是硬要求）—— 用户看到的是「点了一条历史，歌被跳过了」。
 *
 * 现在：
 * - **写**：`source` 一律写规范值（ncm 也显式写 `"netease"`，见下），
 *   `sourceId` / `mediaId` 原样带上；
 * - **读**：老条目（`source == null`）按 bit62 推断，规则收敛在 [SearchHistoryMigration]；
 * - **判重 / 删除 / LazyColumn key** 三处统一走 [SearchHistoryMigration.dedupeKey]；
 * - **落盘形状**由 [SearchHistoryCodec] 的显式 `@SerializedName` 固定，不再由 R8 决定
 *   （真机证据与论证见该文件的 KDoc）。
 *
 * ## 为什么写 ncm 也显式写 `"netease"`
 *
 * 不写的话 `source` 就是 `null`，而 `null` 在本表里的语义是「**v2.5.4 之前写入的老条目**」
 * —— 新数据会长成老条目的形状，[SearchHistoryMigration.isIncomplete] 之类的判据就失效了。
 * 代价是重建 `SongItem` 时仍要把 ncm 还原成 `null`（与 `songRefOf` 同一条约定），
 * 这一层转换在 [SearchHistoryMigration.toSongItem] 里。
 */
object SearchHistoryManager {
    private const val PREFS_NAME = "search_history"
    private const val MAX_ITEMS = 10
    private val TTL_MS = 14L * 24 * 60 * 60 * 1000

    const val TYPE_SONG = 1
    const val TYPE_ALBUM = 10
    const val TYPE_ARTIST = 100

    /**
     * 按歌词搜索（v3.3.0）。
     *
     * ncm `cloudsearch/pc` 的 `type=1006`，实测（2026-10）：搜「让我掉下眼泪的」
     * 返回 `songCount=60`，第一条正是《成都》- 赵雷。返回结构里的 `songs` 与 `type=1`
     * **同形**（`SongItem` 全字段可用），所以展示与播放都复用单曲那条路。
     *
     * ⚠️ 这个常量**不进** [keyFor] 的分支表 —— 歌词搜索的结果就是单曲，
     * 历史记录与单曲共用一个桶（见 [keyFor] 的注释）。
     */
    const val TYPE_LYRIC = 1006

    data class HistoryItem(
        val id: Long,
        val title: String,
        val coverUrl: String?,
        val subtitle: String?,
        val timestamp: Long = System.currentTimeMillis(),
        /**
         * 音源 key（`"netease"` / `"qqmusic"`）。**可空 + 默认值是硬要求**：
         * `null` 的语义是「v2.5.4 之前写入的老条目，字段缺失」，不是「这首歌没有音源」。
         * 判定请用 [SearchHistoryMigration.effectiveSource]，不要直接读这个字符串。
         */
        val source: String? = null,
        /** QQ 的 `songmid`。ncm 恒为 null。**老条目不可推断 ⇒ 保持 null，绝不猜。** */
        val sourceId: String? = null,
        /** QQ 的 `media_mid`。ncm 恒为 null。 */
        val mediaId: String? = null,
    )

    fun addSong(context: Context, song: SongItem) = add(
        context, TYPE_SONG,
        HistoryItem(
            id = song.id,
            title = song.name,
            coverUrl = song.album?.picUrl,
            subtitle = song.artists?.firstOrNull()?.name,
            // 规范值：ncm 也显式写 "netease"（见类文档）。
            source = song.musicSource.key,
            sourceId = song.sourceId,
            mediaId = song.mediaId,
        )
    )

    fun addAlbum(context: Context, album: AlbumSearchItem) = add(
        context, TYPE_ALBUM,
        HistoryItem(
            id = album.id,
            title = album.name,
            coverUrl = album.picUrl,
            subtitle = album.artist?.name
        )
    )

    fun addArtist(context: Context, artist: ArtistSearchItem) = add(
        context, TYPE_ARTIST,
        HistoryItem(
            id = artist.id,
            title = artist.name,
            coverUrl = artist.picUrl,
            subtitle = null
        )
    )

    fun getSongs(context: Context) = getAll(context, TYPE_SONG)
    fun getAlbums(context: Context) = getAll(context, TYPE_ALBUM)
    fun getArtists(context: Context) = getAll(context, TYPE_ARTIST)

    /**
     * 删一条。
     *
     * 签名从 `(type, id)` 换成 `(type, item)` 是有意的：判据必须是
     * [SearchHistoryMigration.dedupeKey]，而它需要 `source`。
     * 只传 id 的话调用方得自己再拼一次键 —— 正是「同一规则两份写法」的形状
     * （v2.5.3 把队列身份收敛进 `QueueKeys` 修的就是这个）。
     */
    fun remove(context: Context, type: Int, item: HistoryItem) {
        val key = keyFor(type)
        val items = load(context, key)
        val target = SearchHistoryMigration.dedupeKey(item)
        items.removeAll { SearchHistoryMigration.dedupeKey(it) == target }
        save(context, key, items)
    }

    fun clearSection(context: Context, type: Int) {
        save(context, keyFor(type), mutableListOf())
    }

    private fun add(context: Context, type: Int, item: HistoryItem) {
        val key = keyFor(type)
        val now = System.currentTimeMillis()
        val items = load(context, key)
        items.removeAll { now - it.timestamp > TTL_MS }
        val target = SearchHistoryMigration.dedupeKey(item)
        items.removeAll { SearchHistoryMigration.dedupeKey(it) == target }
        items.add(0, item.copy(timestamp = now))
        if (items.size > MAX_ITEMS) items.subList(MAX_ITEMS, items.size).clear()
        save(context, key, items)
    }

    private fun getAll(context: Context, type: Int): List<HistoryItem> {
        val key = keyFor(type)
        val now = System.currentTimeMillis()
        val items = load(context, key)
        val filtered = items.filter { now - it.timestamp <= TTL_MS }
        if (filtered.size != items.size) save(context, key, filtered.toMutableList())
        return filtered
    }

    /**
     * 搜索类型 → 历史记录桶名。
     *
     * v3.3.0：`TYPE_LYRIC` **显式**归到 `"songs"`，不走 `else`。理由有两层：
     * ① 歌词搜索的结果就是单曲（同形 `SongItem`），混进 `"artists"` 桶会让艺人历史里
     *    冒出歌名；② 显式分支让「新加一个搜索类型」必须在这里面对一次选择，
     *    而不是被 `else` 静默兜到艺人桶里 —— 后者正是这个 `when` 原本的缺陷形状。
     */
    private fun keyFor(type: Int) = when (type) {
        TYPE_SONG, TYPE_LYRIC -> "songs"
        TYPE_ALBUM -> "albums"
        else -> "artists"
    }

    private fun load(context: Context, key: String): MutableList<HistoryItem> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null)
        return SearchHistoryCodec.decode(json).toMutableList()
    }

    private fun save(context: Context, key: String, items: List<HistoryItem>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(key, SearchHistoryCodec.encode(items)).apply()
    }
}
