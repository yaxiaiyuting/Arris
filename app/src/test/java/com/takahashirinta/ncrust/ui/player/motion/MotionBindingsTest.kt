/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.0.0：**音频特征 → 动效参数的绑定层**（纯逻辑，JVM 直测）。
 *
 * 这一层是铁律 25「动效绑定必须基于真实音频特征，不得用伪随机或固定周期伪装」的
 * 可执行表达。每条用例都在回答同一个问题：**这个视觉量到底跟的是哪个音频特征？**
 *
 * | 视觉量 | 音频特征 | 本文对应的用例 |
 * |---|---|---|
 * | 冲击波 / 光晕的触发 | 瞬态（音频线程判定） | [瞬态计数按差值结算] / [特征不可用时消费方必须回落到内置判据] |
 * | 冲击波 / 光晕的幅度 | 瞬态强度 | [瞬态强度映射到幅度倍率且单调] |
 * | 光晕的圈数 | 瞬态（每次一圈/两圈） | [圈数与档次绑定] |
 * | 粒子的生成速率 | 中高频能量 | [粒子速率与中高频能量正相关且低于门槛恒为零] |
 * | 波形逐柱着色 | 那一刻的频带占比 | [明亮度占比拉伸后仍然单调] |
 * | 背景呼吸 | 全带 RMS | [响度包络跟的是全带 RMS] |
 */
class MotionBindingsTest {

    // ── 1. 瞬态计数：累计值 → 本帧新增 ──────────────────────────────────────────────

    @Test
    fun `瞬态计数按差值结算`() {
        val b = MotionBindings()
        // 第一次接上外部信号：只对齐，不补触发（否则起播那一帧会把进程启动以来的全部计数炸出来）。
        b.update(0.5f, 0.2f, 0.2f, 0.2f, 0.5f, available = true, transientCount = 7L, transientStrength = 0f)
        assertEquals("首次只对齐", 0, b.transients)
        // 之后按差值结算。
        b.update(0.5f, 0.2f, 0.2f, 0.2f, 0.5f, available = true, transientCount = 7L, transientStrength = 0f)
        assertEquals(0, b.transients)
        b.update(0.5f, 0.2f, 0.2f, 0.2f, 0.5f, available = true, transientCount = 8L, transientStrength = 0.4f)
        assertEquals(1, b.transients)
        assertEquals(0.4f, b.strength, 1e-6f)
        b.update(0.5f, 0.2f, 0.2f, 0.2f, 0.5f, available = true, transientCount = 10L, transientStrength = 0.9f)
        assertEquals("一帧里发生多次也要知道", 2, b.transients)
    }

    @Test
    fun `一帧的瞬态次数有界`() {
        val b = MotionBindings()
        b.update(0.5f, 0.2f, 0.2f, 0.2f, 0.5f, available = true, transientCount = 0L, transientStrength = 0f)
        // 一次长卡顿攒下 500 次：必须被夹到上界（否则会一次性炸出几百个特效）。
        b.update(0.5f, 0.2f, 0.2f, 0.2f, 0.5f, available = true, transientCount = 500L, transientStrength = 1f)
        assertEquals(MotionBindings.MAX_TRANSIENTS_PER_FRAME, b.transients)
        // 计数**不回退**：把上界之外的那些"欠账"丢掉，而不是攒着下一次补。
        b.update(0.5f, 0.2f, 0.2f, 0.2f, 0.5f, available = true, transientCount = 501L, transientStrength = 1f)
        assertEquals(1, b.transients)
    }

    @Test
    fun `特征不可用时消费方必须回落到内置判据`() {
        val b = MotionBindings()
        b.update(0.5f, 0.2f, 0.2f, 0.2f, 0.5f, available = true, transientCount = 3L, transientStrength = 0.5f)
        b.update(0.5f, 0.2f, 0.2f, 0.2f, 0.5f, available = true, transientCount = 4L, transientStrength = 0.5f)
        assertEquals(1, b.transients)

        // 链路掉线：不再产出瞬态，并且把计数游标复位（重新接上时不会补一笔大的）。
        b.update(0f, 0f, 0f, 0f, 0f, available = false, transientCount = 0L, transientStrength = 0f)
        assertFalse(b.available)
        assertEquals(0, b.transients)
        assertFalse("中高频必须如实变成 0，而不是编一个数", b.midHigh > 0f)

        b.update(0.5f, 0.2f, 0.2f, 0.2f, 0.5f, available = true, transientCount = 99L, transientStrength = 1f)
        assertEquals("重新接上时只对齐", 0, b.transients)
    }

    // ── 2. 派生的频带量 ────────────────────────────────────────────────────────────

    @Test
    fun `中高频与明亮度由三个频带算出`() {
        val b = MotionBindings()
        b.update(0.5f, low = 0.1f, mid = 0.3f, high = 0.5f, centroid = 0.7f, available = true, transientCount = 0L, transientStrength = 0f)
        assertEquals("中高频 = 两者均值", 0.4f, b.midHigh, 1e-6f)
        // 明亮度 = 高频 /（低+中+高）= 0.5 / 0.9
        assertEquals(0.5f / 0.9f, b.brightness, 1e-5f)
        assertEquals(0.7f, b.centroid, 1e-6f)
    }

