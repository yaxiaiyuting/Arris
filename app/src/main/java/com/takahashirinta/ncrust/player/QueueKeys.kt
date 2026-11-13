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
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.source.trackKeyOf

/** 队列的去重与重排统一按 [TrackKey] 判定，允许不同音源使用相同数字 ID。 */
object QueueKeys {

    /** 点歌后的队列与播放下标，游标始终指向被点的条目。 */
    data class PlayItemPlan(
        val queue: List<SongItem>,
        val currentIndex: Int,
        val playIndex: Int,
        val queueChanged: Boolean,
        val fallbackUsed: Boolean,
    )

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

    /** 当前曲目为空或已移除时返回 -1，由调用方选择游标。 */
    fun indexOfCurrent(keys: List<TrackKey>, current: TrackKey?): Int =
        if (current == null) -1 else keys.indexOf(current)

    /** 移除该身份的所有条目。 */
    fun dedupe(keys: List<TrackKey>, key: TrackKey): List<TrackKey> =
        keys.filter { it != key }

    /** 去掉队列里所有落在 [exclude] 里的条目（批量路径用）。 */
    fun without(keys: List<TrackKey>, exclude: Set<TrackKey>): List<TrackKey> =
        keys.filter { it !in exclude }

    /** 批量插入时保留当前曲目在原队列的位置。 */
    fun excludeCurrent(keys: List<TrackKey>, current: TrackKey?): List<TrackKey> =
        if (current == null) keys else keys.filter { it != current }

    /** 按 [keys] 重排，优先沿用原曲目。缺少任一身份时返回 null。 */
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
}
