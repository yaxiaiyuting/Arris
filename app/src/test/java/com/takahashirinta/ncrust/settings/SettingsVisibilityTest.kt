/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0 设置项依赖门控的单测（纯 JVM，无 Robolectric、无 Android framework）。
 */

package com.takahashirinta.ncrust.settings

import com.takahashirinta.ncrust.lyric.LyricsWordAnimationMode
import com.takahashirinta.ncrust.ui.settings.GatingReason
import com.takahashirinta.ncrust.ui.settings.SettingsAvailability
import com.takahashirinta.ncrust.ui.settings.SettingsEntry
import com.takahashirinta.ncrust.ui.settings.SettingsRegistry
import com.takahashirinta.ncrust.ui.settings.SettingsVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * probe-settings-inventory §4 的依赖门控，逐条断言。
 *
 * 语义要点（也是本文件要证的东西）：门控**只**影响「可见 / 可用」，
 * 不改 key、不改默认值、没有任何写盘路径 —— [gatingNeverWritesAndNeverTouchesUnrelatedKeys]
 * 用「只读取值函数 + 断言 map 原样」把这条钉死。
 */
class SettingsVisibilityTest {

    /** 只读查询：只读 map 里有的键，其余返回 null（= 键缺失）。 */
    private fun reader(values: Map<String, Any?>): (String) -> Any? = { key -> values[key] }

    private fun bare(): (String) -> Any? = reader(emptyMap())

    private fun entry(id: String): SettingsEntry = requireNotNull(SettingsRegistry.entryById(id))

    // ── 1. lyrics_ttml_first 硬依赖 lyrics_ttml_enabled（§4.2）───────────────────────

    @Test
    fun `ttmlFirstIsMountableOnlyWhenTtmlSourceIsEnabled`() {
        // 键缺失 → 用 registry 默认值 true → 可见可用（老用户升级后零行为变化）
        assertTrue(SettingsVisibility.isVisible("lyrics_ttml_first", bare()))
        assertTrue(SettingsVisibility.isEnabled("lyrics_ttml_first", bare()))

        // 显式 true → 同上
        val on = reader(mapOf("lyrics_ttml_enabled" to true))
        assertEquals(SettingsAvailability.FREE, SettingsVisibility.availabilityOf("lyrics_ttml_first", on))

        // 显式 false → **整行不挂载**（不是 alpha 隐藏；现状 UserScreen.kt:598 的 if 就是这个语义）
        val off = reader(mapOf("lyrics_ttml_enabled" to false))
        val availability = SettingsVisibility.availabilityOf("lyrics_ttml_first", off)
        assertFalse(availability.visible)
        assertFalse(availability.enabled)
        assertEquals(GatingReason.TTML_SOURCE_DISABLED, availability.reason)
    }

    @Test
    fun `ttmlEnabledItselfIsNeverGated`() {
        // 前置项自己永远可见可用（否则会形成「打不开的前置」死锁）
        listOf(
            mapOf("lyrics_ttml_enabled" to false),
            mapOf("lyrics_ttml_first" to true),
            emptyMap(),
        ).forEach { values ->
            assertEquals(SettingsAvailability.FREE, SettingsVisibility.availabilityOf("lyrics_ttml_enabled", reader(values)))
        }
    }

    @Test
    fun `visibleEntriesOfLyricsDropsTtmlFirstWhenTheSourceIsOff`() {
        val visibleWithSource = SettingsVisibility.visibleEntriesOf("lyrics", bare()).map { it.id }
        assertTrue("lyrics_ttml_first" in visibleWithSource)

        val visibleWithoutSource = SettingsVisibility
            .visibleEntriesOf("lyrics", reader(mapOf("lyrics_ttml_enabled" to false)))
            .map { it.id }
        assertFalse("lyrics_ttml_first" in visibleWithoutSource)
        // 其余行一个都不能少
        assertEquals(visibleWithSource.filterNot { it == "lyrics_ttml_first" }, visibleWithoutSource)
    }

