/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0「设置界面二级菜单重构」阶段 1：设置项注册表（新包 ui/settings）。
 * **纯数据 + 纯函数**：不持有 Context、不 import 任何 Compose / Android 类型、
 * 没有任何写盘能力（类型上就不给）。单测见 app/src/test/.../settings/。
 */

package com.takahashirinta.ncrust.ui.settings

import com.takahashirinta.ncrust.lyric.LyricsDisplayPrefs
import com.takahashirinta.ncrust.lyric.LyricsSweepConfig
import com.takahashirinta.ncrust.lyric.LyricsSweepQuality
import com.takahashirinta.ncrust.lyric.LyricsWordAnimationMode
import com.takahashirinta.ncrust.lyric.SweepEasing
import com.takahashirinta.ncrust.player.QualityLadder
import com.takahashirinta.ncrust.ui.player.motion.MotionDegrade
import com.takahashirinta.ncrust.ui.player.motion.MotionIntensity
import com.takahashirinta.ncrust.ui.player.motion.MotionPrefs

/** 主设置文件（与其余设置项一致：`LyricsDisplayPrefs.PREFS_NAME`）。 */
const val PREFS_SETTINGS = "ncrust_settings"

/** 网易云 cookie 所在文件（`auth/CookieManager.kt:7`）。 */
const val PREFS_COOKIE = "ncrust_prefs"

/** QQ cookie 所在文件（`qq/QqAuthStore.kt:62`）。 */
const val PREFS_QQ = "ncrust_qq_prefs"

/**
 * 一级分组（二级菜单重构后的 7 张卡片）。
 *
 * 分组顺序 = 枚举声明顺序 = 一级页卡片顺序（`groups()` 直接返回它）。
 *
 * - [id] 用于路由参数，**与 i18n 文案解耦**：改文案不动路由（`NavRoutes.settingsGroup(id)`）。
 * - [titleKey] / [subtitleKey] 是 **`Strings` 上的访问路径**（点号分隔嵌套组），
 *   不是中文文案本身 —— registry 里不存任何用户可见字符串。
 *   ⚠️ 这 14 条分组文案**当前还不存在**（`ui/i18n/Strings.kt` 由并行的 i18n 任务维护，
 *   本任务的边界禁止改 `ui/i18n` 目录）：UI 集成阶段需按这些名字补进 `SettingsStrings` × 8 locale。
 * - [iconName] 是**图标名字符串**，故意不在本文件 import Compose 图标
 *   （调用方 `when (iconName)` 映射到 `Icons.Filled.*`，或在 `ui/components` 里做一张表）。
 */
enum class SettingsGroup(
    val id: String,
    val titleKey: String,
    val subtitleKey: String,
    val iconName: String,
) {
    /** 账号与登录：网易云 / QQ 音乐。 */
    ACCOUNT(
        id = "account",
        titleKey = "settingsGroupAccountTitle",
        subtitleKey = "settingsGroupAccountSubtitle",
        iconName = "AccountCircle",
    ),

    /** 通用：语言、旋转、推荐、内部标记。 */
    GENERAL(
        id = "general",
        titleKey = "settingsGroupGeneralTitle",
        subtitleKey = "settingsGroupGeneralSubtitle",
        iconName = "Tune",
    ),

    /** 外观与动效：主题模式 / 主题色 / 主题色来源 / 页面切换动效 / 自定义背景。 */
    APPEARANCE(
        id = "appearance",
        titleKey = "settingsGroupAppearanceTitle",
        subtitleKey = "settingsGroupAppearanceSubtitle",
        iconName = "Palette",
    ),

    /** 播放与音质：音质档位、播放行为、波形可视化（含 v2.8.0 新增项）。 */
    PLAYBACK(
        id = "playback",
        titleKey = "settingsGroupPlaybackTitle",
        subtitleKey = "settingsGroupPlaybackSubtitle",
        iconName = "PlayCircle",
    ),

    /** 歌词：翻译、逐字、字号、TTML、音译、动态字号。 */
    LYRICS(
        id = "lyrics",
        titleKey = "settingsGroupLyricsTitle",
        subtitleKey = "settingsGroupLyricsSubtitle",
        iconName = "Lyrics",
    ),

    /** 存储与缓存：离线缓存上限、库页显示偏好、清理入口。 */
    STORAGE(
        id = "storage",
        titleKey = "settingsGroupStorageTitle",
        subtitleKey = "settingsGroupStorageSubtitle",
        iconName = "Storage",
    ),

    /** 关于：只读入口（本分组**没有**任何 prefs 键，见 probe-settings-inventory §6.7）。 */
    ABOUT(
        id = "about",
        titleKey = "settingsGroupAboutTitle",
        subtitleKey = "settingsGroupAboutSubtitle",
        iconName = "Info",
    ),
}

/**
 * 控件形态。二级页据此选渲染分支，UI 不再自己判断「这一项是什么」。
 *
 * | 取值 | 含义 | UI 行为 |
 * |---|---|---|
 * | [SWITCH] | 布尔开关 | 整行 toggleable 的开关行 |
 * | [CHOICE] | 有限枚举（[SettingsEntry.choices] 给合法取值与顺序） | 下拉 / 分段选择器 |
 * | [QUALITY_CHOICE] | 音质档位（8 档，顺序 = [QualityLadder.LEVELS]） | 音质专用下拉（带 FLAC 不支持提示） |
 * | [TEXT] | 只能手工 / adb 配置的数值或字符串 | 高级区文本框（普通用户不可见） |
 * | [INFO] | **没有可编辑控件**（内部水位、派生数据、账号凭证） | **不得渲染控件行** |
 * | [ACTION] | 无 prefs key 的行为行（登录、清缓存、关于…） | 可点行 / 跳转 |
 */
enum class SettingsEntryType { SWITCH, CHOICE, QUALITY_CHOICE, TEXT, INFO, ACTION }

