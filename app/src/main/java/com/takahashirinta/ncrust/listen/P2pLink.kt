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
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
    private val pendingAcks = ConcurrentHashMap<Long, Boolean>()
    private val delivered = LinkedHashSet<Int>()
    private val nonce = UUID.randomUUID().toString()
    private val key = SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256")

    private data class Peer(val address: InetSocketAddress, val session: String)

    @Volatile private var peer: Peer? = null
    private var expectedHost: InetSocketAddress? = null
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
        val addr = mapped?.let { "${it.address.hostAddress}:${it.port}" } ?: "$local:$localPort"
        Log.i(TAG, "host mapped=$mappedAddress local=$local:$localPort")
        return "$addr#$token"
    }

    fun startMember(hostIp: String, hostPort: Int) {
        val host = InetSocketAddress(InetAddress.getByName(hostIp), hostPort)
        expectedHost = host
        startReader()
        thread(isDaemon = true, name = "p2p-punch") {
            val hello = seal("H2:$nonce".toByteArray(), "handshake")
            repeat(12) {
                if (!running.get()) return@thread
                sendPacket(hello, host)
                Thread.sleep(300)
            }
        }
    }

    private fun startReader() {
        thread(isDaemon = true, name = "p2p-reader") {
            val buf = ByteArray(2048)
            try {
                while (running.get()) {
                    val packet = DatagramPacket(buf, buf.size)
                    val ok = runCatching { socket.soTimeout = 1_000; socket.receive(packet) }.isSuccess
                    if (!ok) continue
                    val from = InetSocketAddress(packet.address, packet.port)
                    val bound = peer
                    if (bound != null && bound.address != from) continue
                    if (!isHost && from != expectedHost) continue
                    val bytes = packet.data.copyOf(packet.length)
                    val handshake = unseal(bytes, "handshake")
                    if (handshake != null) {
                        handleHandshake(String(handshake, Charsets.UTF_8), from)
                    } else if (bound != null) {
                        val incoming = if (isHost) "member" else "host"
                        val message = unseal(bytes, "$incoming:${bound.session}") ?: continue
                        handlePacket(message, bound)
                    }
                }
            } finally {
                onClosed?.invoke(0, "socket closed")
            }
        }
    }

    private fun handleHandshake(message: String, from: InetSocketAddress) {
        val fields = message.split(':', limit = 4)
        val memberNonce = fields.getOrNull(1)?.takeIf(::validNonce) ?: return
        when (fields.first()) {
            "H2" -> if (isHost && fields.size == 2) sendHandshake("A2:$memberNonce:$nonce", from)
            "A2" -> if (!isHost && fields.size == 3 && memberNonce == nonce) {
                val hostNonce = fields[2].takeIf(::validNonce) ?: return
                sendHandshake("F2:$nonce:$hostNonce", from)
                bindPeer(from, "$nonce:$hostNonce")
            }
            "F2" -> if (isHost && fields.size == 3 && fields[2] == nonce) {
                bindPeer(from, "$memberNonce:$nonce")
            }
        }
    }

    private fun validNonce(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private fun bindPeer(address: InetSocketAddress, session: String) {
        if (peer != null) return
        peer = Peer(address, session)
        onOpen?.invoke()
    }

    private fun sendHandshake(message: String, address: InetSocketAddress) =
        sendPacket(seal(message.toByteArray(), "handshake"), address)

    private fun sendPacket(bytes: ByteArray, address: InetSocketAddress) {
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, address)) }
    }

    private fun authenticate(bytes: ByteArray, domain: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply {
            init(key)
            update(domain.toByteArray(Charsets.UTF_8))
            update(0.toByte())
        }.doFinal(bytes).copyOf(16)

    private fun seal(bytes: ByteArray, domain: String): ByteArray = bytes + authenticate(bytes, domain)

    private fun unseal(packet: ByteArray, domain: String): ByteArray? {
        if (packet.size <= 16) return null
        val bytes = packet.copyOf(packet.size - 16)
        val tag = packet.copyOfRange(bytes.size, packet.size)
        return bytes.takeIf { MessageDigest.isEqual(tag, authenticate(it, domain)) }
    }

    private fun handlePacket(data: ByteArray, bound: Peer) {
        val h = UdpReliable.header(data, data.size) ?: return
        when (h.type) {
            UdpReliable.TYPE_ACK -> pendingAcks.replace((h.msgId.toLong() shl 16) or h.index.toLong(), true)
            UdpReliable.TYPE_DATA -> {
                if (h.count > 64) return
                val outgoing = if (isHost) "host" else "member"
                val ack = UdpReliable.ack(h.msgId, h.index)
                sendPacket(seal(ack, "$outgoing:${bound.session}"), bound.address)
                if (h.msgId in delivered) return
                val whole = reassembler.accept(h, data, data.size) ?: return
                delivered.add(h.msgId)
                if (delivered.size > 256) delivered.remove(delivered.first())
                RoomWire.decode(String(whole, Charsets.UTF_8))?.let { onMessage?.invoke(it) }
            }
        }
    }

    override fun send(message: RoomWire): Boolean {
        val bound = peer ?: return false
        val outgoing = if (isHost) "host" else "member"
        val msgId = UdpReliable.nextMsgId()
        val payload = message.encode().toByteArray()
        if (payload.size > 64 * UdpReliable.CHUNK) return false
        for ((index, chunk) in UdpReliable.fragment(msgId, payload).withIndex()) {
            val packet = seal(chunk, "$outgoing:${bound.session}")
            val ackKey = (msgId.toLong() shl 16) or index.toLong()
            pendingAcks[ackKey] = false
            try {
                for (attempt in 0 until UdpReliable.MAX_RETRY) {
                    if (!running.get() || Thread.currentThread().isInterrupted) return false
                    sendPacket(packet, bound.address)
                    val deadline = android.os.SystemClock.elapsedRealtime() + UdpReliable.RETRY_INTERVAL_MS
                    while (running.get() && android.os.SystemClock.elapsedRealtime() < deadline) {
                        if (pendingAcks[ackKey] == true) break
                        Thread.sleep(20)
                    }
                    if (pendingAcks[ackKey] == true) break
                }
                if (pendingAcks[ackKey] != true) return false
            } finally {
                pendingAcks.remove(ackKey)
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
            if (!token.matches(Regex("[0-9a-f]{32}"))) return null
            val ip = main.substringBeforeLast(':', "")
            val port = main.substringAfterLast(':', "").toIntOrNull() ?: return null
            if (ip.isBlank() || port !in 1..65535) return null
            return Triple(ip, port, token)
        }

        fun newToken(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
