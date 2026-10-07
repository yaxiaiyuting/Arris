/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.9.0：TTML 歌词源两个偏好键（lyrics_ttml_enabled / lyrics_ttml_first）的单测。
 *
 * 为什么用手写内存 SharedPreferences 而不是 Robolectric：本仓库单测没有 Android 运行时
 * （`unitTests.isReturnDefaultValues = true` 只把框架桩的返回值抹平，不提供 prefs 实现），
 * 而这两个读写函数只依赖 SharedPreferences 接口 —— 一个内存实现就够，还能精确复现
 * 「同一个键里存了别的类型」这种真实脏数据（真机上 getBoolean 遇到它必抛 ClassCastException）。
 *
 * 覆盖三件事：默认值（键不存在）、显式值（写盘后读回）、非法值回落默认值。
 */
class LyricsSourcePrefsTest {

    @Test
    fun `默认值——两个键都不存在时都开启`() {
        val prefs = FakePrefs()
        assertTrue(LyricsDisplayPrefs.readTtmlEnabled(prefs))
        assertTrue(LyricsDisplayPrefs.readTtmlFirst(prefs))
    }

    @Test
    fun `显式值——写盘后能读回，两个键互不影响`() {
        val prefs = FakePrefs()
        LyricsDisplayPrefs.writeTtmlEnabled(prefs, false)
        LyricsDisplayPrefs.writeTtmlFirst(prefs, false)
        assertFalse(LyricsDisplayPrefs.readTtmlEnabled(prefs))
        assertFalse(LyricsDisplayPrefs.readTtmlFirst(prefs))

        // 只翻回一个，另一个保持 false —— 两个键共用同一个 prefs 文件，不能串。
        LyricsDisplayPrefs.writeTtmlEnabled(prefs, true)
        assertTrue(LyricsDisplayPrefs.readTtmlEnabled(prefs))
        assertFalse(LyricsDisplayPrefs.readTtmlFirst(prefs))
    }

    @Test
    fun `非法值——键里是别的类型时回落默认 true，不抛异常`() {
        val prefs = FakePrefs()
        // 模拟 prefs 被写坏：同一个键塞了字符串 / 整数。真机 SharedPreferences.getBoolean
        // 在这里会抛 ClassCastException，读函数必须吞掉它、回落默认值。
        prefs.putRaw(LyricsDisplayPrefs.KEY_TTML_ENABLED, "true")
        prefs.putRaw(LyricsDisplayPrefs.KEY_TTML_FIRST, 1)
        assertTrue(LyricsDisplayPrefs.readTtmlEnabled(prefs))
        assertTrue(LyricsDisplayPrefs.readTtmlFirst(prefs))
    }

    @Test
    fun `非法值——写坏后再写入合法值即可恢复，不需要清数据`() {
        val prefs = FakePrefs()
        prefs.putRaw(LyricsDisplayPrefs.KEY_TTML_ENABLED, "坏值")
        assertTrue(LyricsDisplayPrefs.readTtmlEnabled(prefs))
        LyricsDisplayPrefs.writeTtmlEnabled(prefs, false)
        assertFalse(LyricsDisplayPrefs.readTtmlEnabled(prefs))
    }

    @Test
    fun `键名是持久化契约——改名等于静默重置所有用户的选择`() {
        assertEquals("lyrics_ttml_enabled", LyricsDisplayPrefs.KEY_TTML_ENABLED)
        assertEquals("lyrics_ttml_first", LyricsDisplayPrefs.KEY_TTML_FIRST)
    }

    /**
     * 内存版 SharedPreferences。
     *
     * [getBoolean] 刻意写成「取出后硬转 Boolean」——与 Android 原实现一致，
     * 键里存了别的类型时抛 ClassCastException，用来验证读函数的回落行为。
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
