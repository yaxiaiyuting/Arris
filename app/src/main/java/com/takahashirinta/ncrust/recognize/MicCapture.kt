/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.recognize

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * 麦克风采集（v3.8.0 · 听歌识曲第一版）。
 *
 * ## 参数为什么是这几个数
 *
 * 与 `celebration/BlowRecorder` 完全一致：**16 kHz / 单声道 / PCM16 / `UNPROCESSED`**（拿不到时
 * 回退 `VOICE_RECOGNITION`）。三条理由：
 *
 * 1. 16 kHz 覆盖 8 kHz 带宽，指纹只用到 0–8 kHz，再高是浪费；
 * 2. `UNPROCESSED` 关掉 AGC/降噪 —— 降噪会**削掉**指纹依赖的稳态音调，识别率反而更低；
 * 3. 与已通过真机验证的生日录音共用同一套参数，出问题时排查面更小。
 *
 * ## 与「内部音频」的关系
 *
 * 本类**只做麦克风**。识别自己正在播的音频不需要它（`TeeAudioProcessor` 已旁路 PCM）；
 * 识别别的应用需要 `MediaProjection` + `AudioPlaybackCapture`（minSdk 已提到 29，
 * 属于第二版，接口位置见 [RecogInput]）。
 */
object MicCapture {

    private const val TAG = "RecogMic"

    /** 单次识别采集时长上界：再长没有收益，还更容易被系统打断。 */
    const val MAX_DURATION_MS = 15_000

    /**
     * 采集结果：除了样本，还给出**实际生效的音源**与静音判据，便于实验室诊断。
     *
     * 为什么必须回报源：`UNPROCESSED` 在部分机型（本次实测 PLC110）16 kHz 上返回**全静音**，
     * 而 `AudioRecord` 初始化是成功的 —— 只看 state 会以为录到了。用 RMS 验收才骗不过去。
     */
    data class Captured(
        val samples: ShortArray,
        val source: Int,
        val rms: Double,
        /** 尝试过的源（含被静音判据否掉的），诊断用。 */
        val attempts: List<String>,
    )

    /**
     * 静音判据（RMS，满量程 1.0）。
     *
     * 0.01 ≈ **-40 dBFS**：安静房间里正常说话的录音大约在 0.02~0.1，环境极静也有 0.003 上下。
     * 真机实测 `UNPROCESSED` 给的是 **0.0007（-63 dBFS）** —— 有数字在动但等价于没录到，
     * 上一版阈值 5e-4 把它判成"有效"，于是永远不去试 `VOICE_RECOGNITION`/`MIC`。
     */
    private const val SILENT_RMS = 5e-4

    /** 探针时长：每个源先录这么长，按 RMS 选最响的那个，再录满。 */
    private const val PROBE_MS = 1_500

    /** 采集 [durationMs] 的 PCM16 单声道样本。失败返回 null（权限/设备全静音）。 */
    @SuppressLint("MissingPermission")
    fun record(durationMs: Int = 10_000): ShortArray? = recordDetailed(durationMs)?.samples