    @Test
    fun `三个频带全零时明亮度为零而不是 NaN`() {
        val b = MotionBindings()
        b.update(0f, 0f, 0f, 0f, 0f, available = true, transientCount = 0L, transientStrength = 0f)
        assertEquals(0f, b.brightness, 0f)
        assertEquals(0f, b.midHigh, 0f)
    }

    @Test
    fun `脏值一律被夹回 0 到 1`() {
        val b = MotionBindings()
        b.update(
            Float.NaN, Float.POSITIVE_INFINITY, -3f, 7f, Float.NaN,
            available = true, transientCount = 0L, transientStrength = Float.NaN,
        )
        for (v in listOf(b.rms, b.low, b.mid, b.high, b.centroid, b.midHigh, b.brightness, b.strength)) {
            assertTrue("必须是有限值（$v）", v.isFinite())
            assertTrue("必须落在 0..1（$v）", v in 0f..1f)
        }
    }

    @Test
    fun `reset 清空全部状态`() {
        val b = MotionBindings()
        b.update(0.5f, 0.2f, 0.3f, 0.4f, 0.5f, available = true, transientCount = 5L, transientStrength = 0.8f)
        b.reset()
        assertEquals(0f, b.rms, 0f)
        assertEquals(0f, b.midHigh, 0f)
        assertEquals(0, b.transients)
        assertFalse(b.available)
        assertEquals(MotionBindings.NO_COUNT, b.lastCount())
    }

    // ── 3. 粒子：中高频能量 → 生成速率 ─────────────────────────────────────────────

    @Test
    fun `粒子速率与中高频能量正相关且低于门槛恒为零`() {
        assertEquals("低于门槛必须恒为 0（安静段落不生成）", 0f, MotionBindings.particleRateHz(0f, MotionEffects.DENSITY_LOW), 0f)
        assertEquals(0f, MotionBindings.particleRateHz(0.01f, MotionEffects.DENSITY_LOW), 0f)
        assertEquals(
            "正好在门槛上也是 0（不是「很小的速率」）",
            0f,
            MotionBindings.particleRateHz(MotionBindings.PARTICLE_THRESHOLD, MotionEffects.DENSITY_LOW),
            0f,
        )
        val samples = listOf(0.1f, 0.2f, 0.3f, 0.4f)
            .map { MotionBindings.particleRateHz(it, MotionEffects.DENSITY_LOW) }
        for (i in 1 until samples.size) {
            assertTrue("必须单调不减（$samples）", samples[i] >= samples[i - 1])
        }
        assertTrue("必须真的有增长（$samples）", samples.last() > samples.first())
        assertEquals(
            "到上限之后不再加速",
            MotionBindings.PARTICLE_RATE_LOW,
            MotionBindings.particleRateHz(1f, MotionEffects.DENSITY_LOW),
            1e-4f,
        )
    }

    @Test
    fun `炫技档的粒子密度上限更高`() {
        val lowDensity = MotionBindings.particleRateHz(0.5f, MotionEffects.DENSITY_LOW)
        val highDensity = MotionBindings.particleRateHz(0.5f, MotionEffects.DENSITY_HIGH)
        assertTrue("炫技档必须更密（$lowDensity vs $highDensity）", highDensity > lowDensity)
        assertEquals(MotionBindings.PARTICLE_RATE_HIGH, highDensity, 1e-4f)
    }

    @Test
    fun `粒子速率的非法输入不产生 NaN`() {
        assertEquals(0f, MotionBindings.particleRateHz(Float.NaN, MotionEffects.DENSITY_LOW), 0f)
        assertEquals(
            "非有限值一律当成 0（与其它特征的脏值口径一致），绝不产生 NaN 速率",
            0f,
            MotionBindings.particleRateHz(Float.POSITIVE_INFINITY, MotionEffects.DENSITY_LOW),
            0f,
        )
    }

    // ── 4. 冲击波 / 光晕：瞬态强度 → 幅度 ──────────────────────────────────────────

    @Test
    fun `瞬态强度映射到幅度倍率且单调`() {
        val weakest = MotionBindings.strengthScale(0f)
        val mid = MotionBindings.strengthScale(0.5f)
        val strongest = MotionBindings.strengthScale(1f)
        assertEquals("最弱击打仍然可见（不是 0）", MotionBindings.MIN_SCALE, weakest, 1e-6f)
        assertEquals("满强度 = 1.0", 1f, strongest, 1e-6f)
        assertTrue("必须单调（$weakest / $mid / $strongest）", weakest < mid && mid < strongest)
        // 脏值不得越界。
        assertEquals(MotionBindings.MIN_SCALE, MotionBindings.strengthScale(Float.NaN), 1e-6f)
        assertEquals(MotionBindings.MIN_SCALE, MotionBindings.strengthScale(-5f), 1e-6f)
        assertEquals(1f, MotionBindings.strengthScale(99f), 1e-6f)
    }

