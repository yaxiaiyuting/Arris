/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.motion

import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerPrefs
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.0：统一「动效强度」的**读写 + v2.8.0 迁移 + 有界降级**（内存版 SharedPreferences）。
 *
 * 覆盖任务书 §2.1 / §3.2 / §8.2 要求的四件事：
 *  1. 缺 key 取（解析出来的）默认、非法值回落默认；
 *  2. **旧 key → 新档位**的搬运规则逐格正确（含「炫技档但炫技效果关着」这一格）；
 *  3. 迁移幂等，且**既有键一个不动**（回滚安装不丢数据）；
 *  4. 降级阶梯有界、用户手动改档位会重置水位。
 */
class MotionPrefsTest {

    /** 高端机的解析默认档（精致）。 */
    private val modernDefault = MotionIntensity.REFINED

    /** 3GB 级老机的解析默认档（简洁）。 */
    private val lowEndDefault = MotionIntensity.defaultFor(
        isLowRamDevice = false,
        totalMemBytes = 3L * 1024 * 1024 * 1024,
        sdkInt = 34,
    )

    // ── 1. 键名是持久化契约 ─────────────────────────────────────────────────────────

    @Test
    fun `键名字面量被钉住`() {
        assertEquals("ncrust_settings", MotionPrefs.PREFS)
        assertEquals("motion_tier", MotionPrefs.KEY_TIER)
        assertEquals("ui_motion_enabled", MotionPrefs.KEY_UI_MOTION)
        assertEquals("motion_degrade_level", MotionPrefs.KEY_DEGRADE_LEVEL)
        assertEquals("motion_version", MotionPrefs.KEY_VERSION)
    }

    @Test
    fun `迁移读的旧键名与 VisualizerPrefs 逐字一致`() {
        // 迁移是一份历史契约：改名等于静默把老用户的档位丢掉。两边各声明一次是有意的
        // （历史键名与历史键名放在一起），这条断言保证它们不会分叉。
        assertEquals(VisualizerPrefs.KEY_TIER, MotionPrefs.LegacyKeys.KEY_TIER)
        assertEquals(VisualizerPrefs.KEY_SHOWCASE, MotionPrefs.LegacyKeys.KEY_SHOWCASE)
        assertEquals(
            VisualizerPrefs.KEY_AUTO_DOWNGRADED,
            MotionPrefs.LegacyKeys.KEY_AUTO_DOWNGRADED,
        )
    }

    @Test
    fun `新键与 v2_8_0 的旧键不重名`() {
        val fresh = setOf(
            MotionPrefs.KEY_TIER,
            MotionPrefs.KEY_UI_MOTION,
            MotionPrefs.KEY_DEGRADE_LEVEL,
            MotionPrefs.KEY_VERSION,
        )
        val legacy = setOf(
            VisualizerPrefs.KEY_TIER,
            VisualizerPrefs.KEY_SHOWCASE,
            VisualizerPrefs.KEY_SHOCKWAVE,
            VisualizerPrefs.KEY_PARTICLES,
            VisualizerPrefs.KEY_PERSPECTIVE,
            VisualizerPrefs.KEY_DRAG,
            VisualizerPrefs.KEY_AUTO_DOWNGRADED,
            VisualizerPrefs.KEY_TIER_VERSION,
        )
        assertTrue("新旧键不得重名：${fresh intersect legacy}", (fresh intersect legacy).isEmpty())
    }

    // ── 2. 纯读 ────────────────────────────────────────────────────────────────────

    @Test
    fun `缺 key 时用解析出来的设备默认档`() {
        val prefs = MemoryPrefs()
        assertEquals(lowEndDefault, MotionPrefs.readTier(prefs, lowEndDefault))
        assertEquals(modernDefault, MotionPrefs.readTier(prefs, modernDefault))
        assertEquals(MotionIntensity.SIMPLE, lowEndDefault)
        assertEquals(MotionIntensity.REFINED, modernDefault)
        assertFalse("缺 key 不等于用户选过", MotionPrefs.hasExplicitTier(prefs))
        assertTrue("界面动效默认开", MotionPrefs.readUiMotionEnabled(prefs))
        assertEquals(MotionDegrade.NONE, MotionPrefs.readDegradeLevel(prefs))
        assertEquals("缺 key = 未迁移（不是「搬到 0」）", MotionPrefs.VERSION_PRE_V290, MotionPrefs.readVersion(prefs))
    }

