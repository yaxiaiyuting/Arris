/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

/**
 * P1 · 大屏幕模式（横屏桌面播放器布局）与宽屏两栏共用的**纯布局逻辑**。
 *
 * 抽出来的原因有两个：
 *  1. 「大屏模式」不能塞进全仓库那 7 处 `screenWidthDp >= 600` 的宽屏判定里 ——
 *     横屏时窗口宽度必然 >= 600dp（PCL110 实测 800dp），改那个谓词会把首页 / 详情页 /
 *     收藏页的布局一起改掉。所以大屏模式是**第三个谓词**（[isBigScreenActive]），只让播放器读。
 *  2. 这些判定是分栏命中区语义的唯一定义处，必须能脱离 Compose 单测（见 PlayerLayoutTest）。
 */
object PlayerLayout {

    /** 宽屏（平板/折叠展开/车机）分栏断点。与全仓库其余 6 处保持一致，不要改。 */
    const val WIDE_BREAKPOINT_DP = 600

    /**
     * 「平板」断点（`smallestScreenWidthDp`）。
     *
     * 与 [WIDE_BREAKPOINT_DP] **不是同一个谓词**，把两者当成一回事正是 v2.5.4 · D 那个
     * bug 的形状：`screenWidthDp >= 600` 在平板横竖两个方向都成立
     * （WGR-W09 实测竖屏 800dp、横屏 1280dp），而手机横屏也成立（PCL110 800dp）。
     * 要区分「平板」与「横过来的手机」，只有 `smallestScreenWidthDp` 这一条 ——
     * 它与方向无关：手机恒 < 600（S6 = 360dp），平板恒 >= 600（WGR-W09 = 800dp）。
     */
    const val LARGE_SCREEN_BREAKPOINT_DP = 600

    /** 可视化条高度的比例与上下夹取（沿用 v1.8.0 · T3 的取值，抽出来当唯一定义）。 */
    const val VISUALIZER_HEIGHT_FRACTION = 0.11f
    const val VISUALIZER_MIN_HEIGHT_DP = 32f
    const val VISUALIZER_MAX_HEIGHT_DP = 56f

    /**
     * v2.5.4 · D：音频可视化条**该不该挂载**。
     *
     * ## 这个谓词修的是什么
     *
     * v1.8.0 · T3 把可视化只挂在 `bigScreenActive ->` 那一个布局分支里
     * （`PlayerCard.kt` 的横屏桌面播放器布局）。而 [isBigScreenActive] 是
     * 「**用户意图**（点了 ⤢）+ 窗口真的横过来」两个条件的与，而 `bigScreen` 默认 false。
     * 平板横屏时 `isWidePlayer` 为真 ⇒ 走**宽屏两栏**分支，那一条里一个可视化都没挂
     * ⇒ 用户报的「平板横屏波浪条不显示」。
     *
     * 更糟的是平板**没有** ⤢ 入口：那个按钮只存在于竖屏控制条变体里
     * （`FullPlayerControls` 的 `if (landscape)` 分支里没有它），而
     * `landscape = usesSideCover = isWidePlayer || bigScreenActive` 在平板上恒为真。
     * 也就是说这条功能在平板上是**结构性不可达**的，不是「某个条件没满足」。
     *
     * ## 判据（四条与，缺一不可）
     *
     * | 条件 | 作用 | 不满足时的形态 |
     * |---|---|---|
     * | [enabled] | 用户开关（`VisualizerSetting`，默认开） | 关掉 = 整块不挂载，连帧时钟都不跑 |
     * | [bigScreenActive] | 横屏桌面布局（原本唯一的挂载点） | 手机横屏的常规路径 |
     * | [isLargeScreen] | `smallestScreenWidthDp >= 600` = **平板** | 手机横屏不在这里显示（与 v1.8.0 逐像素一致） |
     * | [orientationLandscape] | 只在横屏给 | 平板竖屏保持与 v1.8.0 一致（不放可视化） |
     *
     * ## A/B（每一格都有谓词或真机证据）
     *
     * | 形态 | bigScreenActive | isWidePlayer | isLargeScreen | landscape | 挂载 |
     * |---|---|---|---|---|---|
     * | 手机竖屏 | false | false | false | false | ❌（v1.8.0 起就没有，**不回归**） |
     * | 手机横屏·大屏模式 | true | true | false | true | ✅（走 [bigScreenActive]，**不回归**） |
     * | 手机横屏·非大屏模式 | false | true | false | true | ❌（v1.8.0 起就没有，**不回归**） |
     * | 平板竖屏 | false | true | true | false | ❌（v1.8.0 起就没有，**不回归**） |
     * | **平板横屏** | false | true | true | true | ✅（**本版修的就是这一格**） |
     * | 平板横屏·大屏模式 | true | true | true | true | ✅（走 [bigScreenActive]） |
     *
     * 即：只有「平板 + 横屏」这一格从「无」变成「有」，其余五格与 v1.8.0 逐格相同。
     */
    fun visualizerSlot(
        enabled: Boolean,
        bigScreenActive: Boolean,
        isWidePlayer: Boolean,
        isLargeScreen: Boolean,
        orientationLandscape: Boolean,
    ): Boolean = enabled && (
        bigScreenActive || (isWidePlayer && isLargeScreen && orientationLandscape)
        )

