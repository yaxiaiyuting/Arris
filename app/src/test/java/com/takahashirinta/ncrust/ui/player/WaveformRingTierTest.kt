/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
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
    fun `小球站在柱顶上，柱子回落时被留在空中（弹道滞后）`() {
        // v3.4.5：峰值保持（420ms 计时器 + 匀速下落）已被物理模型取代。
        // 现在小球是一个受重力的质点，柱顶是地面 —— 它**不会**跟着柱子瞬间下降，
        // 而是被留在空中自由落体。这一条就是用户要的「弹起来」的来源。
        val ring = WaveformRing(capacity = 16, barCount = 2)
        ring.push(0f)
        ring.push(1f)
        repeat(12) { ring.pump(active = true, dtMs = 16f, effects = refined) }
        val barAfterRise = ring.barAt(1)
        assertTrue("柱子必须升到接近满幅（实测 $barAfterRise）", barAfterRise > 0.8f)
        assertTrue("小球必须站在柱顶上（绝不低于柱高）", ring.peakAt(1) >= barAfterRise - 1e-6f)

        // 数据掉到 0：柱子开始回落 ⇒ 小球被留在空中
        ring.push(0f)
        repeat(3) { ring.pump(active = true, dtMs = 16f, effects = refined) }
        assertTrue("柱子必须已经明显回落", ring.barAt(1) < barAfterRise - 0.05f)
        assertTrue(
            "小球必须留在柱子**上方**（弹道滞后，不是粘在柱顶上）：peak=${ring.peakAt(1)} bar=${ring.barAt(1)}",
            ring.peakAt(1) > ring.barAt(1) + 1e-4f,
        )
        // v3.4.6：重力从 32 降到 10（用户：「落地好快…像平移到下一帧」），滞空时间变长 ——
        // 被顶起后小球可能**还在上升段**，写死"3 帧后必须在下落"会随参数一起变红。
        // 改成在窗口内观察它是否真的出现过下落段（这才是"弹道滞后"的可证伪命题）。
        var sawDescending = ring.peakVelAt(1) < 0f
        var stillAbove = true
        repeat(40) {
            if (sawDescending) return@repeat
            ring.pump(active = true, dtMs = 16f, effects = refined)
            if (ring.peakVelAt(1) < 0f) sawDescending = true
            if (ring.peakAt(1) <= ring.barAt(1) + 1e-4f) stillAbove = false
        }
        assertTrue("小球必须出现下落段（弹道滞后，而不是粘着柱子一起下去）", sawDescending)
        assertTrue("在下落过程中小球必须始终在柱子之上", stillAbove)
    }

    @Test
    fun `小球落地后必须反弹（位置序列出现局部极小再上升）`() {
        val ring = WaveformRing(capacity = 16, barCount = 2)
        ring.push(0f)
        ring.push(1f)
        repeat(12) { ring.pump(active = true, dtMs = 16f, effects = refined) }
        ring.push(0f)
        var minSeen = Float.MAX_VALUE
        var bounces = 0
        var wasFalling = false
        var prev = ring.peakAt(1)
        repeat(400) {
            ring.pump(active = true, dtMs = 16f, effects = refined)
            val now = ring.peakAt(1)
            if (now < prev) wasFalling = true
            if (now < minSeen) {
                minSeen = now
            } else if (wasFalling && now > minSeen + 1e-4f) {
                // 局部极小之后又上升 == 一次反弹
                bounces++
                wasFalling = false
            }
            prev = now
        }
        assertTrue("落地后必须出现反弹（局部极小再上升），实测 $bounces 次", bounces >= 1)
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
