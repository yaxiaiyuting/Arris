/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import androidx.media3.common.Player
import com.takahashirinta.ncrust.QueueModes
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.TrackKey

/**
 * v3.4.12 · 播放队列的**索引决策层**（移植自上游 98d1fc5，按 Arris 的身份/轮次语义重写）。
 *
 * 上游把「删除后重定位」「下一首是谁」「播完了要不要先 seekTo(0)+prepare」收敛到这里。
 * 本 fork 不能整文件照搬，原因有两个：
 *
 *  1. 身份不是裸 `Long`，而是 [TrackKey]（音源 + id）。跨源同号是两首歌，按 `id` 回查
 *     会把「QQ 123」认成「ncm 123」。
 *  2. 乱序不是「数组末尾回绕到 0」。本 fork 的 `ShuffleRound` 把当前曲钉在轮次下标 0，
 *     一轮播完要**开新一轮并跳过 0**。上游 `nextIndex` 在轮末直接回绕到 0，会退化成
 *     「无缝重播当前曲」/「随机重播刚播完的歌」——所以本文件的 SHUFFLE 分支在轮末返回
 *     `-1`，把「开下一轮」留给持有 `pendingRound` 的调用方（MainActivity），绝不在这里
 *     自作主张地回绕。
 *
 * 纯逻辑、不依赖 Android，JVM 单测见 `PlaybackQueueLogicTest`。
 */
object PlaybackQueueLogic {

    /** 队列 + 当前索引的一次快照。纯值类型，便于整块回写。 */
    data class QueueState(val queue: List<SongItem>, val currentIndex: Int)

    /** 按 [TrackKey] 回查队列下标；找不到返回 -1。 */
    fun indexOfKey(queue: List<SongItem>, key: TrackKey?): Int {
        if (key == null) return -1
        return queue.indexOfFirst { QueueKeys.keyOf(it) == key }
    }

    /**
     * 把播放器回报的**实际起播身份**映射回应用队列索引。
     *
     * 映射不到时退回 [fallbackIndex] 并夹到合法范围。调用方必须先判断「这次过渡是否
     * 允许退回模式推断」：非自然衔接（系统媒体控件 / 车机插入队列外歌曲）时，拿模式
     * 推断的下一首兜底等于凭空推进一格，宁可不推进。
     */
    fun resolveCurrentIndex(
        queue: List<SongItem>,
        actualKey: TrackKey?,
        fallbackIndex: Int,
    ): Int {
        if (queue.isEmpty()) return -1
        val mapped = indexOfKey(queue, actualKey)
        if (mapped >= 0) return mapped
        return fallbackIndex.coerceIn(0, queue.lastIndex)
    }

    /**
     * 实际索引变化后，把乱序游标拉回 `shuffledIndices[mappedIndex]` 的位置。
     *
     * 旧实现只改 `currentQueueIndex`、不改 `shuffledPosition`，于是「下一首」仍从旧游标
     * 推进 —— 随机模式会跳过或重播。映射不到时保留旧游标（调用方会重新洗牌）。
     */
    fun realignShuffledPosition(
        shuffledIndices: List<Int>,
        mappedIndex: Int,
        fallbackPosition: Int,
    ): Int {
        if (shuffledIndices.isEmpty()) return 0
        val pos = shuffledIndices.indexOf(mappedIndex)
        return if (pos >= 0) pos else fallbackPosition.coerceIn(0, shuffledIndices.lastIndex)
    }