    @Test
    fun `缺 key 绝不写回盘`() {
        val prefs = MemoryPrefs()
        MotionPrefs.readTier(prefs, modernDefault)
        MotionPrefs.readUiMotionEnabled(prefs)
        assertTrue("读路径不得有任何写盘", prefs.snapshot().isEmpty())
    }

    @Test
    fun `显式选择优先于设备默认档`() {
        val prefs = MemoryPrefs()
        MotionPrefs.writeTier(prefs, MotionIntensity.SIMPLE)
        assertTrue(MotionPrefs.hasExplicitTier(prefs))
        assertEquals(
            "低端机上也必须尊重用户显式选的精致档",
            MotionIntensity.SIMPLE,
            MotionPrefs.readTier(prefs, modernDefault),
        )
    }

    @Test
    fun `越界档位回落调用方给的默认档`() {
        for (bad in listOf(-1, 3, 99)) {
            val prefs = MemoryPrefs()
            prefs.putRaw(MotionPrefs.KEY_TIER, bad)
            assertEquals("坏值 $bad 必须回落低端默认", lowEndDefault, MotionPrefs.readTier(prefs, lowEndDefault))
            assertEquals("坏值 $bad 必须回落高端默认", modernDefault, MotionPrefs.readTier(prefs, modernDefault))
        }
    }

    @Test
    fun `类型不符的脏数据不抛异常`() {
        val prefs = MemoryPrefs()
        prefs.putRaw(MotionPrefs.KEY_TIER, "2")
        prefs.putRaw(MotionPrefs.KEY_UI_MOTION, 1)
        prefs.putRaw(MotionPrefs.KEY_DEGRADE_LEVEL, "x")
        prefs.putRaw(MotionPrefs.KEY_VERSION, "v1")
        assertEquals(modernDefault, MotionPrefs.readTier(prefs, modernDefault))
        assertTrue(MotionPrefs.readUiMotionEnabled(prefs))
        assertEquals(MotionDegrade.NONE, MotionPrefs.readDegradeLevel(prefs))
        assertEquals(MotionPrefs.VERSION_PRE_V290, MotionPrefs.readVersion(prefs))
    }

    // ── 3. 迁移（旧 key → 新档位）───────────────────────────────────────────────────

    @Test
    fun `有效的炫技档原样搬过来`() {
        assertEquals(
            MotionIntensity.SHOWCASE,
            MotionPrefs.migratedTier(
                legacyTier = MotionIntensity.SHOWCASE,
                legacyShowcase = true,
                deviceDefault = modernDefault,
            ),
        )
    }

    @Test
    fun `炫技档但炫技效果关着时搬到精致档`() {
        // 这一格是本版迁移的**关键决定**：v2.8.0 的 C 档要求 tier==2 **且** showcase==true，
        // 而 showcase 默认 false ⇒ 这些老盘在 v2.8.0 下渲染出来就是精致档。
        // 照抄 2 会让他们突然多出冲击波与粒子 —— 那是语义变化，不是"保留用户选择"。
        assertEquals(
            MotionIntensity.REFINED,
            MotionPrefs.migratedTier(
                legacyTier = MotionIntensity.SHOWCASE,
                legacyShowcase = false,
                deviceDefault = modernDefault,
            ),
        )
    }

    @Test
    fun `简洁与精致档不受炫技开关影响`() {
        for (tier in listOf(MotionIntensity.SIMPLE, MotionIntensity.REFINED)) {
            for (showcase in listOf(true, false)) {
                assertEquals(
                    "tier=$tier showcase=$showcase",
                    tier,
                    MotionPrefs.migratedTier(tier, showcase, modernDefault),
                )
            }
        }
    }

