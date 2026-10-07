/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * v3.3.2 波形滚动抖动的**判据回归**。
 *
 * ## 这个文件为什么长这样
 *
 * 前六次修复全部失败，根因不是「没改对代码」，而是**判据的口径错了三次**：
 * 只量相位差分 / 把窗口平移硬编码成 +1 / 自建 `shiftedCells + frac(phase)` 的「可见位置」。
 * 三种口径都能自圆其说地给出绿灯，而画面照抖。
 *
 * 所以这里的口径**只有一条**，就是渲染层真正消费的那两个数：
 *
 * ```kotlin
 * val phase = WaveformStore.scrollPhase01()
 * val v = window[i] + (window[i + 1] - window[i]) * phase
 * ```
 *
 * ⇒ 每帧的可见位移 ≡ `Δ(窗口平移格) + Δ(scrollPhase01())`。没有第三个自由度。
 * 实现在 [WaveformScrollJitterHarness]，它驱动的是**真实的** [WaveformRing]。
 *
 * ## 钉住的四条
 *
 * | 用例 | 钉住的机制 |
 * |---|---|
 * | [分母必须在到达处测量 —— 不许像 v3_2_4 那样单调爬升] | **主因**：柱间隔被 vsync 量化 ⇒ 斜坡按 0.65 倍速爬 |
 * | [真实音乐幅度下位移必须匀速 —— 安静缓冲不许让画面顿住] | 相位**冻在** `coerceIn(0,1)` 顶棚 ⇒ 位移连续几帧 0.0000 再一次冲出 1.1 格 |
 * | [相位不许停在 1 点 0 顶棚] | 上面那条的另一种量法（直接看相位本身） |
 * | [纯函数 foldScrollPhase] | 折回余量而不是清零；巨额 dt 与非法输入都不越界 |
 *
 * 改前 / 改后（同一台仿真、同一口径，见 [WaveformScrollJitterDiagTest]）：
 *
 * | 场景 | 改前抖动率 | 改后抖动率 | 改前顿帧 | 改后顿帧 |
 * |---|---|---|---|---|
 * | 理想帧 + 理想柱 | 6.09% | 6.09% | 2 | 2 |
 * | 仅帧时间抖动 | 14.34% | 14.34% | 2 | 2 |
 * | 帧抖 + 柱抖 | 22.20% | 22.20% | 22 | 22 |
 * | 真实音乐（每 20 柱一安静缓冲） | 50.00% | **0.00%** | 45 | **0** |
 * | 真实音乐（每 8 柱一安静缓冲） | 79.87% | **0.00%** | 114 | **0** |
 *
 * 注意「仅帧时间抖动」一列改前改后**完全相同** —— 帧时间抖动本身不是缺陷，
 * 位移本来就该正比于 `dt`。修掉的是分母量化与相位冻死。
 */
class WaveformScrollJitterRegressionTest {

    /** 100ms 一根柱、每根带一档 RMS。 */
    private fun bars(seconds: Double, values: List<Float>): List<Pair<Double, Float>> {
        val out = ArrayList<Pair<Double, Float>>()
        var t = 5.0
        var i = 0
        while (t < seconds * 1000.0) {
            out.add(t to values[i % values.size])
            t += 100.0
            i++
        }
        return out
    }

    private fun idealFrames(hz: Double, seconds: Double): List<Double> {
        val step = 1000.0 / hz
        val out = ArrayList<Double>()
        var t = 0.0
        while (t < seconds * 1000.0) {
            out.add(t)
            t += step
        }
        return out
    }

    private fun run(seconds: Double, barValues: List<Float>): WaveformScrollJitterHarness {
        val h = WaveformScrollJitterHarness()
        val frames = idealFrames(60.0, seconds)
        val bars = bars(seconds, barValues)
        var bi = 0
        var fi = 0
        while (fi < frames.size) {
            val ft = frames[fi]
            if (bi < bars.size && bars[bi].first <= ft) {
                h.pushBar(bars[bi].first, bars[bi].second)
                bi++
            } else {
                h.advanceTo(ft)
                fi++
            }
        }
        return h
    }

    /**
     * **主因的指纹**：柱间隔必须收敛到真值（100ms），不许单调爬升。
     *
     * v3.2.4 在 UI 消费处倒推分母，被 vsync 量化成 100 / 116.7 / 133.3ms 三者之一，
     * 滑动平均于是**一路爬到 155ms 且不停**。分母偏大 ⇒ 相位按 0.65 倍速爬 ⇒
     * 爬不满一格就撞上限幅 ⇒ 画面顿住再冲出。
     *
     * 这条用例是整组里最灵敏的一条：把分母改回消费处测量，它立刻变红。
     */
    @Test
    fun `分母必须在到达处测量 —— 不许像 v3_2_4 那样单调爬升`() {
        val h = run(seconds = 20.0, barValues = listOf(0.6f))
        val reported = h.frames.drop(120).map { it.intervalMs }
        assertTrue("样本太少", reported.size > 800)
        val first = reported.take(200).average()
        val last = reported.takeLast(200).average()
        assertEquals("柱间隔的平均必须收敛到真值 100ms（实际 $last）", 100.0, last, 6.0)
        assertTrue(
            "柱间隔从 $first 爬到 $last —— v3.2.4 的指纹是单调爬升（实测 100 → 155ms），" +
                "说明分母又被 vsync 量化了",
            last <= first + 4.0,
        )
    }