    // ── 2. lyrics_sweep_quality 软依赖 lyrics_word_animation（§4.1）─────────────────

    @Test
    fun `sweepQualityIsVisibleButDisabledWhenWordAnimationIsNotGradientSweep`() {
        // 默认（键缺失）= GRADIENT_SWEEP → 可用
        assertEquals(SettingsAvailability.FREE, SettingsVisibility.availabilityOf("lyrics_sweep_quality", bare()))

        LyricsWordAnimationMode.let { mode ->
            listOf(
                mode.GRADIENT_SWEEP to true,
                mode.HARD_CUT to false,
                mode.OFF to false,
            ).forEach { (value, usable) ->
                val availability = SettingsVisibility.availabilityOf(
                    "lyrics_sweep_quality",
                    reader(mapOf("lyrics_word_animation" to value)),
                )
                assertTrue("软依赖下这一行仍然可见（mode=$value）", availability.visible)
                assertEquals("mode=$value 的可用性", usable, availability.enabled)
                if (!usable) {
                    assertEquals(GatingReason.SWEEP_ANIMATION_INACTIVE, availability.reason)
                }
            }
        }
    }

    @Test
    fun `sweepQualityFollowsTheReadPathForDirtyValues`() {
        // 盘上越界脏值：真实读路径 LyricsDisplayPrefs.readWordAnimation 会 normalize 成
        // GRADIENT_SWEEP（LyricsWordAnimationMode.normalize），门控不能比读路径更严。
        listOf(3, 7, -1, 99).forEach { dirty ->
            val availability = SettingsVisibility.availabilityOf(
                "lyrics_sweep_quality",
                reader(mapOf("lyrics_word_animation" to dirty)),
            )
            assertEquals("脏值 $dirty 应回落默认模式", SettingsAvailability.FREE, availability)
        }
        // 类型不符（键里是 String）同样回落默认
        assertEquals(
            SettingsAvailability.FREE,
            SettingsVisibility.availabilityOf("lyrics_sweep_quality", reader(mapOf("lyrics_word_animation" to "OFF"))),
        )
    }

    @Test
    fun `sweepQualityStaysVisibleSoTheUserCanSeeWhyItDoesNothing`() {
        val visible = SettingsVisibility
            .visibleEntriesOf("lyrics", reader(mapOf("lyrics_word_animation" to LyricsWordAnimationMode.OFF)))
            .map { it.id }
        assertTrue("软依赖项必须仍在渲染列表里（置灰 + 提示，而不是消失）", "lyrics_sweep_quality" in visible)
    }

    // ── 3. v2.8.0：C 档细分开关依赖 showcase 且仅 tier==2 可用 ──────────────────────

    @Test
    fun `showcaseDetailsNeedBothTierTwoAndTheShowcaseSwitch`() {
        val showcaseOn = mapOf(
            "visualizer_tier" to SettingsVisibility.TIER_SHOWCASE,
            "visualizer_showcase" to true,
        )
        SettingsVisibility.SHOWCASE_DETAIL_IDS.forEach { id ->
            assertEquals("$id 在 T2 + showcase 开时应可用", SettingsAvailability.FREE, SettingsVisibility.availabilityOf(id, reader(showcaseOn)))
        }

        val showcaseOff = mapOf(
            "visualizer_tier" to SettingsVisibility.TIER_SHOWCASE,
            "visualizer_showcase" to false,
        )
        SettingsVisibility.SHOWCASE_DETAIL_IDS.forEach { id ->
            val availability = SettingsVisibility.availabilityOf(id, reader(showcaseOff))
            assertTrue("$id 在 showcase 关时仍应可见", availability.visible)
            assertFalse("$id 在 showcase 关时不可用", availability.enabled)
            assertEquals(GatingReason.SHOWCASE_DISABLED, availability.reason)
        }
    }

