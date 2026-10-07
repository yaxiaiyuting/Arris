/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 波形滚动抖动的**量化台**（JVM，驱动真实的 [WaveformRing]）。
 *
 * ## 它存在的唯一理由：判据的口径必须等于渲染层真正消费的量
 *
 * `AudioVisualizer` 的三泳道分支逐字是：
 *
 * ```kotlin
 * val phase = WaveformStore.scrollPhase01()            // 0..1
 * for (i in 0 until n) {
 *     val a = window[i]
 *     val b = if (i < n - 1) window[i + 1] else window[n - 1]
 *     val v = a + (b - a) * phase                      // 相邻两格之间线性插值
 *     ...
 * }
 * ```
 *
 * 于是「第 i 格在画面上的横向位置」只由两个量决定：
 *
 *  - **窗口本身被 ring 平移了几格** —— 离散量，每一根新柱到达时 +1（[WaveformRing.shiftedCellsForTest]）；
 *  - **插值相位** —— 连续量，`scrollPhase01()`，取值 0..1（渲染层里 `phase = 1` 与 `phase = 0` 等价，
 *    因为那时 `b − a` 已经整体换了一格）。
 *
 * 所以**每帧的可见位移**（单位 = 格）只可能是：
 *
 * ```
 * 位移_n = (shiftedCells_n − shiftedCells_{n−1}) + (phase_n − phase_{n−1})
 * ```
 *
 * 这一条式子**没有第三个自由度**。v3.3.0 的三次失败全部来自在测试里另建了一套
 * 「可见位置」的定义（只量相位差分 / 把平移硬编码成 +1 / `shiftedCells + frac(phase)`），
 * 于是仿真绿灯、真机依旧抖。本文件**不定义任何位置量**：它只把上面那两个数读出来相减。
 *
 * ## 三条被量化的东西
 *
 * | 量 | 定义 | 为什么它能代表「肉眼看到的抖」 |
 * |---|---|---|
 * | **步长** | 每帧的可见位移（格） | 人眼跟踪的是位移的一阶差；步长忽大忽小就是抖 |
 * | **顿-冲比** | 连续若干帧的位移之和 ÷ 同一段的名义位移 | 「一顿一冲」的幅度：≈1 是匀速，>1.5 就是能看出来的抽 |
 * | **倒退** | 位移 < 0 的帧 | 相位被清零/回绕时最刺眼的那种 |
 *
 * 名义步长恒为 `dt / 柱间隔`：每根柱到达时窗口正好平移一格，所以**任何**平滑实现
 * （只要它把「一格」均匀摊在一个柱周期上）都必然落在它附近。这不是我在测试里
 * 另立的基准，而是「一格/柱周期」这个物理事实。
 *
 * ## 帧与柱的时间戳来自哪
 *
 * [advanceTo] 由调用方按**帧到达时刻**调用（真实设备上是 vsync 的 `withFrameNanos`），
 * [pushBar] 由调用方按**柱到达时刻**调用（真实设备上是 AudioTrack 的 buffer 回调，
 * 与 vsync **无关** —— 这正是抖动的来源之一）。`dtMs` 一律取两次帧时刻之差，
 * 与 `MotionFrameClock` 逐字一致。
 *
 * 非线程安全：单测单线程使用。
 */
