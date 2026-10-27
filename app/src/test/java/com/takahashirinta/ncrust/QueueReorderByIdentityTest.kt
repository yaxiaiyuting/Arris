/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.8.1 · E10（残余）：队列重排必须**按身份**定位，不许盲信拖动开始时的下标。
 */

package com.takahashirinta.ncrust

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.trackKeyOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * E10 残余的回归契约：`reorderByIdentity` 是「拖谁 / 落到谁那儿」的唯一判定点。
 *
 * ## 缺陷本体（为什么下标不够）
 *
 * 提交发生在一次**跨层调用**里（`QueueView` → callbacks → `MainScreen`），而队列是活的：
 * 行内有删除、上层有插播。原队列 `[A,B,C,D,E]` 的拖动参数 `from=2, to=3`（拖 C 到 D 后面），
 * 在「先删掉 B」之后**两个下标都仍然合法**，但 `queue[2]` 已经是 `D`：
 *
 * | 口径 | 移动后 | 结果 |
 * |---|---|---|
 * | 按下标（旧实现） | `[A,C,E,D]` | 把 **D** 挪到了队尾（C 原地没动） |
 * | 按身份（本版） | `[A,D,C,E]` | 把 **C** 挪到 D 后面（用户拖的那一首） |
 *
 * 本文件同时把这两行都钉住：一条断言正确行为，一条断言旧口径的形状 —— 后者是"修的是真问题"
 * 的证据，而不是给现状补测试。
 *
 * ## 边界
 *
 * 身份两端必须都在队列里：任一端找不到（歌曲被删/整队被替换）就**放弃这次移动**（返回 null），
 * 而不是退化成"最接近的下标"。宁可这一次没反应，也不移动相邻的另一首。
 * `PlaybackQueueLogic.removeAt` 的语义（删除后的当前索引修正）一行未动。
 */
class QueueReorderByIdentityTest {

    private fun ncm(id: Long, name: String = "ncm-$id"): SongItem =
        SongItem(
            id = id,
            name = name,
            artists = null,
            album = null,
            duration = null,
            source = null, // ncm 的历史约定：source 字符串写 null
        )

    private fun qq(rawSongId: Long, mid: String): SongItem =
        SongItem(
            id = SourceIds.qqId(rawSongId, mid),
            name = "qq-$rawSongId",
            artists = null,
            album = null,
            duration = null,
            source = MusicSource.QQMUSIC.key,
            sourceId = mid,
            mediaId = mid,
        )

    /** 旧实现的形状（只为对照，不参与生产路径）。 */
    private fun legacyMoveByIndex(queue: List<SongItem>, from: Int, to: Int): List<SongItem> {
        if (from !in queue.indices || to !in queue.indices || from == to) return queue
        return queue.toMutableList().apply {
            val item = removeAt(from)
            add(to, item)
        }
    }

    @Test
    fun `窗口内队列被改过时按身份定位到拖动开始时的那一首`() {
        val a = ncm(1)
        val b = ncm(2)
        val c = ncm(3)
        val d = ncm(4)
        val e = ncm(5)
        val original = listOf(a, b, c, d, e)

        // 拖动开始时：from=2（C）→ to=3（D）
        val fromKey = original[2].trackKeyOf()
        val toKey = original[3].trackKeyOf()

        // 拖动过程中行内删掉了 B（这正是 E10 残余描述的场景）。
        val nowQueue = original.filter { it.id != b.id }
        assertEquals(listOf(a, c, d, e), nowQueue)

        val moved = reorderByIdentity(nowQueue, currentIndex = 0, fromKey = fromKey, toKey = toKey)
            ?: throw AssertionError("两端身份都在队列里，不许放弃")
        assertEquals("拖的是 C：应当落到 D 后面", listOf(a, d, c, e), moved.queue)
        assertEquals("当前播放的是 A（index 0），不应被影响", 0, moved.currentIndex)

        // 证据：同一次拖拽按下标执行会移动另一首（D）—— 这就是本版修掉的东西。
        val legacy = legacyMoveByIndex(nowQueue, 2, 3)
        assertEquals(listOf(a, c, e, d), legacy)
        assertNotEquals("按身份与按下标的结果必须不同（否则测不到问题）", legacy, moved.queue)
    }

    @Test
    fun `当前播放项跟着自己的歌走`() {
        val a = ncm(1)
        val b = ncm(2)
        val c = ncm(3)
        val queue = listOf(a, b, c)
        // 把正在播的 A 挪到队尾：索引必须跟着这首歌走（0 → 2）。
        val moved = reorderByIdentity(queue, currentIndex = 0, a.trackKeyOf(), c.trackKeyOf())
            ?: throw AssertionError("两端都在队列里，不许放弃")
        assertEquals(listOf(b, c, a), moved.queue)
        assertEquals("当前播放项跟随歌曲移动", 2, moved.currentIndex)
    }

    @Test
    fun `跨源同号按身份分开`() {
        // ncm 123 与 QQ 123 是两首歌；QQ 的 id 带 bit62 标志位，`trackKeyOf` 不会把它们混起来。
        val ne = ncm(123)
        val qq = qq(123, "0039MnYb0qxYhV")
        val tail = ncm(456)
        val queue = listOf(ne, qq, tail)
        val moved = reorderByIdentity(queue, currentIndex = 0, qq.trackKeyOf(), ne.trackKeyOf())
            ?: throw AssertionError("两端都在队列里，不许放弃")
        assertEquals("挪的必须是 QQ 那一首，不是同号的 ncm", listOf(qq, ne, tail), moved.queue)
        assertEquals("当前播放的是 ncm 123：它从 0 被挪到 1", 1, moved.currentIndex)
    }

    @Test
    fun `身份不在队列里就放弃`() {
        val a = ncm(1)
        val b = ncm(2)
        val queue = listOf(a, b)
        assertNull(
            "被拖的那首已经不在队列里（被删/整队被替换）⇒ 放弃",
            reorderByIdentity(queue, currentIndex = 0, ncm(999).trackKeyOf(), b.trackKeyOf()),
        )
        assertNull(
            "落点那首已经不在队列里 ⇒ 放弃",
            reorderByIdentity(queue, currentIndex = 0, a.trackKeyOf(), ncm(999).trackKeyOf()),
        )
    }

    @Test
    fun `两端是同一首时放弃`() {
        val a = ncm(1)
        val b = ncm(2)
        assertNull(
            "from == to 没有可移动的方向",
            reorderByIdentity(listOf(a, b), currentIndex = 0, a.trackKeyOf(), a.trackKeyOf()),
        )
    }

    @Test
    fun `空队列不崩`() {
        assertNull(
            reorderByIdentity(emptyList(), currentIndex = 0, ncm(1).trackKeyOf(), ncm(2).trackKeyOf()),
        )
    }
}