/**
 * 一个设置项（或一条无 key 的行为行）。
 *
 * ## 三条硬约束（写在这里，也写在单测里）
 *
 * 1. **key 只复制、不重命名**：[key] 逐字抄自现有代码（含 `wifi_quality` / `mobile_quality`
 *    这类裸 prefs 键），默认值抄自各自的单一真相（见每个条目的 `file:line` 注释）。
 * 2. **默认值不落盘**：[defaultValue] 只用于回显/断言。**没有任何 API 会把它写进 prefs**；
 *    回显一律调既有 `read*`（`lyrics_word_animation` 尤其重要：它的读路径带一次性迁移并写回，
 *    用 registry 默认值回显会让老用户的三选一显示错误 —— `LyricsDisplayPrefs.kt:133-145`）。
 * 3. **读写仍走既有入口**：`KeepScreenOnSetting.write` / `RotationSetting.write` /
 *    `VisualizerSetting.write` / `PageTransitionSetting.writeEnabled` / `ArtistReco.setEnabled` /
 *    `LyricsDisplayPrefs.writeXxx` / `PlayerViewModel.setXxx`；`wifi_quality` / `mobile_quality` /
 *    `lyrics_translation` / `lyrics_in_media_session` **继续用原来的裸 `prefs.edit()`**，
 *    迁移时不要顺手给它们补包装（否则等于新增第三套语义）。
 *
 * ## 唯一归属
 *
 * [group] 是**唯一主分组**；双归属项用 [secondaryGroup] 表达「另一个语义归属」，
 * 它**不参与**分组统计与渲染（只供 UI 做交叉引用 / 搜索）。
 */
data class SettingsEntry(
    /** 稳定 id。有 prefs key 的条目 id == key；无 key 的行为行用 `action.*` 命名空间。 */
    val id: String,
    /** 落盘 key，逐字照抄；[SettingsEntryType.ACTION] 行为行为 null（它不落盘）。 */
    val key: String?,
    val type: SettingsEntryType,
    /** 与 [type] 匹配的期望默认值；null = 键缺失时的读取结果就是 null（账号凭证）。 */
    val defaultValue: Any?,
    /** 唯一主分组。 */
    val group: SettingsGroup,
    /** 次级归属（双归属项才有），不参与分组统计。 */
    val secondaryGroup: SettingsGroup? = null,
    /** `Strings` 访问路径；null = 本项没有用户可见文案（UI 不得为它渲染标题）。 */
    val titleKey: String? = null,
    val subtitleKey: String? = null,
    /** [SettingsEntryType.CHOICE] / [SettingsEntryType.QUALITY_CHOICE] 的合法取值（按 UI 顺序）。 */
    val choices: List<Any>? = null,
    /** 所在 prefs 文件；只有账号凭证不在 `ncrust_settings`。 */
    val prefsFile: String = PREFS_SETTINGS,
    /** v2.8.0「波形可视化分级」新增项（迁移前不存在这个键）。 */
    val isNewInV280: Boolean = false,
    /**
     * v2.9.0「统一动效强度」新增项（v2.8.0 的盘上不存在这些键）。
     *
     * 与 [isNewInV280] 并列而不是复用它：那两个断言各自钉住**一个版本**新增了什么，
     * 合并成一个"新键"标志会让「v2.9.0 顺手夹带了无关功能项」不再被机械挡住。
     */
    val isNewInV290: Boolean = false,
    /**
     * v2.9.0 起**不再参与渲染**、只作为迁移源保留的 v2.8.0 键。
     *
     * 判据是机械的：这批键必须同时满足 [isNewInV280] = true 与 [isInternal] = true，
     * 由 `SettingsRegistryTest.v280KeysAreAllLegacyUnderV290` 逐键断言。
     * 它存在的意义是让「哪些键属于旧模型」成为一份**可枚举**的清单 ——
     * 下一个读到 `visualizer_showcase` 的人一眼能看出它已经不是开关了。
     */
    val legacyV290: Boolean = false,
    /**
     * v3.0.0「音频特征驱动动效」新增项（v2.9.0 的盘上不存在这些键）。
     *
     * 与 [isNewInV280] / [isNewInV290] 并列而不是复用：那几条断言各自钉住**一个版本**
     * 新增了什么，合并成一个「新键」标志会让「顺手夹带了无关功能项」不再被机械挡住。
     */
    val isNewInV300: Boolean = false,
    /**
     * v3.1.0「B 站音源」新增项（v3.0.0 的盘上不存在这个键）。
     *
     * 与 [isNewInV280] / [isNewInV290] / [isNewInV300] 并列而不是复用：那几条断言各自
     * 钉住**一个版本**新增了什么，合并成一个「新键」标志会让「顺手夹带了无关功能项」
     * 不再被机械挡住。
     */
    val isNewInV310: Boolean = false,
    /**
     * v3.2.0「界面律动独立开关」新增项（v3.1.0 的盘上不存在这些键）。
     *
     * 与 [isNewInV280] / [isNewInV290] / [isNewInV300] / [isNewInV310] 并列而不是复用：
     * 那几条断言各自钉住**一个版本**新增了什么，合并成一个「新键」标志会让
     * 「顺手夹带了无关功能项」不再被机械挡住。
     */
    val isNewInV320: Boolean = false,
    /**
     * v3.0.0 起**不再参与渲染**的历史键。
     *
     * 目前只有 `motion_degrade_level` / `motion_degrade_log` 两个：它们属于 v2.9.0 的
     * 自动降级机制，而那个机制在 v3.0.0 被整个删除。保留不删是纪律（回滚安装不丢数据），
     * 但要能被一眼认出来 —— 由 `SettingsRegistryTest.v290DegradeKeysAreInertUnderV300` 断言。
     */
    val legacyV300: Boolean = false,
    /** 代码/探针显式标注的「高级 / 实验性」（探针清单的「是否高级/实验性」列）。 */
    val isAdvanced: Boolean = false,
    /**
     * 内部项：**UI 必须跳过**（无 UI 入口的隐藏键 / 遗留迁移源 / 派生数据 / 账号凭证）。
     * 与 [isAdvanced] 是两件事：高级项仍可能被渲染（例如「歌词（高级）」区），内部项永远不渲染。
     */
    val isInternal: Boolean = false,
)

/**
 * 唯一的设置项注册表。
 *
 * 口径与来源：`docs/verification/v2.8.0/probe-settings-inventory.md`（穷尽清单，口径 C = 42 个 key）
 * 与 `probe-settings-structure.md`（7 分组建议）。**不引入任何「音理」功能项**：
 * 一起听服务器 / B 站登录 / 下载管理 / 流量管理 / 网络设置 / 备份与恢复在本文件里一个都没有，
 * 单测 `legacyPrefKeysArePreservedExactly` 的双向等值断言就是这条纪律的机械防线。
 */
object SettingsRegistry {

