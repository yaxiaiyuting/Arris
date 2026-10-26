package com.takahashirinta.ncrust.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 手机侧配对客户端：按 unikey UDP 广播发现平板，再把本机会话 cookie 加密回传。
 * 成功返回 true；未发现设备/连接失败/发送失败返回 false。
 */
object QrPairClient {

    /**
     * 回传本机会话 cookie。
     *
     * [pairSecret] 来自二维码里的 `arris=` 参数：没有它就无法验证应答方、
     * 也无法派生可用的密钥 ⇒ **直接拒绝回传**（不静默降级到 v1 明文密钥方案）。
     */
    suspend fun sendCookie(unikey: String, cookie: String, pairSecret: String?): Boolean =
        withContext(Dispatchers.IO) {
        if (pairSecret.isNullOrBlank()) {
            android.util.Log.w("QrPairClient", "refuse to pair: QR carries no arris secret")
            return@withContext false
        }
        val target = discover(unikey, pairSecret) ?: return@withContext false
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(target.first, target.second), 4_000)
                socket.soTimeout = 4_000
                val payload = QrPair.encrypt(pairSecret, unikey, cookie)
                DataOutputStream(socket.getOutputStream()).use { out ->
                    out.writeInt(payload.size)
                    out.write(payload)
                    out.flush()
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun discover(unikey: String, pairSecret: String): Pair<String, Int>? {
        val socket = runCatching { DatagramSocket() }.getOrNull() ?: return null
        socket.broadcast = true
        socket.soTimeout = 400
        val challenge = QrPair.newChallenge()
        val request = QrPair.requestPayload(unikey, challenge).toByteArray(Charsets.UTF_8)
        runCatching { InetAddress.getByName("255.255.255.255") }.getOrNull()?.let { broadcast ->
            repeat(3) {
                runCatching { socket.send(DatagramPacket(request, request.size, broadcast, QrPair.UDP_PORT)) }
            }
        }
        val deadline = System.currentTimeMillis() + 3_000
        val buf = ByteArray(512)
        while (System.currentTimeMillis() < deadline) {
            val packet = DatagramPacket(buf, buf.size)
            val received = runCatching { socket.receive(packet); true }.getOrDefault(false)
            if (!received) continue
            val parsed = QrPair.parseResponse(String(packet.data, 0, packet.length))
            if (parsed != null && parsed.unikey == unikey) {
                // 先验签：只有知道 pairSecret 的真平板才能算出这个证明。
                // 局域网里抢先应答的伪造者在这里被挡下 —— 它的 cookie 收不到也解不开。
                val expected = QrPair.proof(pairSecret, unikey, challenge, parsed.tcpPort)
                if (!QrPair.verifyProof(expected, parsed.proof)) {
                    android.util.Log.w("QrPairClient", "reject discovery reply: bad proof from ${packet.address}")
                    continue
                }
                runCatching { socket.close() }
                return packet.address.hostAddress to parsed.tcpPort
            }
        }
        runCatching { socket.close() }
        return null
    }
}
