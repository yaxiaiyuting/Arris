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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Shazam 式短片段指纹（v3.8.0 · 听歌识曲）。
 *
 * ## 为什么不用 Chromaprint
 *
 * Chromaprint 的 12 维 chroma 依赖**能量分布**做量化，而 Visualizer 降级路径只有 8 bit
 * （见识曲报告 §4）；量化误差会直接改子指纹位。且 AcoustID 的 `duration` 语义是**整曲**，
 * 拿它匹配 10 秒片段在结构上就不对。Shazam 式峰值哈希对幅度不敏感（只比相对强度），
 * 天生适合"几秒 + 窄带 + 有损"的输入。
 *
 * ## 参数（可在单测里固定）
 *
 * - 采样 16 kHz（与 `BlowRecorder` 一致，麦克风与内录都能直接喂）
 * - 窗 4096 点（256 ms），Hann 窗；hop 1024 点（64 ms）
 * - 频谱分 4 个频带（0–2k / 2k–4k / 4k–6k / 6k–8k），每帧每带取最强峰
 * - 组合哈希：同一帧的峰与其后 1..[DT_MAX] 帧的峰配对，`(f1, f2, dt)` → Int
 *
 * ## 已知局限（写在这里，免得实现者以为是 bug）
 *
 * - 这不是字节级复刻 Shazam 的指纹，**不能**直接与服务端 `signature` 格式互通；
 *   它服务的是"我们自己的指纹库 / 未来的自建匹配"，或作为**本地预筛**。
 * - 服务端识别仍走 `ShazamProvider` 的原始音频提交（见该类 KDoc 的风险说明）。
 */
object ShazamFingerprint {

    const val SAMPLE_RATE = 16_000
    const val WINDOW = 4096
    const val HOP = 1024
    const val DT_MAX = 8

    /** 频带边界（Hz）。 */
    private val BANDS_HZ = intArrayOf(0, 2_000, 4_000, 6_000, 8_000)

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
        val window = hann(WINDOW)
        val peaks = ArrayList<Peak>(frames * 4)
        val re = FloatArray(WINDOW)
        val im = FloatArray(WINDOW)

        for (f in 0 until frames) {
            val offset = f * HOP
            for (i in 0 until WINDOW) {
                re[i] = (samples[offset + i] / 32768f) * window[i]
                im[i] = 0f
            }
            fft(re, im)
            val bins = WINDOW / 2
            val binHz = SAMPLE_RATE.toFloat() / WINDOW

            // 全频段局部极大 + 每帧限量。
            //
            // 旧实现"每个频带必取一个最强 bin" ⇒ 每帧固定 4 个峰，静音时也一样（实测 84 帧 ×4=336），
            // 那些峰全是噪声，重合率自然是碰运气。现在：
            //  ① 峰必须是 ±NEIGHBOR_BINS 邻域内的极大；
            //  ② 幅度要高于**本帧全体均值**的 PEAK_RATIO 倍（不再用分带均值，避免低频泄漏主导）；
            //  ③ 每帧只留最强的 MAX_PEAKS_PER_FRAME 个。
            val mags = FloatArray(bins) { k ->
                sqrt(re[k] * re[k] + im[k] * im[k])
            }
            var sum = 0f
            for (k in 1 until bins) sum += mags[k]
            val frameMean = sum / (bins - 1)
            val floorMag = max(MIN_MAGNITUDE, frameMean * PEAK_RATIO)
            val candidates = ArrayList<Int>(8)
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
                if (isLocalMax) candidates.add(k)
            }
            if (candidates.isEmpty()) continue
            candidates.sortByDescending { mags[it] }
            var kept = 0
            for (k in candidates) {
                peaks.add(Peak(f, k, mags[k]))
                kept++
                if (kept >= MAX_PEAKS_PER_FRAME) break
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

    /** 让编译器别抱怨未使用的 import（ln 在调试日志里用过，保留说明）。 */
    private val lnRef: (Double) -> Double = ::ln
    private val absRef: (Float) -> Float = ::abs
}
