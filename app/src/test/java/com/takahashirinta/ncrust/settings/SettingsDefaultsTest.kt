/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0 设置项注册表：**默认值对齐**单测（纯 JVM，无 Robolectric）。
 */

package com.takahashirinta.ncrust.settings

import android.content.SharedPreferences
import com.takahashirinta.ncrust.KeepScreenOnSetting
import com.takahashirinta.ncrust.RotationSetting
import com.takahashirinta.ncrust.cache.OfflineAudioCache
import com.takahashirinta.ncrust.lyric.LyricsDisplayPrefs
import com.takahashirinta.ncrust.lyric.LyricsSweepConfig
import com.takahashirinta.ncrust.lyric.LyricsWordAnimationMode
import com.takahashirinta.ncrust.player.QualityLadder
import com.takahashirinta.ncrust.ui.i18n.languagePresets
import com.takahashirinta.ncrust.ui.player.VisualizerSetting
import com.takahashirinta.ncrust.ui.settings.SettingsRegistry
import com.takahashirinta.ncrust.ui.settings.SettingsVisibility
import com.takahashirinta.ncrust.ui.screen.LibrarySectionFold
import com.takahashirinta.ncrust.ui.screen.PlaylistLayout
import com.takahashirinta.ncrust.ui.theme.AccentSource
import com.takahashirinta.ncrust.ui.theme.PageTransitionSetting
import com.takahashirinta.ncrust.ui.theme.ThemeMode
import com.takahashirinta.ncrust.ui.theme.themeColorPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每个**迁移前既有条目**的默认值都必须与现有代码的单一真相一致。
 *
 * 做法分三档，优先用最硬的那一档：
 * 1. **真读路径**：歌词 7 项直接用 `LyricsDisplayPrefs.readXxx(空 prefs)` 的返回值
 *    （顺带覆盖了 `lyrics_word_animation` 的一次性迁移路径）；
 * 2. **生产常量**：其余有常量的项引用常量本身（`const val` 会被编译器内联，零运行时风险）；
 * 3. **逐字出处**：键的默认值内联在读取处、没有常量可引用时（多为裸 prefs 键），
 *    断言字面值并在注释里给出 `file:line` —— 这些**不是魔法值，是契约**。
 *
 * `lyrics_word_animation` 的默认值刻意**不**用 registry 自己的声明来自证：
 * 它必须等于真实读路径的结果（含迁移语义），否则老用户的三选一会显示错误。
 */
class SettingsDefaultsTest {

    private fun default(id: String): Any? =
        requireNotNull(SettingsRegistry.entryById(id)) { "registry 里没有 $id" }.defaultValue

    // ── 1. 真读路径（歌词 7 项）────────────────────────────────────────────────────────

    @Test
    fun `lyricsDefaultsMatchTheRealReadPaths`() {
        val prefs = FakePrefs()
        assertEquals(LyricsDisplayPrefs.readWordAnimation(prefs), default("lyrics_word_animation"))
        assertEquals(LyricsDisplayPrefs.readSweepQuality(prefs), default("lyrics_sweep_quality"))
        assertEquals(LyricsDisplayPrefs.readFontScale(prefs), default("lyrics_font_scale"))
        assertEquals(LyricsDisplayPrefs.readTtmlEnabled(prefs), default("lyrics_ttml_enabled"))
        assertEquals(LyricsDisplayPrefs.readTtmlFirst(prefs), default("lyrics_ttml_first"))
        assertEquals(LyricsDisplayPrefs.readRomanization(prefs), default("lyrics_romanization"))
        assertEquals(LyricsDisplayPrefs.readDynamicFont(prefs), default("lyrics_dynamic_font"))
    }

    @Test
    fun `lyricsChoiceTablesComeFromTheirSingleSource`() {
        assertEquals(
            listOf(
                LyricsWordAnimationMode.GRADIENT_SWEEP,
                LyricsWordAnimationMode.HARD_CUT,
                LyricsWordAnimationMode.OFF,
            ),
            SettingsRegistry.entryById("lyrics_word_animation")!!.choices,
        )
        assertEquals(
            LyricsDisplayPrefs.FONT_SCALE_STEPS,
            SettingsRegistry.entryById("lyrics_font_scale")!!.choices,
        )
        assertEquals(
            LyricsSweepConfig.DEFAULT.easing.ordinal,
            default("lyrics_sweep_easing"),
        )
    }

    @Test
    fun `legacyWordByWordDefaultIsTheMigrationSourceDefault`() {
        // lyric/LyricsDisplayPrefs.kt:137 —— 读时默认 true（老用户「开着逐字」）。
        assertEquals(true, default("lyrics_word_by_word"))
    }

