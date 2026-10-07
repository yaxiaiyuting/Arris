/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.1 · S6 回归单测：歌曲菜单操作列表的最大高度判定。
 */

package com.takahashirinta.ncrust.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.1 · S6 回归：**菜单条目数超过 N 条时的滚动行为**（任务书 §3.4 明确要求补的那条）。
 *
 * ## 背景（原始缺陷，v2.5.0 真机抓到、v2.5.1 补单测）
 *
 * S6/G9209 · Android 7.0 · 1440×2560 = **411×731dp**：歌曲菜单操作条目加到 **10 条**时
 * 弹层总高 752dp > 731dp，最后一条「单曲信息」被挤出屏幕；
 * `MetroBottomSheet` 的内容是不滚动的 `Column` ⇒ 那一条**真的点不到**。
 * 归因：v2.5.0 新增「添加到下一首播放」把它从 9 条推到 10 条。
 *
 * ## 这个类钉住的三件事
 *
 * 1. **回归场景确实被修法覆盖**：S6 竖屏 10 条 ⇒ `needsScroll == true`
 *    （也就是上限 555dp 一定小于条目总高 640dp，`heightIn(max=)` 会生效）；
 * 2. **修法没有引入新的行为变化**：高屏（平板 / 横屏）10 条 ⇒ `needsScroll == false`，
 *    `heightIn(max=)` 不生效 ⇒ 布局与改动前**逐字节一致**
 *    （这正是当初选 `heightIn(max=)` 而不是固定高度的理由）；
 * 3. **上限永远小于屏幕高**：否则"给列表一个最大高度"就挡不住溢出，修法等于没修。
 *
 * ## 与真机验证的分工（**说清楚，避免把单测当证据**）
 *
 * 本类证明的是**算术**。真机上还要证明另外两件事，那一轮在 release 包上做：
 *  - 最后一条「单曲信息」**真的能被滚到并点到**（`uiautomator dump` 的 bounds 证据）；
 *  - 该缺陷**不是 release-only**（v2.5.0 是在 **debug** 包上抓到的，
 *    本版再用 release 包复核一次，见 `docs/verification/v2.5.1/verification/`）。
 */
class SongMenuSheetLayoutTest {

    /** S6/G9209 竖屏的实测几何（dp）。 */
    private val s6PortraitHeightDp = 731

    /** 平板 / 横屏的量级（只需要一个"明显更高"的值，不是某台具体设备）。 */
    private val tallScreenHeightDp = 1000

    /** v2.5.0 之后的完整菜单条目数（新增「添加到下一首播放」后是 10 条）。 */
    private val fullMenuCount = 10