    /**
     * 「当前播放项之后该预载/衔接谁」的唯一决策点。
     *
     * @return 队列下标；`-1` 表示**按当前模式没有下一首**。调用方必须把它当「不预载」
     *   处理，不能当成「预载第一首」。
     *
     * SHUFFLE 的契约与其它模式不同：它只沿**当前这一轮**向前走，轮末返回 `-1`。
     * 轮末的下一首由 `ShuffleRound.newRound(...)` 决定（当前曲钉 0、下一首取 1），
     * 生成后 `pendingRound` 必须同时被「预载」与「过渡」两条路径消费，否则会分叉。
     */
    fun nextIndex(
        queue: List<SongItem>,
        currentIndex: Int,
        mode: Int,
        shuffledIndices: List<Int> = emptyList(),
        shuffledPosition: Int = 0,
    ): Int {
        if (queue.isEmpty() || currentIndex !in queue.indices) return -1
        return when (mode) {
            QueueModes.SINGLE -> currentIndex
            QueueModes.SHUFFLE -> {
                if (shuffledIndices.isEmpty()) return -1
                val pos = shuffledPosition.coerceIn(0, shuffledIndices.lastIndex)
                if (pos >= shuffledIndices.lastIndex) {
                    // 轮末：**不**回绕到 0（那是正在播的这首歌）。交给 pendingRound。
                    -1
                } else {
                    shuffledIndices[pos + 1].takeIf { it in queue.indices } ?: -1
                }
            }
            // 顺序线性 / 相似无限：队尾之外没有下一首（队尾续播由各自分支处理）。
            QueueModes.LINE, QueueModes.INFINITY ->
                if (currentIndex < queue.lastIndex) currentIndex + 1 else -1
            // CYCLE：队尾回绕队首；单曲队列不预载自身，否则 [A,A] 自动衔接 = 假单曲循环。
            else -> if (queue.size > 1) {
                if (currentIndex < queue.lastIndex) currentIndex + 1 else 0
            } else {
                -1
            }
        }
    }

    /**
     * 「播放/继续」之前是否需要先把播放器从「播完了」拉回来。
     *
     * ExoPlayer 的 `play()` 只把 `playWhenReady` 置 true：media3 1.5.0 的
     * `setPlayWhenReadyInternal` 只在 READY/BUFFERING 时启动渲染器，STATE_ENDED 没有
     * 分支，所以队尾播完后点播放键什么都不会发生（issue #29）。必须先
     * `seekTo(0)+prepare`。STATE_IDLE 同理（取流失败后停在 IDLE 时 prepare 才是重试）。
     */
    fun needsRestartBeforePlay(playbackState: Int, hasCurrentItem: Boolean): Boolean {
        if (!hasCurrentItem) return false
        return playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE
    }

    /**
     * 删除队列中的一项，并按**歌曲身份**重新定位当前索引。
     *
     * 旧实现只做 `currentIndex >= size` 的 clamp：删掉当前歌**之前**的项后索引数值不变，
     * 于是一格一格指向了原当前歌的后一首 —— 不变量
     * `queue[currentIndex] == 正在播的歌` 当场破坏，之后所有「下一首/预载谁」的决策都
     * 从错位处开始算（听感就是「点的歌和实际播的不是同一首」）。
     *
     * 删掉的正是当前歌本身时退化为槽位语义：后一首顶上该槽位，队尾则前移一格。
     * 队列条目按 [QueueKeys] 去重，因此「按身份回查」只会命中唯一一项。
     */
    fun removeAt(
        queue: List<SongItem>,
        currentIndex: Int,
        removeIndex: Int,
        keyOf: (SongItem) -> TrackKey = { QueueKeys.keyOf(it) },
    ): QueueState {
        if (removeIndex !in queue.indices) return QueueState(queue, currentIndex)
        val currentKey = queue.getOrNull(currentIndex)?.let(keyOf)
        val newQueue = queue.toMutableList().also { it.removeAt(removeIndex) }
        if (newQueue.isEmpty()) return QueueState(newQueue, -1)
        if (currentIndex !in queue.indices) return QueueState(newQueue, -1)
        val relocated = if (currentKey != null) {
            newQueue.indexOfFirst { keyOf(it) == currentKey }
        } else {
            -1
        }
        val newIndex = if (relocated >= 0) {
            relocated
        } else {
            // 删掉的是当前歌：后一首顶上；队尾删掉则前移一格。
            minOf(currentIndex, newQueue.lastIndex)
        }
        return QueueState(newQueue, newIndex)
    }
}
