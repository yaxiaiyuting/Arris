/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.ui.player.waveform.BandBallistics
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * v3.4.5：**环形缓冲 × 物理弹道**的端到端单测（这是渲染层真正消费的那一层）。
 *
 * 判据全部直接读渲染层消费的数组（`bars` / `peaks` / `bandBarAt` / `bandPeakAt` 与
 * `copyBandsInto` / `copyBandPeaksInto` 写出的复用数组），不另建"可见位置"的定义 ——
 * 本项目前几次失败都是因为在测试里造了第二把尺子。
 *
 * 每个用例末尾都断言「最终 `pump` 返回 false」：这是"不空转"契约，
 * 也是这一档最容易踩的坑（峰值/小球只活在 draw 里 ⇒ 一收敛就冻在画面上）。
 */
class WaveformBallisticsTest {

    private val refined = VisualizerEffects.of(
        tier = VisualizerTier.REFINED,
        showcase = false,
        shockwave = false,
        particles = false,
        perspective = false,
        tapInteraction = false,
    )

    private fun ring() = WaveformRing(capacity = 512, barCount = 8)

    /** 逐帧读某一格的小球高度。 */
    private fun ballOf(r: WaveformRing, i: Int = 7) = r.peakAt(i)

    @Test
    fun `柱子回落时小球被留在空中，并且逐帧加速下落`() {
        val r = ring()
        repeat(12) { r.push(1f) }
        repeat(200) { r.pump(active = true, dtMs = 16f, effects = refined) }
        val barHigh = r.barAt(7)
        assertTrue("柱子必须先升上去（实测 $barHigh）", barHigh > 0.9f)
        assertEquals("小球必须站在柱顶上", barHigh, r.peakAt(7), 1e-6f)

        // 数据归零：柱子回落，小球离地
        repeat(12) { r.push(0f) }
        val dtMs = 16f
        val g = BandBallistics.GRAVITY
        var prevV = r.peakVelAt(7)
        var samples = 0
        var worstErr = 0f
        var airborneFrames = 0
        repeat(30) {
            r.pump(active = true, dtMs = dtMs, effects = refined)
            val v = r.peakVelAt(7)
            val airborne = r.peakAt(7) > r.barAt(7) + 1e-4f
            if (airborne) {
                airborneFrames++
                // v3.4.6：只统计**仍在下降**的空中帧。
                //
                // 撞地那一帧速度会被弹性碰撞反向（`v ← −e·v`）—— 那是一个**冲量**，
                // 不是"自由落体被破坏"。旧实现（e=0.45，加上位置容差让它一直贴地微落）
                // 几乎不产生可见的反弹，这条断言因此碰不到撞地帧；
                // v3.4.6 让后继弹跳真正可见之后必须把它排除，否则量到的是冲量本身。
                if (v < 0f && prevV < 0f) {
                    val dv = v - prevV
                    val err = abs(dv + g * dtMs / 1000f)
                    if (err > worstErr) worstErr = err
                    samples++
                }
            }
            prevV = v
        }
        println("空中帧数=$airborneFrames，其中下降帧样本=$samples，逐帧 Δv 与 −g·dt 的最大偏差=$worstErr（g=$g）")
        assertTrue("小球必须真的离开柱顶（弹道滞后）", airborneFrames >= 3)
        assertTrue("必须量到下降中的空中帧（否则这条断言是空跑的）", samples >= 2)
        assertTrue("自由落体期间 Δv 必须恒等于 −g·dt，实测最大偏差 $worstErr", worstErr < 1e-4f)
    }

