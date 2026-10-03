/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.0 · P2：库页「歌单」tab 三个区块的**手动折叠**状态持久化。
 * **纯逻辑 + 状态机，JVM 可单测。**
 */

package com.takahashirinta.ncrust.ui.screen

import android.content.Context
import android.content.SharedPreferences

/**
 * 库页「歌单」tab 里可折叠的**区块**（v2.6.0 · P2）。
 *
 * ## 为什么折叠单位是「区块」而不是「单张歌单」
 *
 * 探针（`docs/verification/v2.6.0/probe-fold.md` §3）查明这一页是**按源分区的三段**：
 * 本地歌单 / ncm / qm，而用户来这里最常见的诉求是
 * 「我现在只想看 QQ 那一段」（或者「QQ 没登录，别占我半屏」）。
 * 按单张歌单折叠要求用户逐个收起 100 张歌单里的 99 张 —— 那不叫折叠，叫整理。
 * 区块级折叠一次点击就把一整段收掉，且与既有的区块标题（[SectionHeader]）天然对齐。
 *
 * ## 为什么是**三个独立的布尔**而不是一个集合
 *
 * `SharedPreferences` 的 `StringSet` 在本仓库的生产代码里**零使用**（探针 §5.2 的
 * `grep` 已证，只在测试的 FakePrefs 里出现过），而 CSV 先例只有一个
 * （`ArtistReco` 的 `artist_reco_anchor_ids`，那是**数量不定**的集合）。
 * 这里的键集是**编译期固定**的三个，用集合只会换来
 * 「读出来要判空、写回去要防并发丢元素」这两个额外失败面。
 */
enum class LibrarySection(val prefsKey: String) {
    /** 本地歌单。 */
    LOCAL("library_section_collapsed_local"),

    /** ncm 歌单。 */
    NETEASE("library_section_collapsed_netease"),

    /** qm 歌单。 */
    QQMUSIC("library_section_collapsed_qq"),
}

/**
 * 三个区块的折叠状态（v2.6.0 · P2）。不可变值对象 —— 状态机是**纯函数**。
 *
 * ## 铁律：**绝不自动折叠**
 *
 * 这个类型里**没有任何**「因为数量多所以自动收起」的入口。唯一能改变它的
 * 公开操作是 [toggled]，而它只由用户的点击调用（见
 * `LibraryPlaylistsTab` 的 `onToggle`）。这条以「没有那个 API」的形式保证 ——
 * 而不是写在注释里等人遵守 —— 因为自动折叠的典型实现
 * （在读取列表后顺手把状态设成 true）在 code review 里看起来完全合理。
 *
 * ## 为什么状态要落在 prefs 而不是 `remember`
 *
 * 探针 §5.5 指出的实现陷阱：这一页的 tab 内容被
 * `LibraryScreen.kt` 的 `AnimatedContent(targetState = selectedCategory)` 包裹，
 * **切到别的 tab 会把整棵子树卸载**。只放在 `remember` 里的折叠状态会在
 * 切回来时静默展开 —— 用户看到的是「我收起来的又自己打开了」，
 * 而「不自动展开」与「不自动折叠」是同一件事的两面。
 */
data class LibrarySectionFold(
    val collapsedLocal: Boolean = false,
    val collapsedNetease: Boolean = false,
    val collapsedQq: Boolean = false,
) {

    fun isCollapsed(section: LibrarySection): Boolean = when (section) {
        LibrarySection.LOCAL -> collapsedLocal
        LibrarySection.NETEASE -> collapsedNetease
        LibrarySection.QQMUSIC -> collapsedQq
    }

    /** 只反转**被点的那个**区块，其余原样。纯函数。 */
    fun toggled(section: LibrarySection): LibrarySectionFold = when (section) {
        LibrarySection.LOCAL -> copy(collapsedLocal = !collapsedLocal)
        LibrarySection.NETEASE -> copy(collapsedNetease = !collapsedNetease)
        LibrarySection.QQMUSIC -> copy(collapsedQq = !collapsedQq)
    }

    companion object {
        /** 默认全展开 —— 老用户升级后零行为变化。 */
        val ALL_EXPANDED = LibrarySectionFold()
    }
}

/**
 * [LibrarySectionFold] 的落盘读写（v2.6.0 · P2）。
 *
 * | key | 类型 | 默认 |
 * |---|---|---|
 * | `library_section_collapsed_local` | `Boolean` | `false` |
 * | `library_section_collapsed_netease` | `Boolean` | `false` |
 * | `library_section_collapsed_qq` | `Boolean` | `false` |
 *
 * 与 [PlaylistLayoutSetting] 共用 **`ncrust_settings`**（同一份理由，见那里的 KDoc）。
 *
 * 读路径用 `contains` 判「键存在吗」再取布尔，并对类型错配吞掉
 * `ClassCastException` 回落 `false`：`ncrust_settings` 是用户可编辑的
 * （root / 备份还原路径），一个被写成字符串的布尔不该让库页打不开。
 *
 * **不需要迁移逻辑**：三个 key 都是 v2.6.0 新增，没有旧形状（探针 §5 已证）。
 */
object LibrarySectionFoldSetting {

    internal const val PREFS_NAME = "ncrust_settings"

    fun read(context: Context): LibrarySectionFold = read(prefs(context))

    fun write(context: Context, section: LibrarySection, collapsed: Boolean) =
        write(prefs(context), section, collapsed)

    // ---- 纯 prefs 入口：单测直接用（FakePrefs），不必造一个 Context ----

    /** 从任意 [SharedPreferences] 读（单测入口）。 */
    fun read(p: SharedPreferences): LibrarySectionFold = LibrarySectionFold(
        collapsedLocal = readBool(p, LibrarySection.LOCAL.prefsKey),
        collapsedNetease = readBool(p, LibrarySection.NETEASE.prefsKey),
        collapsedQq = readBool(p, LibrarySection.QQMUSIC.prefsKey),
    )

    /**
     * 写入**被改动的那一个键**，其余不碰。
     *
     * 为什么不是「每次都写三个键」：`ncrust_settings` 里的其他键由
     * ThemeManager / LanguageManager / 设置页共享，整表重写会把并发写入
     * （例如用户此刻正好在切主题）覆盖掉。只写自己那一个键是最小冲突面。
     */
    fun write(p: SharedPreferences, section: LibrarySection, collapsed: Boolean) {
        p.edit().putBoolean(section.prefsKey, collapsed).apply()
    }

    internal fun readBool(p: SharedPreferences, key: String): Boolean =
        runCatching { if (p.contains(key)) p.getBoolean(key, false) else false }.getOrDefault(false)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
