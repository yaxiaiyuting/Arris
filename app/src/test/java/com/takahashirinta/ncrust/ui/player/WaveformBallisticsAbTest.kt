/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.ui.player.waveform.BandBallistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * v3.4.5：**改前 / 改后同口径 A/B**。旧公式是逐行抄下来的（不是回忆），
 * 输入序列是从用户录像里量出来的**真实柱高**。
 *
 * ## 输入从哪来
 *
 * [TARGETS] = 用户录像某一个静止画面里逐格量到的柱高（85 格 = 三泳道 28×3+1），
 * 按渲染层的 `heights = √v · heightPx/2` 反解成归一化值。三条泳道的均值分别是
 * **0.198 / 0.395 / 0.630** —— 与画面上"左低右高"的三泳道一致，这条自洽性说明
 * 取样口径是对的。
 *
 * ⚠️ **时间演化是构造的**（把 85 个真实柱高按到达顺序循环）：录像本身只有
 * **4 个不重复帧**、波形完全不动（见交付报告 §1），所以录像里没有可用的动态。
 * 这份 A/B 比较的是「同一输入下两个运动模型」，**不是**"录像里的动态"。
 *
 * ## 尺子（两个模型完全同一把，且与实现无关）
 *
 * 逐帧步进同一个时钟（146.2fps，柱间隔 100ms），两个模型在**同一时刻**拿到
 * **同一个 target**、同一个 dt，然后只看画面量：
 *
 * | 量 | 定义 | 为什么它代表「弹起来」 |
 * |---|---|---|
 * | 小球离地高度 | `ball − bar`（归一化；并按录像标定换算成像素 `45.5·√gap`） | 恒 0 = 小球粘在柱顶上 |
 * | 落体加速度 | 小球位置的**二阶差分** `(yₜ−2yₜ₋₁+yₜ₋₂)/dt²`，只统计**连续离地 ≥3 帧**的样本 | 自由落体必须 ≈ −g；匀速下落是 0 |
 * | 反弹次数 | 小球**贴着柱顶**（gap ≤ 0.01）且速度由负转正、**而此时柱子没有在升** | 被上升的柱子顶起来不算反弹；旧实现落到柱高就停 ⇒ 0 次 |
 * | 柱高单帧最大跳变 | `√` 映射后的像素差 | 阶梯 vs 弹道的直接判据 |
 * | 零变化帧占比 | 柱高画面值逐帧**完全相等**的比例 | 吸附截断会在接近目标时冻住 |
 */
class WaveformBallisticsAbTest {

    /** 录像实测：满幅（v=1）时色带半高 45.5px ⇒ `px = 45.5·√v`。 */
    private val pxPerSqrt = PX_PER_SQRT

    /** 146.2fps（录像标称帧率）。 */
    private val frameMs = 1000f / 146.2f

    /** 柱间隔：与录像里三泳道的柱距一致（100ms ≈ 12.1Hz 那一档）。 */
    private val barMs = 100f

    // ───────────────────── 旧实现（v3.4.4，逐行抄自 git 9b3e837） ─────────────────────

    /**
     * `WaveformRing.approach` / `advancePeak`（v3.4.4）的逐格公式，逐行抄写：
     *
     * ```kotlin
     * val k = 1f - exp(-dt / tau)                       // 22ms 起音 / 130ms 回落
     * val next = current + (target - current) * k
     * bars[i] = if (abs(next - target) < SETTLE_EPSILON) target else next
     * // 峰值：bar >= peak 就贴上去并重置保持计时；保持期内不动；之后**匀速**下落、落到柱高即停
     * var fallen = previous - 0.9f * dt / 1000f
     * if (fallen < bar) fallen = bar
     * ```
     */
    private class LegacyCell {
        var bar = 0f; private set
        var peak = 0f; private set
        private var holdMs = 0f

        fun step(target: Float, dtMs: Float) {
            if (bar != target) {
                val k = if (target > bar) 1f - exp(-dtMs / 22f) else 1f - exp(-dtMs / 130f)
                val next = bar + (target - bar) * k
                bar = if (abs(next - target) < 0.004f) target else next
            }
            val previous = peak
            if (bar >= previous) {
                peak = bar
                holdMs = 420f
            } else if (holdMs > 0f) {
                holdMs = (holdMs - dtMs).coerceAtLeast(0f)
            } else {
                var fallen = previous - 0.9f * dtMs / 1000f
                if (fallen < bar) fallen = bar
                if (fallen < 0f) fallen = 0f
                peak = fallen
            }
        }
    }

    /** 新实现：二阶弹簧-阻尼（柱高）+ 牛顿自由落体/弹性碰撞（小球）。 */
    private class NewCell {
        private val spring = BandBallistics.SpringStep()
        private val ball = BandBallistics.BallStep()
        private val sOut = FloatArray(2)
        private val bOut = FloatArray(3)
        var bar = 0f; private set
        var barVel = 0f; private set
        var peak = 0f; private set
        private var rest = false

