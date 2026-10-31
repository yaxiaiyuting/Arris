package com.takahashirinta.ncrust.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 一起听 · 时钟与漂移（v3.8.0）。 */
class RoomClockTest {

    @Test
    fun `无样本时不做对齐`() {
        assertNull(RoomClock.estimateOffset(emptyList()))
    }

    @Test
    fun `取最小RTT样本而不是平均`() {
        val samples = listOf(
            RoomClock.Sample(sentAtMs = 1_000, receivedAtMs = 1_200, relayNowAtReplyMs = 12_000), // rtt 200
            RoomClock.Sample(sentAtMs = 2_000, receivedAtMs = 2_040, relayNowAtReplyMs = 22_000), // rtt 40（最优）
            RoomClock.Sample(sentAtMs = 3_000, receivedAtMs = 3_400, relayNowAtReplyMs = 30_000), // rtt 400
        )
        val est = RoomClock.estimateOffset(samples)!!
        assertEquals(40L, est.rttMs)
        // 最优样本：localMid = 2020，relay 22000 ⇒ offset 19980
        assertEquals(19_980L, est.offsetMs)
        assertEquals(3, est.samples)
    }

    @Test
    fun `对齐后位置计算不再减单程延迟`() {
        val transport = RoomTransport(playing = true, positionMs = 10_000)
        // offset 5_000：本机 1_000 ⇒ relay 6_000；stamp 在 relay 4_000 ⇒ 已过 2 秒
        val pos = RoomClock.targetPositionMs(
            transport = transport,
            stampedAtMs = 4_000,
            localNowMs = 1_000,
            estimate = RoomClock.Estimate(offsetMs = 5_000, rttMs = 30, samples = 5),
            durationMs = 200_000,
        )
        assertEquals(12_000L, pos)
    }

    @Test
    fun `暂停态位置不随时间推进`() {
        val transport = RoomTransport(playing = false, positionMs = 42_000)
        val pos = RoomClock.targetPositionMs(transport, 1_000, 99_000, null, 200_000)
        assertEquals(42_000L, pos)
    }

    @Test
    fun `位置被夹在时长内`() {
        val transport = RoomTransport(playing = true, positionMs = 199_000)
        val pos = RoomClock.targetPositionMs(transport, 0, 60_000, null, 200_000)
        assertEquals(200_000L, pos)
    }

    @Test
    fun `漂移三档`() {
        assertEquals(DriftPolicy.Action.Ignore, DriftPolicy.decide(30, 0))
        val micro = DriftPolicy.decide(150, 0) as DriftPolicy.Action.MicroAdjust
        // 落后 150ms ⇒ 倍率略大于 1
        assert(micro.rate > 1f) { "rate=${micro.rate}" }
        assertEquals(DriftPolicy.Action.Seek, DriftPolicy.decide(900, 0))
    }

    @Test
    fun `大偏差在冷却期内不反复seek`() {
        val action = DriftPolicy.decide(
            driftMs = 900,
            localNowMs = 5_000,
            lastSeekAtMs = 4_000,
            cooldownMs = 3_000,
        )
        assertEquals(DriftPolicy.Action.Ignore, action)
    }

    @Test
    fun `冷却指数退避并有上限`() {
        assertEquals(6_000L, DriftPolicy.nextCooldown(3_000))
        assertEquals(DriftPolicy.SEEK_COOLDOWN_MAX_MS, DriftPolicy.nextCooldown(20_000))
    }
}
