/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.recognize

import android.annotation.SuppressLint
import android.media.MediaRecorder
import android.util.Log
import java.io.File

/**
 * 识曲录音（v3.8.0）：直接录成 **AAC/M4A**。
 *
 * ## 为什么不再用 AudioRecord + PCM
 *
 * 抓包（2026-10-30，官方客户端 9.6.15）显示识曲请求体里的 `recordAudio` 是
 * **base64 的 MP4/AAC 录音**（`\x00\x00\x00\x18ftypmp42`），不是原始波形、也不是签名。
 * 实测用 ffmpeg 编码的 M4A 走同一条请求即可命中（《当你》— 林俊杰），所以客户端这边
 * 只要产出同样的容器即可，签名那条路（[ShazamSignature]）只作为历史实现保留。
 *
 * ## 为什么用 MediaRecorder 而不是 MediaCodec + MediaMuxer
 *
 * 这里只要"一段能解码的 M4A"，不需要逐帧控制、不需要与播放共用时间轴。
 * `MediaRecorder` 一行 `start()/stop()` 就能给出合法容器，少 200 行手写封装，
 * 也少一类"muxer 状态机写错导致文件不可播"的故障面。
 */
object AacRecorder {

    private const val TAG = "RecogAac"

    /** 官方客户端单次识曲约 10 秒。 */
    const val DEFAULT_DURATION_MS = 10_000

    /**
     * 录一段 M4A。失败返回 null（权限、设备占用、编码器不可用）。
     *
     * 取消通过 runInterruptible 中断等待；finally 总是释放录音器并删除临时文件。
     * 不提交录音失败或被中断时的残留文件。
     */
    @SuppressLint("MissingPermission")
    fun record(context: android.content.Context, durationMs: Int = DEFAULT_DURATION_MS): ByteArray? {
        val out = File(context.cacheDir, "recog_${System.currentTimeMillis()}.m4a")
        var recorder: MediaRecorder? = null
        return try {
            val active: MediaRecorder = if (android.os.Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = active
            active.setAudioSource(MediaRecorder.AudioSource.MIC)
            active.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            active.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            active.setAudioChannels(1)
            active.setAudioSamplingRate(44_100)
            active.setAudioEncodingBitRate(64_000)
            active.setOutputFile(out.absolutePath)
            active.prepare()
            active.start()
            Thread.sleep(durationMs.toLong())
            active.stop()
            val bytes = out.readBytes()
            Log.i(TAG, "recorded ${bytes.size}B ($durationMs ms)")
            if (bytes.isEmpty()) null else bytes
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            Log.w(TAG, "record failed", e)
            null
        } finally {
            runCatching { recorder?.release() }
            out.delete()
        }
    }
}
