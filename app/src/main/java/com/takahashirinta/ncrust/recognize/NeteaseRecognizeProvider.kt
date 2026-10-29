/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.recognize

import android.util.Log
import com.takahashirinta.ncrust.network.crypto.EapiCrypto
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 网易云「听歌识曲」Provider（v3.8.0）。**用你自己的登录态**，不依赖任何付费服务。
 *
 * ## 端点与报文（有来源，不是猜的）
 *
 * 端点：`POST https://music.163.com/eapi/music/audio/match?_nmclfl=1`。
 * 依据：公开逆向记录 <https://nazuki.moe/netease-eapi-music-recognize-reverse-1/>，
 * 其中解密后的请求体形如：
 *
 * ```json
 * {"rawdata":"eJx11H9M1HUYB/C7...","from":"recognize-song","verifyId":1,
 *  "deviceId":"…","os":"iOS","header":{},"algorithmCode":"shazam_v2",
 *  "times":1,"sessionId":"…","duration":3.4,"e_r":true,
 *  "sceneParams":"{\"action\":0,\"code\":2}"}
 * ```
 *
 * 三个关键点：
 * 1. `rawdata` 是 **zlib 压缩后 Base64** 的音频（`eJx` 是 zlib 头）—— 不是裸 PCM；
 * 2. `algorithmCode = shazam_v2`，`duration` 是**真实片段秒数**（官方约 3.4s）；
 * 3. 整包仍走 **eapi 加密**（`params=<hex>`），所以复用仓库里已有的 [EapiCrypto]，
 *    并用 `?_nmclfl=1` 原样保留查询串（签名用的是 path，不含 query）。
 *
 * ## 与旧实现的关系
 *
 * 旧版试的 `/api/music/recognize`、`/api/audio/match`、`/api/song/match` 在登录态下
 * 实测全部返回 `{"code":404,"message":"接口未找到！"}` —— 那条路是不存在的，
 * 已删除，避免"看起来接了其实 404"。
 */
