package com.takahashirinta.ncrust.recognize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 识曲 · WAV 封装与网易云识曲响应解析（v3.8.0）。不发真实请求。 */
class ProvidersTest {

    @Test
    fun `WAV 头字段正确`() {
        val pcm = ShortArray(1600) { (it % 100).toShort() }
        val wav = WavEncoder.encode(pcm, 16_000)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals("data", String(wav, 36, 4))
        assertEquals(44 + pcm.size * 2, wav.size)
        val dataLen = (wav[40].toInt() and 0xFF) or ((wav[41].toInt() and 0xFF) shl 8) or
            ((wav[42].toInt() and 0xFF) shl 16) or ((wav[43].toInt() and 0xFF) shl 24)
        assertEquals(pcm.size * 2, dataLen)
    }

    @Test
    fun `网易云命中解析`() {
        // 真实形状：data.result.song（2026-10-30 真机复现的响应外壳）
        val json = """
            {"code":200,"data":{"type":0,"queryId":"q","mv":null,
              "result":{"song":{"id":186016,"name":"晴天","duration":269000,
                "artists":[{"name":"周杰伦"}],"album":{"name":"叶惠美"}}}}}
        """.trimIndent()
        val provider = NeteaseRecognizeProvider(cookieProvider = { "MUSIC_U=x" })
        val match = (provider.parse(json) as RecogOutcome.Matched).match
        assertEquals("晴天", match.title)
        assertEquals("周杰伦", match.artist)
        assertEquals("叶惠美", match.album)
        assertEquals(269_000L, match.durationMs)
        assertEquals("186016", match.providerTrackId)
    }

    @Test
    fun `result 为 null 是没匹配（真机实测形状）`() {
        val provider = NeteaseRecognizeProvider(cookieProvider = { "MUSIC_U=x" })
        val json = """{"data":{"type":0,"queryId":"q","result":null,"mv":null}}"""
        assertEquals(
            RecogFailure.NO_MATCH,
            (provider.parse(json) as RecogOutcome.Failed).failure,
        )
    }

    @Test
    fun `裸 AES 响应可解密出 JSON`() {
        // 端点返回的是原始 AES 密文（不是 base64）：这条钉住解密入口，
        // 上一版把乱码当字符串丢给 JSONObject，才出现"字符串转 JSONObject 失败"。
        val key = "e82ckenh8dichen8".toByteArray()
        val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
        val enc = cipher.doFinal("""{"code":200,"data":{"result":null}}""".toByteArray())
        val plain = com.takahashirinta.ncrust.network.crypto.EapiCrypto.decryptRawBytes(enc)
        assertTrue(plain.startsWith("{"))
    }

    @Test
    fun `网易云 400 视为没匹配 404 视为端点变更`() {
        val provider = NeteaseRecognizeProvider(cookieProvider = { "MUSIC_U=x" })
        assertEquals(
            RecogFailure.NO_MATCH,
            (provider.parse("""{"code":400,"message":""}""") as RecogOutcome.Failed).failure,
        )
        assertEquals(
            RecogFailure.PROTOCOL_CHANGED,
            (provider.parse("""{"code":404,"message":"接口未找到！"}""") as RecogOutcome.Failed).failure,
        )
    }

    @Test
    fun `rawdata 是 zlib 头加 base64`() {
        // 官方报文里 rawdata 以 eJx 开头 = zlib(deflate)。这条钉住编码方式：改了就解不出来。
        val pcm = ShortArray(16_000) { (it % 200 - 100).toShort() }
        val encoded = NeteaseRecognizeProvider.base64Zlib(NeteaseRecognizeProvider.pcm16le(pcm))
        assertTrue("zlib+base64 应以 eJ 开头，实际=${encoded.take(4)}", encoded.startsWith("eJ"))
        // 解回来必须与输入逐字节一致
        val raw = java.util.Base64.getDecoder().decode(encoded)
        val inflated = java.util.zip.Inflater().run {
            setInput(raw); val out = ByteArray(pcm.size * 2)
            val n = inflate(out); end(); out.copyOf(n)
        }
        assertTrue("解压后长度应等于 2×样本数", inflated.size == pcm.size * 2)
        assertEquals(NeteaseRecognizeProvider.pcm16le(pcm).toList(), inflated.toList())
    }

    @Test
    fun `端点必须带 nmclfl 查询参数`() {
        assertTrue(NeteaseRecognizeProvider.DEFAULT_ENDPOINT.contains("/eapi/music/audio/match"))
    }

    @Test
    fun `网易云未登录时不可用`() {
        assertTrue(!NeteaseRecognizeProvider(cookieProvider = { "" }).isAvailable())
    }

    @Test
    fun `网易云非 JSON 响应判协议变更`() {
        val provider = NeteaseRecognizeProvider(cookieProvider = { "MUSIC_U=x" })
        assertEquals(
            RecogFailure.PROTOCOL_CHANGED,
            (provider.parse("<html>502 Bad Gateway</html>") as RecogOutcome.Failed).failure,
        )
    }
}
