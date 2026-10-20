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
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上游 98d1fc5 的队列决策在 **Arris 语义**下的回归网。
 *
 * 重点不是复述实现，而是钉住三条不能退化的契约：
 *  1. SHUFFLE 轮末**绝不能**回绕到 0（那是当前曲）——必须交给 `ShuffleRound` 开新一轮；
 *  2. 队列身份是 `TrackKey`（音源 + id），不是裸 id；
 *  3. 删除当前曲之前的项后，当前索引必须按身份回到**同一首歌**上。
 */
class PlaybackQueueLogicTest {

    private fun netease(id: Long, name: String = "N$id") = SongItem(
        id = id, name = name, artists = null, album = null, duration = 60_000L,
        source = MusicSource.NETEASE.key, sourceId = null, mediaId = null,
    )

    private fun qq(id: Long, name: String = "Q$id") = SongItem(
        id = id, name = name, artists = null, album = null, duration = 60_000L,
        source = MusicSource.QQMUSIC.key, sourceId = "mid$id", mediaId = "media$id",
    )

    private val a = netease(1, "A")
    private val b = netease(2, "B")
    private val c = netease(3, "C")
    private val queue = listOf(a, b, c)

    // ---------- 1. nextIndex：三种模式 + 乱序轮末 ----------

    @Test
    fun `CYCLE 队尾回绕队首 - 单曲队列不回绕自身`() {
        assertEquals(1, PlaybackQueueLogic.nextIndex(queue, 0, QueueModes.CYCLE))
        assertEquals(2, PlaybackQueueLogic.nextIndex(queue, 1, QueueModes.CYCLE))
        assertEquals(0, PlaybackQueueLogic.nextIndex(queue, 2, QueueModes.CYCLE))
        assertEquals(-1, PlaybackQueueLogic.nextIndex(listOf(a), 0, QueueModes.CYCLE))
    }

    @Test
    fun `SINGLE 始终预载自己 - LINE 与 INFINITY 队尾返回 -1`() {
        assertEquals(1, PlaybackQueueLogic.nextIndex(queue, 1, QueueModes.SINGLE))
        assertEquals(2, PlaybackQueueLogic.nextIndex(queue, 1, QueueModes.LINE))
        assertEquals(-1, PlaybackQueueLogic.nextIndex(queue, 2, QueueModes.LINE))
        assertEquals(2, PlaybackQueueLogic.nextIndex(queue, 1, QueueModes.INFINITY))
        assertEquals(-1, PlaybackQueueLogic.nextIndex(queue, 2, QueueModes.INFINITY))
    }

    @Test
    fun `SHUFFLE 沿本轮向前 - 轮末返回 -1 而不是回绕到当前曲`() {
        val shuffled = listOf(1, 0, 2)
        assertEquals(0, PlaybackQueueLogic.nextIndex(queue, 1, QueueModes.SHUFFLE, shuffled, 0))
        assertEquals(2, PlaybackQueueLogic.nextIndex(queue, 0, QueueModes.SHUFFLE, shuffled, 1))
        // 关键：轮末是 -1。回绕到 shuffled[0] 就是重播当前曲 / 重播刚播完的歌。
        assertEquals(-1, PlaybackQueueLogic.nextIndex(queue, 2, QueueModes.SHUFFLE, shuffled, 2))
    }

    @Test
    fun `非法当前索引与空队列一律 -1`() {
        assertEquals(-1, PlaybackQueueLogic.nextIndex(emptyList(), 0, QueueModes.CYCLE))
        assertEquals(-1, PlaybackQueueLogic.nextIndex(queue, -1, QueueModes.CYCLE))
        assertEquals(-1, PlaybackQueueLogic.nextIndex(queue, 3, QueueModes.CYCLE))
        assertEquals(
            -1,
            PlaybackQueueLogic.nextIndex(queue, 0, QueueModes.SHUFFLE, emptyList(), 0),
        )
    }

    // ---------- 2. 按身份回查：跨源同号是两首歌 ----------

    @Test
    fun `实际在播身份回查优先于模式推断`() {
        val n1 = netease(1)
        val q1 = qq(1)
        val crossSource = listOf(n1, q1, b)
        assertEquals(
            1,
            PlaybackQueueLogic.resolveCurrentIndex(
                crossSource,
                TrackKey(MusicSource.QQMUSIC, 1),
                fallbackIndex = 0,
            ),
        )
        assertEquals(
            0,
            PlaybackQueueLogic.resolveCurrentIndex(
                crossSource,
                TrackKey(MusicSource.NETEASE, 1),
                fallbackIndex = 2,
            ),
        )
    }

