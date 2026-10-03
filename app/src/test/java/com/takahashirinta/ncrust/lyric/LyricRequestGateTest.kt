/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/**
 * v1.9.0：歌词请求序列号闸门（[LyricRequestGate]）的单测。
 *
 * 目标只有一个：**快速切歌时旧响应必须作废**。所以每条用例都在问同一件事 ——
 * 「老号还能不能骗过 isCurrent」。并发那条用的是 8 线程各 1000 次取号，
 * 断言全局唯一且严格递增，覆盖 AtomicLong 方案的原子性（换成普通 Long 必挂）。
 */
class LyricRequestGateTest {

    @Test
    fun `begin——连续取号只有最后一个有效`() {
        val gate = LyricRequestGate()
        val first = gate.begin()
        val second = gate.begin()
        val third = gate.begin()

        assertFalse(gate.isCurrent(first))
        assertFalse(gate.isCurrent(second))
        assertTrue(gate.isCurrent(third))
    }

    @Test
    fun `begin——旧号在下一次begin后立刻失效`() {
        val gate = LyricRequestGate()
        val old = gate.begin()
        assertTrue("刚取到的号必须当场有效", gate.isCurrent(old))

        // 模拟「A 歌请求还在飞，用户切到 B 歌」：B 的 begin 一发生，A 的号就必须作废
        val next = gate.begin()
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(next))
    }

    @Test
    fun `invalidate——全部失效且再次begin恢复有效`() {
        val gate = LyricRequestGate()
        val seq = gate.begin()
        gate.invalidate()
        assertFalse(gate.isCurrent(seq))

        // 作废不是「闸门卡死」：重新开一次请求要能拿到有效号
        val revived = gate.begin()
        assertTrue(gate.isCurrent(revived))
        assertFalse("作废后发出的新号也不能把老号复活", gate.isCurrent(seq))

        // 连续两次 invalidate 也应保持「全部失效」
        gate.invalidate()
        gate.invalidate()
        assertFalse(gate.isCurrent(revived))
        assertTrue(gate.isCurrent(gate.begin()))
    }

    @Test
    fun `isCurrent——从未begin时哨兵0也不算有效`() {
        val gate = LyricRequestGate()
        // 调用方「还没请求过」的字段默认是 0；若它被当成有效号，首帧的迟到响应就能写坏状态
        assertFalse(gate.isCurrent(0L))
        assertFalse(gate.isCurrent(-1L))
        assertFalse(gate.isCurrent(Long.MAX_VALUE))
    }

    @Test
    fun `并发——8线程各1000次要全局唯一且严格递增`() {
        val gate = LyricRequestGate()
        val threads = 8
        val perThread = 1000
        val issued = ConcurrentLinkedQueue<Long>()
        // 用闩锁让 8 个线程尽量同时开抢，制造真正的竞争窗口
        val start = CountDownLatch(1)

        val workers = (0 until threads).map {
            Thread {
                start.await()
                repeat(perThread) { issued.add(gate.begin()) }
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join(60_000) }
        assertTrue("并发线程未在 60s 内结束", workers.none { it.isAlive })

        val sorted = issued.toList().sorted()
        assertEquals(threads * perThread, sorted.size)
        assertEquals("号必须全局唯一", threads * perThread, sorted.distinct().size)
        assertEquals("第一个号是 1", 1L, sorted.first())
        assertEquals("末号等于取号总数", (threads * perThread).toLong(), sorted.last())
        // 唯一 + 落在 1..8000 内 ⇒ 必然严格递增无空洞；这条断言把结论写死，避免以后有人改成复用号
        assertTrue("号必须严格递增无空洞", sorted.zipWithNext().all { (a, b) -> b == a + 1L })

        // 全部取号结束后，只有最后发出的那个号有效
        val last = sorted.last()
        assertTrue(gate.isCurrent(last))
        assertTrue("除末号外任何号都必须失效", sorted.dropLast(1).none { gate.isCurrent(it) })
    }
}
