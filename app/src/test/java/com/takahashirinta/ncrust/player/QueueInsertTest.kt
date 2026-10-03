/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.QueueModes
import com.takahashirinta.ncrust.player.QueueInsert.Outcome
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0 · D：「添加到下一首播放」的队列边界（铁律 16）。
 *
 * 任务书 §6.2 点名要求覆盖五条：队列空时添加 / 非空时插到下一首位置 /
 * 当前歌是最后一首时添加 / 随机模式下添加 / 重复添加同一首歌。
 * 本文件把它们逐条落成用例，另外补上「这首歌就是当前歌」「没有有效当前项」
 * 「乱序排列修正的合法性与幂等性」三组。
 *
 * 这一版的两条硬约束（失败即代表功能静默损坏，不是风格问题）：
 *  1. **`playbackQueue[currentQueueIndex]` 必须始终等于正在播的那首歌** ——
 *     去重后必须重新定位下标，不能沿用旧值；
 *  2. 乱序模式下插入的必须是 `shuffledIndices[shuffledPosition + 1]`，
 *     否则用户点了「添加到下一首播放」，下一首仍然是原来那首随机歌。
 */
class QueueInsertTest {

    // ---------- 身份构造（v2.5.3 · P1：队列身份从裸 Long 换成 TrackKey）----------
    //
    // 用例的**语义没有变**，只是把原来的 `1L` 写成 `key(1)`。
    // 这么改是因为 `QueueInsert.plan` 的签名从 `List<Long>` 换成了 `List<TrackKey>` ——
    // 换强类型的**目的**就是让 `plan(queue.map { it.id }, …)` 编译不过，
    // 所以这里不能靠「保留一个接受裸 id 的重载」来省事。

    private fun key(id: Long, source: MusicSource = MusicSource.NETEASE): TrackKey =
        TrackKey(source, id)

    private fun keys(vararg ids: Long): List<TrackKey> = ids.map { key(it) }


    // ---------- 1. 队列为空：任务书 §6.2「队列为空 → 直接播放」 ----------

    @Test
    fun `空队列添加 - 成为唯一一首并立刻起播`() {
        val plan = QueueInsert.plan(emptyList(), currentIndex = -1, newKey = key(7))

        assertEquals(Outcome.START_FRESH, plan.outcome)
        assertEquals(keys(7), plan.keys)
        assertEquals(0, plan.currentIndex)
        assertEquals(0, plan.insertPos)
        // 这是既有点位上的真实缺陷：只改队列不起播，用户会看到"加了但没反应"。
        assertTrue("空队列必须真的起播", plan.shouldStartPlayback)
        assertTrue(plan.queueChanged)
    }

    @Test
    fun `没有有效当前项 - 追加到队尾并起播它 而不是插到队首`() {
        // 队列非空但 currentQueueIndex 失效（理论上不该出现，但它是可能的状态）。
        val plan = QueueInsert.plan(keys(1, 2, 3), currentIndex = -1, newKey = key(9))

        assertEquals(Outcome.START_FRESH, plan.outcome)
        // 追加到队尾 —— 不能插到下标 0，那会让既有顺序看起来被反转。
        assertEquals(keys(1, 2, 3, 9), plan.keys)
        assertEquals(3, plan.currentIndex)
        assertTrue(plan.shouldStartPlayback)
    }

    @Test
    fun `没有有效当前项且越界下标 - 同样按追加处理`() {
        val plan = QueueInsert.plan(keys(1, 2), currentIndex = 99, newKey = key(5))
        assertEquals(keys(1, 2, 5), plan.keys)
        assertEquals(2, plan.currentIndex)
        assertEquals(Outcome.START_FRESH, plan.outcome)
    }

    // ---------- 2. 队列非空：插到当前歌的下一首位置 ----------

    @Test
    fun `非空队列 - 插到当前歌之后 且不改变当前歌`() {
        val plan = QueueInsert.plan(keys(1, 2, 3), currentIndex = 1, newKey = key(99))

        assertEquals(Outcome.INSERTED, plan.outcome)
        assertEquals(keys(1, 2, 99, 3), plan.keys)
        // 关键不变量：当前歌仍然是 2，指针没动。
        assertEquals(1, plan.currentIndex)
        assertEquals(key(2), plan.keys[plan.currentIndex])
        assertEquals(2, plan.insertPos)
        assertFalse("非空队列不能起播 — 只影响下一首", plan.shouldStartPlayback)
        assertTrue(plan.queueChanged)
    }

