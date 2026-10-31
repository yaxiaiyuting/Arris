package com.takahashirinta.ncrust.listen

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一起听中继的**真套接字**往返测试（v3.8.0）。
 *
 * 用 localhost 上的真实 ServerSocket/Socket，不 mock 网络：这个模块最容易出错的地方
 * 恰恰是"行协议 + 多连接 + 广播顺序"，mock 掉就测不到了。
 */
class RoomRelayTest {

    @Test
    fun `房主广播快照 成员能收到且内容一致`() {
        val server = RoomRelayServer(0)
        val received = CountDownLatch(1)
        server.start()
        val port = server.listenPort

        val client = RoomRelayClient("127.0.0.1", port)
        var got: RoomState? = null
        client.onMessage = { msg ->
            if (msg is RoomWire.Snapshot) {
                got = msg.state
                received.countDown()
            }
        }
        client.connect()
        Thread.sleep(200) // 等 hello 与注册完成

        val state = RoomState(
            roomId = "room-1",
            epoch = 7L,
            seq = 3L,
            hostUserId = "host",
            members = listOf(RoomMember("host", "我", 1L, true)),
            queue = listOf(
                CanonicalTrack(
                    roomTrackId = "rt1",
                    title = "晴天",
                    artist = "周杰伦",
                    album = "叶惠美",
                    durationMs = 269_000,
                    editionKinds = setOf("original"),
                    publicIds = mapOf("ncm" to "186016"),
                )
            ),
            currentRoomTrackId = "rt1",
            transport = RoomTransport(playing = true, positionMs = 12_345, rate = 1.0f),
            stampedAtMs = 1000L,
        )
        server.broadcast(RoomWire.Snapshot(state))

        assertTrue("成员应在 3 秒内收到快照", received.await(3, TimeUnit.SECONDS))
        assertEquals("room-1", got?.roomId)
        assertEquals(3L, got?.seq)
        assertEquals("晴天", got?.queue?.first()?.title)
        assertEquals(12_345L, got?.transport?.positionMs)
        client.close()
        server.close()
    }

    @Test
    fun `时钟探针 pingpong 能算出偏移`() {
        val server = RoomRelayServer(0)
        server.start()
        val port = server.listenPort
        // 房主侧：收到 ping 就回 pong（relayNow 用同一时钟）
        server.onMessage = { _, msg ->
            if (msg is RoomWire.Ping) server.broadcast(RoomWire.Pong(msg.t0, System.nanoTime() / 1_000_000))
        }
        val client = RoomRelayClient("127.0.0.1", port)
        val pong = CountDownLatch(1)
        var sample: RoomClock.Sample? = null
        client.onMessage = { msg ->
            if (msg is RoomWire.Pong) {
                sample = RoomClock.Sample(
                    sentAtMs = msg.t0,
                    receivedAtMs = System.nanoTime() / 1_000_000,
                    relayNowAtReplyMs = msg.relayNow,
                )
                pong.countDown()
            }
        }
        client.connect()
        Thread.sleep(200)
        client.send(RoomWire.Ping(System.nanoTime() / 1_000_000))
        assertTrue("应收到 pong", pong.await(3, TimeUnit.SECONDS))
        val estimate = RoomClock.estimateOffset(listOf(sample!!))!!
        // 本机回环：偏移应接近 0（给 ±200ms 容差，避免 CI 抖动）
        assertTrue(
            "本地回环偏移应接近 0，实际 ${estimate.offsetMs}ms（rtt=${estimate.rttMs}ms）",
            kotlin.math.abs(estimate.offsetMs) < 200,
        )
        client.close()
        server.close()
    }

    @Test
    fun `帧编码解码往返不丢字段`() {
        val op = RoomOp.Play(seq = 9, byUserId = "u1", positionMs = 4321, rate = 1.0f)
        val wire = RoomWire.Op(op)
        val decoded = RoomWire.decode(wire.encode())
        val back = (decoded as RoomWire.Op).op as RoomOp.Play
        assertEquals(9L, back.seq)
        assertEquals(4321L, back.positionMs)
        assertEquals("u1", back.byUserId)
    }
}
