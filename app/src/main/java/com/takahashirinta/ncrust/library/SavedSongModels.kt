/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.0 · P0：收藏库（`ncrust_library` / `saved_songs`）的领域模型与**七条同步规则**。
 * 纯逻辑，无 Android 依赖，JVM 可单测。
 */

package com.takahashirinta.ncrust.library

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.TrackKey

/**
 * 「加入库」这一次动作的**真实结果**（v2.6.0 · P0）。
 *
 * 存在这个枚举的理由是**诚实**：旧实现 `saveSong` 返回 `Unit`，8 个调用点
 * 因此只能无条件弹「已加入库」—— 用户看到的成败与真实成败完全脱钩。
 * 有了返回值，「什么时候该说成功」变成调用点一次显式判断。
 *
 * 它**不描述云端同步的成败**：云端的 `like` 是 fire-and-forget 的异步请求
 * （受风控、会失败、可能根本没登录），把它混进来会让「已加入库」这个提示
 * 变成一个需要等待网络的承诺。本版的语义是「**已经写进本地库并落盘**」，
 * 那正是用户点这个按钮要的结果 —— 而云端同步是**附带**的。
 */
enum class SavedSongOutcome {
    /** 新写入（或从 tombstone 复活）。已落盘。 */
    ADDED,

    /** 本来就在库里。零变化、零网络请求（幂等），对用户同样是「在库里」。 */
    ALREADY_SAVED,

    /** 不是任何真实曲目（`id <= 0`）。**没有写入，不许弹成功。** */
    REJECTED,
    ;

    /** 调用点据此决定要不要提示成功。 */
    val isSuccess: Boolean get() = this != REJECTED
}

/**
 * 一条收藏库曲目**是怎么进来的**（v2.6.0 · P0）。
 *
 * ## 与 [com.takahashirinta.ncrust.local.LocalTrackOrigin] 的关系
 *
 * 两者取值相同、语义相同，但**刻意不合并**：那是「本地歌单」（用户自建歌单，
 * `local.LocalPlaylist*`）的来源标记，这是「收藏库」（库页「单曲」tab，
 * 云端红心歌单的镜像）的来源标记。两个子系统的生命周期、同步来源、删除语义
 * 都不同（本地歌单同步的是**歌单曲目**，收藏库同步的是**账号红心列表**），
 * 共用一个枚举只会把「改一个影响另一个」变成一次静默的跨子系统耦合。
 *
 * 这个区分是「只加不减」能成立的前提：同步只能动 [REMOTE] 那一部分，
 * [LOCAL] 是用户亲手放进去的，**任何同步都不得删除它**（铁律 22）。
 */
enum class SavedSongOrigin {
    /** 由云端红心歌单同步而来。同步时可以继续追加新的，但**永远不会因为「云端没有」而被删掉**。 */
    REMOTE,

    /** 用户手动「加入库」进来的（含 qm 曲目）。同步时**永远保留**。 */
    LOCAL,
}

/**
 * 收藏库里的一首歌（v2.6.0 · P0）。
 *
 * ## 身份 / 载荷的划分（沿用 v2.1.5 的规则）
 *
 * - **身份**：[trackKey]。去重、判等、删除、重新添加**只看它**
 *   （`TrackKey.equals` 只比 `(source, id)`）。任务书 3.2 的「去重用 source + songId」
 *   就是这句话。
 * - **载荷**：[song]。列表渲染要歌名/歌手/封面，而 [TrackKey] 里没有这些；
 *   QQ 的 songmid 也在里面（取链需要）。**它不参与判等**。
 *
 * ## 为什么必须存 [song] 而不是只存 id
 *
 * v2.6.0 之前这里只存 `SongItem`，但没有「来源」这一维，于是**同步无法区分
 * 「云端删掉的」与「用户手动加的」**——两者都表现为「id 不在云端 likedIds 里」，
 * 同步按「云端为真源」把两者一起丢掉。这就是 QQ 曲目「入库后刷新消失」的根因。
 *
 * @property origin 见 [SavedSongOrigin]。
 * @property addedAt 这一条进入收藏库的时刻（**我们自己写的**，非云端时间）。
 * @property tombstoned 用户手动删过。**不物理删除**，否则下一次同步会把它复活
 *   （铁律 4 的同一条理由：无 tombstone 的「只加不减」不成立）。
 */
