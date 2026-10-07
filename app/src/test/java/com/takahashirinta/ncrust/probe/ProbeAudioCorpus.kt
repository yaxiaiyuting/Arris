/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.probe

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.takahashirinta.ncrust.player.AudioFeatureExtractor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v3.2.2 探针的**音频语料读取层**（只在探针单测里使用，不进生产代码）。
 *
 * 为什么它是单测而不是一份独立的 Python 脚本：探针要回答的问题（主导频段切换频率、
 * 曲线过冲）**必须用生产代码本身**去算 —— 用另一份语言重写一遍滤波递推，
 * 结论就变成「我抄的那份对不对」，而不是「生产代码会怎样」。
 * 这里直接把真实歌曲的 PCM 灌进 `AudioFeatureExtractor`（生产类，一行未改），
 * 与真机上音频线程走的是同一条递推。
 *
 * ## 语料的位置（刻意不放进仓库）
 *
 * 语料是 5 首 320 kbps 的完整歌曲（约 200MB），**不入 git**。查找顺序：
 *  1. 系统属性 `ncrust.probe.audio`；
 *  2. 环境变量 `NCRUST_PROBE_AUDIO`；
 *  3. 默认相对路径 `../../.scratch/v322/audio`（相对模块目录 `app/`，即仓库外的工作目录）。
 *
 * 三个都没有时，探针单测 **skip**（`Assume`）—— 没有语料就不出数，
 * 绝不用合成信号或占位数字冒充真实测量（铁律 11）。
 */
object ProbeCorpus {

    /** 探针语料的期望文件名（与 `dl.py` 下载脚本一致）。 */
    val EXPECTED = listOf(
        "electronic" to "Faded / Alan Walker（电子）",
        "classical" to "Vivaldi Four Seasons Spring I / Joshua Bell（古典）",
        "vocal" to "大鱼(唱片版) / 周深（人声）",
        "rap" to "Lose Yourself / Eminem（说唱）",
        "instrumental" to "Summer / 久石譲（纯音乐）",
    )

    fun locate(): File? {
        val candidates = listOfNotNull(
            System.getProperty("ncrust.probe.audio")?.let { File(it) },
            System.getenv("NCRUST_PROBE_AUDIO")?.let { File(it) },
            File("../../.scratch/v322/audio"),
        )
        return candidates.firstOrNull { dir ->
            dir.isDirectory && EXPECTED.all { (name, _) -> File(dir, "$name.wav").isFile }
        }
    }

    /** 输出目录（探针产物落盘位置）：`docs/verification/v3.2.2/probe/`。 */
    fun outputDir(): File {
        val override = System.getProperty("ncrust.probe.out")
        val dir = if (override != null) File(override) else File("../docs/verification/v3.2.2/probe")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    /** 一首歌的解码结果。 */
    class Track(
        val genre: String,
        val label: String,
        val sampleRate: Int,
        val channels: Int,
        /** 交错 PCM（16 bit 有符号，小端）。 */
        val samples: ShortArray,
    ) {
        val durationMs: Double
            get() = samples.size.toDouble() / channels / sampleRate * 1000.0
    }

    fun load(dir: File, genre: String, label: String): Track {
        val wav = readWav(File(dir, "$genre.wav"))
        return Track(genre, label, wav.sampleRate, wav.channels, wav.samples)
    }

    // ------------------------------------------------------------------
    // WAV（RIFF / PCM 16 bit）读取 —— 只认 ffmpeg 生成的那一种布局
    // ------------------------------------------------------------------

    private class Wav(val sampleRate: Int, val channels: Int, val samples: ShortArray)

    private fun readWav(file: File): Wav {
        val bytes = file.readBytes()
        require(bytes.size > 44) { "wav 太小: ${file.path}" }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "不是 RIFF: ${file.path}" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "不是 WAVE: ${file.path}" }
        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var dataOffset = -1
        var dataLength = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = le32(bytes, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    channels = le16(bytes, body + 2)
                    sampleRate = le32(bytes, body + 4)
                    bits = le16(bytes, body + 14)
                }
                "data" -> {
                    dataOffset = body
                    dataLength = minOf(size, bytes.size - body)
                }
            }
            pos = body + size + (size and 1)
        }
        require(dataOffset >= 0 && sampleRate > 0 && channels > 0 && bits == 16) {
            "只支持 16bit PCM wav: ${file.path} (rate=$sampleRate ch=$channels bits=$bits)"
        }
        val count = dataLength / 2
        val out = ShortArray(count)
        for (i in 0 until count) {
            val lo = bytes[dataOffset + i * 2].toInt() and 0xFF
            val hi = bytes[dataOffset + i * 2 + 1].toInt()
            out[i] = (((hi shl 8) or lo).toShort())
        }
        return Wav(sampleRate, channels, out)
    }

    private fun le16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    // ------------------------------------------------------------------
    // 用生产提取器逐缓冲跑一遍
    // ------------------------------------------------------------------

    /** 一次缓冲的特征快照（与 `AudioFeatureExtractor` 的输出字段一一对应）。 */
    class BandFrame(
        /** 缓冲结束时刻（毫秒，相对歌曲开头）。 */
        val tMs: Double,
        val rms: Float,
        val low: Float,
        val mid: Float,
        val high: Float,
    )

    /**
     * 按 [bufferFrames] 帧一块把整首歌喂给 [AudioFeatureExtractor]。
     *
     * `bufferFrames` 的取值依据：真机实测（S6 / Android 7.0）`TeeAudioProcessor`
     * 一次回调是 **4410 帧 = 100ms**（`docs/verification/v3.0.0/probe/EVIDENCE.md`
     * 的 `PROBE-AUDIO-TAP`），所以 S6 上 `low/mid/high` 的**更新率就是 10 Hz**。
     * 探针同时跑 1024 帧（约 23ms ≈ 43 Hz）做敏感度对照 —— 现代设备若缓冲更细，
     * 切换频率只会更高，不会更低。
     */
    @OptIn(UnstableApi::class)
    fun extract(track: Track, bufferFrames: Int = 4410): List<BandFrame> {
        val extractor = AudioFeatureExtractor()
        extractor.configure(track.sampleRate, track.channels)
        check(extractor.available) { "提取器不可用（configure 失败）" }
        val interleavedPerBuffer = bufferFrames * track.channels
        val total = track.samples.size
        val raw = ByteBuffer.allocate(interleavedPerBuffer * 2).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<BandFrame>(total / interleavedPerBuffer + 2)
        var index = 0
        var processedFrames = 0L
        while (index < total) {
            val n = minOf(interleavedPerBuffer, total - index)
            raw.clear()
            for (i in 0 until n) raw.putShort(track.samples[index + i])
            raw.flip()
            if (extractor.process(raw, C.ENCODING_PCM_16BIT)) {
                processedFrames += n / track.channels
                out.add(
                    BandFrame(
                        tMs = processedFrames.toDouble() / track.sampleRate * 1000.0,
                        rms = extractor.rms,
                        low = extractor.low,
                        mid = extractor.mid,
                        high = extractor.high,
                    )
                )
            }
            index += n
        }
        return out
    }

    /** 把一帧写成 CSV 行（证据留档用）。 */
    fun csvHeader(): String = "t_ms,rms,low,mid,high"

    fun csvRow(f: BandFrame): String =
        "%.1f,%.6f,%.6f,%.6f,%.6f".format(f.tMs, f.rms, f.low, f.mid, f.high)
}
