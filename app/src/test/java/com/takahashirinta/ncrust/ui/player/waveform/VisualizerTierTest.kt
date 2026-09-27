/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.8.0 · P1-A：波形档位解析 + 效果矩阵 + 降级有界性的**纯逻辑单测**。
 *
 * 这三件事在真机上都无法观测：档位默认值取决于设备内存/API（换台机器就不一样）、
 * 效果矩阵是「哪一档画哪些笔」（靠截图数不清 draw op）、降级有界性只在长跑+掉帧时才暴露。
 * 所以全部抽成接受参数的纯函数（[VisualizerTier.defaultTier] 收 isLowRam/totalMem/sdkInt），
 * 在 JVM 上逐格断言。
 */
class VisualizerTierTest {

    // ------------------------------------------------------------------
    // 1. 档位解析：缺 key 用**解析出的**默认（不是常量 1），显式选择原样，越界回落默认
    // ------------------------------------------------------------------

    /** 三条静态判据一一对应仓库既有落点（isLowRamDevice / totalMem ≤ 3.5GiB / SDK_INT < 26）。 */
    private val lowRam = VisualizerTier.defaultTier(isLowRamDevice = true, totalMemBytes = 8L * 1024 * 1024 * 1024, sdkInt = 34)
    private val threeGb = VisualizerTier.defaultTier(isLowRamDevice = false, totalMemBytes = 3L * 1024 * 1024 * 1024, sdkInt = 34)
    private val api24 = VisualizerTier.defaultTier(isLowRamDevice = false, totalMemBytes = 8L * 1024 * 1024 * 1024, sdkInt = 24)
    private val modern = VisualizerTier.defaultTier(isLowRamDevice = false, totalMemBytes = 8L * 1024 * 1024 * 1024, sdkInt = 34)

    @Test
    fun `低端机的三条判据各自都能把默认档拉到简洁档`() {
        assertEquals(VisualizerTier.SIMPLE, lowRam)
        assertEquals(VisualizerTier.SIMPLE, threeGb)
        assertEquals(VisualizerTier.SIMPLE, api24)
    }

    @Test
    fun `非低端机默认精致档`() {
        assertEquals(VisualizerTier.REFINED, modern)
    }

    /** 3.5 GiB 是闭区间上界；再高就是精致档。边界值逐点钉住，避免以后有人「顺手」改成 < 。 */
    @Test
    fun `低内存判据的边界值`() {
        assertEquals(VisualizerTier.SIMPLE, VisualizerTier.defaultTier(false, VisualizerTier.LOW_RAM_TOTAL_BYTES, 34))
        assertEquals(VisualizerTier.REFINED, VisualizerTier.defaultTier(false, VisualizerTier.LOW_RAM_TOTAL_BYTES + 1, 34))
    }

    /** API 26 是分界：25 及以下算低端（覆盖 Android 7.0/7.1），26 起不算。 */
    @Test
    fun `API 判据的边界值`() {
        assertEquals(VisualizerTier.SIMPLE, VisualizerTier.defaultTier(false, 8L shl 30, VisualizerTier.LOW_TIER_MAX_SDK_INT))
        assertEquals(VisualizerTier.REFINED, VisualizerTier.defaultTier(false, 8L shl 30, VisualizerTier.LOW_TIER_MAX_SDK_INT + 1))
    }

    /**
     * 内存信息读不到（totalMem = 0）时**不得**把所有设备都判成低端：
     * 宁可给默认档，也不要因为一次读取失败把所有人的可视化锁进简洁档。
     */
    @Test
    fun `读到 0 内存视为未知而不是低端`() {
        assertEquals(VisualizerTier.REFINED, VisualizerTier.defaultTier(false, 0L, 34))
        assertEquals(VisualizerTier.REFINED, VisualizerTier.defaultTier(false, -1L, 34))
    }

    @Test
    fun `越界档位回落调用方给的默认档而不是常量`() {
        // 低端机（解析默认 0）上盘里存了 9 ⇒ 回落到 0（不是常量 1，否则用户会被顶到精致档）
        assertEquals(VisualizerTier.SIMPLE, VisualizerTier.sanitize(9, VisualizerTier.SIMPLE))
        assertEquals(VisualizerTier.SIMPLE, VisualizerTier.sanitize(-1, VisualizerTier.SIMPLE))
        assertEquals(VisualizerTier.REFINED, VisualizerTier.sanitize(9, VisualizerTier.REFINED))
        // 合法值原样
        assertEquals(0, VisualizerTier.sanitize(0, VisualizerTier.REFINED))
        assertEquals(1, VisualizerTier.sanitize(1, VisualizerTier.REFINED))
        assertEquals(2, VisualizerTier.sanitize(2, VisualizerTier.REFINED))
    }

