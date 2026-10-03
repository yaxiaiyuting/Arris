/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.4 · P1：帧率无关性回归（真实 WaveformRing + 真实帧步长）。
 */

package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.ui.player.motion.DisplayRefresh
import com.takahashirinta.ncrust.ui.player.motion.FrameStride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil

/**
 * v3.2.4 · P1 的**回归单测**：波形的滚动必须在 60 / 90 / 120 / 144Hz 上是**同一条运动曲线**。
 *
 * 它驱动的是**真实的 [WaveformRing]**（不是复刻的公式），只有帧到达方式是被模拟的：
 * 帧时间戳按 `1000/刷新率 ± 抖动` 生成，`dt` 取相邻两次推进的时间差，
 * 「这一帧推不推进」由生产代码用的 [FrameStride] + [DisplayRefresh.strideFor] 决定。
 *
 * 三条判据（都是与刷新率无关的量）：
 *
 * 1. **推进率 = 面板刷新率**（步长 1 时）—— 这是用户能直接看到的「跟不跟手」；
 * 2. **没有任何一帧的 `dt` 超过名义帧间隔的 1.2 倍** —— 这一条直接钉死 v3.2.3 的缺陷形状：
 *    时间阈值闸门会在 120Hz 上把「2 个 vsync」偶尔变成「3 个 vsync」（dt = 25ms = 名义的 1.5 倍）。
 *    见 [旧的时间阈值闸门在 120Hz 上会产生量化跳变（特征化测试）]；
 * 3. **滚动位移总量 = 消费掉的柱数** —— 每个音频缓冲（实测 100.00ms / 11Hz）恰好推进一格，
 *    与刷新率无关。这条不变量就是「30fps 与 120fps 画的是同一条曲线」的数学形式。
 */
class WaveformFrameRateTest {

    private data class Run(
        val advances: Int,
        val barsPushed: Int,
        val nominalMs: Float,
        val maxDtMs: Float,
        val advancedCells: Double,
        val wraps: Int,
        val fps: Float,
    )

    /**
     * @param jitterMs 帧时间戳的抖动幅度（均匀分布 ±jitterMs）。真实设备上
     *   frame pacing 抖动就在这个量级，而它正是把闸门从「2 帧」推到「3 帧」的那根稻草。
     */
    private fun simulate(
        hz: Float,
        lowTier: Boolean,
        seconds: Float = 20f,
        jitterMs: Float = 0.6f,
        seed: Long = 20260929L,
    ): Run {
        val ring = WaveformRing(capacity = 256, barCount = 8)
        val stride = FrameStride(DisplayRefresh.strideFor(hz, lowTier))
        // 帧**到达**的间隔永远是面板的刷新周期；步长只决定「到来的帧里哪几帧真的推进」。
        val vsyncMs = DisplayRefresh.frameIntervalMs(hz)
        val nominal = vsyncMs * stride.effectiveStride
        val rnd = Random(seed)
        var t = 0.0
        var nextBar = 0.0
        var lastAdvance = -1.0
        var lastPhase = 0f
        var advancedCells = 0.0
        var wraps = 0
        var advances = 0
        var barsPushed = 0
        var maxDt = 0f
        val totalMs = seconds * 1000.0

        while (t < totalMs) {
            while (nextBar <= t) {
                ring.push(value = 0.6f, low = 0.5f, mid = 0.4f, high = 0.3f)
                nextBar += 100.0
                barsPushed++
            }
            if (stride.shouldAdvance()) {
                val dt = if (lastAdvance < 0.0) nominal else (t - lastAdvance).toFloat()
                lastAdvance = t
                if (advances > 0) maxDt = maxOf(maxDt, dt)
                ring.pump(active = true, dtMs = dt)
                advances++
                val phase = ring.scrollPhase01()
                if (phase >= lastPhase) {
                    advancedCells += (phase - lastPhase).toDouble()
                } else {
                    // 消费到新柱：相位回绕，窗口整体左移一格。
                    advancedCells += (1f - lastPhase) + phase
                    wraps++
                }
                lastPhase = phase
            }
            t += vsyncMs + (rnd.nextDouble() * 2 - 1) * jitterMs
        }
        return Run(
            advances = advances,
            barsPushed = barsPushed,
            nominalMs = nominal,
            maxDtMs = maxDt,
            advancedCells = advancedCells,
            wraps = wraps,
            fps = advances / seconds,
        )
    }

