/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
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
    @Synchronized
    fun writePlaylists(context: Context, playlists: List<LocalPlaylist>): Boolean {
        val kept = if (playlists.size <= LocalPlaylistSync.MAX_PLAYLISTS) {
            playlists
        } else {
            playlists.sortedByDescending { it.updatedAt }.take(LocalPlaylistSync.MAX_PLAYLISTS)
        }
        val dropped = playlists.filterNot { p -> kept.any { it.key == p.key } }
        val changes = linkedMapOf<String, String?>(
            KEY_PLAYLISTS to LocalPlaylistCodec.encodePlaylists(kept),
        )
        dropped.forEach { changes[tracksKey(it.key)] = null }
        // 返回值是「**真的落盘了**」的唯一凭据。旧实现丢掉 commit 结果，于是新建歌单在
        // 磁盘写失败时照样被当成创建成功（H09）：本进程能读到、冷启动就没了。
        return commitValues(prefs(context), changes)
    }

    // ---------------------------------------------------------------- 曲目 ----

    fun readTracks(context: Context, key: com.takahashirinta.ncrust.source.PlaylistKey): List<LocalPlaylistTrack> =
        LocalPlaylistCodec.decodeTracks(prefs(context).getString(tracksKey(key), null))

    /** 覆盖写一张歌单的曲目表（写入前先过容量闸）。 */
    @Synchronized
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
    @Synchronized
    fun removeTracks(context: Context, key: com.takahashirinta.ncrust.source.PlaylistKey) {
        prefs(context).edit().remove(tracksKey(key)).commit()
    }

    /**
     * 一次性写入一个歌单的**元数据 + 曲目**，并顺带更新它的 [LocalPlaylist.updatedAt]。
     *
     * 抽成一个函数是为了让「元数据与曲目表必须一起更新」这件事只有一个落点 ——
     * 分两次调用迟早会出现「歌单在那儿、歌是空的」的中间态。
     */
    @Synchronized
    fun writePlaylistWithTracks(
        context: Context,
        playlist: LocalPlaylist,
        tracks: List<LocalPlaylistTrack>,
        now: Long,
    ): LocalPlaylist? = writePlaylistWithTracks(prefs(context), playlist, tracks, now)

    /**
     * 本地歌单的**修订号**：每一次成功落盘（同步、加曲、删曲、清空、删除、新建）都 +1。
     *
     * 用途：`sync` 在发请求前记下它，提交时要求它没变 —— 否则「同步期间用户删了歌单/移除了
     * 曲目」会被在途同步用旧快照覆盖回去（删除意图丢失、曲目复活）。
     */
    @Volatile
    private var revision = 0L
    fun currentRevision(): Long = revision

    @Synchronized
    internal fun writePlaylistWithTracks(
        storage: SharedPreferences,
        playlist: LocalPlaylist,
        tracks: List<LocalPlaylistTrack>,
        now: Long,
    ): LocalPlaylist? = writePlaylistWithTracksLocked(storage, playlist, tracks, now, null, false)

    /**
     * 带**期望修订号**的写入：`expectedRevision != null` 时要求
     * ① 修订号未变（同步期间没有别的编辑）② 该 key 仍然存在（没被删除）。
     * 任一不满足返回 null，调用方据此把这次同步判为失败而不是"成功"。
     */
    @Synchronized
    fun writePlaylistWithTracksIfUnchanged(
        context: Context,
        playlist: LocalPlaylist,
        tracks: List<LocalPlaylistTrack>,
        now: Long,
        expectedRevision: Long,
    ): LocalPlaylist? =
        writePlaylistWithTracksLocked(prefs(context), playlist, tracks, now, expectedRevision, true)

    @Synchronized
    internal fun writePlaylistWithTracksIfUnchanged(
        storage: SharedPreferences,
        playlist: LocalPlaylist,
        tracks: List<LocalPlaylistTrack>,
        now: Long,
        expectedRevision: Long,
    ): LocalPlaylist? = writePlaylistWithTracksLocked(storage, playlist, tracks, now, expectedRevision, true)

    private fun writePlaylistWithTracksLocked(
        storage: SharedPreferences,
        playlist: LocalPlaylist,
        tracks: List<LocalPlaylistTrack>,
        now: Long,
        expectedRevision: Long?,
        requireExisting: Boolean,
    ): LocalPlaylist? {
        if (expectedRevision != null && expectedRevision != revision) return null
        val updated = playlist.copy(updatedAt = now)
        val list = LocalPlaylistCodec.decodePlaylists(storage.getString(KEY_PLAYLISTS, null))
        val idx = list.indexOfFirst { it.key == updated.key }
        // 同步路径禁止"借更新接口隐式重建"：期间被删掉的歌单不允许复活。
        if (requireExisting && idx < 0) return null
        val next = if (idx < 0) list + updated else list.toMutableList().also { it[idx] = updated }
        val kept = if (next.size <= LocalPlaylistSync.MAX_PLAYLISTS) next
            else next.sortedByDescending { it.updatedAt }.take(LocalPlaylistSync.MAX_PLAYLISTS)
        if (kept.none { it.key == updated.key }) return null
        val dropped = next.filterNot { item -> kept.any { it.key == item.key } }
        val changes = linkedMapOf<String, String?>(KEY_PLAYLISTS to LocalPlaylistCodec.encodePlaylists(kept))
        dropped.forEach { changes[tracksKey(it.key)] = null }
        if (kept.any { it.key == updated.key }) {
            changes[tracksKey(updated.key)] = LocalPlaylistCodec.encodeTracks(LocalPlaylistSync.trimToLimit(tracks))
        }
        if (!commitValues(storage, changes)) return null
        revision++
        return updated
    }

    /**
     * 一次提交多个 key；**失败时把内存也回滚**。
     *
     * `SharedPreferences` 在 `commit()` 返回 false 时，进程内的内存表**已经被改过**
     * （只有磁盘没落）。不回滚的话，同进程的读取会看到一个磁盘上并不存在的状态：
     * 「新建的歌单创建成功」、重启后消失 —— 正是 H09 那种「看起来成功、其实没落盘」。
     *
     * @return true = 磁盘已提交。false = 未提交，且内存已还原成提交前的样子。
     */
    private fun commitValues(storage: SharedPreferences, values: Map<String, String?>): Boolean {
        val before = values.keys.associateWith { storage.getString(it, null) }
        val editor = storage.edit()
        values.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
        if (editor.commit()) return true
        // 回滚本身也可能失败（同一次磁盘故障），但那已经不改变结论：调用方拿到的是 false。
        val rollback = storage.edit()
        before.forEach { (key, value) -> if (value == null) rollback.remove(key) else rollback.putString(key, value) }
        rollback.commit()
        return false
    }

    /**
     * 删除一个本地歌单（元数据 + 曲目表）。
     *
     * 与「清空歌单」不同：删除会连 tombstone 一起丢掉（[LocalPlaylistSync.clearAll] 之后再删），
     * 也就是**放弃这个歌单的同步来源关联**。
     *
     * @return true = 歌单列表的提交成功。曲目表的删除是独立的一条 key，它的 commit 结果
     *   不单独上报（删歌单失败时列表仍在，用户可以重试；`tracks:` 残留是惰性的，读不到）。
     */
    @Synchronized
    fun deletePlaylist(context: Context, key: com.takahashirinta.ncrust.source.PlaylistKey): Boolean {
        revision++
        val list = readPlaylists(context)
        return commitValues(prefs(context), linkedMapOf(
            KEY_PLAYLISTS to LocalPlaylistCodec.encodePlaylists(list.filterNot { it.key == key }),
            tracksKey(key) to null,
        ))
    }

    /**
     * 新建一个本地歌单。
     *
     * @param key 身份。绑远程来源时用远程的 [com.takahashirinta.ncrust.source.PlaylistKey]；
     *   纯手动的用 [LocalPlaylistSync.localOnlyKey]。
     * @return 新建的歌单；同名同 key 已存在时返回**已存在的那一个**（幂等，不覆盖用户的歌）。
     *   **null = 落盘失败**（内存已回滚）—— 调用方不得据此提示「已创建」，应保留重试入口。
     */
    @Synchronized
    fun createPlaylist(
        context: Context,
        key: com.takahashirinta.ncrust.source.PlaylistKey,
        name: String,
        now: Long,
    ): LocalPlaylist? {
        val list = readPlaylists(context)
        list.firstOrNull { it.key == key }?.let { return it }
        val created = LocalPlaylist(key = key, name = name, createdAt = now, updatedAt = now)
        // H09：只有 commit 真的成功，才把这条当成"已创建"。否则返回 null，让上层提示失败。
        return created.takeIf { writePlaylists(context, list + created) }
    }
}
