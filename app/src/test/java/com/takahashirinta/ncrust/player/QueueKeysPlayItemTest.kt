/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.2 · P0：`QueueKeys.planPlayItem`（点歌判定）与 `PlayRequestGate`（代际闸门）单测。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.ui.viewmodel.PlayRequestGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户报告：「**播放一首歌的时候点击其他歌曲不会切换，无论怎么点击都会一直播放原来的歌**」。
 *
 * 这条链路里**唯一一处会静默什么都不做**的地方就是点歌判定本身（旧写法见
 * [QueueKeys.planPlayItem] 的 KDoc）：装配失败 ⇒ 队列原样不动 ⇒ 找不到被点的那首
 * ⇒ `if (idx >= 0)` 静默跳过。所以这里钉住三件事：
 *
 *  1. **点哪首就该播哪首** —— 四种队列情形（含「点当前正在播的那首」）逐一断言
 *     `playIndex` 指向被点的那首，而不是某个硬编码下标；
 *  2. **`playIndex` 永远有效** —— 它必须落在队列的合法下标里。旧写法允许它等于 -1
 *     （= 点了没反应），那是本条用例要永久挡住的形状；
 *  3. **代际判据**（[PlayRequestGate]）—— 快速连点两首时，先发的那次取链必须作废，
 *     后发的那次必须有效。它是「切换」与「切回去了」的分界线。
 *
 * 全部走**生产代码本身**（`QueueKeys.planPlayItem` / `PlayRequestGate`），
 * 不做逐行复刻 —— 复刻会漂移，而这次要守的正是「判定」本身。
 */
class QueueKeysPlayItemTest {

    private fun netease(id: Long, name: String = "NE$id") = SongItem(
        id = id, name = name, artists = null, album = null, duration = 60_000L,
        source = MusicSource.NETEASE.key, sourceId = null, mediaId = null,
    )

    private fun qq(rawId: Long, mid: String = "0039MnYb0qxYhV") = SongItem(
        id = SourceIds.qqId(rawId, mid), name = "QQ$rawId", artists = null, album = null,
        duration = 60_000L, source = MusicSource.QQMUSIC.key, sourceId = mid, mediaId = null,
    )

    private fun assertPlanValid(queue: List<SongItem>, plan: QueueKeys.PlayItemPlan) {
        assertTrue("playIndex 必须落在队列内：${plan.playIndex} / size=${queue.size}", plan.playIndex >= 0)
        assertTrue("playIndex 越界：${plan.playIndex} / size=${queue.size}", plan.playIndex < queue.size)
        assertTrue("currentIndex 越界：${plan.currentIndex}", plan.currentIndex in queue.indices)
        assertEquals(
            "currentQueueIndex 必须指向被点的那首（不变量）",
            QueueKeys.keyOf(queue[plan.playIndex]),
            QueueKeys.keyOf(queue[plan.currentIndex]),
        )
    }

    @Test
    fun `队列为空时点歌 - 该曲成为唯一一首并从 0 起播`() {
        val a = netease(1L, "A")
        val plan = QueueKeys.planPlayItem(emptyList(), -1, a)
        assertEquals(listOf(1L), plan.queue.map { it.id })
        assertEquals(0, plan.playIndex)
        assertEquals(0, plan.currentIndex)
        assertTrue(plan.queueChanged)
        assertFalse(plan.fallbackUsed)
        assertPlanValid(plan.queue, plan)
    }

    @Test
    fun `队列只有一个 A 时点 B - 播的是 B`() {
        val a = netease(1L, "A")
        val b = netease(2L, "B")
        val plan = QueueKeys.planPlayItem(listOf(a), 0, b)
        assertEquals(listOf(1L, 2L), plan.queue.map { it.id })
        assertEquals("必须播 B", QueueKeys.keyOf(b), QueueKeys.keyOf(plan.queue[plan.playIndex]))
        assertTrue(plan.queueChanged)
        assertFalse(plan.fallbackUsed)
        assertPlanValid(plan.queue, plan)
    }

    @Test
    fun `队列 A B 当前播 A 时点 B - 播的是 B 且队列不重复`() {
        val a = netease(1L, "A")
        val b = netease(2L, "B")
        val plan = QueueKeys.planPlayItem(listOf(a, b), 0, b)
        assertEquals("B 必须只有一份", listOf(1L, 2L), plan.queue.map { it.id })
        assertEquals(QueueKeys.keyOf(b), QueueKeys.keyOf(plan.queue[plan.playIndex]))
        assertFalse("队列里出现重复身份", QueueKeys.hasDuplicates(QueueKeys.keysOf(plan.queue)))
        assertPlanValid(plan.queue, plan)
    }