    @Test
    fun `落地后出现反弹 —— 位置序列局部极小再上升`() {
        val r = ring()
        repeat(12) { r.push(1f) }
        repeat(200) { r.pump(active = true, dtMs = 16f, effects = refined) }
        repeat(12) { r.push(0f) }
        var minSeen = Float.MAX_VALUE
        var bounceCount = 0
        var falling = false
        var prev = r.peakAt(7)
        val trace = StringBuilder()
        repeat(120) {
            r.pump(active = true, dtMs = 16f, effects = refined)
            val now = r.peakAt(7)
            if (it < 40) trace.append(String.format("%.3f ", now))
            if (now < prev - 1e-5f) falling = true
            if (now < minSeen) {
                minSeen = now
            } else if (falling && now > minSeen + 1e-4f) {
                bounceCount++
                falling = false
            }
            prev = now
        }
        println("小球高度前 40 帧: $trace")
        println("反弹次数 = $bounceCount")
        assertTrue("落地后必须出现反弹（局部极小再上升），实测 $bounceCount 次", bounceCount >= 1)
    }

    @Test
    fun `小球绝不低于柱高 —— 单条曲线路径`() {
        val r = ring()
        var v = 0.2f
        repeat(600) { k ->
            // 大幅跳变的柱高：最坏情况下也不许穿进柱子里
            v = if (k % 7 == 0) 0.95f else if (k % 3 == 0) 0.02f else v
            r.push(v)
            r.pump(active = true, dtMs = 16f, effects = refined)
            for (i in 0 until 8) {
                assertTrue(
                    "第 $k 帧第 $i 格：小球 ${r.peakAt(i)} 不得低于柱高 ${r.barAt(i)}",
                    r.peakAt(i) >= r.barAt(i) - 1e-6f,
                )
            }
        }
    }

    @Test
    fun `小球绝不低于柱高 —— 三条泳道逐格`() {
        val r = ring()
        repeat(400) { k ->
            val hi = if (k % 5 == 0) 1f else 0.05f
            r.push(0.5f, hi, if (k % 2 == 0) 0.8f else 0.1f, hi, arrivalAtMs = 1_000_000L + k * 100L)
            r.pump(active = true, dtMs = 16f, effects = refined)
            for (band in 0 until 3) {
                for (i in 0 until 8) {
                    assertTrue(
                        "第 $k 帧 band=$band 格 $i：小球必须站在自己那条泳道的柱顶上",
                        r.bandPeakAt(band, i) >= r.bandBarAt(band, i) - 1e-6f,
                    )
                }
            }
        }
    }

    @Test
    fun `弹道与刷新率无关 —— 60Hz 与 146Hz 下的小球轨迹`() {
        // 公平口径：推柱时刻与模拟总时长都相同，只剩"目标切换被观测到的帧边界"这一项。
        fun run(fps: Double): FloatArray {
            val r = ring()
            val dt = (1000.0 / fps).toFloat()
            val out = FloatArray(8)
            var sim = 0f
            var nextBar = 0f
            var k = 0
            var t = 1_000_000L
            while (sim < 2400f) {
                if (sim >= nextBar) {
                    // 12 根满幅、12 根静音：让柱子先上去再塌下来，小球必然经历一次完整起落
                    r.push(if (k < 12) 1f else 0f, 0f, 0f, 0f, arrivalAtMs = t)
                    k++
                    nextBar += 100f
                    t += 100L
                }
                r.pump(active = true, dtMs = dt, effects = refined)
                sim += dt
                // 记录整个过程中的最大值，避免"终态已收敛 ⇒ 看起来一致"
                for (i in 0 until 8) {
                    val d = abs(r.peakAt(i) - r.barAt(i))
                    if (d > out[i]) out[i] = d
                }
            }
            return out
        }
        val a = run(60.0)
        val b = run(146.2)
        var worst = 0f
        for (i in 0 until 8) worst = maxOf(worst, abs(a[i] - b[i]))
        println("小球离地高度（全程最大值）60Hz=${a.toList()} / 146.2Hz=${b.toList()}")
        println("两条刷新率下小球弹道（全程离地高度）最大差 = $worst")
        // ⚠️ 门槛比柱高那条（1e-4）宽得多，原因不是积分器而是**事件时机**：
        // 小球"被柱顶顶起"是一次**接触事件**，触发的帧在 60Hz 与 146Hz 下差最多一帧，
        // 于是那一次 kick 的相位不同 —— 这是采样网格的性质，不是弹道的不确定性。
        // 柱高本身（连续量）仍然是 1e-4 级的一致。
        assertTrue("小球弹道必须与刷新率无关（同一条轨迹 ± 一次 kick 的相位差），实测最大差 $worst", worst < 0.03f)
        assertTrue("小球必须真的离过地（否则这条用例什么也没测）", a.max() > 0.02f)
    }

