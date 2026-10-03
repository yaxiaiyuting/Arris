/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0「界面律动独立开关」的**依赖门控**单测（纯 JVM，无 Android 运行时）。
 */

package com.takahashirinta.ncrust.settings

import com.takahashirinta.ncrust.ui.settings.GatingReason
import com.takahashirinta.ncrust.ui.settings.SettingsRegistry
import com.takahashirinta.ncrust.ui.settings.SettingsRenderPlan
import com.takahashirinta.ncrust.ui.settings.SettingsRowKind
import com.takahashirinta.ncrust.ui.settings.SettingsVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.0：**界面律动那一层的三层门控**（总闸 → 律动闸 → 档位）。
 *
 * ## 为什么门控必须走「不挂载 / 置灰 + 原因」而不是 alpha
 *
 * AGENTS.md 的 Compose 触摸陷阱第 1 条：`alpha = 0` 不会退出命中测试 ——
 * 看不见的开关照样能点，于是「为什么我点了一个置灰的开关它居然生效了」这类 bug
 * 只会在真机上出现。所以本文件的判据是 `SettingsAvailability.visible` / `.enabled`，
 * 与渲染侧的挂载判据**是同一个函数**（`SettingsRenderPlan.rowsOf`）。
 *
 * ## 与渲染侧的关系（同一份语义，两处断言）
 *
 * 这里断言的是**设置页**表现；能力位那一侧由 `MotionEffectsTest` 逐项断言
 * （`总闸关掉时九个独立开关一个都救不回来` / `关掉律动总闸只掐律动类其余一概不受影响`）。
 * 两边用的是同一个键、同一个默认值、同一条公式：
 * **有效 = 档位允许 AND 总闸 AND (律动类 ? 律动闸 : true) AND 逐项开关**。
 */
class MotionRhythmGatingTest {

    /** 默认盘（什么都没有 ⇒ 走 registry 的期望默认值）。 */
    private fun defaults(): MutableMap<String, Any?> = mutableMapOf(
        "motion_tier" to 1, // 精致档（用户选过，避免依赖设备判据）
        "ui_motion_enabled" to true,
        "motion_rhythm_enabled" to true,
        "motion_breathing" to true,
        "motion_cover_float" to true,
        "motion_lyric_pulse" to true,
        "motion_bar_pulse" to true,
        "motion_shockwave" to true,
        "motion_halo" to true,
        "motion_particles" to true,
        "motion_wave_bands" to true,
    )

    private fun read(map: Map<String, Any?>): (String) -> Any? = { key -> map[key] }

    private fun reasonOf(id: String, map: Map<String, Any?>): GatingReason =
        SettingsVisibility.availabilityOf(id, read(map)).reason

    // ── 1. 无门控时全部可用 ──────────────────────────────────────────────────────────

    @Test
    fun `默认盘下九个动效开关全部可用`() {
        SettingsVisibility.MOTION_SWITCH_IDS.forEach { id ->
            val a = SettingsVisibility.availabilityOf(id, read(defaults()))
            assertTrue("$id 应可见", a.visible)
            assertTrue("$id 应可用", a.enabled)
            assertEquals("$id 不该有门控原因", GatingReason.NONE, a.reason)
        }
    }

    @Test
    fun `四个开关确实落在律动类名单里`() {
        // 归类判据：渲染路径里是否读 MotionClock.pulse()/level()（probe-ui-jitter.md §5）。
        assertEquals(
            setOf("motion_breathing", "motion_cover_float", "motion_lyric_pulse", "motion_bar_pulse"),
            SettingsVisibility.RHYTHM_DETAIL_IDS,
        )
        assertTrue(
            "律动类必须是动效开关集合的子集",
            SettingsVisibility.MOTION_SWITCH_IDS.containsAll(SettingsVisibility.RHYTHM_DETAIL_IDS),
        )
        assertEquals("v3.0.0 五项 + v3.2.0 四项", 9, SettingsVisibility.MOTION_SWITCH_IDS.size)
        assertFalse(
            "冲击波/光晕/粒子/多频段波形**不是**律动类（关律动闸不许碰它们）",
            SettingsVisibility.RHYTHM_DETAIL_IDS.any {
                it in setOf("motion_shockwave", "motion_halo", "motion_particles", "motion_wave_bands")
            },
        )
    }

    // ── 2. 第一层：界面动效总闸 ─────────────────────────────────────────────────────

    @Test
    fun `界面动效总闸关掉时九个开关全部置灰并给出原因`() {
        val map = defaults().apply { put("ui_motion_enabled", false) }
        SettingsVisibility.MOTION_SWITCH_IDS.forEach { id ->
            val a = SettingsVisibility.availabilityOf(id, read(map))
            assertTrue("$id 仍然可见（隐藏会让用户以为被删了）", a.visible)
            assertFalse("$id 必须置灰", a.enabled)
            assertEquals("$id 的原因", GatingReason.UI_MOTION_DISABLED, a.reason)
        }
    }

    @Test
    fun `总闸关掉时不会顺手把行从页面上拿掉`() {
        val map = defaults().apply { put("ui_motion_enabled", false) }
        val rows = SettingsRenderPlan.rowsOf("appearance", read(map)).map { it.id }
        SettingsVisibility.MOTION_SWITCH_IDS.forEach {
            assertTrue("$it 必须仍在页面上（置灰而不是消失）", it in rows)
        }
    }

    // ── 3. 第二层：界面律动总闸 ─────────────────────────────────────────────────────