    /** 音质档位的合法取值（0..7，顺序 = [QualityLadder.LEVELS]）。 */
    val QUALITY_CHOICES: List<Int> = QualityLadder.LEVELS.indices.toList()

    /**
     * 离线缓存上限候选（MB）。逐字抄自 `ui/screen/OfflineCacheOverlay.kt:474`
     * （`OFFLINE_CACHE_LIMIT_MB` 是 private，无法引用），合法区间 64..8192
     * 与 `OfflineAudioCache.MIN_MB/MAX_MB` 一致、默认 512 与 `DEFAULT_MAX_BYTES` 一致。
     */
    val OFFLINE_CACHE_MB_CHOICES: List<Int> =
        listOf(64, 128, 256, 384, 512, 768, 1024, 2048, 4096, 8192)

    /**
     * 全部条目，顺序 = **UI 渲染顺序**（分组顺序 = [SettingsGroup] 声明顺序，
     * 组内顺序 = 本列表内的声明顺序）。
     */
    private val ALL: List<SettingsEntry> = buildList {
        // ── 账号与登录 ────────────────────────────────────────────────────────────────
        // 账号块是**无 key 的行为行**（`ui/screen/UserScreen.kt:335-352`、`:354-424`）；
        // 凭证本身没有可编辑控件，标 INFO + isInternal（登录/登出流程读写它，
        // 探针 §6.1 记录了「派生缓存必须与登录/登出一起清」）。
        add(action("action.account_netease", SettingsGroup.ACCOUNT, "sourceNetease", "loginHint"))
        add(action("action.account_qq", SettingsGroup.ACCOUNT, "sourceQqAccount"))
        // v3.2.0 · P1：B 站账号块。形状与上面两块**逐块一致**（铁律 23：不新建登录体系）。
        // 它的「音质说明」走 source 组的 biliQualityNote —— 如实写「是否解锁无损未验证」。
        add(action("action.account_bili", SettingsGroup.ACCOUNT, "sourceBiliAccount"))
        add(
            pref(
                key = "user_cookie",
                type = SettingsEntryType.INFO,
                default = null, // auth/CookieManager.kt:19 getString(KEY_COOKIE, null)
                group = SettingsGroup.ACCOUNT,
                prefsFile = PREFS_COOKIE,
                internal = true,
            )
        )
        add(
            pref(
                key = "qq_cookie",
                type = SettingsEntryType.INFO,
                default = null, // qq/QqAuthStore.kt:94 getString(KEY_COOKIE, null)
                group = SettingsGroup.ACCOUNT,
                prefsFile = PREFS_QQ,
                internal = true,
            )
        )

        // ── 通用 ─────────────────────────────────────────────────────────────────────
        add(
            pref(
                key = "language_code",
                type = SettingsEntryType.CHOICE,
                default = "zh-CN", // ui/i18n/LanguageManager.kt:30
                group = SettingsGroup.GENERAL,
                titleKey = "languageSectionTitle",
                choices = listOf("zh-CN", "zh-TW", "en-US", "ja-JP", "ja-MY", "ko-KP", "de-DE", "ru-RU"),
            )
        )
        add(
            pref(
                key = "auto_rotate",
                type = SettingsEntryType.SWITCH,
                default = true, // RotationSetting.DEFAULT_ENABLED（RotationSetting.kt:42）
                group = SettingsGroup.GENERAL,
                titleKey = "autoRotateLabel",
                subtitleKey = "autoRotateDescription",
            )
        )
        add(
            pref(
                key = "battery_prompt_done",
                type = SettingsEntryType.INFO,
                default = false, // MainActivity.kt:390 首启一次性标记
                group = SettingsGroup.GENERAL,
                internal = true,
            )
        )
        // 音乐人推荐：probe-settings-structure.md §2 建议放「通用」（它不改变播放行为，
        // 只决定首页是否出现推荐卡）；probe-settings-inventory.md §6.3 把它算在「播放」——
        // 两种都说得通，这里按结构探针的建议取「通用」，并在此显式记录这个选择。
        add(
            pref(
                key = "artist_reco_enabled",
                type = SettingsEntryType.SWITCH,
                default = false, // reco/ArtistReco.kt:72 isEnabled → getBoolean(KEY_ENABLED, false)
                group = SettingsGroup.GENERAL,
                titleKey = "artistRecoTitle",
                subtitleKey = "artistRecoDesc",
            )
        )
        add(
            pref(
                key = "artist_reco_target_id",
                type = SettingsEntryType.TEXT,
                default = 0L, // reco/ArtistReco.kt:78 getLong(KEY_TARGET, 0L)
                group = SettingsGroup.GENERAL,
                advanced = true,
                internal = true, // 无 UI 调用方（grep setTarget 只有定义处）
            )
        )
        add(
            pref(
                key = "artist_reco_anchor_ids",
                type = SettingsEntryType.TEXT,
                default = "", // reco/ArtistReco.kt:84 getString(KEY_ANCHORS, "")
                group = SettingsGroup.GENERAL,
                advanced = true,
                internal = true, // 无 UI 调用方（grep setManualAnchors 只有定义处）
            )
        )
        add(
            pref(
                key = "artist_reco_auto_anchor_ids",
                type = SettingsEntryType.INFO,
                default = "", // reco/ArtistReco.kt:91 派生数据（7 天 TTL 自动重建）
                group = SettingsGroup.GENERAL,
                internal = true,
            )
        )
        add(
            pref(
                key = "artist_reco_auto_anchor_at",
                type = SettingsEntryType.INFO,
                default = 0L, // reco/ArtistReco.kt:94 派生时间戳
                group = SettingsGroup.GENERAL,
                internal = true,
            )
        )
        add(action("action.background_activity", SettingsGroup.GENERAL, "batteryTitle"))
        // v3.1.0 · B：**B 站音源的独立开关**（铁律 24：用户可选择是否启用）。
        //
        // 放在「通用」而不是「账号与登录」：它不是一个账号（B 站本轮没有登录接入），
        // 而是一个**内容源开关**。放进账号组会让那一页的语义从「登录」变成「登录 + 杂项」。
        //
        // 默认**关闭**（`BiliPrefs.DEFAULT_ENABLED = false`）。读写走
        // `BiliPrefs.setEnabled` / `BiliPrefs.read`（唯一入口，会同步刷新进程内镜像）。
        add(
            pref(
                key = "bilibili_enabled",
                type = SettingsEntryType.SWITCH,
                default = false, // bili/BiliPrefs.kt:126 DEFAULT_ENABLED
                group = SettingsGroup.GENERAL,
                titleKey = "bilibiliEnabledLabel",
                subtitleKey = "bilibiliEnabledDescription",
                // 它是本版新增的键：盘上不存在旧值，所以「缺键 = 关闭」是唯一解释。
                newInV310 = true,
            )
        )

        // ── 外观与动效 ────────────────────────────────────────────────────────────────
        add(
            pref(
                key = "theme_mode",
                type = SettingsEntryType.CHOICE,
                default = "SYSTEM", // ui/theme/ThemeManager.kt:46 getOrDefault(ThemeMode.SYSTEM)
                group = SettingsGroup.APPEARANCE,
                titleKey = "themeModeSectionTitle",
                choices = listOf("SYSTEM", "DARK", "LIGHT"),
            )
        )
        add(
            pref(
                key = "theme_color_index",
                type = SettingsEntryType.CHOICE,
                default = 0, // ui/theme/ThemeManager.kt:61 getInt(KEY_THEME_INDEX, 0)
                group = SettingsGroup.APPEARANCE,
                titleKey = "themeSectionTitle",
                // 与 themeColorPresets 的 6 个预设一一对应（ThemeManager.kt:26-33）。
                choices = (0..5).toList(),
            )
        )
        add(
            pref(
                key = "accent_source",
                type = SettingsEntryType.CHOICE,
                default = "PRESET", // ui/theme/AccentSource.kt:36 getOrDefault(AccentSource.PRESET)
                group = SettingsGroup.APPEARANCE,
                titleKey = "accentSourceSectionTitle",
                choices = listOf("PRESET", "COVER", "SYSTEM"),
            )
        )
        add(
            pref(
                key = "page_transition_enabled",
                type = SettingsEntryType.SWITCH,
                default = true, // PageTransitionSetting.DEFAULT_ENABLED（PageTransitionSetting.kt:74）
                group = SettingsGroup.APPEARANCE,
                titleKey = "motion.pageTransitionLabel",
                subtitleKey = "motion.pageTransitionDescription",
            )
        )
        add(
            pref(
                key = "custom_bg_enabled",
                type = SettingsEntryType.SWITCH,
                default = false, // ui/theme/BackgroundImageManager.kt:57 getBoolean(KEY_ENABLED, false)
                group = SettingsGroup.APPEARANCE,
                titleKey = "bgSectionTitle",
            )
        )

        add(
            pref(
                key = "audio_visualizer",
                type = SettingsEntryType.SWITCH,
                default = true, // VisualizerSetting.DEFAULT_ENABLED（ui/player/AudioVisualizer.kt:40）
                group = SettingsGroup.APPEARANCE,
                titleKey = "audioVisualizerLabel",
                subtitleKey = "audioVisualizerDescription",
            )
        )

        // ⚠️ **v3.0.0 起：下面这一组「视觉/动效」设置项的主分组是
        // `APPEARANCE`（外观与动效），不是 `PLAYBACK`（播放与音质）。**
        // 它们控制的是"画面长什么样"，与音质档位/无缝播放/禁止熄屏那几项没有任何关系 ——
        // 放在播放与音质页会让用户在"我要调动效"时去翻音质设置。
        // 分组只影响**渲染在哪一页**，键名 / 语义 / 默认值一个都没动。
        //
        // v2.8.0 新增：波形可视化分级。键名由并行的「波形任务」定义并提供读写 API
        // （docs/verification/v2.8.0/probe-waveform-tier.md §3.3）；本文件**只定义条目**，
        // 不实现任何读写。⚠️ 除 visualizer_tier（默认 1，probe §3.3）外的默认值
        // 在探针里没有给出，这里取保守值（C 档炫技一律默认关），以波形任务落地为准。
        add(
            pref(
                key = "visualizer_tier",
                type = SettingsEntryType.CHOICE,
                default = 1, // 读路径默认仍由 VisualizerTier.defaultTier 解析（低端机 → 0）
                group = SettingsGroup.APPEARANCE,
                choices = listOf(0, 1, 2), // 0 = T0 简洁 / 1 = T1 精致 / 2 = T2 炫技
                newInV280 = true,
                // v2.9.0：**降级为迁移源**。统一「动效强度」接管渲染之后，这个键只在
                // `MotionPrefs.migrate` 里被读一次（搬进 `motion_tier`）。留着不删是纪律
                // （回滚安装不丢用户数据），但不能有 UI 入口 —— 两个键都能改档位就是双轨。
                internal = true,
                legacyV290 = true,
            )
        )
        add(
            pref(
                key = "visualizer_showcase",
                type = SettingsEntryType.SWITCH,
                default = false, // 未确认（探针未定义该键的默认值）；C 档炫技默认关
                group = SettingsGroup.APPEARANCE,
                advanced = true,
                newInV280 = true,
                // v2.9.0：细分开关合并进档位 ⇒ 只作为**迁移源**保留，不再有 UI 入口。
                internal = true,
                legacyV290 = true,
            )
        )
        add(
            pref(
                key = "visualizer_shockwave",
                type = SettingsEntryType.SWITCH,
                default = false, // 未确认；同上
                group = SettingsGroup.APPEARANCE,
                advanced = true,
                newInV280 = true,
                // v2.9.0：细分开关合并进档位 ⇒ 只作为**迁移源**保留，不再有 UI 入口。
                internal = true,
                legacyV290 = true,
            )
        )
        add(
            pref(
                key = "visualizer_particles",
                type = SettingsEntryType.SWITCH,
                default = false, // 未确认；同上
                group = SettingsGroup.APPEARANCE,
                advanced = true,
                newInV280 = true,
                // v2.9.0：细分开关合并进档位 ⇒ 只作为**迁移源**保留，不再有 UI 入口。
                internal = true,
                legacyV290 = true,
            )
        )
        add(
            pref(
                key = "visualizer_perspective",
                type = SettingsEntryType.SWITCH,
                default = false, // 未确认；同上
                group = SettingsGroup.APPEARANCE,
                advanced = true,
                newInV280 = true,
                // v2.9.0：细分开关合并进档位 ⇒ 只作为**迁移源**保留，不再有 UI 入口。
                internal = true,
                legacyV290 = true,
            )
        )
        add(
            pref(
                key = "visualizer_drag",
                type = SettingsEntryType.SWITCH,
                default = false, // 未确认；同上
                group = SettingsGroup.APPEARANCE,
                advanced = true,
                newInV280 = true,
                // v2.9.0：细分开关合并进档位 ⇒ 只作为**迁移源**保留，不再有 UI 入口。
                internal = true,
                legacyV290 = true,
            )
        )
        // v2.9.0 补：v2.8.0 实际写了 **8 个**键，但 registry 当时只枚举了 7 个 ——
        // `visualizer_auto_downgraded` 既不在 registry、也不在 42 键清单里，于是
        // 「不丢项」的双向等值断言**盖不到它**（探针 probe-tier-migration.md §1.3 记录了这个洞）。
        // v2.9.0 的迁移仍然读它，所以它必须进入枚举集合：Internal（无 UI 入口）+ legacyV290。
        add(
            pref(
                key = "visualizer_auto_downgraded",
                type = SettingsEntryType.INFO,
                default = false, // VisualizerPrefs.DEFAULT_AUTO_DOWNGRADED
                group = SettingsGroup.APPEARANCE,
                advanced = true,
                internal = true,
                newInV280 = true,
                legacyV290 = true,
            )
        )
        add(
            pref(
                key = "visualizer_tier_version",
                type = SettingsEntryType.INFO,
                default = 1, // probe-waveform-tier.md §3.3：迁移水位，范式同 quality_ladder_version
                group = SettingsGroup.APPEARANCE,
                advanced = true,
                internal = true,
                newInV280 = true,
                // v2.9.0：迁移源（`MotionPrefs` 用的是自己的 `motion_version` 水位，
                // 这个键保留只为「不丢项」与回滚）。原本就没有 UI 入口，标 legacy 是为了
                // 让"哪些键属于 v2.8.0 的旧模型"在一处可枚举。
                legacyV290 = true,
            )
        )

        // ── v2.9.0 新增：统一「动效强度」（4 键）─────────────────────────────────────
        // 键名与默认值的单一真相是 `ui/player/motion/MotionPrefs.kt`；本文件只定义条目、
        // **不实现任何读写**（读写仍走 MotionPrefs.setTier / setUiMotionEnabled）。
        add(
            pref(
                key = "motion_tier",
                type = SettingsEntryType.CHOICE,
                // 默认值同样是**解析出来的**（`MotionPrefs.readTier` 缺 key 时用设备判据），
                // 这里写精致档只是为了回显/断言的期望值，与 v2.8.0 的口径一致。
                default = MotionIntensity.REFINED,
                group = SettingsGroup.APPEARANCE,
                titleKey = "waveform.motionIntensityLabel",
                subtitleKey = "waveform.motionIntensityDescription",
                choices = listOf(
                    MotionIntensity.SIMPLE,
                    MotionIntensity.REFINED,
                    MotionIntensity.SHOWCASE,
                ),
                newInV290 = true,
            )
        )
        add(
            pref(
                key = "ui_motion_enabled",
                type = SettingsEntryType.SWITCH,
                default = MotionPrefs.DEFAULT_UI_MOTION, // true（铁律 22：A 档默认开，但保留总开关）
                group = SettingsGroup.APPEARANCE,
                titleKey = "waveform.uiMotionLabel",
                subtitleKey = "waveform.uiMotionDescription",
                newInV290 = true,
            )
        )
        add(
            pref(
                key = "motion_degrade_level",
                type = SettingsEntryType.INFO,
                default = MotionDegrade.NONE,
                group = SettingsGroup.APPEARANCE,
                advanced = true,
                internal = true, // 派生状态：帧时间实测写它，没有 UI 入口
                newInV290 = true,
                // v3.0.0：**自动降级机制已整个删除** ⇒ 这个键不再参与任何渲染决策。
                // 留着不删是纪律（回滚安装不丢数据），标 legacy 是为了让「它已经不是开关了」
                // 在枚举里一眼可见（与 v2.9.0 把 visualizer_showcase 标 legacyV290 同一手法）。
                legacyV300 = true,
            )
        )
        add(
            pref(
                key = "motion_degrade_log",
                type = SettingsEntryType.TEXT,
                default = MotionPrefs.DEFAULT_DEGRADE_LOG, // ""（从未降级）
                group = SettingsGroup.APPEARANCE,
                advanced = true,
                internal = true, // 诊断用：见 motion_degrade_level 的说明
                newInV290 = true,
                legacyV300 = true,
            )
        )
        // ── v3.0.0 新增：每个新动效的独立开关（5 键，铁律 26）───────────────────────
        // 键名与默认值的单一真相是 `ui/player/motion/MotionPrefs.kt`；本文件只定义条目、
        // **不实现任何读写**（读写走 MotionPrefs.setSwitch + KEY_* 常量）。
        // 默认全开：档位才是「这一档有没有这类动效」的判据，开关只做 AND
        // （缺 key 解析成开 = 升级后观感只随档位表变化，不会因为新键没写而少画东西）。
        add(
            pref(
                key = "motion_shockwave",
                type = SettingsEntryType.SWITCH,
                default = MotionPrefs.DEFAULT_SWITCH,
                group = SettingsGroup.APPEARANCE,
                titleKey = "waveform.motionShockwaveLabel",
                subtitleKey = "waveform.motionShockwaveDescription",
                newInV300 = true,
            )
        )
        add(
            pref(
                key = "motion_halo",
                type = SettingsEntryType.SWITCH,
                default = MotionPrefs.DEFAULT_SWITCH,
                group = SettingsGroup.APPEARANCE,
                titleKey = "waveform.motionHaloLabel",
                subtitleKey = "waveform.motionHaloDescription",
                newInV300 = true,
            )
        )
        add(
            pref(
                key = "motion_particles",
                type = SettingsEntryType.SWITCH,
                default = MotionPrefs.DEFAULT_SWITCH,
                group = SettingsGroup.APPEARANCE,
                titleKey = "waveform.motionParticlesLabel",
                subtitleKey = "waveform.motionParticlesDescription",
                newInV300 = true,
            )
        )
        add(
            pref(
                key = "motion_wave_bands",
                type = SettingsEntryType.SWITCH,
                default = MotionPrefs.DEFAULT_SWITCH,
                group = SettingsGroup.APPEARANCE,
                titleKey = "waveform.motionWaveBandsLabel",
                subtitleKey = "waveform.motionWaveBandsDescription",
                newInV300 = true,
            )
        )
        add(
            pref(
                key = "motion_breathing",
                type = SettingsEntryType.SWITCH,
                default = MotionPrefs.DEFAULT_SWITCH,
                group = SettingsGroup.APPEARANCE,
                titleKey = "waveform.motionBreathingLabel",
                subtitleKey = "waveform.motionBreathingDescription",
                newInV300 = true,
            )
        )

        add(
            pref(
                key = "motion_version",
                type = SettingsEntryType.INFO,
                // 缺 key 的读数是 `VERSION_PRE_V290`（= 0 = 「还没搬过」），
                // 不是「搬到 0」—— 这个两义性与 v1.9.3 的「缺失 vs 空」同源，必须写清。
                default = MotionPrefs.VERSION_PRE_V290,
                group = SettingsGroup.APPEARANCE,
                advanced = true,
                internal = true,
                newInV290 = true,
            )
        )

        // ── v3.2.0 新增：界面律动（节拍驱动）那一层的闸（4 键）────────────────────────
        // 键名与默认值的单一真相仍是 `ui/player/motion/MotionPrefs.kt`；本文件只定义条目。
        //
        // 为什么需要这一组（P1 · 铁律 22）：`ui_motion_enabled` 是**总闸**（关掉 =
        // A/B/C 三层全不挂载、背景回纯色、零帧时钟），而「界面律动」只是其中
        // **驱动量来自 MotionEnvelope 的节拍/强拍/响度** 的那一类（背景呼吸 / 封面浮动 /
        // 歌词律动 / 控制条脉冲 / 封面 3D）。总闸粒度太粗：用户只想让画面别跟着鼓点抖，
        // 但想留着冲击波与粒子时，此前没有任何办法（关总闸会把它们一起关掉）。
        //
        // 语义：**有效 = 档位允许 AND 总闸 AND (律动类 ? 律动闸 : true) AND 逐项开关**。
        // 归类判据是「渲染路径里是否读 `MotionClock.pulse()` / `MotionClock.level()`」，
        // 逐项表见 `docs/verification/v3.2.0/probe-ui-jitter.md` §5。
        //
        // 文案走 `WaveformStrings` 的 8 条新字段（v3.2.0 已补齐 8 种语言）——
        // 与 `motion_shockwave` / `motion_breathing` 等既有动效开关同一组，不新开文案组。
        add(
            pref(
                key = "motion_rhythm_enabled",
                type = SettingsEntryType.SWITCH,
                default = MotionPrefs.DEFAULT_SWITCH,
                group = SettingsGroup.APPEARANCE,
                titleKey = "waveform.motionRhythmLabel",
                subtitleKey = "waveform.motionRhythmDescription",
                newInV320 = true,
            )
        )
        add(
            pref(
                key = "motion_cover_float",
                type = SettingsEntryType.SWITCH,
                default = MotionPrefs.DEFAULT_SWITCH,
                group = SettingsGroup.APPEARANCE,
                titleKey = "waveform.motionCoverFloatLabel",
                subtitleKey = "waveform.motionCoverFloatDescription",
                newInV320 = true,
            )
        )
        add(
            pref(
                key = "motion_lyric_pulse",
                type = SettingsEntryType.SWITCH,
                default = MotionPrefs.DEFAULT_SWITCH,
                group = SettingsGroup.APPEARANCE,
                titleKey = "waveform.motionLyricPulseLabel",
                subtitleKey = "waveform.motionLyricPulseDescription",
                newInV320 = true,
            )
        )
        add(
            pref(
                key = "motion_bar_pulse",
                type = SettingsEntryType.SWITCH,
                default = MotionPrefs.DEFAULT_SWITCH,
                group = SettingsGroup.APPEARANCE,
                titleKey = "waveform.motionBarPulseLabel",
                subtitleKey = "waveform.motionBarPulseDescription",
                newInV320 = true,
            )
        )

        // ── 播放与音质 ────────────────────────────────────────────────────────────────
        add(
            pref(
                key = "wifi_quality",
                type = SettingsEntryType.QUALITY_CHOICE,
                default = 3, // ui/screen/UserScreen.kt:146 getInt("wifi_quality", 3) = LEVELS[3] = lossless
                group = SettingsGroup.PLAYBACK,
                titleKey = "wifiQualityLabel",
                choices = QUALITY_CHOICES,
            )
        )
        add(
            pref(
                key = "mobile_quality",
                type = SettingsEntryType.QUALITY_CHOICE,
                default = 1, // UserScreen.kt:147 getInt("mobile_quality", 1) = LEVELS[1] = higher
                group = SettingsGroup.PLAYBACK,
                titleKey = "mobileQualityLabel",
                choices = QUALITY_CHOICES,
            )
        )
        add(
            pref(
                key = "gapless_playback",
                type = SettingsEntryType.SWITCH,
                default = true, // UserScreen.kt:148 getBoolean("gapless_playback", true)
                group = SettingsGroup.PLAYBACK,
                titleKey = "gaplessSectionTitle",
                subtitleKey = "gaplessDescription",
            )
        )
        add(
            pref(
                key = "keep_screen_on",
                type = SettingsEntryType.SWITCH,
                default = true, // KeepScreenOnSetting.DEFAULT_ENABLED（KeepScreenOnSetting.kt:36）
                group = SettingsGroup.PLAYBACK,
                titleKey = "keepScreenOnLabel",
                subtitleKey = "keepScreenOnHint",
            )
        )

        // §4.5：与 lyrics_in_media_session 语义相邻但**不是同一个开关**（隐藏回退阀，默认 true）。
        // 主归属按消费方（PlaybackService 的媒体会话 metadata）取「播放与音质」，
        // 次级归属标「歌词」——与 lyrics_in_media_session 正好互为镜像。
        add(
            pref(
                key = "session_metadata_lyrics",
                type = SettingsEntryType.SWITCH,
                default = true, // player/PlaybackService.kt:1406 getBoolean("session_metadata_lyrics", true)
                group = SettingsGroup.PLAYBACK,
                secondaryGroup = SettingsGroup.LYRICS,
                advanced = true,
                internal = true, // 无写入点（PlaybackService.kt:1395-1399 注释只给 adb 写法）
            )
        )
        add(
            pref(
                key = "live_update_enabled",
                type = SettingsEntryType.SWITCH,
                default = true, // player/LiveUpdateNotifier.kt:63 getBoolean(KEY_ENABLED, true)
                group = SettingsGroup.PLAYBACK,
                advanced = true,
                internal = true, // 注释称「用户可在 prefs 里关掉」但没有设置行
            )
        )
        add(
            pref(
                key = "live_update_probed",
                type = SettingsEntryType.INFO,
                default = false, // player/LiveUpdateNotifier.kt:64
                group = SettingsGroup.PLAYBACK,
                internal = true, // 纯内部「只打一次日志」标记
            )
        )
        // ⚠️ 迁移纪律：quality_ladder_version 必须与 wifi_quality / mobile_quality **同批迁移**，
        // 否则 QualityLadder.migrate 会重跑（用户选的杜比会再 +1 变成越界）。
        add(
            pref(
                key = "quality_ladder_version",
                type = SettingsEntryType.INFO,
                default = 1, // player/QualityLadder.kt:54 getInt(KEY_VERSION, 1)
                group = SettingsGroup.PLAYBACK,
                internal = true,
            )
        )

        // ── 歌词 ─────────────────────────────────────────────────────────────────────
        add(
            pref(
                key = "lyrics_translation",
                type = SettingsEntryType.SWITCH,
                default = true, // ui/screen/UserScreen.kt:156 getBoolean("lyrics_translation", true)
                group = SettingsGroup.LYRICS,
                titleKey = "lyricsTranslationLabel",
            )
        )
        add(
            pref(
                key = "lyrics_word_animation",
                type = SettingsEntryType.CHOICE,
                default = LyricsWordAnimationMode.GRADIENT_SWEEP, // lyric/LyricsDisplayPrefs.kt:135
                group = SettingsGroup.LYRICS,
                titleKey = "lyricsWordAnimationLabel",
                choices = listOf(
                    LyricsWordAnimationMode.GRADIENT_SWEEP,
                    LyricsWordAnimationMode.HARD_CUT,
                    LyricsWordAnimationMode.OFF,
                ),
            )
        )
        add(
            pref(
                key = "lyrics_sweep_quality",
                type = SettingsEntryType.CHOICE,
                default = LyricsSweepQuality.AUTO, // lyric/LyricsDisplayPrefs.kt:152
                group = SettingsGroup.LYRICS,
                titleKey = "lyricsSweepQualityLabel",
                choices = listOf(LyricsSweepQuality.AUTO, LyricsSweepQuality.SOFT, LyricsSweepQuality.EDGE),
            )
        )
        add(
            pref(
                key = "lyrics_font_scale",
                type = SettingsEntryType.CHOICE,
                default = LyricsDisplayPrefs.FONT_SCALE_DEFAULT, // lyric/LyricsDisplayPrefs.kt:118
                group = SettingsGroup.LYRICS,
                titleKey = "lyricsFontScaleLabel",
                choices = LyricsDisplayPrefs.FONT_SCALE_STEPS, // 0.7 / 0.85 / 1.0 / 1.2 / 1.5
            )
        )
        // 双归属（probe-settings-inventory §6.5 / §6.6）：主归属「歌词」，
        // 次级归属「播放与音质」——它决定当前歌词行要不要推给 PlaybackService。
        add(
            pref(
                key = "lyrics_in_media_session",
                type = SettingsEntryType.SWITCH,
                default = false, // ui/screen/UserScreen.kt:164 getBoolean("lyrics_in_media_session", false)
                group = SettingsGroup.LYRICS,
                secondaryGroup = SettingsGroup.PLAYBACK,
                titleKey = "lyricsInMediaSessionLabel",
                subtitleKey = "lyricsInMediaSessionHint",
            )
        )
        add(
            pref(
                key = "lyrics_ttml_enabled",
                type = SettingsEntryType.SWITCH,
                default = true, // lyric/LyricsDisplayPrefs.kt:166 readTtmlEnabled → true
                group = SettingsGroup.LYRICS,
                titleKey = "lyricsTtmlEnabledLabel",
            )
        )
        add(
            pref(
                key = "lyrics_ttml_first",
                type = SettingsEntryType.SWITCH,
                default = true, // lyric/LyricsDisplayPrefs.kt:173 readTtmlFirst → true
                group = SettingsGroup.LYRICS,
                titleKey = "lyricsTtmlFirstLabel",
            )
        )
        add(
            pref(
                key = "lyrics_romanization",
                type = SettingsEntryType.SWITCH,
                default = false, // lyric/LyricsDisplayPrefs.kt:187 readRomanization → false
                group = SettingsGroup.LYRICS,
                titleKey = "lyricsRomanizationLabel",
                subtitleKey = "lyricsRomanizationHint",
            )
        )
        add(
            pref(
                key = "lyrics_dynamic_font",
                type = SettingsEntryType.SWITCH,
                default = false, // lyric/LyricsDisplayPrefs.kt:195 readDynamicFont → false
                group = SettingsGroup.LYRICS,
                titleKey = "dynamicFontLabel",
                subtitleKey = "dynamicFontHint",
                advanced = true, // 代码与 UI 文案都显式标注「实验性」
            )
        )
        add(
            pref(
                key = "lyrics_word_by_word",
                type = SettingsEntryType.SWITCH,
                default = true, // lyric/LyricsDisplayPrefs.kt:137 读时默认（一次性迁移源）
                group = SettingsGroup.LYRICS,
                titleKey = "lyricsWordByWordLabel", // 文案保留但已无 UI 入口（Strings.kt:1132-1136）
                internal = true, // 遗留键：只被 lyrics_word_animation 的迁移消费，无写入点
            )
        )
        add(
            pref(
                key = "lyrics_sweep_fade_em",
                type = SettingsEntryType.TEXT,
                default = LyricsSweepConfig.DEFAULT.fadeEm, // lyric/SweepTrack.kt:109
                group = SettingsGroup.LYRICS,
                advanced = true,
                internal = true, // 高级覆盖键：只能 adb / root 写，普通用户永远不会碰到
            )
        )
        add(
            pref(
                key = "lyrics_sweep_inactive_alpha",
                type = SettingsEntryType.TEXT,
                default = LyricsSweepConfig.DEFAULT.inactiveAlpha, // lyric/SweepTrack.kt:112
                group = SettingsGroup.LYRICS,
                advanced = true,
                internal = true,
            )
        )
        add(
            pref(
                key = "lyrics_sweep_easing",
                type = SettingsEntryType.CHOICE,
                default = LyricsSweepConfig.DEFAULT.easing.ordinal, // lyric/SweepTrack.kt:115 → LINEAR
                group = SettingsGroup.LYRICS,
                choices = SweepEasing.entries.map { it.ordinal },
                advanced = true,
                internal = true,
            )
        )

        // ── 存储与缓存 ────────────────────────────────────────────────────────────────
        add(
            pref(
                key = "offline_cache_mb",
                type = SettingsEntryType.CHOICE,
                default = 512, // cache/OfflineAudioCache.kt:70（DEFAULT_MAX_BYTES / 1MiB，合法 64..8192）
                group = SettingsGroup.STORAGE,
                titleKey = "offlineCacheLimitLabel",
                choices = OFFLINE_CACHE_MB_CHOICES,
            )
        )
        // 库页显示偏好 4 项：probe-settings-inventory §6.6 明确说塞进「存储与缓存」是**语义错配**
        // （建议单开「库页 / 列表显示」组），但本版固定 7 组 → 主归属沿用清单的分组映射表
        // （存储与缓存），次级归属标「外观与动效」把这个语义关系显式留痕。
        add(
            pref(
                key = "library_playlist_layout",
                type = SettingsEntryType.CHOICE,
                default = 0, // ui/screen/PlaylistLayoutSetting.kt:57 PlaylistLayout.DEFAULT_INDEX = CARD
                group = SettingsGroup.STORAGE,
                secondaryGroup = SettingsGroup.APPEARANCE,
                choices = listOf(0, 1), // 0 = CARD / 1 = LIST
                // 无 titleKey：设置页从来没有这一行的文案，库页那个切换器只有两个 chip
                // （`strings.playlists.layoutCard` / `layoutList`，`LibraryPlaylistsTab.kt:507-545`）。
                // 结构探针 §1.2 也建议这 4 项**不迁移**到设置页（入口留在库页）。UI 集成阶段若要补文案，
                // 再给这 4 条加 titleKey 即可（本文件是唯一落点）。
            )
        )
        add(
            pref(
                key = "library_section_collapsed_local",
                type = SettingsEntryType.SWITCH,
                default = false, // ui/screen/LibrarySectionFoldSetting.kt:138 getBoolean(key, false)
                group = SettingsGroup.STORAGE,
                secondaryGroup = SettingsGroup.APPEARANCE,
            )
        )
        add(
            pref(
                key = "library_section_collapsed_netease",
                type = SettingsEntryType.SWITCH,
                default = false,
                group = SettingsGroup.STORAGE,
                secondaryGroup = SettingsGroup.APPEARANCE,
            )
        )
        add(
            pref(
                key = "library_section_collapsed_qq",
                type = SettingsEntryType.SWITCH,
                default = false,
                group = SettingsGroup.STORAGE,
                secondaryGroup = SettingsGroup.APPEARANCE,
            )
        )
        add(action("action.storage_clear_cache", SettingsGroup.STORAGE, "clearCache"))
        add(action("action.storage_offline_manage", SettingsGroup.STORAGE, "offlineCacheManageLabel"))

        // ── 关于（没有任何 prefs 项，探针 §6.7：这是正确结果而非遗漏）──────────────────
        add(action("action.about_open", SettingsGroup.ABOUT, "aboutButton"))
    }