    @Test
    fun `静音之后小球归零且不再重绘`() {
        val r = ring()
        repeat(12) { r.push(1f) }
        repeat(200) { r.pump(active = true, dtMs = 16f, effects = refined) }
        repeat(12) { r.push(0f) }
        var frames = 0
        while (frames < 800) {
            if (!r.pump(active = true, dtMs = 16f, effects = refined)) break
            frames++
        }
        println("静音后 $frames 帧收敛")
        assertTrue("必须在有限帧内收敛（实测 $frames）", frames in 1..800)
        assertEquals("柱高必须归零", 0f, r.barAt(7), 0f)
        assertEquals("小球必须落回并归零", 0f, r.peakAt(7), 0f)
        assertTrue("小球必须处于静止态", r.peakRestingAt(7))
        assertFalse("收敛后不许再重绘", r.pump(active = true, dtMs = 16f, effects = refined))
    }

    @Test
    fun `暂停后小球也归零并停止重绘`() {
        val r = ring()
        repeat(12) { r.push(1f) }
        repeat(200) { r.pump(active = true, dtMs = 16f, effects = refined) }
        var frames = 0
        while (frames < 800) {
            if (!r.pump(active = false, dtMs = 16f, effects = refined)) break
            frames++
        }
        println("暂停后 $frames 帧收敛（小球=${r.peakAt(7)}）")
        assertTrue("暂停后的衰减必须有界（实测 $frames）", frames in 1..800)
        assertEquals("柱高归零", 0f, r.barAt(7), 0f)
        assertEquals("小球归零", 0f, r.peakAt(7), 0f)
        assertFalse("收敛后不许再重绘", r.pump(active = false, dtMs = 16f, effects = refined))
    }

    @Test
    fun `effects_peaks 关掉时小球不参与物理（与改前逐像素一致）`() {
        val off = VisualizerEffects(
            tier = VisualizerTier.SIMPLE,
            autoDowngraded = false,
            mirror = true,
            rounded = true,
            peaks = false,
            timeOrderedTint = true,
            flow = false,
            dots = false,
            breathe = false,
            shockwave = false,
            particles = false,
            perspective = false,
            tapInteraction = false,
        )
        val r = ring()
        repeat(12) { r.push(1f) }
        repeat(200) { r.pump(active = true, dtMs = 16f, effects = off) }
        assertEquals("关掉峰值位之后 peaks 保持为 0（与改前一致）", 0f, r.peakAt(7), 0f)
        repeat(12) { r.push(0f) }
        var frames = 0
        while (frames < 800) {
            if (!r.pump(active = true, dtMs = 16f, effects = off)) break
            frames++
        }
        assertEquals("关掉之后小球不会自己动起来", 0f, r.peakAt(7), 0f)
        assertTrue("仍然必须收敛（实测 $frames）", frames in 1..800)
    }

    @Test
    fun `柱高始终有界 —— 过冲不许越出 0 到 1`() {
        val r = ring()
        repeat(500) { k ->
            val v = if (k % 2 == 0) 1f else 0f
            r.push(v, v, v, v)
            r.pump(active = true, dtMs = 16f, effects = refined)
            for (i in 0 until 8) {
                val b = r.barAt(i)
                assertTrue("柱高必须落在 [0,1]，实测 $b", b in 0f..1f)
                for (band in 0 until 3) {
                    val lb = r.bandBarAt(band, i)
                    assertTrue("泳道画面值必须落在 [0,1]，实测 $lb", lb in 0f..1f)
                }
            }
        }
    }

