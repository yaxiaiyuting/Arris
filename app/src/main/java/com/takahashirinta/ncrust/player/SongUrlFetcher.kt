/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - Bug1：公开 deviceSupportsFlac / isFlacTier，供设置页对 FLAC 档位给出
 *     「本机不支持该档位，将自动降级」提示，不再静默降档。
 *   - B2：FLAC 门控由「仅平台解码器」改为「平台解码器 或 随包的 FFmpeg 扩展」，
 *     使 API 24–26（Android 7.0/7.1）也能真正播放无损。 */

package com.takahashirinta.ncrust.player

import android.media.MediaCodecList
import android.os.Build
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import android.util.Log
import com.takahashirinta.ncrust.BuildConfig
import com.takahashirinta.ncrust.cache.OfflineKeys
import com.takahashirinta.ncrust.network.ClientIdentity
import com.takahashirinta.ncrust.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [actualLevel] 是服务端返回的 **level 字符串**，[br] / [type] 才是实际文件参数。
 * A1 起两者一起带出来：实测存在 granted=lossless 但 br 仍是 Hi-Res 值（1,685,762）的情况 ——
 * 只看 level 字符串会把"其实在播 Hi-Res"误判成降级（A3 据此重写降级判定）。
 */
data class SongUrlResult(
    val url: String,
    val actualLevel: String,
    val br: Long = 0L,
    val type: String = "",
    /** 该曲能达到的最高档位（privilege.maxBrLevel）。仅在实际文件可能低于请求档位时按需拉取。 */
    val songMaxLevel: String? = null,
    /**
     * v2.2.1 · P0：[actualLevel] 是不是**由实际拿到的东西推出来的**，而不是服务端标签。
     *
     * 为什么必须区分：v1.3.0 的教训是「标签写低、文件其实是高解析」（granted=lossless，
     * 实测 br=1,685,762 = Hi-Res），所以**标签不可信**；但 QQ 那条路的 `actualLevel` 是
     * 从**真正取回的文件名前缀**反推的（`RS01…flac` → hires），它本身就是证据。
     * 两者混在一起时，`QualityAssessment` 只能一律「保持安静」，于是
     * 「请求超清母带、拿到 Hi-Res」在界面上既不是降级也没有任何提示 —— 用户无从判断。
     *
     * `true` 的来源：QQ 取链（前缀反推）、离线缓存 key（key 里就写着档位）。
     * `false`（默认）：网易云 eapi 返回的 `level` 字符串 —— 它只是标签。
     *
     * 注意：本类型**不落盘**（纯内存 DTO），所以按「加字段 = 加迁移逻辑」的规矩这里
     * 不需要迁移；但新语义有单测（QualityAssessmentTest 的 trusted-level 用例）。
     */
    val levelFromFile: Boolean = false,
    /**
     * v2.2.1 · P0：本次请求 [SongUrlResult] 的档位，与实际档位不一致时记在这里。
     * 离线兜底会「退化成这首歌的任意档位」，这是那条路径唯一的诚实出口。
     */
    val fallbackFromLevel: String? = null,
    /**
     * v3.1.0 · B：这条 URL 的**绝对**过期时刻（`System.currentTimeMillis()` 口径）；
     * `null` = 「本音源不提供显式过期时间，用调用方的默认 TTL」。
     *
     * ## 为什么必须由 Provider 交出来，而不是让缓存层自己猜
     *
     * 铁律 26：B 站音频流 URL 有时效。实测（`docs/verification/v3.1.0/bili-research/EVIDENCE.md`）
     * 响应里的 `timeout` 是 **10800s（3h）**，而 URL 自带的 `deadline` 参数是
     * **now + 7200s（2h）** —— 两者不一致，且 URL 自己那个更保守。
     * 只有拿到 URL 的那一层（Provider）才知道该信哪个，所以过期时刻在这里算好、
     * 一路带到缓存条目上。给 `null` 的音源（网易云 / QQ）行为**逐字不变**：
     * 缓存仍按既有的 5 分钟常量判新鲜。
     */
    val expiresAtMs: Long? = null,
)

object SongUrlFetcher {
    private const val TAG = "SongUrlFetcher"
    private const val SONG_URL_PATH = "/eapi/song/enhance/player/url/v1"
    private const val SONG_DETAIL_PATH = "/eapi/v3/song/detail"

    private val FLAC_TIERS = setOf("lossless", "hires", "jyeffect", "jymaster")

