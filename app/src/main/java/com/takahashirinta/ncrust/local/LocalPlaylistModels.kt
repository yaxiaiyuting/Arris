/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 · B：本地歌单的领域模型与**七条同步规则**。纯逻辑，无 Android 依赖，JVM 可单测。
 */

package com.takahashirinta.ncrust.local

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.source.TrackKey

/**
 * 一条本地歌单曲目**是怎么进来的**（v2.3.0 · B）。
 *
 * 这个区分是「只加不减」能成立的前提：同步只能动 [REMOTE] 那一部分，
 * [LOCAL] 是用户亲手放进去的，**任何同步都不得删除它**（任务书 4.2 第 5 条）。
 */
enum class LocalTrackOrigin {
    /** 由远程歌单同步而来。同步时可以继续追加新的，但**永远不会因为「远程没有」而被删掉**。 */
    REMOTE,

    /** 用户手动加进来的。同步时**永远保留**。 */
    LOCAL,
}

/**
 * 一个**本地**歌单（v2.3.0 · B）。
 *
 * ## 与远程歌单的关系
 *
 * [key] 复用 v2.2.0 的 [PlaylistKey]（铁律 6：不另起一套身份模型）。
 * 一个本地歌单可以绑定到一个远程歌单作为**同步来源**；也可以不绑定
 * （[key] 的 `id` 用 [LOCAL_ONLY_ID_PREFIX] 前缀，表示「纯手动，没有远程来源」）。
 *
 * ## 为什么 `id` 是 String 而 [PlaylistKey.id] 也是 String
 *
 * 直接沿用 [PlaylistKey] 的定义，不额外造一个 id 空间 —— ncm 的 playlistId 与
 * QQ 的 tid 都放进同一个 `String` 里，靠 `source` 区分（v2.2.0 已经这么做）。
 *
 * @property lastSyncedAt 上一次成功同步的时刻（ms）。0 = 从未同步。
 *   用于任务书 4.5 的 TTL 判定：**超时才自动同步，不自动全量同步**。
 */
data class LocalPlaylist(
    val key: PlaylistKey,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSyncedAt: Long = 0L,
    /**
     * qm 请求详情要用的**账号内目录号**（v2.2.0 的实测结论：`dirId` 是载荷、不是身份）。
     *
     * 它**不进 [PlaylistKey]**（身份归身份、载荷归载荷）—— 与 `Playlist.dirId` 是同一条规则。
     * 传 0 时 QQ 侧会按 `disstid` 让服务端自行解析，所以老数据缺这个字段也能同步。
     */
    val dirId: Long = 0L,
) {
    /** 是否有可同步的远程来源。 */
    val hasRemoteSource: Boolean get() = key.id.startsWith(LOCAL_ONLY_ID_PREFIX).not()

    companion object {
        /**
         * 纯本地歌单（没有远程来源）的 id 前缀。
         *
         * 用一个**不可能与真实 id 撞**的前缀：ncm 的 playlistId 是十进制数字、
         * QQ 的 tid 也是数字，所以 `local:` 开头是结构上安全的。
         */
        const val LOCAL_ONLY_ID_PREFIX = "local:"
    }
}

/**
 * 本地歌单里的一首歌（v2.3.0 · B）。
 *
 * ## 身份 / 载荷的划分（沿用 v2.1.5 的规则）
 *
 * - **身份**：[trackKey]。去重、判等、删除、重新添加**只看它**
 *   （`TrackKey.equals` 只比 `(source, id)`，见其 KDoc）。任务书 4.3 的
 *   「去重用 source + songId」就是这句话。
 * - **载荷**：[song]。列表渲染要歌名/歌手/封面，而 [TrackKey] 里没有这些。
 *   QQ 的 songmid 也在里面（取链需要）。**它不参与判等**。
 *
 * @property origin 见 [LocalTrackOrigin]。
 * @property addedAt 这一条进入本地歌单的时刻（**我们自己写的**，非远程时间）。
 * @property tombstoned 用户手动删过。**不物理删除**，否则下一次同步会把它复活
 *   （铁律 4：无 tombstone 的「只加不减」不成立）。
 */