    /**
     * v2.5.5 · E：**平板**上「大屏幕模式」入口（⤢）该不该挂载。
     *
     * ## 它修的是什么
     *
     * v2.5.4 的探针顺手发现「平板上根本没有 ⤢ 入口」（它只存在于竖屏控制条变体里，
     * 而 `landscape = usesSideCover = isWidePlayer || bigScreenActive` 在平板上恒为真），
     * 但**没有**在同一个提交里改 —— 两件事的回归面会互相污染。本版单独做这一件。
     *
     * 平板的 `screenWidthDp` 恒 >= 600（WGR-W09 实测竖屏 800dp），所以它走的是
     * **宽屏横向控件条**；那一条里从来没有 ⤢。于是平板用户没有任何路径进入大屏模式。
     *
     * ## 判据（与 [visualizerSlot] 一样，只有一处分栏谓词）
     *
     * | 形态 | isLargeScreen | bigScreenActive | 挂载 | 说明 |
     * |---|---|---|---|---|
     * | 手机竖屏 | false | false | ❌ | 竖屏控制条里**本来就有** ⤢，不要挂第二个 |
     * | 手机横屏·非大屏 | false | false | ❌ | v1.8.0 起就没有，**不回归** |
     * | 手机横屏·大屏中 | false | true | ❌ | 出口在 `trailing` 槽位，不在这里 |
     * | **平板竖屏** | true | false | ✅ | **本版新增** |
     * | **平板横屏** | true | false | ✅ | **本版新增** |
     * | 平板·大屏中 | true | true | ❌ | 出口在 `trailing` 槽位 |
     *
     * 即：只有「平板 + 不在大屏」这两格由无变有，其余四格逐格不变。
     *
     * ## 为什么用 `!bigScreenActive` 而不是 `!requested`
     *
     * `bigScreenActive` = 「用户点了 ⤢」**且**「窗口真的横过来了」。
     * 用用户意图（`requested`）会让按钮在旋转的那一帧消失又出现；
     * 用生效态则保证「按钮在 = 还能进」，与 `trailing` 的出口不重叠。
     */
    fun bigScreenEntrySlot(isLargeScreen: Boolean, bigScreenActive: Boolean): Boolean =
        isLargeScreen && !bigScreenActive

    // ── v2.5.6 · P1：横向控制条的宽度预算 ──────────────────────────────────

    /** 横向控制条左组每个按钮的边长（dp）。与 `FullPlayerControls` 的 `.size(40.dp)` 同值。 */
    const val CONTROLS_SIDE_BUTTON_DP = 40f

