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
import com.takahashirinta.ncrust.playlist.PlaylistDegradation
import com.takahashirinta.ncrust.playlist.PlaylistLoadOutcome
import com.takahashirinta.ncrust.playlist.PlaylistResult
import com.takahashirinta.ncrust.qq.QqPlaylistRepository
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.source.TrackKey
import kotlinx.coroutines.CancellationException

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
     *
     * ⚠️ **只有真的落盘成功才允许返回本结果**（R3）：写盘失败 / 期间的删除或编辑让提交
     * 被拒时，返回的是 [Failed] —— 否则界面会显示一个从未提交的新增数量。
     *
     * @property cause 为什么算不完整。界面可以据此说清"是没网"还是"没取全"。
     */
    data class Partial(
        val added: Int,
        val skipped: Int,
        val total: Int,
        val cause: LocalPartialCause = LocalPartialCause.UNKNOWN,
    ) : LocalSyncOutcome

    /** 远程拉取失败（网络 / 登录态 / 歌单不存在）。**本地数据一个字节都不动。** */
    data object Failed : LocalSyncOutcome
}

/**
 * [LocalSyncOutcome.Partial] 的成因（H10 / H21）。
 *
 * 做成一枚举而不是一个错误字符串：界面文案要能一一对应，而且"没网"与"没取全"
 * 是两种完全不同的用户动作（重试 vs 等它自己补齐）。
 */
enum class LocalPartialCause {

    /** 联网失败或不允许联网，展示的是本地/远程缓存。 */
    CACHE_FALLBACK,

    /** 登录态失效，展示的是缓存。 */
    NEED_LOGIN,

    /** 服务端声明的曲目数没取全（分页被截断 / 响应缺数组 / 条目解析不出来）。 */
    TRUNCATED,

    /** 拿到了但不新鲜，具体原因上游没给。 */
    UNKNOWN,
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
 * | 拉 ncm 歌单曲目 | `PlaylistApi.getPlaylistDetailResult`（GET 语义，只读；带上完整性） |
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
     * 远程读取的**三种结局**（H10）：完整 / 不完整 / 失败。
     *
     * 旧实现是「`List<SongItem>?` + 一个 Boolean」，只有两格：拿到列表就一律算完整。
     * 于是「取到一半」「失败后回落到空表」「缓存回退」全被压成同一格 —— 网易那一路干脆
     * 把 `complete` 写死成 true，截断和失败都能冒充"完整成功"，然后推进 lastSyncedAt。
     *
     * 现在失败与不完整各有自己的类型：[songs] 只表示"能用的那一部分"，
     * 它是否权威由 [partialCause] 回答（null = 完整）。
     *
     * H10 的收尾：网易那一路不再把 `complete` 写死成 true —— 上游
     * `PlaylistApi.getPlaylistDetailResult` 已经把「声明总数 / 失败批次数 / 是否完整」
     * 带回来了，[loadRemote] 直接按它分流（见 `MusicSource.NETEASE` 分支）。
     */
    internal sealed interface RemoteRead {

        /** 能用的曲目（[Failed] 时为空）。 */
        val songs: List<SongItem>

        /** null = 完整读取；非 null = 只拿到一部分，值就是原因。 */
        val partialCause: LocalPartialCause?

        data class Complete(override val songs: List<SongItem>) : RemoteRead {
            override val partialCause: LocalPartialCause? get() = null
        }

        data class Incomplete(
            override val songs: List<SongItem>,
            override val partialCause: LocalPartialCause,
        ) : RemoteRead

        data object Failed : RemoteRead {
            override val songs: List<SongItem> get() = emptyList()
            override val partialCause: LocalPartialCause? get() = null
        }
    }

    private suspend fun loadRemote(
        key: PlaylistKey,
        dirId: Long,
        forceRefresh: Boolean,
    ): RemoteRead {
        return when (key.source) {
            MusicSource.QQMUSIC -> {
                // 与网易那一路同一条纪律：协程取消**原样抛出**，不用 runCatching
                // 把它折成"读取失败"（否则取消会被上层当成网络错误，再也停不下来）。
                val result = try {
                    QqPlaylistRepository.loadDetail(key, dirId, forceRefresh)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return RemoteRead.Failed
                }
                when (result) {
                    is PlaylistResult.Data -> {
                        val outcome = result.outcome
                        // 只有"联网取到的新鲜完整数据"才算完整；缓存/降级/截断一律不算。
                        if (outcome.fresh && outcome.degradation == null) {
                            RemoteRead.Complete(outcome.data.songs)
                        } else {
                            RemoteRead.Incomplete(outcome.data.songs, partialCauseOf(outcome))
                        }
                    }
                    is PlaylistResult.Failed -> RemoteRead.Failed
                }
            }
            MusicSource.NETEASE -> {
                val pid = key.id.toLongOrNull() ?: return RemoteRead.Failed
                // H10：`PlaylistDetailResult` 把完整性一起带回来了 ——
                // 请求/解析失败是 [RemoteRead.Failed]；拿到了但「有批次没取回」或
                // 「条数少于服务端声明」是 [RemoteRead.Incomplete]，上层据此只合并、
                // **不推进 lastSyncedAt**，TTL 之后还能再补取。
                // 取消异常必须原样抛出：runCatching 会把协程取消吞成"读取失败"，
                // 于是取消看起来像网络错误（项目纪律：CancellationException 不吞）。
                val detail = try {
                    PlaylistApi.getPlaylistDetailResult(pid)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return RemoteRead.Failed
                }
                if (detail.complete) {
                    RemoteRead.Complete(detail.songs)
                } else {
                    // 条数对不上 / 某批详情失败 / 响应缺 playlist 对象 —— 都归 TRUNCATED
                    // （该枚举的语义正是「服务端声明的曲目数没取全」）。
                    RemoteRead.Incomplete(detail.songs, LocalPartialCause.TRUNCATED)
                }
            }
            MusicSource.BILIBILI -> RemoteRead.Failed
        }
    }