    @Test
    fun `圈数与档次绑定`() {
        assertEquals("第一圈没有起始偏移", 0f, MotionBindings.ringStartOffset(0), 0f)
        val second = MotionBindings.ringStartOffset(1)
        assertTrue("第二圈必须错开（否则看起来就是一圈）", second > 0f)
        assertTrue("错开量必须落在进度区间内", second < 1f)
        assertTrue("第三圈更靠前", MotionBindings.ringStartOffset(2) > second)
    }

    // ── 5. 逐柱着色：明亮度占比 → 混合系数 ─────────────────────────────────────────

    @Test
    fun `明亮度占比拉伸后仍然单调`() {
        val values = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { MotionBindings.tintMix(it) }
        for (i in 1 until values.size) {
            assertTrue("必须单调不减（$values）", values[i] >= values[i - 1])
        }
        assertEquals("两端仍然饱和在 0 与 1", 0f, values.first(), 0f)
        assertEquals(1f, values.last(), 0f)
        assertEquals("中心不动", 0.5f, MotionBindings.tintMix(MotionBindings.TINT_CENTER), 1e-6f)
        // 拉伸确实放大了差异（否则大多数柱子会挤在中间色、画面上看不出变化）。
        val rawGap = 0.65f - 0.35f
        val stretchedGap = MotionBindings.tintMix(0.65f) - MotionBindings.tintMix(0.35f)
        assertTrue("拉伸必须放大差异（$rawGap → $stretchedGap）", stretchedGap > rawGap)
    }

    @Test
    fun `明亮度占比的脏值不产生越界`() {
        assertEquals(0f, MotionBindings.tintMix(Float.NaN), 0f)
        assertEquals(0f, MotionBindings.tintMix(-9f), 0f)
        assertEquals(1f, MotionBindings.tintMix(9f), 1f)
    }

    // ── 6. 背景呼吸：全带 RMS → 包络（由 MotionEnvelope 承担）─────────────────────

    @Test
    fun `响度包络跟的是全带 RMS`() {
        val e = MotionEnvelope()
        // 全带 RMS 从 0.05 跳到 0.6：包络必须跟着上（起音时间常数 90ms）。
        repeat(20) { e.update(0.05f, 0.02f, 16f, active = true) }
        val before = e.level()
        repeat(20) { e.update(0.6f, 0.02f, 16f, active = true) }
        val after = e.level()
        assertTrue("响度包络必须跟随全带 RMS（$before → $after）", after > before + 0.2f)
        assertTrue(after <= 0.6f + 1e-4f)
    }

    /**
     * **静音必须收敛到静止**（这一条直接对应"不得伪装成随音乐变化"）。
     *
     * 输入全零时，包络、脉冲都必须单调回落到精确的 0，之后不再有任何变化 ——
     * 也就是说「静音时还在动的动效」在结构上不可能出现。
     */
    @Test
    fun `静音输入下所有随时间变化的量都收敛到零`() {
        val e = MotionEnvelope()
        val b = MotionBindings()
        val backdrop = MotionBackdropState()
        val motion = MotionEffects.of(tier = MotionIntensity.SHOWCASE, uiMotionEnabled = true)

        // 先制造一堆"刚发生过"的状态。
        b.update(0.9f, 0.5f, 0.5f, 0.5f, 0.5f, available = true, transientCount = 0L, transientStrength = 1f)
        b.update(0.9f, 0.5f, 0.5f, 0.5f, 0.5f, available = true, transientCount = 1L, transientStrength = 1f)
        e.update(0.9f, 0.5f, 16f, active = true, transients = b.transients, strength = b.strength, externalAvailable = true)
        backdrop.update(b, motion, 16f, active = true)
        assertTrue("前置条件：确实有东西在动", e.pulse() > 0f)

        // 之后输入全零，一直跑。
        var guard = 0
        while (guard < 5000) {
            b.update(0f, 0f, 0f, 0f, 0f, available = true, transientCount = 1L, transientStrength = 0f)
            val changed = e.update(
                0f, 0f, 16f, active = true,
                transients = b.transients, strength = b.strength, externalAvailable = true,
            )
            val backdropChanged = backdrop.update(b, motion, 16f, active = true)
            if (!changed && !backdropChanged) break
            guard++
        }
        assertTrue("必须在有限帧内收敛（$guard 帧）", guard < 2000)
        assertEquals("静音时脉冲必须精确归零", 0f, e.pulse(), 0f)
        assertEquals("静音时响度必须精确归零", 0f, e.level(), 0f)
        assertEquals("静音时不得留下粒子", 0, backdrop.aliveParticles())
        assertEquals("静音时不得留下光晕", 0, backdrop.aliveHalos())
        assertEquals("静音时不得留下冲击波", 0, backdrop.aliveShocks())
        // 收敛之后**一帧都不该再要求重绘**（不空转）。
        assertFalse("收敛后不该再排帧", e.update(0f, 0f, 16f, active = true, transients = 0, strength = 0f, externalAvailable = true))
        assertFalse(backdrop.update(b, motion, 16f, active = true))
    }
}
