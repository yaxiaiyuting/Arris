package com.takahashirinta.ncrust.listen

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 局域网直连传输（v3.8.1）。
 *
 * 存在理由：国内对 `*.workers.dev` 是 **SNI 阻断**（实测 ICMP 通、TLS ClientHello 被 RST），
 * 不开代理时公网中继不可用。局域网这条路不需要任何服务器，朋友在同一 WiFi/热点即可，
 * 协议与公网完全一致（[RoomWire] 行 JSON）。
 */
class RoomLinkLanTest {

    @Test
    fun `房主广播能被成员收到 且在线人数正确`() {
        val host = RoomLink.Lan.host()
        var hostMembers = 0
        host.onMessage = { msg ->
            if (msg is RoomWire.Presence) hostMembers = msg.members
        }
        host.startHost()
        val addr = host.address()
        // CI/容器里可能没有 site-local 地址，此时只验证端口可用
        val port = host.listenPort
        assertTrue("应分配到端口", port > 0)

        val ip = addr?.substringBeforeLast(':') ?: "127.0.0.1"
        val member = RoomLink.Lan.join(ip, port)
        val got = CountDownLatch(1)
        var snapshot: RoomState? = null
        member.onMessage = { msg ->
            if (msg is RoomWire.Snapshot) {
                snapshot = msg.state
                got.countDown()
            }
        }
        member.startMember()
        Thread.sleep(300)

        val state = RoomState(
            roomId = "LAN",
            epoch = 0,
            seq = 3,
            hostUserId = "host",
            queue = listOf(
                CanonicalTrack(
                    roomTrackId = "rt1", title = "晴天", artist = "周杰伦", album = "叶惠美",
                    durationMs = 269_000, editionKinds = emptySet(), publicIds = emptyMap(),
                )
            ),
            currentRoomTrackId = "rt1",
            transport = RoomTransport(true, 5_000, 1f),
            stampedAtMs = 1,
        )
        host.send(RoomWire.Snapshot(state))

        assertTrue("成员应收到快照", got.await(3, TimeUnit.SECONDS))
        assertEquals("晴天", snapshot?.queue?.first()?.title)
        assertEquals(3L, snapshot?.seq)

        member.close()
        host.close()
    }

    @Test
    fun `成员发给房主的操作能到房主`() {
        val host = RoomLink.Lan.host()
        val arrived = CountDownLatch(1)
        var op: RoomOp? = null
        host.onMessage = { msg ->
            if (msg is RoomWire.Op) {
                op = msg.op
                arrived.countDown()
            }
        }
        host.startHost()
        val ip = host.address()?.substringBeforeLast(':') ?: "127.0.0.1"
        val member = RoomLink.Lan.join(ip, host.listenPort)
        member.startMember()
        Thread.sleep(300)
        member.send(RoomWire.Op(RoomOp.Seek(seq = 7, byUserId = "m", positionMs = 12_345)))
        assertTrue("房主应收到操作", arrived.await(3, TimeUnit.SECONDS))
        assertEquals(12_345L, (op as RoomOp.Seek).positionMs)
        member.close()
        host.close()
    }
}
