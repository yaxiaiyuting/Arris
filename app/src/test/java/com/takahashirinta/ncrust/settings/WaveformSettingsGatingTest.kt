/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0「设置界面二级菜单重构」：波形 7 项在设置页上的**门控与回显**单测（纯 JVM）。
 */

package com.takahashirinta.ncrust.settings

import android.content.SharedPreferences
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerPrefs
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerTier
import com.takahashirinta.ncrust.ui.settings.GatingReason
import com.takahashirinta.ncrust.ui.settings.SettingsAvailability
import com.takahashirinta.ncrust.ui.settings.SettingsRegistry
import com.takahashirinta.ncrust.ui.settings.SettingsRenderPlan
import com.takahashirinta.ncrust.ui.settings.SettingsRowKind
import com.takahashirinta.ncrust.ui.settings.SettingsVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务书要求的两件事，逐条断言：
 *
 * 1. **C 档细分开关门控**：`tier != 2` 或 `visualizer_showcase == false` 时不可用
 *    （「置灰 + 原因」，**不是**隐藏 —— 隐藏会让人以为设置项消失了）；
 * 2. **低端机默认档回显为 0 且不落盘**：设置页显示「当前档位」用的是
 *    `VisualizerPrefs.deviceDefaultTier(context)` + `hasExplicitTier(prefs)` 解析出来的值，
 *    这个解析**只读**，一个键都不许写回去（写回去 = 「没选过」这个信息永久丢失，
 *    用户以后换台内存更大的设备也永远停在老默认上）。
 */
class WaveformSettingsGatingTest {

    private fun reader(values: Map<String, Any?>): (String) -> Any? = { key -> values[key] }

    private fun bare(): (String) -> Any? = reader(emptyMap())

    // ── 1. 门控 ──────────────────────────────────────────────────────────────────────

    @Test
    fun `showcaseDetailSwitchesAreDisabledOutsideTheShowcaseTier`() {
        listOf(0, 1, 7, -1).forEach { tier ->
            SettingsVisibility.SHOWCASE_DETAIL_IDS.forEach { id ->
                val availability = SettingsVisibility.availabilityOf(
                    id,
                    reader(mapOf("visualizer_tier" to tier, "visualizer_showcase" to true)),
                )
                assertTrue("$id 在档位 $tier 下仍应可见（置灰而不是隐藏）", availability.visible)
                assertFalse("$id 在档位 $tier 下必须不可用", availability.enabled)
                assertEquals(GatingReason.TIER_NOT_SHOWCASE_CAPABLE, availability.reason)
            }
        }
    }

    @Test
    fun `showcaseDetailSwitchesAreDisabledWhenTheMasterSwitchIsOff`() {
        SettingsVisibility.SHOWCASE_DETAIL_IDS.forEach { id ->
            val availability = SettingsVisibility.availabilityOf(
                id,
                reader(mapOf("visualizer_tier" to VisualizerTier.SHOWCASE, "visualizer_showcase" to false)),
            )
            assertTrue(availability.visible)
            assertFalse("$id 在炫技总开关关闭时必须不可用", availability.enabled)
            assertEquals(GatingReason.SHOWCASE_DISABLED, availability.reason)
        }
    }

    @Test
    fun `showcaseDetailSwitchesAreFreeOnlyAtTierTwoWithTheMasterSwitchOn`() {
        SettingsVisibility.SHOWCASE_DETAIL_IDS.forEach { id ->
            assertEquals(
                "$id 在 T2 + 总开关开时必须可用",
                SettingsAvailability.FREE,
                SettingsVisibility.availabilityOf(
                    id,
                    reader(mapOf("visualizer_tier" to VisualizerTier.SHOWCASE, "visualizer_showcase" to true)),
                ),
            )
        }
    }