    /** 传输三键（上一首 / 播放暂停 / 下一首）的边长合计（dp）：44 + 54 + 44。 */
    const val CONTROLS_TRANSPORT_DP = 142f

    /** 控制条左右各留的边距（dp）。 */
    const val CONTROLS_HORIZONTAL_PADDING_DP = 16f

    /** 音质选择器（`PlayerQualityChip`）的保守宽度估计（dp）。 */
    const val CONTROLS_QUALITY_CHIP_DP = 72f

    /**
     * 音质选择器在给定容器宽度下**放得下吗**。
     *
     * ## 为什么需要这个判据（真机 P0，不是预防性设计）
     *
     * v2.5.5 给的平板 ⤢ 入口在 **EMUI 平板的竖屏下点不到**：
     * `FullPlayerControls` 的横向控制条当时是 `Box(fillMaxWidth)` + 三个各自
     * `align(CenterStart/Center/CenterEnd)` 的 `Row`。三组**互相独立定位**，
     * 容器一窄就必然叠在一起，而**后声明的赢命中测试** ——
     * 于是左组第 4 个按钮（⤢）被居中的传输组整个盖住。
     *
     * WGR-W09 竖屏实测（`docs/verification/v2.5.6/probe-tablet-rotate.md`）：
     * 控制条容器宽 **352dp**，左组占 8~168dp，传输组占 103~247dp ——
     * 重叠 65dp，⤢ 的槽位（128~168dp）**100% 落在重叠区**，
     * 点它实际触发的是「播放/暂停」。横屏容器 563dp 则完全不重叠，
     * 所以这个缺陷**只在竖屏出现**（也正因如此，只在模拟器/单方向测是发现不了的）。
     *
     * ## 判据与优先级
     *
     * 左组（含 ⤢）与传输组是**不可让**的两组：前者是本版要修的入口，
     * 后者是铁律 19 点名的核心播放控制。让的只能是音质选择器 ——
     * 它在竖屏控制条（另一条分支）里仍在，不构成功能缺失。
     *
     * 于是：左组 + 传输组 + 音质片 ≤ 可用宽 时留音质片，否则**不挂**它。
     * 挂载判据是纯函数（本函数），因此可以被单测按真机实测的宽度逐格钉住。
     */
    fun qualityChipFits(
        availableWidthDp: Float,
        sideButtonCount: Int,
        qualityChipDp: Float = CONTROLS_QUALITY_CHIP_DP,
    ): Boolean {
        val usable = availableWidthDp - CONTROLS_HORIZONTAL_PADDING_DP
        val left = CONTROLS_SIDE_BUTTON_DP * sideButtonCount.coerceAtLeast(0)
        return left + CONTROLS_TRANSPORT_DP + qualityChipDp <= usable
    }

    /**
     * 横向控制条左组挂 ⤢ 之后的按钮个数。
     *
     * 抽出来是为了让 [qualityChipFits] 的调用点不必重复「⤢ 在不在」这个判断 ——
     * 两处各写一遍正是 v2.5.5 那个重叠缺陷的同形状温床。
     */
    fun sideButtonCount(bigScreenEntryVisible: Boolean): Int =
        3 + if (bigScreenEntryVisible) 1 else 0

    /**
     * 可视化条的高度（dp）：窗口高 × [VISUALIZER_HEIGHT_FRACTION]，夹在
     * [VISUALIZER_MIN_HEIGHT_DP]~[VISUALIZER_MAX_HEIGHT_DP] 之间。
     *
     * 与 v1.8.0 的算式逐字相同（`(screenHeightDp * 0.11f).coerceIn(32.dp, 56.dp)`），
     * 抽出来只是为了让「平板横屏拿到 56dp」这件事可被单测钉住：
     * WGR-W09 横屏 768dp × 0.11 = 84.5 ⇒ 夹到 56dp；手机横屏 363dp ⇒ 39.9dp。
     */
    fun visualizerHeightDp(screenHeightDp: Float): Float =
        (screenHeightDp * VISUALIZER_HEIGHT_FRACTION)
            .coerceIn(VISUALIZER_MIN_HEIGHT_DP, VISUALIZER_MAX_HEIGHT_DP)

