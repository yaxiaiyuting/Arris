/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.8.0 · P1-A：C 档状态机（冲击波涟漪 + 粒子）的**有界性**单测。
 *
 * 这一层的风险全在「有界」两个字：涟漪/粒子都是「还没演完的状态」，一旦某个上界写错，
 * 表现是低端机上持续掉帧 + 内存里悄悄长出一个列表 —— 而它在开发机上完全看不出来。
 * 所以每个用例都在断言上界，而不是断言"好看"。
 */
class WaveformEffectsStateTest {

    /** 炫技档 + 四个细分全开。 */
    private val showcase = VisualizerEffects.of(
        tier = VisualizerTier.SHOWCASE,
        showcase = true,
        shockwave = true,
        particles = true,
        perspective = true,
        tapInteraction = true,
    )

    /** 只开冲击波。 */
    private val shockwaveOnly = VisualizerEffects.of(
        tier = VisualizerTier.SHOWCASE, showcase = true, shockwave = true,
        particles = false, perspective = false, tapInteraction = false,
    )

    /** 只开粒子。 */
    private val particlesOnly = VisualizerEffects.of(
        tier = VisualizerTier.SHOWCASE, showcase = true, shockwave = false,
        particles = true, perspective = false, tapInteraction = false,
    )

    private val dt = 50f

    /** 先喂几帧安静值建立基线，再给一个突变。 */
    private fun beat(state: WaveformEffectsState, effects: VisualizerEffects, quietFrames: Int = 6) {
        repeat(quietFrames) { state.update(0.05f, dt, active = true, effects = effects) }
        state.update(0.7f, dt, active = true, effects = effects)
    }

    /**
     * 最快速率的节拍流：3 个安静帧（150ms）+ 1 个突变帧（50ms）= 相邻两次触发间隔 200ms，
     * 刚好越过 180ms 冷却。用来把「池满」这条路径真的走一遍。
     */
    private fun fastBeat(state: WaveformEffectsState, effects: VisualizerEffects, times: Int) {
        repeat(times) { beat(state, effects, quietFrames = 3) }
    }

    @Test
    fun `响度突变触发一次涟漪与一组粒子`() {
        val state = WaveformEffectsState()
        beat(state, showcase)
        assertEquals(1, state.aliveRipples())
        assertEquals(WaveformEffectsState.PARTICLES_PER_BEAT, state.aliveParticles())
    }

    @Test
    fun `安静段落不触发`() {
        val state = WaveformEffectsState()
        repeat(60) { state.update(0.05f, dt, active = true, effects = showcase) }
        assertEquals(0, state.aliveRipples())
        assertEquals(0, state.aliveParticles())
        // 而且没有状态要演 ⇒ 不需要重绘
        assertFalse(state.update(0.05f, dt, active = true, effects = showcase))
    }

    @Test
    fun `响度低于最低门槛的突变不算节拍`() {
        val state = WaveformEffectsState()
        // 0.02 → 0.10：相对涨幅很大，但绝对响度低于 ONSET_MIN_RMS
        repeat(6) { state.update(0.02f, dt, active = true, effects = showcase) }
        state.update(0.10f, dt, active = true, effects = showcase)
        assertEquals(0, state.aliveRipples())
    }

    @Test
    fun `冷却时间内不会为同一个瞬态重复触发`() {
        val state = WaveformEffectsState()
        beat(state, showcase)
        assertEquals(1, state.aliveRipples())
        // 冷却 180ms 内再来一个同样强的值：不再触发
        state.update(0.7f, dt, active = true, effects = showcase)
        assertEquals(1, state.aliveRipples())
        assertEquals(WaveformEffectsState.PARTICLES_PER_BEAT, state.aliveParticles())
    }

    @Test
    fun `连续强节拍也压在上界以内`() {
        val state = WaveformEffectsState()
        repeat(40) { beat(state, showcase) }
        assertTrue(
            "涟漪数必须 ≤ ${WaveformEffectsState.RIPPLE_CAPACITY}，实际 ${state.aliveRipples()}",
            state.aliveRipples() <= WaveformEffectsState.RIPPLE_CAPACITY,
        )
        assertTrue(
            "粒子数必须 ≤ ${WaveformEffectsState.PARTICLE_CAPACITY}，实际 ${state.aliveParticles()}",
            state.aliveParticles() <= WaveformEffectsState.PARTICLE_CAPACITY,
        )
    }

    /** 粒子池写满之后是**覆盖最旧的**，不是丢弃新的，也绝不扩容。 */
    @Test
    fun `粒子池写满后覆盖最旧的而不是扩容`() {
        val state = WaveformEffectsState()
        fastBeat(state, showcase, times = 12)
        assertEquals(WaveformEffectsState.PARTICLE_CAPACITY, state.aliveParticles())
        assertTrue(
            "涟漪池同样封顶",
            state.aliveRipples() <= WaveformEffectsState.RIPPLE_CAPACITY,
        )
    }

    @Test
    fun `特效演完之后不再要求重绘`() {
        val state = WaveformEffectsState()
        beat(state, showcase)
        var frames = 0
        while (state.update(0.05f, dt, active = true, effects = showcase)) {
            frames++
            assertTrue("特效寿命必须有界（否则就是永远重绘）", frames < 200)
        }
        assertEquals(0, state.aliveRipples())
        assertEquals(0, state.aliveParticles())
    }

    @Test
    fun `暂停后不再产生新节拍，且残余特效自然演完`() {
        val state = WaveformEffectsState()
        beat(state, showcase)
        assertEquals(1, state.aliveRipples())
        // 暂停后一直送强信号：不得再触发；已存在的涟漪/粒子照常演完（而不是冻在半空）
        var maxAlive = 0
        var frames = 0
        while (state.update(0.9f, dt, active = false, effects = showcase)) {
            frames++
            assertTrue("残余特效必须有界", frames < 200)
            if (state.aliveRipples() > maxAlive) maxAlive = state.aliveRipples()
        }
        assertTrue("暂停期间不得新增涟漪", maxAlive <= 1)
        assertEquals(0, state.aliveRipples())
        assertEquals(0, state.aliveParticles())
    }

