/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.motion

import com.takahashirinta.ncrust.ui.player.waveform.VisualizerTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.0：**统一「动效强度」的能力位矩阵 + 有界降级阶梯**（纯逻辑，无 Android 依赖）。
 *
 * 覆盖任务书 §3.1 的三张表与铁律 22/23：
 *  - 三档 → 波形能力位与界面动效能力位的**逐格**断言（table-driven，不靠肉眼）；
 *  - A 档默认开、但总开关关掉后**三层全关**且**波形不受影响**；
 *  - 降级优先砍界面动效（B → A），再砍波形档位，且**有界**（到顶即止）。
 */
class MotionEffectsTest {

    private fun effects(
        tier: Int,
        ui: Boolean = true,
        degrade: Int = MotionDegrade.NONE,
    ): MotionEffects = MotionEffects.of(tier = tier, uiMotionEnabled = ui, degradeLevel = degrade)

    // ── 1. 三档能力位矩阵 ────────────────────────────────────────────────────────────

    @Test
    fun `简洁档只开 A 档界面动效`() {
        val e = effects(MotionIntensity.SIMPLE)
        assertTrue("A 档全开", e.backgroundBlur && e.backgroundBreathing && e.coverElevation && e.coverTransition)
        assertFalse("B 档必须关", e.anyAdvanced)
        assertFalse("C 档必须关", e.anyShowcase)
        assertEquals("波形仍是 v2.8.0 的简洁档", VisualizerTier.SIMPLE, e.effectiveWaveformTier)
    }

    @Test
    fun `精致档开 A 加 B`() {
        val e = effects(MotionIntensity.REFINED)
        assertTrue(e.anyBasic)
        assertTrue("B 档四项全开", e.fullScreenWaveform && e.lyricPulse && e.beatPulse && e.parallax)
        assertFalse("C 档必须关（默认关）", e.anyShowcase)
        assertEquals(VisualizerTier.REFINED, e.effectiveWaveformTier)
    }

    @Test
    fun `炫技档开 A 加 B 加 C`() {
        val e = effects(MotionIntensity.SHOWCASE)
        assertTrue(e.anyBasic)
        assertTrue(e.anyAdvanced)
        assertTrue("C 档三项全开", e.particles && e.haloBloom && e.cover3d)
        assertEquals(VisualizerTier.SHOWCASE, e.effectiveWaveformTier)
    }

    @Test
    fun `波形那一半仍然按 v2_8_0 的档位语义走`() {
        // 铁律：波形与界面动效统一到同一个档位，但波形自己的 A/B/C 效果矩阵不变。
        val simple = effects(MotionIntensity.SIMPLE).waveform
        assertTrue(simple.rounded && simple.peaks && simple.mirror)
        assertFalse(simple.flow || simple.dots || simple.breathe)

        val refined = effects(MotionIntensity.REFINED).waveform
        assertTrue(refined.flow && refined.dots && refined.breathe)
        assertFalse(refined.shockwave || refined.particles || refined.perspective)

        val showcase = effects(MotionIntensity.SHOWCASE).waveform
        assertTrue(showcase.shockwave && showcase.particles && showcase.perspective)
    }

