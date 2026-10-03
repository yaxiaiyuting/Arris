/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.3 · P1：队列身份从裸 `song.id` 收敛到 `TrackKey`（纯逻辑，JVM 可单测）。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.source.trackKeyOf

/**
 * v2.5.3 · P1：**队列身份运算的唯一落点**。
 *
 * ## 为什么要有这个对象
 *
 * v2.5.2 及以前，队列的四处写入（`insertNext` / `appendToQueue` / `insertAllNext` /
 * `appendAllToQueue`）加 INFINITY 续播、加 `playSongItem`，**全部**用裸 `song.id` 判重：
 *
 * ```kotlin
 * val filtered = playbackQueue.filter { it.id != song.id }        // ← 身份 = 一个 Long
 * val existingIds = playbackQueue.map { it.id }.toSet()
 * ```
 *
 * 探针（`docs/verification/v2.5.3/probe-queue-dedup.md`）数出这样的落点**共 14 处**，
 * 全在 `MainActivity.kt` 里，全部是内联表达式 —— 于是它们既**不可单测**，
 * 又各自是一份会漂移的口径。
 *
 * 更糟的是**同一个应用里同时存在两套身份语义**：
 *  · 队列判重：裸 `Long` id；
 *  · 待播槽位（[PreloadSlot]）、歌词闸门（`LyricLoadCoordinator`）、
 *    续播恢复（`PlaybackStateManager`）：`TrackKey`。
 *
 * 本对象把前者也换成 `TrackKey`，并承接那些内联表达式，使它们**可被 JVM 单测**。
 *
 * ## 探针结论：跨源 `song.id` 撞号的实际发生率是 **0**，那为什么还要改
 *
 * 这不是一次 bug 修复，是**把结构事实变成类型事实**。实测：
 *  · 全部 QQ 曲目的 id 都由 `SourceIds.qqId()` 产出，bit62 恒置位
 *    （`1L shl 62` ≈ 4.6e18），而 ncm songId 是 1e6~3e9 量级 ——
 *    两段区间在 64 位整数上**不可能相交**，与抽样无关；
 *  · 抽样 1000 首双源配对，裸 id 跨源冲突 **0** 次、同源重复 **0** 次；
 *  · 队列快照落盘的是整个 `SongItem`（带 `source`/`mid`/`media_id`），
 *    **没有需要迁移的旧 key 形状**。
 *
 * 也就是说：**今天是 0，但那个 0 靠的是「每个生产者都记得走 qqId」这条纪律**，
 * 而纪律是会破的（v2.1.0 之前就破过一版）。改成 `TrackKey` 之后，
 * 「跨源同号被判成同一首」从「依赖调用点自觉」变成**类型上要显式构造才可能**，
 * 且队列的四个写入路径共用同一个判据 —— 这是本版真正的收益。
 *
 * ## 边界判据（与任务书 §4.2 逐条对应）
 *
 * | 情形 | 结果 |
 * |---|---|
 * | 同源同 id | **去重**（[dedupe] / [without] 会把它拿掉） |
 * | 跨源同 id | **不去重**（`TrackKey` 相等性含 `source`，见 [TrackKey.equals]） |
 * | 重复调用同一操作 | **幂等**（去重后再查，结果一致） |
 *
 * ## 与 [QueueInsert] 的分工
 *
 * [QueueInsert] 管**位置**（插到哪、下标怎么搬、乱序怎么修）；
 * 本对象管**身份**（是不是同一首、当前歌在哪）。
 * 后者是前者的输入 —— `QueueInsert.plan` 的第一个参数就是本对象的输出。
 */
object QueueKeys {

    /**
     * v3.3.2 · P0：**「从列表里点一首歌」的完整判定结果**（纯值，JVM 可单测）。
     *
     * @property queue 这次点击之后的队列（与入参同一份实例 = 队列没动）。
     * @property currentIndex 新的 `currentQueueIndex`。不变量：它一定指着 [queue] 里的
     *   **被点的那首**（[playIndex]），不存在「游标停在别的歌上」的中间态。
     * @property playIndex 调用方应当交给 `playFromQueue` 的下标。**恒 `>= 0`** ——
     *   这是本类型存在的理由之一：旧写法在定位失败时会静默什么都不做（见 [planPlayItem]）。
     * @property queueChanged 队列是否真的变了。`false` = 点的是当前正在播的那首，
     *   调用方**不要**再写一次 `saveQueue`（与旧行为逐字一致）。
     * @property fallbackUsed 是否走了「定位失败」的兜底（追加到队尾并播它）。
     *   正常路径恒 `false`；为 `true` 说明命中了一条以前会**静默丢弃**这次点击的分支 ——
     *   调用方应当打日志：它是「点了没反应」类报告的诊断锚点。
     */
    data class PlayItemPlan(
        val queue: List<SongItem>,
        val currentIndex: Int,
        val playIndex: Int,
        val queueChanged: Boolean,
        val fallbackUsed: Boolean,
    )

