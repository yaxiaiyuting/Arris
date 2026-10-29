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
        // 真实形状（抓包 + 回放实证）：data.result 是数组，每项带 startTime + song
        val json = """
            {"code":200,"data":{"type":1,"queryId":"64f9ce00","result":[
              {"startTime":1000,"song":{"id":108251,"name":"当你","duration":274000,
                "artists":[{"name":"林俊杰"}],"album":{"name":"她说 概念自选辑"}}}]}}
        """.trimIndent()
        val provider = NeteaseRecognizeProvider(cookieProvider = { "MUSIC_U=x" })
        val match = (provider.parse(json) as RecogOutcome.Matched).match
        assertEquals("当你", match.title)
        assertEquals("林俊杰", match.artist)
        assertEquals("她说 概念自选辑", match.album)
        assertEquals(274_000L, match.durationMs)
        assertEquals("108251", match.providerTrackId)
    }

    @Test
    fun `result 为 null 是没匹配（真机实测形状）`() {
        val provider = NeteaseRecognizeProvider(cookieProvider = { "MUSIC_U=x" })
        val json = """{"code":200,"data":{"type":0,"queryId":"q","result":null,"mv":null}}"""
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
    fun `参数错误 400 属于协议变更而不是没匹配`() {
        val provider = NeteaseRecognizeProvider(cookieProvider = { "MUSIC_U=x" })
        assertEquals(
            RecogFailure.PROTOCOL_CHANGED,
            (provider.parse("""{"code":400,"msg":"参数错误"}""") as RecogOutcome.Failed).failure,
        )
        assertEquals(
            RecogFailure.PROTOCOL_CHANGED,
            (provider.parse("""{"code":404,"message":"接口未找到！"}""") as RecogOutcome.Failed).failure,
        )
    }

    @Test
    fun `请求体包含抓包还原的字段`() {
        val p = NeteaseRecognizeProvider(cookieProvider = { "MUSIC_U=x" })
        val payload = p.buildPayload("AAAA", 10.0, "qid-1")
        assertEquals("ASR", payload.getString("algorithmCode"))
        assertEquals("10", payload.getString("duration"))
        assertEquals("qid-1", payload.getString("queryId"))
        assertEquals("app", payload.getString("from"))
        assertTrue(payload.getString("recordAudio").isNotEmpty())
        assertTrue(payload.getString("Url").endsWith("/api/music/hum/audio/unifymatch"))
        // 抓包里被我漏抄过、缺了就 400 的那四个字段
        assertEquals("AAC", payload.getString("recordAudioFormat"))
        assertEquals(10, payload.getInt("recordType"))
        assertEquals("""{"code":2}""", payload.getString("sceneParams"))
        assertTrue(payload.getString("sessionId").isNotBlank())
        assertEquals(1, payload.getInt("times"))
    }

    @Test
    fun `gzip 往返`() {
        val raw = """{"a":1}""".toByteArray()
        assertEquals("{\"a\":1}", NeteaseRecognizeProvider.gunzip(NeteaseRecognizeProvider.gzip(raw)))
    }

    @Test
    fun `端点必须是抓包实证的 hum unifymatch`() {
        // 旧端点 /eapi/music/audio/match 已废弃（服务端恒回 result:null），这条防止回退
        assertEquals(
            "https://interface3.music.163.com/eapi/music/hum/audio/unifymatch",
            NeteaseRecognizeProvider.DEFAULT_ENDPOINT,
        )
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