    @Test
    fun `非空队列 - 当前歌在队首时插入位置是 1`() {
        val plan = QueueInsert.plan(keys(1, 2, 3), currentIndex = 0, newKey = key(99))
        assertEquals(keys(1, 99, 2, 3), plan.keys)
        assertEquals(0, plan.currentIndex)
        assertEquals(1, plan.insertPos)
    }

    // ---------- 3. 当前歌是最后一首（铁律 16 点名） ----------

    @Test
    fun `当前歌是最后一首 - 插到队尾 且仍能成为下一首`() {
        val plan = QueueInsert.plan(keys(1, 2, 3), currentIndex = 2, newKey = key(99))

        assertEquals(Outcome.INSERTED, plan.outcome)
        assertEquals(keys(1, 2, 3, 99), plan.keys)
        assertEquals(2, plan.currentIndex)
        // 插到末尾之后当前歌**不再**是最后一首 ⇒ CYCLE/LINE/INFINITY 的
        // 「队尾没有下一首」判定会自然指向它，不需要写模式特例。
        assertEquals(3, plan.insertPos)
        assertEquals(3, QueueInsert.nextIndexAfterInsert(
            playMode = QueueModes.LINE,
            size = plan.keys.size,
            newCurrentIndex = plan.currentIndex,
            shuffledIndices = emptyList(),
        ))
    }

    @Test
    fun `单曲队列 - 添加第二首后当前歌不再是唯一一首`() {
        val plan = QueueInsert.plan(keys(1), currentIndex = 0, newKey = key(2))
        assertEquals(keys(1, 2), plan.keys)
        assertEquals(1, plan.insertPos)
        assertEquals(1, QueueInsert.nextIndexAfterInsert(
            QueueModes.CYCLE, plan.keys.size, plan.currentIndex, emptyList(),
        ))
    }

    // ---------- 4. 重复添加同一首歌（任务书要求「需确认」→ 已确认为幂等去重） ----------

    @Test
    fun `重复添加 - 已经在下一首位置时幂等 队列一个字节不动`() {
        // 2 已经在 1 的下一首。
        val plan = QueueInsert.plan(keys(1, 2, 3), currentIndex = 0, newKey = key(2))

        assertEquals(Outcome.ALREADY_NEXT, plan.outcome)
        assertEquals(keys(1, 2, 3), plan.keys)
        assertEquals(0, plan.currentIndex)
        assertEquals(1, plan.insertPos)
        assertFalse("幂等路径不应改动队列", plan.queueChanged)
        assertFalse(plan.shouldStartPlayback)
    }

    @Test
    fun `重复添加 - 连点两次的结果与点一次完全相同`() {
        val first = QueueInsert.plan(keys(1, 2, 3), currentIndex = 0, newKey = key(99))
        val second = QueueInsert.plan(first.keys, first.currentIndex, newKey = key(99))

        assertEquals(first.keys, second.keys)
        assertEquals(first.currentIndex, second.currentIndex)
        assertEquals(Outcome.ALREADY_NEXT, second.outcome)
    }

    @Test
    fun `重复添加 - 在队列别处时是搬移 不是新增一份`() {
        // 99 在队尾，当前在第 0 首 ⇒ 应该被搬到下一首，队列长度不变。
        val plan = QueueInsert.plan(keys(1, 2, 99, 3), currentIndex = 0, newKey = key(99))

        assertEquals(Outcome.MOVED_TO_NEXT, plan.outcome)
        assertEquals(keys(1, 99, 2, 3), plan.keys)
        assertEquals("搬移不改变队列长度", 4, plan.keys.size)
        assertEquals(1, plan.insertPos)
        assertEquals(0, plan.currentIndex)
    }

    @Test
    fun `重复添加 - 队列里不出现两份相同的 id`() {
        var queueKeys = keys(1, 2, 3, 4)
        var current = 2
        for (newKey in keys(99, 99, 1, 99, 2)) {
            val plan = QueueInsert.plan(queueKeys, current, newKey)
            queueKeys = plan.keys
            current = plan.currentIndex
            assertEquals("身份序列出现重复：$queueKeys", queueKeys.size, queueKeys.toSet().size)
        }
    }

    // ---------- 5. 这首歌就是当前歌 ----------

    @Test
    fun `它就是当前歌 - 幂等忽略 且给出可上报的结果`() {
        val plan = QueueInsert.plan(keys(1, 2, 3), currentIndex = 1, newKey = key(2))

        assertEquals(Outcome.ALREADY_CURRENT, plan.outcome)
        assertEquals(keys(1, 2, 3), plan.keys)
        assertEquals(1, plan.currentIndex)
        assertEquals(-1, plan.insertPos)
        assertFalse(plan.queueChanged)
    }

