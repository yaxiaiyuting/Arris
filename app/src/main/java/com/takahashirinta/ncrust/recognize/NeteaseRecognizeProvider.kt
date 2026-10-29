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
import java.util.zip.GZIPOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 网易云识曲 Provider（v3.8.0）。**配方来自抓包**（官方客户端 9.6.15，2026-10-30）。
 *
 * ## 真端点
 *
 * `POST https://interface3.music.163.com/eapi/music/hum/audio/unifymatch`
 *
 * 早期我用社区流传的 `/eapi/music/audio/match`，服务端状态码 200 但永远 `result:null` ——
 * 因为那条端点已废弃。真实客户端走的是 `hum/audio/unifymatch`。
 *
 * ## 请求结构（抓包明文，逐字段还原）
 *
 * ```json
 * {"Url":"https://interface3.music.163.com/api/music/hum/audio/unifymatch",
 *  "algorithmCode":"ASR","algorithmVer":"1.0.0",
 *  "audioSceneInformation":"eyIxMCI6…","decrypt":"1","duration":"10",
 *  "e_r":true,"from":"app","header":"{}","identifyTab":"0",
 *  "queryId":"<每次新 UUID>","recordAudio":"<base64 的 M4A>"}
 * ```
 *
 * 外层仍是标准 eapi：`params=<hex(AES-128-ECB 签名串)>`，但整个 body 还要再 **gzip** 一层，
 * 并带 `Content-Encoding: gzip` + `x-mg-zip: gzip`。
 *
 * ## 三个必须照抄的细节（都踩过）
 *
 * 1. **`queryId` 必须每次新生成**：复用旧值服务端会直接返回 `result:null`（缓存/去重）；
 * 2. **`recordAudio` 是 MP4/AAC 容器**，不是 PCM、也不是 Shazam 签名 —— 发 PCM 时服务端
 *    要么回空体、要么 `{"msg":"参数错误","code":400}`；
 * 3. **`x-deviceId` / `x-buildver` / `MConfig-Info` 这些头不能省**：缺了就是 400 参数错误。
 *
 * ## 响应
 *
 * 响应是 AES 密文（外面还可能套一层 gzip），解出：
 * `{"code":200,"data":{"result":[{"startTime":ms,"song":{…}}]}}`；
 * `data.result` 为 null / 空数组即"没听出来"。
 */