    /** 队列条目的身份。**这是队列代码里唯一允许的「取身份」写法。** */
    fun keyOf(song: SongItem): TrackKey = song.trackKeyOf()

    /**
     * v3.3.2 · P0：**「点某一首歌」的唯一决策函数**（`MainActivity.playSongItem` 消费它）。
     *
     * ## 为什么把它抽出来
     *
     * 用户报告「播放一首歌的时候点击其他歌曲不会切换，无论怎么点击都会一直播放原来的歌」。
     * 那条链路里**唯一一处会静默什么都不做**的地方，就是旧写法的最后两行：
     *
     * ```kotlin
     * playbackQueue = QueueKeys.rebuild(...) ?: playbackQueue   // ← 装配失败 ⇒ 队列原样不动
     * val idx = playbackQueue.indexOfFirst { keyOf(it) == songKey }
     * if (idx >= 0) playFromQueue(idx)                          // ← idx < 0 ⇒ 静默 return
     * ```
     *
     * 也就是说：只要 [rebuild] 交不出新队列（返回 null），这次点击就**一个字节都不会发生** ——
     * 没有日志、没有提示、没有 `playSong`，用户耳朵里只有上一首还在响。这条分支原先写在
     * composable 作用域的 `MainActivity` 里，既不可单测、也没有任何判定守着。
     *
     * 抽成本函数之后：① 判定是纯函数（`QueueKeysPlayItemTest` 钉住）；
     * ② [rebuild] 失败**不再是静默丢弃**，而是「追加到队尾并播它」—— 点哪首就播哪首；
     * 队列顺序退化成「这首歌排在最后」，远好于「点了没反应」。
     *
     * ## 语义（与旧实现逐条对应，除上面那条兜底）
     *
     * | 情形 | queue | currentIndex | playIndex |
     * |---|---|---|---|
     * | 队列空 / 游标越界 | 只含被点那首 | 0 | 0 |
     * | 被点那首不在队列里 | 去重后插到当前歌**之后** | 被点那首 | 被点那首 |
     * | 被点那首已在队列（含就是当前歌） | 去重后插到当前歌**之后** | 被点那首 | 被点那首 |
     * | 被点那首**就是**当前歌 | **原样不动** | 不变 | 它的下标 |
     * | [rebuild] 失败（兜底） | 队尾追加被点那首 | 队尾 | 队尾 |
     *
     * 「插到当前歌之后」是它与 `appendToQueue`（排到队尾）**不同**的地方，
     * 也是它取代 insertNext 的语义：点歌 = 立刻打断当前播放。
     *
     * 游标越界那一条特别重要：它意味着「UI 认为有当前歌，但队列里找不到」——
     * 此时**不能**去重、不能插队，否则会拿一个不存在的 currentKey 去定位，
     * 整条队列都可能被改错。
     */
    fun planPlayItem(queue: List<SongItem>, currentIndex: Int, song: SongItem): PlayItemPlan {
        val songKey = keyOf(song)
        if (queue.isEmpty() || currentIndex !in queue.indices) {
            return PlayItemPlan(
                queue = listOf(song),
                currentIndex = 0,
                playIndex = 0,
                queueChanged = true,
                fallbackUsed = false,
            )
        }
        val currentKey = queue.getOrNull(currentIndex)?.let { keyOf(it) }
        // 点的是当前正在播的那首：队列**一个字节都不动**（旧行为：不 saveQueue、不重排）。
        // 这正是「用户在队列面板点当前歌」的场景 —— 它必须能出声音，而不是被当成无操作。
        if (songKey == currentKey) {
            val idx = queue.indexOfFirst { keyOf(it) == songKey }.takeIf { it >= 0 } ?: currentIndex
            return PlayItemPlan(queue, idx, idx, queueChanged = false, fallbackUsed = false)
        }

        val filtered = dedupe(keysOf(queue), songKey).toMutableList()
        val newCurrentIndex = indexOfCurrent(filtered, currentKey).coerceAtLeast(0)
            .let { if (currentKey == null) -1 else it }
        val insertPos = (newCurrentIndex + 1).coerceIn(0, filtered.size)
        filtered.add(insertPos, songKey)
        val rebuilt = rebuild(songs = queue, keys = filtered, extra = listOf(song))
        // ── 兜底：装配不出来也**绝不静默丢弃**这次点击 ────────────────────────────────
        // 正常输入下 rebuild 不可能失败（keys 全部来自 queue ∪ {songKey}）；真失败说明
        // 队列里出现了装配不出的身份。那种时候「点了没反应」是最坏的表现形式，
        // 因为它把一次数据异常伪装成「按钮坏了」。
        val idx = rebuilt?.indexOfFirst { keyOf(it) == songKey } ?: -1
        if (rebuilt == null || idx < 0) {
            val appended = queue + song
            return PlayItemPlan(
                queue = appended,
                currentIndex = appended.lastIndex,
                playIndex = appended.lastIndex,
                queueChanged = true,
                fallbackUsed = true,
            )
        }
        return PlayItemPlan(rebuilt, idx, idx, queueChanged = true, fallbackUsed = false)
    }