    @Test
    fun `showcaseMasterSwitchItselfIsDisabledOutsideTierTwo`() {
        // 档位不是 T2 时打开总开关没有任何效果 ⇒ 可见但不可用（避免死开关）
        val availability = SettingsVisibility.availabilityOf(
            "visualizer_showcase",
            reader(mapOf("visualizer_tier" to VisualizerTier.REFINED)),
        )
        assertTrue(availability.visible)
        assertFalse(availability.enabled)
        assertEquals(GatingReason.TIER_NOT_SHOWCASE_CAPABLE, availability.reason)

        assertEquals(
            SettingsAvailability.FREE,
            SettingsVisibility.availabilityOf(
                "visualizer_showcase",
                reader(mapOf("visualizer_tier" to VisualizerTier.SHOWCASE)),
            ),
        )
    }

    @Test
    fun `tierAndShowcaseAlwaysStayVisibleSoTheGatingCanBeExplained`() {
        // 「置灰 + 原因」的前提是这两行永远在页面上（否则用户无法把档位改成 T2 来解锁细分项）
        listOf(0, 1, 2, 99).forEach { tier ->
            listOf("visualizer_tier", "visualizer_showcase").forEach { id ->
                assertTrue(
                    "$id 在档位 $tier 下必须仍然可见",
                    SettingsVisibility.isVisible(id, reader(mapOf("visualizer_tier" to tier))),
                )
            }
        }
    }

    // ── 2. 低端机默认档：回显 0，且不落盘 ────────────────────────────────────────────

    @Test
    fun `lowEndDevicesResolveToTheSimpleTier`() {
        // 三条静态判据（低内存 / 3 GB 级 / API < 26）各自就能把默认档压到简洁档
        assertEquals(
            VisualizerTier.SIMPLE,
            VisualizerTier.defaultTier(isLowRamDevice = true, totalMemBytes = 0L, sdkInt = 34),
        )
        assertEquals(
            VisualizerTier.SIMPLE,
            VisualizerTier.defaultTier(
                isLowRamDevice = false,
                totalMemBytes = 3L * 1024 * 1024 * 1024, // 3 GiB < 3.5 GiB
                sdkInt = 34,
            ),
        )
        assertEquals(
            VisualizerTier.SIMPLE,
            VisualizerTier.defaultTier(isLowRamDevice = false, totalMemBytes = 8L * 1024 * 1024 * 1024, sdkInt = 25),
        )
        // 取不到内存信息（0）视为未知 ⇒ 不判低端机（宁可给默认档，也不要因一次读失败锁进简洁档）
        assertEquals(
            VisualizerTier.REFINED,
            VisualizerTier.defaultTier(isLowRamDevice = false, totalMemBytes = 0L, sdkInt = 34),
        )
    }

    @Test
    fun `lowEndDeviceEchoesSimpleTierWithoutWritingItBack`() {
        val prefs = FakePrefs()
        val lowEndDefault = VisualizerTier.defaultTier(
            isLowRamDevice = false,
            totalMemBytes = 3L * 1024 * 1024 * 1024,
            sdkInt = 34,
        )
        assertEquals("低端机的解析默认档必须是简洁档", VisualizerTier.SIMPLE, lowEndDefault)

        // 回显：键缺失 ⇒ 解析默认档（0），并且**没有**变成一次写入
        assertFalse("键缺失不能被解读成用户选过", VisualizerPrefs.hasExplicitTier(prefs))
        assertEquals(VisualizerTier.SIMPLE, VisualizerPrefs.readTier(prefs, lowEndDefault))
        assertEquals("回显不得往盘上写任何东西", emptyMap<String, Any?>(), prefs.getAll().toMap())
        assertNull("回显后 visualizer_tier 仍然必须是「没选过」", prefs.getAll()["visualizer_tier"])

        // 用户显式选了精致档 ⇒ 以用户值为准（不再回落低端默认）
        VisualizerPrefs.writeTier(prefs, VisualizerTier.REFINED)
        assertTrue(VisualizerPrefs.hasExplicitTier(prefs))
        assertEquals(VisualizerTier.REFINED, VisualizerPrefs.readTier(prefs, lowEndDefault))

        // 清掉键（模拟「没选过」）后回显又回到解析默认档
        prefs.edit().remove(VisualizerPrefs.KEY_TIER).apply()
        assertEquals(VisualizerTier.SIMPLE, VisualizerPrefs.readTier(prefs, lowEndDefault))
    }

