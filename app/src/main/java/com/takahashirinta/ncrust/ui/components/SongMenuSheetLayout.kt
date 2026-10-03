/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v2.5.1 · S6 回归：歌曲菜单操作列表的**最大高度**判定（纯逻辑，JVM 可单测）。
 */

package com.takahashirinta.ncrust.ui.components

/**
 * v2.5.0 引入、v2.5.1 补回归单测的**歌曲菜单高度判定**（纯逻辑，不依赖 Android）。
 *
 * ## 它修的是什么（真机实测，不是推断）
 *
 * S6/G9209（Android 7.0 · 1440×2560 = 411×731dp）上，歌曲菜单的操作条目加到 **10 条**时：
 *
 * ```
 * 弹层总高 = 112dp(信息区) + 10 × 64dp(每行) + 安全区 ≈ 752dp  >  731dp(屏幕高)
 * ```
 *
 * 最后一条「单曲信息」被挤出屏幕；而 `MetroBottomSheet` 的内容是一个**不滚动**的 `Column`，
 * 实测在其上做上滑手势**所有条目坐标完全不变** —— 那一条是真的点不到，不是"难滚"。
 * 归因很明确：v2.5.0 新增「添加到下一首播放」把菜单从 9 条推到 10 条
 * （9 条时 `转到专辑` 结束于 y=2394 < 2560，刚好放得下）。
 *
 * ## 修法与它的**代价边界**
 *
 * 调用点给操作列表加 `heightIn(max = maxActionsHeightDp(屏幕高))` + `verticalScroll`。
 * 用 `heightIn(max=)` 而**不是**固定高度，是本修法最要紧的一点：
 * 内容本来就放得下时（平板 / 横屏 / 条目少），上限**根本不会生效**，
 * 布局与改动前**逐字节一致**，不会平白多出一个滚动容器。
 *
 * ## 为什么判定要抽成纯函数（而不是留在 Composable 里）
 *
 * 铁律 5「加字段 = 加迁移逻辑 = 加单测」的同类要求：**能在一个便宜的层次上测出来的东西，
 * 不要留给真机**（v2.2.1 规则 5）。「10 条在 731dp 上会不会溢出」「1000dp 上会不会
 * 平白多出滚动容器」这两件事都是算术，`SongMenuSheetLayoutTest` 在 JVM 上就能钉死；
 * 真机那一轮（v2.5.1 的 release 包复核）只需要验证"最后一条真的能滚到"，
 * 不必再重新发现一次算术。
 *
 * ⚠️ **本对象只回答高度，不回答"能不能滚"**。真正的可滚性由
 * `Modifier.verticalScroll` 提供；`needsScroll` 只用于**验证修法确实覆盖了回归场景**，
 * 不参与运行时逻辑（多一个分支就多一处可能与真实布局不符的假设）。
 */
object SongMenuSheetLayout {

    /**
     * 弹层里**不参与滚动**的固定开销（dp）：
     * 信息区 112 + 上下留白 64。
     *
     * 这个 176 是 S6 实测反推的（752 − 640 = 112，再加 64 的安全区），不是估的。
     */
    const val CHROME_DP = 176

    /**
     * 操作列表的最大高度下限（dp）。
     *
     * 极小屏 / 车机窄窗口下 `screenHeightDp - 176` 可能只剩两位数，那会让菜单
     * 一次只显示一行多。160dp ≈ 两个半条目，是"仍然可用"的底线；
     * 取 `coerceAtLeast` 而不是直接用它 —— 上限失真总比菜单不可用好。
     */
    const val MIN_ACTIONS_HEIGHT_DP = 160

    /**
     * 单个操作条目的**视觉高度**（dp）：上下各 16dp padding + 24dp 图标。
     * 与 `SongMenuSheet` 里那行 `Row(...).padding(horizontal = 16.dp, vertical = 16.dp)`
     * 一致；改那行 padding 必须同步改这里，否则本对象的推算会失真。
     */
    const val ACTION_ROW_DP = 64

    /** 操作列表允许占用的最大高度（dp）。 */
    fun maxActionsHeightDp(screenHeightDp: Int): Int =
        (screenHeightDp - CHROME_DP).coerceAtLeast(MIN_ACTIONS_HEIGHT_DP)

    /**
     * 给定屏幕高与条目数，操作列表**是否需要滚动**。
     *
     * 判据是「条目总高 > 上限」。注意它**故意不**等价于「弹层放不放得下」：
     * 上限已经把 64dp 安全区算进去了，所以判据会在真正溢出**之前**一两行就成立 ——
     * 对一个"上限"来说这是想要的方向（宁可早一点可滚，也不要晚一行点不到）。
     */
    fun needsScroll(screenHeightDp: Int, actionCount: Int): Boolean =
        actionCount * ACTION_ROW_DP > maxActionsHeightDp(screenHeightDp)
}
