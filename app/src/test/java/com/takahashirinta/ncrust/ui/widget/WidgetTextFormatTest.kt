/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0 · 桌面播放卡片（App Widget）：**卡片文案与进度值**的 JVM 单测。
 *
 * 这一层的价值在于「格式错了用户一眼就看见」：桌面卡片上没有 Compose 的可见性语义
 * （空串 = 白留一行）、没有兜底、也没法在真机上逐个时长去试。
 */

package com.takahashirinta.ncrust.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetTextFormatTest {

    // ---------------------------------------------------------------- 时长格式

    @Test
    fun `秒级时长格式`() {
        assertEquals("0:00", WidgetTextFormat.formatTime(0L))
        assertEquals("0:00", WidgetTextFormat.formatTime(999L))
        assertEquals("0:01", WidgetTextFormat.formatTime(1_000L))
        assertEquals("0:59", WidgetTextFormat.formatTime(59_999L))
        assertEquals("1:00", WidgetTextFormat.formatTime(60_000L))
        assertEquals("3:07", WidgetTextFormat.formatTime(187_000L))
        assertEquals("9:59", WidgetTextFormat.formatTime(599_999L))
    }

    @Test
    fun `超过一小时给小时段`() {
        assertEquals("1:00:00", WidgetTextFormat.formatTime(3_600_000L))
        assertEquals("1:01:01", WidgetTextFormat.formatTime(3_661_000L))
        assertEquals("10:00:00", WidgetTextFormat.formatTime(36_000_000L))
    }

    @Test
    fun `负数与 TIME_UNSET 一律显示 0 00（卡片上不能出现负号）`() {
        // media3 的 C.TIME_UNSET = Long.MIN_VALUE：时长未知时 player.duration 就是它。
        assertEquals("0:00", WidgetTextFormat.formatTime(-1L))
        assertEquals("0:00", WidgetTextFormat.formatTime(Long.MIN_VALUE))
    }

    // ---------------------------------------------------------------- 空值兜底

    @Test
    fun `标题为空串或空白时给占位`() {
        assertEquals("未知曲目", WidgetTextFormat.titleOrFallback(null, "未知曲目"))
        assertEquals("未知曲目", WidgetTextFormat.titleOrFallback("", "未知曲目"))
        assertEquals("未知曲目", WidgetTextFormat.titleOrFallback("   ", "未知曲目"))
        assertEquals("纯音乐 请欣赏", WidgetTextFormat.titleOrFallback("纯音乐 请欣赏", "未知曲目"))
    }

    @Test
    fun `歌手为空串或空白时给占位`() {
        assertEquals("未知歌手", WidgetTextFormat.artistOrFallback(null, "未知歌手"))
        assertEquals("未知歌手", WidgetTextFormat.artistOrFallback("", "未知歌手"))
        assertEquals("未知歌手", WidgetTextFormat.artistOrFallback("\n", "未知歌手"))
        assertEquals("Aimer", WidgetTextFormat.artistOrFallback("Aimer", "未知歌手"))
    }

    @Test
    fun `两个占位函数互相独立——标题的兜底不会串到歌手`() {
        val title = WidgetTextFormat.titleOrFallback(null, "T")
        val artist = WidgetTextFormat.artistOrFallback(null, "A")
        assertEquals("T", title)
        assertEquals("A", artist)
        assertTrue(title != artist)
    }

    // ---------------------------------------------------------------- 进度条数值

    @Test
    fun `时长未知时不画进度条`() {
        // 一条永远停在 0% 的进度条比没有进度条更像坏了。
        assertNull(WidgetProgressSpec.values(positionMs = 0L, durationMs = 0L))
        assertNull(WidgetProgressSpec.values(positionMs = 5_000L, durationMs = -1L))
        assertNull(WidgetProgressSpec.values(positionMs = 0L, durationMs = Long.MIN_VALUE))
    }

    @Test
    fun `进度按千分比换算`() {
        assertEquals(WidgetProgressValues(1_000, 0), WidgetProgressSpec.values(0L, 1_000L))
        assertEquals(WidgetProgressValues(1_000, 500), WidgetProgressSpec.values(500L, 1_000L))
        assertEquals(WidgetProgressValues(1_000, 1_000), WidgetProgressSpec.values(1_000L, 1_000L))
        assertEquals(WidgetProgressValues(1_000, 250), WidgetProgressSpec.values(45_000L, 180_000L))
    }

    @Test
    fun `位置越界时夹在 0 到时长之间`() {
        // seek 的乐观更新 / 时长变化的那一帧都可能让位置短暂超过时长，
        // 不夹的话 ProgressBar 会画出越界的进度（在部分 ROM 上是可见的错位）。
        assertEquals(WidgetProgressValues(1_000, 1_000), WidgetProgressSpec.values(999_999L, 1_000L))
        assertEquals(WidgetProgressValues(1_000, 0), WidgetProgressSpec.values(-5_000L, 1_000L))
    }

    @Test
    fun `max 恒为 1000 且 progress 永不越界`() {
        val cases = listOf(
            0L to 1L,
            1L to 1L,
            1L to 3_600_000L,
            123_456L to 3_600_000L,
            3_599_999L to 3_600_000L,
        )
        for ((position, duration) in cases) {
            val values = WidgetProgressSpec.values(position, duration)
            assertEquals(WidgetProgressSpec.MAX, values!!.max)
            assertTrue("progress 越界：$values", values.progress in 0..values.max)
        }
    }
}