    @Test
    fun `没选过档位的老盘不写 motion_tier`() {
        assertNull(
            "旧键缺失 ⇒ 返回 null（= 不要写盘），让新版本继续用解析默认档",
            MotionPrefs.migratedTier(legacyTier = null, legacyShowcase = false, deviceDefault = modernDefault),
        )
    }

    @Test
    fun `自动降级过的老盘从第一级界面动效降级起跑`() {
        assertEquals(
            MotionDegrade.UI_ADVANCED_OFF,
            MotionPrefs.migratedDegradeLevel(legacyAutoDowngraded = true),
        )
        assertEquals(MotionDegrade.NONE, MotionPrefs.migratedDegradeLevel(legacyAutoDowngraded = false))
    }

    @Test
    fun `迁移把老盘搬成新键并留下水位`() {
        val prefs = MemoryPrefs()
        prefs.putRaw(MotionPrefs.LegacyKeys.KEY_TIER, MotionIntensity.REFINED)
        prefs.putRaw(MotionPrefs.LegacyKeys.KEY_SHOWCASE, true)
        val before = prefs.snapshot()

        assertTrue(MotionPrefs.migrate(prefs, modernDefault))
        assertEquals(MotionIntensity.REFINED, prefs.getInt(MotionPrefs.KEY_TIER, -1))
        assertEquals(MotionPrefs.CURRENT_VERSION, prefs.getInt(MotionPrefs.KEY_VERSION, -1))
        // 旧键一个不动：回滚安装 v2.8.0 时用户的选择还在。
        assertEquals(before[MotionPrefs.LegacyKeys.KEY_TIER], prefs.snapshot()[MotionPrefs.LegacyKeys.KEY_TIER])
        assertEquals(before[MotionPrefs.LegacyKeys.KEY_SHOWCASE], prefs.snapshot()[MotionPrefs.LegacyKeys.KEY_SHOWCASE])
    }

    @Test
    fun `迁移是幂等的且第二次不写盘`() {
        val prefs = MemoryPrefs()
        prefs.putRaw(MotionPrefs.LegacyKeys.KEY_TIER, MotionIntensity.SIMPLE)
        prefs.putRaw(MotionPrefs.LegacyKeys.KEY_AUTO_DOWNGRADED, true)

        assertTrue("第一次必须写盘", MotionPrefs.migrate(prefs, modernDefault))
        val afterFirst = prefs.snapshot()
        assertFalse("第二次必须是严格 no-op", MotionPrefs.migrate(prefs, modernDefault))
        assertEquals("盘必须逐键不变", afterFirst, prefs.snapshot())
    }

    @Test
    fun `迁移不碰任何无关键`() {
        val prefs = MemoryPrefs()
        val unrelated = mapOf(
            "audio_visualizer" to true,
            "wifi_quality" to 3,
            "lyrics_ttml_enabled" to false,
            "theme_mode" to "DARK",
            "visualizer_tier_version" to 1,
        )
        unrelated.forEach { (k, v) -> prefs.putRaw(k, v) }
        MotionPrefs.migrate(prefs, modernDefault)
        unrelated.forEach { (k, v) -> assertEquals("无关键 $k 被改了", v, prefs.snapshot()[k]) }
    }

    @Test
    fun `全新安装不写任何键`() {
        val prefs = MemoryPrefs()
        assertTrue("水位要从 0 补到 1，所以会写一次", MotionPrefs.migrate(prefs, modernDefault))
        val keys = prefs.snapshot().keys
        assertEquals("只写水位一个键", setOf(MotionPrefs.KEY_VERSION), keys)
        assertFalse("绝不能凭空写入 motion_tier", prefs.contains(MotionPrefs.KEY_TIER))
    }

    @Test
    fun `低端机的老盘按设备默认档兜底越界值`() {
        val prefs = MemoryPrefs()
        prefs.putRaw(MotionPrefs.LegacyKeys.KEY_TIER, 7)
        MotionPrefs.migrate(prefs, lowEndDefault)
        assertEquals(MotionIntensity.SIMPLE, prefs.getInt(MotionPrefs.KEY_TIER, -1))
    }

    // ── 4. 写与降级 ────────────────────────────────────────────────────────────────

