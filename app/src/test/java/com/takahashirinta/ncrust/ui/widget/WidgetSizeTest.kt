/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0 · 桌面播放卡片（App Widget）：**尺寸分桶**的 JVM 单测。
 *
 * 为什么这一层必须测：真机上「尺寸算错」的表现是「桌面上卡片排版错乱 / 标题被裁 / 白卡片」，
 * 而它在这里只是几行断言。AGENTS.md v2.2.1 规则 5：**能在便宜的那一层测出来的东西，
 * 不要留给真机**。
 */

package com.takahashirinta.ncrust.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSizeTest {

    // ---------------------------------------------------------------- 三档基本映射

    @Test
    fun `2x1 落到小档`() {
        assertEquals(WidgetSize.SMALL, WidgetSizeResolver.resolve(minWidthDp = 110, minHeightDp = 40))
    }

    @Test
    fun `4x1 落到中档`() {
        assertEquals(WidgetSize.MEDIUM, WidgetSizeResolver.resolve(minWidthDp = 250, minHeightDp = 40))
    }

    @Test
    fun `4x2 落到大档`() {
        assertEquals(WidgetSize.LARGE, WidgetSizeResolver.resolve(minWidthDp = 250, minHeightDp = 110))
    }

    // ---------------------------------------------------------------- 退化路径

    @Test
    fun `2x2 落到小档——宽度不够放下三键`() {
        // 高度够了（2 行），但 110dp 宽放不下「封面 + 文字 + 三个 36dp 按钮」。
        // 猜大了的代价是标题被裁到看不见、按钮小到点不到，比「少显示两个按钮」严重得多。
        assertEquals(WidgetSize.SMALL, WidgetSizeResolver.resolve(minWidthDp = 110, minHeightDp = 110))
    }

    @Test
    fun `3 格宽（180dp）仍然落到小档`() {
        assertEquals(WidgetSize.SMALL, WidgetSizeResolver.resolve(minWidthDp = 180, minHeightDp = 40))
    }

    @Test
    fun `比 4 格更宽仍然是大档（5x2）`() {
        assertEquals(WidgetSize.LARGE, WidgetSizeResolver.resolve(minWidthDp = 320, minHeightDp = 110))
    }

    @Test
    fun `比 2 行更高也是大档（4x3）`() {
        assertEquals(WidgetSize.LARGE, WidgetSizeResolver.resolve(minWidthDp = 250, minHeightDp = 180))
    }

    // ---------------------------------------------------------------- 未知 / 非法尺寸

    @Test
    fun `未知尺寸（宿主返回 0）落到小档`() {
        // getAppWidgetOptions 在部分 launcher / 刚添加卡片时会返回空 Bundle，
        // getInt 的缺省值就是 0。这一档必须是**最小**而不是最大。
        assertEquals(WidgetSize.SMALL, WidgetSizeResolver.resolve(minWidthDp = 0, minHeightDp = 0))
    }

    @Test
    fun `负数尺寸落到小档（不崩、不越界）`() {
        assertEquals(WidgetSize.SMALL, WidgetSizeResolver.resolve(minWidthDp = -1, minHeightDp = -1))
        assertEquals(WidgetSize.SMALL, WidgetSizeResolver.resolve(minWidthDp = Int.MIN_VALUE, minHeightDp = Int.MIN_VALUE))
    }

    @Test
    fun `只有宽度合法、高度未知时按宽度分档`() {
        // 竖直方向还没量出来（0），但宽度已经够 4 格 ⇒ 给中档（进度条属于大档，
        // 高度未知时不该假设放得下）。
        assertEquals(WidgetSize.MEDIUM, WidgetSizeResolver.resolve(minWidthDp = 250, minHeightDp = 0))
    }

    // ---------------------------------------------------------------- 阈值边界

    @Test
    fun `阈值上下各差 1dp 的边界行为`() {
        assertEquals(WidgetSize.SMALL, WidgetSizeResolver.resolve(249, 109))
        assertEquals(WidgetSize.SMALL, WidgetSizeResolver.resolve(249, 110))
        assertEquals(WidgetSize.SMALL, WidgetSizeResolver.resolve(180, 110))
        assertEquals(WidgetSize.MEDIUM, WidgetSizeResolver.resolve(250, 109))
        assertEquals(WidgetSize.LARGE, WidgetSizeResolver.resolve(250, 110))
        assertEquals(WidgetSize.LARGE, WidgetSizeResolver.resolve(251, 111))
    }

    @Test
    fun `分档在两个方向上都单调——拖大卡片不会掉档`() {
        val widths = listOf(0, 100, 110, 180, 249, 250, 320, 600)
        val heights = listOf(0, 39, 40, 109, 110, 180, 400)
        val order = mapOf(WidgetSize.SMALL to 0, WidgetSize.MEDIUM to 1, WidgetSize.LARGE to 2)
        for (h in heights) {
            var previous = -1
            for (w in widths) {
                val rank = order.getValue(WidgetSizeResolver.resolve(w, h))
                assertTrue("宽度 $w 高 $h 时档位回退了（$previous -> $rank）", rank >= previous)
                previous = rank
            }
        }
        for (w in widths) {
            var previous = -1
            for (h in heights) {
                val rank = order.getValue(WidgetSizeResolver.resolve(w, h))
                assertTrue("宽 $w 高度 $h 时档位回退了（$previous -> $rank）", rank >= previous)
                previous = rank
            }
        }
    }

    // ---------------------------------------------------------------- 格子换算（文档锚点）

    @Test
    fun `minWidth minHeight 到格子数的换算与系统口径一致`() {
        // 系统的换算是 `70n - 30`：1 格 = 40dp、2 格 = 110dp、4 格 = 250dp。
        // 这条用例把「三档 = 2x1 / 4x1 / 4x2」这件用户语言里的事钉在代码里。
        assertEquals(2 to 1, WidgetSizeResolver.cellsFor(110, 40))
        assertEquals(4 to 1, WidgetSizeResolver.cellsFor(250, 40))
        assertEquals(4 to 2, WidgetSizeResolver.cellsFor(250, 110))
        assertEquals(1 to 1, WidgetSizeResolver.cellsFor(0, 0))
        assertEquals(1 to 1, WidgetSizeResolver.cellsFor(-100, -100))
    }

    // ---------------------------------------------------------------- 能力位

    @Test
    fun `小档只有封面标题与播放键`() {
        val caps = WidgetLayoutSpec.capabilitiesFor(WidgetSize.SMALL)
        assertFalse(caps.showArtist)
        assertFalse(caps.showTransport)
        assertFalse(caps.showProgress)
    }

    @Test
    fun `中档加歌手行与三键，但没有进度条`() {
        val caps = WidgetLayoutSpec.capabilitiesFor(WidgetSize.MEDIUM)
        assertTrue(caps.showArtist)
        assertTrue(caps.showTransport)
        assertFalse("4x1 的高度放不下进度条 + 计时行", caps.showProgress)
    }

    @Test
    fun `大档全开`() {
        val caps = WidgetLayoutSpec.capabilitiesFor(WidgetSize.LARGE)
        assertTrue(caps.showArtist)
        assertTrue(caps.showTransport)
        assertTrue(caps.showProgress)
    }

    @Test
    fun `能力位随档位单调递增且封面尺寸合法`() {
        val sizes = WidgetSize.values()
        var artists = 0
        var transports = 0
        var progresses = 0
        for (size in sizes) {
            val caps = WidgetLayoutSpec.capabilitiesFor(size)
            assertEquals(size, caps.size)
            assertTrue("封面 dp 必须为正", caps.coverDp > 0)
            // 卡片上封面最大只有 56dp 见方；超过 96dp 说明有人把布局改大了却忘了这里，
            // 后果是位图白算（Binder 事务变大、宿主还要降采样）。
            assertTrue("封面 dp 过大：${caps.coverDp}", caps.coverDp <= 96)
            if (caps.showArtist) artists++
            if (caps.showTransport) transports++
            if (caps.showProgress) progresses++
        }
        assertEquals(2, artists)
        assertEquals(2, transports)
        assertEquals(1, progresses)
    }
}
