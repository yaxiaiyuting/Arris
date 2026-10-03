/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.8.0 · P1-A：A/B 档新增状态（峰值保持 + 两个动画相位）的纯逻辑单测。
 *
 * 为什么每一条都必须钉住「最终 `pump` 返回 false」：探针 §1 明确警告过，
 * 「峰值/涟漪/粒子只活在 draw 里 ⇒ bars 一收敛 `pump` 就返回 false ⇒ 峰值冻在画面上永不落下」
 * 是这一档最容易踩的坑（v1.8.1 被单测抓到过同形状的缺陷）。所以本文件的每个用例末尾
 * 都在断言"它会停下来"，而不只是断言"它动过"。
 */
class WaveformRingTierTest {

    private val baseline = VisualizerEffects.BASELINE

    /** 精致档（A+B 全开）。 */
    private val refined = VisualizerEffects.of(
        tier = VisualizerTier.REFINED,
        showcase = false,
        shockwave = false,
        particles = false,
        perspective = false,
        tapInteraction = false,
    )

    /** A 档但**关掉峰值**（能力位单独关的场景，用来证明这一位真的在起作用）。 */
    private val noPeaks = VisualizerEffects(
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

    private fun settle(
        ring: WaveformRing,
        effects: VisualizerEffects,
        frames: Int = 400,
        dtMs: Float = 16f,
        active: Boolean = true,
    ): Int {
        var n = 0
        while (n < frames) {
            if (!ring.pump(active = active, dtMs = dtMs, effects = effects)) return n
            n++
        }
        return -1
    }

    // ------------------------------------------------------------------
    // A 档：峰值保持
    // ------------------------------------------------------------------

    @Test
    fun `峰值跟随柱子上冲，并在柱子回落时保持`() {
        val ring = WaveformRing(capacity = 16, barCount = 2)
        ring.push(0f)
        ring.push(1f)
        ring.pump(active = true, dtMs = 16f, effects = refined)
        val peakAfterRise = ring.peakAt(1)
        assertTrue("峰值必须跟上柱子", peakAfterRise > 0.5f)

        // 数据掉到 0：柱子开始回落，但峰值在保持期内不应跟着掉
        ring.push(0f)
        ring.pump(active = true, dtMs = 16f, effects = refined)
        assertEquals(peakAfterRise, ring.peakAt(1), 1e-6f)
        assertTrue("柱子在回落", ring.barAt(1) < peakAfterRise)
    }

    @Test
    fun `保持期结束后峰值下落，但绝不低于柱子`() {
        val ring = WaveformRing(capacity = 16, barCount = 2)
        ring.push(1f)
        ring.pump(active = true, dtMs = 16f, effects = refined)
        // 数据掉到 0：柱子开始回落、峰值进入保持期
        ring.push(0f)
        ring.pump(active = true, dtMs = 16f, effects = refined)
        val peakAtHoldStart = ring.peakAt(1)
        // 保持期（420ms ≈ 26 帧）内不落
        repeat(10) { ring.pump(active = true, dtMs = 16f, effects = refined) }
        assertEquals(peakAtHoldStart, ring.peakAt(1), 1e-6f)
        // 再跑 60 帧（≈960ms）必然已经在下落
        repeat(60) { ring.pump(active = true, dtMs = 16f, effects = refined) }
        assertTrue("保持期结束后必须开始下落", ring.peakAt(1) < peakAtHoldStart)
        assertTrue("峰值不得低于柱子", ring.peakAt(1) >= ring.barAt(1) - 1e-6f)
    }

    /**
     * **收敛判据必须包含峰值**：柱子在几十帧内就归零，峰值要等保持期 + 匀速下落才归零。
     * 如果 `pump` 只看 bars，峰值会冻在半空中永不落下。
     */
    @Test
    fun `峰值归零之后才停止重绘，且之后不再要求重绘`() {
        val ring = WaveformRing(capacity = 16, barCount = 2)
        ring.push(1f)
        ring.pump(active = true, dtMs = 16f, effects = refined)
        // 静音：数据回到 0，于是「柱子 + 峰值 + 相位」三者都必须停下来
        ring.push(0f)
        val frames = settle(ring, refined)
        assertTrue("必须在有限帧内收敛（否则就是峰值冻结/无限重绘）", frames in 1..400)
        assertEquals(0f, ring.barAt(1), 0f)
        assertEquals(0f, ring.peakAt(1), 0f)
        assertFalse(ring.pump(active = true, dtMs = 16f, effects = refined))

        // 暂停：窗口里还残留着更早的那根柱（targets[0] = 1）⇒ **第一次**暂停调用会把它归零
        // 并要求重绘（柱子要开始回落），之后同样必须收敛到 false。
        val pausedFrames = settle(ring, refined, active = false)
        assertTrue("暂停后的衰减必须有界", pausedFrames in 1..400)
        assertEquals(0f, ring.barAt(0), 0f)
        assertEquals(0f, ring.peakAt(0), 0f)
        assertFalse(ring.pump(active = false, dtMs = 16f, effects = refined))
    }

    /** 暂停（active=false）时峰值也要归零并停下来 —— 暂停后不该有"残留光点"永远重绘。 */
    @Test
    fun `暂停后峰值衰减到零并停止重绘`() {
        val ring = WaveformRing(capacity = 16, barCount = 2)
        ring.push(1f); ring.push(1f)
        repeat(20) { ring.pump(active = true, dtMs = 16f, effects = refined) }
        var frames = 0
        while (ring.pump(active = false, dtMs = 16f, effects = refined)) {
            frames++
            assertTrue("暂停后的衰减必须有界", frames < 400)
        }
        assertEquals(0f, ring.peakAt(0), 0f)
        assertEquals(0f, ring.peakAt(1), 0f)
    }

    @Test
    fun `关掉峰值能力位时峰值恒为零`() {
        val ring = WaveformRing(capacity = 8, barCount = 1)
        ring.push(1f)
        repeat(5) { ring.pump(active = true, dtMs = 16f, effects = noPeaks) }
        assertEquals(0f, ring.peakAt(0), 0f)
        assertTrue("柱子本身照常上升", ring.barAt(0) > 0f)
    }

    @Test
    fun `clear 会重置峰值`() {
        val ring = WaveformRing(capacity = 8, barCount = 2)
        ring.push(1f)
        ring.pump(active = true, dtMs = 16f, effects = refined)
        assertTrue(ring.peakAt(1) > 0f)
        ring.clear()
        assertEquals(0f, ring.peakAt(0), 0f)
        assertEquals(0f, ring.peakAt(1), 0f)
    }

    @Test
    fun `两个数组一起拷进复用数组时峰值跟着走`() {
        val ring = WaveformRing(capacity = 8, barCount = 3)
        ring.push(0.5f)
        repeat(3) { ring.pump(active = true, dtMs = 16f, effects = refined) }
        val bars = FloatArray(3)
        val peaks = FloatArray(3)
        ring.copyInto(bars, peaks)
        assertEquals(ring.barAt(0), bars[0], 0f)
        assertEquals(ring.peakAt(2), peaks[2], 0f)
        assertTrue(peaks[2] >= bars[2])
    }

    // ------------------------------------------------------------------
    // B 档：渐变流动相位 + 呼吸
    // ------------------------------------------------------------------

    @Test
    fun `有信号时相位前进并要求重绘`() {
        val ring = WaveformRing(capacity = 8, barCount = 1)
        ring.push(0.5f)
        ring.pump(active = true, dtMs = 16f, effects = refined)
        val flow0 = ring.flowPhase01()
        // 数据不再变化（不再 push），但相位仍应前进 ⇒ 仍然要求重绘
        val needsRedraw = ring.pump(active = true, dtMs = 16f, effects = refined)
        assertTrue("渐变流动期间必须持续重绘", needsRedraw)
        assertTrue("相位必须前进", ring.flowPhase01() != flow0)
    }

    @Test
    fun `相位在 0 到 1 之间循环`() {
        val ring = WaveformRing(capacity = 8, barCount = 1)
        ring.push(0.5f)
        repeat(500) {
            ring.pump(active = true, dtMs = 16f, effects = refined)
            val phase = ring.flowPhase01()
            assertTrue("flow 相位必须落在 [0,1)：$phase", phase >= 0f && phase < 1f)
        }
    }

    /** 静音段与暂停态**不得**为动画排帧（探针 §2 要求的显式停止条件）。 */
    @Test
    fun `静音时不推进相位也不要求重绘`() {
        val ring = WaveformRing(capacity = 8, barCount = 1)
        // 从未 push 过 ⇒ targets 全零
        assertFalse(ring.pump(active = true, dtMs = 16f, effects = refined))
        assertFalse(ring.pump(active = true, dtMs = 16f, effects = refined))
        assertEquals(0f, ring.flowPhase01(), 0f)

        // 播放过再归零：收敛之后同样不再排帧
        ring.push(1f)
        ring.pump(active = true, dtMs = 16f, effects = refined)
        ring.push(0f)
        val frames = settle(ring, refined)
        assertTrue(frames in 1..400)
        assertFalse(ring.pump(active = true, dtMs = 16f, effects = refined))
    }

    @Test
    fun `暂停时不推进相位`() {
        val ring = WaveformRing(capacity = 8, barCount = 1)
        ring.push(0.5f)
        ring.pump(active = true, dtMs = 16f, effects = refined)
        val phase = ring.flowPhase01()
        ring.pump(active = false, dtMs = 16f, effects = refined)
        assertEquals(phase, ring.flowPhase01(), 0f)
    }

    @Test
    fun `呼吸倍率落在设计区间内`() {
        val ring = WaveformRing(capacity = 8, barCount = 1)
        ring.push(0.5f)
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        repeat(400) {
            ring.pump(active = true, dtMs = 16f, effects = refined)
            val scale = ring.breatheScale()
            if (scale < min) min = scale
            if (scale > max) max = scale
        }
        assertTrue("呼吸不得放大亮度：$max", max <= 1.0001f)
        assertTrue("呼吸必须有可见幅度：$min", min < 1f - WaveformRing.BREATHE_DEPTH * 0.5f)
        assertTrue("呼吸不得暗到看不见：$min", min >= 1f - WaveformRing.BREATHE_DEPTH - 1e-4f)
    }

    @Test
    fun `关掉呼吸能力位时倍率恒为 1`() {
        val ring = WaveformRing(capacity = 8, barCount = 1)
        ring.push(0.5f)
        repeat(30) { ring.pump(active = true, dtMs = 16f, effects = baseline) }
        assertEquals("相位 0 就是满亮度（cos 而不是 sin）", 1f, ring.breatheScale(), 0f)
        assertEquals(0f, ring.flowPhase01(), 0f)

        // 相位推进过之后关掉能力位：倍率仍按相位算，但渲染路径不会去读它（只在 effects.breathe 时读）。
        // 这里只钉住不变量：倍率始终落在设计区间内。
        val scale = ring.breatheScale()
        assertTrue(scale in (1f - WaveformRing.BREATHE_DEPTH - 1e-4f)..1.0001f)
    }

    /** 老重载（无 effects）继续按基线走：既有 WaveformRingTest 的 10 个用例依赖它。 */
    @Test
    fun `无 effects 参数的重载等价于基线效果集`() {
        val withOverload = WaveformRing(capacity = 8, barCount = 2)
        val withBaseline = WaveformRing(capacity = 8, barCount = 2)
        withOverload.push(0.7f)
        withBaseline.push(0.7f)
        repeat(20) {
            withOverload.pump(active = true, dtMs = 16f)
            withBaseline.pump(active = true, dtMs = 16f, effects = baseline)
        }
        assertEquals(withBaseline.barAt(1), withOverload.barAt(1), 0f)
        assertEquals(withBaseline.peakAt(1), withOverload.peakAt(1), 0f)
    }
}