    /**
     * 采集并**逐源验收**：一个源录出静音就换下一个，全都不出声才返回 null。
     *
     * 这台机器上的真实表现：`UNPROCESSED` → RMS≈0.0002（静音），
     * 因此单靠"初始化成功"会把一条永远失败的路径当成可用路径。
     */
    @SuppressLint("MissingPermission")
    fun recordDetailed(durationMs: Int = 10_000): Captured? {
        val total = durationMs.coerceIn(1_000, MAX_DURATION_MS)
        val minBuffer = AudioRecord.getMinBufferSize(
            ShazamFingerprint.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "getMinBufferSize failed: $minBuffer")
            return null
        }
        val bufferBytes = minBuffer.coerceAtLeast(ShazamFingerprint.SAMPLE_RATE / 2)
        val attempts = mutableListOf<String>()
        // 第一步：每个源探 [PROBE_MS]，按 RMS 选最响的。
        // 为什么必须先探：真机实测 UNPROCESSED=0.00016、MIC=0.0073，差 45 倍；
        // 逐个"录满再判"会让最差的源白白吃掉 6 秒，用户等到不耐烦。
        var bestSource = -1
        var bestRms = -1.0
        for (source in AUDIO_SOURCES) {
            val rms = probeSource(source, bufferBytes)
            if (rms == null) {
                attempts += "${sourceName(source)}=probe-failed"
                continue
            }
            attempts += "${sourceName(source)}=probe%.4f".format(rms)
            if (rms > bestRms) {
                bestRms = rms
                bestSource = source
            }
        }
        if (bestSource < 0) {
            Log.w(TAG, "no usable source: $attempts")
            return null
        }
        if (bestRms < SILENT_RMS) {
            // 所有源都极静：仍然用最响的那个录满，把"安静"交给上层判 SILENT_INPUT，
            // 而不是在这里直接失败 —— 静音与"环境太静但确实在录"是两件事。
            Log.w(TAG, "all sources very quiet (best=${sourceName(bestSource)} rms=$bestRms)")
        }
        attempts += "chosen=${sourceName(bestSource)}"

        for (source in intArrayOf(bestSource)) {
            val recorder = createRecorder(source, bufferBytes)
            if (recorder == null) {
                attempts += "${sourceName(source)}=init-failed"
                continue
            }
            val out = ShortArray(ShazamFingerprint.SAMPLE_RATE * total / 1_000)
            var written = 0
            try {
                recorder.startRecording()
                while (written < out.size) {
                    val want = minOf(4096, out.size - written)
                    val read = recorder.read(out, written, want)
                    if (read <= 0) break
                    written += read
                }
            } catch (t: Throwable) {
                Log.w(TAG, "record failed on ${sourceName(source)}", t)
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
            if (written <= 0) {
                attempts += "${sourceName(source)}=no-data"
                continue
            }
            val samples = if (written == out.size) out else out.copyOf(written)
            val rms = ShazamFingerprint.rms(samples)
            attempts += "${sourceName(source)}=rms%.4f".format(rms)
            if (rms >= SILENT_RMS) {
                return Captured(samples, source, rms, attempts)
            }
            Log.w(TAG, "source ${sourceName(source)} silent (rms=$rms), trying next")
        }
        Log.w(TAG, "all sources silent: $attempts")
        return null
    }

    /** 探一小段并按 RMS 打分；初始化/读数失败返回 null。 */
    @SuppressLint("MissingPermission")
    private fun probeSource(source: Int, bufferBytes: Int): Double? {
        val recorder = createRecorder(source, bufferBytes) ?: return null
        val probe = ShortArray(ShazamFingerprint.SAMPLE_RATE * PROBE_MS / 1_000)
        var written = 0
        try {
            recorder.startRecording()
            while (written < probe.size) {
                val read = recorder.read(probe, written, minOf(2048, probe.size - written))
                if (read <= 0) break
                written += read
            }
        } catch (t: Throwable) {
            Log.w(TAG, "probe failed on ${sourceName(source)}", t)
            return null
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        if (written <= 0) return null
        return ShazamFingerprint.rms(if (written == probe.size) probe else probe.copyOf(written))
    }

    /** 音源尝试顺序：**MIC 先**（实测这台机器 UNPROCESSED 基本没输出）。 */
    private val AUDIO_SOURCES = intArrayOf(
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.UNPROCESSED,
    )

    fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> "source$source"
    }

    private fun createRecorder(source: Int, bufferBytes: Int): AudioRecord? {
        val r = runCatching {
            AudioRecord(
                source,
                ShazamFingerprint.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        }.getOrNull() ?: return null
        if (r.state == AudioRecord.STATE_INITIALIZED) return r
        runCatching { r.release() }
        return null
    }
}
