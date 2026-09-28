/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0 设置项注册表的单测（纯 JVM，无 Robolectric、不碰 Android framework）。
 */

package com.takahashirinta.ncrust.settings

import com.takahashirinta.ncrust.ui.i18n.StringsSnapshot
import com.takahashirinta.ncrust.ui.i18n.zhCN
import com.takahashirinta.ncrust.ui.settings.PREFS_COOKIE
import com.takahashirinta.ncrust.ui.settings.PREFS_QQ
import com.takahashirinta.ncrust.ui.settings.PREFS_SETTINGS
import com.takahashirinta.ncrust.ui.settings.SettingsEntry
import com.takahashirinta.ncrust.ui.settings.SettingsEntryType
import com.takahashirinta.ncrust.ui.settings.SettingsGroup
import com.takahashirinta.ncrust.ui.settings.SettingsRegistry
import com.takahashirinta.ncrust.ui.settings.SettingsRenderPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SettingsRegistry` 的机械防线。
 *
 * 最重要的两条：
 * 1. [legacyPrefKeysArePreservedExactly] —— **双向等值**：既防「迁移丢项」，也防
 *    「顺手夹带一个音理功能项（一起听 / 下载管理 / 流量管理 / 网络设置 / 备份与恢复 / B 站登录）」；
 * 2. [newV280KeysAreExactlyTheWaveformSet] —— v2.8.0 新增项单独计数，不混进上一条。
 */
class SettingsRegistryTest {

    // ── 1. key 双向等值 ───────────────────────────────────────────────────────────────

    @Test
    fun `legacyPrefKeysArePreservedExactly`() {
        val actual = SettingsRegistry.allEntries()
            // v2.9.0：新增的 4 个动效键同样不属于"口径 C 的 42 个既有键"，
            // 所以两个"某版本新增"标志都要排除 —— 否则这条断言会因为**新增**而变红，
            // 那正好把"机械防线"变成"每次加功能都要改测试"的噪声源。
            .filter {
                !it.isNewInV280 && !it.isNewInV290 && !it.isNewInV300 &&
                    !it.isNewInV310 && !it.isNewInV320
            }
            .mapNotNull { it.key }
            .toSet()

        val missing = LEGACY_PREF_KEYS - actual
        val extra = actual - LEGACY_PREF_KEYS
        assertTrue("迁移丢项（清单里有、registry 里没有）：$missing", missing.isEmpty())
        assertTrue("夹带项（registry 里有、清单里没有）：$extra", extra.isEmpty())
        assertEquals(LEGACY_PREF_KEYS, actual)
        assertEquals("口径 C 的 key 数（probe-settings-inventory §7.1）", 42, LEGACY_PREF_KEYS.size)
    }

    @Test
    fun `newV280KeysAreExactlyTheWaveformSet`() {
        val actual = SettingsRegistry.allEntries()
            .filter { it.isNewInV280 }
            .mapNotNull { it.key }
            .toSet()

        val missing = NEW_V280_KEYS - actual
        val extra = actual - NEW_V280_KEYS
        assertTrue("v2.8.0 新增项丢失：$missing", missing.isEmpty())
        assertTrue("多出来的 v2.8.0 新增项：$extra", extra.isEmpty())
        assertEquals(NEW_V280_KEYS, actual)
    }

    @Test
    fun `newAndLegacyKeySetsAreDisjointAndAllKeysAreUnique`() {
        val all = SettingsRegistry.allEntries().mapNotNull { it.key }
        assertEquals("key 必须唯一", all.size, all.toSet().size)
        assertTrue("key 不能带空白", all.none { it != it.trim() || it.isEmpty() })

        val legacy = SettingsRegistry.allEntries()
            .filter {
                !it.isNewInV280 && !it.isNewInV290 && !it.isNewInV300 &&
                    !it.isNewInV310 && !it.isNewInV320
            }
            .mapNotNull { it.key }
            .toSet()
        val v280 = SettingsRegistry.allEntries().filter { it.isNewInV280 }.mapNotNull { it.key }.toSet()
        val v290 = SettingsRegistry.allEntries().filter { it.isNewInV290 }.mapNotNull { it.key }.toSet()
        val v300 = SettingsRegistry.allEntries().filter { it.isNewInV300 }.mapNotNull { it.key }.toSet()
        assertTrue("新键不得与既有键重名", (legacy intersect v280).isEmpty())
        assertTrue("新键不得与既有键重名", (legacy intersect v290).isEmpty())
        assertTrue("v2.8.0 与 v2.9.0 的新键不得重名", (v280 intersect v290).isEmpty())
        assertTrue("新键不得与既有键重名（v3.0.0）", (legacy intersect v300).isEmpty())
        assertTrue("v2.9.0 与 v3.0.0 的新键不得重名", (v290 intersect v300).isEmpty())
        assertTrue("v2.8.0 与 v3.0.0 的新键不得重名", (v280 intersect v300).isEmpty())
    }

    @Test
    fun `newV290KeysAreExactlyTheMotionSet`() {
        // 机械防线之二：v2.9.0 只允许新增这一组「统一动效强度」的键。
        // 顺手夹带一个无关功能项（一起听 / 下载管理 / 流量管理 / 备份恢复…）会在这里变红。
        val actual = SettingsRegistry.allEntries()
            .filter { it.isNewInV290 }
            .mapNotNull { it.key }
            .toSet()
        assertEquals("v2.9.0 新增项", NEW_V290_KEYS, actual)
        assertEquals(5, NEW_V290_KEYS.size)
    }

    @Test
    fun `newV300KeysAreExactlyTheMotionSwitchSet`() {
        // 机械防线之三：v3.0.0 只允许新增「每个动效一个独立开关」这一组（铁律 26）。
        // 顺手夹带一个无关功能项（一起听 / 下载管理 / 流量管理 / 备份恢复…）会在这里变红。
        val actual = SettingsRegistry.allEntries()
            .filter { it.isNewInV300 }
            .mapNotNull { it.key }
            .toSet()
        assertEquals("v3.0.0 新增项", NEW_V300_KEYS, actual)
        assertEquals("恰好五个独立开关", 5, NEW_V300_KEYS.size)
    }

    @Test
    fun `v290DegradeKeysAreInertUnderV300`() {
        // v3.0.0 把自动降级机制整个删除了：这两个键不再参与任何渲染决策，
        // 但**保留不删**（回滚安装不丢数据）。判据是机械的：必须同时
        // 「标 legacyV300」+「internal（没有 UI 入口）」+「属于 v2.9.0 的新增项」。
        val inert = SettingsRegistry.allEntries().filter { it.legacyV300 }
        assertEquals(
            "只有降级水位与降级日志这两个键是 v3.0.0 的历史键",
            setOf("motion_degrade_level", "motion_degrade_log"),
            inert.mapNotNull { it.key }.toSet(),
        )
        inert.forEach {
            assertTrue("${it.key} 标了 legacyV300 却没有 internal ⇒ 会在设置页渲染出来", it.isInternal)
            assertTrue("${it.key} 不是 v2.9.0 新增项却标了 legacyV300", it.isNewInV290)
        }
        // 反过来：v2.9.0 的另外三个键**仍然在驱动渲染**（不能被顺手标成历史键）。
        val stillLive = SettingsRegistry.allEntries()
            .filter { it.isNewInV290 && !it.legacyV300 }
            .mapNotNull { it.key }
            .toSet()
        assertEquals(
            setOf("motion_tier", "ui_motion_enabled", "motion_version"),
            stillLive,
        )
    }

    @Test
    fun `v300 switches are reachable and have strings`() {
        // 「设置项可达性」：五个开关必须被渲染计划覆盖，且标题/副标题文案齐全
        // （文案住在 `Strings.waveform`，与 v2.9.0 的四条同一口径）。
        val v300 = SettingsRegistry.allEntries().filter { it.isNewInV300 }
        v300.forEach { entry ->
            assertFalse("${entry.id} 不该是内部项 —— 用户必须能关掉每一个新动效", entry.isInternal)
            assertTrue("${entry.id} 必须被渲染计划覆盖", SettingsRenderPlan.isRenderedOnGroupPage(entry))
            assertEquals("${entry.id} 必须是一个开关", SettingsEntryType.SWITCH, entry.type)
            assertEquals("${entry.id} 的默认值必须是开（缺 key = 与档位表一致）", true, entry.defaultValue)
            assertNotNull("${entry.id} 缺 titleKey", entry.titleKey)
            assertNotNull("${entry.id} 缺 subtitleKey", entry.subtitleKey)
            assertTrue(
                "${entry.id} 的文案必须指向 waveform 组",
                entry.titleKey!!.startsWith("waveform.") && entry.subtitleKey!!.startsWith("waveform."),
            )
        }
    }

    @Test
    fun `v280KeysAreAllLegacyUnderV290`() {
        // v2.9.0 把统一档位接过去之后，v2.8.0 的 7 个键**全部**降级为迁移源：
        // 既不能有 UI 入口（两个键都能改档位 = 双轨），也不能悄悄删掉（回滚安装会丢用户数据）。
        val legacy = SettingsRegistry.allEntries().filter { it.legacyV290 }
        assertEquals("v2.8.0 的 8 个键必须全部标 legacy", NEW_V280_KEYS, legacy.mapNotNull { it.key }.toSet())
        legacy.forEach {
            assertTrue("${it.key} 标了 legacy 却没有 internal ⇒ 会在设置页渲染出来", it.isInternal)
            assertTrue("${it.key} 不是 v2.8.0 新增项却标了 legacy", it.isNewInV280)
        }
    }

    @Test
    fun `v290EntriesAreReachableAndHaveStrings`() {
        // 「设置项可达性」：非 internal 的新项必须被渲染计划照顾到，且标题/副标题文案齐全。
        val v290 = SettingsRegistry.allEntries().filter { it.isNewInV290 }
        val rendered = v290.filter { !it.isInternal }
        assertEquals(
            "v2.9.0 只有「动效强度」与「界面动效」两项有 UI 入口",
            setOf("motion_tier", "ui_motion_enabled"),
            rendered.mapNotNull { it.key }.toSet(),
        )
        rendered.forEach { entry ->
            assertTrue("${entry.id} 必须被渲染计划覆盖", SettingsRenderPlan.isRenderedOnGroupPage(entry))
            assertNotNull("${entry.id} 缺 titleKey", entry.titleKey)
            assertTrue(
                "${entry.id} 的 titleKey 必须指向 waveform 组（新文案按纪律放 WaveformStrings）",
                entry.titleKey!!.startsWith("waveform."),
            )
        }
    }

    @Test
    fun `newV310KeysAreExactlyTheBilibiliToggle`() {
        // 与 v2.8.0 / v2.9.0 / v3.0.0 的三条同构：**一个版本新增了什么，单独计数**。
        // 合并成一个「新键」标志会让「顺手夹带了无关功能项」不再被机械挡住。
        val actual = SettingsRegistry.allEntries()
            .filter { it.isNewInV310 }
            .mapNotNull { it.key }
            .toSet()
        assertEquals(setOf("bilibili_enabled"), actual)
        val entry = SettingsRegistry.allEntries().first { it.id == "bilibili_enabled" }
        assertEquals("B 站开关是布尔开关", SettingsEntryType.SWITCH, entry.type)
        assertEquals("默认必须关闭（外部平台依赖不该默认打开）", false, entry.defaultValue)
        assertEquals(SettingsGroup.GENERAL, entry.group)
        assertFalse("它是可见项", entry.isInternal)
        // 文案必须住在分组里（v2.2.1 规则 5），且**两条都要有**。
        assertEquals("bilibiliEnabledLabel", entry.titleKey)
        assertEquals("bilibiliEnabledDescription", entry.subtitleKey)
    }

    @Test
    fun `newV320KeysAreExactlyTheRhythmSwitchSet`() {
        // 机械防线之四：v3.2.0 只允许新增「界面律动那一层的闸」这一组（1 个总闸 + 3 个细粒度）。
        // 顺手夹带一个无关功能项（一起听 / 下载管理 / 流量管理 / 备份恢复…）会在这里变红。
        val actual = SettingsRegistry.allEntries()
            .filter { it.isNewInV320 }
            .mapNotNull { it.key }
            .toSet()
        assertEquals("v3.2.0 新增项", NEW_V320_KEYS, actual)
        assertEquals("恰好四个（一个总闸 + 三个细粒度开关）", 4, NEW_V320_KEYS.size)
        // 复用不重复造键：背景呼吸用的是 v3.0.0 的 `motion_breathing`，**不许**再开一个同义键。
        assertFalse(
            "背景呼吸只有一个键（motion_breathing），不许再造同义键",
            actual.any { it.contains("breath") && it != "motion_breathing" },
        )
        assertFalse("v3.0.0 的键不得被重复标记", actual.any { it in NEW_V300_KEYS })
    }

    @Test
    fun `v320 switches are reachable and have strings`() {
        // 与 v3.0.0 的五条同一口径：可见、被渲染计划覆盖、是开关、默认开、文案路径必须真实存在
        // （路径存在性由 `everyTitleKeyResolvesToARealStringsAccessorPath` 用 i18n 快照单独钉住）。
        val v320 = SettingsRegistry.allEntries().filter { it.isNewInV320 }
        v320.forEach { entry ->
            assertFalse("${entry.id} 不该是内部项 —— 用户必须能关掉每一项", entry.isInternal)
            assertTrue("${entry.id} 必须被渲染计划覆盖", SettingsRenderPlan.isRenderedOnGroupPage(entry))
            assertEquals("${entry.id} 必须是一个开关", SettingsEntryType.SWITCH, entry.type)
            assertEquals("${entry.id} 的默认值必须是开（缺 key = 与档位表一致）", true, entry.defaultValue)
            assertEquals(
                "${entry.id} 属于「外观与动效」（v3.0.0 纪律：声明位置必须跟着 group 走）",
                SettingsGroup.APPEARANCE,
                entry.group,
            )
            assertNotNull("${entry.id} 缺 titleKey", entry.titleKey)
            assertNotNull("${entry.id} 缺 subtitleKey", entry.subtitleKey)
            assertTrue(
                "${entry.id} 的文案必须指向 waveform 组（动效文案的唯一归属）",
                entry.titleKey!!.startsWith("waveform.") &&
                    entry.subtitleKey!!.startsWith("waveform."),
            )
        }
    }

    @Test
    fun `noEntryLooksLikeAyinliFeature`() {
        // 机械防线之二：id / key / 文案路径里都不允许出现「音理」那套功能的名字。
        //
        // ⚠️ v3.1.0：**「bilibili」从禁词表里移除了**，理由不是"它变合法了"，
        // 而是它从来就不是「音理的功能名」—— 它是**另一个 App 的名字**，被顺手写进了这张表。
        // v3.1.0 有意接入 B 站音源（`bilibili_enabled` + `bilibiliEnabledLabel`），
        // 于是这条断言第一次真的因为「夹带」而变红 —— 而它该拦的是
        // 「一起听 / 下载管理 / 流量管理 / 备份恢复」那几样**本 App 不做**的东西。
        // 它仍然逐条拦着其余 15 个词，这一条只是**收窄到它本来的意图**。
        val forbidden = listOf(
            "download", "下载", "traffic", "流量", "network_settings",
            "proxy", "backup", "restore", "备份", "together", "一起听", "webdav", "listen_together",
        )
        val hits = SettingsRegistry.allEntries().flatMap { entry ->
            val haystack = listOfNotNull(entry.id, entry.key, entry.titleKey, entry.subtitleKey)
                .joinToString(" ").lowercase()
            forbidden.filter { haystack.contains(it) }.map { "${entry.id} → $it" }
        }
        assertTrue("出现了不属于本 App 的功能项：$hits", hits.isEmpty())
    }

    // ── 2. 分组完整性 ─────────────────────────────────────────────────────────────────

    @Test
    fun `groupsAreExactlyTheSevenProposedOnes`() {
        val ids = SettingsRegistry.groups().map { it.id }
        assertEquals(listOf("account", "general", "appearance", "playback", "lyrics", "storage", "about"), ids)
        assertEquals("分组 id 必须唯一", ids.size, ids.toSet().size)
        SettingsRegistry.groups().forEach { group ->
            assertTrue("分组 ${group.id} 的文案 key 不能为空", group.titleKey.isNotBlank() && group.subtitleKey.isNotBlank())
            assertTrue("分组 ${group.id} 的图标名不能为空", group.iconName.isNotBlank())
        }
    }

    @Test
    fun `everyGroupIsNonEmptyAndEveryEntryBelongsToExactlyOneGroup`() {
        SettingsRegistry.groups().forEach { group ->
            assertTrue("分组 ${group.id} 是空组", SettingsRegistry.entriesOf(group.id).isNotEmpty())
            SettingsRegistry.entriesOf(group.id).forEach { entry ->
                assertSame("entriesOf 必须只返回本组条目", group, entry.group)
            }
        }
        // 各组条目数之和 == 全量条目数 ⇒ 既不丢项、也不重复计入
        val sum = SettingsRegistry.groups().sumOf { SettingsRegistry.entriesOf(it).size }
        assertEquals(SettingsRegistry.allEntries().size, sum)
    }

    @Test
    fun `everyEntryHasAUniqueId`() {
        val ids = SettingsRegistry.allEntries().map { it.id }
        assertEquals("条目 id 必须唯一", ids.size, ids.toSet().size)
        assertTrue("条目 id 不能为空", ids.none { it.isBlank() })
    }

    @Test
    fun `secondaryGroupIsOnlyUsedForTheDocumentedDualOwnershipEntries`() {
        val dual = SettingsRegistry.allEntries().filter { it.secondaryGroup != null }
        assertEquals(
            "双归属项必须是这 6 条（probe-settings-inventory §4.5 / §6.6）",
            setOf(
                "lyrics_in_media_session", // 主：歌词；次：播放与音质
                "session_metadata_lyrics", // 主：播放与音质；次：歌词（与上一项互为镜像）
                "library_playlist_layout", // 主：存储与缓存；次：外观与动效
                "library_section_collapsed_local",
                "library_section_collapsed_netease",
                "library_section_collapsed_qq",
            ),
            dual.map { it.id }.toSet(),
        )
        dual.forEach { entry ->
            assertTrue("次级归属不能等于主归属：${entry.id}", entry.secondaryGroup != entry.group)
        }
        // 主归属（用于分组统计的那一个）必须是唯一权威
        assertEquals(SettingsGroup.LYRICS, SettingsRegistry.entryById("lyrics_in_media_session")!!.group)
        assertEquals(SettingsGroup.PLAYBACK, SettingsRegistry.entryById("session_metadata_lyrics")!!.group)
    }

    // ── 3. 可达性 / 渲染顺序 ──────────────────────────────────────────────────────────

    @Test
    fun `everyEntryIsReachableByIdAndByKey`() {
        SettingsRegistry.allEntries().forEach { entry ->
            assertSame("entryById 取不到 ${entry.id}", entry, SettingsRegistry.entryById(entry.id))
            entry.key?.let { key ->
                assertSame("entryByKey 取不到 $key", entry, SettingsRegistry.entryByKey(key))
            }
        }
        assertNull(SettingsRegistry.entryById("no.such.entry"))
        assertNull(SettingsRegistry.entryByKey("no_such_key"))
    }

    @Test
    fun `renderOrderListLosesNothing`() {
        val rendered = SettingsRegistry.groups().flatMap { SettingsRegistry.entriesOf(it.id) }
        assertEquals("渲染顺序列表必须与全量条目完全一致（含顺序）", SettingsRegistry.allEntries(), rendered)
        assertEquals(
            SettingsRegistry.allEntries().map { it.id },
            rendered.map { it.id },
        )
    }

    // ── 4. 模型自身的约束 ─────────────────────────────────────────────────────────────

    @Test
    fun `keyIsNullExactlyForActionEntries`() {
        SettingsRegistry.allEntries().forEach { entry ->
            if (entry.key == null) {
                assertEquals("只有 ACTION 行没有 key：${entry.id}", SettingsEntryType.ACTION, entry.type)
            } else {
                assertFalse("有 key 的条目不能是 ACTION：${entry.id}", entry.type == SettingsEntryType.ACTION)
            }
        }
    }

    @Test
    fun `infoEntriesHaveNoTitleAndNoChoices`() {
        SettingsRegistry.allEntries().filter { it.type == SettingsEntryType.INFO }.forEach { entry ->
            assertNull("INFO 项不得有可编辑控件，也不该有文案：${entry.id}", entry.titleKey)
            assertNull("INFO 项不得有 choices：${entry.id}", entry.choices)
        }
    }

    @Test
    fun `switchEntriesDefaultToBooleanAndChoiceEntriesCarryTheirDefault`() {
        SettingsRegistry.allEntries().forEach { entry ->
            when (entry.type) {
                SettingsEntryType.SWITCH ->
                    assertTrue("SWITCH 的默认值必须是 Boolean：${entry.id}", entry.defaultValue is Boolean)

                SettingsEntryType.QUALITY_CHOICE, SettingsEntryType.CHOICE -> {
                    val choices = entry.choices
                    assertTrue("${entry.id} 缺少 choices", !choices.isNullOrEmpty())
                    assertEquals("${entry.id} 的 choices 有重复", choices!!.size, choices.toSet().size)
                    assertTrue(
                        "${entry.id} 的默认值 ${entry.defaultValue} 不在 choices $choices 里",
                        choices.contains(entry.defaultValue),
                    )
                }

                SettingsEntryType.ACTION -> {
                    assertNull("ACTION 行不落盘，默认值必须是 null：${entry.id}", entry.defaultValue)
                    assertNull("ACTION 行没有 choices：${entry.id}", entry.choices)
                }

                SettingsEntryType.TEXT, SettingsEntryType.INFO -> Unit
            }
        }
    }

    @Test
    fun `everyLegacyEntryDeclaresADefault`() {
        // 唯一的例外是两条账号凭证：CookieManager / QqAuthStore 的读默认值就是 null。
        val nullDefaults = SettingsRegistry.allEntries()
            .filter { !it.isNewInV280 }
            .filter { it.defaultValue == null }
            .mapNotNull { it.key }
            .toSet()
        assertEquals(setOf("user_cookie", "qq_cookie"), nullDefaults)
    }

    @Test
    fun `internalEntrySetMatchesTheInventoryHiddenOnes`() {
        assertEquals(INTERNAL_PREF_KEYS, SettingsRegistry.allEntries().filter { it.isInternal }.map { it.id }.toSet())
        // 内部项仍然在 registry 里（防丢项），但 UI 不得渲染
        assertEquals(
            SettingsRegistry.allEntries().size - INTERNAL_PREF_KEYS.size,
            SettingsRegistry.allEntries().count { !it.isInternal },
        )
    }

    @Test
    fun `advancedEntrySetMatchesTheInventoryExperimentalOnes`() {
        assertEquals(ADVANCED_ENTRY_IDS, SettingsRegistry.allEntries().filter { it.isAdvanced }.map { it.id }.toSet())
    }

    @Test
    fun `actionRowIdsAreStableAndComplete`() {
        val actions = SettingsRegistry.allEntries().filter { it.type == SettingsEntryType.ACTION }
        assertEquals(ACTION_IDS, actions.map { it.id }.toSet())
        actions.forEach { action ->
            assertTrue("行为行 ${action.id} 必须在 action. 命名空间下", action.id.startsWith("action."))
            assertTrue("行为行必须有标题文案：${action.id}", !action.titleKey.isNullOrBlank())
        }
    }

    @Test
    fun `onlyAccountCredentialsLiveOutsideNcrustSettings`() {
        val byFile = SettingsRegistry.allEntries().filter { it.key != null }.groupBy { it.prefsFile }
        assertEquals(
            setOf(PREFS_SETTINGS, PREFS_COOKIE, PREFS_QQ),
            byFile.keys,
        )
        assertEquals(setOf("user_cookie"), byFile.getValue(PREFS_COOKIE).map { it.key }.toSet())
        assertEquals(setOf("qq_cookie"), byFile.getValue(PREFS_QQ).map { it.key }.toSet())
    }

    @Test
    fun `titleLessEntriesAreExactlyTheDocumentedOnes`() {
        // 34 条：19 条内部项 − 1 条遗留键（lyrics_word_by_word 有文案但无 UI）
        //        + 6 条 v2.8.0 新增项（文案随 UI 阶段进 i18n）
        //        + 2 条 v2.9.0 派生键（降级水位 / 迁移水位，无 UI 入口）
        //        + 4 条库页显示偏好（文案在库页，结构探针 §1.2 建议不迁移）。
        // 注：v2.9.0 的两个**可见**新项（motion_tier / ui_motion_enabled）**有**文案，不在本清单里。
        val actual = SettingsRegistry.allEntries().filter { it.titleKey == null }.map { it.id }.toSet()
        assertEquals(TITLE_LESS_IDS, actual)
    }

    @Test
    fun `everyTitleKeyResolvesToARealStringsAccessorPath`() {
        // 用仓库既有的 i18n 快照工具反射采集 `Strings` 的全部可达路径（含 `motion.pageTransitionLabel`
        // 这种嵌套组路径）——文案路径写错会在这里被抓住，而不是等到 UI 阶段才发现。
        val paths = StringsSnapshot.capture(zhCN).keys
        val missing = SettingsRegistry.allEntries()
            .flatMap { listOfNotNull(it.titleKey, it.subtitleKey) }
            .filterNot { paths.contains(it) }
        assertTrue("这些文案路径在 Strings 上不存在：$missing", missing.isEmpty())
    }

    // ── 5. 「registry 没有写盘能力」的机械证明 ────────────────────────────────────────

    @Test
    fun `settingsModelHasNoAndroidOrLambdaState`() {
        val classes = listOf(
            SettingsEntry::class.java,
            SettingsGroup::class.java,
            SettingsRegistry::class.java,
        )
        classes.forEach { clazz ->
            clazz.declaredFields.forEach { field ->
                assertFalse(
                    "${clazz.simpleName}.${field.name} 引用了 Android 类型：${field.type.name}",
                    field.type.name.startsWith("android.") || field.type.name.startsWith("androidx."),
                )
                assertFalse(
                    "${clazz.simpleName}.${field.name} 持有函数类型（registry 不该存行为）：${field.type.name}",
                    field.type.name.startsWith("kotlin.jvm.functions."),
                )
            }
            clazz.declaredMethods.forEach { method ->
                val types = method.parameterTypes.toList() + method.returnType
                types.forEach { type ->
                    assertFalse(
                        "${clazz.simpleName}.${method.name} 的签名里出现 Android 类型：${type.name}",
                        type.name.startsWith("android.") || type.name.startsWith("androidx."),
                    )
                }
            }
        }
    }

    @Test
    fun `registryExposesNoWriteLikeApi`() {
        val writeLike = Regex("(?i)(write|put|save|set|edit|commit|apply|clear|remove|delete|persist)")
        val offenders = SettingsRegistry::class.java.declaredMethods
            .map { it.name }
            .filter { writeLike.containsMatchIn(it) }
        assertTrue("registry 不得暴露任何写盘形状的 API：$offenders", offenders.isEmpty())
    }

    private companion object {

        /**
         * 迁移前既有条目的 key 全集（**口径 C = 42**，`probe-settings-inventory.md` §7.1：
         * `ncrust_settings` 全部 40 个键 + `user_cookie` + `qq_cookie`）。
         *
         * 刻意**手写**而不是从 registry 反推 —— 它必须是一份独立的事实，才能双向卡住 registry。
         */
        val LEGACY_PREF_KEYS: Set<String> = setOf(
            // §2.1 ncrust_settings 有 UI 入口的 27 项
            "wifi_quality", "mobile_quality", "gapless_playback", "keep_screen_on", "auto_rotate",
            "audio_visualizer", "artist_reco_enabled", "page_transition_enabled", "lyrics_translation",
            "lyrics_word_animation", "lyrics_sweep_quality", "lyrics_font_scale", "lyrics_in_media_session",
            "lyrics_ttml_enabled", "lyrics_ttml_first", "lyrics_romanization", "lyrics_dynamic_font",
            "theme_color_index", "theme_mode", "accent_source", "language_code", "custom_bg_enabled",
            "offline_cache_mb", "library_playlist_layout", "library_section_collapsed_local",
            "library_section_collapsed_netease", "library_section_collapsed_qq",
            // §2.2 ncrust_settings 无 UI 入口的 13 项
            "lyrics_word_by_word", "lyrics_sweep_fade_em", "lyrics_sweep_inactive_alpha", "lyrics_sweep_easing",
            "session_metadata_lyrics", "live_update_enabled", "battery_prompt_done", "artist_reco_target_id",
            "artist_reco_anchor_ids", "artist_reco_auto_anchor_ids", "artist_reco_auto_anchor_at",
            "quality_ladder_version", "live_update_probed",
            // §6.1 账号凭证 2 项（ncrust_prefs / ncrust_qq_prefs）
            "user_cookie", "qq_cookie",
        )

        /**
         * v2.8.0「波形可视化分级」新增的 7 个键。
         *
         * 任务书正文逐字列了 6 个（`visualizer_tier` / `visualizer_showcase` / `visualizer_shockwave` /
         * `visualizer_particles` / `visualizer_perspective` / `visualizer_drag`），但断言写「恰好 7 个」；
         * 第 7 个取 `probe-waveform-tier.md` §3.3 的迁移水位 `visualizer_tier_version`
         * （范式同 `quality_ladder_version`：必须与 tier 两个值同批迁移）。
         * 同表里的 `visualizer_auto_downgraded` 是**运行期诊断标记**（不是配置项），
         * 按「迁移水位进 registry、运行期标记不进」的口径排除在外，由波形任务自己管。
         */
        /** v2.9.0 新增的 4 个动效键（统一「动效强度」）。 */
        val NEW_V290_KEYS: Set<String> = setOf(
            "motion_tier",
            "ui_motion_enabled",
            "motion_degrade_level",
            "motion_degrade_log",
            "motion_version",
        )

        val NEW_V300_KEYS: Set<String> = setOf(
            "motion_shockwave",
            "motion_halo",
            "motion_particles",
            "motion_wave_bands",
            "motion_breathing",
        )

        /**
         * v3.2.0 新增的 4 个键：**界面律动**那一层的闸。
         *
         * 注意背景呼吸**不在**这里 —— 它复用 v3.0.0 的 `motion_breathing`（不许造同义键）。
         */
        val NEW_V320_KEYS: Set<String> = setOf(
            "motion_rhythm_enabled",
            "motion_cover_float",
            "motion_lyric_pulse",
            "motion_bar_pulse",
        )

        val NEW_V280_KEYS: Set<String> = setOf(
            "visualizer_tier",
            "visualizer_showcase",
            "visualizer_shockwave",
            "visualizer_particles",
            "visualizer_perspective",
            "visualizer_drag",
            "visualizer_tier_version",
            // v2.9.0 补：它是**迁移源**（迁移读它决定起始降级水位），因此必须被枚举到 ——
            // 否则「不丢项」的双向等值断言盖不到它（probe-tier-migration.md §1.3 记的就是这个洞）。
            // v2.8.0 的「运行期标记不进 registry」口径因此在本版被推翻，理由见上面的 KDoc。
            "visualizer_auto_downgraded",
        )

        /** 无 UI 入口的内部项（§2.2 的 13 项 + 2 条账号凭证 + v2.8.0 的迁移水位）。 */
        val INTERNAL_PREF_KEYS: Set<String> = setOf(
            "user_cookie", "qq_cookie",
            "lyrics_word_by_word", "lyrics_sweep_fade_em", "lyrics_sweep_inactive_alpha", "lyrics_sweep_easing",
            "session_metadata_lyrics", "live_update_enabled", "live_update_probed", "battery_prompt_done",
            "artist_reco_target_id", "artist_reco_anchor_ids", "artist_reco_auto_anchor_ids",
            "artist_reco_auto_anchor_at", "quality_ladder_version",
            "visualizer_tier_version",
            // v2.9.0：v2.8.0 的 6 个波形键降级为迁移源（internal = true），
            // 新增的 2 个派生键（降级水位 / 迁移水位）同样没有 UI 入口。
            "visualizer_tier", "visualizer_showcase", "visualizer_shockwave",
            "visualizer_particles", "visualizer_perspective", "visualizer_drag",
            "visualizer_auto_downgraded",
            "motion_degrade_level", "motion_degrade_log", "motion_version",
        )

        /** 高级 / 实验性（探针清单的「是否高级/实验性」列 + v2.8.0 的炫技项）。 */
        val ADVANCED_ENTRY_IDS: Set<String> = setOf(
            "lyrics_dynamic_font",
            "lyrics_sweep_fade_em", "lyrics_sweep_inactive_alpha", "lyrics_sweep_easing",
            "session_metadata_lyrics", "live_update_enabled",
            "artist_reco_target_id", "artist_reco_anchor_ids",
            "visualizer_showcase", "visualizer_shockwave", "visualizer_particles",
            "visualizer_perspective", "visualizer_drag", "visualizer_tier_version",
            "visualizer_auto_downgraded",
            // v2.9.0：三个派生键标 advanced（它们是给排查用的内部水位/日志，
            // 不渲染、但语义上属于"高级/实验性"这一档）。
            "motion_degrade_level", "motion_degrade_log", "motion_version",
        )

        /** 无 prefs key 的行为行（结构探针 §1「非 prefs 行」全表，一条都不能丢）。 */
        val ACTION_IDS: Set<String> = setOf(
            "action.account_netease", "action.account_qq", "action.account_bili",
            "action.background_activity",
            "action.storage_clear_cache", "action.storage_offline_manage", "action.about_open",
        )

        /** 没有文案路径的条目（见 `titleLessEntriesAreExactlyTheDocumentedOnes`）。 */
        val TITLE_LESS_IDS: Set<String> = setOf(
            // 内部项（except lyrics_word_by_word，它的文案 key 还在 i18n 里，只是没有 UI 入口）
            "user_cookie", "qq_cookie", "battery_prompt_done", "artist_reco_target_id",
            "artist_reco_anchor_ids", "artist_reco_auto_anchor_ids", "artist_reco_auto_anchor_at",
            "session_metadata_lyrics", "live_update_enabled", "live_update_probed", "quality_ladder_version",
            "lyrics_sweep_fade_em", "lyrics_sweep_inactive_alpha", "lyrics_sweep_easing",
            "visualizer_tier_version",
            // v2.8.0 新增项：文案随 UI 阶段进 SettingsStrings（本任务禁止改 ui/i18n 目录）
            "visualizer_tier", "visualizer_showcase", "visualizer_shockwave",
            "visualizer_particles", "visualizer_perspective", "visualizer_drag",
            "visualizer_auto_downgraded",
            // v2.9.0 的三个派生键：没有 UI 入口，也就没有文案路径。
            "motion_degrade_level", "motion_degrade_log", "motion_version",
            // 库页显示偏好：文案在库页，设置页不迁移这 4 项
            "library_playlist_layout", "library_section_collapsed_local",
            "library_section_collapsed_netease", "library_section_collapsed_qq",
        )
    }
}