    /** 默认档自己也非法（调用方传了 7）时兜底到精致档，绝不返回一个越界档位。 */
    @Test
    fun `非法的默认档兜底到精致档`() {
        assertEquals(VisualizerTier.REFINED, VisualizerTier.sanitize(9, 7))
    }

    // ------------------------------------------------------------------
    // 2. 效果矩阵
    // ------------------------------------------------------------------

    private fun effects(
        tier: Int,
        showcase: Boolean = false,
        shockwave: Boolean = false,
        particles: Boolean = false,
        perspective: Boolean = false,
        drag: Boolean = false,
        autoDowngraded: Boolean = false,
    ) = VisualizerEffects.of(tier, showcase, shockwave, particles, perspective, drag, autoDowngraded)

    @Test
    fun `简洁档只开 A 档效果`() {
        val e = effects(VisualizerTier.SIMPLE)
        assertTrue(e.mirror)
        assertTrue(e.rounded)
        assertTrue(e.peaks)
        assertTrue("简洁档用按时序着色（非频谱）", e.timeOrderedTint)
        assertFalse(e.flow)
        assertFalse(e.dots)
        assertFalse(e.breathe)
        assertFalse(e.shockwave)
        assertFalse(e.particles)
        assertFalse(e.perspective)
        assertFalse(e.tapInteraction)
    }

    @Test
    fun `精致档在 A 档之上加渐变流动与柱顶光点与呼吸`() {
        val e = effects(VisualizerTier.REFINED)
        assertTrue(e.rounded && e.peaks && e.mirror)
        assertTrue(e.flow)
        assertTrue(e.dots)
        assertTrue(e.breathe)
        assertFalse("渐变流动与按时序着色互斥", e.timeOrderedTint)
        assertFalse(e.shockwave)
    }

    /** 炫技档但总开关关着 ⇒ 与精致档逐位相同（C 档一项都不开）。 */
    @Test
    fun `炫技档在总开关关闭时不开任何 C 档效果`() {
        val refined = effects(VisualizerTier.REFINED)
        val showcaseOff = effects(VisualizerTier.SHOWCASE, showcase = false, shockwave = true, particles = true, perspective = true, drag = true)
        assertEquals(refined.flow, showcaseOff.flow)
        assertEquals(refined.dots, showcaseOff.dots)
        assertEquals(refined.breathe, showcaseOff.breathe)
        assertFalse(showcaseOff.shockwave)
        assertFalse(showcaseOff.particles)
        assertFalse(showcaseOff.perspective)
        assertFalse(showcaseOff.tapInteraction)
        assertFalse(showcaseOff.anyShowcase)
    }

    @Test
    fun `炫技档加总开关打开时逐项细分开关各自独立`() {
        val base = effects(VisualizerTier.SHOWCASE, showcase = true)
        assertFalse(base.shockwave)
        assertFalse(base.particles)
        assertFalse(base.perspective)
        assertFalse(base.tapInteraction)

        assertTrue(effects(VisualizerTier.SHOWCASE, showcase = true, shockwave = true).shockwave)
        assertTrue(effects(VisualizerTier.SHOWCASE, showcase = true, particles = true).particles)
        assertTrue(effects(VisualizerTier.SHOWCASE, showcase = true, perspective = true).perspective)
        assertTrue(effects(VisualizerTier.SHOWCASE, showcase = true, drag = true).tapInteraction)

        // 只开一项不会连带打开别的
        val only = effects(VisualizerTier.SHOWCASE, showcase = true, particles = true)
        assertFalse(only.shockwave)
        assertFalse(only.perspective)
        assertFalse(only.tapInteraction)
        assertTrue(only.anyShowcase)
    }

    /** 简洁/精致档即使把细分开关全打开也不生效（门在档位上，不在开关上）。 */
    @Test
    fun `细分开关在非炫技档一律无效`() {
        for (tier in listOf(VisualizerTier.SIMPLE, VisualizerTier.REFINED)) {
            val e = effects(tier, showcase = true, shockwave = true, particles = true, perspective = true, drag = true)
            assertFalse(e.shockwave)
            assertFalse(e.particles)
            assertFalse(e.perspective)
            assertFalse(e.tapInteraction)
        }
    }