    @Test
    fun `律动闸关掉时只有四个律动类细开关置灰`() {
        val map = defaults().apply { put("motion_rhythm_enabled", false) }
        SettingsVisibility.RHYTHM_DETAIL_IDS.forEach { id ->
            val a = SettingsVisibility.availabilityOf(id, read(map))
            assertFalse("$id 必须置灰", a.enabled)
            assertEquals("$id 的原因", GatingReason.RHYTHM_DISABLED, a.reason)
        }
        // 非律动类**一个都不受影响**（这是「律动闸」与「总闸」的分界线）。
        listOf("motion_shockwave", "motion_halo", "motion_particles", "motion_wave_bands").forEach { id ->
            val a = SettingsVisibility.availabilityOf(id, read(map))
            assertTrue("$id 不该被律动闸影响", a.enabled)
            assertEquals("$id 不该有门控原因", GatingReason.NONE, a.reason)
        }
    }

    @Test
    fun `律动闸自己不被律动闸门控`() {
        // 总闸是策略开关：它只受「界面动效」总闸约束，不随档位/自己置灰 ——
        // 否则用户会掉进"关掉它之后就再也打不开"的死锁。
        val map = defaults().apply { put("motion_rhythm_enabled", false) }
        val a = SettingsVisibility.availabilityOf("motion_rhythm_enabled", read(map))
        assertTrue("关掉自己之后仍然可用（能再打开）", a.enabled)
    }

    // ── 4. 第三层：档位（简洁档是静态档 ⇒ 逐帧开关都是死开关）──────────────────────

    @Test
    fun `简洁档下九个开关里除了律动闸之外全部置灰`() {
        // P0-B 的连带后果：简洁档现在没有任何节拍驱动动效，那八个开关在那一档点了也没用。
        // 判据必须是"置灰 + 原因"，否则用户会以为"我关了冲击波怎么还抖"
        // （真实症状：抖的是封面浮动，而它当时根本没有开关 —— 探针 §3/§6）。
        val map = defaults().apply { put("motion_tier", 0) }
        (SettingsVisibility.MOTION_SWITCH_IDS - "motion_rhythm_enabled").forEach { id ->
            val a = SettingsVisibility.availabilityOf(id, read(map))
            assertTrue("$id 仍可见", a.visible)
            assertFalse("$id 在简洁档必须置灰", a.enabled)
            assertEquals("$id 的原因", GatingReason.TIER_BASIC_ONLY, a.reason)
        }
        // 律动闸是策略开关：简洁档下仍然可操作（用户可以先关掉它再调到精致档）。
        assertTrue(
            "律动闸不随档位置灰",
            SettingsVisibility.availabilityOf("motion_rhythm_enabled", read(map)).enabled,
        )
    }

    @Test
    fun `精致与炫技档下开关都可用`() {
        for (tier in listOf(1, 2)) {
            val map = defaults().apply { put("motion_tier", tier) }
            SettingsVisibility.MOTION_SWITCH_IDS.forEach { id ->
                assertTrue("档位 $tier 的 $id 应可用", SettingsVisibility.isEnabled(id, read(map)))
            }
        }
    }

    @Test
    fun `门控的优先级是总闸然后律动闸然后档位`() {
        // 三层同时不满足时，报出来的原因必须是最外层那一个（用户先看到"总闸关着"才有意义）。
        val map = defaults().apply {
            put("ui_motion_enabled", false)
            put("motion_rhythm_enabled", false)
            put("motion_tier", 0)
        }
        assertEquals(
            GatingReason.UI_MOTION_DISABLED,
            reasonOf("motion_cover_float", map),
        )
        map["ui_motion_enabled"] = true
        assertEquals(
            GatingReason.RHYTHM_DISABLED,
            reasonOf("motion_cover_float", map),
        )
        map["motion_rhythm_enabled"] = true
        assertEquals(
            GatingReason.TIER_BASIC_ONLY,
            reasonOf("motion_cover_float", map),
        )
    }

    // ── 5. 读路径与默认值的口径一致 ─────────────────────────────────────────────────

    @Test
    fun `缺 key 时按 registry 的期望默认值判定为可用`() {
        // 只给档位，其余全缺 ⇒ 全部走默认值（开关默认开）⇒ 可用。
        val map = mapOf<String, Any?>("motion_tier" to 1)
        SettingsVisibility.MOTION_SWITCH_IDS.forEach { id ->
            assertTrue("$id 缺 key 时应视为开", SettingsVisibility.isEnabled(id, read(map)))
        }
    }

    @Test
    fun `档位脏值不会把开关误判成死开关`() {
        // `motion_tier` 的越界值在门控里一律回落精致档（与 `MotionPrefs.readTier` 的口径一致）——
        // 坏数据最坏只是多显示一行可用开关，不该把整页动效设置锁死。
        for (bad in listOf(-1, 3, 99, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val map = defaults().apply { put("motion_tier", bad) }
            SettingsVisibility.MOTION_SWITCH_IDS.forEach { id ->
                assertTrue("脏档位 $bad 下 $id 不该被锁死", SettingsVisibility.isEnabled(id, read(map)))
            }
        }
    }

    @Test
    fun `四个新键都是可见开关且默认开`() {
        listOf(
            "motion_rhythm_enabled",
            "motion_cover_float",
            "motion_lyric_pulse",
            "motion_bar_pulse",
        ).forEach { id ->
            val entry = requireNotNull(SettingsRegistry.entryById(id)) { "registry 里没有 $id" }
            assertEquals("$id 的控件形态", SettingsRowKind.SWITCH, SettingsRenderPlan.rowKindOf(entry))
            assertEquals("$id 默认开（缺 key = 与档位表一致）", true, entry.defaultValue)
            assertFalse("$id 是可见项", entry.isInternal)
        }
    }
}
