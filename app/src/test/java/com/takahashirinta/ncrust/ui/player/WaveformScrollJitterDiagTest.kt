/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * ⚠️ 诊断用（第 1 步「先量化」的产出）。它只打印数字，不做任何断言。
 */

package com.takahashirinta.ncrust.ui.player

import org.junit.Test
import java.util.Random

/**
 * 滚动抖动的**量化诊断**：把「帧间隔」与「可见位移」两条序列打出来，
 * 并分别量出两个嫌疑机制的贡献占比。
 *
 * 四个场景（都用**真实的 [WaveformRing]**，只是喂给它的时间线被控制）：
 *
 * | 场景 | 帧时间 | 柱到达 | 用于量什么 |
 * |---|---|---|---|
 * | A | 理想 60Hz | 理想 100ms，对齐 vsync | 「完全无抖动」的下界（应该匀速）|
 * | B | 理想 60Hz | 理想 100ms，**错开** vsync（真实 HAL 就是这样）| **相位清零**的贡献，与帧抖动无关 |
 * | C | 抖动 60Hz（真机分布）| 理想 100ms | **帧时间抖动**的贡献 |
 * | D | 抖动 60Hz（真机分布）| 抖动 100ms | 两者叠加 = 用户看到的 |
 *
 * 判据口径见 [WaveformScrollJitterHarness]：位移 = Δ(窗口平移格) + Δ(`scrollPhase01()`)。
 */
class WaveformScrollJitterDiagTest {

    private fun runScenario(
        name: String,
        frames: List<Double>,
        bars: List<Double>,
        dumpFrom: Int = 0,
        dumpCount: Int = 0,
    ): WaveformScrollJitterHarness.Summary {
        val h = WaveformScrollJitterHarness()
        var bi = 0
        for (t in frames) {
            while (bi < bars.size && bars[bi] <= t) {
                h.pushBar(bars[bi])
                bi++
            }
            h.advanceTo(t)
        }
        val s = h.summary()
        println("=".repeat(78))
        println("场景 $name")
        println("-".repeat(78))
        println(s)
        if (dumpCount > 0) {
            println(h.dump(dumpFrom, dumpCount))
        }
        return s
    }

    /** 理想帧：严格 1000/hz 一帧。 */
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

    /**
     * 真实帧：`1000/hz ± jitterMs` 均匀抖动 + 每 [hiccupPeriod] 帧一次长帧
     * （GC / 组合 / 调度 —— 真机上 dt 在 15~20ms 跳的成因）。
     */
    private fun jitteredFrames(
        hz: Double,
        seconds: Double,
        jitterMs: Double,
        hiccupMs: Double,
        hiccupPeriod: Int,
        seed: Long,
    ): List<Double> {
        val step = 1000.0 / hz
        val rnd = Random(seed)
        val out = ArrayList<Double>()
        var t = 0.0
        var i = 0
        while (t < seconds * 1000.0) {
            out.add(t)
            var d = step + (rnd.nextDouble() * 2 - 1) * jitterMs
            if (hiccupPeriod > 0 && i % hiccupPeriod == hiccupPeriod - 1) d += hiccupMs
            if (d < 1.0) d = 1.0
            t += d
            i++
        }
        return out
    }

    /** 理想柱：严格 intervalMs 一根。 */
    private fun idealBars(intervalMs: Double, seconds: Double, offsetMs: Double): List<Double> {
        val out = ArrayList<Double>()
        var t = offsetMs
        while (t < seconds * 1000.0) {
            out.add(t)
            t += intervalMs
        }
        return out
    }

    /** 抖动柱：`intervalMs ± jitterMs` —— AudioTrack 缓冲回调与 vsync 无关，间隔本来就不齐。 */
    private fun jitteredBars(
        intervalMs: Double,
        jitterMs: Double,
        seconds: Double,
        offsetMs: Double,
        seed: Long,
    ): List<Double> {
        val rnd = Random(seed)
        val out = ArrayList<Double>()
        var t = offsetMs
        while (t < seconds * 1000.0) {
            out.add(t)
            t += intervalMs + (rnd.nextDouble() * 2 - 1) * jitterMs
        }
        return out
    }

