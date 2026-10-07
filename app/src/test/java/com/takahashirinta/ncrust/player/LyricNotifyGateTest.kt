/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.player.LyricNotifyGate.Decision
import com.takahashirinta.ncrust.player.LyricNotifyGate.NEVER_POSTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.2：跨行重 post 通知的判定（[LyricNotifyGate]）。
 *
 * 这一版把 v1.8.0 的 `SDK_INT < P` 版本闸门**整个删掉**（真机实测证明它让重 post 次数恒为 0），
 * 换成「所有 API 版本都比对内容 + 限流 + 延后重试」。所以用例分两组：
 *  1. 判定语义（四条分支 + 边界）；
 *  2. **不丢行**这个新保证的模型级验证（密集段落里最后一行必须到达）。
 */
class LyricNotifyGateTest {

    private val T0 = 1_000_000L

    // ---------- 1. 判定语义 ----------

    @Test
    fun `服务没进前台——不发，哪怕行已经变了`() {
        assertEquals(
            Decision.SkipNotStarted,
            LyricNotifyGate.decide(
                line = "第一句",
                lastPostedLine = NEVER_POSTED,
                nowMs = T0,
                lastPostAtMs = 0L,
                serviceStarted = false,
            )
        )
    }

    @Test
    fun `第一次拿到歌词行——无条件发（哨兵不等于任何真实行）`() {
        assertEquals(
            Decision.Post,
            LyricNotifyGate.decide("第一句", NEVER_POSTED, T0, 0L, serviceStarted = true)
        )
    }

    @Test
    fun `第一首无歌词的歌——null 行也要发一次（不能因为哨兵判断错而卡在歌名）`() {
        // NEVER_POSTED 是"从没发过"，null 是"当前没有歌词行"，两者不能混
        assertEquals(
            Decision.Post,
            LyricNotifyGate.decide(
                line = null,
                lastPostedLine = NEVER_POSTED,
                nowMs = T0,
                lastPostAtMs = 0L,
                serviceStarted = true,
            )
        )
    }

    @Test
    fun `通知里已经是这一行——不重发`() {
        assertEquals(
            Decision.SkipSameLine,
            LyricNotifyGate.decide("A", "A", T0 + 5_000L, T0, serviceStarted = true)
        )
    }

    @Test
    fun `通知里是 null、当前也还是 null——不重发`() {
        assertEquals(
            Decision.SkipSameLine,
            LyricNotifyGate.decide(null, null, T0 + 5_000L, T0, serviceStarted = true)
        )
    }

    @Test
    fun `从「没有歌词行」变成「有歌词行」——要发`() {
        assertEquals(
            Decision.Post,
            LyricNotifyGate.decide("A", null, T0 + 5_000L, T0, serviceStarted = true)
        )
    }

    @Test
    fun `从「有歌词行」变成「没有歌词行」（尾奏）——要发`() {
        assertEquals(
            Decision.Post,
            LyricNotifyGate.decide(null, "最后一句", T0 + 5_000L, T0, serviceStarted = true)
        )
    }

    @Test
    fun `同一行不因时间流逝而重发——限流不参与去重判定`() {
        // line == lastPostedLine 必须优先于限流分支返回，否则"同一行等够 250ms 又发一次"
        assertEquals(
            Decision.SkipSameLine,
            LyricNotifyGate.decide("A", "A", T0 + 60_000L, T0, serviceStarted = true)
        )
    }

    @Test
    fun `距上次 post 不足最小间隔——延后重试而不是丢弃`() {
        val d = LyricNotifyGate.decide("B", "A", T0 + 100L, T0, serviceStarted = true)
        assertEquals(Decision.Defer(150L), d)
    }

    @Test
    fun `刚好到最小间隔——直接发`() {
        assertEquals(
            Decision.Post,
            LyricNotifyGate.decide(
                "B", "A",
                T0 + LyricNotifyGate.MIN_INTERVAL_MS,
                T0,
                serviceStarted = true,
            )
        )
    }

    @Test
    fun `最小间隔是 250ms（对齐 AOSP 每秒 5 次的通知入队上限）`() {
        assertEquals(250L, LyricNotifyGate.MIN_INTERVAL_MS)
    }

    @Test
    fun `时钟回拨时延后值仍然是合法的正数且不超过一个间隔`() {
        // nowMs < lastPostAtMs 不该让 retryInMs 变成巨大值或负数
        val d = LyricNotifyGate.decide("B", "A", T0 - 5_000L, T0, serviceStarted = true)
        assertTrue("应当是 Defer，实际 $d", d is Decision.Defer)
        val retry = (d as Decision.Defer).retryInMs
        assertTrue("retryInMs=$retry 应当落在 (0, MIN_INTERVAL_MS]", retry in 1..LyricNotifyGate.MIN_INTERVAL_MS)
    }

