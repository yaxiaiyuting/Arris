/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0「设置界面二级菜单重构」阶段 3：**二级页渲染计划**（纯数据 + 纯函数）。
 */

package com.takahashirinta.ncrust.ui.settings

/**
 * 一个设置条目在某张二级页上用什么控件渲染。
 *
 * Compose 层用 `when (kind)` 选分支 —— 枚举是穷尽的，所以「新增一种控件形态却忘了渲染」
 * 是**编译错误**，而不是运行时静默少一行。
 *
 * 两个「不渲染」的取值（[INTERNAL] / [HOSTED_ELSEWHERE]）也在枚举里：这样
 * 「这个条目到底渲不渲染、在哪渲染」只有一个问题、一处答案。
 */
enum class SettingsRowKind {
    /** ncm 账号块（头像 / 昵称 / UID + 登录、登出、扫码授权）。 */
    ACCOUNT_PROFILE,

    /** qm 账号块（登录态 + 会员角标 + 登录/登出/手机号登录）。 */
    ACCOUNT_QQ,

    /**
     * v3.2.0 · P1：B 站账号块（登录态 + 昵称 + 扫码登录/登出）。
     *
     * 不合并进 [ACCOUNT_QQ]：两者的**登录通道完全不同**（B 站是 passport 直连扫码、
     * 不需要 WebView 换票），合并会让渲染分支里出现「按音源分叉」的第二处真相。
     */
    ACCOUNT_BILI,

    /** 语言下拉（8 个 locale，切换后由 Activity 重放 splash 重建全部文案）。 */
    LANGUAGE_DROPDOWN,

    /** 布尔开关行。 */
    SWITCH,

    /** 普通有限枚举下拉（离线缓存上限等）。 */
    DROPDOWN,

    /** 音质档位下拉（8 档 + FLAC 不支持提示 + 立即生效）。 */
    QUALITY_DROPDOWN,

    /** 动效强度下拉（简洁 / 精致 / 炫技；缺 key 时回显**解析出的设备默认档**）。v2.9.0 起同时驱动波形与界面动效。 */
    TIER_DROPDOWN,

    /** 主题模式三选一（跟随系统 / 深色 / 浅色）。 */
    THEME_MODE_SEGMENT,

    /** 主题色六选一（色块）。 */
    THEME_COLOR_SWATCHES,

    /** 主题色来源三选一（预设 / 封面 / 系统）+ 条件式「重读系统色」。 */
    ACCENT_SOURCE_SEGMENT,

    /** 自定义背景图（选图 / 更换 / 移除）。 */
    BACKGROUND_PICKER,

    /** 无 key 的跳转行（跳系统「允许后台活动」）。 */
    ACTION_JUMP,

    /** 缓存占用三项 + 「清除缓存」（二次确认弹窗）。 */
    CLEAR_CACHE,

    /** 「离线缓存管理」入口（全屏 Dialog，含逐曲删除与上限选择）。 */
    OFFLINE_MANAGER,

    /** 「关于」入口。 */
    ABOUT_OPEN,

    /**
     * **不渲染**：`isInternal == true` 的条目（隐藏键 / 遗留迁移源 / 派生数据 / 账号凭证）。
     *
     * 它们仍然在 [SettingsRegistry] 里 —— 那是「不丢项」的机械防线
     * （`SettingsRegistryTest.legacyPrefKeysArePreservedExactly` 的 42 键双向等值）；
     * 但它们**从来没有过 UI 入口**（probe-settings-inventory §2.2 的 13 项 + 2 条凭证 +
     * v2.8.0 的迁移水位），本版不为它们新造界面（铁律：只重组、不加项）。
     */
    INTERNAL,

    /**
     * **不渲染**：由**别的界面**承载的条目（唯一一处：库页的 4 个显示偏好）。
     *
     * `library_playlist_layout` / `library_section_collapsed_*` 的控件在库页
     * （`LibraryPlaylistsTab.kt` 的布局切换器与三个区块收展按钮），
     * 结构探针 §1.2 明确建议**不迁移**：把它们搬进设置页会让「切布局」多两次跳转，
     * 而且设置页那份与库页那份会变成同一状态的两个可写入口（真正的双轨）。
     */
    HOSTED_ELSEWHERE,
}