    @Test
    fun `点当前正在播的那首 - 队列不动但仍要回到它自己`() {
        val a = netease(1L, "A")
        val b = netease(2L, "B")
        val c = netease(3L, "C")
        val plan = QueueKeys.planPlayItem(listOf(a, b, c), 1, b)
        assertEquals("点当前歌不许重排队列", listOf(1L, 2L, 3L), plan.queue.map { it.id })
        assertEquals("必须回到 B 自己", QueueKeys.keyOf(b), QueueKeys.keyOf(plan.queue[plan.playIndex]))
        assertFalse("点当前歌不该触发 saveQueue", plan.queueChanged)
        assertFalse(plan.fallbackUsed)
        assertPlanValid(plan.queue, plan)
    }

    @Test
    fun `点当前正在播的那首 - 队列里存在重复身份时也回到当前那一份`() {
        // 队列快照可能来自旧版本（曾经允许重复入队）。此时 indexOfFirst 会命中**第一份**，
        // 而 currentQueueIndex 指着第二份 —— 判定必须只认 currentIndex，不能把游标搬走。
        val a = netease(1L, "A")
        val b = netease(2L, "B")
        val plan = QueueKeys.planPlayItem(listOf(a, b, b), 2, b)
        assertEquals("游标必须留在原来那一份（index 2）", 2, plan.currentIndex)
        assertEquals(2, plan.playIndex)
        assertFalse(plan.queueChanged)
        assertPlanValid(plan.queue, plan)
    }

    @Test
    fun `游标越界 - 当成空队列处理, 不能拿不存在的 currentKey 去重排队列`() {
        val a = netease(1L, "A")
        val b = netease(2L, "B")
        // 队列非空但游标失效（自动接续后队列被删空、恢复状态缺字段等都会形成这个形状）
        val plan = QueueKeys.planPlayItem(listOf(a, b), 5, b)
        assertEquals("越界时该曲作为唯一一首，而不是去改一条定位不了的队列", listOf(2L), plan.queue.map { it.id })
        assertEquals(0, plan.playIndex)
        assertPlanValid(plan.queue, plan)
    }

    @Test
    fun `点队列里靠后的那首 - 插到当前歌之后而不是排到队尾`() {
        val a = netease(1L, "A")
        val b = netease(2L, "B")
        val c = netease(3L, "C")
        val plan = QueueKeys.planPlayItem(listOf(a, b, c), 0, c)
        assertEquals("C 要挪到 A 之后", listOf(1L, 3L, 2L), plan.queue.map { it.id })
        assertEquals(QueueKeys.keyOf(c), QueueKeys.keyOf(plan.queue[plan.playIndex]))
        assertFalse(QueueKeys.hasDuplicates(QueueKeys.keysOf(plan.queue)))
        assertPlanValid(plan.queue, plan)
    }

    @Test
    fun `点队列里靠前的那首 - 游标跟着被点的那首走`() {
        val a = netease(1L, "A")
        val b = netease(2L, "B")
        val c = netease(3L, "C")
        val plan = QueueKeys.planPlayItem(listOf(a, b, c), 2, a)
        assertEquals(QueueKeys.keyOf(a), QueueKeys.keyOf(plan.queue[plan.playIndex]))
        assertEquals("currentIndex 必须等于 playIndex（不变量）", plan.playIndex, plan.currentIndex)
        assertFalse(QueueKeys.hasDuplicates(QueueKeys.keysOf(plan.queue)))
        assertPlanValid(plan.queue, plan)
    }

    @Test
    fun `跨源同号 - 播的是被点的那一首, 不是同号的另一源`() {
        // ncm 123 与 QQ 123 是不同的歌（QQ 合成 id 带 bit62，这里直接用同号构造极端情形）。
        val ne = netease(123L, "NE")
        val qqSong = SongItem(
            id = 123L, name = "QQ", artists = null, album = null, duration = 60_000L,
            source = MusicSource.QQMUSIC.key, sourceId = "0039MnYb0qxYhV", mediaId = null,
        )
        val plan = QueueKeys.planPlayItem(listOf(ne), 0, qqSong)
        assertEquals("跨源同号必须两首都在队列里", 2, plan.queue.size)
        assertEquals(TrackKey(MusicSource.QQMUSIC, 123L), QueueKeys.keyOf(plan.queue[plan.playIndex]))
        assertPlanValid(plan.queue, plan)
    }

