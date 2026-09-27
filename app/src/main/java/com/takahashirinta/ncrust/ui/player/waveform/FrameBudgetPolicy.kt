/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

/**
 * v2.8.0 · P1-A：**帧时间超标的持续判定**（纯逻辑，无 Android 依赖 ⇒ 可 JVM 直测）。
 *
 * ## 判据为什么必须是「持续超标」而不是单帧
 *
 * 单帧超标的原因太多了（封面解码、列表滚动、GC、系统调度），拿它做降级会变成
 * "看谁不顺眼就关掉谁"。所以这里是一个**滑动窗口**：最近 [WINDOW_FRAMES] 帧里
 * 超预算的帧数达到 [OVER_BUDGET_FRAMES] 才算「持续」。
 *
 * ## 常量依据（都写清楚，不要凭感觉调）
 *
 * | 常量 | 值 | 依据 |
 * |---|---|---|
 * | [FRAME_BUDGET_NS] | 16_666_667ns | 60Hz 的一个 vsync 周期。"这一帧的 CPU 时长超过一个 vsync" 就是通行意义上的掉帧（jank）。注意 `FrameMetrics.TOTAL_DURATION` 量的是**这一帧自己的时长**、不是帧间隔，所以低端机把重绘限到 30fps 也不影响这个预算 |
 * | [WINDOW_FRAMES] | 60 帧 | ≈1 秒 @60fps：够长到把偶发长帧摊平，够短到 1 秒内就能做完决定 |
 * | [OVER_BUDGET_FRAMES] | 24 帧（40%） | 40% 的帧超标意味着"一直在掉"，而不是"偶尔抖一下"。取 40% 而不是 50%：可视化的开销是**持续**负载，一旦它把帧时间顶起来，超标比例就会稳定在高位 |
 *
 * ## 有界性（铁律 4）
 *
 *  - 每个 `FrameBudgetPolicy` 实例**最多返回一次 true**（[decided]）；
 *  - 调用方（`VisualizerFrameMonitor`）在拿到 true 之后立刻注销监听 ⇒ 之后连帧都不再看；
 *  - 「只降一级」「已降过就不再降」由 [VisualizerTier.downgradedTier] 与
 *    `VisualizerPrefs.applyAutoDowngrade` 负责，这一层只管"要不要通知一次"。
 *
 * ## 这个判据**不知道**是谁把帧顶起来的
 *
 * 归因需要 Perfetto（探针 §6.2）。这里是有意的保守单向阀：只在**可视化挂载期间**采样，
 * 只降一级、只发生一次、永不自动恢复。误判的代价是"用户少看一层特效"，
 * 而不是"用户被反复降档"。
 */
class FrameBudgetPolicy(
    /** 滑动窗口长度（帧）。 */
    private val windowSize: Int = WINDOW_FRAMES,
    /** 单帧预算（纳秒）：≥ 它算超标。 */
    private val budgetNs: Long = FRAME_BUDGET_NS,
    /** 窗口内超标帧数达到它才判定「持续超标」。 */
    private val overBudgetThreshold: Int = OVER_BUDGET_FRAMES,
) {
    init {
        require(windowSize > 0) { "windowSize must be > 0" }
        require(overBudgetThreshold in 1..windowSize) { "threshold must be in 1..windowSize" }
    }

    /** 环形窗口：每一项表示"那一帧是否超标"。定长 BooleanArray ⇒ 零分配。 */
    private val overBudget = BooleanArray(windowSize)

    private var cursor = 0

    /** 已采样的帧数（封顶 [windowSize]）—— 窗口没填满之前不结算。 */
    private var framesSeen = 0

    /** 窗口内当前超标帧数（增量维护，避免每帧 O(N) 扫描）。 */
    private var overCount = 0

    /** 已经判定过一次 ⇒ 之后永远返回 false（每实例最多一次）。 */
    private var decided = false

    /**
     * 采一帧。
     *
     * @param durationNs 该帧的总时长（`FrameMetrics.TOTAL_DURATION`）。
     *   非正数视为**没有数据**（平台在极端情况下会给出 0 / -1），不计入窗口、不推进游标 ——
     *   把"读不到"当成"没超标"会让判定永远不触发，当成"超标"则会误判。
     * @return true = 判定「持续超标」，调用方应当执行**一次**降级（之后本对象不再返回 true）。
     */
    fun onFrame(durationNs: Long): Boolean {
        if (decided) return false
        if (durationNs <= 0L) return false
        val isOver = durationNs >= budgetNs
        if (overBudget[cursor]) overCount--
        overBudget[cursor] = isOver
        if (isOver) overCount++
        cursor++
        if (cursor >= windowSize) {
            cursor = 0
            framesSeen = windowSize
        } else if (framesSeen < windowSize) {
            framesSeen++
        }
        if (framesSeen < windowSize) return false
        if (overCount < overBudgetThreshold) return false
        decided = true
        return true
    }

    /** 单测 / 诊断：窗口内当前超标帧数。 */
    fun overBudgetCount(): Int = overCount

    /** 单测 / 诊断：是否已经判定过。 */
    fun hasDecided(): Boolean = decided

    companion object {
        /** 单帧预算：60Hz 的一个 vsync 周期。 */
        const val FRAME_BUDGET_NS = 16_666_667L

        /** 滑动窗口长度（帧）：≈1 秒 @60fps。 */
        const val WINDOW_FRAMES = 60

        /** 窗口内超标帧数门槛：40%。 */
        const val OVER_BUDGET_FRAMES = 24
    }
}