    /** 暂停时基线要归零：否则暂停期间基线会跟着"静音"衰减，起播第一个瞬态失去参照。 */
    @Test
    fun `暂停会重置基线`() {
        val state = WaveformEffectsState()
        repeat(20) { state.update(0.5f, dt, active = true, effects = showcase) }
        assertTrue(state.baselineValue() > 0.1f)
        state.update(0.5f, dt, active = false, effects = showcase)
        assertEquals(0f, state.baselineValue(), 0f)
    }

    /**
     * 基线归零之后，起播第一根柱**安静**时不触发（靠最低响度门槛挡住）——
     * 这是"暂停/切歌回来不要炸一个涟漪"的那条边界。
     */
    @Test
    fun `基线归零后安静的第一根柱不触发`() {
        val state = WaveformEffectsState()
        repeat(20) { state.update(0.5f, dt, active = true, effects = showcase) }
        repeat(20) { state.update(0.5f, dt, active = false, effects = showcase) }
        state.update(0.05f, dt, active = true, effects = showcase)
        assertEquals(0, state.aliveRipples())
    }

    /**
     * 反过来：基线归零后起播第一根柱**很响**时会触发一次 —— 这是**有意**的行为，
     * 不是误判（从静音切到强响度，在只听 RMS 的前提下就是一次 onset）。
     * 把它写进单测是为了避免以后有人把它当 bug"修"掉、反而让起播的第一个鼓点丢反馈。
     */
    @Test
    fun `基线归零后很强的第一根柱会触发一次`() {
        val state = WaveformEffectsState()
        repeat(20) { state.update(0.5f, dt, active = true, effects = showcase) }
        repeat(20) { state.update(0.5f, dt, active = false, effects = showcase) }
        state.update(0.8f, dt, active = true, effects = showcase)
        assertEquals(1, state.aliveRipples())
    }

    @Test
    fun `关掉细分开关立刻清空对应状态`() {
        val state = WaveformEffectsState()
        beat(state, showcase)
        assertTrue(state.aliveRipples() > 0)
        assertTrue(state.aliveParticles() > 0)
        // 只剩粒子
        state.update(0.05f, dt, active = true, effects = particlesOnly)
        assertEquals(0, state.aliveRipples())
        assertTrue(state.aliveParticles() > 0)
        // 只剩冲击波
        state.update(0.05f, dt, active = true, effects = shockwaveOnly)
        assertEquals(0, state.aliveParticles())
    }

    @Test
    fun `关掉冲击波时不会产生涟漪`() {
        val state = WaveformEffectsState()
        repeat(10) { beat(state, particlesOnly) }
        assertEquals(0, state.aliveRipples())
        assertTrue(state.aliveParticles() > 0)
    }

    @Test
    fun `clear 清空所有状态`() {
        val state = WaveformEffectsState()
        beat(state, showcase)
        state.clear()
        assertEquals(0, state.aliveRipples())
        assertEquals(0, state.aliveParticles())
        assertEquals(0f, state.baselineValue(), 0f)
        assertFalse(state.update(0.05f, dt, active = true, effects = showcase))
    }

    @Test
    fun `非法输入不产生状态也不抛异常`() {
        val state = WaveformEffectsState()
        repeat(3) { state.update(Float.NaN, dt, active = true, effects = showcase) }
        repeat(3) { state.update(Float.POSITIVE_INFINITY, dt, active = true, effects = showcase) }
        repeat(3) { state.update(-5f, dt, active = true, effects = showcase) }
        assertEquals(0, state.aliveRipples())
        assertEquals(0, state.aliveParticles())
        assertTrue(state.baselineValue().isFinite())
    }

    /** 固定种子：同一段输入得到同一套粒子轨迹（排查"是不是随机看着乱"时要有可复现性）。 */
    @Test
    fun `粒子轨迹可复现`() {
        val a = WaveformEffectsState()
        val b = WaveformEffectsState()
        beat(a, particlesOnly)
        beat(b, particlesOnly)
        for (i in 0 until WaveformEffectsState.PARTICLE_CAPACITY) {
            assertEquals(a.particleXAt(i), b.particleXAt(i), 0f)
            assertEquals(a.particleYAt(i), b.particleYAt(i), 0f)
        }
    }

    /** 粒子必须在有限的寿命内真的动过（否则就是"生成了但不更新"的死状态）。 */
    @Test
    fun `粒子会随时间移动并衰减`() {
        val state = WaveformEffectsState()
        beat(state, particlesOnly)
        val x0 = state.particleXAt(0)
        val y0 = state.particleYAt(0)
        val life0 = state.particleLifeAt(0)
        state.update(0.05f, 50f, active = true, effects = particlesOnly)
        assertTrue("寿命必须衰减", state.particleLifeAt(0) < life0)
        assertTrue("位置必须变化", state.particleXAt(0) != x0 || state.particleYAt(0) != y0)
    }

    @Test
    fun `涟漪进度只在 0 到 1 之间`() {
        val state = WaveformEffectsState()
        beat(state, shockwaveOnly)
        repeat(30) {
            state.update(0.05f, 16f, active = true, effects = shockwaveOnly)
            for (i in 0 until state.rippleCapacity) {
                val p = state.rippleProgressAt(i)
                assertTrue("进展要么是空的（负数）要么在 [0,1)：$p", p < 0f || (p >= 0f && p < 1f))
            }
        }
    }
}
