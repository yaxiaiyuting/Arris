/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.listen

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 房间连接的两条传输（v3.8.1）。
 *
 * ## 为什么要有两条
 *
 * | 传输 | 需要什么 | 适用场景 |
 * |---|---|---|
 * | [RoomLink.Wss] | 一台公网可达的中继（自建/自定义域名） | 异地一起听 |
 * | [RoomLink.Lan] | **什么都不需要**，同一 WiFi/热点即可 | 朋友就在旁边 |
 *
 * 国内网络对 `*.workers.dev` 是 **SNI 阻断**（ICMP 通、TLS ClientHello 被 RST，实测），
 * 所以默认那台 Cloudflare Worker 在不开代理时基本连不上。局域网这条路不受影响，
 * 且协议完全一致 —— 只是把"一条 WebSocket"换成"一条 TCP 长连接，每行一条 JSON"。
 */
sealed interface RoomLink {

    fun send(message: RoomWire): Boolean
    fun close()

    var onMessage: ((RoomWire) -> Unit)?
    var onClosed: ((code: Int, reason: String) -> Unit)?
    var onOpen: (() -> Unit)?

    /** 公网：Cloudflare Worker / 自建 WSS 中继。 */
    class Wss(
        private val url: String,
        fixedIps: List<String> = emptyList(),
    ) : RoomLink {
        private val inner = RoomRelayWs(url, fixedIps = fixedIps)
        override var onMessage: ((RoomWire) -> Unit)?
            get() = inner.onMessage
            set(v) { inner.onMessage = v }
        override var onClosed: ((code: Int, reason: String) -> Unit)?
            get() = inner.onClosed
            set(v) { inner.onClosed = v }
        override var onOpen: (() -> Unit)?
            get() = inner.onOpen
            set(v) { inner.onOpen = v }

        fun connect() = inner.connect()
        override fun send(message: RoomWire): Boolean = inner.send(message)
        override fun close() = inner.close()
    }

    /**
     * 局域网：房主本机监听一个端口，成员直连。
     *
     * 房间码就是 `IP:端口`（房主界面上直接显示，可念给对方或扫码）。
     */
    class Lan private constructor(
        private val server: ServerSocket?,
        private val socket: Socket?,
    ) : RoomLink {

        private val running = AtomicBoolean(true)
        private var writer: BufferedWriter? = null
        private val peers = java.util.concurrent.ConcurrentHashMap<Long, BufferedWriter>()
        private var peerSeq = 0L

        override var onMessage: ((RoomWire) -> Unit)? = null
        override var onClosed: ((code: Int, reason: String) -> Unit)? = null
        override var onOpen: (() -> Unit)? = null

        /** 房主侧：本机监听端口（0 = 系统分配）。 */
        val listenPort: Int get() = server?.localPort ?: -1

        /**
         * 房主侧的连接串：`IP:端口`。
         *
         * 取值顺序**刻意**是：Tailscale 的 `100.64.0.0/10` 优先，其次局域网 `192.168/10/172.16`。
         * 原因：Tailscale 地址**跨网可达**（对方在另一个城市也能连），而局域网地址只在同一
         * WiFi 下有效。两者同时存在时，房主想把"能用的那个"发出去，而不是"看起来像内网的那个"。
         *
         * 注意：Tailscale 需要双方各自安装它的客户端（免费）；这是它提供虚拟网卡的代价，
         * 不是我们这个 App 能替掉的。
         */
        fun address(): String? = preferredAddress(localIpv4s())?.let { "$it:$listenPort" }

        fun startHost() {
            val srv = server ?: return
            onOpen?.invoke()
            thread(isDaemon = true, name = "lan-room-accept") {
                while (running.get()) {
                    val client = runCatching { srv.accept() }.getOrNull() ?: break
                    val id = peerSeq++
                    val w = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))
                    peers[id] = w
                    thread(isDaemon = true, name = "lan-room-peer-$id") {
                        val r = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                        while (running.get()) {
                            val line = runCatching { r.readLine() }.getOrNull() ?: break
                            RoomWire.decode(line)?.let { onMessage?.invoke(it) }
                        }
                        peers.remove(id)
                        onClosed?.invoke(0, "peer $id left")
                    }
                }
            }
        }

        fun startMember() {
            val s = socket ?: return
            val w = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            writer = w
            onOpen?.invoke()
            thread(isDaemon = true, name = "lan-room-reader") {
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (running.get()) {
                    val line = runCatching { r.readLine() }.getOrNull() ?: break
                    RoomWire.decode(line)?.let { onMessage?.invoke(it) }
                }
                running.set(false)
                onClosed?.invoke(0, "closed")
            }
        }

        override fun send(message: RoomWire): Boolean {
            val line = message.encode()
            return if (writer != null) {
                runCatching { synchronized(this) { writer!!.write(line); writer!!.newLine(); writer!!.flush() } }.isSuccess
            } else {
                var any = false
                peers.values.forEach { w ->
                    any = runCatching { synchronized(w) { w.write(line); w.newLine(); w.flush() } }.isSuccess || any
                }
                any
            }
        }

        override fun close() {
            running.set(false)
            runCatching { server?.close() }
            runCatching { socket?.close() }
            peers.clear()
            writer = null
        }

        companion object {
            /** 房主：监听所有网卡的随机端口。 */
            fun host(): Lan = Lan(ServerSocket(0).also { it.reuseAddress = true }, null)

            /** 成员：连到 `ip:port`。 */
            fun join(ip: String, port: Int): Lan = Lan(
                null,
                Socket().apply { connect(InetSocketAddress(ip, port), 5_000) },
            )

            /** 纯函数：从本机 IPv4 列表里挑最适合分享的那个（便于单测）。 */
            fun preferredAddress(all: List<String>): String? {
                fun isTailscale(ip: String): Boolean {
                    val p = ip.split('.')
                    if (p.size != 4) return false
                    val a = p[0].toIntOrNull() ?: return false
                    val b = p[1].toIntOrNull() ?: return false
                    return a == 100 && b in 64..127   // 100.64.0.0/10
                }
                return all.firstOrNull(::isTailscale)
                    ?: all.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
            }

            private fun localIpv4s(): List<String> = runCatching {
                java.net.NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<java.net.Inet4Address>()
                    .mapNotNull { it.hostAddress }
            }.getOrDefault(emptyList())
        }
    }

    companion object {
        private const val TAG = "RoomLink"
        fun log(msg: String) = Log.i(TAG, msg)
    }
}