        fun step(target: Float, dtMs: Float) {
            val dtSec = dtMs / 1000f
            val omega = if (target >= bar) {
                BandBallistics.omegaFor(BandBallistics.ATTACK_TAU_MS)
            } else {
                BandBallistics.omegaFor(BandBallistics.RELEASE_TAU_MS)
            }
            spring.prepare(omega, BandBallistics.DAMPING_RATIO, dtSec)
            spring.step(bar, barVel, target, sOut)
            bar = sOut[0].coerceIn(0f, 1f)
            barVel = sOut[1]
            ball.step(peak, ballVel, rest, bar, barVel, dtSec, bOut)
            peak = bOut[0].coerceIn(bar, 1f)
            ballVel = bOut[1]
            rest = bOut[2] != 0f
        }

        private var ballVel = 0f
    }

    /** 一次仿真的结果（全部是**画面量**，与实现无关）。 */
    private class Result(val label: String) {
        var frames = 0
        var zeroChange = 0
        var maxJumpPx = 0f
        var maxGap = 0f
        var accelSum = 0.0
        var accelN = 0
        var accelWorst = 0f
        val accelSamples = ArrayList<Float>(4096)
        var freeFallN = 0
        var bounces = 0
        var maxAirborneRun = 0
        var settled = false
        var trace = StringBuilder()

        override fun toString(): String {
            val accel = if (accelN > 0) accelSum / accelN else Double.NaN
            val sorted = accelSamples.sorted()
            val median = if (sorted.isEmpty()) Float.NaN else sorted[sorted.size / 2]
            return String.format(
                "%s: 零变化帧 %5.1f%%  柱高单帧最大跳变 %5.2fpx  小球最大离地 %.3f (%.1fpx)  " +
                    "落体加速度 中位数 %+.2f /s²（均值 %+.2f，自由落体占比 %.0f%%，样本 %d）  " +
                    "贴地反弹 %d 次  最长连续离地 %d 帧  静音收敛=%b",
                label,
                100.0 * zeroChange / (frames - 1),
                maxJumpPx,
                maxGap,
                sqrt(maxGap.coerceAtLeast(0f)) * PX_PER_SQRT,
                median,
                accel,
                100.0 * freeFallN / accelN.coerceAtLeast(1),
                accelN,
                bounces,
                maxAirborneRun,
                settled,
            )
        }
    }

    private fun simulate(legacy: Boolean, totalFrames: Int = 4000): Result {
        val dt = frameMs
        val g = BandBallistics.GRAVITY
        val r = Result(if (legacy) "改前(v3.4.4)" else "改后(v3.4.5)")
        val lc = LegacyCell()
        val nc = NewCell()
        var simMs = 0f
        var nextBarMs = 0f
        var k = 0
        var target = 0f
        var prevBar = Float.NaN
        var prevBall = Float.NaN
        var prevPrevBall = Float.NaN
        var airborneRun = 0
        var prevVel = 0f
        repeat(totalFrames) { frame ->
            if (simMs >= nextBarMs) {
                target = TARGETS[k % TARGETS.size]
                k++
                nextBarMs += barMs
            }
            // ★ 同一时刻、同一 target、同一 dt —— 两个模型唯一的差别就是运动方程。
            if (legacy) lc.step(target, dt) else nc.step(target, dt)
            val barV = if (legacy) lc.bar else nc.bar
            val ballV = if (legacy) lc.peak else nc.peak
            val gap = ballV - barV
            if (gap > r.maxGap) r.maxGap = gap
            if (prevBar.isFinite()) {
                val jump = abs(sqrt(barV) - sqrt(prevBar)) * pxPerSqrt
                if (jump > r.maxJumpPx) r.maxJumpPx = jump
                if (barV == prevBar) r.zeroChange++
                val vel = (ballV - prevBall) / (dt / 1000f)
                val groundUp = barV - prevBar
                if (prevBall.isFinite() && prevPrevBall.isFinite()) {
                    val accel = (ballV - 2f * prevBall + prevPrevBall) / (dt / 1000f) / (dt / 1000f)
                    // 只统计**连续离地 ≥3 帧**的样本：贴地/刚离地那一两帧里有地面反力与
                    // 释放瞬间，它们不是"自由落体"（这一步是修掉第一版口径的错误后的写法）。
                    if (airborneRun >= 2 && gap > 1e-3f) {
                        r.accelSum += accel
                        r.accelN++
                        r.accelSamples.add(accel)
                        if (abs(accel + g) < 0.1f * g) r.freeFallN++
                        val dev = accel + g
                        if (abs(dev) > abs(r.accelWorst)) r.accelWorst = dev
                    }
                }
                // 反弹 = 贴着柱顶、速度由负转正，且**柱子没有在升**（被顶起来的不算）。
                if (gap <= 0.01f && prevVel < -0.05f && vel > 0.05f && groundUp <= 1e-5f) {
                    r.bounces++
                }
                prevVel = vel
            }
            airborneRun = if (gap > 1e-3f) airborneRun + 1 else 0
            if (airborneRun > r.maxAirborneRun) r.maxAirborneRun = airborneRun
            if (frame < 240) {
                r.trace.append(String.format("%.4f,%.4f,%.4f%n", barV, ballV, gap))
            }
            prevPrevBall = prevBall
            prevBall = ballV
            prevBar = barV
            simMs += dt
            r.frames++
        }
        // 静音 1 秒：柱高归零 ⇒ 小球必须落到 0 并**停住**
        repeat(200) { if (legacy) lc.step(0f, dt) else nc.step(0f, dt) }
        val fBar = if (legacy) lc.bar else nc.bar
        val fBall = if (legacy) lc.peak else nc.peak
        r.settled = fBar == 0f && fBall == 0f
        return r
    }