    @Test
    fun `四场景量化 —— 相位清零与帧时间抖动各自的贡献`() {
        val seconds = 20.0
        val ideal60 = idealFrames(60.0, seconds)
        // 真机 S6（G920F / LineageOS 20，60Hz）的帧时间来自 dumpsys gfxinfo framestats，
        // 本机实测分布见 WaveformScrollJitterDeviceTraceTest；这里先用 ±1.5ms + 每 37 帧一次 5ms 长帧。
        val jit60 = jitteredFrames(60.0, seconds, jitterMs = 1.5, hiccupMs = 5.0, hiccupPeriod = 37, seed = 20260930L)

        println("\n### 帧间隔序列（前 24 帧，ms）")
        val dtIdeal = ideal60.zipWithNext { a, b -> b - a }
        val dtJit = jit60.zipWithNext { a, b -> b - a }
        println("A/C 用的理想帧: " + dtIdeal.take(12).joinToString(" ") { "%.2f".format(it) })
        println("B/D 用的抖动帧: " + dtJit.take(24).joinToString(" ") { "%.2f".format(it) })
        println("抖动帧 dt: 均值=%.3fms sd=%.3fms min=%.3f max=%.3f"
            .format(dtJit.average(), dtJit.map { (it - dtJit.average()).let { d -> d * d } }
                .let { kotlin.math.sqrt(it.average()) }, dtJit.min(), dtJit.max()))

        val a = runScenario(
            "A · 理想帧(60Hz) + 理想柱(100ms, 与 vsync 对齐)", ideal60, idealBars(100.0, seconds, 0.0),
            dumpFrom = 60, dumpCount = 14,
        )
        val b = runScenario(
            "B · 理想帧(60Hz) + 理想柱(100ms, 错开 vsync 5ms)", ideal60, idealBars(100.0, seconds, 5.0),
            dumpFrom = 60, dumpCount = 14,
        )
        val c = runScenario(
            "C · 抖动帧(60Hz) + 理想柱(100ms, 错开 vsync 5ms)", jit60, idealBars(100.0, seconds, 5.0),
            dumpFrom = 60, dumpCount = 14,
        )
        val d = runScenario(
            "D · 抖动帧(60Hz) + 抖动柱(100ms±8ms)", jit60,
            jitteredBars(100.0, 8.0, seconds, 5.0, seed = 777L),
            dumpFrom = 60, dumpCount = 14,
        )

        println("=".repeat(78))
        println("### 贡献分解（抖动率 = 步长 sd ÷ 名义步长）")
        println("A 理想        : %.2f%%   最差顿-冲=%.3f×  顿=%d  倒退=%d"
            .format(a.jitterRatio * 100, a.worstBurstRatio, a.stallCount, a.backsteps))
        println("B 只错开到达  : %.2f%%   最差顿-冲=%.3f×  顿=%d  倒退=%d"
            .format(b.jitterRatio * 100, b.worstBurstRatio, b.stallCount, b.backsteps))
        println("C 只抖帧      : %.2f%%   最差顿-冲=%.3f×  顿=%d  倒退=%d"
            .format(c.jitterRatio * 100, c.worstBurstRatio, c.stallCount, c.backsteps))
        println("D 两者叠加    : %.2f%%   最差顿-冲=%.3f×  顿=%d  倒退=%d"
            .format(d.jitterRatio * 100, d.worstBurstRatio, d.stallCount, d.backsteps))
        println("位移总量 vs 推入柱数：A %.2f/%d  B %.2f/%d  C %.2f/%d  D %.2f/%d"
            .format(a.advancedCells, a.barsPushed, b.advancedCells, b.barsPushed,
                c.advancedCells, c.barsPushed, d.advancedCells, d.barsPushed))
    }
}