    /**
     * 把 QQ 侧已有的降级标记映射成本层的成因（H10 / H21 的"完整性带回上层"）。
     *
     * `fresh=false` 但没有任何 degradation 是旧缓存/老代码写下的形状：那也**不是**完整同步，
     * 按"缓存回退"处理，绝不放行成 Success。
     */
    internal fun partialCauseOf(outcome: PlaylistLoadOutcome<*>): LocalPartialCause =
        when (outcome.degradation) {
            PlaylistDegradation.OFFLINE -> LocalPartialCause.CACHE_FALLBACK
            PlaylistDegradation.NEED_LOGIN -> LocalPartialCause.NEED_LOGIN
            PlaylistDegradation.TRUNCATED -> LocalPartialCause.TRUNCATED
            null -> if (outcome.fresh) LocalPartialCause.UNKNOWN else LocalPartialCause.CACHE_FALLBACK
        }

    /** 供既有调用方用的"尽力读"：失败返回 null；不完整照旧返回拿到的那一部分。 */
    suspend fun loadRemoteSongs(
        key: PlaylistKey,
        dirId: Long,
        forceRefresh: Boolean,
    ): List<SongItem>? = when (val read = loadRemote(key, dirId, forceRefresh)) {
        is RemoteRead.Failed -> null
        else -> read.songs
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
     * 4. 落盘被拒（写盘失败 / 期间删除 / 期间编辑）⇒ [LocalSyncOutcome.Failed]，
     *    **连 Partial 都不许回**（R3）。
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
        val read = loadRemote(playlist.key, playlist.dirId, forceRefresh = force)
        if (read is RemoteRead.Failed) return LocalSyncOutcome.Failed
        // 降级/截断/缓存：能合的就合（只加不减，不丢用户数据），但**不算完整成功**：
        // 不更新 lastSyncedAt，让 TTL 之后还能再补取，也不向界面报 Success。
        val incompleteCause = read.partialCause

        val existing = LocalPlaylistStore.readTracks(context, playlist.key)
        val merged = LocalPlaylistSync.merge(existing, read.songs, now)
        // lastSyncedAt 只在完整读取时推进 —— 它是「完整同步过」的事实，别的时候不写。
        val toWrite = if (incompleteCause == null) playlist.copy(lastSyncedAt = now) else playlist
        // ★ 提交结果是**唯一**的成功判据：写盘失败、期间被删、期间被编辑都会返回 null。
        //   R3 的根因就是这一段在"不完整"分支里被整段跳过、返回值被忽略：
        //   于是写盘失败时界面仍显示「新增 N 首」，而磁盘上一条都没提交。
        LocalPlaylistStore.writePlaylistWithTracksIfUnchanged(
            context = context,
            playlist = toWrite,
            tracks = merged.tracks,
            now = now,
            expectedRevision = revisionBefore,
        ) ?: return LocalSyncOutcome.Failed

        val total = merged.tracks.count { it.isVisible }
        return if (incompleteCause == null) {
            LocalSyncOutcome.Success(
                added = merged.added,
                skipped = merged.skipped,
                total = total,
            )
        } else {
            LocalSyncOutcome.Partial(
                added = merged.added,
                skipped = merged.skipped,
                total = total,
                cause = incompleteCause,
            )
        }
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

    /**
     * 新建一个纯手动（无远程来源）的本地歌单。
     *
     * @return 新建的歌单；**null = 落盘失败**（H09）。
     *
     * 旧实现无论磁盘有没有写成功都返回一个对象，于是 UI 无条件关掉对话框、弹出
     * 「已创建」；进程重启后歌单并不存在。现在提交失败必须是**可判**的失败：
     * 调用方拿到 null 就保留对话框/重试入口，并提示失败。
     */
    fun createLocalOnly(
        context: Context,
        name: String,
        now: Long = System.currentTimeMillis(),
    ): LocalPlaylist? {
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
     *
     * 写盘失败返回 null；元数据创建与读取共用 Store 提交锁，不能虚报转存成功。
     */
    fun adoptRemote(
        context: Context,
        key: PlaylistKey,
        name: String,
        dirId: Long,
        now: Long = System.currentTimeMillis(),
    ): LocalPlaylist? = synchronized(LocalPlaylistStore) {
        val existing = LocalPlaylistStore.readPlaylists(context).firstOrNull { it.key == key }
        if (existing != null) return@synchronized existing
        val created = LocalPlaylist(
            key = key,
            name = name,
            createdAt = now,
            updatedAt = now,
            lastSyncedAt = 0L,
            dirId = dirId,
        )
        val list = LocalPlaylistStore.readPlaylists(context)
        created.takeIf { LocalPlaylistStore.writePlaylists(context, list + created) }
    }

    /**
     * 删除一个本地歌单（元数据 + 曲目，含 tombstone）。
     *
     * @return true = 歌单列表这次提交成功（曲目表删除不单独报告，见报告）。
     */
    fun deletePlaylist(context: Context, key: PlaylistKey): Boolean =
        LocalPlaylistStore.deletePlaylist(context, key)

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