    @Test
    fun `用户改档位会重置降级水位`() {
        val prefs = MemoryPrefs()
        MotionPrefs.writeDegradeLevel(prefs, MotionDegrade.UI_ALL_OFF)
        MotionPrefs.writeTier(prefs, MotionIntensity.SHOWCASE)
        assertEquals(
            "用户显式改档位永远优先于自动降级",
            MotionDegrade.NONE,
            MotionPrefs.readDegradeLevel(prefs),
        )
        assertEquals(MotionIntensity.SHOWCASE, MotionPrefs.readTier(prefs, lowEndDefault))
    }

    @Test
    fun `界面动效总开关不重置降级水位`() {
        val prefs = MemoryPrefs()
        MotionPrefs.writeDegradeLevel(prefs, MotionDegrade.UI_ADVANCED_OFF)
        MotionPrefs.writeUiMotionEnabled(prefs, false)
        assertEquals(
            "总开关是稳定偏好，与设备实测的降级结论生命周期不同",
            MotionDegrade.UI_ADVANCED_OFF,
            MotionPrefs.readDegradeLevel(prefs),
        )
    }

    @Test
    fun `自动降级逐级推进并且写回盘`() {
        val prefs = MemoryPrefs()
        MotionPrefs.writeTier(prefs, MotionIntensity.SHOWCASE)

        assertEquals(MotionDegrade.UI_ADVANCED_OFF, MotionPrefs.applyAutoDowngrade(prefs))
        assertEquals(MotionIntensity.SHOWCASE, MotionPrefs.readTier(prefs, modernDefault))
        assertEquals(MotionDegrade.UI_ADVANCED_OFF, MotionPrefs.readDegradeLevel(prefs))

        assertEquals(MotionDegrade.UI_ALL_OFF, MotionPrefs.applyAutoDowngrade(prefs))
        assertEquals(
            "第 1/2 级只砍界面动效，波形档位必须原封不动",
            MotionIntensity.SHOWCASE,
            MotionPrefs.readTier(prefs, modernDefault),
        )

        assertEquals(MotionDegrade.WAVEFORM_DOWN, MotionPrefs.applyAutoDowngrade(prefs))
        assertEquals(
            "第 3 级才把档位降一级，且写回 motion_tier（设置页显示的必须是实际渲染的那一档）",
            MotionIntensity.REFINED,
            MotionPrefs.readTier(prefs, modernDefault),
        )

        assertNull("到顶之后永不动作", MotionPrefs.applyAutoDowngrade(prefs))
    }

    @Test
    fun `已经在简洁档时第三级不再越界`() {
        val prefs = MemoryPrefs()
        MotionPrefs.writeTier(prefs, MotionIntensity.SIMPLE)
        MotionPrefs.writeDegradeLevel(prefs, MotionDegrade.UI_ALL_OFF)
        assertEquals(MotionDegrade.WAVEFORM_DOWN, MotionPrefs.applyAutoDowngrade(prefs))
        assertEquals(MotionIntensity.SIMPLE, MotionPrefs.readTier(prefs, modernDefault))
    }

    @Test
    fun `降级之后读出来的能力位立刻反映新水位`() {
        val prefs = MemoryPrefs()
        MotionPrefs.writeTier(prefs, MotionIntensity.SHOWCASE)
        val before = MotionPrefs.readEffects(prefs, modernDefault)
        assertTrue(before.anyShowcase)

        MotionPrefs.applyAutoDowngrade(prefs)
        val after = MotionPrefs.readEffects(prefs, modernDefault)
        assertFalse("第 1 级之后 B/C 档界面动效必须关掉", after.anyAdvanced || after.anyShowcase)
        assertTrue("A 档留着", after.anyBasic)
        assertNotEquals(before.degradeLevel, after.degradeLevel)
    }

    @Test
    fun `当前水位常量与阶梯上界一致`() {
        assertEquals(MotionDegrade.WAVEFORM_DOWN, MotionDegrade.MAX)
        assertEquals(1, MotionPrefs.CURRENT_VERSION)
        assertEquals(VisualizerTier.REFINED, MotionIntensity.DEFAULT)
    }
}
