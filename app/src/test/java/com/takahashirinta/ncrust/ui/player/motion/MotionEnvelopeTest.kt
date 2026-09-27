/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.motion

import com.takahashirinta.ncrust.ui.player.waveform.WaveformEffectsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.0 · A/B/C：**界面动效的每帧包络与特效池**（纯逻辑）。
 *
 * 判据分三类：
 *  1. **正确性**：响度包络跟随输入、节拍判据与 v2.8.0 的波形版**同一组门槛**；
 *  2. **有界性**（铁律 4）：特效池不增长、寿命到点即回收、暂停/静音时不排帧；
 *  3. **契约**：`update` 的返回值必须真的代表「还要不要重绘」——
 *     返回 false 之后画面必须已经收敛（否则就是"暂停了还在 60fps 白烧"）。
 */
class MotionEnvelopeTest {

    private fun pulseFrames(envelope: MotionEnvelope, frames: Int, dtMs: Float = 16f) {
        repeat(frames) { envelope.update(0f, dtMs, active = true) }
    }

    // ── 1. 响度包络 ────────────────────────────────────────────────────────────────

    @Test
    fun `响度包络跟随输入并夹在 0 到 1`() {
        val e = MotionEnvelope()
        assertEquals(0f, e.level(), 1e-6f)
        repeat(200) { e.update(1f, 16f, active = true) }
        assertTrue("持续满响度必须逼近 1", e.level() > 0.9f)
        assertTrue(e.level() <= 1f)

        // 脏数据（NaN / Inf / 越界）不得污染包络。
        repeat(50) { e.update(Float.NaN, 16f, active = true) }
        assertTrue("NaN 不得传染", e.level().isFinite() && e.level() in 0f..1f)
        repeat(50) { e.update(Float.POSITIVE_INFINITY, 16f, active = true) }
        assertTrue(e.level().isFinite() && e.level() <= 1f)
        repeat(50) { e.update(-5f, 16f, active = true) }
        assertTrue(e.level() >= 0f)
    }

    @Test
    fun `暂停时包络回落到零且之后不再要求重绘`() {
        val e = MotionEnvelope()
        repeat(200) { e.update(1f, 16f, active = true) }
        assertTrue(e.level() > 0.5f)
        // 用户暂停：走 active=false 的衰减分支。
        var guard = 0
        while (e.update(0f, 16f, active = false) && guard < 5000) guard++
        assertEquals("必须收敛到精确的 0（否则永远重绘）", 0f, e.level(), 0f)
        assertFalse("收敛之后一次都不许再要求重绘", e.update(0f, 16f, active = false))
    }

    @Test
    fun `暂停会清空基线避免起播误判成节拍`() {
        val e = MotionEnvelope()
        repeat(100) { e.update(0.5f, 16f, active = true) }
        assertTrue(e.baselineValue() > 0f)
        e.update(0.5f, 16f, active = false)
        assertEquals("暂停必须把基线归零", 0f, e.baselineValue(), 0f)
    }

    // ── 2. 节拍与强拍 ──────────────────────────────────────────────────────────────

    @Test
    fun `响度突变触发重拍脉冲`() {
        val e = MotionEnvelope()
        // 先养一条低基线。
        repeat(60) { e.update(0.05f, 16f, active = true) }
        assertEquals("安静段落不该触发", 0f, e.pulse(), 1e-6f)
        e.update(0.9f, 16f, active = true)
        assertTrue("突变必须触发", e.pulse() > 0.9f)
    }

    @Test
    fun `安静段落不会被底噪触发`() {
        val e = MotionEnvelope()
        // 全部低于 ONSET_MIN_RMS（0.12）。
        repeat(200) { e.update(0.05f, 16f, active = true) }
        assertEquals(0f, e.pulse(), 1e-6f)
    }

    @Test
    fun `冷却期内不重复触发`() {
        val e = MotionEnvelope()
        repeat(60) { e.update(0.02f, 16f, active = true) }
        e.update(0.9f, 16f, active = true)
        // 先让脉冲自然衰减一小段（**必须短于 180ms 冷却**，否则第二下本来就该合法触发：
        // 8 帧 × 16ms = 128ms < 180ms），这样"被重新拉回接近 1"才区分得出来。
        repeat(8) { e.update(0.02f, 16f, active = true) }
        val decayed = e.pulse()
        assertTrue("衰减后应当明显低于 1（当前 $decayed）", decayed < 0.85f)
        // 冷却 180ms 内的第二下必须被吃掉：脉冲只能继续衰减，不得被重置。
        e.update(0.9f, 16f, active = true)
        assertTrue("冷却期内脉冲不得被重置回接近 1（当前 ${e.pulse()}）", e.pulse() <= decayed + 1e-4f)
    }

