/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.listen.p2p

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * UDP 上的一条**精简可靠层**（v3.8.1 · P2P 直连）。
 *
 * ## 为什么需要它
 *
 * 打洞成功后拿到的是 UDP 通路，而我们的房间协议是"一行一条 JSON"的可变长消息，
 * 会超过 MTU、会丢包、会乱序。这里只实现刚好够用的部分：
 *
 * - **分片**：每条消息切成 ≤1100 字节的块（留出 IP/UDP 头与包头余量）；
 * - **确认与重传**：接收方按 (msgId, 块序号) 回 ACK，发送方对未确认的块重传，
 *   最多 `MAX_RETRY` 次；整体超过 `SEND_TIMEOUT_MS` 视为该条消息丢弃；
 * - **重组**：按 msgId 收集块，齐了以后按序拼回整条消息。
 *
 * 不做的：拥塞控制、窗口流控、有序消息流、加密。
 * 房间协议本身是**小消息 + 低频**（每秒 1 条快照、偶发操作），够用。
 */
object UdpReliable {

    /** 单块负载上限（保守值，避免 IP 分片）。 */
    const val CHUNK = 1100

    /** 包头：type(1) + msgId(4) + idx(2) + count(2) = 9 字节。 */
    const val HEADER = 9

    const val TYPE_DATA = 1
    const val TYPE_ACK = 2

    const val MAX_RETRY = 4
    const val SEND_TIMEOUT_MS = 4_000L
    const val RETRY_INTERVAL_MS = 250L

    /** 把一条消息切成若干块（每块都带完整包头）。纯函数，便于单测。 */
    fun fragment(msgId: Int, payload: ByteArray): List<ByteArray> {
        val count = ((payload.size + CHUNK - 1) / CHUNK).coerceAtLeast(1)
        require(count <= 0xFFFF) { "message too large: $count chunks" }
        return (0 until count).map { idx ->
            val from = idx * CHUNK
            val to = (from + CHUNK).coerceAtMost(payload.size)
            val body = payload.copyOfRange(from, to)
            val packet = ByteBuffer.allocate(HEADER + body.size)
            packet.put(TYPE_DATA.toByte())
            packet.putInt(msgId)
            packet.putShort(idx.toShort())
            packet.putShort(count.toShort())
            packet.put(body)
            packet.array()
        }
    }

    /** 解析包头；太短返回 null。 */
    fun header(packet: ByteArray, len: Int): Header? {
        if (len < HEADER) return null
        val bb = ByteBuffer.wrap(packet, 0, HEADER)
        val type = bb.get().toInt()
        val msgId = bb.int
        val idx = bb.short.toInt() and 0xFFFF
        val count = bb.short.toInt() and 0xFFFF
        // DATA 帧要求 idx < count；ACK 帧只带 (msgId, idx)，count 恒为 1，不能套同一条判据
        if (count <= 0) return null
        if (type == TYPE_DATA && idx >= count) return null
        return Header(type, msgId, idx, count)
    }

    data class Header(val type: Int, val msgId: Int, val index: Int, val count: Int)

    /** 重组器：按 msgId 收集块；齐了返回整条消息并清理。 */
    class Reassembler {
        private data class Partial(val count: Int, val parts: Array<ByteArray?>, var updatedAt: Long)

        private val partial = ConcurrentHashMap<Int, Partial>()

        fun accept(h: Header, packet: ByteArray, len: Int): ByteArray? {
            val body = packet.copyOfRange(HEADER, len)
            val entry = partial.getOrPut(h.msgId) {
                Partial(h.count, arrayOfNulls(h.count), System.currentTimeMillis())
            }
            if (entry.count != h.count) return null
            entry.parts[h.index] = body
            entry.updatedAt = System.currentTimeMillis()
            if (entry.parts.any { it == null }) {
                // 顺手清理超过 10 秒没补齐的半成品，避免内存泄漏
                val stale = partial.entries.filter { System.currentTimeMillis() - it.value.updatedAt > 10_000 }
                stale.forEach { partial.remove(it.key) }
                return null
            }
            partial.remove(h.msgId)
            val out = ByteArrayOutputStream()
            entry.parts.forEach { out.write(it!!) }
            return out.toByteArray()
        }
    }

    /** ACK 包：告诉对方"这个 msgId 的这块我收到了"。 */
    fun ack(msgId: Int, index: Int): ByteArray = ByteBuffer.allocate(HEADER)
        .put(TYPE_ACK.toByte())
        .putInt(msgId)
        .putShort(index.toShort())
        .putShort(1)
        .array()

    fun nextMsgId(): Int = msgIdSeq.incrementAndGet()
    private val msgIdSeq = AtomicInteger(1)
}
