/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.testsupport

import android.content.SharedPreferences

/**
 * v2.9.0：**内存版 `SharedPreferences`**（跨多个测试类共用的测试替身）。
 *
 * 为什么不用 Robolectric：本仓库的 `testOptions.unitTests.isReturnDefaultValues = true`
 * 只把 Android 框架桩方法的返回值抹平，**不提供 prefs 实现**；而 prefs 读写逻辑只依赖
 * `SharedPreferences` 接口 —— 一个内存实现就够，还能精确复现「键里存了别的类型」
 * 这种真实脏数据（真机上 `getInt` 遇到它必抛 `ClassCastException`）。
 *
 * `getX` 刻意**硬转类型**（与 Android 原实现一致）：软转会把
 * 「类型不符 ⇒ 抛异常 ⇒ 被读路径吞掉 ⇒ 回落默认值」这条真实链路在测试里抹掉。
 *
 * 与 v2.8.0 的 `VisualizerPrefsTest` 里那个私有 FakePrefs 是同一个东西；
 * v2.9.0 起提到公共位置，供 `MotionPrefsTest` 与 `SettingsRegistryTest` 等复用。
 */
class MemoryPrefs(
    private val values: MutableMap<String, Any?> = mutableMapOf(),
) : SharedPreferences {

    /** 直接塞一个任意类型的值（模拟脏盘）。 */
    fun putRaw(key: String, value: Any?) {
        values[key] = value
    }

    /** 盘的**逐键快照**（迁移幂等断言用：跑两次迁移后必须一模一样）。 */
    fun snapshot(): Map<String, Any?> = LinkedHashMap(values)

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

    override fun edit(): SharedPreferences.Editor = MemoryEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private class MemoryEditor(
        private val values: MutableMap<String, Any?>,
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
