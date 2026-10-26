/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 · B：本地歌单的**同步编排**与编辑入口。只读远程、只写本地。
 */

package com.takahashirinta.ncrust.local

import android.content.Context
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.playlist.PlaylistResult
import com.takahashirinta.ncrust.qq.QqPlaylistRepository
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.source.TrackKey

/**
 * 一次同步的结果（v2.3.0 · B）。
 *
 * 做成 sealed 而不是「一个 Result + 一个 error 字符串」，是为了让 UI 的每一句话都有出处：
 * 成功时给「新增 N，跳过 M」，没有远程来源时给「这个歌单没有可同步的远程来源」，
 * 失败时给「同步失败，请检查网络」——**三者不能互相冒充**。
 */
sealed interface LocalSyncOutcome {

    /** 同步成功。[added] = 新追加的条数，[skipped] = 因 tombstone 跳过的条数。 */
    data class Success(val added: Int, val skipped: Int, val total: Int) : LocalSyncOutcome

    /** 这个本地歌单没有绑定远程来源（纯手动歌单）——**不发任何请求**。 */
    data object NoRemoteSource : LocalSyncOutcome

    /**
     * 部分成功：拿到了数据但不完整（缓存回退 / 截断 / 降级）。
     *
     * 与 [Success] 的区别是**不更新 lastSyncedAt**：TTL 之后仍会再补取一次。
     * 界面应显示"已同步 N 首（数据不完整，稍后会自动补齐）"而不是完整成功。
     */
    data class Partial(val added: Int, val skipped: Int, val total: Int) : LocalSyncOutcome

    /** 远程拉取失败（网络 / 登录态 / 歌单不存在）。**本地数据一个字节都不动。** */
    data object Failed : LocalSyncOutcome
}

/**
 * 本地歌单的仓库（v2.3.0 · B）。
 *
 * ## 一条铁律：**本文件只读远程、只写本地**
 *
 * 任务书把「远程歌单写操作（创建、改名、加歌、删歌）」明确排除在范围外，
 * 而铁律 3 允许本地歌单编辑。这条边界在本文件里是**结构性**的：
 * 依赖里没有任何 `PlaylistEditApi` / QQ 写接口，只有：
 *
 * | 用途 | 依赖 |
 * |---|---|
 * | 拉 ncm 歌单曲目 | `PlaylistApi.getPlaylistDetail`（GET 语义，只读） |
 * | 拉 QQ 歌单曲目 | `QqPlaylistRepository.loadDetail`（v2.2.0 的只读镜像） |
 * | 落盘 | `LocalPlaylistStore`（应用私有 prefs） |
 *
 * ## 有界性（铁律 8）
 *
 * 同步**没有重试循环**：一次调用最多发一次远程请求，失败就返回 [LocalSyncOutcome.Failed]。
 * 自动同步还受 [LocalPlaylistSync.shouldAutoSync] 的 TTL 约束 —— 进入歌单不会每次都打网络。
 */
object LocalPlaylistRepository {

    /**
     * 拉取远程歌单的曲目。**任何异常都返回 null，绝不抛给调用方**
     * （与 `MusicSourceProvider` 的契约一致）。
     *
     * @param key 远程歌单身份。
     * @param dirId qm 的目录号（载荷）。0 = 让服务端按 disstid 解析。
     */
    /**
     * 远程读取结果 + **完整性**。
     *
     * 旧实现只把 `data` 解包成 songs，把 `fresh / fromCache / degradation` 全丢了：
     * 截断的缓存（TRUNCATED 1/100 条）、离线回退、甚至 NEED_LOGIN 回退都被当成
     * "完整成功了"，于是 `sync` 更新 lastSyncedAt，10 分钟内不再补取。
     */
    private data class RemoteFetch(val songs: List<SongItem>?, val complete: Boolean)

    private suspend fun loadRemoteOutcome(
        key: PlaylistKey,
        dirId: Long,
        forceRefresh: Boolean,
    ): RemoteFetch {
        return when (key.source) {
            MusicSource.QQMUSIC -> {
                val result = runCatching {
                    QqPlaylistRepository.loadDetail(key, dirId, forceRefresh)
                }.getOrNull() ?: return RemoteFetch(null, false)
                when (result) {
                    is PlaylistResult.Data -> RemoteFetch(
                        songs = result.outcome.data.songs,
                        // 只有"联网取到的新鲜完整数据"才算完整；缓存/降级一律不算。
                        complete = result.outcome.fresh && result.outcome.degradation == null,
                    )
                    is PlaylistResult.Failed -> RemoteFetch(null, false)
                }
            }
            MusicSource.NETEASE -> {
                val pid = key.id.toLongOrNull() ?: return RemoteFetch(null, false)
                RemoteFetch(runCatching { PlaylistApi.getPlaylistDetail(pid) }.getOrNull(), true)
            }
            MusicSource.BILIBILI -> RemoteFetch(null, false)
        }
    }

