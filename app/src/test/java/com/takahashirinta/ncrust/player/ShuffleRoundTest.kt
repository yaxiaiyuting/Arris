/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v2.0.0 · T1-C：乱序轮次生成的契约。
 *
 * 为什么必须由单测兜住：真机上"扎堆"依赖统计与设备状态，无法稳定复现；
 * 而"当前曲钉在下标 0""不丢歌不重歌""相邻不同主艺人""约束不可满足时优雅退化"
 * 这四条是可以逐条钉死的确定性契约。
 */
class ShuffleRoundTest {

    private fun keys(vararg groups: Int): (Int) -> Long? = { i -> groups[i].toLong() }

    @Test
    fun `round is a permutation with the current song pinned at index zero`() {
        val round = ShuffleRound.newRound(size = 8, currentIndex = 3, keyOf = { null }, random = Random(1))
        assertEquals(8, round.size)
        assertEquals(3, round[0])
        assertEquals((0 until 8).toList(), round.sorted())
    }

    @Test
    fun `round handles degenerate sizes`() {
        assertEquals(emptyList<Int>(), ShuffleRound.newRound(size = 0, currentIndex = 0, keyOf = { null }))
        assertEquals(listOf(0), ShuffleRound.newRound(size = 1, currentIndex = 0, keyOf = { null }))
        // size=1 时 currentIndex 越界也要夹住，绝不崩
        assertEquals(listOf(0), ShuffleRound.newRound(size = 1, currentIndex = 7, keyOf = { null }))
        assertEquals(listOf(0, 1), ShuffleRound.newRound(size = 2, currentIndex = 0, keyOf = { null }))
    }

    @Test
    fun `round is stable for a fixed seed`() {
        val a = ShuffleRound.newRound(size = 30, currentIndex = 0, keyOf = { null }, random = Random(42))
        val b = ShuffleRound.newRound(size = 30, currentIndex = 0, keyOf = { null }, random = Random(42))
        assertEquals(a, b)
    }

    @Test
    fun `disperse breaks adjacent same artist pairs when it can`() {
        // A A B B C C A A B B：主艺人成块，全部相邻对都可拆
        val groups = intArrayOf(1, 1, 2, 2, 3, 3, 1, 1, 2, 2)
        val order = groups.indices.toList()
        val fixed = ShuffleRound.disperse(order, keys(*groups), Random(7))
        assertEquals(order.sorted(), fixed.sorted())
        for (i in 1 until fixed.size) {
            assertNotEquals(
                "位置 $i 与 $i-1 撞了主艺人（${fixed[i - 1]} vs ${fixed[i]}）",
                groups[fixed[i - 1]], groups[fixed[i]]
            )
        }
    }

    @Test
    fun `disperse keeps every song exactly once even when it gives up`() {
        // 全部同主艺人：约束永远不可满足 ⇒ 必须原样退化，不丢歌、不重歌、不死循环
        val groups = intArrayOf(5, 5, 5, 5, 5, 5)
        val order = listOf(4, 2, 0, 5, 1, 3)
        val fixed = ShuffleRound.disperse(order, keys(*groups), Random(3))
        assertEquals(order.sorted(), fixed.sorted())
    }

    @Test
    fun `disperse treats a missing artist as unconstrained`() {
        // null = 缺艺人信息（不参与约束）：既不该被当成"同一艺人"，也不该把别人卡死
        val g = arrayOfNulls<Int?>(4)
        g[0] = 1; g[1] = 1; g[2] = 2; g[3] = 2
        val keyOf: (Int) -> Long? = { i -> g[i]?.toLong() }
        val fixed = ShuffleRound.disperse(listOf(0, 1, 2, 3), keyOf, Random(9))
        assertEquals(listOf(0, 1, 2, 3).sorted(), fixed.sorted())
        assertNotEquals(g[fixed[0]], g[fixed[1]])
    }

    @Test
    fun `disperse is a no-op for tiny rounds`() {
        assertEquals(listOf(1, 0), ShuffleRound.disperse(listOf(1, 0), keys(1, 1), Random(1)))
        assertEquals(emptyList<Int>(), ShuffleRound.disperse(emptyList(), keys(), Random(1)))
    }

    @Test
    fun `new round never starts with the same artist as the current song when avoidable`() {
        // 轮首（下标 1）是"下一首"，必须尽量与"正在播的那首"（下标 0）不同主艺人
        val groups = intArrayOf(1, 1, 1, 2, 3, 4)
        repeat(20) { seed ->
            val round = ShuffleRound.newRound(size = groups.size, currentIndex = 0, keyOf = keys(*groups), random = Random(seed))
            assertNotEquals(groups[round[0]], groups[round[1]])
        }
    }

    @Test
    fun `round keeps dispersion across the whole sequence when the pool allows`() {
        // 3 个艺人 × 各 4 首，理论上完全可分散
        val groups = IntArray(12) { it / 4 + 1 }
        repeat(20) { seed ->
            val round = ShuffleRound.newRound(size = groups.size, currentIndex = 0, keyOf = keys(*groups), random = Random(seed))
            assertEquals((0 until groups.size).toList(), round.sorted())
            for (i in 1 until round.size) {
                assertTrue(
                    "seed=$seed 位置 $i 撞主艺人：${round}",
                    groups[round[i - 1]] != groups[round[i]]
                )
            }
        }
    }
}
