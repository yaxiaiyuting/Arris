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
 * v3.2.0：**简洁档收窄成静态档**（P0-B），并加入「界面律动总闸 + 三个细粒度开关」。
 *
 * 覆盖：
 *  - 三档 → 波形能力位与界面动效能力位的**逐格**断言（table-driven，不靠肉眼）；
 *  - **简洁档的律动类能力位逐个为 false**，且 `needsFrameClock` / `needsAudioFeatures`
 *    都为 false（= 那一档零逐帧，P0-B 的可执行判据）；
 *  - A 档默认开、但总开关关掉后**全关**且**波形不受影响**；
 *  - 九个独立开关各自能单独关掉一项，且**只**关掉那一项；
 *  - **律动总闸**：关掉它只影响律动类，冲击波/光晕/粒子/视差一概不受影响；
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

    /** 只关掉律动总闸（其余全开）。 */
    private fun rhythmOff(): MotionSwitches = MotionSwitches(rhythm = false)

    // ── 1. 三档能力位矩阵（v3.2.0 的档位表）────────────────────────────────────────

    /**
     * **P0-B 的可执行判据**：`MotionEffects.of(0, ...)` 的律动类能力位**一个都不为真**。
     *
     * 判据写法是表驱动的：把「律动类」逐项列出来断言 —— 少列一项就是漏测，
     * 而"漏测"正是这个 bug 上一版溜过去的方式（封面浮动藏在 `coverElevation` 里）。
     */
    @Test
    fun `简洁档是静态档：律动类能力位逐个为假`() {
        val e = effects(MotionIntensity.SIMPLE)
        // 律动类 = 驱动量来自 MotionEnvelope 的节拍 / 强拍 / 响度（见 probe-ui-jitter.md §5）
        assertFalse("背景呼吸（随响度包络）", e.backgroundBreathing)
        assertFalse("封面浮动（随鼓点脉冲）", e.coverFloat)
        assertFalse("歌词律动（随鼓点脉冲）", e.lyricPulse)
        assertFalse("控制条脉冲（随鼓点脉冲）", e.beatPulse)
        assertFalse("封面 3D 旋转（唯一驱动量是 pulse）", e.cover3d)
        // 简洁档不是「关掉动效」，是「不抖」：静态项必须留下。
        assertTrue("背景模糊", e.backgroundBlur)
        assertTrue("封面浮起阴影（静态）", e.coverElevation)
        assertTrue("切歌淡入", e.coverTransition)
        // 零逐帧是最强的那条结论：不挂帧循环、音频线程也不跑特征。
        assertFalse("简洁档不得有任何逐帧界面动效", e.needsFrameClock)
        assertFalse("简洁档不需要音频特征", e.needsAudioFeatures)
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
        assertTrue("v3.2.0：律动类在精致档全部打开", e.backgroundBreathing && e.coverFloat)
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

    /**
     * **总闸关 ⇒ 一个都不挂载**（P1 要求的第一条）。
     *
     * 「一个都不挂载」的机械表达是 `anyUiMotion == false`：`PlayerCard` 用它决定
     * 背景层与帧时钟挂不挂（`PlayerCard.kt` 的 `if (motion.anyUiMotion)`），
     * 不是靠 `alpha`（AGENTS.md 触摸陷阱第 1 条）。
     */
    @Test
    fun `总闸关掉时九个独立开关一个都救不回来`() {
        for (tier in MotionIntensity.RANGE) {
            val e = effects(tier, ui = false)
            assertFalse("档位 $tier：总闸关 ⇒ 界面动效整个不挂载", e.anyUiMotion)
            assertFalse(e.anyBasic || e.anyAdvanced || e.anyShowcase)
            assertFalse(e.anyAudioBinding)
            assertFalse("总闸关 ⇒ 零逐帧", e.needsFrameClock)
            assertFalse("总闸关 ⇒ 不跑音频特征", e.needsAudioFeatures)
        }
        // 开关**本身**的默认值仍然是开（缺 key = 与档位表一致）—— 总闸不去改写它们。
        assertTrue(MotionPrefs.DEFAULT_UI_MOTION)
    }

    @Test
    fun `总开关默认是开的`() {
        assertTrue("铁律 22：A 档默认开", MotionPrefs.DEFAULT_UI_MOTION)
    }

    // ── 3. 独立开关（铁律 26：每个新动效必须能单独关）──────────────────────────────

    @Test
    fun `九个独立开关默认全开`() {
        val s = MotionSwitches.ALL_ON
        assertTrue(
            "九项默认全开",
            s.shockwave && s.halo && s.particles && s.waveBands && s.breathing &&
                s.rhythm && s.coverFloat && s.lyricPulse && s.barPulse,
        )
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

    // ---- v3.2.0：律动类的四个细粒度开关，逐项证伪（关掉任意一项只影响那一项）----

    @Test
    fun `关掉背景呼吸只影响呼吸`() {
        val e = effects(MotionIntensity.SHOWCASE, switches = MotionSwitches(breathing = false))
        assertFalse(e.backgroundBreathing)
        assertTrue("背景模糊还在（它是 A 档的另一项）", e.backgroundBlur)
        assertTrue("律动类的其它三项不受影响", e.coverFloat && e.lyricPulse && e.beatPulse)
        assertTrue(e.shockwave && e.haloBloom && e.particles)
    }

    @Test
    fun `关掉封面浮动只影响封面浮动`() {
        val e = effects(MotionIntensity.SHOWCASE, switches = MotionSwitches(coverFloat = false))
        assertFalse("封面浮动关掉", e.coverFloat)
        assertTrue("静态浮起阴影是另一件事，不受影响", e.coverElevation)
        assertTrue("其它律动类不受影响", e.backgroundBreathing && e.lyricPulse && e.beatPulse)
        assertTrue("非律动类不受影响", e.shockwave && e.particles && e.cover3d)
    }

    @Test
    fun `关掉歌词律动只影响歌词律动`() {
        val e = effects(MotionIntensity.SHOWCASE, switches = MotionSwitches(lyricPulse = false))
        assertFalse(e.lyricPulse)
        assertTrue(e.backgroundBreathing && e.coverFloat && e.beatPulse)
        assertTrue("全屏波形与视差不是律动类", e.fullScreenWaveform && e.parallax)
    }

    @Test
    fun `关掉控制条脉冲只影响控制条脉冲`() {
        val e = effects(MotionIntensity.SHOWCASE, switches = MotionSwitches(barPulse = false))
        assertFalse(e.beatPulse)
        assertTrue(e.backgroundBreathing && e.coverFloat && e.lyricPulse)
        assertTrue("冲击波/光晕/粒子不受影响", e.shockwave && e.haloBloom && e.particles)
    }

    @Test
    fun `四个律动类细开关全关时只剩非律动动效`() {
        val e = effects(
            MotionIntensity.SHOWCASE,
            switches = MotionSwitches(
                breathing = false,
                coverFloat = false,
                lyricPulse = false,
                barPulse = false,
            ),
        )
        assertTrue("封面 3D 是律动类里唯一没有独立开关的一项，只受律动闸约束 ⇒ 仍在", e.cover3d)
        assertTrue("所以 anyRhythm 仍为真（律动闸没关）", e.anyRhythm)
        assertFalse("但四个细粒度开关对应的四项确实都关了",
            e.backgroundBreathing || e.coverFloat || e.lyricPulse || e.beatPulse)
        assertTrue("非律动类照旧", e.shockwave && e.haloBloom && e.particles && e.parallax)
        assertTrue(e.fullScreenWaveform)
        assertTrue("背景模糊（静态）还在", e.backgroundBlur)
        assertTrue("仍有非律动的逐帧项 ⇒ 帧时钟要跑", e.needsFrameClock)
    }

    @Test
    fun `五个开关全关时界面动效只剩静态项与律动类`() {
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
        assertFalse(
            "这五项（冲击波 / 光晕 / 粒子 / 多频段 / 呼吸）确实全关了",
            e.shockwave || e.haloBloom || e.particles || e.waveform.waveBandOn ||
                e.backgroundBreathing,
        )
        assertTrue(
            "但 `anyAudioBinding` 仍然为真：律动类的另外三项还在消费音频特征",
            e.anyAudioBinding,
        )
        assertTrue(
            "但律动类的另外三项（封面浮动 / 歌词律动 / 控制条脉冲）仍然跟节拍走 ⇒ 仍需要音频特征",
            e.needsAudioFeatures,
        )
        assertTrue("B 档（全屏波形 / 节拍脉冲）仍开着 ⇒ 帧时钟还要跑", e.needsFrameClock)
        assertTrue("背景模糊（静态）也还在", e.backgroundBlur)
    }

    // ── 3b. v3.2.0：律动总闸（`motion_rhythm_enabled`）────────────────────────────

    /**
     * **律动闸关 ⇒ 非律动效果不受影响**（P1 要求的第二条）。
     *
     * 这条是「界面律动」这个概念的边界：它只掐 `MotionEnvelope` 的节拍/响度那一类。
     * 用户关掉它是为了让画面**别跟着鼓点抖**，不是为了关掉冲击波与粒子 ——
     * 那两件事各有自己的开关，混在一起会让"我关了律动，怎么还有光圈"变成新的抱怨。
     */
    @Test
    fun `关掉律动总闸只掐律动类其余一概不受影响`() {
        for (tier in listOf(MotionIntensity.REFINED, MotionIntensity.SHOWCASE)) {
            val on = effects(tier)
            val off = effects(tier, switches = rhythmOff())
            // 律动类：全关
            assertFalse("档位 $tier：背景呼吸", off.backgroundBreathing)
            assertFalse("档位 $tier：封面浮动", off.coverFloat)
            assertFalse("档位 $tier：歌词律动", off.lyricPulse)
            assertFalse("档位 $tier：控制条脉冲", off.beatPulse)
            assertFalse("档位 $tier：封面 3D（唯一驱动量是 pulse）", off.cover3d)
            assertFalse("档位 $tier：一个律动类都不剩", off.anyRhythm)
            // 非律动类：逐项与「开」时**逐字段相等**
            assertEquals("档位 $tier：背景模糊", on.backgroundBlur, off.backgroundBlur)
            assertEquals("档位 $tier：封面阴影", on.coverElevation, off.coverElevation)
            assertEquals("档位 $tier：切歌淡入", on.coverTransition, off.coverTransition)
            assertEquals("档位 $tier：全屏波形", on.fullScreenWaveform, off.fullScreenWaveform)
            assertEquals("档位 $tier：视差", on.parallax, off.parallax)
            assertEquals("档位 $tier：冲击波", on.shockwave, off.shockwave)
            assertEquals("档位 $tier：光晕", on.haloBloom, off.haloBloom)
            assertEquals("档位 $tier：粒子", on.particles, off.particles)
            assertEquals("档位 $tier：粒子密度", on.particleDensity, off.particleDensity)
            assertEquals("档位 $tier：光晕圈数", on.haloRings, off.haloRings)
            assertEquals("档位 $tier：多频段调制", on.waveform.waveBandMode, off.waveform.waveBandMode)
            // 剩下的逐帧量只有非律动的那几项。
            assertTrue("档位 $tier：冲击波/光晕/粒子仍是逐帧的", off.needsFrameClock)
        }
    }

    /**
     * 「关掉就是关掉，任何档位都不许绕过」：律动闸关掉之后，
     * **把档位从简洁调到炫技也不会让任何律动类能力位复活**。
     */
    @Test
    fun `律动闸关掉后提到炫技档也不会有律动`() {
        for (tier in MotionIntensity.RANGE) {
            val e = effects(tier, switches = rhythmOff())
            assertFalse("档位 $tier：律动类一个都不能漏", e.anyRhythm)
        }
    }

    @Test
    fun `律动闸不影响简洁档的静态项`() {
        // 简洁档本来就没有律动类；律动闸关掉不该让它再少画东西。
        val on = effects(MotionIntensity.SIMPLE)
        val off = effects(MotionIntensity.SIMPLE, switches = rhythmOff())
        assertTrue(off.backgroundBlur && off.coverElevation && off.coverTransition)
        assertEquals(on.anyUiMotion, off.anyUiMotion)
        assertEquals(on.backgroundLayerEnabled, off.backgroundLayerEnabled)
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
            assertEquals(reference.coverFloat, e.coverFloat)
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

        // 简洁档：静态档，**零逐帧**（v3.2.0 的 P0-B）。
        val basicOnly = effects(MotionIntensity.SIMPLE)
        assertFalse("简洁档不得有任何逐帧项", basicOnly.needsFrameClock)
        assertFalse("B/C 档的逐帧项一个都不能开", basicOnly.anyAdvanced || basicOnly.anyShowcase)
        assertFalse("简洁档一个律动类都没有", basicOnly.anyRhythm)

        // 界面动效**整个关掉**时同样是"零逐帧"。
        val off = effects(MotionIntensity.SHOWCASE, ui = false)
        assertFalse("总开关关掉 ⇒ 零逐帧", off.needsFrameClock)
        assertFalse("总开关关掉 ⇒ 背景层也不挂", off.backgroundLayerEnabled)

        // 逐帧项逐个关掉之后，needsFrameClock 必须为 false（不多不少）。
        val staticOnly = effects(
            MotionIntensity.SIMPLE,
            switches = MotionSwitches(
                breathing = false,
                coverFloat = false,
            ),
        )
        assertFalse("简洁档 + 关掉律动 ⇒ 没有任何逐帧动效", staticOnly.needsFrameClock)
    }

    @Test
    fun `背景层在纯色回退时仍然挂载`() {
        // 模糊失败时回退纯色是 `MotionBackdrop` 内部的事；这里的判据是"要不要挂这一层"，
        // 只要 A 档的静态项开着就挂（简洁档也要挂：模糊背景是那一档唯一的美化）。
        val e = effects(MotionIntensity.SIMPLE)
        assertTrue(e.backgroundLayerEnabled)
        assertTrue("简洁档的背景层是静态的，但必须挂", e.anyBasic)
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
        assertFalse("简洁档只剩背景模糊（静态）时不需要音频特征", onlyBlur.needsAudioFeatures)

        // 波形频带响应单独存在时仍然需要特征（它读的是三频段能量）。
        val onlyBands = effects(
            MotionIntensity.REFINED,
            switches = MotionSwitches(
                shockwave = false,
                halo = false,
                particles = false,
                breathing = false,
                coverFloat = false,
                lyricPulse = false,
                barPulse = false,
                waveBands = true,
            ),
        )
        assertTrue(onlyBands.needsAudioFeatures)

        // v3.2.0：封面浮动是**独立**的音频特征消费者 —— 只留它一项时也必须判定为"要特征"
        // （否则会出现"设置开着、封面却不动"的静默失效）。
        val onlyCoverFloat = effects(
            MotionIntensity.REFINED,
            switches = MotionSwitches(
                shockwave = false,
                halo = false,
                particles = false,
                breathing = false,
                lyricPulse = false,
                barPulse = false,
                waveBands = false,
                coverFloat = true,
            ),
        )
        assertTrue("只留封面浮动时仍然需要音频特征", onlyCoverFloat.needsAudioFeatures)
        assertTrue("它也是逐帧的 ⇒ 帧时钟要跑", onlyCoverFloat.needsFrameClock)
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