    suspend fun loadRemoteSongs(
        key: PlaylistKey,
        dirId: Long,
        forceRefresh: Boolean,
    ): List<SongItem>? = loadRemoteOutcome(key, dirId, forceRefresh).songs

    private suspend fun loadRemoteSongsForSync(
        key: PlaylistKey,
        dirId: Long,
        forceRefresh: Boolean,
    ): List<SongItem>? {
        // 兼容旧入口：sync 只走这个包装，保持原有的 `loadRemoteSongs` 契约。
        return loadRemoteOutcome(key, dirId, forceRefresh).songs
    }

    /**
     * 同步一个本地歌单。
     *
     * @param force 用户下拉刷新 ⇒ 跳过 TTL 与远程缓存，强制取一次。
     *
     * 行为：
     * 1. 没有远程来源 ⇒ [LocalSyncOutcome.NoRemoteSource]，**不发请求**；
     * 2. 拉取失败 ⇒ [LocalSyncOutcome.Failed]，**本地数据不动**（只加不减的另一半：
     *    失败时更不能删东西）；
     * 3. 成功 ⇒ [LocalPlaylistSync.merge] 合并 → 落盘（元数据 + 曲目一起写）。
     */
    suspend fun sync(
        context: Context,
        playlist: LocalPlaylist,
        force: Boolean,
        now: Long = System.currentTimeMillis(),
    ): LocalSyncOutcome {
        if (!playlist.hasRemoteSource) return LocalSyncOutcome.NoRemoteSource
        // 记住发请求前的修订号：网络期间用户可能删歌单、移除曲目或重新加回。
        // 没有这道闸门，旧同步返回后会把删除意图覆盖掉（歌单复活 / tombstone 被抹）。
        val revisionBefore = LocalPlaylistStore.currentRevision()
        val fetched = loadRemoteOutcome(playlist.key, playlist.dirId, forceRefresh = force)
        val remote = fetched.songs ?: return LocalSyncOutcome.Failed

        val existing = LocalPlaylistStore.readTracks(context, playlist.key)
        val merged = LocalPlaylistSync.merge(existing, remote, now)
        if (!fetched.complete) {
            // 降级/截断/缓存：能合的就合（只加不减，不丢用户数据），但**不算完整成功**：
            // 不更新 lastSyncedAt，让 TTL 之后还能再补取，也不向界面报 Success。
            LocalPlaylistStore.writePlaylistWithTracksIfUnchanged(
                context = context,
                playlist = playlist,           // lastSyncedAt 保持原值
                tracks = merged.tracks,
                now = now,
                expectedRevision = revisionBefore,
            )
            return LocalSyncOutcome.Partial(
                added = merged.added, skipped = merged.skipped,
                total = merged.tracks.count { it.isVisible },
            )
        }
        LocalPlaylistStore.writePlaylistWithTracksIfUnchanged(
            context = context,
            // lastSyncedAt 只在这里更新 —— 它是「成功同步过」的事实，失败时不写。
            playlist = playlist.copy(lastSyncedAt = now),
            tracks = merged.tracks,
            now = now,
            expectedRevision = revisionBefore,
        ) ?: return LocalSyncOutcome.Failed
        return LocalSyncOutcome.Success(
            added = merged.added,
            skipped = merged.skipped,
            total = merged.tracks.count { it.isVisible },
        )
    }

    /**
     * 进入歌单时的**按需同步**（任务书 4.5）。
     *
     * 只有 TTL 超时（或从未同步）才真的发请求；TTL 内直接返回 `null` 表示「这次什么都没做」。
     */
    suspend fun syncIfStale(
        context: Context,
        playlist: LocalPlaylist,
        now: Long = System.currentTimeMillis(),
    ): LocalSyncOutcome? {
        if (!LocalPlaylistSync.shouldAutoSync(playlist.lastSyncedAt, now)) return null
        return sync(context, playlist, force = false, now = now)
    }

    // ------------------------------------------------------------ 编辑操作 ----

