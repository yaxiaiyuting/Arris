/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v2.5.1 · F：「页面切换动效」开关的唯一读写入口（用户可配，默认启用）。
 */

package com.takahashirinta.ncrust.ui.theme

import android.content.Context
import android.content.SharedPreferences

/**
 * v2.5.1 · F：「页面切换动效」开关 —— **唯一读写入口**。
 *
 * ## 这是什么
 *
 * `ui/navigation/NavGraph.kt` 的页面转场由本开关决定：
 *  - **开（默认）** ⇒ 四个转场走 [AppMotion.pageTransitionSpec]（260ms fade + 方向性位移）；
 *  - **关** ⇒ 四个转场挂 `EnterTransition.None` / `ExitTransition.None`，
 *    行为**逐字节等于 v2.5.0**（跳变、零中间帧、零动画计算）。
 *
 * 设置页文案是「页面切换动效」，说明文字是「关闭可提升低端机流畅度」。
 *
 * ## 为什么要一个对象，而不是在设置页 `prefs.edit().putBoolean(...)`
 *
 * 因为「加字段 = 加迁移逻辑 = 加单测」是本仓库的硬约束
 * （v1.9.3 固化的歌词缓存迁移纪律，见 `AGENTS.md`）。把读写摊在 Composable 里，
 * 迁移规则就只存在于某一行 UI 代码中，**没有任何测试能钉住它**。
 * 抽成对象之后：
 *  1. 键名与默认值是**具名常量**，可以被单测断言（改名 = 静默重置所有用户的选择）；
 *  2. 「键不存在」这一支有**独立的纯函数** [resolveEnabled] 可测；
 *  3. 脏键（键里存了别的类型）的回落路径也能测 —— 真机上
 *     `SharedPreferences.getBoolean` 遇到它必抛 `ClassCastException`。
 *
 * ## 迁移逻辑：v1（无键） → v2（显式布尔）
 *
 * | 读到的状态 | 含义 | 结果 |
 * |---|---|---|
 * | 键**不存在** | v2.5.0 及以前的老用户，**从未做过选择** | [DEFAULT_ENABLED] = **开** |
 * | `true` / `false` | 用户在设置页显式选过 | 原样返回 |
 * | 键存在但类型不符 | prefs 被写坏 | [DEFAULT_ENABLED] = **开**，不抛异常 |
 *
 * 第一条就是本次的迁移决策：**老用户默认拿到「开」**。
 * 这是任务书「默认启用」的字面要求，也是唯一自洽的选择 ——
 * v2.5.0 的「没有转场」不是用户选的，而是当时的产品决策；
 * 把它当成用户的显式选择（默认关）会与「默认启用」直接矛盾。
 *
 * 与 [com.takahashirinta.ncrust.lyric.LyricsDisplayPrefs.readWordAnimation] 的差别：
 * 那里必须把老布尔键**消费一次**（因为它承载的是用户的显式选择）；
 * 这里**没有**可消费的老键，所以不需要写回 —— [readEnabled] 是纯读、无副作用，
 * 也因此可以在单测里反复调用而不污染 FakePrefs。
 *
 * ## 默认值为什么是「开」
 *
 * 铁律 17（本版新增）：「页面转场等影响体验的动效，默认启用但提供开关，由用户选择。」
 * 关掉是**用户的权利**，不是默认。
 */
object PageTransitionSetting {

    const val PREFS = "ncrust_settings"

    /**
     * 持久化键名。**这是契约**：改名等于静默重置所有用户的选择
     * （单测 `PageTransitionSettingTest` 逐字断言它）。
     */
    const val KEY = "page_transition_enabled"

    /** 默认**启用**（任务书 §2.1「默认值：启用」）。 */
    const val DEFAULT_ENABLED = true

    /**
     * 迁移判定（**纯函数**，v1 → v2）。
     *
     * @param stored `prefs` 里读到的值；**键不存在**或**类型不符**时为 `null`
     * @return 实际生效的开关值
     */
    fun resolveEnabled(stored: Boolean?): Boolean = stored ?: DEFAULT_ENABLED

    /**
     * 读盘上的**原始**值：键不存在 → `null`；类型不符 → `null`（异常被吞掉）。
     *
     * 单独暴露它是为了让「缺失」与「显式 false」在单测里**可区分** ——
     * 两者的最终行为相同，但语义完全不同（前者是迁移，后者是用户选择）。
     */
    fun readStored(prefs: SharedPreferences): Boolean? = runCatching {
        if (prefs.contains(KEY)) prefs.getBoolean(KEY, DEFAULT_ENABLED) else null
    }.getOrNull()

    /** 读开关（带迁移与脏键回落）。 */
    fun readEnabled(prefs: SharedPreferences): Boolean = resolveEnabled(readStored(prefs))

    /** 读开关（Context 便捷版，给 Compose/Activity 用）。 */
    fun readEnabled(context: Context): Boolean = readEnabled(prefs(context))

    /** 唯一写入口。 */
    fun writeEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }

    /** 唯一写入口（Context 便捷版）。 */
    fun writeEnabled(context: Context, enabled: Boolean) {
        writeEnabled(prefs(context), enabled)
    }

    /**
     * 转场时长（毫秒）——**关闭时严格为 0**。
     *
     * `NavGraph` 用 `durationMs(enabled) == 0` 之外还直接走 `EnterTransition.None`；
     * 这个函数存在的意义是让「关掉 = 0ms」这件事**可被单测钉住**，
     * 而不是靠读 `NavGraph` 的 if 分支相信它。
     *
     * 返回 0 而不是「一个 1ms 的 tween」是有意的：`tween(0)` 仍然会挂一个
     * `Transition` 对象并逐帧回调（虽然只有一帧），而 `EnterTransition.None`
     * 连中间帧都不产生 —— 铁律 17 的「关闭时不能有残留动画计算」指的是后者。
     */
    fun durationMs(enabled: Boolean): Int = if (enabled) AppMotion.PAGE_TRANSITION_MS else 0

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