class NeteaseRecognizeProvider(
    private val cookieProvider: () -> String,
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RecognitionProvider {

    override val name: String = "网易云"

    override fun isAvailable(): Boolean = cookieProvider().isNotBlank()

    override suspend fun recognize(input: RecogInput): RecogOutcome {
        val cookie = cookieProvider()
        if (cookie.isBlank()) return RecogOutcome.Failed(RecogFailure.PERMISSION_DENIED, "未登录网易云")

        val (bytes, durationSec) = when (input) {
            // 官方要的是 MP4/AAC 容器；调用方应传 AacRecorder 的结果。
            is RecogInput.Encoded -> input.bytes to (input.durationMs.coerceAtLeast(1L) / 1000.0)
            is RecogInput.Pcm -> return RecogOutcome.Failed(
                RecogFailure.PROTOCOL_CHANGED,
                "该端点只接受 M4A/AAC；PCM 需先编码（见 AacRecorder）",
            )
        }
        if (bytes.isEmpty()) return RecogOutcome.Failed(RecogFailure.SILENT_INPUT, "录音为空")

        val payload = buildPayload(
            audioBase64 = Base64.getEncoder().encodeToString(bytes),
            durationSec = durationSec,
            queryId = UUID.randomUUID().toString(),
        )
        val body = gzip(buildForm(payload))
        Log.i(TAG, "unifymatch audio=${bytes.size}B duration=${durationSec}s body=${body.size}B")

        val request = Request.Builder()
            .url(endpoint)
            .header("Cookie", cookie)
            .header("User-Agent", UA)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Content-Encoding", "gzip")
            .header("x-mg-zip", "gzip")
            .header("x-os", "android")
            .header("x-osver", ANDROID_VERSION)
            .header("x-appver", APP_VER)
            .header("x-buildver", BUILD_VER)
            .header("x-deviceId", DEVICE_ID)
            .header("x-sDeviceId", DEVICE_ID)
            .header("CMPageId", "page_listen_identify_songs_sub")
            .header("MConfig-Info", MCONFIG)
            .header("x-aeapi", "true")
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.bytes() ?: ByteArray(0)
                if (raw.isEmpty()) {
                    return RecogOutcome.Failed(
                        RecogFailure.PROTOCOL_CHANGED,
                        "服务端回空体（HTTP ${response.code}，audio=${bytes.size}B）",
                    )
                }
                val decrypted = EapiCrypto.decryptRawBytes(raw).ifBlank { String(raw, Charsets.UTF_8) }
                // 解出来可能是 JSON 文本，也可能是 gzip 字节序列（抓包里两种都出现过）
                val json = decrypted.takeIf { it.trimStart().startsWith("{") }
                    ?: gunzip(raw).ifBlank { decrypted }
                Log.i(TAG, "HTTP ${response.code} raw=${raw.size}B plain=${json.take(240)}")
                when {
                    response.code >= 500 -> RecogOutcome.Failed(RecogFailure.SERVER_ERROR, "HTTP ${response.code}")
                    response.code == 401 || response.code == 403 ->
                        RecogOutcome.Failed(RecogFailure.QUOTA_EXCEEDED, "HTTP ${response.code}")
                    !response.isSuccessful -> RecogOutcome.Failed(RecogFailure.PROTOCOL_CHANGED, "HTTP ${response.code}")
                    else -> parse(json)
                }
            }
        } catch (e: IOException) {
            RecogOutcome.Failed(RecogFailure.NO_NETWORK, e.message.orEmpty())
        } catch (t: Throwable) {
            RecogOutcome.Failed(RecogFailure.PROTOCOL_CHANGED, t.message.orEmpty())
        }
    }

    /** 抓包还原的请求体字段（顺序无关，eapi 签名对的是序列化后的整串）。 */
    fun buildPayload(audioBase64: String, durationSec: Double, queryId: String): JSONObject = JSONObject()
        .put("Url", "https://interface3.music.163.com/api/music/hum/audio/unifymatch")
        .put("algorithmCode", "ASR")
        .put("algorithmVer", "1.0.0")
        .put("audioSceneInformation", AUDIO_SCENE)
        .put("decrypt", "1")
        .put("duration", durationSec.toInt().toString())
        .put("e_r", true)
        .put("from", "app")
        .put("header", "{}")
        .put("identifyTab", "0")
        .put("queryId", queryId)
        .put("recordAudio", audioBase64)

    fun parse(json: String?): RecogOutcome {
        if (json.isNullOrBlank()) return RecogOutcome.Failed(RecogFailure.PROTOCOL_CHANGED, "空响应")
        return try {
            val root = JSONObject(json)
            val code = root.optInt("code", 200)
            if (code != 200) {
                return when (code) {
                    400 -> RecogOutcome.Failed(RecogFailure.PROTOCOL_CHANGED, root.optString("msg"))
                    401, 403 -> RecogOutcome.Failed(RecogFailure.QUOTA_EXCEEDED, root.optString("msg"))
                    else -> RecogOutcome.Failed(RecogFailure.PROTOCOL_CHANGED, "code=$code ${root.optString("msg")}")
                }
            }
            val data = root.optJSONObject("data") ?: return RecogOutcome.Failed(RecogFailure.NO_MATCH, "无 data")
            val result = data.optJSONArray("result")
            if (result == null || result.length() == 0) {
                return RecogOutcome.Failed(RecogFailure.NO_MATCH, "result 为空")
            }
            val first = result.optJSONObject(0) ?: return RecogOutcome.Failed(RecogFailure.NO_MATCH, "")
            val song = first.optJSONObject("song") ?: return RecogOutcome.Failed(RecogFailure.NO_MATCH, "无 song")
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

    /** eapi 签名串 + form 编码（未压缩）。 */
    private fun buildForm(payload: JSONObject): ByteArray {
        val params = EapiCrypto.encryptParams(SIGN_PATH_URL, payload.toMap())
        return "params=$params".toByteArray(Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "NeteaseRecog"

        const val DEFAULT_ENDPOINT = "https://interface3.music.163.com/eapi/music/hum/audio/unifymatch"

        /** eapi 签名里用的是 `/api/...` 路径（`/eapi/` 要重写）。 */
        private const val SIGN_PATH_URL = "https://interface3.music.163.com/eapi/music/hum/audio/unifymatch"

        private const val UA =
            "NeteaseMusic/9.6.15.261027102340(9006015);Dalvik/2.1.0 (Linux; U; Android 17; " +
                "PLC110 Build/CP2A.260605.016)"
        private const val APP_VER = "9.6.15"
        private const val BUILD_VER = "261027102340"
        private const val ANDROID_VERSION = "17"

        /** 抓包里的设备指纹（脱敏后使用；服务端只做设备维度限流，不做账号绑定校验）。 */
        private const val DEVICE_ID =
            "bnVsbAkwMjowMDowMDowMDowMDowMAk0NTVlMTc3MDhhMzllZDY0CTBmOTAzMzExNmZiY2YxODU%3D"

        private const val MCONFIG =
            "{\"IuRPVVmc3WWul9fT\":{\"version\":\"122593408\",\"appver\":\"9.6.15\"}," +
                "\"tPJJnts2H31BZXmp\":{\"version\":\"5472256\",\"appver\":\"4.80.0\"}}"

        /** 抓包里 10 秒录音对应的场景特征（原样照抄，服务端据此选择识别策略）。 */
        private const val AUDIO_SCENE =
            "eyIxMCI6eyJtdXIiOiIwLjQ1IiwibXVzIjoiMTMuNjMiLCJzaXIiOiIwLjM2Iiwic2lzIjoiMTYuODUiLCJzcHIiOiIwLjAzIiwic3BzIjoiMTMuOTkifX0="

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

        fun gzip(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(bytes.size / 2 + 64)
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }

        /** 响应可能是 gzip 字节（直接解）或已解好的 JSON 文本。 */
        fun gunzip(bytes: ByteArray): String = try {
            java.util.zip.GZIPInputStream(bytes.inputStream()).use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        } catch (_: Throwable) {
            ""
        }

        /** PCM16 → 小端字节（保留给历史调用方）。 */
        fun pcm16le(samples: ShortArray): ByteArray {
            val out = ByteArrayOutputStream(samples.size * 2)
            for (s in samples) {
                val v = s.toInt()
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
            }
            return out.toByteArray()
        }
    }
}

/** `JSONObject` → `Map<String, Any>`（eapi 加密要的是可序列化 map）。 */
private fun JSONObject.toMap(): Map<String, Any> {
    val map = LinkedHashMap<String, Any>()
    keys().forEach { k -> map[k] = get(k) }
    return map
}