    /** 读一个本地歌单的元数据 + 曲目。 */
    fun read(context: Context, key: PlaylistKey): Pair<LocalPlaylist?, List<LocalPlaylistTrack>> {
        val meta = LocalPlaylistStore.readPlaylists(context).firstOrNull { it.key == key }
        return meta to LocalPlaylistStore.readTracks(context, key)
    }

    /** 规则 6：从本地歌单移除一首歌（打 tombstone，不物理删除）。 */
    fun removeTrack(
        context: Context,
        key: PlaylistKey,
        trackKey: TrackKey,
        now: Long = System.currentTimeMillis(),
    ): List<LocalPlaylistTrack>? {
        val tracks = LocalPlaylistStore.readTracks(context, key)
        val next = LocalPlaylistSync.remove(tracks, trackKey)
        // 返回 null = 持久化没成功：调用方不得提示「已移除」，应保留可重试入口。
        return next.takeIf { persist(context, key, next, now) }
    }

    /** 规则 5 / 7：手动加一首歌（已存在则清除 tombstone）。 */
    fun addTrack(
        context: Context,
        key: PlaylistKey,
        song: SongItem,
        now: Long = System.currentTimeMillis(),
    ): List<LocalPlaylistTrack>? {
        val tracks = LocalPlaylistStore.readTracks(context, key)
        val next = LocalPlaylistSync.addManual(tracks, song, now)
        return next.takeIf { persist(context, key, next, now) }
    }

    /** 清空歌单：**连 tombstone 一起清**（规则里的「tombstone 清除条件」之一）。 */
    fun clear(
        context: Context,
        key: PlaylistKey,
        now: Long = System.currentTimeMillis(),
    ): List<LocalPlaylistTrack>? {
        val tracks = LocalPlaylistStore.readTracks(context, key)
        val next = LocalPlaylistSync.clearAll(tracks)
        return next.takeIf { persist(context, key, next, now) }
    }

    /** 新建一个纯手动（无远程来源）的本地歌单。 */
    fun createLocalOnly(
        context: Context,
        name: String,
        now: Long = System.currentTimeMillis(),
    ): LocalPlaylist {
        // 用时间戳造 id：同一毫秒内连点两次会撞，所以再串一个自增序号。
        val id = now.toString() + "-" + (localOnlyCounter++).toString()
        return LocalPlaylistStore.createPlaylist(
            context = context,
            key = LocalPlaylistSync.localOnlyKey(id),
            name = name,
            now = now,
        )
    }

    /**
     * 把一个**远程歌单**变成可编辑的本地歌单（首次进入时调用）。
     *
     * 幂等：已经建过就返回那一个，**不覆盖**用户已经攒下的曲目。
     */
    fun adoptRemote(
        context: Context,
        key: PlaylistKey,
        name: String,
        dirId: Long,
        now: Long = System.currentTimeMillis(),
    ): LocalPlaylist {
        val existing = LocalPlaylistStore.readPlaylists(context).firstOrNull { it.key == key }
        if (existing != null) return existing
        val created = LocalPlaylist(
            key = key,
            name = name,
            createdAt = now,
            updatedAt = now,
            lastSyncedAt = 0L,
            dirId = dirId,
        )
        val list = LocalPlaylistStore.readPlaylists(context)
        LocalPlaylistStore.writePlaylists(context, list + created)
        return created
    }

    /** 删除一个本地歌单（元数据 + 曲目，含 tombstone）。 */
    fun deletePlaylist(context: Context, key: PlaylistKey) {
        LocalPlaylistStore.deletePlaylist(context, key)
    }

    // -------------------------------------------------------------- 内部 ----

    /** 进程内自增，只用于给纯本地歌单造不撞的 id（**不落盘**，因此不需要持久化）。 */
    private var localOnlyCounter: Int = 0

    /** 写曲目 + 更新元数据的 `updatedAt`（一处落点，避免两个字段分叉）。 */
    private fun persist(
        context: Context,
        key: PlaylistKey,
        tracks: List<LocalPlaylistTrack>,
        now: Long,
    ): Boolean {
        val meta = LocalPlaylistStore.readPlaylists(context).firstOrNull { it.key == key }
            ?: LocalPlaylist(
                key = key,
                name = key.id,
                createdAt = now,
                updatedAt = now,
            )
        // Store 在 commit 失败时会回滚内存快照并返回 null；旧实现丢掉这个结果，
        // 于是「磁盘没写成功」在 UI 上仍然显示成「已加入」。
        return LocalPlaylistStore.writePlaylistWithTracks(context, meta, tracks, now) != null
    }
}
