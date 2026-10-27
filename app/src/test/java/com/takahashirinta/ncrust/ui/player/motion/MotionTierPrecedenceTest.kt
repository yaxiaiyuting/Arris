/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.8.0 · J14：水位升级时**不许**把已经迁移过的盘搬回旧档位。
 */

package com.takahashirinta.ncrust.ui.player.motion

import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J14 的回归契约：`motion_tier` 一旦存在且合法，迁移**不许**再覆盖它。
 *
 * ## 缺陷本体
 *
 * 迁移是**按水位幂等**的（`motion_version`），而水位本身会升级（v3.0.0 3→4、v3.2.0 4→5）。
 * 每次水位升级都会再走一遍 `migrate()`，而旧实现在那里**无条件**把
 * 「旧命名空间 `visualizer_tier` 的档位」写进 `motion_tier`：
 *
 * ```
 * val tier = migratedTier(legacyTier, legacyShowcase, deviceDefault)
 * safeEdit(prefs) { if (tier != null) it.putInt(KEY_TIER, tier) }   // ← 无条件
 * ```
 *
 * 旧键按设计**永不删除**（回滚安装不丢数据），所以「盘上有旧键」根本不能当成
 * 「还没迁移过」。真实的用户路径：
 *
 * `v2.8 迁移（旧 SHOWCASE）` → `用户手动改成 SIMPLE` → `后续版本升级水位`
 * ⇒ 档位被旧设置**搬回** SHOWCASE（用户看到画面突然多出一堆动效）。
 *
 * ## 现在的判据
 *
 * - 新键存在且取值合法 ⇒ 以它为准，不搬运旧值（只推进水位）；
 * - 新键缺失/越界 ⇒ 照旧从旧键搬（那是**第一次**迁移，也是这张盘唯一的档位来源）。
 */
class MotionTierPrecedenceTest {

    private val modernDefault = MotionIntensity.REFINED

    /** 一张 v3.0.0 落下的盘（水位 4 = 已经搬完 v2.8.0 那一轮）。 */
    private fun alreadyMigratedDisk(tier: Int): MemoryPrefs = MemoryPrefs().apply {
        putRaw(MotionPrefs.KEY_VERSION, MotionPrefs.VERSION_V300)
        putRaw(MotionPrefs.KEY_TIER, tier)
        // 旧命名空间的键按设计留着（回滚安装要用），而且值为 SHOWCASE。
        putRaw(MotionPrefs.LegacyKeys.KEY_TIER, MotionIntensity.SHOWCASE)
        putRaw(MotionPrefs.LegacyKeys.KEY_SHOWCASE, true)
    }

    @Test
    fun `已迁移过且用户改过档位 —— 水位升级不许搬回旧值`() {
        val prefs = alreadyMigratedDisk(MotionIntensity.SIMPLE)
        assertTrue("水位 4 → 5 会写一次盘", MotionPrefs.migrate(prefs, modernDefault))
        assertEquals(
            "新键是用户后来显式选的档位，绝不能被动效迁移覆盖",
            MotionIntensity.SIMPLE,
            prefs.getInt(MotionPrefs.KEY_TIER, -1),
        )
        assertEquals(MotionPrefs.CURRENT_VERSION, prefs.getInt(MotionPrefs.KEY_VERSION, -1))
    }

    @Test
    fun `旧值为 SHOWCASE 而新键是 REFINED —— 保持 REFINED`() {
        val prefs = alreadyMigratedDisk(MotionIntensity.REFINED)
        MotionPrefs.migrate(prefs, modernDefault)
        assertEquals(MotionIntensity.REFINED, prefs.getInt(MotionPrefs.KEY_TIER, -1))
    }

    @Test
    fun `第一次迁移仍然要从旧键搬 —— 新键缺失时照旧`() {
        val prefs = MemoryPrefs().apply {
            putRaw(MotionPrefs.LegacyKeys.KEY_TIER, MotionIntensity.SHOWCASE)
            putRaw(MotionPrefs.LegacyKeys.KEY_SHOWCASE, true)
        }
        assertTrue(MotionPrefs.migrate(prefs, modernDefault))
        assertEquals(
            "全新迁移路径不能被这次修复顺手掐断",
            MotionIntensity.SHOWCASE,
            prefs.getInt(MotionPrefs.KEY_TIER, -1),
        )
    }

    @Test
    fun `新键越界时按缺失处理 —— 仍然从旧键搬`() {
        val prefs = MemoryPrefs().apply {
            putRaw(MotionPrefs.KEY_VERSION, MotionPrefs.VERSION_V300)
            putRaw(MotionPrefs.KEY_TIER, 99) // 脏盘
            putRaw(MotionPrefs.LegacyKeys.KEY_TIER, MotionIntensity.SIMPLE)
        }
        MotionPrefs.migrate(prefs, modernDefault)
        assertEquals(
            "越界值不是用户意图，只能用旧键救回来（与 MotionIntensity.sanitize 同一口径）",
            MotionIntensity.SIMPLE,
            prefs.getInt(MotionPrefs.KEY_TIER, -1),
        )
    }

    @Test
    fun `已经到当前水位的盘仍然是严格 no-op`() {
        val prefs = alreadyMigratedDisk(MotionIntensity.SIMPLE)
        prefs.putRaw(MotionPrefs.KEY_VERSION, MotionPrefs.CURRENT_VERSION)
        val before = prefs.snapshot()
        assertFalse("水位够了就不该再碰盘", MotionPrefs.migrate(prefs, modernDefault))
        assertEquals(before, prefs.snapshot())
    }
}