data class SavedSongEntry(
    val trackKey: TrackKey,
    val origin: SavedSongOrigin,
    val addedAt: Long,
    val tombstoned: Boolean = false,
    val song: SongItem,
) {
    /** 可渲染的条目（tombstone 不上屏）。 */
    val isVisible: Boolean get() = !tombstoned
}

/**
 * 收藏库的**七条同步规则**（v2.6.0 · P0）。纯逻辑，JVM 可单测。
 *
 * 任务书 3.2 逐条对应到下面这些函数，一条不多一条不少：
 *
 * | # | 规则 | 落点 |
 * |---|---|---|
 * | 1 | 远程有 + 本地无 + 不在 tombstone → 添加，origin = REMOTE | [merge] |
 * | 2 | 远程有 + 本地有 → 保留，不动 | [merge] |
 * | 3 | 远程无 + 本地有 → 保留（只加不减） | [merge] |
 * | 4 | 远程有 + 在 tombstone → 跳过 | [merge] |
 * | 5 | 本地手动新增 → origin = LOCAL，同步永远保留 | [addManual] |
 * | 6 | 用户手动删除 → 设 tombstoned = true，不物理删除 | [remove] |
 * | 7 | 用户重新添加 → 清除 tombstone | [addManual] |
 *
 * 另有两条**不属于七条规则、但必须存在**的维护操作：
 * [appendRemote]（分页补详情时的纯追加路径）与 [isRemoteLikeEligible]
 * （跨源写操作的闸门判据）。
 *
 * ## 排序口径（有意为之，写在这里以免下一个人「顺手」改掉）
 *
 * 结果顺序 = **① 本地手动新增（可见，保持原有相对顺序）→ ② 云端 likedIds 顺序里可见的
 * 条目 → ③ 云端没有、但本地有的（只加不减，保持原有相对顺序）→ ④ tombstone 记账尾巴**。
 *
 * ①放在最前是因为 `saveSong` 的既有语义就是「新加的插到最前」——若改成
 * 「远端顺序在前」，用户加完一首歌再刷新，那首歌会**跳到列表末尾**，
 * 那是一个用户可见的回归。④放在最后是因为它不上屏，但必须留在表里参与判重。
 */
object SavedSongSync {

    /**
     * tombstone 记账尾巴的容量上限。
     *
     * 「不物理删除」意味着这张表会随用户删除次数单调增长，而它是**整个 JSON 一起
     * 落盘**的（SharedPreferences 单 key）。没有上限 = 一个反复加删的用户能把
     * 一份 prefs 撑到任意大（铁律 5：失败处理必须有界）。上限按 [SavedSongEntry.addedAt]
     * 的**新→旧**保留 —— 只有最近的删除才可能被同步复活，最老的 tombstone 早已
     * 不在任何云端 likedIds 里，丢掉它们是安全的。
     */
    const val MAX_TOMBSTONES = 500

    // ------------------------------------------------------------------ 规则 1~4 ----

