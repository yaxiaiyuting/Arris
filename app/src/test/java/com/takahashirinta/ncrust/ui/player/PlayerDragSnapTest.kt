/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.7.0 · P0 回归测试：播放器卡片两处纵向手势的吸附判定。
 *
 * 全部用例的数值都来自 PCL110（1272×2800 / Android 16）真机探针实测，
 * 不是构造出来的假设值 —— 见 PlayerDragSnap.kt 的头部注释与 TASK.md 第 13 节。
 *
 * 关键回归：
 *  - 收起态 400px 上滑（实测 endProgress=0.147）**必须展开**（旧逻辑要 0.5，拖不上去）；
 *  - 展开态 400px 下滑（实测 endProgress=0.8496）**必须收起**（旧逻辑要 <0.75）；
 *  - 微动不得误触发（手感不能变灵敏到误触）。
 */
class PlayerDragSnapTest {

    // ---------- 整卡：收起态 → 展开 ----------

    @Test
    fun `collapsed short up flick expands`() {
        // 实测：mini bar 上滑 400px/300ms → endProgress=0.147（旧逻辑 target=0，弹回）
        assertEquals(1f, PlayerCardDragSnap.target(0f, 0.147f, 0f))
    }

    @Test
    fun `collapsed long up drag expands`() {
        // 实测：1400px/2500ms → 0.57
        assertEquals(1f, PlayerCardDragSnap.target(0f, 0.57f, 0f))
    }

    @Test
    fun `collapsed tiny up drag stays collapsed`() {
        // 触摸 slop（42px）刚过就抬手：不得展开
        assertEquals(0f, PlayerCardDragSnap.target(0f, 0.02f, 0f))
    }

    @Test
    fun `collapsed fast up fling expands without distance`() {
        // 快速上甩 200px/120ms ≈ -1600px/s：行程不够也要展开（一甩就上来）
        assertEquals(1f, PlayerCardDragSnap.target(0f, 0.05f, -1600f))
    }

    @Test
    fun `collapsed down fling stays collapsed`() {
        assertEquals(0f, PlayerCardDragSnap.target(0f, 0f, 1600f))
    }

    @Test
    fun `expand threshold boundary`() {
        assertEquals(0f, PlayerCardDragSnap.target(0f, 0.079f, 0f))
        assertEquals(1f, PlayerCardDragSnap.target(0f, 0.08f, 0f))
    }

    @Test
    fun `collapsed 150px tiny drag stays collapsed but 250px expands`() {
        // 真机实测（2380px 行程，42px 触摸 slop）：150px→0.045 不展开；250px→0.087 展开
        assertEquals(0f, PlayerCardDragSnap.target(0f, 0.045f, 0f))
        assertEquals(1f, PlayerCardDragSnap.target(0f, 0.087f, 0f))
    }

    // ---------- 整卡：展开态 → 收起 ----------

    @Test
    fun `expanded short down drag collapses`() {
        // 实测：封面区下滑 400px/1500ms → endProgress=0.8496（旧逻辑 target=1，弹回）
        assertEquals(0f, PlayerCardDragSnap.target(1f, 0.8496f, 0f))
    }

    @Test
    fun `expanded tiny down drag stays expanded`() {
        assertEquals(1f, PlayerCardDragSnap.target(1f, 0.96f, 0f))
        // 150px 慢速下滑（扣 slop 后 ≈0.045）不足以收起
        assertEquals(1f, PlayerCardDragSnap.target(1f, 0.955f, 0f))
    }

    @Test
    fun `expanded fast down fling collapses`() {
        assertEquals(0f, PlayerCardDragSnap.target(1f, 0.95f, 1600f))
    }

    @Test
    fun `expanded fast up fling stays expanded`() {
        // 展开态里向上甩：不得被当成收起（0.95 在收起阈值 0.92 之上）
        assertEquals(1f, PlayerCardDragSnap.target(1f, 0.95f, -1600f))
    }

    @Test
    fun `collapse threshold boundary`() {
        assertEquals(0f, PlayerCardDragSnap.target(1f, 0.92f, 0f))
        assertEquals(1f, PlayerCardDragSnap.target(1f, 0.921f, 0f))
    }

    @Test
    fun `mid progress follows branch of start side`() {
        // 半开状态（动画被打断后重新起手）：分支按 startProgress 选，与旧实现同一判据
        //   startProgress < 0.5 → 展开分支（拖过 8% 就展开）
        assertEquals(1f, PlayerCardDragSnap.target(0.4f, 0.5f, 0f))
        //   startProgress >= 0.5 → 收起分支（没拖过 8% 就回展开）
        assertEquals(0f, PlayerCardDragSnap.target(0.6f, 0.6f, 0f))
        // 边界：0.49 仍算收起半边
        assertEquals(1f, PlayerCardDragSnap.target(0.49f, 0.08f, 0f))
        // 边界：0.5 起就落「收起」分支（与旧实现同一判据）；半开状态未拖过 8% → 归位收起
        assertEquals(0f, PlayerCardDragSnap.target(0.5f, 0.5f, 0f))
    }

    // ---------- 控制栏：收起 / 恢复 ----------

    @Test
    fun `controls expanded long up drag collapses`() {
        // 实测：把手慢上滑 300px/1500ms，h≈1005 → delta=+0.30 → 收起
        assertEquals(1f, ControlsDragSnap.target(0f, 0.30f, 0f))
    }

    @Test
    fun `controls new 12 percent threshold collapses`() {
        // 旧逻辑要 0.25（235px+slop≈280px，正常速度上滑 266px 够不到）
        assertEquals(1f, ControlsDragSnap.target(0f, 0.12f, 0f))
    }

    @Test
    fun `controls small up drag stays expanded`() {
        assertEquals(0f, ControlsDragSnap.target(0f, 0.08f, 0f))
    }

    @Test
    fun `controls fast up fling collapses`() {
        assertEquals(1f, ControlsDragSnap.target(0f, 0.03f, -1500f))
    }

    @Test
    fun `controls collapsed long down drag restores`() {
        // 实测：收起态把手下滑 200px/200ms，h≈996 → delta=-0.077 → 恢复
        assertEquals(0f, ControlsDragSnap.target(1f, 1f - 0.077f, 0f))
    }

    @Test
    fun `controls collapsed tiny down drag stays collapsed`() {
        // 实测：收起态把手下滑 30px/200ms → delta=-0.03，未到 5% 阈值 → 保持收起
        assertEquals(1f, ControlsDragSnap.target(1f, 1f - 0.03f, 0f))
    }

    @Test
    fun `controls collapsed fast down fling restores`() {
        assertEquals(0f, ControlsDragSnap.target(1f, 0.99f, 1500f))
    }

    @Test
    fun `controls restore threshold boundary`() {
        assertEquals(1f, ControlsDragSnap.target(1f, 1f - 0.049f, 0f))
        assertEquals(0f, ControlsDragSnap.target(1f, 1f - 0.05f, 0f))
    }

    @Test
    fun `controls micro move returns to origin side`() {
        assertEquals(0f, ControlsDragSnap.target(0f, 0.02f, 0f))
        assertEquals(1f, ControlsDragSnap.target(1f, 1f - 0.02f, 0f))
    }

    @Test
    fun `fling velocity threshold is inclusive at exactly 600`() {
        // 恰好等于阈值就算甩动（约定：达到即提交）
        assertEquals(1f, PlayerCardDragSnap.target(0f, 0.01f, -600f))
        assertEquals(0f, PlayerCardDragSnap.target(0f, 0.01f, -599f))
    }
}
