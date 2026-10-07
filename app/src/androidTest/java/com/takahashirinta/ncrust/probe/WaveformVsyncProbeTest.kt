/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 探针：**在真机的真实 vsync 上**采集两条序列 —— 帧间隔（Choreographer 的 frameTimeNanos 之差）
 * 与可见位移（`Δ窗口平移格 + ΔscrollPhase01()`，与渲染层同源）。
 *
 * 为什么需要它：模拟器的帧时间被 SwiftShader 拖慢（仓库铁律 16），不能当面板的结论。
 * 本探针不改任何生产代码 —— 它把**真实的 vsync 时间戳**喂给真实的 WaveformRing。
 *
 * ## v3.4.7 起多量三件事（这才是重点）
 *
 * 1. **两个时钟的基准差**：帧时间戳（`frameTimeNanos / 1e6`）与 `SystemClock.uptimeMillis()`
 *    在同一帧里各取一次，报告差值 —— 相位锚点把这两个量相减，它们必须**同源**。
 * 2. **柱到达帧的位移**：单独统计「窗口平移了的那一帧」的位移。相位若被清零或锚错，
 *    这一帧的位移会塌成 0（用户读到的就是「顿一下」），而它本该 ≈ 名义步长。
 * 3. **柱来自另一条线程**：柱由独立的 HandlerThread 按自己的节拍 push、时间戳取
 *    `SystemClock.uptimeMillis()`（与生产里 ExoPlayer 的播放线程同构），**不在 vsync 网格上**。
 */

