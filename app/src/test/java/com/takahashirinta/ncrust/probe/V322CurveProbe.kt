/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.probe

import androidx.media3.common.util.UnstableApi
import com.takahashirinta.ncrust.player.AudioFeatureExtractor
import com.takahashirinta.ncrust.ui.player.WaveformRing
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v3.2.2 探针 · §2.5 **曲线平滑算法的实测**（过冲 / 负高度 / 构造耗时）。
 *
 * ## 为什么输入必须是真实波形而不是随机数
 *
 * 「会不会过冲」取决于数据的形状：单调段不过冲是插值算法的**定义性质**，
 * 真正会出事的是「快速起音后立刻掉到接近静音」这种**形状**—— 那正是鼓点。
 * 用随机数或正弦测出来的结论在音乐上没有意义。所以这里把 5 首真实歌曲的 PCM
 * 走一遍**生产**的 `AudioFeatureExtractor` + **生产**的 `WaveformRing`
 * （两级时间常数平滑都在里面），取渲染层实际读到的那 28 个柱高，
 * 再对三种插值算法逐帧求值。
 *
 * ## 判据
 *
 * - **过冲**：`max(curve) - max(data)`（正值 = 超出数据范围）；
 * - **负高度**：`min(curve) < 0` 的采样点个数（铁律 31 明令禁止）；
 * - 采样密度 16 点/段，28 段 ⇒ 每帧 448 个采样点。
 *
 * 结果写进 `docs/verification/v3.2.2/probe/curve-smoothing-measurements.md`。
 */
class V322CurveProbe {

    private companion object {
        const val SUB = 16
        const val BAR_COUNT = 28
        const val RING_CAPACITY = 256
        const val FRAME_MS = 16f
        const val BUFFER_FRAMES = 4410
    }

    /** 一种插值算法的控制点切线。 */
    private enum class Algo(val label: String) {
        CATMULL_ROM("Catmull-Rom（均匀参数化）"),
        MONOTONE_NAIVE("单调三次 · 朴素（中心差分 + 圆条件限幅）"),
        MONOTONE_CUBIC("单调三次 · PCHIP（保号切线 + 圆条件限幅）"),
        CLAMPED_BEZIER("贝塞尔 + 控制点限幅"),
    }

    private class Stats {
        var frames = 0
        var maxOvershoot = 0f
        var maxUndershoot = 0f
        var negativeSamples = 0L
        var totalSamples = 0L
        var framesWithNegative = 0
        var framesWithOvershoot = 0
        var maxCurveValue = 0f
        var maxDataValue = 0f
    }

    // ------------------------------------------------------------------
    // 切线
    // ------------------------------------------------------------------

    /** Catmull-Rom：`m_i = (y_{i+1} − y_{i−1}) / 2dx`（端点单侧差分）。**会过冲**。 */
    private fun catmullRomTangents(y: FloatArray, dx: Float): FloatArray {
        val n = y.size
        val m = FloatArray(n)
        if (n < 2) return m
        m[0] = (y[1] - y[0]) / dx
        m[n - 1] = (y[n - 1] - y[n - 2]) / dx
        for (i in 1 until n - 1) m[i] = (y[i + 1] - y[i - 1]) / (2f * dx)
        return m
    }

