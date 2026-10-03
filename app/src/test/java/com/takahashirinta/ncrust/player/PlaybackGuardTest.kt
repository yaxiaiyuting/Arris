/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.1 · P0 回归单测：**失败处理必须有界**。
 *
 * 每一条都对应 2026-09-25 那次真机级联故障里的一个具体环节，
 * 用例名后面括号里是它在真机 logcat 里的样子（证据见
 * docs/verification/v2.2.1/p0-quality-loop/logcat/）。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.source.MusicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackGuardTest {

    private val ladder = listOf(
        "jymaster", "dolby", "jyeffect", "hires", "lossless", "exhigh", "higher", "standard",
    )

    // ---------------------------------------------------------------- 分类

    /**
     * 真机那条致命错误：`errorCodeName = ERROR_CODE_UNSPECIFIED`（最没用的一档），
     * 真正原因只在 cause 里。分类必须看 cause，否则会把它当 UNKNOWN 继续瞎重试。
     */
    @Test
    fun `sink failure is classified from cause not error code`() {
        val failure = PlaybackFailure(
            errorCode = 0,
            errorCodeName = "ERROR_CODE_UNSPECIFIED",
            causeClass = "java.lang.UnsupportedOperationException",
            causeMessage = "Default channel mixing coefficients for 6->1 are not yet implemented.",
        )
        assertEquals(FailureKind.SINK, classifyFailure(failure))
    }

    @Test
    fun `io failure is classified as source`() {
        val failure = PlaybackFailure(
            errorCode = 2001,
            errorCodeName = "ERROR_CODE_IO_BAD_HTTP_STATUS",
            causeClass = "androidx.media3.datasource.HttpDataSource\$InvalidResponseCodeException",
            causeMessage = "Response code: 403",
        )
        assertEquals(FailureKind.SOURCE, classifyFailure(failure))
    }

    @Test
    fun `decoder failure is classified as decoder`() {
        val failure = PlaybackFailure(
            errorCode = 3001,
            errorCodeName = "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
            causeClass = "androidx.media3.exoplayer.mediacodec.MediaCodecUtil\$DecoderQueryException",
            causeMessage = "No decoder for audio/flac",
        )
        assertEquals(FailureKind.DECODER, classifyFailure(failure))
    }

    // ------------------------------------------------- 降档重试：单调 + 有界

    /**
     * **回归的固定点**：请求 exhigh、实际拿到 lossless（离线兜底会退化成任意档位）。
     * 旧实现用「实际档位」算下一档 ⇒ next 又是 exhigh；再失败还是 exhigh ⇒ 永不收敛。
     * 现在必须严格降下去，而且不重复。
     */
    @Test
    fun `retry never returns a level at or above the actual level`() {
        val guard = QualityRetryGuard()
        guard.onNewSong("qqmusic:1")
        val d = guard.decide("exhigh", "lossless", ladder, nowMs = 1_000)
        assertEquals(GuardAction.RETRY, d.action)
        // 取两者中较差的（exhigh，index 5）再降一档 ⇒ higher（index 6）。
        // 旧实现只看 actual=lossless（index 4）⇒ 下一档 exhigh ⇒ 与请求档位相同 ⇒ 死循环。
        assertEquals("higher", d.nextLevel)
        assertTrue(
            "下一档必须严格低于两者",
            ladder.indexOf(d.nextLevel!!) > ladder.indexOf("exhigh") &&
                ladder.indexOf(d.nextLevel!!) > ladder.indexOf("lossless"),
        )
    }

    /** 同一条链不能原地打转：连续失败必须一路向下。 */
    @Test
    fun `retry ladder is strictly monotonic`() {
        val guard = QualityRetryGuard()
        guard.onNewSong("qqmusic:1")
        val first = guard.decide("jymaster", "jymaster", ladder, 1_000)
        assertEquals("dolby", first.nextLevel)
        val second = guard.decide(first.nextLevel!!, first.nextLevel!!, ladder, 2_000)
        assertEquals("jyeffect", second.nextLevel)
        val third = guard.decide(second.nextLevel!!, second.nextLevel!!, ladder, 3_000)
        assertEquals("hires", third.nextLevel)
        // 三次用完 —— 第四次必须停下，而不是继续降。
        val fourth = guard.decide(third.nextLevel!!, third.nextLevel!!, ladder, 4_000)
        assertEquals(GuardAction.STOP_AND_WAIT, fourth.action)
        assertNull(fourth.nextLevel)
    }

    /** 单曲自动重试上限 = 3（任务书的硬要求）。 */
    @Test
    fun `retry capped at three attempts per song`() {
        val guard = QualityRetryGuard()
        guard.onNewSong("qqmusic:1")
        var level = "jymaster"
        var retries = 0
        repeat(10) {
            val d = guard.decide(level, level, ladder, nowMs = it * 20_000L)
            if (d.action == GuardAction.RETRY) {
                retries++
                level = d.nextLevel!!
            }
        }
        assertEquals(QualityRetryGuard.MAX_ATTEMPTS_PER_SONG, retries)
        assertEquals(3, retries)
    }

    /** 换歌之后额度重置（但节流时间戳保留）。 */
    @Test
    fun `new song resets the per-song budget`() {
        val guard = QualityRetryGuard()
        guard.onNewSong("qqmusic:1")
        repeat(3) { guard.decide("standard", "standard", ladder, it * 20_000L) }
        assertEquals(GuardAction.STOP_AND_WAIT, guard.decide("standard", "standard", ladder, 99_000).action)
        guard.onNewSong("qqmusic:2")
        assertEquals(GuardAction.RETRY, guard.decide("jymaster", "hires", ladder, 200_000).action)
    }

    /** 用户手动操作 / 重启 = 全部清零。 */
    @Test
    fun `resetAll clears everything`() {
        val guard = QualityRetryGuard()
        guard.onNewSong("qqmusic:1")
        repeat(3) { guard.decide("jymaster", "jymaster", ladder, it * 20_000L) }
        guard.resetAll()
        val d = guard.decide("jymaster", "jymaster", ladder, 500_000)
        assertEquals(GuardAction.RETRY, d.action)
        assertEquals(0, guard.attemptsForCurrentSong() - 1)
    }

    /**
     * **输出链故障的档位拉黑**：实测「杜比」档（QQ Q001 = 6 声道 FLAC）会打坏 AudioSink，
     * 那就不该在本首歌里再试它 —— 旧实现会（阶梯顺序里 dolby 紧跟 jymaster）。
     */
    @Test
    fun `unusable level is never retried for the same song`() {
        val guard = QualityRetryGuard()
        guard.onNewSong("qqmusic:1")
        guard.markUnusable("dolby")
        val d = guard.decide("jymaster", "jymaster", ladder, 1_000)
        assertEquals(GuardAction.RETRY, d.action)
        assertNotEquals("dolby", d.nextLevel)
        assertEquals("jyeffect", d.nextLevel)
    }

    // ---------------------------------------------------- 音频焦点节流（10s）

    /**
     * 音频焦点节流：两次自动重取之间至少 10s。
     * 真机上的现象就是这一条缺失导致的 —— 「看视频时被打断，视频进度被反复打断，停播交替」。
     */
    @Test
    fun `restart is throttled to ten seconds after a failure`() {
        val guard = QualityRetryGuard()
        guard.onNewSong("qqmusic:1")
        guard.onFailure(nowMs = 100_000)
        val d = guard.decide("jymaster", "jymaster", ladder, nowMs = 103_000)
        assertEquals(GuardAction.RETRY, d.action)
        assertEquals(7_000L, d.delayMs)
    }

    @Test
    fun `no delay when the throttle window already elapsed`() {
        val guard = QualityRetryGuard()
        guard.onNewSong("qqmusic:1")
        guard.onFailure(nowMs = 100_000)
        val d = guard.decide("jymaster", "jymaster", ladder, nowMs = 100_000 + QualityRetryGuard.MIN_RESTART_INTERVAL_MS)
        assertEquals(0L, d.delayMs)
    }

    @Test
    fun `first failure in a fresh process is not delayed`() {
        val guard = QualityRetryGuard()
        guard.onNewSong("qqmusic:1")
        assertEquals(0L, guard.decide("jymaster", "jymaster", ladder, nowMs = 5).delayMs)
    }

    // -------------------------------------------------------- 自动跳歌熔断

    /** 连续自动跳歌上限 = 5，第 6 次必须被拒绝。 */
    @Test
    fun `auto skip trips after five consecutive skips`() {
        val guard = AutoSkipGuard()
        repeat(AutoSkipGuard.MAX_CONSECUTIVE_AUTO_SKIPS) { assertTrue(guard.requestAutoSkip()) }
        assertEquals(5, guard.consecutiveSkips)
        assertFalse("第 6 次必须熔断", guard.requestAutoSkip())
        assertTrue(guard.tripped)
        // 熔断后继续请求仍然拒绝（不是"再给一次机会"）。
        assertFalse(guard.requestAutoSkip())
    }

    /** 真的播出声了（进度前进 ≥3s）才清零 —— 「起播即失败」不能清零。 */
    @Test
    fun `only confirmed progress clears the skip counter`() {
        val guard = AutoSkipGuard()
        repeat(4) { guard.requestAutoSkip() }
        assertEquals(4, guard.consecutiveSkips)
        guard.onProgressConfirmed()
        assertEquals(0, guard.consecutiveSkips)
        repeat(5) { assertTrue(guard.requestAutoSkip()) }
    }

    /** 用户手动操作清零。 */
    @Test
    fun `user action clears the skip counter and the trip`() {
        val guard = AutoSkipGuard()
        repeat(6) { guard.requestAutoSkip() }
        assertTrue(guard.tripped)
        guard.onUserAction()
        assertFalse(guard.tripped)
        assertTrue(guard.requestAutoSkip())
    }

    // -------------------------------------------- 「音质失败 ≠ 播放失败」的契约

    /**
     * 这条用例锁的是**调用方的分支契约**（真正的分支在 PlayerViewModel.onUrlUnavailable 里）：
     * 只有非音质来源才允许走到 `onUnplayable`（= 跳歌）。
     *
     * 之所以用这个方式锁：`PlayerViewModel` 需要真 Application 才能构造，项目里没有 Robolectric
     * （见 PlayerViewModel 里那段「单测抓不到」的注释）。所以把**判据**抽成一个纯函数，
     * 让「哪些来源可以跳歌」这件事本身可以被单测钉住。
     */
    @Test
    fun `quality origins never skip to the next song`() {
        assertFalse(maySkipOnUrlFailure(PlayOrigin.QUALITY_SWITCH))
        assertFalse(maySkipOnUrlFailure(PlayOrigin.QUALITY_RETRY))
        assertTrue(maySkipOnUrlFailure(PlayOrigin.USER))
        assertTrue(maySkipOnUrlFailure(PlayOrigin.AUTO_NEXT))
        assertTrue(maySkipOnUrlFailure(PlayOrigin.PRELOAD))
    }

    /** 与 [maySkipOnUrlFailure] 配套：取链失败时，音质来源要先尝试回退到「最后成功档位」。 */
    @Test
    fun `quality origins prefer falling back to the last good level`() {
        assertTrue(prefersLastGoodLevel(PlayOrigin.QUALITY_SWITCH))
        assertTrue(prefersLastGoodLevel(PlayOrigin.QUALITY_RETRY))
        assertFalse(prefersLastGoodLevel(PlayOrigin.AUTO_NEXT))
    }

    // ------------------------------------------------ 降级不自动升回（持久化上限）

    private val levelsLowToHigh = listOf(
        "standard", "higher", "exhigh", "lossless", "hires", "jyeffect", "jymaster", "dolby",
    )

    /** 记住「这首歌最多到 hires」之后，自动路径不得再请求母带。 */
    @Test
    fun `remembered ceiling caps the automatic request`() {
        val ceiling = QualityCeilingMemory()
        ceiling.remember("qqmusic:1", "hires", nowMs = 1_000)
        assertEquals(
            "hires",
            ceiling.effectiveRequest("jymaster", "qqmusic:1", levelsLowToHigh, nowMs = 2_000),
        )
    }

    /** 记的上限比偏好还高时不许反过来「升档」—— 上限只是上限。 */
    @Test
    fun `ceiling never raises the request`() {
        val ceiling = QualityCeilingMemory()
        ceiling.remember("qqmusic:1", "jymaster", nowMs = 1_000)
        assertEquals(
            "lossless",
            ceiling.effectiveRequest("lossless", "qqmusic:1", levelsLowToHigh, nowMs = 2_000),
        )
    }

    /** 用户手动切档位 ⇒ 上限清掉，重新试探。 */
    @Test
    fun `manual switch clears the ceiling`() {
        val ceiling = QualityCeilingMemory()
        ceiling.remember("qqmusic:1", "hires", nowMs = 1_000)
        ceiling.clear("qqmusic:1")
        assertEquals(
            "jymaster",
            ceiling.effectiveRequest("jymaster", "qqmusic:1", levelsLowToHigh, nowMs = 2_000),
        )
    }

    /** TTL 过期 ⇒ 自动重新试探（续费/版权回归后不能永久降级）。 */
    @Test
    fun `ceiling expires after ttl`() {
        val ceiling = QualityCeilingMemory(ttlMs = 1_000)
        ceiling.remember("qqmusic:1", "hires", nowMs = 1_000)
        assertEquals(
            "jymaster",
            ceiling.effectiveRequest("jymaster", "qqmusic:1", levelsLowToHigh, nowMs = 5_000),
        )
    }

    /** 没有记录（老数据 / 别的歌）= 不知道，不是「最高只到某档」。 */
    @Test
    fun `missing entry means no ceiling`() {
        val ceiling = QualityCeilingMemory()
        assertEquals(
            "jymaster",
            ceiling.effectiveRequest("jymaster", "qqmusic:9", levelsLowToHigh, nowMs = 1),
        )
    }

    /** 条数有上限，不会无界增长。 */
    @Test
    fun `ceiling memory is bounded`() {
        val ceiling = QualityCeilingMemory()
        repeat(QualityCeilingMemory.MAX_ENTRIES + 50) {
            ceiling.remember("song:$it", "hires", nowMs = it.toLong())
        }
        assertEquals(QualityCeilingMemory.MAX_ENTRIES, ceiling.snapshot(nowMs = 10_000).size)
    }

    // ================================================================ v2.3.0 · C
    // 「此源无版权，可切另一源」提示的节流闸。它挂在「取链彻底失败 ⇒ 即将自动跳歌」
    // 这条**本来就会连续触发**的路径上，所以自己必须也有界（铁律 8）。

    @Test
    fun `提示闸第一次一定放行`() {
        val gate = SourceFallbackHintGate()
        assertTrue(gate.shouldHint(1_000L))
    }

    @Test
    fun `提示闸在时间窗内只放行一次`() {
        val gate = SourceFallbackHintGate(minIntervalMs = 20_000L)
        assertTrue(gate.shouldHint(0L))
        assertFalse(gate.shouldHint(1L))
        assertFalse(gate.shouldHint(19_999L))
        assertTrue(gate.shouldHint(20_000L))
    }

    @Test
    fun `连续失败不会弹出一串提示（最多 5 次自动跳歌就有界了）`() {
        val gate = SourceFallbackHintGate(minIntervalMs = 20_000L)
        var shown = 0
        // 模拟 v2.2.1 那场级联：2 秒内连续 5 次取链失败
        for (i in 0 until 5) {
            if (gate.shouldHint(i * 400L)) shown++
        }
        assertEquals(1, shown)
    }

    @Test
    fun `reset 之后下一次失败立刻能提示`() {
        val gate = SourceFallbackHintGate(minIntervalMs = 20_000L)
        assertTrue(gate.shouldHint(0L))
        assertFalse(gate.shouldHint(1L))
        gate.reset()
        assertTrue(gate.shouldHint(2L))
    }

    @Test
    fun `第一次一定放行——哨兵比较不能写成减法（会溢出成相反方向）`() {
        // 反向断言：如果实现写成 `nowMs - lastShownAtMs < minIntervalMs`，
        // lastShownAtMs 的初值 Long.MIN_VALUE 会让第一次减出一个正的大数以外的结果……
        // 具体地，nowMs - Long.MIN_VALUE 在 nowMs 较小时溢出为负数 ⇒ 第一次被误判成
        // 「刚提示过」⇒ 提示永远不出现。这个用例把那条路钉死。
        val gate = SourceFallbackHintGate(minIntervalMs = 20_000L)
        assertTrue(gate.shouldHint(0L))
    }

    @Test
    fun `时钟被往回拨时放行——不因为时钟问题让用户永远看不到出口`() {
        val gate = SourceFallbackHintGate(minIntervalMs = 20_000L)
        assertTrue(gate.shouldHint(1_000_000L))
        // 系统时间被调回到更早
        assertTrue(gate.shouldHint(500_000L))
    }

    @Test
    fun `另一个音源的判定只用结构性事实`() {
        assertEquals(MusicSource.QQMUSIC, MusicSource.otherThan(MusicSource.NETEASE))
        assertEquals(MusicSource.NETEASE, MusicSource.otherThan(MusicSource.QQMUSIC))
    }
}
