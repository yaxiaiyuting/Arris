/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.8.0 · P1-A：波形分级设置项的读写 + 迁移 + 有界降级（内存版 SharedPreferences）。
 *
 * 为什么用手写内存 prefs 而不是 Robolectric：本仓库单测没有 Android 运行时
 * （`unitTests.isReturnDefaultValues = true` 只把框架桩的返回值抹平，不提供 prefs 实现），
 * 而这些读写函数只依赖 SharedPreferences 接口 —— 一个内存实现就够，还能精确复现
 * 「键里存了别的类型」这种真实脏数据（真机上 getInt/getBoolean 遇到它必抛 ClassCastException）。
 *
 * 覆盖任务书要求的四件事：缺 key 取默认、非法值回落默认、迁移幂等、既有键一个不动。
 */
class VisualizerPrefsTest {

    /** 高端机（解析默认 = 精致档）。 */
    private val modernDefault = VisualizerTier.REFINED

    /** 3GB 级老机（解析默认 = 简洁档）。 */
    private val lowEndDefault = VisualizerTier.defaultTier(
        isLowRamDevice = false,
        totalMemBytes = 3L * 1024 * 1024 * 1024,
        sdkInt = 34,
    )

    @Test
    fun `键名是持久化契约——改名等于静默重置所有用户的选择`() {
        assertEquals("ncrust_settings", VisualizerPrefs.PREFS)
        assertEquals("visualizer_tier", VisualizerPrefs.KEY_TIER)
        assertEquals("visualizer_showcase", VisualizerPrefs.KEY_SHOWCASE)
        assertEquals("visualizer_shockwave", VisualizerPrefs.KEY_SHOCKWAVE)
        assertEquals("visualizer_particles", VisualizerPrefs.KEY_PARTICLES)
        assertEquals("visualizer_perspective", VisualizerPrefs.KEY_PERSPECTIVE)
        assertEquals("visualizer_drag", VisualizerPrefs.KEY_DRAG)
        assertEquals("visualizer_auto_downgraded", VisualizerPrefs.KEY_AUTO_DOWNGRADED)
        assertEquals("visualizer_tier_version", VisualizerPrefs.KEY_TIER_VERSION)
    }

    // ------------------------------------------------------------------
    // 缺 key = 未选择（≠ 选了默认档）
    // ------------------------------------------------------------------

    @Test
    fun `全部键缺失时取默认值与解析默认档`() {
        val prefs = FakePrefs()
        assertEquals(0, lowEndDefault)
        assertEquals(1, modernDefault)
        assertEquals(lowEndDefault, VisualizerPrefs.readTier(prefs, lowEndDefault))
        assertEquals(modernDefault, VisualizerPrefs.readTier(prefs, modernDefault))
        assertFalse(VisualizerPrefs.hasExplicitTier(prefs))
        assertFalse(VisualizerPrefs.readShowcase(prefs))
        assertFalse(VisualizerPrefs.readShockwave(prefs))
        assertFalse(VisualizerPrefs.readParticles(prefs))
        assertFalse(VisualizerPrefs.readPerspective(prefs))
        assertFalse(VisualizerPrefs.readTapInteraction(prefs))
        assertFalse(VisualizerPrefs.readAutoDowngraded(prefs))
        assertEquals(VisualizerPrefs.DEFAULT_TIER_VERSION, VisualizerPrefs.readTierVersion(prefs))
    }

    /** 缺 key 与「显式选了 1」必须可区分：这是「低端机默认简洁」不被误读成「用户选了精致」的前提。 */
    @Test
    fun `缺 key 与显式选了精致档可区分`() {
        val fresh = FakePrefs()
        assertFalse(VisualizerPrefs.hasExplicitTier(fresh))
        VisualizerPrefs.writeTier(fresh, VisualizerTier.REFINED)
        assertTrue(VisualizerPrefs.hasExplicitTier(fresh))
        assertEquals(VisualizerTier.REFINED, VisualizerPrefs.readTier(fresh, lowEndDefault))
    }

