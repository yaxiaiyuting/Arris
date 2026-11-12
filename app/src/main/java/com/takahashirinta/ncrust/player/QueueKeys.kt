/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
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

    /** 点歌后的队列与播放下标，游标始终指向被点的条目。 */
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
     * 点其他歌时把它移到当前曲之后并立即播放。空队列或无效游标从新歌开始。
     * 旧快照可能有重复身份。插入位置取当前身份的首个位置，载荷取同身份的末条。
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
        val currentKey = queue.getOrNull(currentIndex)?.let(::keyOf)
        if (songKey == currentKey) {
            return PlayItemPlan(queue, currentIndex, currentIndex, queueChanged = false, fallbackUsed = false)
        }

        val songsByKey = HashMap<TrackKey, SongItem>(queue.size * 2)
        val remaining = ArrayList<SongItem>(queue.size + 1)
        var selected = song
        var insertPosition = if (currentKey == null) 0 else -1
        var hasDuplicates = false
        for (item in queue) {
            val key = keyOf(item)
            if (key == songKey) {
                selected = item
                continue
            }
            if (insertPosition < 0 && key == currentKey) insertPosition = remaining.size + 1
            if (songsByKey.put(key, item) != null) hasDuplicates = true
            remaining.add(item)
        }
        if (insertPosition < 0) {
            val appended = queue + song
            return PlayItemPlan(appended, appended.lastIndex, appended.lastIndex, queueChanged = true, fallbackUsed = true)
        }
        if (hasDuplicates) {
            for (index in remaining.indices) remaining[index] = songsByKey.getValue(keyOf(remaining[index]))
        }
        remaining.add(insertPosition, selected)
        return PlayItemPlan(remaining, insertPosition, insertPosition, queueChanged = true, fallbackUsed = false)
    }

    /** 整条队列的身份序列。 */
    fun keysOf(queue: List<SongItem>): List<TrackKey> = queue.map { keyOf(it) }

    internal fun keySetOf(queue: List<SongItem>): Set<TrackKey> =
        queue.mapTo(HashSet(queue.size * 2), ::keyOf)

    internal fun songsOutside(songs: List<SongItem>, existing: Set<TrackKey>): List<SongItem> =
        songs.filterNot { keyOf(it) in existing }

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