package com.takahashirinta.ncrust.probe

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.takahashirinta.ncrust.ui.player.WaveformRing
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class WaveformVsyncProbeTest {

    private class Rec(
        val tMs: Double,
        val dtMs: Float,
        val shifted: Long,
        val phase: Float,
        val intervalMs: Float,
        /** 同一帧里 `uptimeMillis() − frameTimeNanos/1e6`（两个时钟的基准差，毫秒）。 */
        val clockSkewMs: Long,
    )

    /**
     * @param barPeriodMs 柱到达周期（模拟 AudioTrack / ExoPlayer 的缓冲回调，与 vsync 无关）
     * @param feedFrameClock true = 把帧时间戳喂给 ring（v3.4.7 的生产锚点）；
     *   false = 不喂（旧口径：环自己按 `dt` 累加帧时钟）。
     */
    private fun run(
        barPeriodMs: Double,
        frames: Int,
        feedFrameClock: Boolean,
    ): List<Rec> {
        val out = ArrayList<Rec>(frames)
        val latch = CountDownLatch(1)
        val uiThread = HandlerThread("vsync-probe-ui").apply { start() }
        val barThread = HandlerThread("vsync-probe-bar").apply { start() }
        val ui = Handler(uiThread.looper)
        val bar = Handler(barThread.looper)
        val ring = WaveformRing(capacity = 512, barCount = 16)
        val clock = LongArray(2)
        var first = true
        var shiftedPrev = 0L
        var phasePrev = 0f

        // 音频线程：按自己的节拍推柱。时间戳就是它自己那一刻的 uptimeMillis —— 与生产同构。
        val pushBar = object : Runnable {
            override fun run() {
                ring.push(
                    value = 0.6f,
                    low = 0.5f,
                    mid = 0.4f,
                    high = 0.3f,
                    arrivalAtMs = SystemClock.uptimeMillis(),
                )
                bar.postDelayed(this, barPeriodMs.toLong())
            }
        }
        bar.post(pushBar)

        ui.post {
            val choreographer = Choreographer.getInstance()
            val cb = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    val frameMs = frameTimeNanos / 1_000_000.0
                    val skew = SystemClock.uptimeMillis() - (frameTimeNanos / 1_000_000L)
                    val dt = if (clock[1] == 0L) {
                        16.667f
                    } else {
                        ((frameTimeNanos - clock[1]) / 1_000_000f).coerceIn(1f, 100f)
                    }
                    clock[1] = frameTimeNanos
                    // ↓↓↓ 生产路径：帧时间戳与柱到达时间戳**同一个时钟** ↓↓↓
                    if (feedFrameClock) {
                        ring.pump(
                            active = true,
                            dtMs = dt,
                            effects = VisualizerEffects.BASELINE,
                            nowMs = frameTimeNanos / 1_000_000L,
                        )
                    } else {
                        // 对照臂：不喂帧时间戳 ⇒ 环回落到「自己按 dt 累加」的旧口径。
                        ring.pump(active = true, dtMs = dt, effects = VisualizerEffects.BASELINE)
                    }
                    val shifted = ring.shiftedCellsForTest()
                    val phase = ring.scrollPhase01()
                    if (!first) {
                        out.add(
                            Rec(
                                frameMs,
                                dt,
                                shifted - shiftedPrev,
                                phase - phasePrev,
                                ring.phaseIntervalMsForTest(),
                                skew,
                            ),
                        )
                    }
                    first = false
                    shiftedPrev = shifted
                    phasePrev = phase
                    if (out.size >= frames) {
                        bar.removeCallbacksAndMessages(null)
                        latch.countDown()
                        return
                    }
                    choreographer.postFrameCallback(this)
                }
            }
            choreographer.postFrameCallback(cb)
        }
        latch.await(90, TimeUnit.SECONDS)
        barThread.quitSafely()
        uiThread.quitSafely()
        return out
    }

    /** 真机上 `am instrument` 不回传 stdout ⇒ 同时写 logcat（本探针专用的唯一 tag）。 */
    private fun emit(line: String) {
        android.util.Log.i("VSYNCPROBE", line)
        println(line)
    }

    private fun report(tag: String, recs: List<Rec>) {
        val body = recs.drop(60)
        if (body.isEmpty()) return
        val dts = body.map { it.dtMs.toDouble() }
        val mean = dts.average()
        val sd = sqrt(dts.sumOf { (it - mean) * (it - mean) } / dts.size)
        // 可见位移 = Δ平移格 + Δ相位（唯一与渲染层同源的口径）。
        val steps = body.map { it.shifted + it.phase.toDouble() }
        val sMean = steps.average()
        val sSd = sqrt(steps.sumOf { (it - sMean) * (it - sMean) } / steps.size)
        val nominal = mean / recs.last().intervalMs
        val stalls = steps.count { it < nominal * 0.25 }
        val backs = steps.count { it < -0.01 }
        // 柱到达帧（窗口平移了的那一帧）单独统计 —— 相位被清零/锚错时，塌的就是这一帧。
        val arrivals = body.filter { it.shifted > 0 }
        val aSteps = arrivals.map { it.shifted + it.phase.toDouble() }
        val aMin = aSteps.minOrNull() ?: 0.0
        val aMax = aSteps.maxOrNull() ?: 0.0
        val aMean = if (aSteps.isEmpty()) 0.0 else aSteps.average()
        val sk = body.map { it.clockSkewMs }
        emit(
            ("PROBE[%s] 帧数=%d dt: 均=%.3fms sd=%.3f min=%.3f max=%.3f | " +
                "位移: 均=%.4f sd=%.4f 名义=%.4f 抖动率=%.2f%% 顿=%d 倒退=%d | 柱间隔估计=%.2fms")
                .format(
                    tag, body.size, mean, sd, dts.min(), dts.max(),
                    sMean, sSd, nominal, sSd / nominal * 100, stalls, backs,
                    recs.last().intervalMs,
                ),
        )
        emit(
            ("PROBE[%s] 到达帧(n=%d): 位移 均=%.4f 最小=%.4f 最大=%.4f （名义 %.4f，最小=%.0f%% 名义） | " +
                "时钟基准差 uptime−frame: 均=%.1fms 最小=%d 最大=%d")
                .format(
                    tag, arrivals.size, aMean, aMin, aMax, nominal,
                    if (nominal > 0) aMin / nominal * 100 else 0.0,
                    sk.average(), sk.minOrNull() ?: 0L, sk.maxOrNull() ?: 0L,
                ),
        )
        // 前 14 帧原始序列（先数据后结论）。
        emit("PROBE[$tag] 帧# dt(ms) Δ平移 Δ相位 位移 基准差(ms)")
        body.take(14).forEachIndexed { i, r ->
            emit(
                "PROBE[$tag] %3d %7.3f %5d %8.4f %8.4f %6d".format(
                    i, r.dtMs, r.shifted.toInt(), r.phase, r.shifted + r.phase, r.clockSkewMs,
                ),
            )
        }
    }

    @Test
    fun 真机真实vsync上的帧间隔与可见位移() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        listOf(
            "A 柱100.0ms" to 100.0,
            "B 柱82.6ms（录像工况）" to 82.6,
            "C 柱41.2ms（用户报的那台）" to 41.2,
        ).forEach { (tag, period) ->
            // 同一个进程、同一台设备、时间相邻的两臂：
            //  · 「同源」= 帧时间戳喂给 ring（v3.4.7 生产路径）
            //  · 「累加」= 不喂（环按 dt 自己攒时钟 = 旧口径的底座）
            run(period, frames = 60, feedFrameClock = true) // 预热
            report("$tag · 同源", run(period, frames = 420, feedFrameClock = true))
            report("$tag · 累加", run(period, frames = 420, feedFrameClock = false))
        }
    }
}