    @Test
    fun `showcaseDetailsAreUnusableWhenTierIsNotTwoEvenWithShowcaseOn`() {
        listOf(0, 1, SettingsVisibility.TIER_DEFAULT).forEach { tier ->
            val values = mapOf("visualizer_tier" to tier, "visualizer_showcase" to true)
            SettingsVisibility.SHOWCASE_DETAIL_IDS.forEach { id ->
                val availability = SettingsVisibility.availabilityOf(id, reader(values))
                assertFalse("tier=$tier 时 $id 必须不可用", availability.enabled)
                assertEquals(GatingReason.TIER_NOT_SHOWCASE_CAPABLE, availability.reason)
            }
        }
    }

    @Test
    fun `tierAndShowcaseDefaultsKeepAllShowcaseFeaturesOff`() {
        // 键全缺失（老用户）→ 档位默认 T1、showcase 默认 false ⇒ 4 个细分项一律不可用
        SettingsVisibility.SHOWCASE_DETAIL_IDS.forEach { id ->
            val availability = SettingsVisibility.availabilityOf(id, bare())
            assertFalse(availability.enabled)
            assertEquals(GatingReason.TIER_NOT_SHOWCASE_CAPABLE, availability.reason)
        }
        assertEquals(SettingsVisibility.TIER_DEFAULT, SettingsVisibility.visualizerTier(bare()))
    }

    @Test
    fun `showcaseSwitchItselfNeedsTierTwo`() {
        // 推断规则（任务书只写了「4 个细分开关仅 tier==2 可用」）：档位不到 T2 时打开
        // showcase 也不会有效果 ⇒ 可见但不可用，避免死开关。
        assertEquals(
            SettingsAvailability.FREE,
            SettingsVisibility.availabilityOf("visualizer_showcase", reader(mapOf("visualizer_tier" to 2))),
        )
        listOf(0, 1).forEach { tier ->
            val availability = SettingsVisibility.availabilityOf(
                "visualizer_showcase",
                reader(mapOf("visualizer_tier" to tier)),
            )
            assertTrue(availability.visible)
            assertFalse(availability.enabled)
            assertEquals(GatingReason.TIER_NOT_SHOWCASE_CAPABLE, availability.reason)
        }
    }

    @Test
    fun `tierIsNormalizedForDirtyValues`() {
        assertEquals(1, SettingsVisibility.visualizerTier(bare()))
        listOf(-1, 3, 7, 99).forEach { dirty ->
            assertEquals("脏值 $dirty 应回落 TIER_DEFAULT", SettingsVisibility.TIER_DEFAULT, SettingsVisibility.visualizerTier(reader(mapOf("visualizer_tier" to dirty))))
        }
        // 合法值原样保留
        listOf(0, 1, 2).forEach { tier ->
            assertEquals(tier, SettingsVisibility.visualizerTier(reader(mapOf("visualizer_tier" to tier))))
        }
        // 类型不符 → 回落默认
        assertEquals(
            SettingsVisibility.TIER_DEFAULT,
            SettingsVisibility.visualizerTier(reader(mapOf("visualizer_tier" to "2"))),
        )
    }

    @Test
    fun `visualizerTierItselfAndTheMasterSwitchAreNeverGated`() {
        // 档位与总开关是这套门控的**输入**，不能被自己门控（否则永远打不开）
        listOf(
            emptyMap(),
            mapOf("visualizer_showcase" to false),
            mapOf("visualizer_tier" to 0),
        ).forEach { values ->
            assertEquals(SettingsAvailability.FREE, SettingsVisibility.availabilityOf("visualizer_tier", reader(values)))
            assertEquals(SettingsAvailability.FREE, SettingsVisibility.availabilityOf("audio_visualizer", reader(values)))
        }
    }

    // ── 4. 兜底与边界 ─────────────────────────────────────────────────────────────────

    @Test
    fun `unknownEntryIdFallsBackToFreeWithoutThrowing`() {
        // 与 AccentSource.kt:36 的 getOrDefault(PRESET) 同一条纪律：坏输入最坏只是多显示一行。
        assertEquals(SettingsAvailability.FREE, SettingsVisibility.availabilityOf("no.such.entry", bare()))
    }

