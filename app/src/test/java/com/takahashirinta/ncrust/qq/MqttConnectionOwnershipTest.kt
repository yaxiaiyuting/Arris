/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.8.0 · H20：MQTT 传输层的**会话归属**（旧连接的回调不得影响新连接）。
 */

package com.takahashirinta.ncrust.qq

import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H20 的回归契约：一条 WebSocket 连接的**终止事件只属于它自己**。
 *
 * ## 缺陷本体（06:43 轮 H20，三个状态探针）
 *
 * `MqttWebSocketTransport.reset()`（扫码重定向时用）取消旧 socket 并清空队列，但
 * `Connection.onMessage/onClosed/onFailure` 都**不检查自己还是不是 active**，
 * 一律往会话级的 `incoming` 里写。OkHttp 的 `cancel()` 是**异步**回调 ——
 * 那条「连接没了」哨兵可能落在 `reset()` 清队列**之后**，于是：
 *
 * - 新连接上等事件的 `awaitEvent` 第一次 `receive()` 拿到旧连接的哨兵 ⇒ 判成 `Failed`；
 * - 当前连接收到关闭帧时，旧实现只打一行日志（既不关 socket、也不投终态事件），
 *   等待方一直等到超时（扫码界面把超时当「继续等待」，用户要等到二维码过期）。
 *
 * ## 这个文件怎么测（**不联网**）
 *
 * 真实入口 `connect()` 必须先握手成功才拿得到 `Connection`，单测不可能去连
 * `mu.y.qq.com`。生产代码里「造一条连接 + 设为当前」已经抽成
 * [MqttWebSocketTransport.attachConnection]（`connect()` 自己也走它），
 * 于是测试可以拿到**真实的** `Connection` 并手工触发 `WebSocketListener` 回调，
 * 再用一个进程内的假 [WebSocket] 观察 `close()` 有没有被调用。
 *
 * 断言的是**生产代码的回调行为**，不是复制一份逻辑再测自己。
 */
class MqttConnectionOwnershipTest {

    /** 进程内假 socket：只记录 `close`/`cancel` 有没有被调用。 */
    private class FakeWebSocket : WebSocket {
        var closeCodes = mutableListOf<Int>()
        var cancelled = false

        override fun request(): Request = Request.Builder().url("https://example.invalid/").build()
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean {
            closeCodes += code
            return true
        }

        override fun cancel() {
            cancelled = true
        }
    }

    /** 一条合法的 PINGREQ（`0xC0 0x00`，剩余长度 0）—— MqttCodec.parse 能直接切出来。 */
    private val pingReq = byteArrayOf(0xC0.toByte(), 0x00)

    private fun transport() = MqttWebSocketTransport("client-under-test")

    @Test
    fun `旧连接的回调不再写入新连接的会话`() {
        val transport = transport()
        val old = transport.attachConnection()
        transport.reset()                      // 模拟「跟随 SERVER_REFERENCE 重定向」
        val fresh = transport.attachConnection()
        val oldSocket = FakeWebSocket()
        val newSocket = FakeWebSocket()
        old.socket = oldSocket
        fresh.socket = newSocket

        // 旧连接的迟到回调（OkHttp 的 cancel 是异步的，真实时序就是这样）
        old.onMessage(oldSocket, pingReq.toByteString())
        old.onClosed(oldSocket, 1006, "socket closed")
        old.onFailure(oldSocket, java.io.IOException("Socket closed"), null)

        assertNull(
            "旧连接的任何回调都不许进入会话通道 —— 否则新连接的 awaitEvent 第一条就拿到假失败",
            transport.incoming.tryReceive().getOrNull(),
        )

        // 新连接照常工作
        fresh.onMessage(newSocket, pingReq.toByteString())
        val frame = transport.incoming.tryReceive().getOrNull()
        assertEquals("新连接的消息必须照常送达", MqttCodec.PINGREQ, frame!!.type)
        assertTrue("send 走的是当前连接", transport.send(pingReq))
    }

    @Test
    fun `当前连接收到关闭帧必须主动收尾并且只投一次终态`() {
        val transport = transport()
        val conn = transport.attachConnection()
        val socket = FakeWebSocket()
        conn.socket = socket

        conn.onClosing(socket, 1001, "server going away")

        assertEquals(
            "收到关闭帧必须回一个正常关闭（旧实现连 socket.close 都没调）",
            listOf(1000),
            socket.closeCodes,
        )
        assertFalse("不回 cancel（那会让对端看到 1006 异常关闭）", socket.cancelled)

        val first = transport.incoming.tryReceive().getOrNull()
        assertEquals("必须投一条终态事件，而不是让 awaitEvent 等到超时", MqttCodec.DISCONNECT, first!!.type)
        assertNull("只能投一次", transport.incoming.tryReceive().getOrNull())

        // 之后 OkHttp 还会回调 onClosed —— 不许再投第二条。
        conn.onClosed(socket, 1000, "closed")
        assertNull("onClosed 不许再投第二条终态事件", transport.incoming.tryReceive().getOrNull())
    }

    @Test
    fun `当前连接异常断开同样投一次终态事件`() {
        val transport = transport()
        val conn = transport.attachConnection()
        val socket = FakeWebSocket()
        conn.socket = socket

        conn.onFailure(socket, java.net.SocketTimeoutException("read timed out"), null)

        assertEquals(MqttCodec.DISCONNECT, transport.incoming.tryReceive().getOrNull()!!.type)
        assertNull(transport.incoming.tryReceive().getOrNull())
        assertFalse("已断开的连接不再接受 send", transport.send(pingReq))
    }

    @Test
    fun `reset 之后旧连接的迟到失败被丢弃且不影响 send`() {
        val transport = transport()
        val old = transport.attachConnection()
        val oldSocket = FakeWebSocket()
        old.socket = oldSocket

        transport.reset()
        assertTrue("reset 必须 cancel 旧 socket", oldSocket.cancelled)
        assertFalse("reset 之后没有当前连接，send 必须失败而不是写进旧连接", transport.send(pingReq))

        old.onFailure(oldSocket, java.io.IOException("Socket closed"), null)
        assertNull(transport.incoming.tryReceive().getOrNull())
    }

    @Test
    fun `握手失败只投一次终态事件`() {
        val transport = transport()
        val conn = transport.attachConnection()
        // 握手失败路径：生产代码在 connect() 的超时分支里调用这两步。
        conn.deliverDisconnectOnce()
        conn.retire()
        conn.deliverDisconnectOnce()

        assertEquals(MqttCodec.DISCONNECT, transport.incoming.tryReceive().getOrNull()!!.type)
        assertNull(transport.incoming.tryReceive().getOrNull())
    }
}