    /** 宽屏两栏时**右栏**占比（原实现写死 0.56f，这里提出来当唯一定义）。 */
    const val WIDE_RIGHT_FRACTION = 0.56f

    /** 大屏模式左栏占比：与宽屏两栏同一比例，保持两套布局的视觉一致。 */
    const val BIG_SCREEN_LEFT_FRACTION = 1f - WIDE_RIGHT_FRACTION

    /**
     * 第三谓词：大屏幕模式是否生效。
     *
     * [requested] 是用户的意图（点了播放器里的「大屏幕」按钮），[orientationLandscape]
     * 是当前**窗口**方向。两者都与才切横屏桌面布局：
     *  - 用户点了按钮但系统还没转过去（旋转有 ~1 帧到数百毫秒的延迟）→ 仍是竖屏布局，
     *    避免竖屏窗口里塞一个横屏两栏布局；
     *  - 用户在大屏里把手机转回竖屏 → 立即回竖屏布局（MainActivity 同时会退出大屏模式）。
     */
    fun isBigScreenActive(requested: Boolean, orientationLandscape: Boolean): Boolean =
        requested && orientationLandscape

    /**
     * v3.2.1 · A（铁律 26）：分栏比例的**正下限** —— 左右两栏共用同一个下限。
     *
     * ## 为什么是「正下限」而不是 0
     *
     * `Modifier.weight(w)` 的内部实现是 `require(w > 0.0) { "invalid weight $w; must be
     * greater than zero" }`。所以任何一次把 `0f` 传进 `weight()` 的调用都会**直接崩掉整个 App**
     * （不是布局变形，是 `IllegalArgumentException` 冒泡到 `AndroidRuntime`）。
     * 下限取一个极小数而不是 0：宽度在视觉上等价于 0（< 0.03px），但语义上永远是合法的权重。
     *
     * ## 为什么必须有这一条（v3.2.0 的真机崩溃）
     *
     * v3.2.0 的 `invalid weight 0.0` 崩溃里，`PlayerCard` 只有**一处**动态权重
     * （`wideLeftFraction`），而它**只有右栏**有 `coerceAtLeast(0.0001f)`、
     * **左栏没有** —— 这个不对称本身就是隐患：一旦上限保护被加到一侧而另一侧忘了，
     * 结果就是「宽度异常 → 崩 App」而不是「宽度趋 0」。
     * 铁律 26：**对称位置的保护必须对称**。本轮把下限收敛到这一个常量，
     * 左栏 [wideLeftFraction] 与右栏 [wideRightFraction] 都必须经过它。
     */
    const val MIN_SPLIT_FRACTION = 0.0001f

    /**
     * 宽屏左栏占整宽的比例：随 wideSplit 在 100%（单栏）与 44%（两栏）之间过渡。
     * 窄屏恒为 1（[isWidePlayer] = false 时不读 wideSplit）。
     *
     * ## v3.2.1 · A：出口处加正下限（对称保护的一半）
     *
     * 返回值恒 `>= [MIN_SPLIT_FRACTION]`，且**恒不是 NaN**。调用点因此不可能把 `0f`
     * 交给 `Modifier.weight`，即使将来 [WIDE_RIGHT_FRACTION] 被改大、或 `wideSplit`
     * 被换成别的来源。
     *
     * ⚠️ **NaN 必须显式挡掉**：`Float.NaN.coerceIn(0f, 1f)` 返回 NaN、
     * `NaN.coerceAtLeast(x)` 也返回 NaN（两次比较都是 false）——
     * 也就是「加了 coerce 还是会崩」。这一条是 v3.2.1 补的，写进单测
     * （`PlayerLayoutTest.wideLeftFraction 恒为合法权重`）。
     */
    fun wideLeftFraction(isWidePlayer: Boolean, wideSplit: Float): Float {
        if (!isWidePlayer) return 1f
        val split = if (wideSplit.isNaN()) 0f else wideSplit.coerceIn(0f, 1f)
        return (1f - WIDE_RIGHT_FRACTION * split).coerceAtLeast(MIN_SPLIT_FRACTION)
    }