    @Test
    fun `首次 post（lastPostAtMs = 0）不会被限流挡住`() {
        // nowMs 是一个正常的 elapsedRealtime 大数，0 作为"从没发过"的初值必须放行
        assertEquals(
            Decision.Post,
            LyricNotifyGate.decide("A", NEVER_POSTED, T0, 0L, serviceStarted = true)
        )
    }

    @Test
    fun `哨兵本身不会与真实歌词行撞值`() {
        // 真实歌词是文本，不可能以 NUL 开头 —— 哨兵用它打头，保证不撞任何真实行
        assertTrue(NEVER_POSTED.startsWith("\u0000"))
        assertTrue("哨兵不能是空串", NEVER_POSTED.isNotEmpty())
        // 真实歌词行的取值谱系（null / 空 / 空白 / 正常文本）相对哨兵都必须判成「内容变了」
        for (line in listOf<String?>(null, "", "   ", "作词 : 某人", "作词")) {
            assertEquals(
                "line=${line?.let { "「$it」" } ?: "null"} 相对哨兵应当 Post",
                Decision.Post,
                LyricNotifyGate.decide(line, NEVER_POSTED, T0, 0L, serviceStarted = true),
            )
        }
    }

    // ---------- 2. 「不丢行」：密集段落里最后一行必须到达 ----------

    /**
     * [LyricNotifyGate] 的调用方模型：发出去就记账，被限流就记下"要等多久"，
     * 到点用**当时最新的行**再判一次（PlaybackService 里的 `lyricNotifyRetry` 就是这个语义）。
     */
    private class FakeService(started: Boolean = true) {
        var started: Boolean = started
        var lastPosted: String? = NEVER_POSTED
        var lastPostAt: Long = 0L
        val postedLines = mutableListOf<String?>()
        var pendingRetryAtMs: Long? = null

        fun onLine(line: String?, now: Long) {
            when (val d = LyricNotifyGate.decide(line, lastPosted, now, lastPostAt, started)) {
                Decision.Post -> {
                    postedLines += line
                    lastPosted = line
                    lastPostAt = now
                    pendingRetryAtMs = null
                }
                is Decision.Defer -> pendingRetryAtMs = now + d.retryInMs
                Decision.SkipNotStarted, Decision.SkipSameLine -> Unit
            }
        }
    }

    @Test
    fun `密集段落（200ms 一行）——中间行可以让路，最后一行一定会被 post`() {
        val svc = FakeService()
        var now = T0
        // 该曲最后一行是"END"
        val script = listOf("L1", "L2", "L3", "L4", "L5", "END")

        for (line in script) {
            svc.onLine(line, now)
            // 播放推进：每次 200ms（比 MIN_INTERVAL_MS 快，一定会触发限流）
            now += 200L
            // 到点就按"当时最新的行"重试一次
            svc.pendingRetryAtMs?.let { at ->
                if (at <= now) {
                    svc.onLine(line, at)
                }
            }
        }
        // 段落结束后，把还挂着的延迟重试跑到点（此时最新行就是 END）
        svc.pendingRetryAtMs?.let { at -> svc.onLine("END", at) }

        assertEquals("最后一行必须出现在通知里", "END", svc.lastPosted)
        assertTrue("至少要发出若干次（不是一次都不发）", svc.postedLines.size >= 2)
    }

    @Test
    fun `中间行被限流时不会把通知永久钉在被丢掉的那一行上`() {
        val svc = FakeService()
        // t=0 发 L1
        svc.onLine("L1", T0)
        assertEquals("L1", svc.lastPosted)

        // t=100 来 L2：太近，延后（不是丢弃）
        svc.onLine("L2", T0 + 100L)
        assertEquals("被限流时通知仍停在 L1", "L1", svc.lastPosted)
        assertEquals("并且挂了一次重试", T0 + 250L, svc.pendingRetryAtMs)

        // t=250 重试：此时最新行是 L2 —— 必须补上
        svc.onLine("L2", svc.pendingRetryAtMs!!)
        assertEquals("重试到点后 L2 必须补上", "L2", svc.lastPosted)
    }

    @Test
    fun `服务还没起来时收到行——不 post 也不排重试（服务起来后自然有别的 post 路径）`() {
        val svc = FakeService(started = false)
        svc.onLine("L1", T0)
        assertEquals(NEVER_POSTED, svc.lastPosted)
        assertTrue("不该排重试", svc.pendingRetryAtMs == null)
        assertEquals(emptyList<String?>(), svc.postedLines)
    }
}