    // ─────────────────────────────────────────────────────────────────────
    // 1. 回归场景：S6 竖屏 10 条必须可滚
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S6 竖屏 10 条——上限小于条目总高，列表会滚动（回归被修法覆盖）`() {
        val cap = SongMenuSheetLayout.maxActionsHeightDp(s6PortraitHeightDp)
        val rows = fullMenuCount * SongMenuSheetLayout.ACTION_ROW_DP

        assertEquals("731 - 176", 555, cap)
        assertEquals("10 × 64", 640, rows)
        assertTrue("上限必须小于条目总高，heightIn(max=) 才会生效", rows > cap)
        assertTrue(SongMenuSheetLayout.needsScroll(s6PortraitHeightDp, fullMenuCount))
    }

    @Test
    fun `S6 竖屏——修法前的溢出是真实的：弹层总高超过屏幕`() {
        // 这条把「为什么必须修」写成断言，而不是留在注释里：
        // 112(信息区) + 10×64(条目) = 752 > 731。修法前的最后一条确实在屏幕外。
        val naiveTotal = 112 + fullMenuCount * SongMenuSheetLayout.ACTION_ROW_DP
        assertEquals(752, naiveTotal)
        assertTrue("弹层总高 $naiveTotal 必须真的超过屏幕 $s6PortraitHeightDp", naiveTotal > s6PortraitHeightDp)
    }

    @Test
    fun `上限永远小于屏幕高——否则修法等于没修`() {
        // 只在"减去固定开销后仍不低于下限"的区间上成立；低于该区间时下限 160 接管
        // （见下一条用例）。这一条覆盖从最小可用高度到平板的所有现实取值。
        for (h in 336..2000 step 1) {
            val cap = SongMenuSheetLayout.maxActionsHeightDp(h)
            assertTrue("屏幕高 $h 时上限 $cap 必须严格小于屏幕高", cap < h)
            assertEquals(h - SongMenuSheetLayout.CHROME_DP, cap)
        }
    }

    @Test
    fun `极小屏下限 160dp——上限失真也不能让菜单不可用`() {
        assertEquals(160, SongMenuSheetLayout.maxActionsHeightDp(300))
        assertEquals(160, SongMenuSheetLayout.maxActionsHeightDp(336))
        assertEquals(161, SongMenuSheetLayout.maxActionsHeightDp(337))
        // 下限下"是否滚动"仍然回答 true（条目总高 640 > 160），菜单依然可滚。
        assertTrue(SongMenuSheetLayout.needsScroll(300, fullMenuCount))
        assertFalse("空菜单永远不需要滚动", SongMenuSheetLayout.needsScroll(300, 0))
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. 不许引入新的行为变化：放得下就与改动前逐字节一致
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `高屏 10 条——放得下就不滚动，布局与修法前一致`() {
        val cap = SongMenuSheetLayout.maxActionsHeightDp(tallScreenHeightDp)
        val rows = fullMenuCount * SongMenuSheetLayout.ACTION_ROW_DP
        assertEquals(824, cap)
        assertTrue("条目总高必须小于上限，heightIn(max=) 才不会生效", rows <= cap)
        assertFalse(SongMenuSheetLayout.needsScroll(tallScreenHeightDp, fullMenuCount))
    }

    @Test
    fun `条目越少越不容易滚动——单调性`() {
        // 对同一个屏幕高，needsScroll 必须随条目数单调不减：
        // 一旦出现"11 条不需要滚、10 条需要滚"，就说明上限公式写错了。
        for (h in listOf(300, 480, 731, 1000, 1600)) {
            var prev = false
            for (n in 0..30) {
                val now = SongMenuSheetLayout.needsScroll(h, n)
                assertTrue("屏幕高 $h：$n 条时滚动判定比 ${n - 1} 条时更宽松", now || !prev)
                prev = now
            }
        }
    }

    @Test
    fun `阈值是半开区间——恰好等于上限时不滚，多 1dp 才滚`() {
        // 上限 555 ⇒ 8 条(512) 不滚、9 条(576) 滚。边界写清楚，
        // 免得下一个人把 `>` 改成 `>=` 时以为"没区别"。
        assertEquals(555, SongMenuSheetLayout.maxActionsHeightDp(s6PortraitHeightDp))
        assertFalse(SongMenuSheetLayout.needsScroll(s6PortraitHeightDp, 8))
        assertTrue(SongMenuSheetLayout.needsScroll(s6PortraitHeightDp, 9))
        assertFalse(SongMenuSheetLayout.needsScroll(s6PortraitHeightDp, 555 / SongMenuSheetLayout.ACTION_ROW_DP))
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. 常量与调用点的一致性（改名/改 padding 时必须一起改）
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `固定开销与行高是常量——与调用点SongMenuSheet的 padding 同步`() {
        // 176 与 64 是从 S6 实测反推的（752 − 640 = 112，再加 64 安全区）。
        // 调用点 `Row(...).padding(horizontal = 16.dp, vertical = 16.dp)` + 24dp 图标 = 64dp；
        // 改那行 padding 的人必须同时改这里，否则本类的推算全部失真。
        assertEquals(176, SongMenuSheetLayout.CHROME_DP)
        assertEquals(64, SongMenuSheetLayout.ACTION_ROW_DP)
        assertEquals(160, SongMenuSheetLayout.MIN_ACTIONS_HEIGHT_DP)
    }
}
