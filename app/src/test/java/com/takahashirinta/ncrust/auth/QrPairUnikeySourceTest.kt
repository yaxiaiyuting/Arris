package com.takahashirinta.ncrust.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 局域网交接（`QrPair`）在「unikey 来源改走 eapi + PC 身份」之后仍然成立。
 *
 * 交接协议本身没动：它只认二维码内容里的 `codekey`（unikey）与最终那份 cookie。
 * 这里锁住的就是这条连接点 —— 新的 PC 形态二维码（不带 chainId）依旧能解析出 unikey。
 */
class QrPairUnikeySourceTest {

    @Test
    fun `pc form qr content yields the unikey`() {
        val content = QrLoginProtocol.qrContent("UNIKEY123")

        assertEquals("UNIKEY123", QrPair.unikeyFromQrContent(content))
    }

    @Test
    fun `handoff payloads carry unikey challenge and a verifiable proof`() {
        val unikey = QrPair.unikeyFromQrContent(QrLoginProtocol.qrContent("UNIKEY123"))!!
        val secret = "s3cret"

        val request = QrPair.parseRequest(QrPair.requestPayload(unikey, "c0ffee"))!!
        assertEquals(unikey, request.unikey)
        assertEquals("c0ffee", request.challenge)

        // 应答必须带 HMAC 证明：知道 secret 的一方能验过，不知道的（伪造者）不行。
        val response = QrPair.parseResponse(QrPair.responsePayload(unikey, 47821, secret, request.challenge))!!
        assertEquals(unikey, response.unikey)
        assertEquals(47821, response.tcpPort)
        assertEquals(QrPair.proof(secret, unikey, request.challenge, 47821), response.proof)
        assertTrue(!QrPair.verifyProof(QrPair.proof("other", unikey, request.challenge, 47821), response.proof))

        // 非本协议的广播不能被当成本次交接。
        assertNull(QrPair.parseRequest("NCRUSTPAIR1?UNIKEY123"))
        // 二维码里没有 arris 参数 ⇒ 没有配对密钥（旧版/纯官方二维码）。
        assertNull(QrPair.secretFromQrContent(QrLoginProtocol.qrContent("UNIKEY123")))
        assertEquals("abc", QrPair.secretFromQrContent(QrPair.withSecret(QrLoginProtocol.qrContent("UNIKEY123"), "abc")))
    }

    @Test
    fun `cookie survives the handoff encryption`() {
        val unikey = "UNIKEY123"
        val cookie = "MUSIC_U=abc; __csrf=xyz"

        val secret = "pair-secret"
        assertEquals(cookie, QrPair.decrypt(secret, unikey, QrPair.encrypt(secret, unikey, cookie)))
        // 换 unikey 或换 secret（= 换密钥）都解不开：伪造者拿到广播里的 unikey 也不够。
        assertNull(QrPair.decrypt(secret, "OTHERKEY", QrPair.encrypt(secret, unikey, cookie)))
        assertNull(QrPair.decrypt("other-secret", unikey, QrPair.encrypt(secret, unikey, cookie)))
    }
    @Test fun `well padded non cookie plaintext is rejected`() {
        assertNull(QrPair.decrypt("s", "UNIKEY123", QrPair.encrypt("s", "UNIKEY123", "not a session cookie")))
    }
    @Test fun `valid padding cannot hide malformed utf8`() {
        val key=javax.crypto.spec.SecretKeySpec(java.security.MessageDigest.getInstance("SHA-256").digest("s|UNIKEY123".toByteArray()).copyOf(16),"AES")
        val iv=ByteArray(16)
        val cipher=javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,key,javax.crypto.spec.IvParameterSpec(iv))
        val malformed="MUSIC_U=".toByteArray()+byteArrayOf(0xc3.toByte(),0x28)
        assertNull(QrPair.decrypt("s", "UNIKEY123", iv+cipher.doFinal(malformed)))
    }
}