    @Test
    fun `队列里找不到身份时才用模式推断兜底`() {
        assertEquals(
            2,
            PlaybackQueueLogic.resolveCurrentIndex(
                queue,
                TrackKey(MusicSource.QQMUSIC, 99),
                fallbackIndex = 2,
            ),
        )
        // 夹到合法范围，绝不把越界值交出去。
        assertEquals(2, PlaybackQueueLogic.resolveCurrentIndex(queue, null, fallbackIndex = 99))
        assertEquals(0, PlaybackQueueLogic.resolveCurrentIndex(queue, null, fallbackIndex = -3))
        assertEquals(-1, PlaybackQueueLogic.resolveCurrentIndex(emptyList(), null, fallbackIndex = 0))
    }

    @Test
    fun `索引对齐后乱序游标跟着回到同一首歌`() {
        val shuffled = listOf(2, 0, 1)
        assertEquals(1, PlaybackQueueLogic.realignShuffledPosition(shuffled, 0, 0))
        assertEquals(2, PlaybackQueueLogic.realignShuffledPosition(shuffled, 1, 0))
        // 不在本轮（可能属于 pendingRound）：保留旧游标，调用方会重新洗牌。
        assertEquals(1, PlaybackQueueLogic.realignShuffledPosition(shuffled, 99, 1))
        assertEquals(0, PlaybackQueueLogic.realignShuffledPosition(emptyList(), 0, 3))
    }

    // ---------- 3. removeAt：删当前曲之前的项必须重定位 ----------

    @Test
    fun `删除当前曲之前的项 - 当前索引回到同一首歌`() {
        // 当前曲**不是**队尾时才能暴露「只 clamp」的错位：旧实现留下 1（指向 C），
        // 正确结果是 B 的下标 0。选 current=2 的队尾场景会被 clamp 偶然救回来。
        val state = PlaybackQueueLogic.removeAt(queue, currentIndex = 1, removeIndex = 0)
        assertEquals(listOf(b, c), state.queue)
        assertEquals(0, state.currentIndex)
        assertEquals("B", state.queue[state.currentIndex].name)
    }

    @Test
    fun `删除当前曲之后的项 - 当前索引不变`() {
        val state = PlaybackQueueLogic.removeAt(queue, currentIndex = 0, removeIndex = 2)
        assertEquals(listOf(a, b), state.queue)
        assertEquals(0, state.currentIndex)
        assertEquals("A", state.queue[state.currentIndex].name)
    }

    @Test
    fun `删除当前曲本身 - 后一首顶上槽位`() {
        val state = PlaybackQueueLogic.removeAt(queue, currentIndex = 1, removeIndex = 1)
        assertEquals(listOf(a, c), state.queue)
        assertEquals(1, state.currentIndex)
        assertEquals("C", state.queue[state.currentIndex].name)
    }

    @Test
    fun `删除当前曲且它在队尾 - 前移一格`() {
        val state = PlaybackQueueLogic.removeAt(queue, currentIndex = 2, removeIndex = 2)
        assertEquals(listOf(a, b), state.queue)
        assertEquals(1, state.currentIndex)
        assertEquals("B", state.queue[state.currentIndex].name)
    }

    @Test
    fun `删空队列 - 当前索引归 -1`() {
        val state = PlaybackQueueLogic.removeAt(listOf(a), currentIndex = 0, removeIndex = 0)
        assertTrue(state.queue.isEmpty())
        assertEquals(-1, state.currentIndex)
    }

    @Test
    fun `非法删除下标与非法当前下标不改变队列`() {
        val unchanged = PlaybackQueueLogic.removeAt(queue, currentIndex = 1, removeIndex = 9)
        assertEquals(queue, unchanged.queue)
        assertEquals(1, unchanged.currentIndex)

        val invalidCurrent = PlaybackQueueLogic.removeAt(queue, currentIndex = -1, removeIndex = 0)
        assertEquals(listOf(b, c), invalidCurrent.queue)
        assertEquals(-1, invalidCurrent.currentIndex)
    }

    // ---------- 4. 播完了先 seekTo(0)+prepare ----------

    @Test
    fun `ENDED 与 IDLE 在播放前必须重启 - READY 不动`() {
        assertTrue(PlaybackQueueLogic.needsRestartBeforePlay(Player.STATE_ENDED, true))
        assertTrue(PlaybackQueueLogic.needsRestartBeforePlay(Player.STATE_IDLE, true))
        assertFalse(PlaybackQueueLogic.needsRestartBeforePlay(Player.STATE_READY, true))
        assertFalse(PlaybackQueueLogic.needsRestartBeforePlay(Player.STATE_BUFFERING, true))
        // 没有当前项时 seekTo(0) 没有意义，交给上层决定。
        assertFalse(PlaybackQueueLogic.needsRestartBeforePlay(Player.STATE_ENDED, false))
    }
}