    private val BY_ID: Map<String, SettingsEntry> = ALL.associateBy { it.id }

    /** 一级卡片顺序。 */
    fun groups(): List<SettingsGroup> = SettingsGroup.entries.toList()

    /** 某分组内的条目（**渲染顺序**，已按声明顺序排好）。 */
    fun entriesOf(groupId: String): List<SettingsEntry> = ALL.filter { it.group.id == groupId }

    /** 某分组内的条目（枚举重载）。 */
    fun entriesOf(group: SettingsGroup): List<SettingsEntry> = entriesOf(group.id)

    /** 全量条目（分组顺序 + 组内顺序 = UI 渲染顺序）。 */
    fun allEntries(): List<SettingsEntry> = ALL

    /** 按 id 取条目；未知 id 返回 null（不抛异常）。 */
    fun entryById(id: String): SettingsEntry? = BY_ID[id]

    /** 按落盘 key 取条目（有 key 的条目才查得到）；未知 key 返回 null。 */
    fun entryByKey(key: String): SettingsEntry? = ALL.firstOrNull { it.key == key }

    // ── 私有构造助手 ──────────────────────────────────────────────────────────────────

    /** 有 prefs key 的条目：id == key（避免两套名字漂移）。 */
    private fun pref(
        key: String,
        type: SettingsEntryType,
        default: Any?,
        group: SettingsGroup,
        titleKey: String? = null,
        subtitleKey: String? = null,
        choices: List<Any>? = null,
        prefsFile: String = PREFS_SETTINGS,
        secondaryGroup: SettingsGroup? = null,
        newInV280: Boolean = false,
        newInV290: Boolean = false,
        legacyV290: Boolean = false,
        newInV300: Boolean = false,
        legacyV300: Boolean = false,
        newInV310: Boolean = false,
        newInV320: Boolean = false,
        advanced: Boolean = false,
        internal: Boolean = false,
    ): SettingsEntry = SettingsEntry(
        id = key,
        key = key,
        type = type,
        defaultValue = default,
        group = group,
        secondaryGroup = secondaryGroup,
        titleKey = titleKey,
        subtitleKey = subtitleKey,
        choices = choices,
        prefsFile = prefsFile,
        isNewInV280 = newInV280,
        isNewInV290 = newInV290,
        legacyV290 = legacyV290,
        isNewInV300 = newInV300,
        legacyV300 = legacyV300,
        isNewInV310 = newInV310,
        isNewInV320 = newInV320,
        isAdvanced = advanced,
        isInternal = internal,
    )

    /** 无 prefs key 的行为行（账号块 / 后台运行 / 清缓存 / 离线缓存管理 / 关于）。 */
    private fun action(
        id: String,
        group: SettingsGroup,
        titleKey: String,
        subtitleKey: String? = null,
    ): SettingsEntry = SettingsEntry(
        id = id,
        key = null,
        type = SettingsEntryType.ACTION,
        defaultValue = null,
        group = group,
        titleKey = titleKey,
        subtitleKey = subtitleKey,
    )
}