    @Test
    fun `队列里全是 QQ 曲目 - 连续点歌每一首都能播出自己的下标`() {
        val q1 = qq(1L, "mid1")
        val q2 = qq(2L, "mid2")
        val q3 = qq(3L, "mid3")
        var queue = listOf(q1)
        var current = 0
        for (clicked in listOf(q2, q3, q1, q2)) {
            val plan = QueueKeys.planPlayItem(queue, current, clicked)
            assertPlanValid(plan.queue, plan)
            assertEquals(
                "点 ${QueueKeys.keyOf(clicked).tag} 必须播它自己",
                QueueKeys.keyOf(clicked),
                QueueKeys.keyOf(plan.queue[plan.playIndex]),
            )
            assertFalse("点歌之后队列不许出现重复", QueueKeys.hasDuplicates(QueueKeys.keysOf(plan.queue)))
            queue = plan.queue
            current = plan.currentIndex
        }
    }

    @Test
    fun `快速连点两首 - 每一次判定都自洽(后一次在前一次的结果上继续)`() {
        val a = netease(1L, "A")
        val b = netease(2L, "B")
        val c = netease(3L, "C")
        // 用户连点：A（起播）→ B（打断）→ C（再打断）。每一步都必须指向当时被点的那首，
        // 且 currentQueueIndex 与队列不变量始终成立。
        val p1 = QueueKeys.planPlayItem(emptyList(), -1, a)
        assertPlanValid(p1.queue, p1)
        val p2 = QueueKeys.planPlayItem(p1.queue, p1.currentIndex, b)
        assertEquals(QueueKeys.keyOf(b), QueueKeys.keyOf(p2.queue[p2.playIndex]))
        assertPlanValid(p2.queue, p2)
        val p3 = QueueKeys.planPlayItem(p2.queue, p2.currentIndex, c)
        assertEquals(QueueKeys.keyOf(c), QueueKeys.keyOf(p3.queue[p3.playIndex]))
        assertPlanValid(p3.queue, p3)
        // 连点的结果：每次点歌都「插到当前歌之后」，而当前歌正好跟着点歌往前走，
        // 所以队列就是点击顺序 [A, B, C] —— 这正是「点哪首就播哪首」的直观形状。
        assertEquals(listOf(1L, 2L, 3L), p3.queue.map { it.id })
    }

    // ───────────────────────── 代际闸门（PlayerViewModel 的取链守卫） ─────────────────────────

    @Test
    fun `代际闸门 - 后一次请求作废前一次`() {
        val gate = PlayRequestGate()
        val first = gate.next()
        assertTrue("刚发出的请求必须有效", gate.isCurrent(first))
        val second = gate.next()
        assertNotEquals(first, second)
        assertFalse("快速连点：先发的那次取链结果必须作废，否则会把已切走的歌再放一次", gate.isCurrent(first))
        assertTrue("后发的那次必须有效（它就是用户最后点的那首）", gate.isCurrent(second))
    }

    @Test
    fun `代际闸门 - 预载普通入队不推进代际(否则会把点歌的取链吃掉)`() {
        val gate = PlayRequestGate()
        val play = gate.next()
        // 预载的「普通入队」路径不调用 next()，代际必须原地不动 ——
        // 这正是「预载把点歌吃掉」那条缺陷形状的反面判据。
        assertEquals(play, gate.current)
        assertTrue(gate.isCurrent(play))
    }

    @Test
    fun `代际闸门 - 预载接管推进代际并作废并发取链`() {
        val gate = PlayRequestGate()
        val play = gate.next()
        val takeover = gate.next()          // 接管也是一次开播动作
        assertFalse(gate.isCurrent(play))
        assertTrue(gate.isCurrent(takeover))
        assertEquals(takeover, gate.current)
    }

    @Test
    fun `代际闸门 - 单调递增, 同一代际可被多次判定`() {
        val gate = PlayRequestGate()
        val seen = (1..50).map { gate.next() }
        assertEquals(seen.sorted(), seen)
        assertEquals(50, gate.current)
        assertTrue(gate.isCurrent(50))
        assertFalse(gate.isCurrent(49))
    }
}