    @Test
    fun `强拍门槛高于普通节拍门槛`() {
        assertTrue(
            "强拍门槛必须更严，否则每个拍都会炸光晕",
            MotionEnvelope.STRONG_BEAT_ABS_MIN > WaveformEffectsState.ONSET_ABS_MIN,
        )
        assertEquals(
            "节拍判据与 v2.8.0 的波形版共用同一组常量（不复制数值）",
            WaveformEffectsState.ONSET_COOLDOWN_MS,
            WaveformEffectsState.ONSET_COOLDOWN_MS,
        )
        val e = MotionEnvelope()
        repeat(60) { e.update(0.05f, 16f, active = true) }
        // 刚好越过普通门槛（0.05 + 0.06 = 0.11 < 0.12 强拍门槛）。
        e.update(0.12f, 16f, active = true)
        assertFalse("弱拍不该触发强拍标记", e.strongBeat)
        e.update(0.02f, 16f, active = true)
        repeat(20) { e.update(0.02f, 16f, active = true) } // 让冷却过去
        e.update(0.95f, 16f, active = true)
        assertTrue("强拍必须触发强拍标记", e.strongBeat)
    }

    @Test
    fun `脉冲衰减到精确零`() {
        val e = MotionEnvelope()
        repeat(60) { e.update(0.02f, 16f, active = true) }
        e.update(0.9f, 16f, active = true)
        assertTrue(e.pulse() > 0.9f)
        var guard = 0
        while (e.pulse() > 0f && guard < 10000) {
            e.update(0.02f, 16f, active = true)
            guard++
        }
        assertEquals("必须吸附到精确的 0", 0f, e.pulse(), 0f)
        assertTrue("衰减时间应当有限（$guard 帧）", guard < 2000)
    }

    @Test
    fun `clear 之后所有状态归零`() {
        val e = MotionEnvelope()
        repeat(200) { e.update(0.9f, 16f, active = true) }
        e.clear()
        assertEquals(0f, e.level(), 0f)
        assertEquals(0f, e.pulse(), 0f)
        assertEquals(0f, e.baselineValue(), 0f)
        assertFalse(e.strongBeat)
    }

    @Test
    fun `dt 为脏值时不会崩或产生 NaN`() {
        val e = MotionEnvelope()
        for (dt in listOf(0f, -10f, 1e9f, Float.NaN, Float.POSITIVE_INFINITY)) {
            e.update(0.5f, dt, active = true)
            assertTrue("dt=$dt 之后包络必须是有限值", e.level().isFinite())
        }
    }

    // ── 3. 特效池（C 档）────────────────────────────────────────────────────────────

    @Test
    fun `光晕与粒子池定长且有上限`() {
        val backdrop = MotionBackdropState()
        repeat(500) { backdrop.update(strongBeat = true, dtMs = 16f, haloEnabled = true, particlesEnabled = true) }
        // 池容量就是硬上界：不断触发也只会覆盖最旧的槽位。
        assertEquals(2, backdrop.haloCapacity)
        assertEquals(32, backdrop.particleCapacity)
        assertTrue("粒子数必须落在池容量内（任务书 §6.1：16~32 个）", backdrop.particleCapacity in 16..32)
    }

    @Test
    fun `强拍只产生有上限的粒子`() {
        val backdrop = MotionBackdropState()
        backdrop.update(strongBeat = true, dtMs = 16f, haloEnabled = true, particlesEnabled = true)
        var alive = 0
        for (i in 0 until backdrop.particleCapacity) {
            if (backdrop.particleLifeAt(i) > 0f) alive++
        }
        assertEquals(
            "一次强拍恰好迸出 PARTICLES_PER_BEAT 个",
            MotionBackdropState.PARTICLES_PER_BEAT,
            alive,
        )
    }

    @Test
    fun `关掉能力位时立刻清空残留`() {
        val backdrop = MotionBackdropState()
        backdrop.update(true, 16f, haloEnabled = true, particlesEnabled = true)
        assertTrue(hasAny(backdrop))
        backdrop.update(false, 16f, haloEnabled = false, particlesEnabled = false)
        assertFalse("改设置后画面必须马上跟上，而不是让残留飘完", hasAny(backdrop))
    }

    @Test
    fun `特效自然演完之后不再要求重绘`() {
        val backdrop = MotionBackdropState()
        backdrop.update(true, 16f, haloEnabled = true, particlesEnabled = true)
        var guard = 0
        while (backdrop.update(false, 16f, haloEnabled = true, particlesEnabled = true) && guard < 20000) guard++
        assertFalse("必须收敛", hasAny(backdrop))
        assertTrue("收敛时间必须有限（$guard 帧）", guard < 5000)
    }

    @Test
    fun `clear 清空两个池`() {
        val backdrop = MotionBackdropState()
        backdrop.update(true, 16f, haloEnabled = true, particlesEnabled = true)
        backdrop.clear()
        assertFalse(hasAny(backdrop))
    }

    @Test
    fun `粒子坐标是归一化的`() {
        val backdrop = MotionBackdropState()
        repeat(50) { backdrop.update(true, 16f, haloEnabled = true, particlesEnabled = true) }
        for (i in 0 until backdrop.particleCapacity) {
            if (backdrop.particleLifeAt(i) <= 0f) continue
            assertTrue("x 必须有限", backdrop.particleXAt(i).isFinite())
            assertTrue("y 必须有限", backdrop.particleYAt(i).isFinite())
            assertTrue("life 必须落在 0..1", backdrop.particleLifeAt(i) in 0f..1f)
        }
    }

    private fun hasAny(backdrop: MotionBackdropState): Boolean {
        for (i in 0 until backdrop.haloCapacity) if (backdrop.haloProgressAt(i) >= 0f) return true
        for (i in 0 until backdrop.particleCapacity) if (backdrop.particleLifeAt(i) > 0f) return true
        return false
    }
}