data class LocalPlaylistTrack(
    val trackKey: TrackKey,
    val origin: LocalTrackOrigin,
    val addedAt: Long,
    val tombstoned: Boolean = false,
    val song: SongItem? = null,
) {
    /** 可渲染的曲目（tombstone 的条目不上屏）。 */
    val isVisible: Boolean get() = !tombstoned
}

/**
 * 本地歌单的**七条同步规则**（v2.3.0 · B）。纯逻辑，JVM 可单测。
 *
 * 任务书 4.2 逐条对应到下面七个函数，一条不多一条不少：
 *
 * | # | 规则 | 落点 |
 * |---|---|---|
 * | 1 | 远程有 + 本地无 + 不在 tombstone → 添加，origin = REMOTE | [merge] |
 * | 2 | 远程有 + 本地有 → 保留，不动 | [merge] |
 * | 3 | 远程无 + 本地有 → 保留（只加不减） | [merge]（**默认行为，不需要分支**） |
 * | 4 | 远程有 + 在 tombstone → 跳过 | [merge] |
 * | 5 | 本地手动新增 → origin = LOCAL，同步时永远保留 | [addManual] |
 * | 6 | 用户手动删除 → 设 tombstoned = true，不物理删除 | [remove] |
 * | 7 | 用户手动重新添加同一首 → 清除 tombstone | [addManual] |
 *
 * 另有两条**不属于七条规则、但必须存在**的维护操作：
 * [clearAll]（清空歌单 ⇒ tombstone 一起清）与 [trimToLimit]（容量有界，见其 KDoc）。
 *
 * ## 排序（任务书 4.4）
 *
 * **不做整体重排**。已存在的条目保持原下标；只有新条目追加到末尾。
 * 这一点是结构性保证而不是「我们记得别排序」——[merge] 全程用一次线性遍历
 * 构造新列表，从不调用 `sortedBy`。
 */
object LocalPlaylistSync {

    /**
     * 一次同步的结果。
     *
     * @property tracks 新的曲目列表（含 tombstone 条目，顺序稳定）。
     * @property added 本次新增的条数（规则 1）。
     * @property skipped 本次因 tombstone 被跳过的条数（规则 4）——UI 用它解释
     *   「为什么远程明明有、本地却是空的」。
     */
    data class Result(
        val tracks: List<LocalPlaylistTrack>,
        val added: Int,
        val skipped: Int,
    )

    /**
     * 把远程曲目合并进本地列表。**唯一会新增条目的地方。**
     *
     * @param existing 现有本地曲目（含 tombstone 条目，顺序即展示顺序）。
     * @param remoteSongs 远程歌单当前的曲目（**同一音源**；跨源不合并 —— 铁律）。
     * @param now 本次同步的时刻（ms）——新增条目的 `addedAt`。
     *
     * 实现要点：
     * - 用 `linkedMapOf` 建索引，**判等完全交给 [TrackKey]**（它已覆写 equals 只看 `(source, id)`）；
     * - 遍历 `existing` 时**原样放进输出**（规则 2 / 3 / 5 全在这一步被满足：
     *   不动 origin、不动 addedAt、不动下标、不删任何东西）；
     * - 只有在 `existing` 里**完全没见过**的远程曲目才可能被追加（规则 1），
     *   而它一旦在 tombstone 集合里就跳过（规则 4）。
     */
    fun merge(
        existing: List<LocalPlaylistTrack>,
        remoteSongs: List<SongItem>,
        now: Long,
    ): Result {
        val out = ArrayList<LocalPlaylistTrack>(existing.size + remoteSongs.size)
        // 索引到条目本身（不只是「见过没见过」）—— 规则 2 与规则 4 的区别就在被索引到的
        // 那一条的 tombstoned 上：都是「本地已经有这一条」，但一个是正常保留、
        // 另一个是「用户删过，尊重他的删除」。
        val byKey = HashMap<TrackKey, LocalPlaylistTrack>(existing.size)
        for (t in existing) {
            byKey[t.trackKey] = t
            out.add(t)   // 规则 2 / 3 / 5：原样保留（不删、不重排、不改 origin/addedAt）
        }

        var added = 0
        var skipped = 0
        for (song in remoteSongs) {
            if (song.id <= 0L) continue          // 没有数字身份，进不了队列，直接丢
            val key = TrackKey.fromSong(song)
            val previous = byKey[key]
            if (previous != null) {
                // 规则 4：远程有 + 本地在 tombstone → 跳过（尊重用户删除）。
                // 规则 2：远程有 + 本地有（活的）→ 保留，不动，也不计数。
                if (previous.tombstoned) skipped++
                continue
            }
            val fresh = LocalPlaylistTrack(
                trackKey = key,
                origin = LocalTrackOrigin.REMOTE,
                addedAt = now,
                tombstoned = false,
                song = song,
            )
            byKey[key] = fresh
            out.add(fresh)                        // 规则 1：追加到**末尾**
            added++
        }
        return Result(out, added, skipped)
    }