    // ── 2. 生产常量 ───────────────────────────────────────────────────────────────────

    @Test
    fun `switchDefaultsMatchTheirProductionConstants`() {
        assertEquals(KeepScreenOnSetting.DEFAULT_ENABLED, default("keep_screen_on"))
        assertEquals(RotationSetting.DEFAULT_ENABLED, default("auto_rotate"))
        assertEquals(VisualizerSetting.DEFAULT_ENABLED, default("audio_visualizer"))
        assertEquals(PageTransitionSetting.DEFAULT_ENABLED, default("page_transition_enabled"))
        assertEquals(PlaylistLayout.DEFAULT_INDEX, default("library_playlist_layout"))
        assertEquals(LibrarySectionFold.ALL_EXPANDED.collapsedLocal, default("library_section_collapsed_local"))
        assertEquals(LibrarySectionFold.ALL_EXPANDED.collapsedNetease, default("library_section_collapsed_netease"))
        assertEquals(LibrarySectionFold.ALL_EXPANDED.collapsedQq, default("library_section_collapsed_qq"))
    }

    @Test
    fun `qualityAndCacheDefaultsMatchTheirConstants`() {
        // ui/screen/UserScreen.kt:146-147 的裸 prefs 默认值必须落在档位表的正确档上。
        assertEquals(QualityLadder.LEVELS.indexOf("lossless"), default("wifi_quality"))
        assertEquals(QualityLadder.LEVELS.indexOf("higher"), default("mobile_quality"))
        assertEquals(3, default("wifi_quality")) // 冗余但可读：3 = 无损
        assertEquals(1, default("mobile_quality")) // 1 = 较好

        // cache/OfflineAudioCache.kt:51/70 —— DEFAULT_MAX_BYTES 换算成 MB 就是读默认值。
        assertEquals((OfflineAudioCache.DEFAULT_MAX_BYTES / 1024 / 1024).toInt(), default("offline_cache_mb"))
        assertEquals(
            SettingsRegistry.OFFLINE_CACHE_MB_CHOICES,
            SettingsRegistry.entryById("offline_cache_mb")!!.choices,
        )
        assertTrue(
            "档位表必须落在合法区间内",
            SettingsRegistry.OFFLINE_CACHE_MB_CHOICES.all {
                it in OfflineAudioCache.MIN_MB..OfflineAudioCache.MAX_MB
            },
        )
        assertEquals(
            "档位表必须升序去重",
            SettingsRegistry.OFFLINE_CACHE_MB_CHOICES.sorted(),
            SettingsRegistry.OFFLINE_CACHE_MB_CHOICES,
        )
    }

    @Test
    fun `themeAndLanguageDefaultsMatchTheirEnums`() {
        assertEquals(ThemeMode.SYSTEM.name, default("theme_mode")) // ui/theme/ThemeManager.kt:46
        assertEquals(AccentSource.PRESET.name, default("accent_source")) // ui/theme/AccentSource.kt:36
        assertEquals(
            ThemeMode.entries.map { it.name },
            SettingsRegistry.entryById("theme_mode")!!.choices,
        )
        assertEquals(
            AccentSource.entries.map { it.name },
            SettingsRegistry.entryById("accent_source")!!.choices,
        )

        // ui/i18n/LanguageManager.kt:30 —— 默认 "zh-CN"，且必须是语言表里的第一项。
        assertEquals(languagePresets.first().code, default("language_code"))
        assertEquals(
            languagePresets.map { it.code },
            SettingsRegistry.entryById("language_code")!!.choices,
        )

        // ui/theme/ThemeManager.kt:61 —— getInt(KEY_THEME_INDEX, 0)；合法值 = 6 个预设的下标。
        assertEquals(0, default("theme_color_index"))
        assertEquals(
            themeColorPresets.indices.toList(),
            SettingsRegistry.entryById("theme_color_index")!!.choices,
        )
    }

    // ── 3. 内联默认值（无常量可引用，逐个给 file:line）────────────────────────────────

