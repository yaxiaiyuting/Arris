/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 指纹算法思路参考 SongRec（GPL-3.0+，https://github.com/marin-m/SongRec）的
 * `algorithm.rs`：频谱峰值 → 组合哈希。**没有搬运其源码**，按公开描述重写，以便在 JVM 上单测。
 */

package com.takahashirinta.ncrust.recognize

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 实验室的本地音频指纹。用 Hann 窗分析 16 kHz PCM，
 * 每帧保留六个最强局部峰，再与后续帧的峰组合成哈希。
 */
object ShazamFingerprint {

    const val SAMPLE_RATE = 16_000
    const val WINDOW = 4096
    const val HOP = 1024
    const val DT_MAX = 8

    data class Peak(val frame: Int, val freqBin: Int, val magnitude: Float)

    data class Fingerprint(
        val peaks: List<Peak>,
        val hashes: IntArray,
    ) {
        val isEmpty: Boolean get() = hashes.isEmpty()
    }

    /**
     * 计算指纹。
     *
     * @param samples PCM16 单声道
     * @param sampleRate 必须等于 [SAMPLE_RATE]（不做重采样：采集侧就按这个参数录）
     */
    fun compute(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): Fingerprint {
        require(sampleRate == SAMPLE_RATE) { "只接受 $SAMPLE_RATE Hz 输入，实际 $sampleRate" }
        if (samples.size < WINDOW) return Fingerprint(emptyList(), IntArray(0))

        val frames = (samples.size - WINDOW) / HOP + 1
        val window = hannCache
        val peaks = ArrayList<Peak>(frames * 4)
        val re = FloatArray(WINDOW)
        val im = FloatArray(WINDOW)
        val bins = WINDOW / 2
        val mags = FloatArray(bins)
        val strongest = IntArray(MAX_PEAKS_PER_FRAME)

        for (f in 0 until frames) {
            val offset = f * HOP
            for (i in 0 until WINDOW) {
                re[i] = (samples[offset + i] / 32768f) * window[i]
                im[i] = 0f
            }
            fft(re, im)
            // 直流分量参与邻域比较，均值从第一个非直流频点起算。
            mags[0] = sqrt(re[0] * re[0] + im[0] * im[0])
            var sum = 0f
            for (k in 1 until bins) {
                val magnitude = sqrt(re[k] * re[k] + im[k] * im[k])
                mags[k] = magnitude
                sum += magnitude
            }
            val frameMean = sum / (bins - 1)
            val floorMag = max(MIN_MAGNITUDE, frameMean * PEAK_RATIO)
            var count = 0
            for (k in max(1, NEIGHBOR_BINS) until bins - NEIGHBOR_BINS) {
                val m = mags[k]
                if (m <= floorMag) continue
                var isLocalMax = true
                for (d in -NEIGHBOR_BINS..NEIGHBOR_BINS) {
                    if (d == 0) continue
                    if (mags[k + d] > m) {
                        isLocalMax = false
                        break
                    }
                }
                if (!isLocalMax) continue

                // 幅度相同的峰按频点升序保留，与原稳定排序一致。
                var index = count
                while (index > 0 && mags[strongest[index - 1]] < m) index--
                if (index == MAX_PEAKS_PER_FRAME) continue
                for (slot in min(count, strongest.lastIndex) downTo index + 1) {
                    strongest[slot] = strongest[slot - 1]
                }
                strongest[index] = k
                count = min(count + 1, MAX_PEAKS_PER_FRAME)
            }
            for (index in 0 until count) {
                val bin = strongest[index]
                peaks.add(Peak(f, bin, mags[bin]))
            }
        }

        return Fingerprint(peaks, hashPeaks(peaks))
    }

    /** 峰 → 组合哈希。同一帧只与"更靠后、且频率更高"的峰配对，保证方向一致、可去重。 */
    fun hashPeaks(peaks: List<Peak>): IntArray {
        if (peaks.size < 2) return IntArray(0)
        val byFrame = peaks.groupBy { it.frame }
        val out = IntArrayBuilder()
        for (p in peaks) {
            for (dt in 1..DT_MAX) {
                val later = byFrame[p.frame + dt] ?: continue
                for (q in later) {
                    if (q.freqBin <= p.freqBin) continue
                    out.add(hash(p.freqBin, q.freqBin, dt))
                }
            }
        }
        return out.toArray()
    }

    /** `(f1, f2, dt)` → 32 bit。取模与掩码固定在代码里，改它就是换指纹（缓存必须一起失效）。 */
    fun hash(f1: Int, f2: Int, dt: Int): Int {
        val h = (f1 and 0x1FF) or ((f2 and 0x1FF) shl 9) or ((dt and 0x1F) shl 18)
        // 简单的位混合，避免低位分布过于规律
        var x = h * -0x61c88647
        x = x xor (x ushr 15)
        return x
    }

    // ------------------------------------------------------------ 内部实现

    private const val PEAK_RATIO = 2.5f

    /** 局部极大判据的邻域半宽（bin）。 */
    private const val NEIGHBOR_BINS = 3

    /** 每帧最多保留几个峰（Shazam 类实现通常在 3~10 之间）。 */
    private const val MAX_PEAKS_PER_FRAME = 6
    private const val MIN_MAGNITUDE = 5e-4f

    private val hannCache: FloatArray by lazy { hann(WINDOW) }

    private fun hann(n: Int): FloatArray = FloatArray(n) { i ->
        (0.5 * (1 - cos(2 * PI * i / (n - 1)))).toFloat()
    }

    /** 原地基 2 迭代 FFT（长度必须是 2 的幂）。 */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var curR = 1f
                var curI = 0f
                for (k in 0 until len / 2) {
                    val uR = re[i + k]
                    val uI = im[i + k]
                    val vR = re[i + k + len / 2] * curR - im[i + k + len / 2] * curI
                    val vI = re[i + k + len / 2] * curI + im[i + k + len / 2] * curR
                    re[i + k] = uR + vR
                    im[i + k] = uI + vI
                    re[i + k + len / 2] = uR - vR
                    im[i + k + len / 2] = uI - vI
                    val nextR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nextR
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** 极简 int 动态数组（避免装箱与额外依赖）。 */
    private class IntArrayBuilder(initial: Int = 256) {
        private var data = IntArray(initial)
        private var size = 0

        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }

        fun toArray(): IntArray = data.copyOf(size)
    }

    /** 供"自检"用：把 PCM 的 RMS 算出来，静音判定不靠猜。 */
    fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) {
            val v = s / 32768.0
            sum += v * v
        }
        return sqrt(sum / samples.size)
    }

    /** 调试用：指纹摘要（前 N 个哈希 + 总数），便于自检页面展示与比对。 */
    fun summarize(fp: Fingerprint, take: Int = 6): String {
        if (fp.isEmpty) return "peaks=${fp.peaks.size} hashes=0"
        val head = fp.hashes.take(take).joinToString(",") { it.toString() }
        return "peaks=${fp.peaks.size} hashes=${fp.hashes.size} head=[$head]"
    }

    /** 仅用于单测：明确两个指纹的重合率。 */
    fun overlapRatio(a: IntArray, b: IntArray): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val setA = a.toHashSet()
        val setB = b.toHashSet()
        val inter = setA.count { it in setB }
        return inter.toFloat() / min(setA.size, setB.size).toFloat()
    }
}
