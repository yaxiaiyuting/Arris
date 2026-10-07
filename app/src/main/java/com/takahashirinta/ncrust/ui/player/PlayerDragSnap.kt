/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（v1.7.0 · P0「收起后拖不上来」）：
 *   把播放器卡片两处纵向手势的**吸附判定**从 PlayerCard.kt 里抽成纯函数，便于 JVM 单测。
 *   原实现的行程阈值相对拖动总距离过大，实测「拖不上来」，见下方注释。
 */

package com.takahashirinta.ncrust.ui.player

/**
 * v1.7.0 · P0：播放器卡片「整卡展开 / 收起」的吸附判定（纯逻辑，可单测）。
 *
 * 背景（PCL110 / 1272×2800 / Android 16 真机探针实测，v1.6.1）：
 *
 * | 手势 | 原判据 | 需要的行程 | 用户感受 |
 * |---|---|---|---|
 * | 收起态 mini bar 上滑展开 | progress >= 0.5 | **1190px ≈ 340dp** | 拖不上去，松手弹回 |
 * | 展开态内容区下滑收起 | progress <= 0.75 | 595px ≈ 170dp | 要拖很长 |
 *
 * totalDragDistancePx = contentHeightPx * 0.85（MainScreen），卡片必须走完整段行程
 * 才能让 progress 从 0 到 1 —— 于是「过中点」= 手指要跨过 340dp。用户报告
 * 「收起播放器界面后…只能拖下去不能拖上来」正是这一条：快速上滑（400px）只到
 * progress≈0.15，松手按原逻辑弹回收起态，看起来就像手势被歌词界面吃掉了。
 *
 * 现在的判定把两件事分开：
 *  1. **方向敏感的行程阈值**（占拖动总距离的比例）：向上 15% / 向下 15%；
 *  2. **甩动（fling）**：速度超过 FLING_VELOCITY_PX_PER_S 时按方向直接提交，
 *     不再要求行程 —— 符合「快速一甩」的直觉。
 *
 * 阈值取 15% 而不是 5%：整卡拖拽区包含封面/顶栏等非面板区域，太灵敏会误收起。
 */
internal object PlayerCardDragSnap {

    /**
     * 收起态（startProgress < 0.5）向上拖：行程占比达到该值即展开。
     *
     * 为什么是 0.08：真机实测 mini bar 上滑在 endProgress 上的落点是
     * 150px→0.045、250px→0.087、300px→0.108、400px→0.147（2380px 行程，前 42px 是触摸 slop）。
     * 原实现要求 >=0.5（=1190px≈340dp），以上手势**全部**被判成弹回 ——
     * 正是用户报的「收起播放器界面后…只能拖下去不能拖上来」。
     * 0.08 对应 190px+slop ≈ 66dp 行程：一次正常的短上滑就能拖出来，
     * 而 60px 级别的轻触仍然不展开（那种轻触由 mini bar 的点按展开兜底）。
     */
    const val EXPAND_FRACTION = 0.08f

    /** 展开态向下拖：行程占比达到该值即收起（同样留出 slop 余量）。 */
    const val COLLAPSE_FRACTION = 0.08f

    /**
     * 甩动速度阈值（px/s，屏幕 y 向下为正）。**达到**该值即按方向提交，与行程无关。
     *
     * 取 600 而不是 900：真机用 adb 注入的「250px/200ms」名义上 1250px/s，但事件时间戳
     * 实测被摊薄（同一注入在控制栏检测器上能过 900、在整卡检测器上过不了），而真人手指的
     * 一顿快速上滑普遍在 600~2000px/s。600 既覆盖真实甩动，又远高于「慢慢拖」的 <300px/s。
     */
    const val FLING_VELOCITY_PX_PER_S = 600f

    /**
     * @param startProgress 手势按下时的 progress（0 = 收起成 mini bar，1 = 全屏）
     * @param endProgress   抬手时的 progress
     * @param velocityY     抬手时的竖直速度（px/s，向上为负）
     * @return 1f = 展开，0f = 收起
     */
    fun target(startProgress: Float, endProgress: Float, velocityY: Float): Float {
        val expanding = startProgress < 0.5f
        return if (expanding) {
            val flingUp = velocityY <= -FLING_VELOCITY_PX_PER_S
            if (endProgress >= EXPAND_FRACTION || flingUp) 1f else 0f
        } else {
            val flingDown = velocityY >= FLING_VELOCITY_PX_PER_S
            if (endProgress <= 1f - COLLAPSE_FRACTION || flingDown) 0f else 1f
        }
    }
}

/**
 * v1.7.0 · P0：底部控制栏「收起 / 恢复」的吸附判定（纯逻辑，可单测）。
 *
 * 原实现（v1.4.0 · A）：收起要 25% 行程、恢复只要 5%。方向敏感本身是对的
 * （收起会挡住内容、需要「故意」；恢复没有副作用、门槛要低），但 25% 在
 * PCL110 上 = 235px + 42px 触摸 slop ≈ 280px，正常速度的上滑（266px/400ms）
 * 刚好够不到 —— 实测「上拖收起」时灵时不灵。收窄到 12% 并补上甩动判定。
 */
internal object ControlsDragSnap {

    /** 收起态向下拖：行程占比达到该值即恢复（保持 v1.4.2 的小阈值手感）。 */
    const val RESTORE_FRACTION = 0.05f

    /** 展开态向上拖：行程占比达到该值即收起（原 0.25，实测够不到）。 */
    const val COLLAPSE_FRACTION = 0.12f

    /** 甩动速度阈值（px/s，屏幕 y 向下为正）。与控制栏/整卡同一取值，见上。 */
    const val FLING_VELOCITY_PX_PER_S = 600f

    /**
     * @param from      手势按下时的收起进度（0 = 展开，1 = 完全收起）
     * @param current   抬手时的收起进度
     * @param velocityY 抬手时的竖直速度（px/s，向上为负）
     * @return 1f = 收起控制栏，0f = 展开控制栏
     */
    fun target(from: Float, current: Float, velocityY: Float): Float {
        val delta = current - from
        return when {
            velocityY <= -FLING_VELOCITY_PX_PER_S -> 1f
            velocityY >= FLING_VELOCITY_PX_PER_S -> 0f
            delta <= -RESTORE_FRACTION -> 0f
            delta >= COLLAPSE_FRACTION -> 1f
            else -> if (from >= 0.5f) 1f else 0f
        }
    }
}
