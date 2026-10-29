package com.takahashirinta.ncrust.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 一起听 · 房主权威状态机（v3.8.0）：每条不变量一个用例。 */
class RoomEngineTest {

    private fun track(id: String, title: String = "t$id") = CanonicalTrack(
        roomTrackId = id,
        title = title,
        artist = "a",
        durationMs = 180_000,
    )

    private fun room(vararg tracks: CanonicalTrack) = RoomState(
        roomId = "r1",
        hostUserId = "host",
        queue = tracks.toList(),
        currentRoomTrackId = tracks.firstOrNull()?.roomTrackId,
        stampedAtMs = 1_000,
    )

    @Test
    fun `非房主的操作一律拒绝`() {
        val s = room(track("a"))
        val r = RoomEngine.apply(s, RoomOp.Play(seq = 1, byUserId = "member"), nowMs = 2_000)
        assertEquals(RoomEngine.Reason.NotHost, (r as RoomEngine.ApplyResult.Rejected).reason)
    }

    @Test
    fun `序号必须严格递增_重复投递被拒`() {
        val s = room(track("a")).copy(seq = 5)
        val r = RoomEngine.apply(s, RoomOp.Pause(seq = 5, byUserId = "host", positionMs = 10), nowMs = 2_000)
        assertEquals(RoomEngine.Reason.Stale, (r as RoomEngine.ApplyResult.Rejected).reason)
    }

    @Test
    fun `播放会写 stamp_位置随时间推进`() {
        val s = room(track("a"))
        val applied = RoomEngine.apply(s, RoomOp.Play(seq = 1, byUserId = "host", positionMs = 1_000), nowMs = 5_000)
        val next = (applied as RoomEngine.ApplyResult.Applied).state
        assertTrue(next.transport.playing)
        assertEquals(5_000L, next.stampedAtMs)

        // 对齐后 2 秒：位置应该是 1_000 + 2_000，且**不再减单程延迟**
        val pos = RoomClock.targetPositionMs(
            transport = next.transport,
            stampedAtMs = next.stampedAtMs,
            localNowMs = 7_000,
            estimate = RoomClock.Estimate(offsetMs = 0, rttMs = 8, samples = 3),
            durationMs = 180_000,
        )
        assertEquals(3_000L, pos)
    }

    @Test
    fun `下一首按队列顺序推进_到尾部不越界`() {
        val s = room(track("a"), track("b")).copy(seq = 1)
        val r1 = RoomEngine.apply(s, RoomOp.Next(seq = 2, byUserId = "host"), nowMs = 2_000)
        assertEquals("b", (r1 as RoomEngine.ApplyResult.Applied).state.currentRoomTrackId)

        val r2 = RoomEngine.apply(r1.state, RoomOp.Next(seq = 3, byUserId = "host"), nowMs = 3_000)
        // 队尾没有下一首 ⇒ 拒绝（不静默跳回第一首）
        assertEquals(RoomEngine.Reason.UnknownTrack, (r2 as RoomEngine.ApplyResult.Rejected).reason)
    }

    @Test
    fun `插入下一首落在当前曲之后`() {
        val s = room(track("a"), track("b")).copy(seq = 1)
        val r = RoomEngine.apply(s, RoomOp.AddTrack(seq = 2, byUserId = "host", track = track("x"), insertNext = true), nowMs = 2_000)
        val q = (r as RoomEngine.ApplyResult.Applied).state.queue.map { it.roomTrackId }
        assertEquals(listOf("a", "x", "b"), q)
    }

    @Test
    fun `删掉当前曲后_当前曲落到同位置的下一条`() {
        val s = room(track("a"), track("b"), track("c")).copy(seq = 1)
        val r = RoomEngine.apply(s, RoomOp.RemoveTrack(seq = 2, byUserId = "host", roomTrackId = "a"), nowMs = 2_000)
        val st = (r as RoomEngine.ApplyResult.Applied).state
        assertEquals("b", st.currentRoomTrackId)
        assertEquals(0L, st.transport.positionMs)
    }

    @Test
    fun `成员只接受更新的快照_旧快照被拒`() {
        val local = room(track("a")).copy(seq = 10)
        val older = room(track("a")).copy(seq = 9)
        val result = RoomEngine.accept(local, older)
        assertEquals(RoomEngine.Reason.Stale, (result as RoomEngine.Acceptance.Reject).reason)
    }

    @Test
    fun `纪元变更时无条件接受并要求重定位`() {
        val local = room(track("a")).copy(seq = 99, epoch = 1)
        val incoming = room(track("b")).copy(seq = 1, epoch = 2)
        val result = RoomEngine.accept(local, incoming)
        assertTrue((result as RoomEngine.Acceptance.Accept).epochChanged)
    }

    @Test
    fun `同一首歌跨源描述相同即视为同一首_时长差异不算另一首`() {
        val a = track("a", "晴天").copy(artist = "周杰伦", durationMs = 269_000)
        val b = track("b", "晴天").copy(artist = "周杰伦", durationMs = 270_500)
        assertTrue(a.sameSong(b))
        assertTrue(a.samePerformance(b))
        val live = b.copy(editionKinds = setOf("live"))
        assertTrue("版本不同就不是同一首", !a.sameSong(live))
    }
}