/**
 * 二级页渲染计划：`SettingsEntry` → [SettingsRowKind]，以及「某分组应渲染哪些条目」。
 *
 * ## 它解决什么问题
 *
 * 迁移期的最大风险是「某个既有设置项在旧的平铺页被删掉、但二级页忘了接」
 * （结构探针 §5 风险 1）。本对象把「每一项去哪了」变成**一份可枚举、可断言的数据**：
 * 二级页的渲染列表由它产出，单测再对同一个函数做双向等值断言 ⇒ 丢项/重复都不可能悄悄发生。
 *
 * ## 三条纪律（与 `SettingsRegistry` 的 KDoc 同源）
 *
 * 1. 本文件**没有**任何写盘能力，也不 import 任何 Android / Compose 类型；
 * 2. 它只决定「渲染成什么控件」，**不决定读写走哪条路** —— 读写仍在 Compose 层显式调用
 *    既有入口（`KeepScreenOnSetting.write` / `RotationSetting.write` / `VisualizerSetting.write` /
 *    `PageTransitionSetting.writeEnabled` / `ArtistReco.setEnabled` / `LyricsDisplayPrefs.*` /
 *    `PlayerViewModel.*` / 裸 `prefs.edit()`）；
 * 3. 未知 id 一律回落 [SettingsRowKind.INTERNAL]（= 不渲染）而不是抛异常 ——
 *    与 `SettingsVisibility.availabilityOf` 的「坏输入最坏只是少一行」同一条纪律。
 */
object SettingsRenderPlan {

    /** 由库页承载的 4 个条目（结构探针 §1.2；也是 `secondaryGroup = APPEARANCE` 的那 4 条）。 */
    val HOSTED_ELSEWHERE: Set<String> = linkedSetOf(
        "library_playlist_layout",
        "library_section_collapsed_local",
        "library_section_collapsed_netease",
        "library_section_collapsed_qq",
    )

    /** 由**别的界面**承载时的宿主名（单测/诊断用；本版只有库页一个宿主）。 */
    const val HOST_LIBRARY = "library"

    /** 不渲染的两种形态。 */
    private val NOT_RENDERED = setOf(SettingsRowKind.INTERNAL, SettingsRowKind.HOSTED_ELSEWHERE)

    /**
     * **一级页的卡片列表**（数量与顺序 = 7 个分组）。
     *
     * 一级页渲染的就是这个函数（`UserScreen.kt`），单测断言的也是它 —— 卡片少一张、
     * 顺序变了，不可能只发生在一侧。
     */
    fun cardGroups(): List<SettingsGroup> = SettingsRegistry.groups()

    /** 条目由哪个外部界面承载；`null` = 不是外部承载。 */
    fun hostOf(entryId: String): String? = if (entryId in HOSTED_ELSEWHERE) HOST_LIBRARY else null

