/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.listen.p2p

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * 极简 STUN 客户端（RFC 5389 Binding Request）——只做一件事：问出「我这个 UDP 端口在公网上长什么样」。
 *
 * ## 为什么要它
 *
 * P2P 直连（无服务器）必须让房主知道自己的**公网映射**，才能把这个地址发给对方去打洞。
 * 完整 STUN 协议很大，我们只需要 Binding Request / Binding Success Response + XOR-MAPPED-ADDRESS。
 *
 * ## 服务器选择
 *
 * 用**国内可达**的公共 STUN（小米、腾讯、B 站等），不用 Google 的（国内基本不回应）。
 * 任意一个成功即可；全部失败就退回本机地址（同一 WiFi 下仍然能用）。
 */
object StunClient {

    /** 实测国内可用的 STUN 服务器（UDP 3478）。顺序即尝试顺序。 */
    private val SERVERS = listOf(
        "stun.miwifi.com" to 3478,
        "stun.qq.com" to 3478,
        "stun.chat.bilibili.com" to 3478,
        "stun.hitv.com" to 3478,
    )

    private const val MAGIC_COOKIE = 0x2112A442
    private const val BINDING_REQUEST = 0x0001
    private const val BINDING_SUCCESS = 0x0101
    private const val ATTR_XOR_MAPPED = 0x0020

    data class Mapped(val address: InetAddress, val port: Int) {
        override fun toString() = "${address.hostAddress}:$port"
    }

    /**
     * 用**已有的 socket** 发 STUN 查询 —— 这一点很关键：必须是同一条 socket，
     * 否则问出来的映射端口与实际收发包的端口不同，打洞必然失败。
     */
    fun queryMapped(socket: DatagramSocket, timeoutMs: Int = 1_500): Mapped? {
        for ((host, port) in SERVERS) {
            val result = runCatching { queryOne(socket, host, port, timeoutMs) }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private fun queryOne(socket: DatagramSocket, host: String, port: Int, timeoutMs: Int): Mapped? {
        val server = InetSocketAddress(InetAddress.getByName(host), port)
        val txId = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val request = buildBindingRequest(txId)
        socket.send(DatagramPacket(request, request.size, server))

        val buf = ByteArray(1024)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            socket.soTimeout = remaining
            val resp = DatagramPacket(buf, buf.size)
            runCatching { socket.receive(resp) }.getOrElse { return null }
            val parsed = parseBindingResponse(buf, resp.length, txId) ?: continue
            return parsed
        }
        return null
    }

    /** 构造 Binding Request（20 字节头）。 */
    fun buildBindingRequest(txId: ByteArray): ByteArray {
        val msg = ByteArray(20)
        msg[0] = (BINDING_REQUEST shr 8).toByte()          // type
        msg[1] = (BINDING_REQUEST and 0xFF).toByte()
        msg[2] = 0; msg[3] = 0                              // length = 0
        msg[4] = ((MAGIC_COOKIE shr 24) and 0xFF).toByte()
        msg[5] = ((MAGIC_COOKIE shr 16) and 0xFF).toByte()
        msg[6] = ((MAGIC_COOKIE shr 8) and 0xFF).toByte()
        msg[7] = (MAGIC_COOKIE and 0xFF).toByte()
        txId.copyInto(msg, 8, 0, 12)
        return msg
    }

    /**
     * 解析 Binding Success Response，取 XOR-MAPPED-ADDRESS。
     *
     * 纯函数（便于用固定报文单测）：`txId` 必须与请求一致，否则丢弃（防串包）。
     */
    fun parseBindingResponse(buf: ByteArray, len: Int, txId: ByteArray): Mapped? {
        if (len < 20) return null
        val type = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        if (type != BINDING_SUCCESS) return null
        for (i in 8 until 20) {
            if (buf[i] != txId[i - 8]) return null
        }
        val msgLen = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        var pos = 20
        val end = (20 + msgLen).coerceAtMost(len)
        while (pos + 4 <= end) {
            val attrType = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            val attrLen = ((buf[pos + 2].toInt() and 0xFF) shl 8) or (buf[pos + 3].toInt() and 0xFF)
            val valuePos = pos + 4
            if (valuePos + attrLen > end) break
            if (attrType == ATTR_XOR_MAPPED && attrLen >= 8) {
                val family = buf[valuePos + 1].toInt() and 0xFF
                val xorPort = ((buf[valuePos + 2].toInt() and 0xFF) shl 8) or (buf[valuePos + 3].toInt() and 0xFF)
                val port = xorPort xor (MAGIC_COOKIE shr 16)
                if (family == 0x01) {
                    val ip = ByteArray(4) { i ->
                        (buf[valuePos + 4 + i].toInt() xor ((MAGIC_COOKIE shr (24 - 8 * i)) and 0xFF)).toByte()
                    }
                    return Mapped(InetAddress.getByAddress(ip), port)
                }
                return null // IPv6 暂不支持：国内家用/移动网络的公网 v6 打洞成功率另说
            }
            pos = valuePos + attrLen + ((4 - attrLen % 4) % 4) // 4 字节对齐
        }
        return null
    }
}
