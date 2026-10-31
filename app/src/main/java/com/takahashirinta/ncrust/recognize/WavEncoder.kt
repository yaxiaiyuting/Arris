/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.recognize

import java.io.ByteArrayOutputStream

/**
 * PCM16 → WAV（v3.8.0 · 听歌识曲）。
 *
 * 为什么需要它：第三方识别接口（AudD / ACRCloud / 网易云 `music/recognize`）
 * 都要求**带容器的音频文件**，而我们手里的只有裸 PCM。WAV 头 44 字节、无压缩、
 * 不需要任何依赖，是唯一"零成本"的选择。
 */
object WavEncoder {

    /** 单声道 16 bit WAV。 */
    fun encode(samples: ShortArray, sampleRate: Int = ShazamFingerprint.SAMPLE_RATE): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        val byteRate = sampleRate * 2 // 单声道 × 16 bit

        out.write("RIFF".toByteArray())
        out.writeLE32(36 + dataSize)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.writeLE32(16)          // PCM 子块大小
        out.writeLE16(1)           // format = PCM
        out.writeLE16(1)           // 声道数
        out.writeLE32(sampleRate)
        out.writeLE32(byteRate)
        out.writeLE16(2)           // block align
        out.writeLE16(16)          // bits per sample
        out.write("data".toByteArray())
        out.writeLE32(dataSize)
        for (s in samples) {
            val v = s.toInt()
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLE16(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLE32(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
        write((v shr 16) and 0xFF)
        write((v shr 24) and 0xFF)
    }
}
