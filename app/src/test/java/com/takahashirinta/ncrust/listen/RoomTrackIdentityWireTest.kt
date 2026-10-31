package com.takahashirinta.ncrust.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 一起听「歌曲身份」必须能穿过网络（v3.8.1）。
 *
 * 成员侧要按平台 ID 精确起播，前提是房主把 ID 放进 [CanonicalTrack.publicIds] 并且
 * [RoomWire] 的编解码不把它丢掉。这条链路任何一处断了，现象都是「连上了但不同步」，
 * 而且不会有任何报错 —— 所以用一次往返把它钉住。
 */
class RoomTrackIdentityWireTest {

    private fun state(track: CanonicalTrack) = RoomState(
        roomId = "123456",
        epoch = 1L,
        seq = 7L,
        hostUserId = "host",
        members = emptyList(),
        queue = listOf(track),
        currentRoomTrackId = track.roomTrackId,
        transport = RoomTransport(playing = true, positionMs = 42_000),
        stampedAtMs = 1_700_000_000_000,
    )

    @Test
    fun `歌曲ID与音源能完整往返`() {
        val track = CanonicalTrack(
            roomTrackId = "rt-1",
            title = "屋顶",
            artist = "周杰伦",
            album = "",
            durationMs = 240_000,
            editionKinds = emptySet(),
            publicIds = mapOf("songId" to "186016", "source" to "netease"),
        )
        val wire = RoomWire.Snapshot(state(track)).encode()
        val decoded = RoomWire.decode(wire) as? RoomWire.Snapshot
        assertNotNull("快照应能解码", decoded)
        val back = decoded!!.state.queue.single()
        assertEquals("186016", back.publicIds["songId"])
        assertEquals("netease", back.publicIds["source"])
        assertEquals("屋顶", back.title)
    }

    @Test
    fun `没有ID的老房间解码后不会崩`() {
        val track = CanonicalTrack(
            roomTrackId = "rt-2",
            title = "晴天",
            artist = "",
            album = "",
            durationMs = 0,
            editionKinds = emptySet(),
            publicIds = emptyMap(),
        )
        val decoded = RoomWire.decode(RoomWire.Snapshot(state(track)).encode()) as? RoomWire.Snapshot
        assertEquals(null, decoded!!.state.queue.single().publicIds["songId"])
    }
}