    /**
     * 条目 → 控件形态。
     *
     * 显式列举的 id 是那些**不能靠 type 推出来**的（需要专用组件 / 特殊读写路径）；
     * 其余按 [SettingsEntry.type] 归类。这样既避免 39 条各写一行，也保证
     * 「注册表里新增一条 SWITCH」自动获得开关行的渲染计划。
     */
    fun rowKindOf(entry: SettingsEntry): SettingsRowKind {
        if (entry.isInternal) return SettingsRowKind.INTERNAL
        if (entry.id in HOSTED_ELSEWHERE) return SettingsRowKind.HOSTED_ELSEWHERE
        return when (entry.id) {
            // 账号：两块无 key 的行为行（各自是一整块 UI，不是一行）
            "action.account_netease" -> SettingsRowKind.ACCOUNT_PROFILE
            "action.account_qq" -> SettingsRowKind.ACCOUNT_QQ
            "action.account_bili" -> SettingsRowKind.ACCOUNT_BILI

            // 通用
            "language_code" -> SettingsRowKind.LANGUAGE_DROPDOWN
            "action.background_activity" -> SettingsRowKind.ACTION_JUMP

            // 外观与动效
            "theme_mode" -> SettingsRowKind.THEME_MODE_SEGMENT
            "theme_color_index" -> SettingsRowKind.THEME_COLOR_SWATCHES
            "accent_source" -> SettingsRowKind.ACCENT_SOURCE_SEGMENT
            "custom_bg_enabled" -> SettingsRowKind.BACKGROUND_PICKER

            // 播放与音质
            "wifi_quality", "mobile_quality" -> SettingsRowKind.QUALITY_DROPDOWN
            // v2.9.0：波形档位下拉升级为**统一动效强度**下拉（同一个 RowKind，文案与键都换了）。
            "motion_tier" -> SettingsRowKind.TIER_DROPDOWN

            // 歌词：字号是「有限档位」下拉（不是连续值）
            "lyrics_font_scale" -> SettingsRowKind.DROPDOWN

            // 存储与缓存
            "offline_cache_mb" -> SettingsRowKind.DROPDOWN
            "action.storage_clear_cache" -> SettingsRowKind.CLEAR_CACHE
            "action.storage_offline_manage" -> SettingsRowKind.OFFLINE_MANAGER

            // 关于
            "action.about_open" -> SettingsRowKind.ABOUT_OPEN

            else -> when (entry.type) {
                SettingsEntryType.SWITCH -> SettingsRowKind.SWITCH
                SettingsEntryType.CHOICE, SettingsEntryType.QUALITY_CHOICE -> SettingsRowKind.DROPDOWN
                SettingsEntryType.ACTION -> SettingsRowKind.ACTION_JUMP
                // TEXT / INFO 只可能是内部项或高级覆盖键：上面 isInternal 已经拦掉，
                // 兜到这里说明有人往 registry 里加了一条没有渲染计划的可见项 ——
                // 不渲染，并由 SettingsRenderPlanTest 的「非 internal 必被计划到」断言把它抓住。
                SettingsEntryType.TEXT, SettingsEntryType.INFO -> SettingsRowKind.INTERNAL
            }
        }
    }

    /** 这一条是否**由某张二级页渲染**（排除内部项与外部承载项）。 */
    fun isRenderedOnGroupPage(entry: SettingsEntry): Boolean = rowKindOf(entry) !in NOT_RENDERED

    /**
     * 某分组**计划渲染**的条目（静态：不含门控）。
     *
     * 顺序 = registry 声明顺序 = [SettingsRegistry.entriesOf] 的顺序。
     */
    fun plannedRowsOf(groupId: String): List<SettingsEntry> =
        SettingsRegistry.entriesOf(groupId).filter { isRenderedOnGroupPage(it) }

    /**
     * 某分组在当前取值下**实际挂载**的条目 = 计划渲染 ∩ 门控可见
     * （[SettingsVisibility.visibleEntriesOf] 已排除 `isInternal`）。
     *
     * 二级页的 `LazyColumn` 就是按这个列表铺的 —— 所以「页面上有哪些行」与单测断言的
     * 是同一个函数，不存在「测试过了但 UI 没接线」的缝隙。
     */
    fun rowsOf(groupId: String, read: (String) -> Any?): List<SettingsEntry> =
        SettingsVisibility.visibleEntriesOf(groupId, read).filter { isRenderedOnGroupPage(it) }

    /** 全部分组的计划渲染条目（一级页卡片顺序 → 组内顺序）。 */
    fun allPlannedRows(): List<SettingsEntry> =
        SettingsRegistry.groups().flatMap { plannedRowsOf(it.id) }

    /**
     * 「非 internal 的条目」全集 = 计划渲染的 ∪ 外部承载的。**恰好一次，双向等值**。
     *
     * 单测用它把「丢项」与「重复」同时钉死。
     */
    fun plannedEntryIds(): Set<String> =
        SettingsRegistry.allEntries().filter { !it.isInternal }.map { it.id }.toSet()
}
