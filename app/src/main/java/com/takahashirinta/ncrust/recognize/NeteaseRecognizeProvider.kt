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
        // 关键：rawdata 必须是 **Shazam 签名**（KB 级），不是 PCM。
        // 实测发 PCM（≈120 KB）时服务端直接回空 body；发签名（≈2.8 KB）才返回正常 JSON。
        val signature = ShazamSignature.generateFromPcm16(samples)
        val rawdata = ShazamSignature.toRawdata(signature)
        Log.i(TAG, "signature=${signature.size}B rawdata=${rawdata.length}B pcm=${samples.size * 2}B")
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
                if (bytes.isEmpty()) {
                    // 实测对照（2026-10-30）：rawdata ≈2.8KB（签名）→ 正常 JSON；
                    // ≈120KB（PCM）→ **空 body**。所以空体几乎等价于"载荷形态/体积不对"。
                    return RecogOutcome.Failed(
                        RecogFailure.PROTOCOL_CHANGED,
                        "服务端回空体（载荷 ${rawdata.length}B）：签名应 ≈3KB，PCM 会被拒",
                    )
                }
                val plain = if (bytes[0] == '{'.code.toByte()) {
                    String(bytes, Charsets.UTF_8)
                } else {
                    EapiCrypto.decryptRawBytes(bytes)
                }
                Log.i(TAG, "audio/match HTTP ${response.code} bytes=${bytes.size} plain=${plain.take(300)}")
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
            if (data.isNull("result")) {
                val reason = data.optInt("noMatchReason", -1)
                return RecogOutcome.Failed(
                    RecogFailure.NO_MATCH,
                    if (reason >= 0) "result=null（noMatchReason=$reason）" else "result=null",
                )
            }
            val resultArray = data.optJSONArray("result")
            val result: JSONObject? = data.optJSONObject("result")
                ?: resultArray?.optJSONObject(0)
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
