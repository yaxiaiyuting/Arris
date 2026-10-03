/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 探针（第 1 步「先量化」）：**在真机的真实 vsync 上**采集两条序列 ——
 * 帧间隔（Choreographer 的 frameTimeNanos 之差）与可见位移
 * （`Δ窗口平移格 + ΔscrollPhase01()`，与渲染层同源）。
 *
 * 为什么需要它：模拟器的帧时间被 SwiftShader 拖慢（仓库铁律 16），
 * 不能当 60Hz 面板的结论；而本机的 S6（G920F / 60Hz）是真面板。
 * 本探针不改任何生产代码 —— 它把**真实的 vsync 时间戳**喂给真实的 WaveformRing。
 */

package com.takahashirinta.ncrust.probe

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.takahashirinta.ncrust.ui.player.WaveformRing
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class WaveformVsyncProbeTest {

    private class Rec(val tMs: Double, val dtMs: Float, val shifted: Long, val phase: Float, val intervalMs: Float)

    /**
     * @param barPeriodMs 柱到达周期（模拟 AudioTrack 缓冲回调，与 vsync 无关）
     * @param barOffsetMs 柱到达相对 vsync 网格的错开量
     */
    private fun run(barPeriodMs: Double, barOffsetMs: Double, frames: Int): List<Rec> {
        val out = ArrayList<Rec>(frames)
        val latch = CountDownLatch(1)
        val thread = HandlerThread("vsync-probe").apply { start() }
        val handler = Handler(thread.looper)
        val ring = WaveformRing(capacity = 512, barCount = 16)
        val clock = LongArray(2)
        var nextBarAt = barOffsetMs
        var pushed = 0
        var shiftedPrev = 0L
        var phasePrev = 0f
        var first = true

        handler.post {
            val choreographer = Choreographer.getInstance()
            val cb = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    // 1) 先把「这一帧之前到达的柱」推入环 —— 与生产同构：音频线程独立 push。
                    val nowMs = frameTimeNanos / 1_000_000.0
                    while (nextBarAt <= nowMs) {
                        ring.push(0.6f, 0.5f, 0.4f, 0.3f)
                        pushed++
                        nextBarAt += barPeriodMs
                    }
                    // 2) 帧推进：dt 取两次 Choreographer 时间戳之差（逐字复刻 MotionFrameClock）。
                    val dt = if (clock[1] == 0L) {
                        16.667f
                    } else {
                        ((frameTimeNanos - clock[1]) / 1_000_000f).coerceIn(1f, 100f)
                    }
                    clock[1] = frameTimeNanos
                    ring.pump(active = true, dtMs = dt)
                    val shifted = ring.shiftedCellsForTest()
                    val phase = ring.scrollPhase01()
                    if (!first) {
                        out.add(
                            Rec(
                                frameTimeNanos / 1_000_000.0,
                                dt,
                                shifted - shiftedPrev,
                                phase - phasePrev,
                                ring.barIntervalMs(),
                            ),
                        )
                    }
                    first = false
                    shiftedPrev = shifted
                    phasePrev = phase
                    if (out.size >= frames) {
                        latch.countDown()
                        return
                    }
                    choreographer.postFrameCallback(this)
                }
            }
            choreographer.postFrameCallback(cb)
        }
        latch.await(60, TimeUnit.SECONDS)
        thread.quitSafely()
        return out
    }

    private fun report(tag: String, recs: List<Rec>) {
        val body = recs.drop(30)
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
        println(
            "PROBE[$tag] 帧数=${body.size} dt: 均=%.3fms sd=%.3f min=%.3f max=%.3f | " +
                "位移: 均=%.4f sd=%.4f 名义=%.4f 抖动率=%.2f%% 顿=%s 倒退=%s | 柱间隔估计=%.1fms".format(
                    mean, sd, dts.min(), dts.max(),
                    sMean, sSd, nominal, sSd / nominal * 100, stalls, backs,
                    recs.last().intervalMs,
                ),
        )
        // 前 14 帧原始序列（先数据后结论）。
        println("PROBE[$tag] 帧# dt(ms) Δ平移 Δ相位 位移")
        body.take(14).forEachIndexed { i, r ->
            println("PROBE[$tag] %3d %7.3f %5d %8.4f %8.4f".format(i, r.dtMs, r.shifted.toInt(), r.phase, r.shifted + r.phase))
        }
    }

    @Test
    fun 真机真实vsync上的帧间隔与可见位移() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        listOf(
            Triple("柱100ms对齐vsync", 100.0, 0.0),
            Triple("柱100ms错开5ms", 100.0, 5.0),
            Triple("柱100ms错开11ms", 100.0, 11.0),
            Triple("柱96ms错开5ms", 96.0, 5.0),
        ).forEach { (tag, period, offset) ->
            // 先预热 2 秒再采集（避开起播阶段 EMA 未收敛）。
            run(period, offset, frames = 120)
            val recs = run(period, offset, frames = 420)
            report(tag, recs)
        }
    }
}