    @Test
    fun `非低内存设备在四档刷新率上推进率都等于面板刷新率`() {
        listOf(60f, 90f, 120f, 144f).forEach { hz ->
            val run = simulate(hz, lowTier = false)
            val expected = hz
            assertTrue(
                "${hz}Hz 上实测 ${run.fps}fps —— 推进率必须跟随面板（v3.2.3 在 90Hz 上只有 45fps）",
                abs(run.fps - expected) / expected < 0.02f,
            )
        }
    }

    @Test
    fun `四档刷新率上都不存在超过名义帧间隔 1_2 倍的帧 —— 没有闸门量化`() {
        listOf(60f, 90f, 120f, 144f).forEach { hz ->
            val run = simulate(hz, lowTier = false)
            val ratio = run.maxDtMs / run.nominalMs
            assertTrue(
                "${hz}Hz 上最大 dt=${run.maxDtMs}ms，是名义帧间隔 ${run.nominalMs}ms 的 ${ratio} 倍 —— " +
                    "超过 1.2 倍说明有「多跨一个 vsync」的量化（v3.2.3 的缺陷形状）",
                ratio <= 1.2f,
            )
        }
    }

    @Test
    fun `滚动位移总量等于消费掉的柱数 —— 与刷新率无关的同一条运动曲线`() {
        listOf(60f, 90f, 120f, 144f).forEach { hz ->
            val run = simulate(hz, lowTier = false)
            // 每个音频缓冲（100ms）推进恰好一格。窗口首尾各有不到一格的边界余量。
            assertTrue(
                "${hz}Hz 上位移 ${run.advancedCells} 格 vs 推入 ${run.barsPushed} 根柱",
                abs(run.advancedCells - run.barsPushed) <= 1.5,
            )
        }
    }

    @Test
    fun `低内存设备在四档刷新率上都被节流到同一档（约 30fps）而不是跟着面板跑`() {
        listOf(60f, 90f, 120f, 144f).forEach { hz ->
            val run = simulate(hz, lowTier = true)
            assertTrue(
                "低内存设备在 ${hz}Hz 上跑了 ${run.fps}fps —— 必须被节流到 27~34fps",
                run.fps in 27f..34f,
            )
        }
    }

    @Test
    fun `音频缓冲仍是 100ms 时柱间隔的 EMA 会收敛到 100ms 附近`() {
        val ring = WaveformRing(capacity = 256, barCount = 8)
        val stride = FrameStride(1)
        var t = 0.0
        var nextBar = 0.0
        var last = -1.0
        while (t < 30_000.0) {
            while (nextBar <= t) {
                ring.push(0.5f)
                nextBar += 100.0
            }
            if (stride.shouldAdvance()) {
                val dt = if (last < 0) 16.667f else (t - last).toFloat()
                last = t
                ring.pump(true, dt)
            }
            t += 16.667
        }
        assertTrue(
            "柱间隔 EMA 实测 ${ring.barIntervalMs()}ms，应落在 100~118ms（帧量化让它略偏大）",
            ring.barIntervalMs() in 100f..118f,
        )
    }

    /**
     * **特征化测试**（characterization test）：把 v3.2.3 的旧判据（时间阈值 16ms）原样跑一遍，
     * 证明它在 120Hz 上**确实**会产生「多跨一个 vsync」的量化跳变，而新判据没有。
     *
     * 这条用例存在的意义是**防止回退**：如果将来有人把「数帧」改回「数时间」，
     * [四档刷新率上都不存在超过名义帧间隔 1_2 倍的帧 —— 没有闸门量化] 会变红，
     * 而这条会继续绿 —— 两条一起读就能立刻看出是哪一种退化。
     */
    @Test
    fun `旧的时间阈值闸门在 120Hz 上会产生量化跳变（特征化测试）`() {
        val hz = 120f
        val vsync = DisplayRefresh.frameIntervalMs(hz)
        val rnd = Random(20260929L)
        var t = 0.0
        var lastAdvance = -1.0
        var maxDt = 0f
        var advances = 0
        // ↓↓↓ 逐字复刻 v3.2.3 的 MotionClock 闸门：now - 上次推进 >= 16ms ↓↓↓
        val budgetMs = 16.0
        while (t < 20_000.0) {
            if (lastAdvance < 0.0 || t - lastAdvance >= budgetMs) {
                if (lastAdvance >= 0.0) maxDt = maxOf(maxDt, (t - lastAdvance).toFloat())
                lastAdvance = t
                advances++
            }
            t += vsync + (rnd.nextDouble() * 2 - 1) * 0.6
        }
        val ratio = maxDt / vsync
        assertTrue(
            "旧闸门在 120Hz 上的最大 dt=${maxDt}ms = ${ratio} 个 vsync；" +
                "本用例假定它会出现「3 个 vsync」的跳变（=1.5 倍）—— 若不再出现，说明复刻失真了",
            ratio >= 1.4f,
        )
        assertTrue("旧闸门在 120Hz 上不可能跑满 120fps", advances / 20f < hz * 0.9f)
        // 新判据在同样的抖动下：没有跳变（对照见上一条用例）。
        val run = simulate(hz, lowTier = false)
        assertEquals(1.0f, run.maxDtMs / run.nominalMs, 0.2f)
    }