    @Test
    fun `unrelatedKeysNeverAffectGating`() {
        val noisy = reader(
            mapOf(
                "audio_visualizer" to false,
                "wifi_quality" to 0,
                "theme_mode" to "DARK",
                "gapless_playback" to false,
                "visualizer_tier" to 2,
                "visualizer_showcase" to true,
            )
        )
        // 上面塞了一堆无关键（含 tier=2 / showcase=true），都不该影响歌词那两条的判定
        assertEquals(SettingsAvailability.FREE, SettingsVisibility.availabilityOf("lyrics_ttml_first", noisy))
        assertEquals(SettingsAvailability.FREE, SettingsVisibility.availabilityOf("lyrics_sweep_quality", noisy))
    }

    @Test
    fun `renderableEntriesExcludeInternalOnes`() {
        SettingsRegistry.groups().forEach { group ->
            val renderable = SettingsVisibility.renderableEntriesOf(group.id)
            assertTrue("内部项不得出现在渲染列表里：${group.id}", renderable.none { it.isInternal })
            assertEquals(
                SettingsRegistry.entriesOf(group.id).count { !it.isInternal },
                renderable.size,
            )
        }
        val lyrics = SettingsVisibility.renderableEntriesOf("lyrics").map { it.id }
        assertFalse("lyrics_word_by_word" in lyrics)
        assertFalse("lyrics_sweep_fade_em" in lyrics)
        assertTrue("lyrics_word_animation" in lyrics)
    }

    @Test
    fun `gatingNeverWritesAndNeverTouchesUnrelatedKeys`() {
        // 只读查询函数：门控拿不到写入能力；这里再证「只读了依赖链上的那几个键」。
        val values = mapOf("lyrics_word_animation" to LyricsWordAnimationMode.OFF)
        val reads = mutableListOf<String>()
        val probing: (String) -> Any? = { key -> reads += key; values[key] }

        val availability = SettingsVisibility.availabilityOf("lyrics_sweep_quality", probing)
        assertEquals(setOf("lyrics_word_animation"), reads.toSet())
        assertEquals(GatingReason.SWEEP_ANIMATION_INACTIVE, availability.reason)

        // 默认值不受门控影响：registry 里声明的东西一个字都没变。
        assertEquals(
            LyricsWordAnimationMode.GRADIENT_SWEEP,
            SettingsRegistry.entryById("lyrics_word_animation")!!.defaultValue,
        )
        assertEquals(true, SettingsRegistry.entryById("lyrics_ttml_first")!!.defaultValue)
    }

    @Test
    fun `everyEntryGatesWithoutThrowingOnEmptyOrDirtyPrefs`() {
        // 脏 prefs：Int 写成 -42、Boolean 写成 String —— 门控必须像真实读路径一样回落默认值，
        // 一条都不许抛异常（脏键最坏只是多显示一行，不该让设置页崩）。
        val dirty: Map<String, Any?> = SettingsRegistry.allEntries()
            .mapNotNull { it.key }
            .associateWith { key ->
                when (key) {
                    "visualizer_tier", "lyrics_word_animation" -> -42
                    "lyrics_ttml_enabled", "visualizer_showcase", "visualizer_shockwave" -> "yes"
                    "theme_color_index", "offline_cache_mb" -> 999_999
                    else -> null
                }
            }
        SettingsRegistry.allEntries().forEach { e ->
            listOf(emptyMap<String, Any?>(), dirty).forEach { prefs ->
                val availability = SettingsVisibility.availabilityOf(e, reader(prefs))
                // 唯一的「隐藏」规则是 lyrics_ttml_first 的硬依赖；脏值下它走默认值 true ⇒ 仍可见。
                assertTrue("${e.id} 不应因为脏 prefs 被隐藏", availability.visible)
                assertTrue(
                    "${e.id} 的 reason 与 visible 必须自洽",
                    availability.visible || availability.reason != GatingReason.NONE,
                )
            }
        }
    }
}