    // ---------- 6. 关键不变量：去重后必须重新定位当前歌的下标 ----------

    @Test
    fun `去重后重新定位 - 当前歌之前有重复项时指针不能指错`() {
        // 这是 AGENTS.md 点名的那个坑：朴素的 `filter` 会把当前歌**之前**的重复项也删掉，
        // 于是 currentQueueIndex 指的就不再是当前歌了。
        // 当前歌是下标 2 的 2；99 出现在它**之前**（下标 0）。
        val plan = QueueInsert.plan(keys(99, 1, 2, 3), currentIndex = 2, newKey = key(99))

        assertEquals(key(2), plan.keys[plan.currentIndex])
        assertEquals(keys(1, 2, 99, 3), plan.keys)
        assertEquals(1, plan.currentIndex)
        assertEquals(2, plan.insertPos)
        assertEquals(Outcome.MOVED_TO_NEXT, plan.outcome)
    }

    @Test
    fun `关键不变量 - 任意输入下 新队列的当前下标都指向原当前歌`() {
        val queues = listOf(
            keys(1, 2, 3),
            keys(2, 1, 3),
            keys(1),
            keys(1, 2),
            keys(3, 2, 1),
        )
        val news = keys(1, 2, 3, 42)
        for (q in queues) {
            for (cur in q.indices) {
                for (n in news) {
                    val plan = QueueInsert.plan(q, cur, n)
                    if (plan.currentIndex in plan.keys.indices) {
                        assertEquals(
                            "队列=$q cur=$cur new=$n ⇒ ${plan.keys}@${plan.currentIndex}",
                            q[cur],
                            plan.keys[plan.currentIndex],
                        )
                    }
                    assertEquals("去重被破坏：$q + $n ⇒ ${plan.keys}", plan.keys.size, plan.keys.toSet().size)
                }
            }
        }
    }

    // ---------- 7. 随机模式：排列修正（铁律 16 的「随机模式」） ----------

    @Test
    fun `随机模式 - 插入的歌成为当前歌在编排序列里的下一项`() {
        // 队列 [10,20,30,40]，当前是 10；编排顺序 = 10,30,40,20。
        val oldKeys = keys(10, 20, 30, 40)
        val shuffled = listOf(0, 2, 3, 1)
        val plan = QueueInsert.plan(oldKeys, currentIndex = 0, newKey = key(99))
        assertEquals(keys(10, 99, 20, 30, 40), plan.keys)

        val fixed = QueueInsert.shuffleAfterInsert(
            oldKeys = oldKeys,
            newKeys = plan.keys,
            shuffled = shuffled,
            newCurrentIndex = plan.currentIndex,
            insertPos = plan.insertPos,
        )
        assertNotNull(fixed)
        // 当前歌（10，新下标 0）之后必须是新插入的 99（新下标 1）。
        val pos = fixed!!.indexOf(plan.currentIndex)
        assertEquals("插入项必须紧跟在当前歌的播放顺序之后", plan.insertPos, fixed[pos + 1])
        assertEquals("结果必须是完整排列", plan.keys.size, fixed.size)
        assertEquals("结果不能有重复项", plan.keys.size, fixed.toSet().size)
    }

    @Test
    fun `随机模式 - 搬移已有项时结果仍是合法排列且长度不变`() {
        // 99 原本在队尾（下标 3）。搬它到下一首。
        val oldKeys = keys(1, 2, 3, 99)
        val shuffled = listOf(0, 3, 1, 2)
        val plan = QueueInsert.plan(oldKeys, currentIndex = 0, newKey = key(99))
        assertEquals(Outcome.MOVED_TO_NEXT, plan.outcome)
        assertEquals(keys(1, 99, 2, 3), plan.keys)

        val fixed = QueueInsert.shuffleAfterInsert(
            oldKeys = oldKeys,
            newKeys = plan.keys,
            shuffled = shuffled,
            newCurrentIndex = plan.currentIndex,
            insertPos = plan.insertPos,
        )
        assertNotNull(fixed)
        assertEquals("搬移不改变队列长度 ⇒ 排列长度也不变", plan.keys.size, fixed!!.size)
        assertEquals(plan.keys.size, fixed.toSet().size)
        assertEquals(plan.insertPos, fixed[fixed.indexOf(plan.currentIndex) + 1])
    }

    @Test
    fun `随机模式 - 空排列返回 null 交给调用方重洗`() {
        val plan = QueueInsert.plan(keys(1, 2), currentIndex = 0, newKey = key(9))
        assertNull(
            QueueInsert.shuffleAfterInsert(
                oldKeys = keys(1, 2),
                newKeys = plan.keys,
                shuffled = emptyList(),
                newCurrentIndex = plan.currentIndex,
                insertPos = plan.insertPos,
            )
        )
    }

