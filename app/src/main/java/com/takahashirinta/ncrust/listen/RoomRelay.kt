/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.listen

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 局域网中继（v3.8.0 · 一起听）。**纯 JDK，无第三方依赖。**
 *
 * ## 它只做一件事：把一行 JSON 转给房间里的其他人
 *
 * 没有账号、没有鉴权、不碰音频、不存状态 —— 房主才是权威（见 [RoomEngine]）。
 * 这样一来：服务端逻辑 ~100 行、可单测、断线只影响自己，且**不需要任何公网基础设施**。
 *
 * ## 房间码是什么
 *
 * `房间码 = 房主局域网 IP + 端口`。局域网内成员直接连；跨网需要自己解决（内网穿透/VPN），
 * 本轮不做，也不假装能做。
 */
class RoomRelayServer(
    /** 监听端口；0 = 由系统分配。 */
    port: Int = 0,
) {
    private val socket = ServerSocket(port)
    private val running = AtomicBoolean(true)
    private val clients = ConcurrentHashMap<String, Client>()

    val listenPort: Int get() = socket.localPort

    /** 供 UI 展示的连接串（局域网 IPv4 + 端口）；找不到可用地址时返回 null。 */
    fun advertiseAddress(): String? = localIpv4()?.let { "$it:$listenPort" }

    /** 收到的消息（含发送者 id）。 */
    @Volatile
    var onMessage: ((fromUserId: String, message: RoomWire) -> Unit)? = null

    @Volatile
    var onJoin: ((userId: String) -> Unit)? = null

    @Volatile
    var onLeave: ((userId: String) -> Unit)? = null

    fun start() {
        thread(isDaemon = true, name = "room-relay-accept") {
            while (running.get()) {
                val accept = runCatching { socket.accept() }.getOrNull() ?: break
                val client = Client(accept)
                clients[client.userId] = client
                client.start()
                onJoin?.invoke(client.userId)
            }
        }
    }

    fun broadcast(message: RoomWire, exceptUserId: String? = null) {
        val line = message.encode()
        clients.forEach { (id, c) ->
            if (id != exceptUserId) c.send(line)
        }
    }

    fun sendTo(userId: String, message: RoomWire) {
        clients[userId]?.send(message.encode())
    }

    fun close() {
        running.set(false)
        runCatching { socket.close() }
        clients.values.forEach { it.close() }
        clients.clear()
    }

    private inner class Client(private val socket: Socket) {
        val userId: String = "u${socket.port}-${System.nanoTime() % 100_000}"
        private val writer: BufferedWriter =
            BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        private val reader: BufferedReader =
            BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

        fun start() {
            send(RoomWire.Hello(userId).encode())
            thread(isDaemon = true, name = "room-relay-client-$userId") {
                while (running.get()) {
                    val line = runCatching { reader.readLine() }.getOrNull() ?: break
                    val message = RoomWire.decode(line) ?: continue
                    onMessage?.invoke(userId, message)
                }
                clients.remove(userId)
                onLeave?.invoke(userId)
            }
        }

        fun send(line: String) {
            runCatching {
                synchronized(writer) {
                    writer.write(line)
                    writer.newLine()
                    writer.flush()
                }
            }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}

/** 成员侧连接。 */
class RoomRelayClient(
    private val host: String,
    private val port: Int,
) {
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private val running = AtomicBoolean(true)

    @Volatile var onMessage: ((RoomWire) -> Unit)? = null
    @Volatile var onClosed: (() -> Unit)? = null

    /** 连接并开始读；失败抛 IOException（调用方决定怎么提示）。 */
    fun connect() {
        val s = Socket(host, port)
        socket = s
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        thread(isDaemon = true, name = "room-relay-reader") {
            while (running.get()) {
                val line = runCatching { reader.readLine() }.getOrNull() ?: break
                RoomWire.decode(line)?.let { onMessage?.invoke(it) }
            }
            running.set(false)
            onClosed?.invoke()
        }
    }

    fun send(message: RoomWire) {
        runCatching {
            synchronized(this) {
                writer?.write(message.encode())
                writer?.newLine()
                writer?.flush()
            }
        }
    }

    fun close() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        writer = null
    }
}