    @Test
    fun `barePrefDefaultsMatchTheInlineReadDefaults`() {
        assertEquals(true, default("gapless_playback")) // ui/screen/UserScreen.kt:148
        assertEquals(true, default("lyrics_translation")) // ui/screen/UserScreen.kt:156
        assertEquals(false, default("lyrics_in_media_session")) // ui/screen/UserScreen.kt:164
        assertEquals(false, default("custom_bg_enabled")) // ui/theme/BackgroundImageManager.kt:57
        assertEquals(false, default("artist_reco_enabled")) // reco/ArtistReco.kt:72 getBoolean(KEY_ENABLED, false)
        assertEquals(true, default("session_metadata_lyrics")) // player/PlaybackService.kt:1406
        assertEquals(true, default("live_update_enabled")) // player/LiveUpdateNotifier.kt:63
        assertEquals(false, default("live_update_probed")) // player/LiveUpdateNotifier.kt:64
        assertEquals(false, default("battery_prompt_done")) // MainActivity.kt:390
        assertEquals(0L, default("artist_reco_target_id")) // reco/ArtistReco.kt:78
        assertEquals("", default("artist_reco_anchor_ids")) // reco/ArtistReco.kt:84
        assertEquals("", default("artist_reco_auto_anchor_ids")) // reco/ArtistReco.kt:91
        assertEquals(0L, default("artist_reco_auto_anchor_at")) // reco/ArtistReco.kt:94
        assertEquals(1, default("quality_ladder_version")) // player/QualityLadder.kt:54 getInt(KEY_VERSION, 1)
        assertNull(default("user_cookie")) // auth/CookieManager.kt:19 getString(KEY_COOKIE, null)
        assertNull(default("qq_cookie")) // qq/QqAuthStore.kt:94 getString(KEY_COOKIE, null)
    }

    @Test
    fun `advancedSweepOverridesDefaultToTheSweepConfigDefault`() {
        // lyric/SweepTrack.kt:109/112/115 —— 三个高级覆盖键的默认值就是定稿值。
        assertEquals(LyricsSweepConfig.DEFAULT.fadeEm, default("lyrics_sweep_fade_em"))
        assertEquals(LyricsSweepConfig.DEFAULT.inactiveAlpha, default("lyrics_sweep_inactive_alpha"))
        assertEquals(LyricsSweepConfig.DEFAULT.easing.ordinal, default("lyrics_sweep_easing"))
    }

    // ── 4. v2.8.0 新增项（波形任务提供读写 API 后需回填确认）─────────────────────────

    @Test
    fun `newV280DefaultsAreTheProvisionalOnes`() {
        // probe-waveform-tier.md §3.3：tier 默认 1（T1 精致）、迁移水位默认 1。
        assertEquals(SettingsVisibility.TIER_DEFAULT, default("visualizer_tier"))
        assertEquals(1, default("visualizer_tier_version"))
        assertEquals(listOf(0, 1, 2), SettingsRegistry.entryById("visualizer_tier")!!.choices)
        // ⚠️ 其余 5 个键（showcase + 4 个 C 档细分）的默认值探针没有给出，这里取「C 档炫技默认关」。
        // 波形任务落地读写 API 后，若它取别的默认值，改 SettingsRegistry 一处即可（本测试会同步失败提醒）。
        listOf(
            "visualizer_showcase",
            "visualizer_shockwave",
            "visualizer_particles",
            "visualizer_perspective",
            "visualizer_drag",
        ).forEach { id ->
            assertEquals("$id 的默认值应与波形任务确认", false, default(id))
        }
    }

    // ── 5. 覆盖完备性 ─────────────────────────────────────────────────────────────────

    @Test
    fun `everyLegacyEntryHasANonNullDefaultExceptCredentials`() {
        SettingsRegistry.allEntries()
            .filter { !it.isNewInV280 && it.key != null }
            .forEach { entry ->
                if (entry.key == "user_cookie" || entry.key == "qq_cookie") {
                    assertNull(entry.id, entry.defaultValue)
                } else {
                    assertNotNull("${entry.id} 缺少默认值声明", entry.defaultValue)
                }
            }
    }

    /**
     * 内存版 SharedPreferences（与 `ui/screen/PlaylistLayoutSettingTest.kt` 同一套写法）。
     *
     * 本仓库单测没有 Android 运行时（`unitTests.isReturnDefaultValues = true` 只把框架桩的返回值抹平），
     * 而歌词那几个读函数只依赖 `SharedPreferences` 接口 —— 一个内存实现就够。
     */
    private class FakePrefs(
        private val values: MutableMap<String, Any?> = mutableMapOf(),
    ) : SharedPreferences {

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
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    /** apply() 立即落盘（这里的「盘」就是内存 map），够测读写往返。 */
    private class FakeEditor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {

        private val pending = mutableMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { pending[key] = values }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { pending.remove(key) }
        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }
        override fun commit(): Boolean {
            applyPending()
            return true
        }
        override fun apply() = applyPending()

        private fun applyPending() {
            if (clearRequested) values.clear()
            values.putAll(pending)
            pending.clear()
            clearRequested = false
        }
    }
}