    @Test
    fun `随机模式 - 排列与队列脱节时返回 null 而不是给出错顺序`() {
        // 排列里引用了不存在的旧下标 ⇒ 无法安全映射。
        val plan = QueueInsert.plan(keys(1, 2), currentIndex = 0, newKey = key(9))
        assertNull(
            QueueInsert.shuffleAfterInsert(
                oldKeys = keys(1, 2),
                newKeys = plan.keys,
                shuffled = listOf(0, 7),
                newCurrentIndex = plan.currentIndex,
                insertPos = plan.insertPos,
            )
        )
    }

    @Test
    fun `随机模式 - 当前歌不在排列里时返回 null`() {
        val plan = QueueInsert.plan(keys(1, 2), currentIndex = 0, newKey = key(9))
        assertNull(
            QueueInsert.shuffleAfterInsert(
                oldKeys = keys(1, 2),
                newKeys = plan.keys,
                shuffled = listOf(1, 2), // 少了当前歌 1L 的新下标 0
                newCurrentIndex = plan.currentIndex,
                insertPos = plan.insertPos,
            )
        )
    }

    @Test
    fun `随机模式 - 结果必须是恰好覆盖每个下标一次`() {
        // 穷举一个 4 首队列的所有排列，验证永不返回缺项/重项的排列。
        val oldKeys = keys(10, 20, 30, 40)
        val allPerms = permutations((0 until 4).toList())
        var checked = 0
        for (perm in allPerms) {
            val plan = QueueInsert.plan(oldKeys, currentIndex = 2, newKey = key(99))
            val fixed = QueueInsert.shuffleAfterInsert(
                oldKeys = oldKeys,
                newKeys = plan.keys,
                shuffled = perm,
                newCurrentIndex = plan.currentIndex,
                insertPos = plan.insertPos,
            ) ?: continue
            checked++
            assertEquals(plan.keys.size, fixed.size)
            assertEquals(plan.keys.size, fixed.toSet().size)
            assertEquals((0 until plan.keys.size).toSet(), fixed.toSet())
        }
        assertTrue("至少要有一半排列能安全修正（覆盖率 $checked/${allPerms.size}）", checked > allPerms.size / 2)
    }

    // ---------- 8. 待播槽位重同步（本版修掉的核心缺陷） ----------

    @Test
    fun `待播槽位 - 除单曲循环外都要重同步`() {
        assertTrue(QueueInsert.shouldPreloadAfterInsert(QueueModes.CYCLE))
        assertTrue(QueueInsert.shouldPreloadAfterInsert(QueueModes.SHUFFLE))
        assertTrue(QueueInsert.shouldPreloadAfterInsert(QueueModes.LINE))
        assertTrue(QueueInsert.shouldPreloadAfterInsert(QueueModes.INFINITY))
        // 单曲循环的语义是"永远重播当前这一首"，重同步会静默把它变成顺序播放。
        assertFalse(QueueInsert.shouldPreloadAfterInsert(QueueModes.SINGLE))
    }

    @Test
    fun `下一首下标 - 乱序取排列里的后一项 其余模式取线性后一项`() {
        // 线性：队列下标 0 之后是 1。
        assertEquals(
            1,
            QueueInsert.nextIndexAfterInsert(QueueModes.CYCLE, 3, 0, emptyList()),
        )
        // 乱序：排列 = 0,2,1；当前 0 的下一项是 2，**不是**线性的 1。
        assertEquals(
            2,
            QueueInsert.nextIndexAfterInsert(QueueModes.SHUFFLE, 3, 0, listOf(0, 2, 1)),
        )
    }

    @Test
    fun `下一首下标 - 没有下一首时返回 -1`() {
        assertEquals(-1, QueueInsert.nextIndexAfterInsert(QueueModes.LINE, 3, 2, emptyList()))
        assertEquals(-1, QueueInsert.nextIndexAfterInsert(QueueModes.SHUFFLE, 3, 2, listOf(0, 1, 2)))
        assertEquals(-1, QueueInsert.nextIndexAfterInsert(QueueModes.CYCLE, 0, -1, emptyList()))
    }

    private fun <T> permutations(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        val out = ArrayList<List<T>>()
        for (i in items.indices) {
            val rest = items.toMutableList().also { it.removeAt(i) }
            for (tail in permutations(rest)) {
                out.add(listOf(items[i]) + tail)
            }
        }
        return out
    }
}