    @Test
    fun `显式档位原样读回，且不受设备默认档影响`() {
        for (tier in VisualizerTier.RANGE) {
            val prefs = FakePrefs()
            VisualizerPrefs.writeTier(prefs, tier)
            assertEquals(tier, VisualizerPrefs.readTier(prefs, lowEndDefault))
            assertEquals(tier, VisualizerPrefs.readTier(prefs, modernDefault))
        }
    }

    @Test
    fun `越界档位回落解析默认档`() {
        for (bad in listOf(-1, 3, 99, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val prefs = FakePrefs()
            prefs.putRaw(VisualizerPrefs.KEY_TIER, bad)
            assertEquals("低端机上越界值必须回落 0", lowEndDefault, VisualizerPrefs.readTier(prefs, lowEndDefault))
            assertEquals("高端机上越界值回落 1", modernDefault, VisualizerPrefs.readTier(prefs, modernDefault))
        }
    }

    /** 键里存了别的类型（真机上是脏数据/被别的版本写坏）：不抛，回落默认。 */
    @Test
    fun `类型不符的脏数据不抛异常并回落默认`() {
        val prefs = FakePrefs()
        prefs.putRaw(VisualizerPrefs.KEY_TIER, "2")
        prefs.putRaw(VisualizerPrefs.KEY_SHOWCASE, 1)
        prefs.putRaw(VisualizerPrefs.KEY_TIER_VERSION, "v1")
        assertEquals(modernDefault, VisualizerPrefs.readTier(prefs, modernDefault))
        assertFalse(VisualizerPrefs.readShowcase(prefs))
        assertEquals(VisualizerPrefs.DEFAULT_TIER_VERSION, VisualizerPrefs.readTierVersion(prefs))
    }

    @Test
    fun `C 档细分开关写盘后能读回，互不串键`() {
        val prefs = FakePrefs()
        VisualizerPrefs.writeShockwave(prefs, true)
        VisualizerPrefs.writeParticles(prefs, true)
        VisualizerPrefs.writeShowcase(prefs, true)
        assertTrue(VisualizerPrefs.readShockwave(prefs))
        assertTrue(VisualizerPrefs.readParticles(prefs))
        assertTrue(VisualizerPrefs.readShowcase(prefs))
        assertFalse("没写的键不得被带开", VisualizerPrefs.readPerspective(prefs))
        assertFalse(VisualizerPrefs.readTapInteraction(prefs))
    }

    // ------------------------------------------------------------------
    // 效果矩阵一次读全
    // ------------------------------------------------------------------

    @Test
    fun `readEffects 把档位与细分开关合成一个效果对象`() {
        val prefs = FakePrefs()
        VisualizerPrefs.writeTier(prefs, VisualizerTier.SHOWCASE)
        VisualizerPrefs.writeShowcase(prefs, true)
        VisualizerPrefs.writeShockwave(prefs, true)
        VisualizerPrefs.writeTapInteraction(prefs, true)
        val effects = VisualizerPrefs.readEffects(prefs, lowEndDefault)
        assertEquals(VisualizerTier.SHOWCASE, effects.tier)
        assertTrue(effects.shockwave)
        assertTrue(effects.tapInteraction)
        assertFalse(effects.particles)
        assertFalse(effects.autoDowngraded)
    }

    // ------------------------------------------------------------------
    // 迁移：缺 key 取默认、非法值回落、幂等、既有键一个不动
    // ------------------------------------------------------------------

    /**
     * v1 的迁移在「键缺失」时是**严格 no-op**：`DEFAULT_TIER_VERSION` 与
     * `CURRENT_TIER_VERSION` 都是 1，缺 key 读出来就是当前水位，没有需要搬运的键。
     * 这条用例把「no-op 且不偷偷写键」钉住 —— 悄悄写一个等于默认值的键，
     * 会让「这个键到底代不代表用户选择过」更难判断。
     */
    @Test
    fun `缺 key 时迁移不写盘也不新增键`() {
        val prefs = FakePrefs()
        prefs.putRaw("audio_visualizer", true)
        val before = prefs.getAll().toMap()
        assertFalse(VisualizerPrefs.migrate(prefs))
        assertEquals(before, prefs.getAll().toMap())
        assertEquals(VisualizerPrefs.CURRENT_TIER_VERSION, VisualizerPrefs.readTierVersion(prefs))
    }

    /** 水位**低于**当前版本时（回滚安装 / 脏盘 / 将来的搬运版本）才真的写盘。 */
    @Test
    fun `低水位被补到当前版本`() {
        val prefs = FakePrefs()
        prefs.putRaw(VisualizerPrefs.KEY_TIER_VERSION, 0)
        assertTrue(VisualizerPrefs.migrate(prefs))
        assertEquals(VisualizerPrefs.CURRENT_TIER_VERSION, VisualizerPrefs.readTierVersion(prefs))
        // 补过之后第二次就是 no-op
        assertFalse(VisualizerPrefs.migrate(prefs))
    }

    @Test
    fun `迁移幂等——跑两次结果完全一致，第二次不写盘`() {
        val prefs = FakePrefs()
        prefs.putRaw(VisualizerPrefs.KEY_TIER_VERSION, 0)
        assertTrue(VisualizerPrefs.migrate(prefs))
        val afterFirst = prefs.getAll().toMap()
        assertFalse("第二次必须直接返回", VisualizerPrefs.migrate(prefs))
        assertEquals(afterFirst, prefs.getAll().toMap())

        // 已经在水位之上的（未来版本回滚场景）也不动它
        val future = FakePrefs()
        future.putRaw(VisualizerPrefs.KEY_TIER_VERSION, VisualizerPrefs.CURRENT_TIER_VERSION + 5)
        assertFalse(VisualizerPrefs.migrate(future))
        assertEquals(VisualizerPrefs.CURRENT_TIER_VERSION + 5, VisualizerPrefs.readTierVersion(future))
    }

    /**
     * 迁移**不得**删除或改写任何既有键：`audio_visualizer`（总开关）与用户已选的档位
     * 必须在迁移前后逐值相同 —— 波形分级是新增功能，不能顺手重置老用户的选择。
     */
    @Test
    fun `迁移不碰任何既有键`() {
        val prefs = FakePrefs()
        prefs.putRaw("audio_visualizer", false)
        prefs.putRaw("wifi_quality", 3)
        prefs.putRaw("theme_index", 0)
        VisualizerPrefs.writeTier(prefs, VisualizerTier.SIMPLE)
        val before = prefs.getAll().toMap()
        VisualizerPrefs.migrate(prefs)
        val after = prefs.getAll().toMap()
        assertEquals(before["audio_visualizer"], after["audio_visualizer"])
        assertEquals(before["wifi_quality"], after["wifi_quality"])
        assertEquals(before["theme_index"], after["theme_index"])
        assertEquals(before[VisualizerPrefs.KEY_TIER], after[VisualizerPrefs.KEY_TIER])
        assertEquals("v1 的迁移不新增任何键", before.size, after.size)

        // 低水位那条分支同样不许碰既有键
        val legacy = FakePrefs()
        legacy.putRaw("audio_visualizer", false)
        legacy.putRaw(VisualizerPrefs.KEY_TIER_VERSION, 0)
        legacy.putRaw(VisualizerPrefs.KEY_TIER, 2)
        VisualizerPrefs.migrate(legacy)
        assertEquals(false, legacy.getAll()["audio_visualizer"])
        assertEquals(2, legacy.getAll()[VisualizerPrefs.KEY_TIER])
        assertEquals(VisualizerPrefs.CURRENT_TIER_VERSION, legacy.getAll()[VisualizerPrefs.KEY_TIER_VERSION])
    }

    /** 老用户（只有 audio_visualizer）**不得**被解读成「选了最低档」。 */
    @Test
    fun `只有总开关的老用户走解析默认档`() {
        val prefs = FakePrefs()
        prefs.putRaw("audio_visualizer", true)
        assertFalse(VisualizerPrefs.hasExplicitTier(prefs))
        assertEquals(lowEndDefault, VisualizerPrefs.readTier(prefs, lowEndDefault))
        assertEquals(modernDefault, VisualizerPrefs.readTier(prefs, modernDefault))
    }

    // ------------------------------------------------------------------
    // v3.0.0：自动降级机制已整个删除
    // ------------------------------------------------------------------

    /**
     * `VisualizerPrefs` 上不再有任何"降级"入口；`visualizer_auto_downgraded` 只剩**读**。
     *
     * 这条用反射断言而不是注释：机制删掉之后，谁把它加回来（哪怕只是加一个方法），
     * 构建就会因为这条用例而变红。
     */
    @Test
    fun `不存在任何自动降级入口`() {
        val names = VisualizerPrefs::class.java.declaredMethods.map { it.name.lowercase() }
        // 只禁「推进降级」那一类名字。读历史标记的 `readAutoDowngraded` 必须保留
        // （`MotionPrefs.migrate` 仍要读它来补迁移说明）。
        val forbidden = names.filter { it.contains("applydowngrade") || it.contains("downgradedtier") }
        assertTrue("VisualizerPrefs 不该再有降级入口：$forbidden", forbidden.isEmpty())
        // 读历史标记的入口保留（`MotionPrefs.migrate` 仍要读它来补迁移说明）。
        assertTrue(
            "读历史标记的入口必须在",
            VisualizerPrefs::class.java.declaredMethods.any { it.name == "readAutoDowngraded" },
        )
    }

    /** 降级标记现在只是一个**历史值**：读得到、但不影响任何能力位。 */
    @Test
    fun `历史降级标记不再影响能力位`() {
        val prefs = FakePrefs()
        VisualizerPrefs.writeTier(prefs, VisualizerTier.SHOWCASE)
        VisualizerPrefs.writeShowcase(prefs, true)
        VisualizerPrefs.writeShockwave(prefs, true)
        val before = VisualizerPrefs.readEffects(prefs, modernDefault)
        assertTrue(before.shockwave)
        assertFalse(before.autoDowngraded)

        // 手工把历史标记与档位写成一个"曾经降过级"的盘。
        prefs.putRaw(VisualizerPrefs.KEY_AUTO_DOWNGRADED, true)
        val after = VisualizerPrefs.readEffects(prefs, modernDefault)
        assertEquals("档位由键决定，不由标记决定", VisualizerTier.SHOWCASE, after.tier)
        assertTrue("标记只用于诊断回显", after.autoDowngraded)
        assertTrue("炫技档的 C 档效果照旧", after.shockwave)
    }

    // ------------------------------------------------------------------
    // 内存版 SharedPreferences（getX 刻意硬转类型，与 Android 原实现一致）
    // ------------------------------------------------------------------

    private class FakePrefs(
        private val values: MutableMap<String, Any?> = mutableMapOf()
    ) : SharedPreferences {

        fun putRaw(key: String, value: Any?) {
            values[key] = value
        }

        override fun getAll(): MutableMap<String, *> = values

        override fun getString(key: String, defValue: String?): String? =
            if (values.containsKey(key)) values[key] as? String else defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            if (values.containsKey(key)) values[key] as? MutableSet<String> else defValues

        override fun getInt(key: String, defValue: Int): Int =
            if (values.containsKey(key)) values[key] as Int else defValue

        override fun getLong(key: String, defValue: Long): Long =
            if (values.containsKey(key)) values[key] as Long else defValue

        override fun getFloat(key: String, defValue: Float): Float =
            if (values.containsKey(key)) values[key] as Float else defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            if (values.containsKey(key)) values[key] as Boolean else defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(values)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }

    private class FakeEditor(
        private val values: MutableMap<String, Any?>
    ) : SharedPreferences.Editor {

        private val pending = mutableMapOf<String, Any?>()
        private var pendingClear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putStringSet(key: String, stringValues: MutableSet<String>?): SharedPreferences.Editor {
            pending[key] = stringValues
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = REMOVED
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            pendingClear = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (pendingClear) {
                values.clear()
                pendingClear = false
            }
            pending.forEach { (key, value) ->
                if (value === REMOVED) values.remove(key) else values[key] = value
            }
            pending.clear()
        }

        private companion object {
            private val REMOVED = Any()
        }
    }
}
