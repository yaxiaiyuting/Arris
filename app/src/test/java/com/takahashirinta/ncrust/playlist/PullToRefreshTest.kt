/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.0：下拉刷新阈值的单测（对齐 v1.7.0「阈值必须能被单测钉住」的教训）。
 */

package com.takahashirinta.ncrust.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullToRefreshTest {

    @Test
    fun `未越过阈值不触发`() {
        assertFalse(PullToRefresh.shouldTrigger(0f, atTop = true, alreadyLoading = false))
        assertFalse(PullToRefresh.shouldTrigger(159f, atTop = true, alreadyLoading = false))
    }

    @Test
    fun `恰好到达阈值即触发（左闭）`() {
        assertTrue(
            PullToRefresh.shouldTrigger(
                PullToRefresh.THRESHOLD_PX, atTop = true, alreadyLoading = false,
            )
        )
    }

    /**
     * **最重要的一条**：列表不在顶部时一律不触发。
     * 否则「往下滚列表」会被当成「下拉刷新」—— 这是这类控件最常见的 bug。
     */
    @Test
    fun `不在顶部时不触发`() {
        assertFalse(PullToRefresh.shouldTrigger(1000f, atTop = false, alreadyLoading = false))
    }

    /** 加载中不重复触发，避免连续下拉打出一串请求。 */
    @Test
    fun `加载中不重复触发`() {
        assertFalse(PullToRefresh.shouldTrigger(500f, atTop = true, alreadyLoading = true))
    }

    /** 上滑（负位移）不得触发。 */
    @Test
    fun `向上滑不触发`() {
        assertFalse(PullToRefresh.shouldTrigger(-200f, atTop = true, alreadyLoading = false))
        assertEquals(0f, PullToRefresh.progress(-200f), 0.001f)
    }

    @Test
    fun `进度在 0 到 1 之间且封顶`() {
        assertEquals(0f, PullToRefresh.progress(0f), 0.001f)
        assertEquals(0.5f, PullToRefresh.progress(PullToRefresh.MAX_DRAG_PX / 2f), 0.001f)
        assertEquals(1f, PullToRefresh.progress(PullToRefresh.MAX_DRAG_PX), 0.001f)
        assertEquals("超过最大位移不再增长", 1f, PullToRefresh.progress(10_000f), 0.001f)
    }

    @Test
    fun `armed 与 shouldTrigger 的阈值一致`() {
        assertFalse(PullToRefresh.armed(PullToRefresh.THRESHOLD_PX - 1f))
        assertTrue(PullToRefresh.armed(PullToRefresh.THRESHOLD_PX))
    }

    /**
     * 阈值是「像素位移」，不是比例 —— 这正是 v1.7.0 的教训：
     * 用比例表达的阈值必须换算成实际可拖动行程才知道是不是「要拖半个屏幕」。
     */
    @Test
    fun `阈值量级合理——不要求拖半个屏幕`() {
        assertTrue("阈值应远小于一个屏幕高度", PullToRefresh.THRESHOLD_PX < 400f)
        assertTrue("但也不该小到容易误触", PullToRefresh.THRESHOLD_PX > 100f)
    }
}
