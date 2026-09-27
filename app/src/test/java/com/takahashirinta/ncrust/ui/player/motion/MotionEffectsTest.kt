/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.motion

import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.0：**统一「动效强度」的能力位矩阵**（纯逻辑，无 Android 依赖）。
 * v3.0.0：档位表加入音频驱动的动效，并**删除自动降级**。
 *
 * 覆盖：
 *  - 三档 → 波形能力位与界面动效能力位的**逐格**断言（table-driven，不靠肉眼）；
 *  - A 档默认开、但总开关关掉后**全关**且**波形不受影响**；
 *  - 五个独立开关各自能单独关掉一项，且**只**关掉那一项；
 *  - **渲染与任何后台状态无关**（结构保证：`MotionEffects` 上根本没有降级水位字段）。
 */
class MotionEffectsTest {

    private fun effects(
        tier: Int,
        ui: Boolean = true,
        switches: MotionSwitches = MotionSwitches.ALL_ON,
    ): MotionEffects = MotionEffects.of(
        tier = tier,
        uiMotionEnabled = ui,
        switches = switches,
    )

    // ── 1. 三档能力位矩阵（v3.0.0 的档位表）────────────────────────────────────────

    @Test
    fun `简洁档只有波形基础与背景美化的 A 档`() {
        val e = effects(MotionIntensity.SIMPLE)
        assertTrue("A 档全开", e.backgroundBlur && e.backgroundBreathing && e.coverElevation && e.coverTransition)
        assertFalse("B 档必须关", e.anyAdvanced)
        assertFalse("C 档必须关", e.anyShowcase)
        assertFalse("音频驱动的动效是精致档起才有的", e.shockwave || e.haloBloom || e.particles)
        assertEquals("波形仍是 v2.8.0 的简洁档", VisualizerTier.SIMPLE, e.effectiveWaveformTier)
        assertEquals(
            "多频段调制也是精致档起",
            VisualizerEffects.MODE_WAVE_BAND_OFF,
            e.waveform.waveBandMode,
        )
    }

    @Test
    fun `精致档开 A 加 B 加音频驱动动效（低密度单圈）`() {
        val e = effects(MotionIntensity.REFINED)
        assertTrue(e.anyBasic)
        assertTrue("B 档四项全开", e.fullScreenWaveform && e.lyricPulse && e.beatPulse && e.parallax)
        assertTrue("v3.0.0：冲击波/光晕/粒子在精致档就有", e.shockwave && e.haloBloom && e.particles)
        assertEquals("低密度", MotionEffects.DENSITY_LOW, e.particleDensity)
        assertEquals("单圈", 1, e.haloRings)
        assertFalse("C 档（封面 3D）仍然只在炫技档", e.cover3d)
        assertEquals(VisualizerTier.REFINED, e.effectiveWaveformTier)
        assertEquals(VisualizerEffects.MODE_WAVE_BAND_TINT, e.waveform.waveBandMode)
    }