class WaveformScrollJitterHarness(
    capacity: Int = 512,
    private val barCount: Int = 16,
) {
    val ring = WaveformRing(capacity = capacity, barCount = barCount)

    /** 每一帧一条记录。 */
    class Frame(
        /** 帧到达时刻（毫秒，模拟时钟）。 */
        val tMs: Double,
        /** 距上一帧的毫秒数（喂给 `pump` 的那个 `dtMs`）。 */
        val dtMs: Float,
        /** 窗口累计平移格数。 */
        val shiftedCells: Long,
        /** `scrollPhase01()`。 */
        val phase: Float,
        /** 这一帧的可见位移（格）= Δ平移 + Δ相位。 */
        val displacement: Float,
        /** `pump` 的返回值（要不要重绘）。 */
        val wantsRedraw: Boolean,
        /**
         * 这一帧相位斜坡**实际使用的分母**（`phaseIntervalMs()` 的读数，不是滑动平均）——
         * 判据读的必须是被消费的那个量，读滑动平均会在分母不是它的时候把结论读反（v3.4.7）。
         */
        val intervalMs: Float,
    )

    private val records = ArrayList<Frame>()

    private var lastFrameT = Double.NaN

    private var prevShifted = 0L

    private var prevPhase = 0f

    /**
     * v3.4.7：是否把**帧时间戳**喂给环（`pump(nowMs = 帧到达时刻)`）。
     *
     * 默认 true = **生产模型**：相位由「这一帧的时间戳 − 最后一根被消费的柱的到达时刻」算出，
     * 两个量同源。设成 false 只剩回落口径（环自己按 dt 累加），用来对照。
     */
    var feedFrameClock: Boolean = true

    /** 累计推入的柱数（名义位移的基准）。 */
    var barsPushed: Int = 0
        private set

    /** 到达过的柱时刻（毫秒）—— 与帧序列一样是调用方给的**事实**，不是推断。 */
    val barArrivalsMs = ArrayList<Double>()

    val frames: List<Frame> get() = records

    /**
     * 推进一帧。
     *
     * @param tMs 帧到达时刻（毫秒）。
     * @param active 播放中（喂给 `pump` 的 `active`）。
     * @param dtScale 把喂给 `pump` 的 `dt` 乘一个系数。**只该影响弹道**（柱高 / 小球），
     *   不该影响相位 —— v3.4.7 起相位的分子分母都来自真实时间戳，`dt` 一个字都不参与。
     *   这条参数就是给「相位是不是从累加量来的」那一条判据用的。
     */
    fun advanceTo(tMs: Double, active: Boolean = true, dtScale: Float = 1f): Frame {
        val dt = if (lastFrameT.isNaN()) 0f else (tMs - lastFrameT).toFloat()
        lastFrameT = tMs
        // 逐字复刻 MotionFrameClock：dt 取两次帧时间戳之差，首帧用名义帧间隔。
        val dtMs = (if (records.isEmpty()) FIRST_FRAME_DT_MS else dt) * dtScale
        val wantsRedraw = ring.pump(
            active = active,
            dtMs = dtMs,
            effects = VisualizerEffects.BASELINE,
            // ★ v3.4.7：生产里帧循环把**这一帧自己的时间戳**原样传下去
            //   （`withFrameNanos` 的 frameTimeNanos / 1e6）。这里如实照做 ——
            //   以前只喂 `dtMs`，于是量的是「环自己攒的时钟」，与真机不是一回事。
            nowMs = if (feedFrameClock) tMs.toLong() else 0L,
        )
        val shifted = ring.shiftedCellsForTest()
        val phase = when {
            legacyAnchor -> legacyAnchorPhase(dtMs, shifted)
            legacyPhase -> legacyPhase(shifted, dtMs)
            else -> ring.scrollPhase01()
        }
        val displacement = (shifted - prevShifted).toFloat() + (phase - prevPhase)
        prevShifted = shifted
        prevPhase = phase
        val frame = Frame(tMs, dtMs, shifted, phase, displacement, wantsRedraw, ring.phaseIntervalMsForTest())
        records.add(frame)
        return frame
    }

    // ------------------------------------------------------------------
    // 「改前」复刻之一：v3.2.4 的相位（每根柱到达时**清零**，且只在画面还有内容时推进）
    //
    // 为什么要在测试里复刻旧实现、而不是 checkout 老代码：判据必须只有一份。
    // 复刻只用**环本身报出来的** `barIntervalMs()`，与生产同源；本模式与新模式
    // 喂同一份帧/柱时间线、用同一个 `位移 = Δ平移格 + Δphase` 口径 ⇒ 改前改后可比。
    // ------------------------------------------------------------------

    /** true = 用 v3.2.4 的相位（复刻缺陷）；false = 用当前生产的 `scrollPhase01()`。 */
    var legacyPhase: Boolean = false

    private var legacySinceBarMs = 0f

    private fun legacyPhase(shifted: Long, dtMs: Float): Float {
        // v3.2.4 的**顺序本身就是缺陷**：先算柱间隔、再判信号门控、最后才（在门控通过时）清零。
        // 门控用的是 `targets[barCount-1]`（**未平滑的原始柱高**）⇒ 真实音乐里
        // 单根安静缓冲会让相位**冻在**旧值上，下一根有声的柱到达时才归零 + 摊开整格位移。
        // 环自己维护柱间隔的滑动平均，这里读它的 `barIntervalMs()`，与生产同源。
        val interval = if (ring.barIntervalMs() > 1f) {
            ring.barIntervalMs()
        } else {
            WaveformRing.DEFAULT_BAR_INTERVAL_MS
        }
        val hasSignal = ring.targetAt(barCount - 1) > WaveformRing.ANIMATION_MIN_SIGNAL
        if (hasSignal) {
            legacySinceBarMs += dtMs
            if (shifted != prevShifted) legacySinceBarMs = 0f
        }
        val p = legacySinceBarMs / interval
        return if (p.isFinite()) p.coerceIn(0f, 1f) else 0f
    }

    // ------------------------------------------------------------------
    // 「改前」复刻之二：**v3.3.2 ~ v3.4.6 的锚点**（跨时钟相减 —— v3.4.7 定位到的真根因）
    //
    // 逐字复刻那三行：
    //   frameClockMs += dtMs.toLong()                                   // ← 逐帧截断
    //   if (clockOffsetMs == 0L) clockOffsetMs = frameClockMs - arrival  // 只标定一次
    //   sinceBarMs = frameClockMs - clockOffsetMs - arrival             // ← 两个时钟相减
    //   sinceBarMs = foldScrollPhase(sinceBarMs, interval, consumed)
    //
    // 它必须能复现真机探针量到的指纹：`sinceBarMs` 只在 {0, 柱间隔} 之间跳、到达帧位移塌成 0。
    // 它**只活在测试里**，用来给「改前/改后」提供同一份时间线上的对照（与 `legacyPhase` 同规矩）。
    // ------------------------------------------------------------------

    /** true = 用 v3.3.2 的跨时钟锚点（复刻缺陷）。 */
    var legacyAnchor: Boolean = false

    private var legacyFrameClockMs = 0L

    private var legacyClockOffsetMs = 0L

    private var legacyAnchorSinceBarMs = 0f

    private fun legacyAnchorPhase(dtMs: Float, shifted: Long): Float {
        val interval = if (ring.barIntervalMs() > 1f) {
            ring.barIntervalMs()
        } else {
            WaveformRing.DEFAULT_BAR_INTERVAL_MS
        }
        legacyFrameClockMs += dtMs.toLong()
        legacyAnchorSinceBarMs += dtMs
        val consumed = (shifted - prevShifted).toInt()
        if (consumed > 0 && barArrivalsMs.isNotEmpty()) {
            val arrival = barArrivalsMs[(shifted - 1).toInt().coerceIn(0, barArrivalsMs.size - 1)].toLong()
            if (legacyClockOffsetMs == 0L) legacyClockOffsetMs = legacyFrameClockMs - arrival
            legacyAnchorSinceBarMs = (legacyFrameClockMs - legacyClockOffsetMs - arrival).toFloat()
            legacyAnchorSinceBarMs = WaveformRing.foldScrollPhase(legacyAnchorSinceBarMs, interval, consumed)
        }
        val p = legacyAnchorSinceBarMs / interval
        return if (p.isFinite()) p.coerceIn(0f, 1f) else 0f
    }

    /**
     * 推一根柱（柱到达时刻与帧到达时刻**独立**）。
     *
     * `arrivalAtMs` 就是生产里 `SystemClock.uptimeMillis()` 的那个值 —— 环用它测**到达间隔**
     * （相位斜坡的分母）。传 0 会让环退回旧口径，所以这里必须如实给。
     */
    fun pushBar(tMs: Double, value: Float = 0.6f) {
        ring.push(
            value = value,
            low = value * 0.8f,
            mid = value * 0.6f,
            high = value * 0.4f,
            arrivalAtMs = tMs.toLong(),
        )
        barArrivalsMs.add(tMs)
        barsPushed++
    }

    /** 名义柱间隔：所有到达间隔的平均（不假设它等于任何常量）。 */
    fun meanBarIntervalMs(): Double {
        if (barArrivalsMs.size < 2) return WaveformRing.DEFAULT_BAR_INTERVAL_MS.toDouble()
        return (barArrivalsMs.last() - barArrivalsMs.first()) / (barArrivalsMs.size - 1)
    }

    /** 某个时刻的名义柱间隔（用整段平均；用于算名义步长）。 */
    fun summary(): Summary {
        val interval = meanBarIntervalMs()
        val analyzed = records.drop(SKIP_FRAMES)
        val steps = analyzed.map { it.displacement }
        val nominal = analyzed.map { it.dtMs / interval.toFloat() }
        val mean = steps.average()
        val meanNominal = nominal.average()
        val variance = steps.sumOf { (it - mean) * (it - mean) } / steps.size.coerceAtLeast(1)
        val sd = sqrt(variance)
        // 「顿-冲比」：滑窗 1 个柱周期内的位移之和 ÷ 同段名义位移之和。
        // 值 >1 说明这一段整体冲得比该有的快（前面必然顿过）。
        var worstBurst = 1.0
        var worstBurstAtMs = 0.0
        val windowMs = interval
        var lo = 0
        for (hi in analyzed.indices) {
            while (analyzed[hi].tMs - analyzed[lo].tMs > windowMs) lo++
            if (analyzed[hi].tMs - analyzed[lo].tMs < windowMs * 0.9) continue
            var sum = 0.0
            var nomSum = 0.0
            for (k in lo..hi) {
                sum += analyzed[k].displacement
                nomSum += analyzed[k].dtMs / interval
            }
            if (nomSum <= 0.0) continue
            val ratio = sum / nomSum
            if (ratio > worstBurst) {
                worstBurst = ratio
                worstBurstAtMs = analyzed[hi].tMs
            }
        }
        val minStep = steps.minOrNull() ?: 0f
        val maxStep = steps.maxOrNull() ?: 0f
        // v3.4.7：**柱到达帧**（窗口平移了的那一帧）的位移。相位若被清零或锚到错误的时钟，
        // 这一帧的位移会塌成 0（用户读到的「顿一下」），而它本该 ≈ 名义步长。
        val arrivalSteps = analyzed.indices
            .filter { i -> i > 0 && analyzed[i].shiftedCells != analyzed[i - 1].shiftedCells }
            .map { i -> analyzed[i].displacement.toDouble() }
        // 停顿帧：位移低于名义的 25%（肉眼就是「顿住」）。
        val stallCount = analyzed.indices.count { i ->
            nominal[i] > 0f && analyzed[i].displacement < nominal[i] * STALL_FRACTION
        }
        val backsteps = steps.count { it < -BACKSTEP_EPS }
        return Summary(
            frames = analyzed.size,
            meanBarIntervalMs = interval,
            nominalStep = meanNominal,
            meanStep = mean,
            sdStep = sd,
            jitterRatio = if (meanNominal > 0.0) sd / meanNominal else 0.0,
            minStep = minStep,
            maxStep = maxStep,
            minStepRatio = if (meanNominal > 0f) minStep / meanNominal.toFloat() else 0f,
            maxStepRatio = if (meanNominal > 0f) maxStep / meanNominal.toFloat() else 0f,
            worstBurstRatio = worstBurst,
            worstBurstAtMs = worstBurstAtMs,
            stallCount = stallCount,
            backsteps = backsteps,
            barsPushed = barsPushed,
            // 位移总量 = 逐帧位移之和（与判据**同一个式子**，不另立基准）。
            advancedCells = analyzed.sumOf { it.displacement.toDouble() },
            // v3.4.7：柱**到达帧**的位移单独统计 —— 相位被清零/锚错时，塌的就是这一帧。
            arrivalCount = arrivalSteps.size,
            arrivalMinStepRatio = if (arrivalSteps.isEmpty() || meanNominal <= 0.0) {
                0f
            } else {
                (arrivalSteps.minOrNull() ?: 0.0).toFloat() / meanNominal.toFloat()
            },
            arrivalMaxStepRatio = if (arrivalSteps.isEmpty() || meanNominal <= 0.0) {
                0f
            } else {
                (arrivalSteps.maxOrNull() ?: 0.0).toFloat() / meanNominal.toFloat()
            },
            // 相位停在 `coerceIn(0,1)` 顶棚的帧数：>2% 就说明斜坡爬不满一格（柱子迟到或分母偏大）。
            ceilingDwell = analyzed.count { it.phase >= 0.999f },
        )
    }

    /**
     * 一段可打印的原始序列（先数据后结论）。
     *
     * 「位移」一列就是渲染层每帧实际执行的位移：`Δ(窗口平移格) + ΔscrollPhase01()`。
     */
    fun dump(fromIndex: Int, count: Int): String {
        val sb = StringBuilder()
        sb.append("帧#        t(ms)     dt(ms)  平移格  phase    位移(格)  重绘  间隔估计\n")
        for (i in fromIndex until minOf(fromIndex + count, records.size)) {
            val f = records[i]
            val prev = records.getOrNull(i - 1)
            val marker = if (prev != null && f.shiftedCells != prev.shiftedCells) "  ← 柱到达" else ""
            sb.append(
                "%4d  %10.3f  %7.3f  %5d  %7.4f  %8.4f  %s  %7.2f%s%n".format(
                    i, f.tMs, f.dtMs, f.shiftedCells, f.phase, f.displacement,
                    if (f.wantsRedraw) "Y" else "n", f.intervalMs, marker,
                ),
            )
        }
        return sb.toString()
    }

    class Summary(
        val frames: Int,
        val meanBarIntervalMs: Double,
        val nominalStep: Double,
        val meanStep: Double,
        val sdStep: Double,
        /** 抖动率 = 步长标准差 ÷ 名义步长。理想匀速 = 0，越大越抖。 */
        val jitterRatio: Double,
        val minStep: Float,
        val maxStep: Float,
        val minStepRatio: Float,
        val maxStepRatio: Float,
        /** 一个柱周期内的最大「冲」的倍数（1.0 = 匀速）。 */
        val worstBurstRatio: Double,
        val worstBurstAtMs: Double,
        val stallCount: Int,
        val backsteps: Int,
        val barsPushed: Int,
        val advancedCells: Double,
        /** v3.4.7：柱到达帧的条数（窗口平移了的那一帧）。 */
        val arrivalCount: Int,
        /** 到达帧位移 ÷ 名义步长。**1.0 ≈ 正确**；0 = 这一帧的位移被丢掉了（旧模型的形状）。 */
        val arrivalMinStepRatio: Float,
        val arrivalMaxStepRatio: Float,
        /** 相位停在 1.0 顶棚的帧数（斜坡爬不满一格 = 画面原地不动）。 */
        val ceilingDwell: Int,
    ) {
        override fun toString(): String = buildString {
            append("柱间隔=%.2fms 名义步长=%.4f 实测均步=%.4f 步长sd=%.5f 抖动率=%.2f%%\n"
                .format(meanBarIntervalMs, nominalStep, meanStep, sdStep, jitterRatio * 100))
            append("最小步=%.4f(%.0f%%名义) 最大步=%.4f(%.0f%%名义) 顿(位移<25%%名义)=%d帧 倒退=%d帧\n"
                .format(minStep, minStepRatio * 100, maxStep, maxStepRatio * 100, stallCount, backsteps))
            append("到达帧 n=%d 位移 %.0f%%~%.0f%% 名义  顶棚停留=%d帧\n"
                .format(arrivalCount, arrivalMinStepRatio * 100, arrivalMaxStepRatio * 100, ceilingDwell))
            append("最差顿-冲比=%.3f× @%.0fms  位移总量=%.2f格  推入=%d根  分析帧数=%d"
                .format(worstBurstRatio, worstBurstAtMs, advancedCells, barsPushed, frames))
        }
    }

    companion object {
        /** 首帧的 `dt`：`MotionFrameClock` 用名义帧间隔（60Hz = 16.667ms）。 */
        const val FIRST_FRAME_DT_MS = 16.667f

        /** 丢掉起播阶段（环形缓冲还没填满、EMA 还没收敛）。 */
        const val SKIP_FRAMES = 120

        /** 位移低于名义步长的这个比例 ⇒ 记一次「顿」。 */
        const val STALL_FRACTION = 0.25

        /** 小于它的负位移算「倒退」（浮点噪声容差）。 */
        const val BACKSTEP_EPS = 0.01f

        /** 判定两条步长序列是否一致时的容差。 */
        fun approxEquals(a: Float, b: Float, eps: Float = 1e-4f): Boolean = abs(a - b) <= eps
    }
}
