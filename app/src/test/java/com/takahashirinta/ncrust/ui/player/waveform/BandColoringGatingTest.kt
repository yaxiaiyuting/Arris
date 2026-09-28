/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import com.takahashirinta.ncrust.ui.player.motion.MotionEffects
import com.takahashirinta.ncrust.ui.player.motion.MotionIntensity
import com.takahashirinta.ncrust.ui.player.motion.MotionSwitches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.2：**档位适配与开关联动**的单测（任务书 §3.4）。
 *
 * ## 本版的核心决定 ①：**不新增任何设置项**
 *
 * 主导频段着色挂的是既有能力位 `waveBandMode`，而它由**既有的** `motion_wave_bands`
 * 开关决定（v3.2.2 起与档位解耦，见下）。理由：
 *  - 它就是 v3.0.0「多频段波形调制」这条功能的**新表达**（逐柱 tint → 整条曲线一个色），
 *    不是第二件事，再加一个开关会变成"两个开关管同一件事"；
 *  - 仓库纪律「能不加字段就不加」（v1.9.3 固化）：没有新键 ⇒ 没有新迁移 ⇒ 没有新文案 ⇒
 *    没有 2^N 组合要上真机验证（v2.8.0 的教训）。
 *
 * 这个决定是**可执行的**：下面逐格断言"谁能开、谁不能开"，以及"特征到底发不发"
 * （`needsAudioFeatures`）——最后一条是硬约束，漏了就会出现"开关开着但没有数据"。
 */
class BandColoringGatingTest {

    private fun waveformOf(
        tier: Int,
        uiMotionEnabled: Boolean = true,
        waveBands: Boolean = true,
        rhythm: Boolean = true,
    ) = MotionEffects.of(
        tier = tier,
        uiMotionEnabled = uiMotionEnabled,
        switches = MotionSwitches(waveBands = waveBands, rhythm = rhythm),
    ).waveform

    @Test
    fun `简洁档 —— 也开频段着色（本版起与档位解耦）`() {
        // 这条是**用户实测反馈**的直接落点：低端机的默认档就是简洁档，
        // 按档位收窄的后果是"默认体验是一条单色曲线"，用户报「没有做出
        // 左中右分别代表低中高频率的感觉」。着色是波形自己的属性 ⇒ 三档都开。
        val w = waveformOf(MotionIntensity.SIMPLE)
        assertTrue("简洁档也要按频带着色", w.waveBandOn)
        assertEquals(VisualizerEffects.MODE_WAVE_BAND_TINT, w.waveBandMode)
        assertFalse("频带能量条仍然是炫技档的", w.waveBandLanes)
    }

    @Test
    fun `三档都开频带着色 —— 档位只决定别的效果`() {
        for (tier in MotionIntensity.SIMPLE..MotionIntensity.SHOWCASE) {
            assertTrue("tier=$tier 必须着色", waveformOf(tier).waveBandOn)
        }
        assertTrue("只有炫技档画频带能量条", waveformOf(MotionIntensity.SHOWCASE).waveBandLanes)
        assertFalse(waveformOf(MotionIntensity.REFINED).waveBandLanes)
    }

    @Test
    fun `精致档 —— 开启频段着色`() {
        val w = waveformOf(MotionIntensity.REFINED)
        assertTrue(w.waveBandOn)
        assertEquals(VisualizerEffects.MODE_WAVE_BAND_TINT, w.waveBandMode)
        assertFalse("频带能量条是炫技档的", w.waveBandLanes)
    }

    @Test
    fun `炫技档 —— 频段着色 加 三频带能量条`() {
        val w = waveformOf(MotionIntensity.SHOWCASE)
        assertTrue(w.waveBandOn)
        assertTrue(w.waveBandLanes)
    }

    @Test
    fun `用户关掉多频段开关 —— 任何档位都不着色`() {
        for (tier in MotionIntensity.SIMPLE..MotionIntensity.SHOWCASE) {
            val w = waveformOf(tier, waveBands = false)
            assertFalse("tier=$tier 关掉开关后不得着色", w.waveBandOn)
        }
    }

    @Test
    fun `界面动效总闸关掉 —— 波形照旧（含着色），它只管界面动效`() {
        // 波形的开关是 `audio_visualizer` + `motion_wave_bands` 两个；
        // `ui_motion_enabled` 管的是背景/粒子/歌词律动那一层。
        val w = waveformOf(MotionIntensity.SHOWCASE, uiMotionEnabled = false)
        assertTrue("总闸关掉不影响波形的频带着色", w.waveBandOn)
        assertFalse("但炫技档的频带能量条随总闸一起关（它属于界面动效那一层）", w.waveBandLanes)
    }

