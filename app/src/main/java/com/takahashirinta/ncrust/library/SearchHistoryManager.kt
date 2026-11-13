package com.takahashirinta.ncrust.library

import android.content.Context
import com.takahashirinta.ncrust.network.AlbumSearchItem
import com.takahashirinta.ncrust.network.ArtistSearchItem
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.musicSource

/** 保存最近使用的单曲、专辑和歌手。单曲身份与旧数据恢复规则见 [SearchHistoryMigration]。 */
object SearchHistoryManager {
    private const val PREFS_NAME = "search_history"
    private const val MAX_ITEMS = 10
    private const val TTL_MS = 14L * 24 * 60 * 60 * 1000
    private val lock = Any()

    const val TYPE_SONG = 1
    const val TYPE_ALBUM = 10
    const val TYPE_ARTIST = 100

    /** 歌词搜索的结果与单曲搜索共用历史。 */
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
            // 显式写入网易云标识，兼容用 null 表示旧格式的条目。
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

    /** 删除指定身份并返回该分区当前可显示的记录。 */
    internal fun remove(context: Context, type: Int, item: HistoryItem): List<HistoryItem> = synchronized(lock) {
        val key = keyFor(type)
        val items = load(context, key)
        val target = SearchHistoryMigration.dedupeKey(item)
        val now = System.currentTimeMillis()
        if (items.removeAll { now - it.timestamp > TTL_MS || SearchHistoryMigration.dedupeKey(it) == target }) {
            save(context, key, items)
        }
        items
    }

    fun clearSection(context: Context, type: Int) = synchronized(lock) {
        save(context, keyFor(type), emptyList())
    }

    private fun add(context: Context, type: Int, item: HistoryItem) = synchronized(lock) {
        val key = keyFor(type)
        val now = System.currentTimeMillis()
        val items = load(context, key)
        val target = SearchHistoryMigration.dedupeKey(item)
        items.removeAll { now - it.timestamp > TTL_MS || SearchHistoryMigration.dedupeKey(it) == target }
        items.add(0, item.copy(timestamp = now))
        if (items.size > MAX_ITEMS) items.subList(MAX_ITEMS, items.size).clear()
        save(context, key, items)
    }

    private fun getAll(context: Context, type: Int): List<HistoryItem> = synchronized(lock) {
        val key = keyFor(type)
        val now = System.currentTimeMillis()
        val items = load(context, key)
        if (items.removeAll { now - it.timestamp > TTL_MS }) save(context, key, items)
        items
    }

    private fun keyFor(type: Int) = when (type) {
        TYPE_SONG, TYPE_LYRIC -> "songs"
        TYPE_ALBUM -> "albums"
        else -> "artists"
    }

    private fun load(context: Context, key: String): MutableList<HistoryItem> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null)
        return SearchHistoryCodec.decode(json)
    }

    private fun save(context: Context, key: String, items: List<HistoryItem>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(key, SearchHistoryCodec.encode(items)).apply()
    }
}
