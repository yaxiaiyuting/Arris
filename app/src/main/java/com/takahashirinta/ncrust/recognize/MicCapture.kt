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

    /** 采集 [durationMs] 的 PCM16 单声道样本。失败返回 null（权限/设备占用）。 */
    @SuppressLint("MissingPermission")
    fun record(durationMs: Int = 10_000): ShortArray? {
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
        val recorder = createRecorder(bufferBytes) ?: return null
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
            Log.w(TAG, "record failed", t)
            return null
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        if (written <= 0) return null
        return if (written == out.size) out else out.copyOf(written)
    }

    private fun createRecorder(bufferBytes: Int): AudioRecord? {
        val candidates = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in candidates) {
            val r = runCatching {
                AudioRecord(
                    source,
                    ShazamFingerprint.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            }.getOrNull() ?: continue
            if (r.state == AudioRecord.STATE_INITIALIZED) return r
            runCatching { r.release() }
        }
        return null
    }
}