    /**
     * **朴素**的「中心差分 + 圆条件限幅」。
     *
     * 这个变体留在探针里不是凑数：它就是「照着 Fritsch–Carlson 的圆条件写一遍」最容易写出的
     * 形状，而实测显示它**会**产生负高度（切线方向与斜率反向时 `|m| ≤ 3|Δ|` 不再把控制点
     * 关在区间内）。它是本次测量里被证伪的那个方案，见 `curve-smoothing-measurements.md`。
     */
    private fun naiveMonotoneTangents(y: FloatArray, dx: Float): FloatArray {
        val n = y.size
        val m = FloatArray(n)
        if (n < 2) return m
        val d = FloatArray(n - 1) { (y[it + 1] - y[it]) / dx }
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) {
                m[i] = 0f
                m[i + 1] = 0f
                continue
            }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
        return m
    }

    /**
     * Fritsch–Carlson / PCHIP 保单调切线。**两条条件缺一不可**：
     *
     *  1. **保号**：`m_i · Δ_i ≤ 0` ⇒ `m_i = 0`。只做第 2 条是不够的 —— 当相邻两段斜率反号、
     *     且前一段更陡时，`m_i` 会取到与 `Δ_i` 相反的符号，此时 `|m_i| ≤ 3|Δ_i|` 根本不把控制点
     *     关在 `[y_i, y_{i+1}]` 内，曲线会冲出区间（探针实测：负高度）。
     *  2. **圆条件**：`a² + b² ≤ 9`（`a = m_i/Δ_i`、`b = m_{i+1}/Δ_i`），否则按 `3/√s` 等比缩小。
     *
     * 内部点用加权调和平均（PCHIP 原式），端点用单侧差分再做一次同向夹取。
     */
    private fun monotoneTangents(y: FloatArray, dx: Float): FloatArray {
        val n = y.size
        val m = FloatArray(n)
        if (n < 2) return m
        val d = FloatArray(n - 1) { (y[it + 1] - y[it]) / dx }
        if (n == 2) {
            m[0] = d[0]
            m[1] = d[0]
            return m
        }
        m[0] = endpointTangent(d[0], d[1])
        m[n - 1] = endpointTangent(d[n - 2], d[n - 3])
        for (i in 1 until n - 1) {
            val dl = d[i - 1]
            val dr = d[i]
            m[i] = if (dl * dr <= 0f) {
                0f
            } else {
                // 加权调和平均（h 全相等 ⇒ 2·dl·dr/(dl+dr)），天然夹在两段斜率之间。
                val w1 = 3f * dx
                val w2 = 3f * dx
                (w1 + w2) / (w1 / dl + w2 / dr)
            }
        }
        // 条件 1：保号（内部点已由调和平均保证；端点的单侧差分可能反向，这里兜底）。
        for (i in 0 until n - 1) {
            if (d[i] == 0f) {
                m[i] = 0f
                m[i + 1] = 0f
            } else {
                if (m[i] * d[i] < 0f) m[i] = 0f
                if (m[i + 1] * d[i] < 0f) m[i + 1] = 0f
            }
        }
        // 条件 2：圆条件。
        for (i in 0 until n - 1) {
            if (d[i] == 0f) continue
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
        return m
    }

    /** 端点切线：单侧差分，且不得与相邻段斜率反号（否则端段会冲出区间）。 */
    private fun endpointTangent(d0: Float, d1: Float): Float =
        if (d0 * d1 <= 0f) 0f else if (abs(d1) > abs(d0)) 3f * d0 else d0

    /** 由 Hermite 切线得到三次贝塞尔的两个控制点 y（x 固定在 1/3 处 ⇒ 时间轴单调）。 */
    private fun controlY(y0: Float, y1: Float, m0: Float, m1: Float, dx: Float): Pair<Float, Float> =
        (y0 + m0 * dx / 3f) to (y1 - m1 * dx / 3f)

    /**
     * 逐段采样一条曲线，返回 [min, max, negativeCount, total]。
     *
     * @param clampControl 是否把控制点 y 夹进该段两端值的区间（贝塞尔凸包性质 ⇒ 曲线不出区间）。
     */
    private fun sample(
        y: FloatArray,
        dx: Float,
        tangents: FloatArray,
        clampControl: Boolean,
    ): FloatArray {
        val n = y.size
        val out = FloatArray((n - 1) * SUB + 1)
        var k = 0
        for (i in 0 until n - 1) {
            var c1 = y[i] + tangents[i] * dx / 3f
            var c2 = y[i + 1] - tangents[i + 1] * dx / 3f
            if (clampControl) {
                val lo = minOf(y[i], y[i + 1])
                val hi = maxOf(y[i], y[i + 1])
                c1 = c1.coerceIn(lo, hi)
                c2 = c2.coerceIn(lo, hi)
            }
            for (s in 0 until SUB) {
                val t = s.toFloat() / SUB
                val u = 1f - t
                out[k++] = u * u * u * y[i] + 3f * u * u * t * c1 + 3f * u * t * t * c2 + t * t * t * y[i + 1]
            }
        }
        out[k] = y[n - 1]
        return out
    }

    private fun accumulate(stats: Stats, data: FloatArray, curve: FloatArray) {
        stats.frames++
        var dataMin = Float.MAX_VALUE
        var dataMax = -Float.MAX_VALUE
        for (v in data) {
            if (v < dataMin) dataMin = v
            if (v > dataMax) dataMax = v
        }
        var curveMin = Float.MAX_VALUE
        var curveMax = -Float.MAX_VALUE
        var neg = 0
        for (v in curve) {
            if (v < curveMin) curveMin = v
            if (v > curveMax) curveMax = v
            if (v < 0f) neg++
        }
        val over = curveMax - dataMax
        if (over > stats.maxOvershoot) stats.maxOvershoot = over
        if (over > 1e-4f) stats.framesWithOvershoot++
        val under = dataMin - curveMin
        if (under > stats.maxUndershoot) stats.maxUndershoot = under
        if (neg > 0) stats.framesWithNegative++
        stats.negativeSamples += neg
        stats.totalSamples += curve.size
        if (curveMax > stats.maxCurveValue) stats.maxCurveValue = curveMax
        if (dataMax > stats.maxDataValue) stats.maxDataValue = dataMax
    }

    @OptIn(UnstableApi::class)
    @Test
    fun curveSmoothingOvershootOnRealWaveforms() {
        val dir = ProbeCorpus.locate()
        assumeTrue("未找到探针音频语料，跳过实测", dir != null)
        val corpus = dir!!
        val outDir = ProbeCorpus.outputDir()

        val stats = HashMap<Algo, Stats>()
        for (a in Algo.values()) stats[a] = Stats()
        var framesTotal = 0L
        var peakBarValue = 0f

        for ((genre, label) in ProbeCorpus.EXPECTED) {
            val track = ProbeCorpus.load(corpus, genre, label)
            val featureFrames = ProbeCorpus.extract(track, BUFFER_FRAMES)
            // 生产环形缓冲：与渲染层同一套攻击/回落时间常数。
            val ring = WaveformRing(capacity = RING_CAPACITY, barCount = BAR_COUNT)
            val bars = FloatArray(BAR_COUNT)
            val peaks = FloatArray(BAR_COUNT)
            val mixes = FloatArray(BAR_COUNT)
            var pushed = 0
            var frameIndex = 0
            // 每 100ms 到达一根柱；帧循环按 16ms 推进 ⇒ 与真机同构（一帧 0~1 根柱）。
            val totalFrames = (featureFrames.lastOrNull()?.tMs ?: 0.0).toInt() / FRAME_MS.toInt()
            var nextPushMs = 0
            while (frameIndex < totalFrames) {
                val nowMs = frameIndex * FRAME_MS.toInt()
                while (nextPushMs <= nowMs && pushed < featureFrames.size) {
                    val f = featureFrames[pushed]
                    ring.push(
                        f.rms,
                        f.low,
                        AudioFeatureExtractor.bandMix(f.low, f.mid, f.high),
                    )
                    pushed++
                    nextPushMs = f.tMs.toInt()
                }
                ring.pump(active = true, dtMs = FRAME_MS)
                if (frameIndex % 4 == 0) {
                    ring.copyInto(bars, peaks, mixes)
                    // 渲染层的真实映射：柱高 = sqrt(柱值)，两者都作用在「高度」上。
                    val heights = FloatArray(BAR_COUNT) { sqrt(bars[it].coerceIn(0f, 1f)) }
                    if (heights.max() > peakBarValue) peakBarValue = heights.max()
                    val dx = 1f
                    val cr = catmullRomTangents(heights, dx)
                    val mono = monotoneTangents(heights, dx)
                    val naive = naiveMonotoneTangents(heights, dx)
                    accumulate(stats[Algo.CATMULL_ROM]!!, heights, sample(heights, dx, cr, clampControl = false))
                    accumulate(stats[Algo.MONOTONE_NAIVE]!!, heights, sample(heights, dx, naive, clampControl = false))
                    accumulate(stats[Algo.MONOTONE_CUBIC]!!, heights, sample(heights, dx, mono, clampControl = false))
                    accumulate(stats[Algo.CLAMPED_BEZIER]!!, heights, sample(heights, dx, cr, clampControl = true))
                    framesTotal++
                }
                frameIndex++
            }
            println("[probe] curve $genre 完成，累计帧 $framesTotal")
        }

        // ---- 构造耗时（纯数学部分；Path.cubicTo 的真机成本见 probe-perf-tier）----
        val timing = HashMap<Algo, Double>()
        val benchHeights = FloatArray(BAR_COUNT) { 0.3f + 0.5f * ((it * 37) % 11) / 11f }
        for (a in Algo.values()) {
            repeat(20_000) { buildCurve(a, benchHeights) }
            val iterations = 200_000
            val t0 = System.nanoTime()
            repeat(iterations) { buildCurve(a, benchHeights) }
            val t1 = System.nanoTime()
            timing[a] = (t1 - t0).toDouble() / iterations
        }

        // ---- 落盘 ----
        val md = StringBuilder()
        md.append("# 探针 §2.5 曲线平滑算法 —— 真实波形实测\n\n")
        md.append("输入：5 首完整歌曲经**生产** `AudioFeatureExtractor`（4410 帧 = 100ms 缓冲）+ ")
        md.append("**生产** `WaveformRing`（28 柱 / 攻击 22ms / 回落 130ms）产出的柱高序列，")
        md.append("按 16ms 帧推进、每 4 帧取一帧参与统计，共 **").append(framesTotal).append("** 帧。\n\n")
        md.append("对每一帧的 28 个柱高（振幅域 = `sqrt(柱值)`）做四种插值，每段采样 16 点。\n\n")
        md.append("| 算法 | 过冲帧数 | 最大过冲 | 最小下冲 | 负高度采样点 | 出现负高度的帧 | 采样点总数 |\n")
        md.append("|---|---|---|---|---|---|---|\n")
        for (a in Algo.values()) {
            val s = stats[a]!!
            md.append("| ").append(a.label)
                .append(" | ").append(s.framesWithOvershoot).append(" / ").append(s.frames)
                .append(" | ").append("%.5f".format(s.maxOvershoot))
                .append(" | ").append("%.5f".format(s.maxUndershoot))
                .append(" | ").append(s.negativeSamples)
                .append(" | ").append(s.framesWithNegative)
                .append(" | ").append(s.totalSamples)
                .append(" |\n")
        }
        md.append("\n数据侧参考值：最大柱高 ").append("%.4f".format(peakBarValue)).append("（振幅域）。\n")
        md.append("\n## 曲线构造耗时（JVM，28 点，200k 次迭代）\n\n")
        md.append("| 算法 | ns/次 |\n|---|---|\n")
        for (a in Algo.values()) md.append("| ").append(a.label).append(" | ").append("%.0f".format(timing[a])).append(" |\n")
        md.append("\n> 四种算法都是 27 段 `cubicTo`，**绘制**成本与算法无关；上表只是控制点计算的 CPU 侧差异，")
        md.append("真机帧时间见 `probe-perf-tier.md`。\n")

        File(outDir, "curve-smoothing-measurements.md").writeText(md.toString())
        println(md)

        // 铁律 31：单调三次不得出现负高度，也不得越出数据范围。
        val mono = stats[Algo.MONOTONE_CUBIC]!!
        assertTrue("单调三次出现负高度采样点: ${mono.negativeSamples}", mono.negativeSamples == 0L)
        assertTrue("单调三次过冲: ${mono.maxOvershoot}", mono.maxOvershoot <= 1e-4f)
        assertTrue("单调三次下冲: ${mono.maxUndershoot}", mono.maxUndershoot <= 1e-4f)
    }

    private fun buildCurve(algo: Algo, y: FloatArray) {
        val dx = 1f
        when (algo) {
            Algo.CATMULL_ROM -> sample(y, dx, catmullRomTangents(y, dx), clampControl = false)
            Algo.MONOTONE_NAIVE -> sample(y, dx, naiveMonotoneTangents(y, dx), clampControl = false)
            Algo.MONOTONE_CUBIC -> sample(y, dx, monotoneTangents(y, dx), clampControl = false)
            Algo.CLAMPED_BEZIER -> sample(y, dx, catmullRomTangents(y, dx), clampControl = true)
        }
    }
}
