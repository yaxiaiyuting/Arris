/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.1 · F 回归单测：「页面切换动效」的设置持久化与迁移。
 */

package com.takahashirinta.ncrust.ui.theme

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.1 · F：`PageTransitionSetting` 的持久化契约、v1→v2 迁移与「关闭即 0ms」。
 *
 * ## 为什么这四条必须存在（铁律 5：加字段 = 加迁移逻辑 = 加单测）
 *
 * 这个开关是**新加的一个持久化字段**。它的失效方式全都不是崩溃，而是**静默**的：
 *
 * | 失效 | 用户看到的现象 | 哪条用例挡住它 |
 * |---|---|---|
 * | 键名打错 / 被改名 | 所有用户的选择被重置成默认（无人察觉） | `键名是持久化契约` |
 * | 老用户（无键）被判成「关」 | 升级后转场凭空消失，与「默认启用」矛盾 | `迁移——键不存在时默认开` |
 * | 显式「关」被判成「开」 | 用户关不掉动效（本轮的核心验收项） | `显式值——关就是关` |
 * | 脏键（类型不符）没被吞掉 | 真机上 `getBoolean` 抛 `ClassCastException` → **设置页崩** | `脏键——回落默认，不抛异常` |
 * | 关闭时仍算出非 0 时长 | 关掉后还挂一个 1 帧的 Transition（"没有残留计算"不成立） | `关闭时时长严格为 0` |
 *
 * ## 为什么用手写内存 SharedPreferences 而不是 Robolectric
 *
 * 与 `LyricsSourcePrefsTest` 同一条理由：本仓库单测没有 Android 运行时
 * （`unitTests.isReturnDefaultValues = true` 只抹平框架桩的返回值，不提供 prefs 实现），
 * 而读写函数只依赖 `SharedPreferences` 接口。手写实现还能精确复现
 * 「同一个键里存了别的类型」这种真实脏数据 —— 真机上 `getBoolean` 遇到它必抛。
 */
class PageTransitionSettingTest {

    // ─────────────────────────────────────────────────────────────────────
    // 1. 键名与默认值：持久化契约
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `键名是持久化契约——改名等于静默重置所有用户的选择`() {
        assertEquals("ncrust_settings", PageTransitionSetting.PREFS)
        assertEquals("page_transition_enabled", PageTransitionSetting.KEY)
    }

    @Test
    fun `默认值是启用——任务书 §2_1 的硬要求`() {
        assertTrue(PageTransitionSetting.DEFAULT_ENABLED)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. v1 → v2 迁移
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `迁移——键不存在时默认开（v2_5_0 老用户从未做过选择）`() {
        // v1 的状态就是这个：v2.5.0 及以前根本没有这个键。
        // 「没有转场」是当时的产品决策，**不是**用户的显式选择，
        // 所以不能把它当成 false —— 那会与「默认启用」直接矛盾。
        assertEquals(true, PageTransitionSetting.resolveEnabled(null))

        val prefs = FakePrefs()
        assertNull("键不存在时 readStored 必须返回 null（而不是 false）", PageTransitionSetting.readStored(prefs))
        assertTrue(PageTransitionSetting.readEnabled(prefs))
    }

    @Test
    fun `显式值——写盘后能读回，且关就是关`() {
        val prefs = FakePrefs()

        PageTransitionSetting.writeEnabled(prefs, false)
        assertFalse("用户关掉之后必须真的读到关", PageTransitionSetting.readEnabled(prefs))
        assertEquals(false, PageTransitionSetting.readStored(prefs))

        PageTransitionSetting.writeEnabled(prefs, true)
        assertTrue(PageTransitionSetting.readEnabled(prefs))
        assertEquals(true, PageTransitionSetting.readStored(prefs))
    }

    @Test
    fun `迁移只补默认值，绝不覆盖用户已做的选择`() {
        // 这一条是上一条的反面：迁移的**唯一**触发条件是「键不存在」。
        val prefs = FakePrefs()
        PageTransitionSetting.writeEnabled(prefs, false)
        // 再读 N 次也不会被"迁移"回默认值（readEnabled 是纯读、无副作用）。
        repeat(5) { assertFalse(PageTransitionSetting.readEnabled(prefs)) }
        assertFalse(PageTransitionSetting.readEnabled(prefs))
    }

    @Test
    fun `脏键——回落默认，不抛异常`() {
        // prefs 被写坏：同一个键塞了字符串。真机 SharedPreferences.getBoolean
        // 在这里会抛 ClassCastException，读函数必须吞掉它、回落默认值。
        val prefs = FakePrefs()
        prefs.putRaw(PageTransitionSetting.KEY, "true")
        assertNull(PageTransitionSetting.readStored(prefs))
        assertTrue(PageTransitionSetting.readEnabled(prefs))
    }

    @Test
    fun `脏键——写入合法值即可恢复，不需要清数据`() {
        val prefs = FakePrefs()
        prefs.putRaw(PageTransitionSetting.KEY, 1)
        assertTrue(PageTransitionSetting.readEnabled(prefs))
        PageTransitionSetting.writeEnabled(prefs, false)
        assertFalse(PageTransitionSetting.readEnabled(prefs))
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. 关闭 = 0ms（铁律 17「关闭时不能有残留动画计算」）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `关闭时时长严格为 0——不是 1ms、不是负数`() {
        assertEquals(0, PageTransitionSetting.durationMs(enabled = false))
    }

    @Test
    fun `开启时时长就是 AppMotion 的页面转场时长（唯一真相）`() {
        assertEquals(AppMotion.PAGE_TRANSITION_MS, PageTransitionSetting.durationMs(enabled = true))
        // 顺带把常量本身钉住：改它必须是有意识的，而不是顺手。
        assertEquals(260, AppMotion.PAGE_TRANSITION_MS)
    }

    @Test
    fun `转场规格的时长与曲线来自 AppMotion 而不是调用点自己拍`() {
        val spec = AppMotion.pageTransitionSpec<Float>()
        assertEquals(AppMotion.PAGE_TRANSITION_MS, spec.durationMillis)
        assertEquals(AppMotion.pageTransitionEasing, spec.easing)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 测试替身
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 内存版 SharedPreferences（与 `LyricsSourcePrefsTest.FakePrefs` 同一份实现思路）。
     *
     * [getBoolean] 刻意写成「取出后硬转 Boolean」—— 与 Android 原实现一致，
     * 键里存了别的类型时抛 `ClassCastException`，用来验证读函数的回落行为。
     */
    private class FakePrefs(
        private val values: MutableMap<String, Any?> = mutableMapOf()
    ) : SharedPreferences {

        /** 绕过 Editor 直接写真值，专门用来制造「键里是另一种类型」的脏数据。 */
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

    /** apply() 立即落盘（这里"盘"就是内存 map），够测读写往返。 */
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
            if (pendingClear) values.clear()
            pending.forEach { (k, v) -> if (v === REMOVED) values.remove(k) else values[k] = v }
        }

        private companion object {
            val REMOVED = Any()
        }
    }
}
