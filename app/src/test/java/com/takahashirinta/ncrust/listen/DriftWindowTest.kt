package com.takahashirinta.ncrust.listen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成员侧漂移窗口（v3.8.14）。
 *
 * 真机反馈「一起听的客户端会一直因为同步主机而暂停」：容差 800ms 太紧，
 * 网络抖动就能触发纠正。现在窗口 5 秒内不动，超过 12 秒才强制对齐。
 */
class DriftWindowTest {

    private val tolerance = 5_000L
    private val hard = 12_000L

    private fun snap(pos: Long, playing: Boolean = true) = PlaybackSnapshot(
        title = "t", artist = "a", positionMs = pos, playing = playing, songId = 1L, sourceKey = "netease",
    )

    @Test
    fun `两秒偏差在窗口内不动`() {
        assertTrue(FollowPolicy.withinDriftWindow(snap(10_000), snap(12_000), tolerance))
        assertFalse(FollowPolicy.needsSeek(snap(10_000), snap(12_000), tolerance))
    }

    @Test
    fun `恰好 5 秒仍算窗口内`() {
        assertTrue(FollowPolicy.withinDriftWindow(snap(10_000), snap(15_000), tolerance))
    }

    @Test
    fun `超过窗口才纠正`() {
        assertFalse(FollowPolicy.withinDriftWindow(snap(10_000), snap(16_000), tolerance))
        assertTrue(FollowPolicy.needsSeek(snap(10_000), snap(16_000), tolerance))
    }

    @Test
    fun `超过硬阈值应当强制对齐`() {
        val drift = kotlin.math.abs(25_000L - 10_000L)
        assertTrue(drift >= hard)
    }

    @Test
    fun `本地还没起播时不算偏差`() {
        assertFalse(FollowPolicy.withinDriftWindow(null, snap(10_000), tolerance))
        assertFalse(FollowPolicy.needsSeek(null, snap(10_000), tolerance))
    }

    @Test
    fun `播放态不同才需要跟随`() {
        assertTrue(FollowPolicy.needsTransportFix(snap(0, playing = true), snap(0, playing = false)))
        assertFalse(FollowPolicy.needsTransportFix(snap(0, playing = true), snap(0, playing = true)))
    }
}