    /** 整条队列的身份序列。 */
    fun keysOf(queue: List<SongItem>): List<TrackKey> = queue.map { keyOf(it) }

    /**
     * 队列里**当前歌**的下标；找不到返回 -1。
     *
     * `null`（还没有当前歌）一律返回 -1 —— 不要回落 0，那会让「队列非空但没有当前项」
     * 这种状态被悄悄当成「当前是第一首」。
     */
    fun indexOfCurrent(keys: List<TrackKey>, current: TrackKey?): Int =
        if (current == null) -1 else keys.indexOf(current)

    /**
     * 去掉队列里**所有**等于 [key] 的条目。
     *
     * 不去重的后果不是「多一首」这么轻：v1.5.2 用户报告的
     * 「UI/歌词/媒体卡片显示下一首、耳朵还是上一首」串台，
     * 根因链第一步就是 ExoPlayer 播放列表里出现了 `[当前, 下一首, 下一首']`。
     */
    fun dedupe(keys: List<TrackKey>, key: TrackKey): List<TrackKey> =
        keys.filter { it != key }

    /** 去掉队列里所有落在 [exclude] 里的条目（批量路径用）。 */
    fun without(keys: List<TrackKey>, exclude: Set<TrackKey>): List<TrackKey> =
        keys.filter { it !in exclude }

    /**
     * 批量插入时**剔除当前歌**。
     *
     * 「把当前歌塞到下一首」会让它在 [dedupe] 里被删掉、`currentQueueIndex`
     * 指向完全不同的条目 —— 这是必须显式挡住的边界。
     */
    fun excludeCurrent(keys: List<TrackKey>, current: TrackKey?): List<TrackKey> =
        if (current == null) keys else keys.filter { it != current }

    /**
     * `candidates` 里**还没有**出现在 `existing` 中的那些。
     *
     * INFINITY 续播与「批量追加」都靠它。返回的是 `TrackKey`；
     * 调用方用 [filterSongs] 或自己按下标映射回 `SongItem`。
     */
    fun missing(existing: Collection<TrackKey>, candidates: Collection<TrackKey>): List<TrackKey> {
        val seen = existing.toHashSet()
        return candidates.filter { it !in seen }
    }

    /** 按身份序列把 [songs] 筛出来（保序、去重后仍保持原相对顺序）。 */
    fun selectSongs(songs: List<SongItem>, keys: Collection<TrackKey>): List<SongItem> {
        val want = keys.toHashSet()
        return songs.filter { keyOf(it) in want }
    }

    /**
     * 按身份把 `SongItem` 重新装配成 [keys] 指定的顺序。
     *
     * 装配不出来（[keys] 里出现了 [songs] 中不存在的身份，且不在 [extra] 里）时
     * 返回 **null** —— 调用方应当**放弃这次变更**而不是交出一条缺项的队列：
     * 队列缺项意味着某首歌再也播不到，那比「这次没插进去」严重得多。
     *
     * @param extra 允许补齐的额外条目（通常是这次要插入的那一首）
     */
    fun rebuild(
        songs: List<SongItem>,
        keys: List<TrackKey>,
        extra: List<SongItem> = emptyList(),
    ): List<SongItem>? {
        val byKey = HashMap<TrackKey, SongItem>(songs.size * 2)
        songs.forEach { byKey[keyOf(it)] = it }
        extra.forEach { byKey.putIfAbsent(keyOf(it), it) }
        val out = ArrayList<SongItem>(keys.size)
        for (k in keys) out.add(byKey[k] ?: return null)
        return out
    }

    /**
     * 队列里出现了**同一个身份两次**吗。
     *
     * 这条事实不变量（「队列里同一首歌最多一份」）被队列面板、保存为歌单、
     * INFINITY 的 `existingIds` 过滤共同默认成立。留一个显式的判定，
     * 让它在测试里可断言，而不是只能靠读代码相信。
     */
    fun hasDuplicates(keys: List<TrackKey>): Boolean = keys.size != keys.toHashSet().size

    /** 身份序列里重复出现的那些（诊断用）。 */
    fun duplicates(keys: List<TrackKey>): Set<TrackKey> {
        val seen = HashSet<TrackKey>(keys.size * 2)
        val dup = LinkedHashSet<TrackKey>()
        keys.forEach { if (!seen.add(it)) dup.add(it) }
        return dup
    }

    /**
     * 诊断用的一行摘要：`netease:1, qqmusic:2, …`。
     *
     * 队列身份出问题时最能救命的一条日志 —— 裸 id 的日志里，
     * `123` 到底来自哪个音源是**看不出来**的。
     */
    fun describe(keys: List<TrackKey>, limit: Int = 20): String {
        val head = keys.take(limit).joinToString(", ") { it.tag }
        return if (keys.size <= limit) head else "$head … (+${keys.size - limit})"
    }

    /** 由 `(source, id)` 直接造身份；给「只知道 id」的路径（车机 browse tree 等）用。 */
    fun keyOf(source: MusicSource, id: Long): TrackKey = TrackKey(source, id)
}
