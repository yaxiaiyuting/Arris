/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.0 · A 回归单测：列表入场动效的**有界性**（铁律 15）。
 */

package com.takahashirinta.ncrust.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0 · A：列表 item 入场动效必须是**有界**的。
 *
 * ## 为什么这条要有单测
 *
 * 铁律 15：**UI 动效不得影响播放性能。** 本应用的目标下限是 Android 7.0 / 3GB RAM。
 * 「列表 item 出现时淡入 + 上滑」如果对每个 item 都执行，快速甩动一屏 8~10 行的列表时
 * 每帧会有十几个 `Animatable` 同时在跑 —— 那是低端机上最典型的掉帧来源。
 *
 * 「有界」这件事如果只写在注释里，下一个人把 `MAX_ANIMATED_INDEX` 改成
 * `Int.MAX_VALUE`（或者干脆去掉判断）不会有任何东西变红。所以把它测出来 ——
 * 与 v2.2.1「有界必须能被测出来」是同一条纪律。
 */
class ListItemAppearTest {

    @Test
    fun `首屏范围内的下标播放入场动效`() {
        assertTrue(ListItemAppear.shouldAnimate(0))
        assertTrue(ListItemAppear.shouldAnimate(1))
        assertTrue(ListItemAppear.shouldAnimate(ListItemAppear.MAX_ANIMATED_INDEX - 1))
    }

    @Test
    fun `阈值之后一律不动画 —— 甩动长列表时一个动画都不起`() {
        assertFalse(ListItemAppear.shouldAnimate(ListItemAppear.MAX_ANIMATED_INDEX))
        assertFalse(ListItemAppear.shouldAnimate(ListItemAppear.MAX_ANIMATED_INDEX + 1))
        assertFalse(ListItemAppear.shouldAnimate(100))
        assertFalse(ListItemAppear.shouldAnimate(10_000))
    }

    @Test
    fun `负下标不动画（理论上不出现 但越界读列表会是崩溃）`() {
        assertFalse(ListItemAppear.shouldAnimate(-1))
        assertFalse(ListItemAppear.shouldAnimate(Int.MIN_VALUE))
    }

    @Test
    fun `阈值必须是一个小的固定值 —— 防止被改成 Int_MAX_VALUE 让动效失去边界`() {
        // 上限取 64：一屏放不下 64 行（窄屏单曲行约 64dp，400dp 高的可视区只有 6 行），
        // 所以 64 已经远超"首屏"的语义。真正的意图是拦住"干脆不设界"。
        assertTrue(
            "MAX_ANIMATED_INDEX=${ListItemAppear.MAX_ANIMATED_INDEX} 过大，" +
                "入场动效将失去边界（铁律 15：动效不得影响播放性能）",
            ListItemAppear.MAX_ANIMATED_INDEX in 1..64,
        )
    }

    @Test
    fun `边界是半开区间 —— 恰好 MAX 这一项不动画`() {
        // 这一条与第一条互为反面，防止将来把 `<` 写成 `<=` 时两边同时"看起来对"。
        assertTrue(ListItemAppear.shouldAnimate(ListItemAppear.MAX_ANIMATED_INDEX - 1))
        assertFalse(ListItemAppear.shouldAnimate(ListItemAppear.MAX_ANIMATED_INDEX))
    }
}
