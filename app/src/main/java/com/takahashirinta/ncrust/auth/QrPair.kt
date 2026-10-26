package com.takahashirinta.ncrust.auth

import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 方案 A「一码二用」的局域网配对协议与加密。
 *
 * 平板显示官方登录二维码（内容仍是标准 `music.163.com/login?codekey=<unikey>`）：
 *  - 官方 ncm App 扫 → 官方确认 unikey → 平板轮询拿到 cookie，走官方登录；
 *  - Arris 手机扫 → 解析出 unikey，经局域网把本机会话 cookie 回传给平板。
 *
 * 二维码本身不含平板地址：手机用 UDP 广播按 unikey 询问，平板应答自己的 IP+TCP 端口。
 * 安全上以 unikey（屏幕可见的一次性短时密钥）派生 AES-128 加密 cookie，平板仅在
 * 正在展示该 unikey 的二维码期间监听，成功后立即停止。
 */
object QrPair {
    const val UDP_PORT = 47_821

    /**
     * v2 协议（S19 修复）。
     *
     * v1 的问题：发现报文把 `unikey` 明文广播，而 AES 密钥**只由 unikey 派生** ——
     * 局域网里能抢先应答的伪造者拿到 unikey 就能解出回传的 cookie。
     *
     * v2 起：
     *  - 二维码 URL 追加 `&arris=<随机 32 字节 hex>`（官方 App 忽略未知参数，兼容不变），
     *    这个 `pairSecret` **不进广播**；
     *  - 手机广播 `unikey:challenge`（challenge 每次随机）；
     *  - 平板应答带 `HMAC(pairSecret, unikey|challenge|port)`，手机**先验签再连**；
     *    没有 secret 的伪造者算不出这个证明，也解不开回传（密钥 = SHA256(secret|unikey)）。
     *  - 二维码里没有 `arris` 参数（旧版平板 / 纯官方二维码）⇒ 手机**拒绝回传**，
     *    不做"降级回 v1"的静默回退。
     */
    private const val REQ_PREFIX = "NCRUSTPAIR2?"
    private const val RESP_PREFIX = "NCRUSTPAIR2!"

    const val SECRET_PARAM = "arris"

    data class Request(val unikey: String, val challenge: String)

    data class Response(val unikey: String, val tcpPort: Int, val proof: String)

    fun requestPayload(unikey: String, challenge: String) = "$REQ_PREFIX$unikey:$challenge"

    fun responsePayload(unikey: String, tcpPort: Int, secret: String, challenge: String): String {
        val proof = proof(secret, unikey, challenge, tcpPort)
        return "$RESP_PREFIX$unikey:$tcpPort:$proof"
    }

    /** 从 UDP 请求解析 (unikey, challenge)；非本协议返回 null。 */
    fun parseRequest(payload: String): Request? {
        if (!payload.startsWith(REQ_PREFIX)) return null
        val rest = payload.removePrefix(REQ_PREFIX)
        val idx = rest.lastIndexOf(':')
        if (idx <= 0) return null
        val unikey = rest.substring(0, idx)
        val challenge = rest.substring(idx + 1)
        if (unikey.isEmpty() || challenge.isEmpty()) return null
        return Request(unikey, challenge)
    }

    /** 解析应答为 (unikey, tcpPort, proof)；格式不符返回 null。 */
    fun parseResponse(payload: String): Response? {
        if (!payload.startsWith(RESP_PREFIX)) return null
        val parts = payload.removePrefix(RESP_PREFIX).split(':')
        if (parts.size != 3) return null
        val port = parts[1].toIntOrNull() ?: return null
        if (parts[0].isEmpty() || parts[2].isEmpty()) return null
        return Response(parts[0], port, parts[2])
    }

    /** HMAC-SHA256(secret, unikey|challenge|port) 的 hex。 */
    fun proof(secret: String, unikey: String, challenge: String, tcpPort: Int): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("$unikey|$challenge|$tcpPort".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 常量时间比较，避免用响应时间猜 proof。 */
    fun verifyProof(expected: String, actual: String): Boolean {
        if (expected.length != actual.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor actual[i].code)
        return diff == 0
    }

    fun newChallenge(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun newSecret(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun key(secret: String, unikey: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$secret|$unikey".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest.copyOf(16), "AES")
    }

    /** AES-128-CBC，随机 IV 前置。 */
    fun encrypt(secret: String, unikey: String, plain: String): ByteArray {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key(secret, unikey), IvParameterSpec(iv))
        return iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(secret: String, unikey: String, data: ByteArray): String? = runCatching {
        if (data.size <= 16) return null
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key(secret, unikey), IvParameterSpec(data.copyOfRange(0, 16)))
        // CBC padding is not an integrity check: a wrong key can occasionally have valid padding.
        // Keep the wire protocol, but refuse malformed UTF-8 and non-session plaintext.
        val bytes = cipher.doFinal(data.copyOfRange(16, data.size))
        val plain = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        plain.takeIf { NeteaseCookie.hasSession(it) }
    }.getOrNull()

    /** 把配对密钥附加到官方二维码链接上（官方 App 会忽略未知参数）。 */
    fun withSecret(qrContent: String, secret: String): String =
        if (qrContent.contains("$SECRET_PARAM=")) qrContent
        else "$qrContent" + if (qrContent.contains('?')) "&$SECRET_PARAM=$secret" else "?$SECRET_PARAM=$secret"

    /** 从二维码内容取配对密钥；没有（旧版/纯官方）返回 null。 */
    fun secretFromQrContent(content: String): String? = runCatching {
        URL(content.trim()).query
            ?.split('&')
            ?.mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }
            ?.firstOrNull { it[0] == SECRET_PARAM }
            ?.get(1)
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** 从二维码内容解析 unikey：支持标准登录链接，也兼容裸 unikey。 */
    fun unikeyFromQrContent(content: String): String? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("http")) return trimmed.takeIf { it.isNotEmpty() }
        return runCatching {
            URL(trimmed).query
                ?.split('&')
                ?.mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }
                ?.firstOrNull { it[0] == "codekey" }
                ?.get(1)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