    /**
     * 平台自带的 FLAC **解码**器（API 27+ 才有）。
     *
     * 注意 API 24–26（Android 7.0/7.1）的系统里 audio/flac 只有
     * OMX.google.flac.encoder —— 只有编码器，没有解码器，因此这些系统上
     * 拿到 flac URL 也播不出声（表现为「有进度没声音」）。
     */
    private val platformFlacDecoder: Boolean by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            false
        } else {
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    !info.isEncoder && info.supportedTypes.any { it.equals("audio/flac", ignoreCase = true) }
                }
            }.getOrDefault(false)
        }
    }

    /**
     * 随包分发的 FFmpeg 软件解码扩展（org.jellyfin.media3:media3-ffmpeg-decoder，GPL-3.0）。
     * isAvailable() 只在对应 ABI 的 libffmpegJNI.so 真正加载成功时返回 true。
     */
    private val ffmpegExtensionAvailable: Boolean by lazy {
        runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)
    }

    /**
     * 本机能否真正解码 FLAC：**平台解码器或 FFmpeg 扩展，有其一即可**。
     * 因此 API 24–26 也能走无损档位，不再被静默跳过。
     */
    private val deviceCanDecodeFlac: Boolean by lazy {
        platformFlacDecoder || ffmpegExtensionAvailable
    }

    init {
        // 实测无损是否真的可解时，这一行是唯一权威依据（尤其 API 24–26 与各种 ROM）。
        Log.i(
            TAG,
            "FLAC capability: platformDecoder=$platformFlacDecoder " +
                "ffmpegExtension=$ffmpegExtensionAvailable " +
                "ffmpegVersion=" + runCatching { FfmpegLibrary.getVersion() }.getOrDefault("n/a") +
                " => canDecodeFlac=$deviceCanDecodeFlac"
        )
    }

    /**
     * 本机是否具备系统 FLAC 解码器（API < 27 没有）。
     * 设置页据此在用户选中 FLAC 档位时提示「本机不支持该档位，将自动降级」（Bug1-C）。
     */
    val deviceSupportsFlac: Boolean get() = deviceCanDecodeFlac

    /** [level] 是否属于必须依赖 FLAC 解码器的档位。 */
    fun isFlacTier(level: String): Boolean = level in FLAC_TIERS

    // Returns null when no level yields a playable URL (e.g. VIP-only song without a
    // subscription, or no valid session). Callers must skip the song instead of playing.
    suspend fun fetch(songId: Long, level: String = "lossless"): SongUrlResult? = withContext(Dispatchers.IO) {
        // Try the requested level first, then fall back down the quality ladder.
        val fallbackLevels = when (level) {
            "dolby"    -> listOf("dolby", "hires", "lossless", "exhigh", "higher", "standard")
            "jymaster" -> listOf("jymaster", "hires", "lossless", "exhigh", "higher", "standard")
            "jyeffect" -> listOf("jyeffect", "lossless", "exhigh", "higher", "standard")
            "hires"    -> listOf("hires", "lossless", "exhigh", "higher", "standard")
            "lossless" -> listOf("lossless", "exhigh", "higher", "standard")
            "exhigh"   -> listOf("exhigh", "higher", "standard")
            "higher"   -> listOf("higher", "standard")
            "standard" -> listOf("standard")
            else       -> listOf(level, "lossless", "exhigh", "higher", "standard")
        }

        for (tryLevel in fallbackLevels) {
            // 设备解不了 FLAC 时,flac 档位取来也是无声,直接跳到 mp3 档。
            if (tryLevel in FLAC_TIERS && !deviceCanDecodeFlac) continue
            try {
                val payload = buildPayload(songId, tryLevel)
                // A1：服务端按 **Cookie 里的客户端身份** 判定音质上限。只带 MUSIC_U/__csrf 时，
                // 请求 hires 会被静默封顶成 lossless（code 仍是 200）；追加 os/appver 后同一请求
                // 才真正返回 hires。实测见仓库外 tools/probe-quality.py。
                val response = RetrofitClient.eapiPost(
                    SONG_URL_PATH,
                    payload,
                    useInterface = true,
                    extraCookie = ClientIdentity.extraCookieFor(RetrofitClient.getCookie())
                )
                val body = response.body?.string() ?: continue
                Log.d(TAG, "eapi response ($tryLevel): $body")
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: continue
                if (data.length() > 0) {
                    val obj = data.getJSONObject(0)
                    val code = obj.optInt("code", 200)
                    val url = obj.optString("url")
                    val actualLevel = obj.optString("level", tryLevel)
                    // type/br 用于诊断:type 是实际容器(mp3/flac/mp4),br 是码率,
                    // 出现"有进度没声音"时靠这两项判断拿到的到底是不是预期的文件。
                    val type = obj.optString("type", "")
                    val br = obj.optLong("br", 0L)
                    // code != 200 means the level is unavailable (404 = no resource / not entitled).
                    // There is no point accepting such an entry, so advance down the ladder.
                    if (code != 200 || url.isNullOrEmpty()) {
                        logAttempt(tryLevel, actualLevel, br, type, code, accepted = false)
                        continue
                    }
                    logAttempt(tryLevel, actualLevel, br, type, code, accepted = true)
                    // A3：只有"实际文件可能低于请求档位"时才多问一次该曲的档位上限 ——
                    // 用来把「账号无权限」和「该曲根本没这个档位」分开。平时不加这次往返。
                    val songMaxLevel =
                        if (QualityAssessment.needsSongCapability(level, br, type)) fetchSongMaxLevel(songId)
                        else null
                    // v1.6.0 · D1：给播放 URL 挂上离线缓存的 key（query 参数是装饰，
                    // CDN 只认路径里的签名，实测删改 query 仍 206）。挂上之后 CacheDataSource
                    // 才能把「同一首歌 + 同一档位」的所有轮换 URL 认成同一份缓存。
                    return@withContext SongUrlResult(
                        OfflineKeys.withKey(url, songId, actualLevel), actualLevel, br, type, songMaxLevel,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetch failed for level=$tryLevel", e)
            }
        }

        // 所有档位都取不到可播放的 URL。绝不要用 https://music.163.com/song/media/outer/url?id=X.mp3
        // 兜底 —— 那个旧端点对无版权/需会员的歌曲返回 302→404 的 HTML 页面，ExoPlayer 拿到非音频流
        // 会无限缓冲（"卡住"）。这里直接返回 null，让上层跳歌而不是播放坏链接。
        Log.e(TAG, "no playable url for songId=$songId at any level")
        null
    }

    /**
     * 该曲能达到的最高档位（privilege.maxBrLevel）。带内存缓存（每曲一次），失败返回 null，
     * 绝不抛给调用方 —— 判定降级原因失败不该影响播放。
     */
    private suspend fun fetchSongMaxLevel(songId: Long): String? {
        songMaxLevelCache[songId]?.let { return it }
        return try {
            val payload = mapOf("c" to JSONArray().put(JSONObject().put("id", songId)).toString())
            val response = RetrofitClient.eapiPost(
                SONG_DETAIL_PATH,
                payload,
                extraCookie = ClientIdentity.extraCookieFor(RetrofitClient.getCookie())
            )
            val body = response.body?.string()
            val privileges = body?.let { JSONObject(it).optJSONArray("privileges") }
            val level = if (privileges != null && privileges.length() > 0) {
                privileges.getJSONObject(0).optString("maxBrLevel").takeIf { it.isNotEmpty() }
            } else null
            if (level != null) {
                if (songMaxLevelCache.size > 256) songMaxLevelCache.clear()
                songMaxLevelCache[songId] = level
            }
            Log.i(TAG, "song capability: songId=$songId maxBrLevel=${level ?: "unknown"}")
            level
        } catch (e: Exception) {
            Log.w(TAG, "song capability fetch failed for songId=$songId", e)
            null
        }
    }

    private val songMaxLevelCache = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /**
     * A1：把「请求档位 / 服务端给的 level 字符串 / 实际文件参数」分开打出来，供真机实测比对
     * 与 A3 的降级判定使用 —— 实测存在 granted=lossless 但 br 仍是 Hi-Res 值的情况。
     * debug 额外带 deviceId 前 4 位（确认身份是否真的生效），release 只留必要字段。
     */
    private fun logAttempt(
        requested: String,
        granted: String,
        br: Long,
        type: String,
        code: Int,
        accepted: Boolean,
    ) {
        val detail = "quality attempt: requested=$requested granted=$granted br=$br type=$type code=$code accepted=$accepted"
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "$detail deviceId=${ClientIdentity.deviceId().take(4)}…")
        } else {
            Log.i(TAG, detail)
        }
    }

    private fun buildPayload(songId: Long, level: String): Map<String, String> {
        // 官方客户端 header 字段,声明 PC 端并携带随机 requestId,保证杜比等音质返回正常码率。
        // A1：body header 与 Cookie 里的身份同源。实测解锁只看 **Cookie**，body 这份对音质
        // 无用；但两处不一致会形成自相矛盾的客户端指纹（旧值 appver="" / deviceId="pyncm!"），
        // 因此一并填成同一组值。
        val config = JSONObject()
            .put("os", ClientIdentity.OS)
            .put("appver", ClientIdentity.APPVER)
            .put("osver", ClientIdentity.OSVER)
            .put("deviceId", ClientIdentity.deviceId())
            .put("requestId", (20_000_000..30_000_000).random().toString())

        val base = mutableMapOf(
            "ids" to JSONArray().put(songId).toString(),
            "level" to level,
            "header" to config.toString(),
        )
        // 杜比全景声必须以 mp4 容器输出(EAC3),其余音质用 FLAC。
        base["encodeType"] = if (level == "dolby") "mp4" else "flac"
        return base
    }
}