    /**
     * 规则 6：用户手动删除 ⇒ 打 tombstone，**不物理删除**。
     *
     * ## 为什么「不物理删除」是规则 4 能成立的全部原因
     *
     * 设计上的一个关键选择：**tombstone 条目留在列表里**（只是
     * [LocalPlaylistTrack.tombstoned] = true），而不是塞进一个单独的 `Set<TrackKey>` 黑名单。
     * 三个好处：
     *
     * 1. **一条记录表达一件事** —— 「这首被删过」与「这首是谁、什么时候进来的」在同一个地方，
     *    不需要两个结构互相同步（两份状态迟早会不一致）；
     * 2. **重启后天然生效**：它跟着列表一起落盘，不需要额外的持久化路径；
     * 3. [merge] 因此只需要两个分支：「本地有这一条吗」+「它是 tombstone 吗」。
     *
     * 谁要是「顺手」在这里加一句 `filterNot { it.tombstoned }`，
     * **规则 4 会当场失效**（删掉的歌会在下一次同步复活）——
     * 这段注释就是留给他的，`LocalPlaylistSyncTest` 有专门的用例钉住这条不变量。
     */
    fun remove(tracks: List<LocalPlaylistTrack>, trackKey: TrackKey): List<LocalPlaylistTrack> {
        var hit = false
        val out = tracks.map { t ->
            if (t.trackKey == trackKey && !t.tombstoned) {
                hit = true
                t.copy(tombstoned = true)
            } else {
                t
            }
        }
        // 没命中说明「这首歌不在这个歌单里」——原样返回，调用方不需要区分
        // （UI 的入口只在列表里出现，正常情况下不会命中不到）。
        return if (hit) out else tracks
    }

    /**
     * 规则 5 / 7：用户手动加一首歌。
     *
     * - **不在这里的** ⇒ 追加到末尾，[LocalTrackOrigin.LOCAL]；
     * - **已经在的**（无论是否 tombstoned）⇒ **清除 tombstone** 并把 origin 改成 [LocalTrackOrigin.LOCAL]，
     *   **保持它原来的下标**（规则 7 + 任务书 4.4「不做整体重排」）。
     *
     * ## 为什么重新添加要把 origin 改成 LOCAL
     *
     * 规则 7 的语义是「用户的删除意图被撤销了」。撤销之后这一条**既是**远程有的、
     * **也是**用户亲手放回来的 —— 两种解释都成立。选 LOCAL 是因为它是**更强**的那一种：
     * 万一用户之后又退订了那个远程歌单，LOCAL 保证这条不会跟着消失；
     * 反过来选 REMOTE 则没有任何额外好处。这是「选不会丢用户数据的那一侧」。
     */
    fun addManual(
        tracks: List<LocalPlaylistTrack>,
        song: SongItem,
        now: Long,
    ): List<LocalPlaylistTrack> {
        if (song.id <= 0L) return tracks
        val key = TrackKey.fromSong(song)
        val idx = tracks.indexOfFirst { it.trackKey == key }
        if (idx < 0) {
            return tracks + LocalPlaylistTrack(
                trackKey = key,
                origin = LocalTrackOrigin.LOCAL,
                addedAt = now,
                tombstoned = false,
                song = song,
            )
        }
        val old = tracks[idx]
        val out = tracks.toMutableList()
        out[idx] = old.copy(
            origin = LocalTrackOrigin.LOCAL,
            tombstoned = false,
            // 载荷补全：同一首歌第二次进来可能带更完整的元数据（例如第一次只有 id）。
            song = song,
        )
        return out
    }