    @Test
    fun `越界档位一律回落精致档`() {
        for (bad in listOf(-1, 3, 99, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val e = effects(bad)
            assertEquals("非法档位 $bad 必须回落精致档", MotionIntensity.REFINED, e.tier)
        }
    }

    // ── 2. 总开关（铁律 22：A 档默认开，但保留总开关）────────────────────────────────

    @Test
    fun `界面动效总开关关掉时三层全关且波形不受影响`() {
        for (tier in MotionIntensity.RANGE) {
            val on = effects(tier, ui = true)
            val off = effects(tier, ui = false)
            assertFalse("档位 $tier：总开关关掉后不得有任何界面动效", off.anyUiMotion)
            assertFalse(off.backgroundLayerEnabled)
            assertFalse("关掉总开关不得动波形", off.needsFrameClock && off.fullScreenWaveform)
            assertEquals(
                "档位 $tier：总开关与波形无关",
                on.effectiveWaveformTier,
                off.effectiveWaveformTier,
            )
            assertEquals(on.waveform.flow, off.waveform.flow)
            assertEquals(on.waveform.shockwave, off.waveform.shockwave)
        }
    }

    @Test
    fun `总开关默认是开的`() {
        assertTrue("铁律 22：A 档默认开", MotionPrefs.DEFAULT_UI_MOTION)
    }

    // ── 3. 降级阶梯（铁律 23：优先砍界面动效，再砍波形档位）─────────────────────────

    @Test
    fun `第一级降级只砍 B 档界面动效`() {
        val e = effects(MotionIntensity.SHOWCASE, degrade = MotionDegrade.UI_ADVANCED_OFF)
        assertTrue("A 档必须留着", e.anyBasic)
        assertFalse("B 档必须关掉", e.anyAdvanced)
        assertFalse("C 档随 B 档一起关（C 是 B 的超集）", e.anyShowcase)
        assertEquals("波形档位此时不动", MotionIntensity.SHOWCASE, e.effectiveWaveformTier)
    }

    @Test
    fun `第二级降级把 A 档也砍掉`() {
        val e = effects(MotionIntensity.SHOWCASE, degrade = MotionDegrade.UI_ALL_OFF)
        assertFalse("界面动效全关", e.anyUiMotion)
        assertEquals("波形档位仍然不动", MotionIntensity.SHOWCASE, e.effectiveWaveformTier)
    }

    @Test
    fun `第三级降级才动波形档位且只降一级`() {
        val showcase = effects(MotionIntensity.SHOWCASE, degrade = MotionDegrade.WAVEFORM_DOWN)
        assertEquals(MotionIntensity.REFINED, showcase.effectiveWaveformTier)
        assertFalse(showcase.waveform.shockwave)

        val refined = effects(MotionIntensity.REFINED, degrade = MotionDegrade.WAVEFORM_DOWN)
        assertEquals(MotionIntensity.SIMPLE, refined.effectiveWaveformTier)

        val simple = effects(MotionIntensity.SIMPLE, degrade = MotionDegrade.WAVEFORM_DOWN)
        assertEquals("已经在最低档 ⇒ 不越界", MotionIntensity.SIMPLE, simple.effectiveWaveformTier)
    }

    @Test
    fun `降级阶梯是有界的`() {
        assertEquals(MotionDegrade.UI_ADVANCED_OFF, MotionDegrade.next(MotionDegrade.NONE))
        assertEquals(MotionDegrade.UI_ALL_OFF, MotionDegrade.next(MotionDegrade.UI_ADVANCED_OFF))
        assertEquals(MotionDegrade.WAVEFORM_DOWN, MotionDegrade.next(MotionDegrade.UI_ALL_OFF))
        assertNull("到顶之后永不推进", MotionDegrade.next(MotionDegrade.WAVEFORM_DOWN))
        // 越界值归一化到 NONE：坏盘最坏只是「多降一次」，不是崩溃或死循环。
        assertEquals(MotionDegrade.NONE, MotionDegrade.sanitize(-3))
        assertEquals(MotionDegrade.NONE, MotionDegrade.sanitize(Int.MAX_VALUE))
    }

    @Test
    fun `降级水位越界值被当成未降级`() {
        val e = effects(MotionIntensity.SHOWCASE, degrade = 999)
        assertEquals(MotionDegrade.NONE, e.degradeLevel)
        assertTrue(e.anyShowcase)
    }

    @Test
    fun `非严重超标时阶梯封在第一级`() {
        // 两台真机各踩过一次：判据只是"刚好越线"，却把 A 档也砍了。
        assertEquals(MotionDegrade.UI_ADVANCED_OFF, MotionDegrade.maxLevelFor(atFloorTier = false, severe = false))
        assertEquals(MotionDegrade.MAX, MotionDegrade.maxLevelFor(atFloorTier = false, severe = true))
        assertEquals(MotionDegrade.UI_ADVANCED_OFF, MotionDegrade.maxLevelFor(atFloorTier = true, severe = true))
        assertFalse(MotionDegrade.isSevere(24, 60))
        assertFalse(MotionDegrade.isSevere(0, 0))
        assertTrue(MotionDegrade.isSevere(54, 60))
    }

    @Test
    fun `降级级别与界面动效的对应关系是单调的`() {
        // 单调性：水位升高 ⇒ 开着的能力位只减不增（防止将来有人写出"降级反而开了新效果"）。
        var previous = effects(MotionIntensity.SHOWCASE, degrade = 0)
        var count = count(previous)
        for (level in 1..MotionDegrade.MAX) {
            val current = effects(MotionIntensity.SHOWCASE, degrade = level)
            val now = count(current)
            assertTrue("水位 $level 之后能力位不得变多（$count → $now）", now <= count)
            count = now
            previous = current
        }
        assertEquals("到顶之后不该再有界面动效", 0, count(previous))
    }

    private fun count(e: MotionEffects): Int = listOf(
        e.backgroundBlur, e.backgroundBreathing, e.coverElevation, e.coverTransition,
        e.fullScreenWaveform, e.lyricPulse, e.beatPulse, e.parallax,
        e.particles, e.haloBloom, e.cover3d,
    ).count { it }

    // ── 4. 能力位之间的结构约束 ─────────────────────────────────────────────────────

    @Test
    fun `需要帧时钟的效果集合被如实声明`() {
        // needsFrameClock 是「要不要挂帧循环」的判据：漏一项就会出现"设置开了但画面不动"，
        // 多一项会让静态效果白烧帧。逐格对照。
        val e = effects(MotionIntensity.SHOWCASE)
        assertTrue(e.needsFrameClock)
        // 只剩 A 档时：A 档里**只有"背景呼吸"是逐帧的**（模糊图、阴影、切歌淡入都是静态的），
        // 所以帧时钟仍然要挂 —— 这一条正是"别把静态效果也算进 needsFrameClock"的反向断言。
        val basicOnly = MotionEffects.of(
            tier = MotionIntensity.SIMPLE,
            uiMotionEnabled = true,
            degradeLevel = MotionDegrade.NONE,
        )
        assertTrue("A 档全开时：背景呼吸需要帧时钟", basicOnly.needsFrameClock)
        assertFalse("但 B/C 档的逐帧项一个都不能开", basicOnly.anyAdvanced || basicOnly.anyShowcase)

        // 界面动效**整个关掉**时才是真正的"零逐帧"。
        val off = MotionEffects.of(
            tier = MotionIntensity.SHOWCASE,
            uiMotionEnabled = false,
            degradeLevel = MotionDegrade.NONE,
        )
        assertFalse("总开关关掉 ⇒ 零逐帧", off.needsFrameClock)
        assertFalse("总开关关掉 ⇒ 背景层也不挂", off.backgroundLayerEnabled)

        // 降级到 2 级之后：界面动效全关，只剩波形那一半 —— 波形自己的档位仍然需要帧时钟。
        val degraded = MotionEffects.of(
            tier = MotionIntensity.SHOWCASE,
            uiMotionEnabled = true,
            degradeLevel = MotionDegrade.UI_ALL_OFF,
        )
        assertFalse("界面动效全关", degraded.anyUiMotion)
        assertTrue("但波形档位还是炫技档 ⇒ 帧时钟必须继续跑", degraded.effectiveWaveformTier > VisualizerTier.SIMPLE)
    }

    @Test
    fun `背景层在纯色回退时仍然挂载`() {
        // 模糊失败时回退纯色是 `MotionBackdrop` 内部的事；这里的判据是"要不要挂这一层"，
        // 只要 A 档开着就挂（呼吸与 C 档粒子都画在它上面）。
        val e = MotionEffects.of(MotionIntensity.SIMPLE, true, MotionDegrade.NONE)
        assertTrue(e.backgroundLayerEnabled)
    }
}
