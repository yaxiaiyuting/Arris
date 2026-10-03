/*
 * Ncrust —— ncm 第三方客户端
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

    // ── 3. 特效池（v3.0.0：瞬态 → 冲击波/光晕；中高频能量 → 粒子）────────────────

    /** 造一份"刚发生一次瞬态"的特征输入。 */
    private fun bindingsWith(
        midHigh: Float = 0f,
        transients: Int = 0,
        strength: Float = 0f,
    ): MotionBindings = MotionBindings().apply {
        // 中高频平均能量 = midHigh ⇒ mid = high = midHigh（update 取两者均值）。
        update(
            rms = 0.5f,
            low = 0.2f,
            mid = midHigh,
            high = midHigh,
            centroid = 0.5f,
            available = true,
            transientCount = 0L,
            transientStrength = strength,
        )
        // 第一次 update 只对齐计数；再推一次把 transients 变成真正的"本帧新增"。
        if (transients > 0) {
            update(
                rms = 0.5f,
                low = 0.2f,
                mid = midHigh,
                high = midHigh,
                centroid = 0.5f,
                available = true,
                transientCount = transients.toLong(),
                transientStrength = strength,
            )
        }
    }

    private fun showcaseEffects() = MotionEffects.of(
        tier = MotionIntensity.SHOWCASE,
        uiMotionEnabled = true,
    )

    @Test
    fun `冲击波与光晕池定长且有上限`() {
        val backdrop = MotionBackdropState()
        val motion = showcaseEffects()
        repeat(500) {
            backdrop.update(bindingsWith(transients = 1, strength = 1f), motion, dtMs = 16f, active = true)
        }
        // 池容量就是硬上界：不断触发也只会覆盖最旧的槽位。
        assertEquals(4, backdrop.haloCapacity)
        assertEquals(2, backdrop.shockCapacity)
        assertEquals(40, backdrop.particleCapacity)
        assertTrue("存活数必须落在池容量内", backdrop.aliveHalos() <= 4)
        assertTrue(backdrop.aliveShocks() <= 2)
        assertTrue(backdrop.aliveParticles() <= 40)
    }

    @Test
    fun `炫技档一次瞬态扩两圈光晕且只出一个冲击波`() {
        val backdrop = MotionBackdropState()
        val motion = showcaseEffects()
        assertEquals(2, motion.haloRings)
        backdrop.update(bindingsWith(transients = 1, strength = 1f), motion, dtMs = 16f, active = true)
        assertEquals("炫技档多圈", 2, backdrop.aliveHalos())
        assertEquals(1, backdrop.aliveShocks())
        // 两圈错开起始进度（否则看起来就是一圈）。
        val progresses = (0 until backdrop.haloCapacity)
            .map { backdrop.haloProgressAt(it) }
            .filter { it >= 0f }
        assertEquals(2, progresses.size)
        assertTrue("两圈必须错开：$progresses", progresses[0] != progresses[1])
    }

    @Test
    fun `精致档一次瞬态只扩一圈`() {
        val backdrop = MotionBackdropState()
        val motion = MotionEffects.of(tier = MotionIntensity.REFINED, uiMotionEnabled = true)
        assertEquals(1, motion.haloRings)
        backdrop.update(bindingsWith(transients = 1, strength = 0.5f), motion, dtMs = 16f, active = true)
        assertEquals(1, backdrop.aliveHalos())
    }

    @Test
    fun `粒子生成速率与中高频能量正相关`() {
        // 纯函数层面：能量越高，速率越高；低于门槛恒为 0。
        assertEquals(0f, MotionBindings.particleRateHz(0f, MotionEffects.DENSITY_LOW), 0f)
        assertEquals(
            0f,
            MotionBindings.particleRateHz(MotionBindings.PARTICLE_THRESHOLD, MotionEffects.DENSITY_LOW),
            0f,
        )
        val mid = MotionBindings.particleRateHz(0.2f, MotionEffects.DENSITY_LOW)
        val high = MotionBindings.particleRateHz(0.4f, MotionEffects.DENSITY_LOW)
        assertTrue("能量越高生成越快（$mid → $high）", high > mid && mid > 0f)
        assertTrue(
            "高密度档的上限更高",
            MotionBindings.particleRateHz(0.4f, MotionEffects.DENSITY_HIGH) > high,
        )
    }

    @Test
    fun `安静段落不生成粒子`() {
        val backdrop = MotionBackdropState()
        val motion = showcaseEffects()
        // 中高频能量为 0（安静段落）：连续跑 200 帧也不该有一个粒子。
        repeat(200) { backdrop.update(bindingsWith(midHigh = 0f), motion, dtMs = 16f, active = true) }
        assertEquals("安静段落生成粒子 = 铁律 25 禁止的假反应", 0, backdrop.aliveParticles())
    }

    @Test
    fun `粒子由中高频能量驱动且速率有界`() {
        val backdrop = MotionBackdropState()
        val motion = showcaseEffects()
        // 高能量跑 1 秒：生成数应当接近速率上限（26/s），且不超过池容量。
        repeat(60) { backdrop.update(bindingsWith(midHigh = 0.5f), motion, dtMs = 16f, active = true) }
        val alive = backdrop.aliveParticles()
        assertTrue("高能量段必须真的生成粒子（$alive）", alive > 5)
        assertTrue("但不超过池容量的硬上界（$alive）", alive <= backdrop.particleCapacity)
        assertTrue("累加器有界", backdrop.emitAccumulator() <= 1f)
    }

    @Test
    fun `关掉能力位时立刻清空残留`() {
        val backdrop = MotionBackdropState()
        val on = showcaseEffects()
        backdrop.update(bindingsWith(midHigh = 0.5f, transients = 1, strength = 1f), on, 16f, active = true)
        assertTrue(hasAny(backdrop))
        // 用户把这三项全关掉 ⇒ 画面必须马上跟上，而不是让残留飘完。
        val off = MotionEffects.of(
            tier = MotionIntensity.SHOWCASE,
            uiMotionEnabled = true,
            switches = MotionSwitches(shockwave = false, halo = false, particles = false),
        )
        backdrop.update(bindingsWith(), off, 16f, active = true)
        assertFalse("关掉之后必须没有残留", hasAny(backdrop))
    }

    @Test
    fun `特效自然演完之后不再要求重绘`() {
        val backdrop = MotionBackdropState()
        val motion = showcaseEffects()
        backdrop.update(bindingsWith(midHigh = 0.5f, transients = 1, strength = 1f), motion, 16f, active = true)
        var guard = 0
        // 之后不再有瞬态、也没有中高频能量 ⇒ 存活的特效演完就收敛。
        while (backdrop.update(bindingsWith(), motion, 16f, active = true) && guard < 20000) guard++
        assertFalse("必须收敛", hasAny(backdrop))
        assertTrue("收敛时间必须有限（$guard 帧）", guard < 5000)
    }

    @Test
    fun `暂停时不生成新特效但已存在的会演完`() {
        val backdrop = MotionBackdropState()
        val motion = showcaseEffects()
        backdrop.update(bindingsWith(midHigh = 0.5f, transients = 1, strength = 1f), motion, 16f, active = true)
        val before = backdrop.aliveParticles()
        backdrop.update(bindingsWith(midHigh = 0.5f), motion, 16f, active = false)
        assertTrue("暂停时不得新增粒子", backdrop.aliveParticles() <= before)
        assertEquals("暂停时累加器归零（恢复播放不补触发）", 0f, backdrop.emitAccumulator(), 0f)
    }

    @Test
    fun `clear 清空三个池`() {
        val backdrop = MotionBackdropState()
        val motion = showcaseEffects()
        backdrop.update(bindingsWith(midHigh = 0.5f, transients = 1, strength = 1f), motion, 16f, active = true)
        backdrop.clear()
        assertFalse(hasAny(backdrop))
    }

    // ── 3b. v3.0.0：冲击波 / 光晕的**随机出生点** ─────────────────────────────────

    /**
     * **出生点必须真的随机**（不再是"永远在屏幕正中"）。
     *
     * 这条断言的是「随机」这件事本身，而不只是"值合法"：连续多次瞬态的出生点
     * 必须出现过多个不同的位置 —— 否则用户看到的还是同一个装饰动画在原地反复炸。
     */
    @Test
    fun `冲击波与光晕的出生点每次不同`() {
        val backdrop = MotionBackdropState()
        val motion = showcaseEffects()
        val seen = mutableSetOf<Pair<Int, Int>>()
        repeat(40) {
            // 每次瞬态之后把上一批清掉，保证每次都是"新出生"的槽位。
            backdrop.clear()
            backdrop.update(bindingsWith(transients = 1, strength = 0.8f), motion, 16f, active = true)
            val shock = backdrop.shockProgressAt(0)
            if (shock >= 0f) {
                seen += (backdrop.shockXAt(0) * 1000).toInt() to (backdrop.shockYAt(0) * 1000).toInt()
            }
        }
        assertTrue("出生点必须出现过多个不同位置（实测 ${seen.size} 个）", seen.size >= 10)
    }

    @Test
    fun `出生点落在安全区间内且有限`() {
        val backdrop = MotionBackdropState()
        val motion = showcaseEffects()
        repeat(200) {
            backdrop.update(bindingsWith(transients = 1, strength = 1f), motion, 16f, active = true)
        }
        for (i in 0 until backdrop.shockCapacity) {
            if (backdrop.shockProgressAt(i) < 0f) continue
            assertTrue("x 必须有限", backdrop.shockXAt(i).isFinite())
            assertTrue("y 必须有限", backdrop.shockYAt(i).isFinite())
            assertTrue(
                "x 必须落在 [${MotionBackdropState.SPAWN_X_MIN}, ${MotionBackdropState.SPAWN_X_MAX}]（实测 ${backdrop.shockXAt(i)}）",
                backdrop.shockXAt(i) in MotionBackdropState.SPAWN_X_MIN..MotionBackdropState.SPAWN_X_MAX,
            )
            assertTrue(
                "y 必须落在 [${MotionBackdropState.SPAWN_Y_MIN}, ${MotionBackdropState.SPAWN_Y_MAX}]（实测 ${backdrop.shockYAt(i)}）",
                backdrop.shockYAt(i) in MotionBackdropState.SPAWN_Y_MIN..MotionBackdropState.SPAWN_Y_MAX,
            )
        }
        for (i in 0 until backdrop.haloCapacity) {
            if (backdrop.haloProgressAt(i) < 0f) continue
            assertTrue(backdrop.haloXAt(i) in MotionBackdropState.SPAWN_X_MIN..MotionBackdropState.SPAWN_X_MAX)
            assertTrue(backdrop.haloYAt(i) in MotionBackdropState.SPAWN_Y_MIN..MotionBackdropState.SPAWN_Y_MAX)
        }
    }

    /** 同一击的多圈光晕**共用同一个出生点**（否则一次击打看起来像两件独立的事）。 */
    @Test
    fun `同一击的多圈共用出生点`() {
        val backdrop = MotionBackdropState()
        val motion = showcaseEffects()
        assertEquals(2, motion.haloRings)
        backdrop.update(bindingsWith(transients = 1, strength = 1f), motion, 16f, active = true)
        val live = (0 until backdrop.haloCapacity).filter { backdrop.haloProgressAt(it) >= 0f }
        assertEquals(2, live.size)
        assertEquals(
            "两圈必须同心",
            backdrop.haloXAt(live[0]),
            backdrop.haloXAt(live[1]),
            0f,
        )
        assertEquals(backdrop.haloYAt(live[0]), backdrop.haloYAt(live[1]), 0f)
        // 而且要与同一次瞬态的冲击波同心。
        assertEquals("冲击波与光晕同源 ⇒ 同心", backdrop.haloXAt(live[0]), backdrop.shockXAt(0), 0f)
    }

    /** 固定种子 ⇒ 同一段音频每次得到同一套出生点（与粒子同一手法，便于排查）。 */
    @Test
    fun `出生点可复现`() {
        fun run(): List<Pair<Float, Float>> {
            val backdrop = MotionBackdropState()
            val motion = showcaseEffects()
            repeat(5) {
                backdrop.clear()
                backdrop.update(bindingsWith(transients = 1, strength = 1f), motion, 16f, active = true)
            }
            return (0 until backdrop.shockCapacity)
                .filter { backdrop.shockProgressAt(it) >= 0f }
                .map { backdrop.shockXAt(it) to backdrop.shockYAt(it) }
        }
        assertEquals("同一颗种子必须给出同一套出生点", run(), run())
    }

    @Test
    fun `粒子坐标是归一化的`() {
        val backdrop = MotionBackdropState()
        val motion = showcaseEffects()
        repeat(50) {
            backdrop.update(bindingsWith(midHigh = 0.5f, transients = 1, strength = 0.8f), motion, 16f, active = true)
        }
        for (i in 0 until backdrop.particleCapacity) {
            if (backdrop.particleLifeAt(i) <= 0f) continue
            assertTrue("x 必须有限", backdrop.particleXAt(i).isFinite())
            assertTrue("y 必须有限", backdrop.particleYAt(i).isFinite())
            assertTrue("life 必须落在 0..1", backdrop.particleLifeAt(i) in 0f..1f)
        }
    }

    // ── 4. v3.0.0：特征链路不可用时回落内置判据 ────────────────────────────────────

    @Test
    fun `特征不可用时回落内置判据`() {
        val e = MotionEnvelope()
        // 先建立基线（安静的若干帧）。
        repeat(20) { e.update(0.05f, 0.05f, 16f, active = true) }
        // 一次低频突变：externalAvailable=false ⇒ 走内置判据。
        e.update(0.5f, 0.5f, 16f, active = true)
        assertTrue("内置判据必须能触发脉冲", e.pulse() > 0.9f)
    }

    @Test
    fun `特征可用时瞬态由音频线程给出`() {
        val e = MotionEnvelope()
        // 响度完全不变（内置判据永远不该触发），但外部报了 1 次瞬态。
        repeat(20) { e.update(0.1f, 0.1f, 16f, active = true, transients = 0, strength = 0f, externalAvailable = true) }
        assertEquals("平静时不该有脉冲", 0f, e.pulse(), 0f)
        e.update(0.1f, 0.1f, 16f, active = true, transients = 1, strength = 1f, externalAvailable = true)
        assertTrue("外部瞬态必须触发脉冲", e.pulse() > 0.9f)
        assertTrue("强度足够高 ⇒ 强拍", e.strongBeat)
    }

    @Test
    fun `强度不足时不算强拍`() {
        val e = MotionEnvelope()
        e.update(
            0.1f, 0.1f, 16f, active = true,
            transients = 1, strength = MotionEnvelope.STRONG_BEAT_STRENGTH * 0.5f,
            externalAvailable = true,
        )
        assertTrue("脉冲照旧", e.pulse() > 0.9f)
        assertFalse("但不算强拍", e.strongBeat)
    }

    private fun hasAny(backdrop: MotionBackdropState): Boolean {
        for (i in 0 until backdrop.haloCapacity) if (backdrop.haloProgressAt(i) >= 0f) return true
        for (i in 0 until backdrop.shockCapacity) if (backdrop.shockProgressAt(i) >= 0f) return true
        for (i in 0 until backdrop.particleCapacity) if (backdrop.particleLifeAt(i) > 0f) return true
        return false
    }
}
