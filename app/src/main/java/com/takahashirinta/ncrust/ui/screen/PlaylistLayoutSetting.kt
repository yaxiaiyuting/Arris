/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.0 · P1：库页「歌单」tab 的布局模式（卡片式 / 列表式）持久化。
 * **纯逻辑（除 prefs 读写外），JVM 可单测。**
 */

package com.takahashirinta.ncrust.ui.screen

import android.content.Context
import android.content.SharedPreferences

/**
 * 库页「歌单」tab 的布局模式（v2.6.0 · P1，铁律 24）。
 *
 * ## 为什么默认是 [CARD]
 *
 * 探针（`docs/verification/v2.6.0/probe-layout-switch.md` §5.5）查明：
 * **不存在「两个源都零行为变化」的默认值** —— 改造前这一页本来就是混合形态
 * （本地歌单与 ncm 是网格卡片、QQ 是整行）。所以默认值是一个**产品选择**，
 * 选定 [CARD] 的理由：
 *
 * 1. 它保留**面积最大**的两段（本地歌单 + ncm）的现状，升级后绝大多数用户
 *    看到的界面与升级前一致；
 * 2. 任务书的验收要求是「两个源布局一致」，而那必然意味着**某一段要改变形态**；
 *    选 [LIST] 会让两段同时改变，选 [CARD] 只让 QQ 那一段改变，改动面更小；
 * 3. QQ 那一段改成卡片之后，与它上下两段在视觉上「同一页同一语言」，
 *    这正是 P1 要的统一；而原来「用整行区分这是 QQ 段」的诉求，
 *    现在由**区块标题**（`hdr-qq`）承担 —— 标题一直都在，不是新增信息负担。
 *
 * 这个取舍是**有意的**，不是遗漏；旧行为可以通过一次点击回到（[LIST]）。
 */
enum class PlaylistLayout {
    /** 卡片式：大封面 + 标题 + 歌曲数，`GridCells.Adaptive(160.dp)`（窄屏 2 列）。 */
    CARD,

    /** 列表式：48dp 小封面 + 标题 + 副标题，`GridCells.Fixed(1)`。 */
    LIST,
    ;

    companion object {
        /**
         * 把落盘的整数**规范化**成合法取值。
         *
         * 越界 / 缺失一律回落 [CARD]（默认值），**不抛异常**：
         * 这个值只由一个按钮写、也只影响列表外观，为一个显示偏好让启动失败
         * 是不成比例的（与 `LyricsDisplayPrefs`「越界一律回落」同一条纪律）。
         */
        fun fromIndex(index: Int?): PlaylistLayout =
            entries.getOrElse(index ?: DEFAULT_INDEX) { CARD }

        /** 默认值的索引（落盘用）。 */
        const val DEFAULT_INDEX: Int = 0
    }
}

/**
 * [PlaylistLayout] 的落盘读写（v2.6.0 · P1）。
 *
 * ## 单一落点
 *
 * 与 `ThemeManager` / `LanguageManager` / `LyricsDisplayPrefs` / `PageTransitionSetting`
 * 共用 **`ncrust_settings`** —— 全仓库的用户显示偏好都在这一个文件里，
 * 新开一个 prefs 文件会让「清缓存」「备份」这类操作多一个需要记住的地方。
 *
 * ## 键名与形状
 *
 * | key | 类型 | 取值 |
 * |---|---|---|
 * | `library_playlist_layout` | `Int` | `0` = [PlaylistLayout.CARD]，`1` = [PlaylistLayout.LIST] |
 *
 * **存 Int 而不是存枚举名**：与 `theme_color_index` / `lyrics_word_animation` /
 * `lyrics_sweep_quality` 三个既有先例一致，且 Int 的读路径必须经过
 * [PlaylistLayout.fromIndex] 的白名单，所以「服务端/旧版本写了个 7」不会崩。
 *
 * ## 不需要迁移逻辑
 *
 * 这是一个**全新键**：v2.6.0 之前不存在任何布局偏好（探针 §5.4 的
 * `grep` 已证），所以没有老形状可认，也就没有 `migrate()`。
 * 铁律 3「加字段 = 加迁移逻辑」在这里的正确形态是**「没有旧形状 ⇒ 没有迁移」**，
 * 而不是写一段永远不会执行的迁移代码。
 */
object PlaylistLayoutSetting {

    internal const val PREFS_NAME = "ncrust_settings"
    internal const val KEY_LAYOUT = "library_playlist_layout"

    /** 当前布局模式。读不到 / 值非法时回落 [PlaylistLayout.CARD]。 */
    fun read(context: Context): PlaylistLayout = read(prefs(context))

    /**
     * 写入布局模式并**同步可见**（`apply()`）。
     *
     * 调用方在同一个组合帧里把它读回状态，所以这里必须落盘后再返回 ——
     * 「切换后立即生效、且重启后保持」是铁律 24 的两半，缺一不可。
     */
    fun write(context: Context, layout: PlaylistLayout) = write(prefs(context), layout)

    // ---- 纯 prefs 入口：单测直接用（FakePrefs），不必造一个 Context ----
    //
    // 两个同名重载而不是让调用方自己 `context.getSharedPreferences(...)`：
    // prefs 文件名是本类的契约（见下面的键名契约用例），调用点不该知道它。

    /** 从任意 [SharedPreferences] 读（单测入口）。 */
    fun read(p: SharedPreferences): PlaylistLayout =
        PlaylistLayout.fromIndex(readStoredIndex(p))

    /** 写进任意 [SharedPreferences]（单测入口）。 */
    fun write(p: SharedPreferences, layout: PlaylistLayout) {
        p.edit().putInt(KEY_LAYOUT, layout.ordinal).apply()
    }

    /**
     * 读原始整数。**区分「键不存在」与「显式写了非法值」** ——
     * 两者都回落默认，但只有前者是「新用户」，后者是「数据脏了」，
     * 单测需要能分别构造这两种形状。
     *
     * 键存在但类型不是 Int（例如被人用 `putString` 写过）时**吞掉
     * `ClassCastException` 并回落** —— SharedPreferences 的类型错配在真机上
     * 是 `ClassCastException` 崩溃，而这里只是一个显示偏好。
     */
    internal fun readStoredIndex(p: SharedPreferences): Int? =
        runCatching { if (p.contains(KEY_LAYOUT)) p.getInt(KEY_LAYOUT, PlaylistLayout.DEFAULT_INDEX) else null }
            .getOrNull()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