    @Test
    fun `炫技档加密度与圈数以及 C 档`() {
        val e = effects(MotionIntensity.SHOWCASE)
        assertTrue(e.anyBasic)
        assertTrue(e.anyAdvanced)
        assertTrue("C 档全开", e.particles && e.haloBloom && e.cover3d)
        assertEquals("高密度", MotionEffects.DENSITY_HIGH, e.particleDensity)
        assertEquals("光晕多圈", 2, e.haloRings)
        assertEquals(VisualizerTier.SHOWCASE, e.effectiveWaveformTier)
        assertEquals(
            "炫技档多一条三频带能量条",
            VisualizerEffects.MODE_WAVE_BAND_LANES,
            e.waveform.waveBandMode,
        )
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
    fun `界面动效总开关关掉时全关且波形不受影响`() {
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

    // ── 3. 五个独立开关（铁律 26：每个新动效必须能单独关）──────────────────────────

    @Test
    fun `五个独立开关默认全开`() {
        val s = MotionSwitches.ALL_ON
        assertTrue(s.shockwave && s.halo && s.particles && s.waveBands && s.breathing)
        assertTrue("默认值常量也是开", MotionPrefs.DEFAULT_SWITCH)
    }

    @Test
    fun `关掉冲击波只影响冲击波`() {
        val e = effects(MotionIntensity.SHOWCASE, switches = MotionSwitches(shockwave = false))
        assertFalse(e.shockwave)
        assertTrue("其它音频驱动动效不受影响", e.haloBloom && e.particles && e.backgroundBreathing)
        assertTrue("多频段调制也不受影响", e.waveform.waveBandOn)
    }

    @Test
    fun `关掉光晕只影响光晕`() {
        val e = effects(MotionIntensity.SHOWCASE, switches = MotionSwitches(halo = false))
        assertFalse(e.haloBloom)
        assertTrue(e.shockwave && e.particles)
    }

    @Test
    fun `关掉粒子只影响粒子`() {
        val e = effects(MotionIntensity.SHOWCASE, switches = MotionSwitches(particles = false))
        assertFalse(e.particles)
        assertTrue(e.shockwave && e.haloBloom)
    }

    @Test
    fun `关掉多频段调制只影响波形那一半`() {
        val e = effects(MotionIntensity.SHOWCASE, switches = MotionSwitches(waveBands = false))
        assertEquals(VisualizerEffects.MODE_WAVE_BAND_OFF, e.waveform.waveBandMode)
        assertFalse(e.waveform.waveBandOn)
        assertTrue("界面动效一个都不受影响", e.shockwave && e.haloBloom && e.particles)
    }

    @Test
    fun `关掉背景呼吸只影响呼吸`() {
        val e = effects(MotionIntensity.SHOWCASE, switches = MotionSwitches(breathing = false))
        assertFalse(e.backgroundBreathing)
        assertTrue("背景模糊还在（它是 A 档的另一项）", e.backgroundBlur)
        assertTrue(e.shockwave && e.haloBloom && e.particles)
    }

    @Test
    fun `五个开关全关时界面动效只剩背景模糊与浮起`() {
        val e = effects(
            MotionIntensity.SHOWCASE,
            switches = MotionSwitches(
                shockwave = false,
                halo = false,
                particles = false,
                waveBands = false,
                breathing = false,
            ),
        )
        assertFalse("五项音频驱动动效全关", e.anyAudioBinding)
        assertTrue(
            "但 B 档的节拍脉冲 / 歌词律动仍然跟 RMS 走 ⇒ 仍然需要音频特征",
            e.needsAudioFeatures,
        )
        assertTrue("B 档（全屏波形 / 节拍脉冲）仍开着 ⇒ 帧时钟还要跑", e.needsFrameClock)
        assertTrue("背景模糊（静态）也还在", e.backgroundBlur)
    }

    // ── 4. 「渲染只由用户选的档位决定」的结构性保证 ─────────────────────────────────

    /**
     * v3.0.0：**`MotionEffects` 上不存在任何降级水位字段**。
     *
     * 这条用反射断言而不是注释：自动降级机制被删除之后，「渲染偷偷依赖了某个后台状态」
     * 必须在编译期/测试期就不可能发生。谁把水位加回来，这条会立刻变红。
     */
    @Test
    fun `能力位对象上不存在降级水位字段`() {
        val names = MotionEffects::class.java.declaredFields.map { it.name }
        assertFalse(
            "MotionEffects 上不该再有降级水位字段：$names",
            names.any { it.contains("degrade", ignoreCase = true) },
        )
        // `of(...)` 的参数个数也要钉住：多一个 Int 参数就说明有人把水位加回来了。
        // （这里数个数而不是读参数名 —— 参数名需要 `-java-parameters`，不该依赖编译开关。）
        val ofParams = MotionEffects.Companion::class.java.declaredMethods
            .filter { it.name == "of" }
            .map { it.parameterCount }
        assertEquals("MotionEffects.of 的参数个数变了：$ofParams", listOf(4), ofParams)
    }

    @Test
    fun `同档同开关的组合必然给出同一份能力位（渲染可推导）`() {
        // 反复构造 200 次，能力位必须逐字段相同 —— 没有任何隐藏状态参与。
        val reference = effects(MotionIntensity.REFINED)
        repeat(200) {
            val e = effects(MotionIntensity.REFINED)
            assertEquals(reference.shockwave, e.shockwave)
            assertEquals(reference.haloBloom, e.haloBloom)
            assertEquals(reference.particles, e.particles)
            assertEquals(reference.backgroundBreathing, e.backgroundBreathing)
            assertEquals(reference.waveform.waveBandMode, e.waveform.waveBandMode)
            assertEquals(reference.needsFrameClock, e.needsFrameClock)
        }
    }

    // ── 5. 能力位之间的结构约束 ─────────────────────────────────────────────────────

    @Test
    fun `需要帧时钟的效果集合被如实声明`() {
        // needsFrameClock 是「要不要挂帧循环」的判据：漏一项就会出现"设置开了但画面不动"，
        // 多一项会让静态效果白烧帧。逐格对照。
        val e = effects(MotionIntensity.SHOWCASE)
        assertTrue(e.needsFrameClock)

        // 只剩 A 档时：A 档里**只有"背景呼吸"是逐帧的**（模糊图、阴影、切歌淡入都是静态的）。
        val basicOnly = effects(MotionIntensity.SIMPLE)
        assertTrue("A 档全开时：背景呼吸需要帧时钟", basicOnly.needsFrameClock)
        assertFalse("但 B/C 档的逐帧项一个都不能开", basicOnly.anyAdvanced || basicOnly.anyShowcase)
        // 简洁档的「音频驱动」只有背景呼吸一项（新版动效都要精致档起）。
        assertTrue("简洁档只有背景呼吸是音频驱动的", basicOnly.backgroundBreathing)
        assertFalse(
            "冲击波 / 光晕 / 粒子 / 频带响应都不在简洁档",
            basicOnly.shockwave || basicOnly.haloBloom || basicOnly.particles ||
                basicOnly.waveform.waveBandOn,
        )

        // 界面动效**整个关掉**时才是真正的"零逐帧"。
        val off = effects(MotionIntensity.SHOWCASE, ui = false)
        assertFalse("总开关关掉 ⇒ 零逐帧", off.needsFrameClock)
        assertFalse("总开关关掉 ⇒ 背景层也不挂", off.backgroundLayerEnabled)

        // 把逐帧项逐个关掉：全关之后 needsFrameClock 必须为 false（不多不少）。
        val noBreath = effects(MotionIntensity.SIMPLE, switches = MotionSwitches(breathing = false))
        assertFalse("简洁档 + 关掉呼吸 ⇒ 没有任何逐帧动效", noBreath.needsFrameClock)
    }

    @Test
    fun `背景层在纯色回退时仍然挂载`() {
        // 模糊失败时回退纯色是 `MotionBackdrop` 内部的事；这里的判据是"要不要挂这一层"，
        // 只要 A 档开着就挂（呼吸与音频驱动的特效都画在它上面）。
        val e = effects(MotionIntensity.SIMPLE)
        assertTrue(e.backgroundLayerEnabled)
    }

    // ── 6. 「需要音频特征」的判据 ───────────────────────────────────────────────────

    @Test
    fun `需要音频特征的判据覆盖每一个音频驱动的动效`() {
        // 逐项关掉，只有全关之后才允许说"不需要特征"。
        val all = effects(MotionIntensity.SHOWCASE)
        assertTrue(all.needsAudioFeatures)

        val onlyBlur = effects(
            MotionIntensity.SIMPLE,
            switches = MotionSwitches(breathing = false),
        )
        assertFalse("只剩背景模糊（静态）时不需要音频特征", onlyBlur.needsAudioFeatures)

        // 波形频带响应单独存在时仍然需要特征（它读的是三频段能量）。
        val onlyBands = effects(
            MotionIntensity.REFINED,
            switches = MotionSwitches(
                shockwave = false,
                halo = false,
                particles = false,
                breathing = false,
                waveBands = true,
            ),
        )
        assertTrue(onlyBands.needsAudioFeatures)
    }

    // ── 7. 遗留常量（历史键的读取口径）──────────────────────────────────────────────

    @Test
    fun `降级遗留常量只用于读旧盘`() {
        assertEquals(0, MotionDegrade.NONE)
        assertEquals(1, MotionDegrade.UI_ADVANCED_OFF)
        assertEquals(2, MotionDegrade.UI_ALL_OFF)
        assertEquals(3, MotionDegrade.WAVEFORM_DOWN)
        assertEquals(3, MotionDegrade.MAX)
        // 越界值归一化（读旧盘用）。
        assertEquals(MotionDegrade.NONE, MotionDegrade.sanitize(-3))
        assertEquals(MotionDegrade.NONE, MotionDegrade.sanitize(Int.MAX_VALUE))
        assertEquals(MotionDegrade.WAVEFORM_DOWN, MotionDegrade.sanitize(3))
        // 旧级别的可读描述（迁移说明里写给用户看）。
        assertEquals("ui-advanced-off", MotionDegrade.describeLegacyLevel(1))
        assertEquals("waveform-down", MotionDegrade.describeLegacyLevel(3))
    }
}