    /**
     * **S6 的非回归证明**（本版对 60Hz 的唯一承诺）：在 60Hz 面板上，
     * 旧判据（`now - 上次推进 >= 16ms`）与新判据（步长 1）**逐帧给出同一个推进序列**。
     *
     * 为什么这一条比「在 60Hz 设备上再测一次帧时间」更有说服力：
     *
     * - 旧判据在 60Hz 上恒真（vsync 16.667ms > 预算 16ms）⇒ `stride = 1` 与它**语义等价**，
     *   不是「差不多」，是**逐帧相同**；
     * - 帧时间测量会被热漂移、GPU、后台进程污染（v3.2.3 的一条口径：同一配置相隔 10 分钟
     *   就能差 7ms），而这条用例是确定性的、环境无关的。
     *
     * 唯一的行为差异是 `dtMs` 的**首帧**取值：旧代码用常量 16，新代码用**真实帧间隔** 16.667。
     * 首帧那一帧只影响一次 `sinceBarMs` 的初值（差 0.667ms = 相位的 0.7%），
     * 且第一帧之后两者立刻同源。
     */
    @Test
    fun `60Hz 上新旧判据逐帧等价 —— S6 的非回归证明`() {
        val hz = 60f
        val vsync = DisplayRefresh.frameIntervalMs(hz)
        // 两趟用**同一个随机种子**，所以两趟看到的是同一条帧时间序列。
        fun run(oldRule: Boolean): List<Double> {
            val rnd = Random(4242L)
            val pacer = FrameStride(DisplayRefresh.strideFor(hz, lowTier = false))
            val out = ArrayList<Double>()
            var t = 0.0
            var last = -1.0
            while (t < 10_000.0) {
                val advance = if (oldRule) {
                    // 逐字复刻 v3.2.3：MotionClock.kt:212 `clock[0] == 0L || now - clock[0] >= budgetNs`
                    last < 0.0 || t - last >= 16.0
                } else {
                    // v3.2.4：非低内存设备步长恒为 1 ⇒ 每帧都推进
                    pacer.shouldAdvance()
                }
                if (advance) {
                    out.add(t)
                    last = t
                }
                t += vsync + (rnd.nextDouble() * 2 - 1) * 0.6
            }
            return out
        }

        val old = run(oldRule = true)
        val new = run(oldRule = false)
        assertEquals("60Hz 上新旧判据的推进次数必须完全一致", old.size, new.size)
        assertTrue("10 秒窗口内应推进约 600 次，实测 ${old.size}", old.size in 580..620)
        old.indices.forEach { i ->
            assertEquals(
                "第 $i 次推进的时刻必须一致（差 >1µs 就说明判据不等价）",
                old[i],
                new[i],
                1e-6,
            )
        }
    }

    @Test
    fun `帧步长与柱间隔同为 100ms 时相位在一格内单调推进`() {
        val ring = WaveformRing(capacity = 256, barCount = 8)
        var t = 0.0
        var nextBar = 0.0
        var last = -1.0
        var lastPhase = 0f
        var violations = 0
        var samples = 0
        while (t < 20_000.0) {
            while (nextBar <= t) {
                ring.push(0.7f)
                nextBar += 100.0
            }
            val dt = if (last < 0) 16.667f else (t - last).toFloat()
            last = t
            ring.pump(true, dt)
            val phase = ring.scrollPhase01()
            // 同一格内：相位单调不减；回绕只允许发生在「新柱到达」那一帧。
            if (phase < lastPhase - 1e-6f) {
                // 允许回绕，但回绕后的相位必须接近 0（= 刚刚消费完）
                samples++
                if (phase > 0.02f) violations++
            }
            lastPhase = phase
            t += 16.667
        }
        assertTrue("回绕样本 $samples", samples > 100)
        assertEquals("回绕后相位必须归零，否则曲线会「回跳」", 0, violations)
    }
}