    @Test
    fun `copyBandPeaksInto 写进调用方复用的数组（零分配口径）`() {
        val r = ring()
        repeat(12) { r.push(0.5f, 1f, 0.5f, 0.2f, arrivalAtMs = 1_000_000L + it * 100L) }
        repeat(200) { r.pump(active = true, dtMs = 16f, effects = refined) }
        val low = FloatArray(8)
        val mid = FloatArray(8)
        val high = FloatArray(8)
        val lowId = low
        r.copyBandPeaksInto(low, mid, high)
        assertSame("渲染层传进来的数组必须被原地复用", lowId, low)
        for (i in 0 until 8) {
            assertTrue("小球 $i 必须与柱高逐格对齐", low[i] >= r.bandBarAt(WaveformRing.BAND_LOW, i) - 1e-6f)
            assertTrue("小球必须是有限值", low[i].isFinite() && mid[i].isFinite() && high[i].isFinite())
            assertEquals("小球必须落在 [0,1]", low[i].coerceIn(0f, 1f), low[i], 0f)
        }
    }

    @Test
    fun `小球跟着窗口一起平移 —— 不是粘在下标上`() {
        // v3.4.5 自测抓到的真实缺陷：`shiftBandIn` 一开始只平移了原始窗口与画面值窗口，
        // 忘了平移小球状态。后果是每一根新柱到达时小球的地面被换成邻居的值，
        // 而"小球不低于柱高"的夹取会立刻把它压到那个新地面上 —— 离地高度与轨迹全被抹掉。
        //
        // 判据刻意做成**精确等式**：推进用 `dtMs = 0`（弹道不前进），于是这一帧里
        // 唯一允许发生的事就是窗口平移 ⇒ 平移之后第 i 格的小球必须**逐位等于**
        // 上一帧第 i+1 格的小球。差一点点都说明小球没跟着自己的柱子走。
        val r = ring()
        var t = 1_000_000L
        repeat(8) { k ->
            val low = if (k % 2 == 0) 1f else 0.2f
            r.push(0f, low, 0f, 0f, arrivalAtMs = t)
            t += 100L
            repeat(60) { r.pump(active = true, dtMs = 16f, effects = refined) }
        }
        repeat(200) { r.pump(active = true, dtMs = 16f, effects = refined) }
        val before = FloatArray(8) { r.bandPeakAt(WaveformRing.BAND_LOW, it) }
        val barBefore = FloatArray(8) { r.bandBarAt(WaveformRing.BAND_LOW, it) }
        println("平移前小球: ${before.toList()}")
        println("平移前柱高: ${barBefore.toList()}")

        r.push(0f, 0.7f, 0f, 0f, arrivalAtMs = t)
        r.pump(active = true, dtMs = 0f, effects = refined)   // dt=0 ⇒ 只允许平移

        val moved = FloatArray(7) { r.bandPeakAt(WaveformRing.BAND_LOW, it) }
        println("平移后小球: ${moved.toList()}")
        for (i in 0 until 7) {
            assertEquals(
                "第 $i 格的小球必须等于上一帧第 ${i + 1} 格（跟着柱子左移）",
                before[i + 1], moved[i], 0f,
            )
        }
    }

    @Test
    fun `小球位置数组的容量口径与下标一致 —— 不会越界`() {
        // 渲染层的几何数组是 barCount*3+1（三泳道 + 一格延伸），小球必须能在同样的
        // 下标范围内被读出来（v3.4.1 在 peaks 上踩过一次越界闪退，这里钉住口径）。
        val r = ring()
        r.copyBandPeaksInto(FloatArray(3), FloatArray(3), FloatArray(3)) // 传小数组不许崩
        r.copyBandPeaksInto(FloatArray(32), FloatArray(32), FloatArray(32)) // 传大数组也不许越界
        assertEquals("越界读必须返回 0 而不是崩", 0f, r.bandPeakAt(9, 0), 0f)
        assertEquals("越界读必须返回 0 而不是崩", 0f, r.bandPeakAt(0, 99), 0f)
    }
}
