/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.8.0 · J12：progress 域 → **屏幕坐标**竖直速度的符号契约。
 */

package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J12 的回归契约：吸附判定收到的 `velocityY` 必须是**屏幕坐标**（向上为负）。
 *
 * ## 缺陷本体
 *
 * 「展开进度」与「屏幕 Y」方向**相反**：手指向上划 ⇒ 屏幕 y 减小（`dragAmount < 0`）
 * ⇒ 进度**增加**。而 [PlayerCardDragSnap.target] / [ControlsDragSnap.target] 的
 * `velocityY` 参数约定的是屏幕 Y（向上为负，阈值 `FLING_VELOCITY_PX_PER_S = 600`）。
 *
 * 旧实现把 progress 域的增量直接当屏幕速度传进去（两处都没有反号）：
 *
 * | 手势 | 真实情况 | 旧实现传给判定的速度 | 判定结果 |
 * |---|---|---|---|
 * | 收起态上甩 40px / 10ms | 向上 4000px/s | **+4000** | 「下滑」⇒ 弹回收起（应展开） |
 * | 控制栏上拖 120px / 50ms | 向上 2400px/s | **+2400** | 「下拉」⇒ 恢复控制栏（应收起） |
 *
 * ## 修法
 *
 * 符号只允许在**一处**出现：[PlayerCardDragSnap.screenVelocityY]。整卡拖拽与控制栏
 * 都用它换算（两处各写一个 `-` 正是这条缺陷的成因）。
 */
class PlayerDragScreenVelocityTest {

    @Test
    fun `向上划（进度增加）得到负的屏幕速度`() {
        val v = PlayerCardDragSnap.screenVelocityY(
            progressDelta = 0.04f, // 向上划 40px / 1000px 行程
            travelPx = 1000f,
            elapsedMs = 10L,
        )
        assertEquals("屏幕 Y 向上为负 —— 4% 行程 / 10ms = 4000px/s", -4000f, v, 1e-3f)
    }

    @Test
    fun `向下划（进度减少）得到正的屏幕速度`() {
        val v = PlayerCardDragSnap.screenVelocityY(progressDelta = -0.04f, travelPx = 1000f, elapsedMs = 10L)
        assertEquals(4000f, v, 1e-3f)
    }

    @Test
    fun `没有时间就没有速度（不产生 Inf）`() {
        assertEquals(0f, PlayerCardDragSnap.screenVelocityY(0.04f, 1000f, 0L), 0f)
        assertEquals(0f, PlayerCardDragSnap.screenVelocityY(0.04f, 1000f, -5L), 0f)
    }

    @Test
    fun `收起态上甩必须展开 —— 修复前的符号会把它判成弹回`() {
        val travel = 1000f
        val delta = 0.04f          // 40px / 1000px
        val elapsed = 10L          // 4000px/s

        val fixed = PlayerCardDragSnap.screenVelocityY(delta, travel, elapsed)
        assertEquals("修复后：向上甩 ⇒ 展开", 1f, PlayerCardDragSnap.target(0f, delta, fixed), 0f)

        val oldBug = delta * travel / (elapsed / 1000f) // 旧实现（未反号）
        assertEquals("旧符号（+4000）确实会判成弹回 —— 这条断言钉住缺陷形状", 0f, PlayerCardDragSnap.target(0f, delta, oldBug), 0f)
    }

    @Test
    fun `控制栏上拖必须收起 —— 行走阈值之上也不许被反向甩动翻回来`() {
        val height = 400f
        val from = 0f
        val current = 0.30f        // 已越过 12% 的行程阈值
        val elapsed = 50L

        val fixed = PlayerCardDragSnap.screenVelocityY(current - from, height, elapsed)
        assertEquals("修复后：向上推 ⇒ 收起", 1f, ControlsDragSnap.target(from, current, fixed), 0f)

        val oldBug = (current - from) * height / (elapsed / 1000f) // 旧实现（未反号）
        assertEquals(
            "旧符号（+2400）会把「已经越过阈值的上拖」判成下拉恢复 —— 探针复现的那个形状",
            0f,
            ControlsDragSnap.target(from, current, oldBug),
            0f,
        )
    }

    @Test
    fun `两个吸附判定的甩动阈值口径一致`() {
        assertEquals(
            "整卡与控制栏共用同一条甩动阈值（屏幕 Y，px/s）",
            PlayerCardDragSnap.FLING_VELOCITY_PX_PER_S,
            ControlsDragSnap.FLING_VELOCITY_PX_PER_S,
            0f,
        )
        assertTrue(
            "换算出的向上速度必须达到甩动阈值才会走甩动分支",
            PlayerCardDragSnap.screenVelocityY(0.1f, 1000f, 100L) <= -PlayerCardDragSnap.FLING_VELOCITY_PX_PER_S,
        )
    }
}
