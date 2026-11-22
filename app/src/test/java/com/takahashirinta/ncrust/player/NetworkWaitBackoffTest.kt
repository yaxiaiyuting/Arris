/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v4.1.7 · P0 回归单测：**网络型失败必须一直等到网络回来，而不是两次就放弃**。
 *
 * 用例名后面括号里是它在真机 logcat 里对应的时间点
 * （Droidloom cell / API 37 / 4.1.6-gpl / 2026-11-22，屏幕熄灭 + 断 DNS 模拟网络抖动）：
 *
 *   10:47:03  第 1 次定额重试（3 秒后）
 *   10:47:26  第 2 次定额重试（23 秒后，因为中间要等一次取链超时）
 *   10:47:46  `取链网络失败且重试额度已用尽 — pausing for user action`
 */

package com.takahashirinta.ncrust.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkWaitBackoffTest {

    /** 退避阶梯本身：单调不减、首级 2 秒、末级 30 秒并保持。 */
    @Test
    fun `backoff steps are non decreasing and capped`() {
        val backoff = NetworkWaitBackoff()
        val delays = (0 until 8).map { backoff.nextDelay(it * 1_000L) }
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L, 30_000L, 30_000L, 30_000L), delays)
        // 单调不减是「不惊扰用户」的判据：退避不能越等越急。
        delays.zipWithNext().forEach { (a, b) -> assertTrue(b!! >= a!!) }
    }

    /**
     * 真机那条中断的形状：**67 秒断网**。
     *
     * 旧实现只有 2 次定额（3 秒间隔），67 秒里早就用完了。本条钉住「一次 67 秒的
     * 网络故障全程都在窗口内、每次都给得出下一次退避」，也就是「不会在断网第 3 次就停」。
     */
    @Test
    fun `a 67 second outage never exhausts the window`() {
        val backoff = NetworkWaitBackoff()
        var now = 0L
        var granted = 0
        repeat(12) {
            val delay = backoff.nextDelay(now)
            assertNotNull("断网只到 ${now}ms 就放弃了", delay)
            granted++
            now += delay!!
        }
        assertTrue("只给了 $granted 次机会就超窗口", now >= 67_000L)
    }

    /** 一直没网：等满窗口后返回 null，调用方才允许「暂停 + 提示」。 */
    @Test
    fun `window expiry returns null once`() {
        val backoff = NetworkWaitBackoff()
        assertNotNull(backoff.nextDelay(0L))
        // 窗口是 10 分钟：第 599 秒仍然等，第 600 秒放弃。
        assertNotNull(backoff.nextDelay(599_000L))
        assertNull(backoff.nextDelay(600_000L))
        assertNull(backoff.nextDelay(601_000L))
    }

    /** 窗口从**第一次失败**开始算，不随每次重试顺延（否则一直没网就永远不停）。 */
    @Test
    fun `window is anchored at the first failure`() {
        val backoff = NetworkWaitBackoff()
        backoff.nextDelay(0L)
        backoff.nextDelay(500_000L)
        assertNull("窗口被每次重试顺延了", backoff.nextDelay(600_000L))
    }

    /** 真的播出声 / 用户操作 / 换歌：下一轮从最小退避重新开始。 */
    @Test
    fun `reset re-arms the first step`() {
        val backoff = NetworkWaitBackoff()
        backoff.nextDelay(0L)
        backoff.nextDelay(10_000L)
        backoff.nextDelay(20_000L)
        assertEquals(10_000L, backoff.lastDelayMs)
        assertTrue(backoff.isWaiting())

        backoff.reset()

        assertFalse(backoff.isWaiting())
        assertEquals(-1L, backoff.lastDelayMs)
        assertEquals(2_000L, backoff.nextDelay(0L))
    }

    /** 时钟回退（改系统时间 / 单调钟重启）只能放行，绝不能误判成「窗口已过」而停下。 */
    @Test
    fun `clock going backwards still grants a retry`() {
        val backoff = NetworkWaitBackoff()
        backoff.nextDelay(1_000_000L)
        assertNotNull(backoff.nextDelay(10L))
    }

    /** `waitedMs` 是日志判据：不在等待时是 0，等待中是自窗口起点的时长。 */
    @Test
    fun `waitedMs reports time since the window opened`() {
        val backoff = NetworkWaitBackoff()
        assertEquals(0L, backoff.waitedMs(5_000L))
        backoff.nextDelay(5_000L)
        assertEquals(0L, backoff.waitedMs(5_000L))
        assertEquals(67_000L, backoff.waitedMs(72_000L))
    }

    /** 旧定额的语义必须**不再是**判据：2 次之后仍然有下一次。 */
    @Test
    fun `more than two retries are available`() {
        val backoff = NetworkWaitBackoff()
        repeat(UrlRetryGate.MAX_ATTEMPTS_PER_SONG) { assertNotNull(backoff.nextDelay(it * 3_000L)) }
        assertNotNull(
            "超过旧定额 ${UrlRetryGate.MAX_ATTEMPTS_PER_SONG} 次就放弃了 —— 熄屏中断会照旧发生",
            backoff.nextDelay(30_000L),
        )
    }
}
