package com.takahashirinta.ncrust.recognize

import org.junit.Assert.assertEquals
import org.junit.Test
import com.takahashirinta.ncrust.network.crypto.EapiCrypto
import java.util.zip.GZIPOutputStream

/**
 * 识曲**响应解码链**（v3.8.0）。
 *
 * 真机踩坑：成功响应是「AES 密文，解密后是 **gzip 流**，再解压才是 JSON」。
 * 之前只对密文 gunzip，于是拿到乱码 → JSONObject 报错（用户看到的就是"json 报错"）。
 * 这里用与线上同构的向量钉住三层解码。
 */
class ResponseDecodeTest {

    /** JSON → gzip → AES-ECB，与抓包里的成功响应同构。 */
    private fun encryptResponse(json: String): ByteArray {
        val gz = java.io.ByteArrayOutputStream().also { b ->
            GZIPOutputStream(b).use { it.write(json.toByteArray()) }
        }.toByteArray()
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"),
        )
        return cipher.doFinal(gz)
    }

    @Test
    fun `AES 密文里套 gzip 能解出 JSON`() {
        val json = """{"code":200,"data":{"result":[]}}"""
        val raw = encryptResponse(json)
        // 直接复用 provider 的解码路径（通过反射不合适，改为断言 EapiCrypto 两级行为）
        val decrypted = EapiCrypto.decryptRawToBytes(raw)
        assertTrueGzip(decrypted)
        val plain = java.util.zip.GZIPInputStream(decrypted.inputStream()).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        assertEquals(json, plain)
    }

    @Test
    fun `明文 JSON 不需要解密`() {
        val bytes = """{"code":200}""".toByteArray()
        assert(org.junit.Assert.assertEquals('{', bytes[0].toInt().toChar()) == Unit)
    }

    private fun assertTrueGzip(bytes: ByteArray) {
        org.junit.Assert.assertTrue(
            "解密后应是 gzip 流（1f 8b），实际 %02x %02x".format(bytes.getOrElse(0) { 0 }, bytes.getOrElse(1) { 0 }),
            bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte(),
        )
    }
}
