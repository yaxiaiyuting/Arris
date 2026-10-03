/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 · B：本地歌单的落盘（应用私有目录）。
 */

package com.takahashirinta.ncrust.local

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地歌单的持久化（v2.3.0 · B）。
 *
 * ## 存储选型：`SharedPreferences` + Gson（沿用项目现状）
 *
 * 全仓**没有 Room、没有 DataStore**（`probe-local-playlist.md` §3 的审计结论），
 * 所有持久化都是 SharedPreferences + Gson。本地歌单没有理由破例：
 *
 * - 规模小（用户自建歌单通常个位数，每条几百字节）；
 * - 需要**同步读取**（库页第一帧就要渲染，不能先转圈）；
 * - 事务性要求低（单进程单写者，且所有写入都走本文件的 `edit()`）。
 *
 * ## 容量有界（铁律 8）
 *
 * `SharedPreferences` 是**整个文件读进内存**的，所以本文件的每一次写入都过
 * [LocalPlaylistSync.trimToLimit] / [MAX_PLAYLISTS] 两道闸 —— 上限的取舍在那边有说明。
 * **不在读路径上裁剪**：读的时候裁剪会让「超限」这个事实静默消失，
 * 而写路径裁剪至少会在下一次写入时把状态收敛回来。
 *
 * ## key 形状
 *
 * ```
 * playlists                    → LocalPlaylistCodec.encodePlaylists(...)
 * tracks:<source>:<ownerId>:<playlistId>  → LocalPlaylistCodec.encodeTracks(...)
 * ```
 *
 * `tracks:` 后面的三元组与 [com.takahashirinta.ncrust.source.PlaylistKey.tag] 同序同义
 * （音源 + 归属账号 + 歌单 id）。**ownerId 必须进 key** —— 理由与 `QqPlaylistStore` 完全一样：
 * 少了它，换账号会读到上一个账号的歌单。
 *
 * ## 为什么用 `commit()` 而不是 `apply()`（与项目其它地方不同）
 *
 * 本文件的写入发生在**用户点「移除」/「清空」之后**，而那两个动作的语义是
 * 「我的删除意图已经落盘」。`apply()` 是异步的：进程在它落盘前被杀，
 * 用户会看到「删掉的歌又回来了」——正是本版要杜绝的那件事。
 * 写入很小（单张歌单的 JSON），阻塞代价远小于丢一次用户意图。
 * 代价写在明处：**调用方不要在 UI 线程上循环调用**（本文件的调用点都是单次点击）。
 */
object LocalPlaylistStore {

    const val PREFS = "ncrust_local_playlists"

    /** 歌单列表的 key。 */
    internal const val KEY_PLAYLISTS = "playlists"

    /** 曲目表的 key 前缀。 */
    internal const val PREFIX_TRACKS = "tracks:"

    /** 曲目表的 key：`tracks:<source>:<ownerId>:<playlistId>`。 */
    internal fun tracksKey(key: com.takahashirinta.ncrust.source.PlaylistKey): String =
        PREFIX_TRACKS + key.tag

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // -------------------------------------------------------------- 歌单列表 ----

    fun readPlaylists(context: Context): List<LocalPlaylist> =
        LocalPlaylistCodec.decodePlaylists(prefs(context).getString(KEY_PLAYLISTS, null))

    /**
     * 覆盖写歌单列表。超过 [LocalPlaylistSync.MAX_PLAYLISTS] 时**保留最近更新的那些**
     * （按 [LocalPlaylist.updatedAt] 降序），旧的连同它们的曲目表一起删掉 ——
     * 只删列表项会把曲目表变成永远读不到的垃圾（`SharedPreferences` 不会自己回收）。
     */
    fun writePlaylists(context: Context, playlists: List<LocalPlaylist>) {
        val kept = if (playlists.size <= LocalPlaylistSync.MAX_PLAYLISTS) {
            playlists
        } else {
            playlists.sortedByDescending { it.updatedAt }.take(LocalPlaylistSync.MAX_PLAYLISTS)
        }
        val dropped = playlists.filterNot { p -> kept.any { it.key == p.key } }
        val editor = prefs(context).edit()
            .putString(KEY_PLAYLISTS, LocalPlaylistCodec.encodePlaylists(kept))
        dropped.forEach { editor.remove(tracksKey(it.key)) }
        editor.commit()
    }

    // ---------------------------------------------------------------- 曲目 ----

    fun readTracks(context: Context, key: com.takahashirinta.ncrust.source.PlaylistKey): List<LocalPlaylistTrack> =
        LocalPlaylistCodec.decodeTracks(prefs(context).getString(tracksKey(key), null))

    /** 覆盖写一张歌单的曲目表（写入前先过容量闸）。 */
    fun writeTracks(
        context: Context,
        key: com.takahashirinta.ncrust.source.PlaylistKey,
        tracks: List<LocalPlaylistTrack>,
    ) {
        val bounded = LocalPlaylistSync.trimToLimit(tracks)
        prefs(context).edit()
            .putString(tracksKey(key), LocalPlaylistCodec.encodeTracks(bounded))
            .commit()
    }

    /** 删掉一张歌单的曲目表（删除歌单 / 列表裁剪时用）。 */
    fun removeTracks(context: Context, key: com.takahashirinta.ncrust.source.PlaylistKey) {
        prefs(context).edit().remove(tracksKey(key)).commit()
    }

    /**
     * 一次性写入一个歌单的**元数据 + 曲目**，并顺带更新它的 [LocalPlaylist.updatedAt]。
     *
     * 抽成一个函数是为了让「元数据与曲目表必须一起更新」这件事只有一个落点 ——
     * 分两次调用迟早会出现「歌单在那儿、歌是空的」的中间态。
     */
    fun writePlaylistWithTracks(
        context: Context,
        playlist: LocalPlaylist,
        tracks: List<LocalPlaylistTrack>,
        now: Long,
    ): LocalPlaylist {
        val updated = playlist.copy(updatedAt = now)
        val list = readPlaylists(context)
        val idx = list.indexOfFirst { it.key == updated.key }
        val next = if (idx < 0) list + updated else list.toMutableList().also { it[idx] = updated }
        writePlaylists(context, next)
        writeTracks(context, updated.key, tracks)
        return updated
    }

    /**
     * 删除一个本地歌单（元数据 + 曲目表）。
     *
     * 与「清空歌单」不同：删除会连 tombstone 一起丢掉（[LocalPlaylistSync.clearAll] 之后再删），
     * 也就是**放弃这个歌单的同步来源关联**。
     */
    fun deletePlaylist(context: Context, key: com.takahashirinta.ncrust.source.PlaylistKey) {
        val list = readPlaylists(context)
        writePlaylists(context, list.filterNot { it.key == key })
        removeTracks(context, key)
    }

    /**
     * 新建一个本地歌单。
     *
     * @param key 身份。绑远程来源时用远程的 [com.takahashirinta.ncrust.source.PlaylistKey]；
     *   纯手动的用 [LocalPlaylistSync.localOnlyKey]。
     * @return 新建的歌单；同名同 key 已存在时返回**已存在的那一个**（幂等，不覆盖用户的歌）。
     */
    fun createPlaylist(
        context: Context,
        key: com.takahashirinta.ncrust.source.PlaylistKey,
        name: String,
        now: Long,
    ): LocalPlaylist {
        val list = readPlaylists(context)
        list.firstOrNull { it.key == key }?.let { return it }
        val created = LocalPlaylist(key = key, name = name, createdAt = now, updatedAt = now)
        writePlaylists(context, list + created)
        return created
    }
}