    @Test
    fun `同一输入下 改前与改后 —— 同口径四个量`() {
        val before = simulate(legacy = true)
        val after = simulate(legacy = false)
        println(before)
        println(after)
        println("TRACE_BEFORE_BEGIN")
        print(before.trace)
        println("TRACE_BEFORE_END")
        println("TRACE_AFTER_BEGIN")
        print(after.trace)
        println("TRACE_AFTER_END")

        // ── 结构性差别（这两条就是用户说的"没有弹起来"的物理含义）──
        val medianBefore = before.accelSamples.sorted().let { if (it.isEmpty()) Float.NaN else it[it.size / 2] }
        val medianAfter = after.accelSamples.sorted().let { if (it.isEmpty()) Float.NaN else it[it.size / 2] }
        println(String.format(
            "落体加速度中位数：改前 %+.2f /s²（匀速下落 ⇒ 0）、改后 %+.2f /s²（自由落体 ⇒ −g = %+.1f）",
            medianBefore, medianAfter, -BandBallistics.GRAVITY))
        println(String.format(
            "自由落体占比（|a+g| < 10%%）：改前 %.0f%%、改后 %.0f%%",
            100.0 * before.freeFallN / before.accelN, 100.0 * after.freeFallN / after.accelN))
        assertEquals("改前是匀速下落（中位加速度 0）", 0.0, medianBefore.toDouble(), 0.5)
        assertEquals("改后必须是自由落体（中位加速度 −g）", -BandBallistics.GRAVITY.toDouble(), medianAfter.toDouble(), 1.0)
        assertTrue("改前几乎没有自由落体帧", 100.0 * before.freeFallN / before.accelN < 5.0)
        assertTrue("改后绝大多数离地帧都是自由落体", 100.0 * after.freeFallN / after.accelN > 80.0)
        assertEquals("改前落地后永不反弹", 0, before.bounces)
        assertTrue("改后落地后必须反弹（实测 ${after.bounces} 次）", after.bounces >= 1)
        assertTrue("两边都必须收敛到 0（不空转契约）", before.settled && after.settled)
    }

    companion object {
        /** 录像实测：满幅（v=1）时色带半高 45.5px（CENTER 到顶边 45.5px）。 */
        private const val PX_PER_SQRT = 45.5f

        /**
         * 用户录像里逐格量到的**真实柱高**（归一化，85 格 = 三泳道 28×3+1）。
         * 三条泳道均值 0.198 / 0.395 / 0.630 —— 与画面"左低右高"一致。
         */
        private val TARGETS = floatArrayOf(
            0.184f, 0.365f, 0.420f, 0.148f, 0.088f, 0.044f, 0.044f, 0.365f, 0.420f, 0.088f,
            0.027f, 0.044f, 0.267f, 0.314f, 0.148f, 0.027f, 0.148f, 0.148f, 0.223f, 0.420f,
            0.148f, 0.148f, 0.267f, 0.223f, 0.184f, 0.314f, 0.223f, 0.116f, 0.314f, 0.679f,
            0.479f, 0.420f, 0.542f, 0.420f, 0.420f, 0.679f, 0.365f, 0.314f, 0.365f, 0.365f,
            0.365f, 0.420f, 0.314f, 0.267f, 0.314f, 0.314f, 0.365f, 0.542f, 0.365f, 0.267f,
            0.542f, 0.365f, 0.365f, 0.365f, 0.267f, 0.267f, 0.365f, 0.609f, 0.679f, 0.754f,
            0.832f, 0.754f, 0.832f, 1.000f, 0.754f, 0.609f, 0.914f, 0.754f, 0.609f, 0.542f,
            0.479f, 0.542f, 0.609f, 0.609f, 0.609f, 0.679f, 0.609f, 0.542f, 0.754f, 0.609f,
            0.542f, 0.420f, 0.420f, 0.420f, 0.420f,
        )
    }
}