    /**
     * 互斥规则（探针 §2 的「硬互斥」）：同一笔绘制要么 `color=` 要么 `brush=`。
     * 渐变流动占住色带 ⇒ 按时序着色必须关；真频谱位（本版恒 false）同理。
     */
    @Test
    fun `渐变流动与按时序着色硬互斥，且色带模式只有一个读法`() {
        for (tier in VisualizerTier.RANGE) {
            for (showcase in listOf(false, true)) {
                val e = effects(tier, showcase = showcase)
                assertFalse("flow 与 timeOrderedTint 不得同时为真", e.flow && e.timeOrderedTint)
                assertEquals(
                    if (e.flow) VisualizerEffects.MODE_FLOW else VisualizerEffects.MODE_TIME_TINT,
                    e.colorChannelMode,
                )
            }
        }
    }

    /**
     * 本版**没有**真频谱：能力位恒为 false，而且永远不会与渐变流动同时为真。
     * 这条用例是「不要画一个会被读成频谱的假东西」的机器守卫。
     */
    @Test
    fun `本版不存在真频谱着色`() {
        for (tier in VisualizerTier.RANGE) {
            for (showcase in listOf(false, true)) {
                val e = effects(tier, showcase, shockwave = true, particles = true, perspective = true, drag = true)
                assertFalse(e.spectrumColoring)
                assertFalse(e.flow && e.spectrumColoring)
            }
        }
    }

    /** 越界档位进效果矩阵 ⇒ 按精致档处理（绘制不能因为一个脏值就什么都不画）。 */
    @Test
    fun `非法档位进效果矩阵回落到精致档`() {
        val e = effects(99)
        assertEquals(VisualizerTier.REFINED, e.tier)
        assertTrue(e.flow)
    }

    @Test
    fun `自动降级标记会带进效果对象供诊断`() {
        assertTrue(effects(VisualizerTier.REFINED, autoDowngraded = true).autoDowngraded)
        assertFalse(effects(VisualizerTier.REFINED).autoDowngraded)
    }

    // ------------------------------------------------------------------
    // 3. 降级策略的有界性（铁律 4：只降一级、每进程最多一次、不无限重试）
    // ------------------------------------------------------------------

    @Test
    fun `连续超标也只降一级`() {
        // 第一次：T2 → T1
        val first = VisualizerTier.downgradedTier(VisualizerTier.SHOWCASE, autoDowngraded = false)
        assertEquals(VisualizerTier.REFINED, first)
        // 第二次：标记已置位 ⇒ 不再降（哪怕帧时间继续超标）
        assertNull(VisualizerTier.downgradedTier(first!!, autoDowngraded = true))
        assertNull(VisualizerTier.downgradedTier(VisualizerTier.SHOWCASE, autoDowngraded = true))
    }

    @Test
    fun `精致档再降一次到简洁档，之后不再降`() {
        val next = VisualizerTier.downgradedTier(VisualizerTier.REFINED, autoDowngraded = false)
        assertEquals(VisualizerTier.SIMPLE, next)
        assertNull(VisualizerTier.downgradedTier(next!!, autoDowngraded = true))
    }

    @Test
    fun `已经在最低档时不降级`() {
        assertNull(VisualizerTier.downgradedTier(VisualizerTier.SIMPLE, autoDowngraded = false))
        assertNull(VisualizerTier.downgradedTier(VisualizerTier.SIMPLE, autoDowngraded = true))
    }

    /** 脏值（越界档位）不得让降级路径算出越界结果：先归一化再降。 */
    @Test
    fun `越界档位先归一化再降一级`() {
        assertEquals(VisualizerTier.SIMPLE, VisualizerTier.downgradedTier(99, autoDowngraded = false))
        assertEquals(VisualizerTier.SIMPLE, VisualizerTier.downgradedTier(-4, autoDowngraded = false))
    }

    /** 无论从哪一档开始，最多只会发生一次降级（把「已降过」沿调用链传下去）。 */
    @Test
    fun `从任何档位出发最多降一次`() {
        for (start in VisualizerTier.RANGE) {
            var tier = start
            var downgraded = false
            var steps = 0
            while (true) {
                val next = VisualizerTier.downgradedTier(tier, downgraded) ?: break
                tier = next
                downgraded = true
                steps++
                assertTrue("降级次数必须有界", steps <= 1)
            }
            assertEquals(if (start == VisualizerTier.SIMPLE) 0 else 1, steps)
            assertEquals(maxOf(VisualizerTier.SIMPLE, start - 1), tier)
        }
    }
}