    /**
     * **症状的量化**：真实音乐幅度下（周期性出现低于空转门槛的单根缓冲），
     * 每帧可见位移必须匀速。
     *
     * v3.2.4 在这里量到的是「连续 5 帧 0.0000，紧接一帧 1.0000」（名义步长的 600%）——
     * 因为它的信号门槛挂在**未平滑的原始柱高**上，单根安静缓冲就让相位冻住。
     */
    @Test
    fun `真实音乐幅度下位移必须匀速 —— 安静缓冲不许让画面顿住`() {
        // 每 8 根一根安静缓冲（0.005 < ANIMATION_MIN_SIGNAL = 0.02），其余 0.08~0.9。
        val values = List(8) { i -> if (i == 7) 0.005f else 0.1f + i * 0.1f }
        val h = run(seconds = 20.0, barValues = values)
        val s = h.summary()
        assertTrue("样本太少", s.frames > 900)
        // 匀速 ⇒ 每帧位移 ≈ dt / 柱间隔。允许帧量化带来的一帧误差（≤1 帧的量），
        // 但绝不允许 v3.2.4 那种「0.0000 ×5 帧 + 1.0000 ×1 帧」。
        assertTrue(
            "最大步 ${s.maxStep} 是名义 ${s.nominalStep} 的 ${s.maxStepRatio} 倍 —— 超过 1.6 倍就是「冲」",
            s.maxStepRatio <= 1.6f,
        )
        assertTrue("最小步 ${s.minStep}（${s.minStepRatio} 倍名义）—— 出现 0 就是「顿」", s.minStepRatio >= 0.4f)
        assertEquals("不该有倒退（相位单调推进）", 0, s.backsteps)
        assertEquals("不该有「顿」帧（位移 < 25% 名义）", 0, s.stallCount)
        // 抖动率 = 步长 sd ÷ 名义步长。理想帧 + 准确分母 ⇒ 量级上应该是个位数百分比；
        // v3.2.4 在同样输入下是 50%~80%。
        assertTrue("抖动率 ${s.jitterRatio * 100}% 应低于 10%", s.jitterRatio < 0.10)
    }

    /** 相位本身不许停在 `coerceIn(0,1)` 的 1.0 顶棚上（停住 = 画面原地不动）。 */
    @Test
    fun `相位不许停在 1 点 0 顶棚`() {
        val values = List(8) { i -> if (i == 7) 0.005f else 0.1f + i * 0.1f }
        val h = run(seconds = 20.0, barValues = values)
        val body = h.frames.drop(120)
        val ceiling = body.count { it.phase >= 0.999f }
        assertTrue(
            "相位在 1.0 上停留了 $ceiling 帧 / ${body.size} —— 超过 2% 就说明斜坡爬不满一格",
            ceiling <= body.size * 0.02,
        )
    }

    /** 位移总量必须等于窗口平移的格数（不许因为折回而丢位移或凭空多出位移）。 */
    @Test
    fun `位移总量等于平移格数 —— 折回不丢位移也不多给`() {
        val h = run(seconds = 20.0, barValues = listOf(0.6f))
        val s = h.summary()
        // 分析窗口内消费掉的格数 + 首尾相位差 = 逐帧位移之和。
        val shiftedSpan = h.frames.last().shiftedCells - h.frames[WaveformScrollJitterHarness.SKIP_FRAMES].shiftedCells
        assertTrue(
            "位移总量 ${s.advancedCells} 与平移格数 $shiftedSpan 对不上",
            kotlin.math.abs(s.advancedCells - shiftedSpan) <= 1.0,
        )
    }

    // ------------------------------------------------------------------
    // 纯函数：折回余量（换算层，与帧时序无关）
    // ------------------------------------------------------------------

    @Test
    fun `foldScrollPhase 折回余量而不是清零`() {
        // 一个周期 100ms、消费 1 格 ⇒ 余量 = 超出部分（这就是「这一帧多出来的位移」）。
        assertEquals(16.0f, WaveformRing.foldScrollPhase(116f, 100f, 1), 1e-4f)
        assertEquals(0.0f, WaveformRing.foldScrollPhase(100f, 100f, 1), 1e-4f)
        // 一帧里追回 3 格：减掉 3 个间隔。
        assertEquals(12.0f, WaveformRing.foldScrollPhase(312f, 100f, 3), 1e-4f)
    }

    @Test
    fun `foldScrollPhase 对非法输入与巨额 dt 都有界`() {
        assertEquals(0f, WaveformRing.foldScrollPhase(Float.NaN, 100f, 1), 0f)
        assertEquals(0f, WaveformRing.foldScrollPhase(Float.POSITIVE_INFINITY, 100f, 1), 0f)
        // 间隔非法 ⇒ 回落默认值，结果仍落在 [0, interval)。
        val fallback = WaveformRing.foldScrollPhase(150f, Float.NaN, 1)
        assertTrue("间隔非法时余量越界: $fallback", fallback in 0f..WaveformRing.DEFAULT_BAR_INTERVAL_MS)
        // 暂停恢复式的巨额 dt：折到一格之内，不越界、不出现大数精度损失。
        val huge = WaveformRing.foldScrollPhase(10_000_000f, 100f, 1)
        assertTrue("巨额 dt 余量越界: $huge", huge in 0f..100f)
        // 消费 0 格时余量原样保留（这一帧没有柱到达）。
        assertEquals(42f, WaveformRing.foldScrollPhase(42f, 100f, 0), 1e-4f)
    }
}