    /**
     * 云端刷新：把 [remoteIds]（有序）与 [remoteSongs]（已取到详情的子集）合并进 [local]。
     *
     * @param local 当前内存里的条目（**唯一的真源**，不是磁盘上的那份）。
     * @param remoteIds 云端红心歌单的全部 id，**有序**。
     * @param remoteSongs 本次已经取到详情的 id → SongItem。**允许是 [remoteIds] 的子集**
     *   （首屏只取前 50 首详情）：取不到详情的 id 这一轮**不加**，但它仍在
     *   [remoteIds] 里，下一次刷新还会被尝试 —— 所以这不是丢数据。
     * @param now 本次同步时刻（ms），写给新加入的条目。
     */
    fun merge(
        local: List<SavedSongEntry>,
        remoteIds: List<Long>,
        remoteSongs: Map<Long, SongItem>,
        now: Long,
    ): List<SavedSongEntry> {
        val existing = LinkedHashMap<TrackKey, SavedSongEntry>(local.size * 2)
        for (e in local) existing[e.trackKey] = e
        val covered = remoteIds.toHashSet()

        val out = LinkedHashMap<TrackKey, SavedSongEntry>(local.size + remoteIds.size)

        // ① 本地手动新增（可见）：保持原有相对顺序，永远排在最前。
        for (e in local) {
            if (e.origin == SavedSongOrigin.LOCAL && e.isVisible) out[e.trackKey] = e
        }

        // ② 云端顺序里可见的条目：已有条目按云端顺序重排（与既有行为逐字一致），
        //    云端有、本地没有且不在 tombstone 的在此追加（规则 1）。
        for (id in remoteIds) {
            val key = TrackKey(MusicSource.NETEASE, id)
            val cur = existing[key]
            when {
                // 规则 4：云端有 + 在 tombstone ⇒ 跳过（绝不复活）。
                cur != null && cur.tombstoned -> Unit
                // 规则 2：云端有 + 本地有 ⇒ 保留，不动。
                cur != null -> out[key] = cur
                else -> {
                    val song = remoteSongs[id] ?: continue
                    out[key] = SavedSongEntry(key, SavedSongOrigin.REMOTE, now, false, song)
                }
            }
        }

        // ③ 云端没有、但本地有（可见）：只加不减（规则 3）。
        //    ⚠️ `remoteIds`（云端红心歌单）**只描述 ncm 那一侧**，所以「云端有没有」
        //    这个判据对 QQ 条目恒不成立 —— QQ 条目一律走只加不减。
        //    `origin = LOCAL` 的条目在①已经进 out，LinkedHashMap 对已有 key 的
        //    重新赋值**不改变插入顺序**，所以它们仍然排在最前。
        for (e in local) {
            if (!e.isVisible) continue
            val coveredByRemote =
                e.trackKey.source == MusicSource.NETEASE && e.trackKey.id in covered
            if (coveredByRemote) continue
            out.getOrPut(e.trackKey) { e }
        }

        // ④ tombstone 记账尾巴：不上屏，但必须留在表里，否则下一次同步会把它复活。
        //    有界（MAX_TOMBSTONES），按 addedAt 新→旧保留。
        local.asSequence()
            .filter { entry -> entry.tombstoned }
            .sortedByDescending { entry -> entry.addedAt }
            .take(MAX_TOMBSTONES)
            .forEach { entry -> out.getOrPut(entry.trackKey) { entry } }

        return out.values.toList()
    }

    // ------------------------------------------------------------------ 规则 5 ----

    /**
     * 用户手动「加入库」（规则 5 / 7）。**纯函数**，调用方负责落盘。
     *
     * 三种情形：
     * - 表里没有 → 插到**最前**，`origin = LOCAL`（规则 5）；
     * - 表里有且 tombstoned → **清除 tombstone**、改成 LOCAL、`addedAt = now`、
     *   并移到最前（规则 7：用户重新添加 ⇒ 这条歌回来了）；
     * - 表里有且可见 → 原样返回（**幂等**，重复点不改变顺序，也不改 origin ——
     *   一首云端同步来的歌被「再加一次」不该把它降级成 LOCAL，那会让下一次
     *   云端顺序重排失效）。
     */
    fun addManual(local: List<SavedSongEntry>, song: SongItem, now: Long): List<SavedSongEntry> {
        val key = TrackKey.ofSong(song)
        val existing = local.firstOrNull { it.trackKey == key }
        if (existing != null && existing.isVisible) return local
        val fresh = SavedSongEntry(key, SavedSongOrigin.LOCAL, now, false, song)
        val out = ArrayList<SavedSongEntry>(local.size + 1)
        out.add(fresh)
        for (e in local) if (e.trackKey != key) out.add(e)
        return out
    }

    /** 批量版 [addManual]：**逐首**走同一条判据，顺序按入参顺序叠在最前（后加的在上）。 */
    fun addManualAll(local: List<SavedSongEntry>, songs: List<SongItem>, now: Long): List<SavedSongEntry> {
        var acc = local
        for (s in songs) acc = addManual(acc, s, now)
        return acc
    }

    // ------------------------------------------------------------------ 规则 6 ----

    /**
     * 用户手动删除（规则 6）：**设 tombstone，不物理删除**。
     *
     * 幂等，**且幂等时返回同一个列表实例**：调用方（[LibraryManager.removeSong]）
     * 用引用相等（`after !== before`）判断「这次到底改没改」，
     * 而 `map {}` 会无条件造一个新列表 —— 那会让一次对不存在 key 的删除
     * 也触发一次落盘与一次 `like(false)` 网络请求。
     * 对不存在的 key / 已经 tombstone 的 key 一律原样返回。
     *
     * ## 为什么这里也要走一次 [boundTombstones]
     *
     * tombstone 只在**这个函数**里增长。若只在 [merge] 里截断，一个
     * 「加一首→删一首」反复操作、但期间从不刷新的用户能把表撑到任意大
     * （`LibraryImportPersistenceTest` 里那条「落盘体积无界」的用例就是
     * 用 800 次加删把它照出来的：当时看到的是 800 条而不是 500 条）。
     */
    fun remove(local: List<SavedSongEntry>, key: TrackKey): List<SavedSongEntry> {
        val idx = local.indexOfFirst { it.trackKey == key }
        if (idx < 0) return local
        if (local[idx].tombstoned) return local
        val out = ArrayList<SavedSongEntry>(local.size)
        out.addAll(local)
        out[idx] = out[idx].copy(tombstoned = true)
        return boundTombstones(out)
    }

