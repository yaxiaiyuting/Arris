/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.listen

/**
 * 漂移处置（v3.8.0 · 一起听）。**纯逻辑，JVM 可单测。**
 *
 * 三档判据来自调研报告 §3.3（阈值是桌面环境经验值，**需真机 minRtt 分布验证**）：
 *
 * | 偏差 | 处置 | 听感 |
 * |---|---|---|
 * | ≤ [IGNORE_MS] | 忽略 | 无 |
 * | ≤ [MICRO_MS] | 保音高微调倍率（`setPlaybackParameters`） | 察觉不到 |
 * | 更大 | seek + 冷却（指数退避） | 一次小跳 |
 *
 * 冷却的意义：网络抖动时如果每帧都 seek，会变成"一直在原地跳"，比不同步更难听。
 */
object DriftPolicy {

    const val IGNORE_MS = 50L
    const val MICRO_MS = 300L

    /** 微调倍率上限（±2%）。超过这个量人耳能听出音调/节奏变化。 */
    const val MAX_RATE_ADJUST = 0.02f

    /** seek 冷却起点与上限。 */
    const val SEEK_COOLDOWN_START_MS = 3_000L
    const val SEEK_COOLDOWN_MAX_MS = 24_000L

    sealed interface Action {
        data object Ignore : Action
        data class MicroAdjust(val rate: Float) : Action
        data object Seek : Action
    }

    /**
     * 决定这一次要不要动手。
     *
     * @param driftMs `target − actual`（正 = 我落后了，需要快一点）
     * @param lastSeekAtMs 上次 seek 的时刻（-1 = 从未）
     * @param cooldownMs 当前冷却长度（由调用方从上次结果里带回来，形成指数退避）
     */
    fun decide(
        driftMs: Long,
        localNowMs: Long,
        lastSeekAtMs: Long = -1L,
        cooldownMs: Long = SEEK_COOLDOWN_START_MS,
    ): Action {
        val magnitude = kotlin.math.abs(driftMs)
        if (magnitude <= IGNORE_MS) return Action.Ignore
        if (magnitude <= MICRO_MS) {
            // 把偏差摊进倍率：正偏差（落后）⇒ 加快；负偏差 ⇒ 放慢。
            val ratio = (1f + (driftMs.toFloat() / MICRO_MS.toFloat()) * MAX_RATE_ADJUST)
                .coerceIn(1f - MAX_RATE_ADJUST, 1f + MAX_RATE_ADJUST)
            return Action.MicroAdjust(ratio)
        }
        // 大偏差：冷却期内不反复 seek。
        if (lastSeekAtMs >= 0 && localNowMs - lastSeekAtMs < cooldownMs) return Action.Ignore
        return Action.Seek
    }

    /** 下一次 seek 的冷却长度（指数退避，封顶 [SEEK_COOLDOWN_MAX_MS]）。 */
    fun nextCooldown(previous: Long): Long =
        (previous * 2).coerceAtMost(SEEK_COOLDOWN_MAX_MS)
}
