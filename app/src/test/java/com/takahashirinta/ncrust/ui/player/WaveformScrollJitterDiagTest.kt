/*
 * Ncrust —— 网易云音乐第三方客户端
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
 * 并分别量出各机制的贡献。每个场景**同时**跑「改前（v3.2.4）」与「改后」，
 * 用同一个判据口径 ⇒ 数字可比。
 *
 * 判据口径见 [WaveformScrollJitterHarness]：位移 = Δ(窗口平移格) + Δ(`scrollPhase01()`)。
 *
 * 场景分两组：
 *
 *  - **A~E 时间线组**：恒幅柱，变化的是帧/柱的时间线 ⇒ 量「时间线抖动」的贡献；
 *  - **F~G 信号组**：柱高按真实音乐幅度分布（含安静的缓冲），时间线理想
 *    ⇒ 量「信号门控」的贡献（v3.2.4 的 `targets[last] > ANIMATION_MIN_SIGNAL` 才推进相位）。
 */
class WaveformScrollJitterDiagTest {

    private fun runScenario(
        name: String,
        frames: List<Double>,
        bars: List<Pair<Double, Float>>,
        legacy: Boolean,
        dumpFrom: Int = 0,
        dumpCount: Int = 0,
    ): WaveformScrollJitterHarness.Summary {
        val h = WaveformScrollJitterHarness()
        h.legacyPhase = legacy
        // **事件按自己的时刻发生**：音频线程在 barT 那一刻 push（与 vsync 无关），
        // UI 帧在 t 那一刻只做一次快照。两者合并成一条时间线，谁先到谁先发生 ——
        // 这样 `barIntervalMs` 测到的才是**真实的缓冲间隔**，而不是被帧网格量化过的值。
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
        val s = h.summary()
        println("=".repeat(84))
        println("场景 $name  [${if (legacy) "改前 v3.2.4" else "改后"}]")
        println("-".repeat(84))
        println(s)
        if (dumpCount > 0) println(h.dump(dumpFrom, dumpCount))
        return s
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

    private fun flatBars(intervalMs: Double, seconds: Double, offsetMs: Double, value: Float = 0.6f): List<Pair<Double, Float>> {
        val out = ArrayList<Pair<Double, Float>>()
        var t = offsetMs
        while (t < seconds * 1000.0) {
            out.add(t to value)
            t += intervalMs
        }
        return out
    }

    private fun jitteredBars(
        intervalMs: Double,
        jitterMs: Double,
        seconds: Double,
        offsetMs: Double,
        seed: Long,
        value: Float = 0.6f,
    ): List<Pair<Double, Float>> {
        val rnd = Random(seed)
        val out = ArrayList<Pair<Double, Float>>()
        var t = offsetMs
        while (t < seconds * 1000.0) {
            out.add(t to value)
            t += intervalMs + (rnd.nextDouble() * 2 - 1) * jitterMs
        }
        return out
    }

    /**
     * 真实音乐幅度：底噪 0.02~0.12 的安静缓冲穿插 0.3~0.9 的强拍，
     * **并且周期性出现单根低于 0.02 的缓冲**（间奏 / 换气 / 混音空档）。
     * 这正是 v3.2.4 的信号门控会被触发的地方。
     */
    private fun musicBars(
        intervalMs: Double,
        seconds: Double,
        offsetMs: Double,
        seed: Long,
        quietEvery: Int,
    ): List<Pair<Double, Float>> {
        val rnd = Random(seed)
        val out = ArrayList<Pair<Double, Float>>()
        var t = offsetMs
        var i = 0
        while (t < seconds * 1000.0) {
            val v = when {
                quietEvery > 0 && i % quietEvery == quietEvery - 1 -> 0.005f + rnd.nextFloat() * 0.010f
                i % 8 == 0 -> 0.45f + rnd.nextFloat() * 0.45f
                else -> 0.08f + rnd.nextFloat() * 0.30f
            }
            out.add(t to v)
            t += intervalMs
            i++
        }
        return out
    }

    private fun line(tag: String, a: WaveformScrollJitterHarness.Summary, b: WaveformScrollJitterHarness.Summary) {
        println(
            "%-16s 改前 %7.2f%% → 改后 %7.2f%%   顿-冲 %.3f×→%.3f×   顿 %d→%d 帧   倒退 %d→%d 帧"
                .format(
                    tag, a.jitterRatio * 100, b.jitterRatio * 100,
                    a.worstBurstRatio, b.worstBurstRatio,
                    a.stallCount, b.stallCount, a.backsteps, b.backsteps,
                ),
        )
    }

    @Test
    fun 量化诊断_时间线与信号门控的贡献() {
        val seconds = 20.0
        val ideal60 = idealFrames(60.0, seconds)
        val jit60 = jitteredFrames(60.0, seconds, jitterMs = 1.5, hiccupMs = 5.0, hiccupPeriod = 37, seed = 20260930L)
        val jit120 = jitteredFrames(120.0, seconds, jitterMs = 0.8, hiccupMs = 4.0, hiccupPeriod = 53, seed = 20260931L)

        val dtJit = jit60.zipWithNext { a, b -> b - a }
        println("\n### 序列一：帧间隔（前 24 帧，ms）—— 先数据后结论")
        println("理想帧        : " + ideal60.zipWithNext { a, b -> b - a }.take(12).joinToString(" ") { "%.2f".format(it) })
        println("抖动帧(60Hz)  : " + dtJit.take(24).joinToString(" ") { "%.2f".format(it) })
        println(
            "抖动帧 dt 统计: 均值=%.3fms sd=%.3fms min=%.3f max=%.3f"
                .format(
                    dtJit.average(),
                    kotlin.math.sqrt(dtJit.map { (it - dtJit.average()).let { d -> d * d } }.average()),
                    dtJit.min(), dtJit.max(),
                ),
        )

        val aOld = runScenario("A 理想帧+理想柱(对齐)", ideal60, flatBars(100.0, seconds, 0.0), true, 60, 13)
        val aNew = runScenario("A 理想帧+理想柱(对齐)", ideal60, flatBars(100.0, seconds, 0.0), false, 60, 13)
        val bOld = runScenario("B 理想帧+理想柱(错开5ms)", ideal60, flatBars(100.0, seconds, 5.0), true, 60, 13)
        val bNew = runScenario("B 理想帧+理想柱(错开5ms)", ideal60, flatBars(100.0, seconds, 5.0), false, 60, 13)
        val cOld = runScenario("C 抖动帧+理想柱", jit60, flatBars(100.0, seconds, 5.0), true, 60, 13)
        val cNew = runScenario("C 抖动帧+理想柱", jit60, flatBars(100.0, seconds, 5.0), false, 60, 13)
        val barsD = jitteredBars(100.0, 8.0, seconds, 5.0, 777L)
        val dOld = runScenario("D 抖动帧+抖动柱", jit60, barsD, true, 60, 13)
        val dNew = runScenario("D 抖动帧+抖动柱", jit60, barsD, false, 60, 13)
        val eOld = runScenario("E 120Hz+抖动柱", jit120, barsD, true)
        val eNew = runScenario("E 120Hz+抖动柱", jit120, barsD, false)

        // ---- 信号组：时间线理想、柱高是真实音乐幅度 ----
        val mus20 = musicBars(100.0, seconds, 5.0, seed = 4242L, quietEvery = 20)
        val fOld = runScenario("F 理想帧+音乐(每20柱一静音)", ideal60, mus20, true, 40, 30)
        val fNew = runScenario("F 理想帧+音乐(每20柱一静音)", ideal60, mus20, false, 40, 30)
        val mus8 = musicBars(100.0, seconds, 5.0, seed = 4243L, quietEvery = 8)
        val gOld = runScenario("G 理想帧+音乐(每8柱一静音)", ideal60, mus8, true, 40, 30)
        val gNew = runScenario("G 理想帧+音乐(每8柱一静音)", ideal60, mus8, false, 40, 30)

        println("\n" + "=".repeat(84))
        println("### 贡献分解（抖动率 = 步长 sd ÷ 名义步长；同一台仿真、同一口径）")
        line("A 全理想", aOld, aNew)
        line("B 只错开到达", bOld, bNew)
        line("C 只抖帧", cOld, cNew)
        line("D 帧抖+柱抖", dOld, dNew)
        line("E 120Hz 叠加", eOld, eNew)
        line("F 音乐:每20柱静音", fOld, fNew)
        line("G 音乐:每8柱静音", gOld, gNew)
        println("-".repeat(84))
        println(
            "位移总量/推入柱数: A %d/%d→%d/%d  B %d/%d→%d/%d  C %d/%d→%d/%d  D %d/%d→%d/%d  F %d/%d→%d/%d  G %d/%d→%d/%d"
                .format(
                    aOld.advancedCells.toInt(), aOld.barsPushed, aNew.advancedCells.toInt(), aNew.barsPushed,
                    bOld.advancedCells.toInt(), bOld.barsPushed, bNew.advancedCells.toInt(), bNew.barsPushed,
                    cOld.advancedCells.toInt(), cOld.barsPushed, cNew.advancedCells.toInt(), cNew.barsPushed,
                    dOld.advancedCells.toInt(), dOld.barsPushed, dNew.advancedCells.toInt(), dNew.barsPushed,
                    fOld.advancedCells.toInt(), fOld.barsPushed, fNew.advancedCells.toInt(), fNew.barsPushed,
                    gOld.advancedCells.toInt(), gOld.barsPushed, gNew.advancedCells.toInt(), gNew.barsPushed,
                ),
        )
    }
}
