/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v4.2.0：服务侧复活判据的单测。
 */

package com.takahashirinta.ncrust.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRecoveryTest {

    // ---------------- 退避表 ----------------

    @Test
    fun `退避按 15s 起逐级翻倍并在 3 分钟封顶`() {
        assertEquals(15_000L, PlaybackRecovery.backoffMs(0))
        assertEquals(30_000L, PlaybackRecovery.backoffMs(1))
        assertEquals(60_000L, PlaybackRecovery.backoffMs(2))
        assertEquals(120_000L, PlaybackRecovery.backoffMs(3))
        assertEquals(180_000L, PlaybackRecovery.backoffMs(4))
        // 不设次数上限：第 5、6、100 次仍然是 3 分钟一次，而不是「放弃」。
        assertEquals(180_000L, PlaybackRecovery.backoffMs(5))
        assertEquals(180_000L, PlaybackRecovery.backoffMs(100))
    }

    @Test
    fun `退避表单调不减（防止有人插一个更短的间隔进来）`() {
        var previous = 0L
        for (attempt in 0..12) {
            val delay = PlaybackRecovery.backoffMs(attempt)
            assertTrue("第 $attempt 次的间隔 $delay 比上一次 $previous 短", delay >= previous)
            previous = delay
        }
    }

    // ---------------- 「死掉了」的判据 ----------------

    private fun dead(nowMs: Long) = PlaybackRecovery.nextDeadSinceMs(
        nowMs = nowMs,
        deadSinceMs = 0L,
        advanced = false,
        playing = false,
        hasIntent = true,
    )

    @Test
    fun `正在出声不算死`() {
        assertEquals(
            0L,
            PlaybackRecovery.nextDeadSinceMs(
                nowMs = 10_000L, deadSinceMs = 5_000L, advanced = true,
                playing = true, hasIntent = true,
            ),
        )
    }

    @Test
    fun `用户暂停不算死_这条保证我们绝不擅自重播用户已经停下的事`() {
        assertEquals(
            0L,
            PlaybackRecovery.nextDeadSinceMs(
                nowMs = 10_000L, deadSinceMs = 5_000L, advanced = false,
                playing = false, hasIntent = false,
            ),
        )
    }

    @Test
    fun `位置还在前进就不算死（READY 但没声音那种形状要靠位置戳穿）`() {
        assertEquals(
            0L,
            PlaybackRecovery.nextDeadSinceMs(
                nowMs = 10_000L, deadSinceMs = 5_000L, advanced = true,
                playing = false, hasIntent = true,
            ),
        )
    }

    @Test
    fun `第一次观察到死时记下时刻_之后保持不变`() {
        assertEquals(7_000L, dead(nowMs = 7_000L))
        // 第二次采样：起点不能被刷新，否则 now - deadSince 永远够不到阈值。
        assertEquals(
            7_000L,
            PlaybackRecovery.nextDeadSinceMs(
                nowMs = 9_000L, deadSinceMs = 7_000L, advanced = false,
                playing = false, hasIntent = true,
            ),
        )
    }

    @Test
    fun `复活窗口 30 分钟_窗口外不再替他续播`() {
        assertTrue("窗口还没开始", PlaybackRecovery.withinRetryWindow(nowMs = 1_000L, windowStartMs = 0L))
        assertTrue(
            "窗口内",
            PlaybackRecovery.withinRetryWindow(
                nowMs = 1_000L + PlaybackRecovery.RETRY_WINDOW_MS, windowStartMs = 1_000L,
            ),
        )
        assertFalse(
            "窗口外（多半已经到地方了，不该再擅自响起）",
            PlaybackRecovery.withinRetryWindow(
                nowMs = 1_000L + PlaybackRecovery.RETRY_WINDOW_MS + 1L, windowStartMs = 1_000L,
            ),
        )
    }

    // ---------------- 复活判据 ----------------

    @Test
    fun `死够 15 秒且本轮没试过就该复活`() {
        assertTrue(
            PlaybackRecovery.shouldResurrect(
                nowMs = 20_000L, deadSinceMs = 5_000L, hasIntent = true, playing = false,
                attempt = 0, lastAttemptAtMs = 0L, resolvable = true,
            ),
        )
    }

    @Test
    fun `没死够 15 秒不复活（否则会和卡顿自愈抢跑）`() {
        assertFalse(
            PlaybackRecovery.shouldResurrect(
                nowMs = 19_999L, deadSinceMs = 5_000L, hasIntent = true, playing = false,
                attempt = 0, lastAttemptAtMs = 0L, resolvable = true,
            ),
        )
    }

    @Test
    fun `刚试过要等满这一档退避`() {
        // attempt=0 ⇒ 等 15 秒。
        assertFalse(
            PlaybackRecovery.shouldResurrect(
                nowMs = 100_000L + 14_999L, deadSinceMs = 5_000L, hasIntent = true, playing = false,
                attempt = 0, lastAttemptAtMs = 100_000L, resolvable = true,
            ),
        )
        assertTrue(
            PlaybackRecovery.shouldResurrect(
                nowMs = 100_000L + 15_000L, deadSinceMs = 5_000L, hasIntent = true, playing = false,
                attempt = 0, lastAttemptAtMs = 100_000L, resolvable = true,
            ),
        )
    }

    @Test
    fun `第 N 次之后等的是第 N 档退避`() {
        val last = 500_000L
        // attempt=3 ⇒ 120 秒。
        assertFalse(
            PlaybackRecovery.shouldResurrect(
                nowMs = last + 119_000L, deadSinceMs = 5_000L, hasIntent = true, playing = false,
                attempt = 3, lastAttemptAtMs = last, resolvable = true,
            ),
        )
        assertTrue(
            PlaybackRecovery.shouldResurrect(
                nowMs = last + 120_000L, deadSinceMs = 5_000L, hasIntent = true, playing = false,
                attempt = 3, lastAttemptAtMs = last, resolvable = true,
            ),
        )
    }

    @Test
    fun `没有播放意图或正在出声或取不了链都不复活`() {
        val base = { hasIntent: Boolean, playing: Boolean, resolvable: Boolean ->
            PlaybackRecovery.shouldResurrect(
                nowMs = 60_000L, deadSinceMs = 1_000L, hasIntent = hasIntent, playing = playing,
                attempt = 0, lastAttemptAtMs = 0L, resolvable = resolvable,
            )
        }
        assertFalse("用户已经暂停", base(false, false, true))
        assertFalse("已经在出声", base(true, true, true))
        assertFalse("qm 缺 songmid，取多少次都是空", base(true, false, false))
        assertTrue("三者都满足", base(true, false, true))
    }

    @Test
    fun `没在死的状态里就不复活`() {
        assertFalse(
            PlaybackRecovery.shouldResurrect(
                nowMs = 60_000L, deadSinceMs = 0L, hasIntent = true, playing = false,
                attempt = 0, lastAttemptAtMs = 0L, resolvable = true,
            ),
        )
    }
}