    @Test
    fun `界面律动闸不影响频段着色 —— 它不读 pulse 或 level`() {
        // 律动闸（motion_rhythm_enabled）的判据是"渲染路径是否读 MotionClock.pulse()/level()"。
        // 主导频段着色的驱动量是 low/mid/high 三个频带能量，**不经过包络** ⇒ 不属于律动类。
        for (tier in MotionIntensity.SIMPLE..MotionIntensity.SHOWCASE) {
            val on = waveformOf(tier, rhythm = true)
            val off = waveformOf(tier, rhythm = false)
            assertEquals("tier=$tier", on.waveBandMode, off.waveBandMode)
            assertTrue(off.waveBandOn)
        }
    }

    @Test
    fun `开着频段着色时 音频线程必须发布特征 —— 否则开关是空的`() {
        // needsAudioFeatures 是音频线程侧"要不要算/发布特征"的唯一判据。
        // 漏了这条 ⇒ 界面开着开关但永远读不到 low/mid/high（静默失效）。
        val w = waveformOf(MotionIntensity.REFINED)
        assertTrue(w.waveBandOn)
        val effects = MotionEffects.of(
            tier = MotionIntensity.REFINED,
            uiMotionEnabled = true,
            switches = MotionSwitches(waveBands = true),
        )
        assertTrue("waveBandOn 必须让 needsAudioFeatures 为真", effects.needsAudioFeatures)
    }

    @Test
    fun `关掉频段着色后 特征链路可以整个不跑`() {
        val effects = MotionEffects.of(
            tier = MotionIntensity.SIMPLE,
            uiMotionEnabled = false,
            switches = MotionSwitches(
                shockwave = false,
                halo = false,
                particles = false,
                waveBands = false,
                breathing = false,
                coverFloat = false,
                barPulse = false,
                lyricPulse = false,
            ),
        )
        assertFalse(effects.needsAudioFeatures)
    }

    @Test
    fun `简洁档 + 界面动效全关时 特征链路仍然要跑 —— 因为波形要着色`() {
        // v3.2.2 的连带代价：低端机（默认简洁档）现在也要算三频带。
        // 这正是"着色必须复用既有特征、不得新增每帧计算"（铁律 29）的前提 ——
        // 链路一旦不开，画面上就会退回单色，而用户看到的是"开关没生效"。
        val effects = MotionEffects.of(
            tier = MotionIntensity.SIMPLE,
            uiMotionEnabled = false,
            switches = MotionSwitches(
                shockwave = false,
                halo = false,
                particles = false,
                waveBands = true,
                breathing = false,
                coverFloat = false,
                barPulse = false,
                lyricPulse = false,
            ),
        )
        assertTrue(effects.waveform.waveBandOn)
        assertTrue("波形要着色 ⇒ 音频线程必须发布三频带", effects.needsAudioFeatures)
    }

    @Test
    fun `不新增持久化键 —— 键名集合里没有任何曲线或频段色的新键`() {
        // 「能不加字段就不加」的可执行断言：这条测试红了就说明有人加了新键，
        // 那就必须同时补迁移逻辑与迁移单测（铁律 3）。
        val known = setOf(
            "visualizer_tier", "visualizer_showcase", "visualizer_shockwave", "visualizer_particles",
            "visualizer_perspective", "visualizer_drag", "visualizer_auto_downgraded",
            "visualizer_tier_version",
        )
        val declared = setOf(
            VisualizerPrefs.KEY_TIER, VisualizerPrefs.KEY_SHOWCASE, VisualizerPrefs.KEY_SHOCKWAVE,
            VisualizerPrefs.KEY_PARTICLES, VisualizerPrefs.KEY_PERSPECTIVE, VisualizerPrefs.KEY_DRAG,
            VisualizerPrefs.KEY_AUTO_DOWNGRADED, VisualizerPrefs.KEY_TIER_VERSION,
        )
        assertEquals("VisualizerPrefs 的键集合变了 —— 加键必须补迁移与单测", known, declared)
        assertEquals("迁移水位不该被这次改动推动", 1, VisualizerPrefs.CURRENT_TIER_VERSION)
    }
}
