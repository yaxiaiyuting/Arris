/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.listen

import android.util.Log
import com.takahashirinta.ncrust.listen.p2p.StunClient
import com.takahashirinta.ncrust.listen.p2p.UdpReliable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * P2P 直连传输（v3.8.1 · 实验）：**不需要任何服务器，也不需要装第三方软件**。
 *
 * ## 工作方式（和 Minecraft 第三方启动器联机同一套原理）
 *
 * 1. 房主绑定一个 UDP 端口，向内网可达的公共 STUN 问出自己的**公网映射** `IP:端口`；
 * 2. 房主把 `映射地址#令牌` 作为**房间码**发出去（微信/短信都行，这就是"信令"）；
 * 3. 成员向该地址发包 → 各自的 NAT 都会记录出/入映射 → 双向通路建立（UDP 打洞）；
 * 4. 通路之上跑 [UdpReliable]（分片 + ACK + 重传），再之上就是我们原来的 [RoomWire] 行协议。
 *
 * ## 会在什么情况下失败（必须说清楚）
 *
 * - **对称 NAT / 运营商 CGNAT**（移动数据常见）：外部端口随目标变化，打不洞。此时只有中继能救；
 * - 双方都在不同的对称 NAT 后面 → 同上；
 * - 同一 WiFi 下反而不需要打洞（局域网模式更简单可靠）。
 *
 * 所以 UI 上它是"实验"入口，失败要给出明确原因而不是静默重试。
 */
class P2pLink private constructor(
    private val socket: DatagramSocket,
    private val token: String,
    private val isHost: Boolean,
) : RoomLink {

    override var onMessage: ((RoomWire) -> Unit)? = null
    override var onClosed: ((code: Int, reason: String) -> Unit)? = null
    override var onOpen: (() -> Unit)? = null

    private val running = AtomicBoolean(true)
    private val reassembler = UdpReliable.Reassembler()
    private val pendingAcks = ConcurrentHashMap<Long, Boolean>()   // key = msgId<<16 | idx

    @Volatile private var peer: InetSocketAddress? = null
    @Volatile var mappedAddress: String? = null
        private set

    /** 房主：开始监听并问 STUN，返回「公网地址:端口#令牌」作为房间码。 */
    fun startHost(): String? {
        val mapped = runCatching {
            socket.soTimeout = 1_500
            StunClient.queryMapped(socket)
        }.getOrNull()
        val local = socket.localAddress?.hostAddress ?: "127.0.0.1"
        val localPort = socket.localPort
        mappedAddress = mapped?.toString()
        startReader()
        onOpen?.invoke()
        val addr = mapped?.let { "${it.address.hostAddress}:${it.port}" } ?: "$local:$localPort"
        Log.i(TAG, "host mapped=$mappedAddress local=$local:$localPort")
        return "$addr#$token"
    }

    /** 成员：向房主地址发 HELLO，收到任何回包即视为打通。 */
    fun startMember(hostIp: String, hostPort: Int) {
        peer = InetSocketAddress(InetAddress.getByName(hostIp), hostPort)
        startReader()
        thread(isDaemon = true, name = "p2p-punch") {
            val hello = "HELLO:$token".toByteArray()
            repeat(12) {
                if (!running.get()) return@thread
                runCatching {
                    socket.send(DatagramPacket(hello, hello.size, peer))
                }
                Thread.sleep(300)
            }
        }
    }

    private fun startReader() {
        thread(isDaemon = true, name = "p2p-reader") {
            val buf = ByteArray(2048)
            while (running.get()) {
                val packet = DatagramPacket(buf, buf.size)
                val ok = runCatching { socket.soTimeout = 1_000; socket.receive(packet) }.isSuccess
                if (!ok) continue
                val data = packet.data
                val len = packet.length
                // 成员侧：房主的任何回包都确认了对端地址
                if (peer == null) peer = InetSocketAddress(packet.address, packet.port)
                handlePacket(data, len)
            }
            onClosed?.invoke(0, "socket closed")
        }
    }

    private fun handlePacket(data: ByteArray, len: Int) {
        // 控制帧：HELLO / HELLO-ACK（明文，仅用于打洞确认）
        val text = runCatching { String(data, 0, len, Charsets.UTF_8) }.getOrNull()
        if (text == "HELLO:$token") {
            val p = peer
            if (p != null) runCatching {
                val ack = "HELLO-ACK:$token".toByteArray()
                socket.send(DatagramPacket(ack, ack.size, p))
            }
            if (isHost) onOpen?.invoke()
            return
        }
        if (text == "HELLO-ACK:$token") {
            onOpen?.invoke()
            return
        }

        val h = UdpReliable.header(data, len) ?: return
        when (h.type) {
            UdpReliable.TYPE_ACK -> {
                pendingAcks[(h.msgId.toLong() shl 16) or h.index.toLong()] = true
                return
            }
            UdpReliable.TYPE_DATA -> {
                // 收到就 ACK（即使还没重组完）
                val p = peer
                if (p != null) runCatching {
                    val a = UdpReliable.ack(h.msgId, h.index)
                    socket.send(DatagramPacket(a, a.size, p))
                }
                val whole = reassembler.accept(h, data, len) ?: return
                val text2 = runCatching { String(whole, Charsets.UTF_8) }.getOrNull() ?: return
                RoomWire.decode(text2)?.let { onMessage?.invoke(it) }
            }
        }
    }

    override fun send(message: RoomWire): Boolean {
        val p = peer ?: return false
        val payload = message.encode().toByteArray()
        val msgId = UdpReliable.nextMsgId()
        val chunks = UdpReliable.fragment(msgId, payload)
        for (chunk in chunks) {
            var sent = false
            repeat(UdpReliable.MAX_RETRY) { attempt ->
                if (sent) return@repeat
                runCatching { socket.send(DatagramPacket(chunk, chunk.size, p)) }
                val deadline = System.currentTimeMillis() + UdpReliable.RETRY_INTERVAL_MS
                while (System.currentTimeMillis() < deadline) {
                    val h = UdpReliable.header(chunk, chunk.size) ?: return@repeat
                    if (pendingAcks[(h.msgId.toLong() shl 16) or h.index.toLong()] == true) {
                        sent = true
                        return@repeat
                    }
                    Thread.sleep(20)
                }
            }
        }
        return true
    }

    override fun close() {
        running.set(false)
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "P2pLink"

        /** 房主建房：绑定随机 UDP 端口。 */
        fun host(token: String): P2pLink =
            P2pLink(DatagramSocket(0), token, isHost = true)

        /** 成员加入。 */
        fun join(token: String): P2pLink =
            P2pLink(DatagramSocket(0), token, isHost = false)

        /** 解析房间码 `IP:端口#令牌`。 */
        fun parseCode(code: String): Triple<String, Int, String>? {
            val main = code.substringBefore('#')
            val token = code.substringAfter('#', "")
            if (token.isBlank()) return null
            val ip = main.substringBeforeLast(':', "")
            val port = main.substringAfterLast(':', "").toIntOrNull() ?: return null
            if (ip.isBlank() || port <= 0) return null
            return Triple(ip, port, token)
        }

        fun newToken(): String = (100000..999999).random().toString()
    }
}