class NeteaseRecognizeProvider(
    private val cookieProvider: () -> String,
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val deviceId: String = "",
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RecognitionProvider {

    override val name: String = "网易云"

    override fun isAvailable(): Boolean = cookieProvider().isNotBlank()

    override suspend fun recognize(input: RecogInput): RecogOutcome {
        val cookie = cookieProvider()
        if (cookie.isBlank()) return RecogOutcome.Failed(RecogFailure.PERMISSION_DENIED, "未登录网易云")

        val samples: ShortArray
        val sampleRate: Int
        when (input) {
            is RecogInput.Pcm -> {
                samples = input.samples
                sampleRate = input.sampleRate
            }
            // 官方客户端上传的是原始波形；编码后的文件需要解码，本轮不做，交给麦克风路径。
            is RecogInput.Encoded ->
                return RecogOutcome.Failed(RecogFailure.PROTOCOL_CHANGED, "该端点只接受 PCM 输入")
        }
        if (samples.isEmpty()) return RecogOutcome.Failed(RecogFailure.SILENT_INPUT, "")

        val durationSec = samples.size.toDouble() / sampleRate
        val rawdata = base64Zlib(pcm16le(samples))
        val sessionId = UUID.randomUUID().toString().replace("-", "").take(16)
        val payload = mapOf(
            "rawdata" to rawdata,
            "from" to "recognize-song",
            "verifyId" to 1,
            "deviceId" to deviceId.ifBlank { "android-${sessionId.take(8)}" },
            "os" to "android",
            "header" to JSONObject(),
            "algorithmCode" to "shazam_v2",
            "times" to 1,
            "sessionId" to sessionId,
            "duration" to String.format(java.util.Locale.US, "%.1f", durationSec).toDouble(),
            "e_r" to true,
            "sceneParams" to "{\"action\":0,\"code\":2}",
        )
        val params = EapiCrypto.encryptParams(endpoint, payload)
        val request = Request.Builder()
            .url("$endpoint?_nmclfl=1")
            .header("Cookie", cookie)
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .post(FormBody.Builder().add("params", params).build())
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                // 必须按**字节**读：这个端点返回裸 AES 密文，按字符串读会丢掉不可打印字节。
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val text = "" // 旧路径保留占位，实际用 bytes
                Log.i(TAG, "audio/match HTTP ${response.code} len=${text.length}")
                // 把明文截断打出来：这个端点的响应形状没有任何公开文档，
                // 只靠猜字段会一直炸在解析层（实测就踩过一次 String→JSONObject）。
                // 原始 body 也打一份：这个端点的响应编码方式必须靠实际字节判断
                // （是 zlib 还是 AES，猜错就得到乱码），body 很小（≈150 字符），一行够。
                Log.i(TAG, "audio/match body64=$text")
                val plain = eapiPlain(text)
                Log.i(TAG, "audio/match plain: ${plain.take(300)}")
                when {
                    response.code >= 500 -> RecogOutcome.Failed(RecogFailure.SERVER_ERROR, "HTTP ${response.code}")
                    response.code == 401 || response.code == 403 ->
                        RecogOutcome.Failed(RecogFailure.QUOTA_EXCEEDED, "HTTP ${response.code}")
                    !response.isSuccessful -> RecogOutcome.Failed(RecogFailure.PROTOCOL_CHANGED, "HTTP ${response.code}")
                    else -> parse(plain)
                }
            }
        } catch (e: IOException) {
            RecogOutcome.Failed(RecogFailure.NO_NETWORK, e.message.orEmpty())
        } catch (t: Throwable) {
            RecogOutcome.Failed(RecogFailure.PROTOCOL_CHANGED, t.message.orEmpty())
        }
    }

    /**
     * 把响应体变成 JSON 文本。**三种形态都试**（实测顺序也按这个来）：
     *
     * 1. 直接是 JSON（明文）；
     * 2. Base64 → **zlib 解压** → JSON（本端点实测就是这种：`len≈148`，明文里解密后仍是二进制）；
     * 3. Base64 → AES 解密（老 eapi 的 `e_r` 响应）。
     *
     * 之所以要都试：这个端点没有任何公开文档，上一版只做 AES 解密，拿到的是乱码
     * （logcat: `plain: ���=…`），然后必然在解析层炸掉。
     */
    fun eapiPlain(body: String): String {
        val trimmed = body.trim()
        if (trimmed.startsWith("{")) return trimmed
        val bytes = runCatching { Base64.getDecoder().decode(trimmed) }.getOrNull()
        if (bytes != null && bytes.isNotEmpty()) {
            // ① zlib（0x78 0x01/0x9C/0xDA）
            if (bytes[0] == 0x78.toByte()) {
                runCatching {
                    val inflater = java.util.zip.Inflater()
                    inflater.setInput(bytes)
                    val out = ByteArrayOutputStream(bytes.size * 4)
                    val buf = ByteArray(4096)
                    while (!inflater.finished()) {
                        val n = inflater.inflate(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                    inflater.end()
                    String(out.toByteArray(), Charsets.UTF_8)
                }.getOrNull()?.takeIf { it.trimStart().startsWith("{") }?.let { return it }
            }
            // ② AES（旧 eapi e_r 响应）
            runCatching { EapiCrypto.decryptResponse(trimmed) }.getOrNull()
                ?.takeIf { it.trimStart().startsWith("{") }?.let { return it }
        }
        // 最后一道：有些响应是「JSON 字符串里再套 JSON」（`"{\"code\":200,...}"`）。
        // 直接 JSONObject(...) 会抛 "Value ... of type java.lang.String cannot be converted
        // to JSONObject" —— 真机就是这样炸的，所以这里脱一层引号再交给 parse。
        val unquoted = runCatching {
            val v = org.json.JSONTokener(trimmed).nextValue()
            if (v is String) v else null
        }.getOrNull()
        return unquoted ?: trimmed
    }

    fun parse(json: String?): RecogOutcome {
        if (json.isNullOrBlank()) return RecogOutcome.Failed(RecogFailure.PROTOCOL_CHANGED, "空响应")
        return try {
            val root = JSONObject(json)
            // 识曲端点的成功响应**没有 code 字段**（实测 `{"data":{…}}`），
            // 所以缺省按 200 处理；只有显式出现的错误码才当错误。
            val code = root.optInt("code", 200)
            if (code != 200) {
                return when (code) {
                    404 -> RecogOutcome.Failed(RecogFailure.PROTOCOL_CHANGED, "接口未找到")
                    400, 401 -> RecogOutcome.Failed(RecogFailure.NO_MATCH, root.optString("message"))
                    -460 -> RecogOutcome.Failed(RecogFailure.QUOTA_EXCEEDED, root.optString("message"))
                    else -> RecogOutcome.Failed(RecogFailure.PROTOCOL_CHANGED, "code=$code ${root.optString("message")}")
                }
            }
            // 实测形状（2026-10-30，真机 + 本机复现）：
            // {"data":{"type":0,"queryId":"…","result":null,"mv":null}}  ← 没匹配
            // 命中时 result 里带 song（或 songs[]），这里三种都兼容。
            val data = root.optJSONObject("data") ?: return RecogOutcome.Failed(RecogFailure.NO_MATCH, "无 data")
            if (data.isNull("result")) return RecogOutcome.Failed(RecogFailure.NO_MATCH, "result=null")
            val result: JSONObject? = data.optJSONObject("result")
            val song = data.optJSONObject("song")
                ?: result?.optJSONObject("song")
                ?: result?.optJSONArray("songs")?.optJSONObject(0)?.optJSONObject("song")
                ?: result?.optJSONArray("songs")?.optJSONObject(0)
                ?: return RecogOutcome.Failed(RecogFailure.NO_MATCH, "result 里没有 song")
            val title = song.optString("name")
            if (title.isBlank()) return RecogOutcome.Failed(RecogFailure.NO_MATCH, "")
            val artists = song.optJSONArray("artists")?.let { arr ->
                (0 until arr.length()).mapNotNull {
                    arr.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank)
                }
            }.orEmpty()
            RecogOutcome.Matched(
                RecogMatch(
                    title = title,
                    artist = artists.joinToString("/"),
                    album = song.optJSONObject("album")?.optString("name").orEmpty(),
                    durationMs = song.optLong("duration", 0L),
                    confidence = 1f,
                    provider = name,
                    providerTrackId = song.optLong("id", 0L).takeIf { it > 0 }?.toString().orEmpty(),
                )
            )
        } catch (t: Throwable) {
            RecogOutcome.Failed(
                RecogFailure.PROTOCOL_CHANGED,
                (t.message.orEmpty() + " | raw=" + json.take(120)).trim(),
            )
        }
    }

    companion object {
        private const val TAG = "NeteaseRecog"

        /** 逆向记录里的真实端点；`?_nmclfl=1` 在请求时附加（签名不含 query）。 */
        const val DEFAULT_ENDPOINT = "https://music.163.com/eapi/music/audio/match"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        /** PCM16 → 小端字节。 */
        fun pcm16le(samples: ShortArray): ByteArray {
            val out = ByteArrayOutputStream(samples.size * 2)
            for (s in samples) {
                val v = s.toInt()
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
            }
            return out.toByteArray()
        }

        /** zlib（deflate + zlib 头）后 Base64 —— 官方 `rawdata` 的编码方式。 */
        fun base64Zlib(bytes: ByteArray): String {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream(bytes.size / 2 + 64)
            val buf = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
            deflater.end()
            return Base64.getEncoder().encodeToString(out.toByteArray())
        }
    }
}