    @Test
    fun `tierValueOnDiskWinsEvenWhenItIsHigherThanTheDeviceDefault`() {
        // 低端机用户手动选 T2 ⇒ 设置页必须显示炫技（而不是「被系统按设备判据改回去」）
        val prefs = FakePrefs()
        prefs.putRaw(VisualizerPrefs.KEY_TIER, VisualizerTier.SHOWCASE)
        assertEquals(
            VisualizerTier.SHOWCASE,
            VisualizerPrefs.readTier(prefs, VisualizerTier.SIMPLE),
        )
        // 越界脏值回落**解析出的默认档**（不是常量 1 —— 那会把低端机顶到精致档）
        prefs.putRaw(VisualizerPrefs.KEY_TIER, 99)
        assertEquals(VisualizerTier.SIMPLE, VisualizerPrefs.readTier(prefs, VisualizerTier.SIMPLE))
    }

    // ── 3. 7 个波形键在二级页上的落位 ────────────────────────────────────────────────

    @Test
    fun `theSevenWaveformKeysLandOnThePlaybackPageWithTheExpectedControls`() {
        // v2.9.0 起：v2.8.0 的 8 个键**全部**降级为迁移源（`legacyV290` + `internal`）——
        // 它们仍然在 registry 里（防丢项 + 回滚不丢数据），但**一行都不渲染**：
        // 统一「动效强度」接管渲染之后，再给它们 UI 入口就是同一状态的两个可写入口（双轨）。
        val legacy = mapOf(
            "visualizer_tier" to SettingsRowKind.INTERNAL,
            "visualizer_showcase" to SettingsRowKind.INTERNAL,
            "visualizer_shockwave" to SettingsRowKind.INTERNAL,
            "visualizer_particles" to SettingsRowKind.INTERNAL,
            "visualizer_perspective" to SettingsRowKind.INTERNAL,
            "visualizer_drag" to SettingsRowKind.INTERNAL,
            "visualizer_tier_version" to SettingsRowKind.INTERNAL,
            // v2.8.0 的第 8 个键：v2.9.0 补进 registry（v2.8.0 时漏枚举，见 probe-tier-migration §1.3）。
            "visualizer_auto_downgraded" to SettingsRowKind.INTERNAL,
        )
        legacy.forEach { (id, kind) ->
            val entry = requireNotNull(SettingsRegistry.entryById(id)) { "registry 里没有 $id" }
            assertTrue("$id 必须标成 v2.8.0 新增项", entry.isNewInV280)
            assertTrue("$id 必须标成 v2.9.0 的迁移源", entry.legacyV290)
            assertEquals("$id 的主分组必须是播放与音质", "playback", entry.group.id)
            assertEquals("$id 的控件形态", kind, SettingsRenderPlan.rowKindOf(entry))
        }
        assertEquals(
            "v2.8.0 的 8 个键一个都不能出现在二级页上（全部由迁移逻辑消费）",
            emptyList<String>(),
            SettingsRenderPlan.plannedRowsOf("playback").map { it.id }
                .filter { it.startsWith("visualizer_") },
        )
        // 接替它们的两个可见项。
        assertEquals(
            "统一动效强度必须落在播放与音质页，且顺序是「档位 → 总开关」",
            listOf("motion_tier", "ui_motion_enabled"),
            SettingsRenderPlan.plannedRowsOf("playback").map { it.id }
                .filter { it.startsWith("motion_") || it == "ui_motion_enabled" },
        )
        val tierEntry = requireNotNull(SettingsRegistry.entryById("motion_tier"))
        assertEquals(SettingsRowKind.TIER_DROPDOWN, SettingsRenderPlan.rowKindOf(tierEntry))
        assertTrue("motion_tier 必须标成 v2.9.0 新增项", tierEntry.isNewInV290)
    }

    // ── 内存版 SharedPreferences（与 VisualizerPrefsTest / LyricsSourcePrefsTest 同形）────

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