    /**
     * 清空歌单：**连 tombstone 一起清**（任务书 4.2 的「tombstone 清除条件」之一）。
     *
     * 返回空列表就够了吗？是的 —— tombstone 就存在列表里（见 [remove] 的说明），
     * 列表空了，删除记录也就没了。这正是「一条记录表达一件事」的收益：
     * 「清空歌单」不需要第二个清理动作。
     */
    @Suppress("UNUSED_PARAMETER")
    fun clearAll(tracks: List<LocalPlaylistTrack>): List<LocalPlaylistTrack> = emptyList()

    /**
     * 容量裁剪：**有界性**（铁律 8）。
     *
     * ## 为什么 tombstone 必须能过期
     *
     * tombstone 只增不减（每次删除都留一条），而 `SharedPreferences` 是**整个文件读进内存**的。
     * 一个反复加删的用户会让这个文件无限增长 —— 所以必须有回收策略。
     *
     * ## 回收顺序：**先丢最旧的 tombstone，绝不丢活动条目**
     *
     * 这是本函数唯一的难点。丢活动条目 = 用户的数据消失（不可接受）；
     * 丢 tombstone = 那一首歌**可能在下次同步时复活**（可接受，而且它本来就是「被删过的」）。
     * 所以：只要还有 tombstone，就先丢它们（按 [LocalPlaylistTrack.addedAt] 最旧优先）；
     * tombstone 丢完了还超限，就**不再裁剪**并保持原样 —— 宁可暂时超限，
     * 也不静默删用户看得见的歌。
     *
     * @return 裁剪后的列表 + 是否发生了裁剪。
     */
    fun trimToLimit(
        tracks: List<LocalPlaylistTrack>,
        maxTracks: Int = MAX_TRACKS_PER_PLAYLIST,
    ): List<LocalPlaylistTrack> {
        if (maxTracks <= 0) return tracks
        if (tracks.size <= maxTracks) return tracks
        val tombstones = tracks.filter { it.tombstoned }.sortedBy { it.addedAt }
        val overflow = tracks.size - maxTracks
        if (tombstones.size < overflow) return tracks   // 只靠 tombstone 清不出足够空间 ⇒ 放弃裁剪
        val drop = tombstones.take(overflow).toHashSet()
        return tracks.filterNot { drop.contains(it) }
    }

    /** 单个歌单的曲目上限（含 tombstone）。见 [trimToLimit] 的取舍说明。 */
    const val MAX_TRACKS_PER_PLAYLIST = 2000

    /** 本地歌单个数上限。 */
    const val MAX_PLAYLISTS = 100

    /** 进入歌单时判定「要不要自动同步」的 TTL —— 与远程歌单缓存同一个数，不另造。 */
    const val SYNC_TTL_MS = 10 * 60 * 1000L

    /**
     * 任务书 4.5：进入歌单时是否该自动同步。
     *
     * @param lastSyncedAt 上次成功同步时刻；0 = 从未同步 ⇒ **一定要同步**。
     * @param now 当前时刻。
     * @param force 用户下拉刷新 ⇒ 无条件同步。
     */
    fun shouldAutoSync(lastSyncedAt: Long, now: Long, force: Boolean = false): Boolean {
        if (force) return true
        if (lastSyncedAt <= 0L) return true
        // 时钟被往回拨（用户改系统时间 / NTP 校正）时 `now - lastSyncedAt` 会是负数。
        // 那种情况下**同步一次**（保守），而不是把 TTL 当成无限长。
        val elapsed = now - lastSyncedAt
        if (elapsed < 0L) return true
        return elapsed >= SYNC_TTL_MS
    }

    /** 一个新的**纯本地**歌单 key（没有远程来源）。 */
    fun localOnlyKey(id: String, ownerId: String = PlaylistKey.OWNER_ANONYMOUS): PlaylistKey =
        PlaylistKey(
            source = MusicSource.DEFAULT,
            id = LocalPlaylist.LOCAL_ONLY_ID_PREFIX + id,
            ownerId = ownerId,
        )
}