    /**
     * v3.2.1 · A：与 [wideLeftFraction] **对称**的右栏占比（对称保护的另一半）。
     *
     * 旧实现写的是 `(1f - wideLeftFraction).coerceAtLeast(0.0001f)` —— 保护是对的，
     * 但它把「下限」这个事实**又写了一遍**（另一处写 `0.0001f` 字面量的地方就是漂移的起点）。
     * 现在左右两侧共用 [MIN_SPLIT_FRACTION] 与同一个 NaN 判据，
     * `PlayerLayoutTest` 用「对称性」断言把它钉住：对定义域里的任何输入，
     * 两个返回值都 `> 0` 且 `左 + 右` 落在 `[1, 1 + MIN_SPLIT_FRACTION]` 内。
     */
    fun wideRightFraction(wideLeftFraction: Float): Float {
        if (wideLeftFraction.isNaN()) return 1f - MIN_SPLIT_FRACTION
        return (1f - wideLeftFraction).coerceAtLeast(MIN_SPLIT_FRACTION)
    }

    /**
     * 宽屏**分栏语义边界**（px）：左边是封面/信息区，右边是歌词·队列面板。
     *
     * 这个值是命中测试的分界线（面板内让路给列表滚动 / 面板外整卡拖拽），
     * 必须与真实分栏边界一致 —— 旧实现两处都用 `screenWidthPx / 2`，而真实边界是
     * 0.44×宽（两栏稳定态），于是 44%~50% 那条窄带被判成"封面区"：在带内上下拖歌词，
     * 事件被整卡拖拽抢走（P1 顺手修掉）。
     */
    fun splitBoundaryPx(screenWidthPx: Float, isWidePlayer: Boolean, wideSplit: Float): Float =
        screenWidthPx * wideLeftFraction(isWidePlayer, wideSplit)

    /** 大屏模式的分栏边界（px）：左栏占比固定，不随任何动画变化。 */
    fun bigScreenLeftBoundaryPx(screenWidthPx: Float): Float =
        screenWidthPx * BIG_SCREEN_LEFT_FRACTION

    /**
     * 封面正方形边长（px）：在实测到的可用宽高里取最小边。
     *
     * 大屏模式左栏拿到的是「左栏宽 × 左栏剩余高」，取小边 ⇒ 封面尽量占满**可用高度**，
     * 且永远是正方形；宽屏竖屏两栏同理（原实现是 `minOf(b.width, b.height)`，
     * 这里补一个 >= 1px 的下界，避免首帧 0 让 Coil 按 1px 解码成纯色）。
     */
    fun squareCoverSizePx(availableWidthPx: Float, availableHeightPx: Float): Float =
        minOf(availableWidthPx, availableHeightPx).coerceAtLeast(1f)

    /**
     * 唯一封面 overlay 在**实测之前**的兜底边长（px）。
     *
     * 实测（[squareCoverSizePx]）要等第一帧布局回调，此前若用 1px 会让 Coil 按 1px
     * 解码成纯色（重进播放器时尤其明显）。大屏模式左栏高度约等于可用高度，所以取
     * 「窗口宽 × 0.4」与「窗口高 × 0.5」的较小者 —— 横屏（PCL110：2800×1272px）
     * 得到 636px ≈ 182dp，与实测值同量级。
     */
    fun coverFallbackSizePx(screenWidthPx: Float, screenHeightPx: Float): Float =
        minOf(screenWidthPx * 0.4f, screenHeightPx * 0.5f)
}
