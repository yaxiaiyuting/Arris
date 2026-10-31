package com.takahashirinta.ncrust.listen.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P2P 直连的两个纯逻辑件（v3.8.1）：STUN 报文解析 与 UDP 可靠层。
 *
 * 真机打洞没法在单测里跑（需要真实 NAT），所以把**能确定性验证的部分**全部钉住：
 * STUN 响应解析（XOR-MAPPED-ADDRESS 的异或规则最容易写错）、分片/重组、ACK 包头。
 */
class P2pProtocolTest {

    private fun bindingResponse(txId: ByteArray, ip: ByteArray, port: Int): ByteArray {
        val magic = 0x2112A442
        val out = java.io.ByteArrayOutputStream()
        val header = java.nio.ByteBuffer.allocate(20)
        header.putShort(0x0101)
        header.putShort(12)
        header.putInt(magic)
        header.put(txId)
        out.write(header.array())
        val attr = java.nio.ByteBuffer.allocate(12)
        attr.putShort(0x0020)
        attr.putShort(8)
        attr.put(0)
        attr.put(1)
        attr.putShort((port xor (magic shr 16)).toShort())
        for (i in 0 until 4) {
            attr.put((ip[i].toInt() xor ((magic shr (24 - 8 * i)) and 0xFF)).toByte())
        }
        out.write(attr.array())
        return out.toByteArray()
    }

    @Test
    fun `STUN 解析出 XOR-MAPPED 地址`() {
        val tx = ByteArray(12) { it.toByte() }
        val ip = byteArrayOf(203.toByte(), 0, 113, 7)
        val packet = bindingResponse(tx, ip, 54321)
        val mapped = StunClient.parseBindingResponse(packet, packet.size, tx)
        assertEquals("203.0.113.7", mapped?.address?.hostAddress)
        assertEquals(54321, mapped?.port)
    }

    @Test
    fun `事务号不匹配的响应被丢弃`() {
        val tx = ByteArray(12) { it.toByte() }
        val other = ByteArray(12) { (it + 1).toByte() }
        val packet = bindingResponse(tx, byteArrayOf(1, 2, 3, 4), 1000)
        assertNull("串包必须丢", StunClient.parseBindingResponse(packet, packet.size, other))
    }

    @Test
    fun `Binding Request 头正确`() {
        val tx = ByteArray(12) { 7 }
        val req = StunClient.buildBindingRequest(tx)
        assertEquals(20, req.size)
        assertEquals(0x0001, ((req[0].toInt() and 0xFF) shl 8) or (req[1].toInt() and 0xFF))
        assertEquals(0x2112A442.toInt(), java.nio.ByteBuffer.wrap(req, 4, 4).int)
        assertArrayEquals(tx, req.copyOfRange(8, 20))
    }

    @Test
    fun `分片与重组往返一致`() {
        val payload = ByteArray(3 * UdpReliable.CHUNK + 17) { (it % 251).toByte() }
        val msgId = UdpReliable.nextMsgId()
        val chunks = UdpReliable.fragment(msgId, payload)
        assertEquals(4, chunks.size)

        val re = UdpReliable.Reassembler()
        var whole: ByteArray? = null
        for (c in chunks.reversed()) {
            val h = UdpReliable.header(c, c.size)!!
            val r = re.accept(h, c, c.size)
            if (r != null) whole = r
        }
        assertArrayEquals(payload, whole)
    }

    @Test
    fun `ACK 包头能解析回原 msgId 与序号`() {
        val msgId = 0x123456
        val ack = UdpReliable.ack(msgId, 3)
        val h = UdpReliable.header(ack, ack.size)!!
        assertEquals(UdpReliable.TYPE_ACK, h.type)
        assertEquals(msgId, h.msgId)
        assertEquals(3, h.index)
    }

    @Test
    fun `超短包不会崩`() {
        assertNull(UdpReliable.header(byteArrayOf(1, 2), 2))
    }

    @Test
    fun `房间码解析 IP 端口与令牌`() {
        val parsed = com.takahashirinta.ncrust.listen.P2pLink.parseCode("203.0.113.7:54321#123456")
        assertEquals("203.0.113.7", parsed?.first)
        assertEquals(54321, parsed?.second)
        assertEquals("123456", parsed?.third)
        assertNull("缺令牌应拒绝", com.takahashirinta.ncrust.listen.P2pLink.parseCode("203.0.113.7:54321"))
        assertNull("缺端口应拒绝", com.takahashirinta.ncrust.listen.P2pLink.parseCode("203.0.113.7#123456"))
    }
}