    /**
     * tombstone 尾巴的有界化：可见条目**顺序与实例都不动**，tombstone 只保留
     * 最新的 [MAX_TOMBSTONES] 条并集中到表尾。
     *
     * **没有超限时返回同一个实例**（引用相等），这是它唯一的调用方
     * （[remove]）判断「要不要落盘」的依据 —— 一次多余的落盘就是一次
     * 多余的 prefs 全量 JSON 序列化。
     */
    fun boundTombstones(local: List<SavedSongEntry>): List<SavedSongEntry> {
        var tombstones = 0
        for (e in local) if (e.tombstoned) tombstones++
        if (tombstones <= MAX_TOMBSTONES) return local
        val out = ArrayList<SavedSongEntry>(local.size)
        for (e in local) if (!e.tombstoned) out.add(e)
        local.asSequence()
            .filter { it.tombstoned }
            .sortedByDescending { it.addedAt }
            .take(MAX_TOMBSTONES)
            .forEach { out.add(it) }
        return out
    }

    // ------------------------------------------------------- 分页补详情的纯追加路径 ----

    /**
     * 滚动到底拉下一批详情时的**纯追加**（不是 [merge]）。
     *
     * 为什么不用 [merge]：分页拿到的只是一段 slice，而 [merge] 会把「不在这一批里的」
     * 条目重排到后面 —— 每翻一页列表顺序就抖一次。这里只做「append if absent」，
     * 与 v2.6.0 之前的 `loadMoreLikedSongs` 行为逐字一致。
     *
     * tombstone 的 id **不追加**（否则用户删掉的歌会在翻页时复活）。
     *
     * **没有新增时返回同一个实例**：调用方用引用相等判断「要不要落盘」，
     * 而每一页都无条件造一个新列表 = 每次翻页都触发一次 prefs 全量序列化。
     */
    fun appendRemote(local: List<SavedSongEntry>, songs: List<SongItem>, now: Long): List<SavedSongEntry> {
        if (songs.isEmpty()) return local
        val known = local.mapTo(HashSet(local.size * 2 + songs.size)) { it.trackKey }
        var out: ArrayList<SavedSongEntry>? = null
        for (s in songs) {
            val key = TrackKey.ofSong(s)
            if (!known.add(key)) continue
            if (out == null) {
                out = ArrayList(local.size + songs.size)
                out.addAll(local)
            }
            out.add(SavedSongEntry(key, SavedSongOrigin.REMOTE, now, false, s))
        }
        return out ?: local
    }

    // ------------------------------------------------------------------ 闸门 ----

    /**
     * 这个 id 能不能发给 ncm 的**写接口**（`/api/radio/like`、`/api/album/sub`）。
     *
     * ## 为什么需要它（不是一个假想的风险）
     *
     * qm 的 id 由 [SourceIds.qqId] 合成（`bit62` 恒置位，是**正数**），
     * 所以「`id > 0`」这条既有的卫语句在数学上不可能拦住它 —— 与 v2.5.5 修掉的
     * `PlayReporter` 跨源上报是**同一个形状**（`ReportGate` 的 KDoc 记了完整根因链）。
     *
     * 判据只能是 `bit62`（[SourceIds.isQqId]）。**不许用 id 区间启发式** ——
     * QQ 的裸 songid 与 ncm 的 id 同样是 9~10 位十进制，区间完全重叠。
     *
     * ## 为什么单独一个函数，而不是复用 `ReportGate` 的枚举
     *
     * `ReportGate.Target` 的契约是**双向对称**的（每个合法 id 恰好被一个目标接受），
     * 它的两个取值是两个**上报目标**。收藏（like）是**写操作**，不是上报；
     * 把它塞进那个枚举会破坏「恰好被一个目标接受」这条被单测钉住的语义。
     * 所以判据落在这里，但**与 `ReportGate` 共用同一个底层谓词**（`SourceIds.isQqId`），
     * 不重新实现一遍。
     */
    fun isRemoteLikeEligible(songId: Long): Boolean = songId > 0L && !SourceIds.isQqId(songId)
}
