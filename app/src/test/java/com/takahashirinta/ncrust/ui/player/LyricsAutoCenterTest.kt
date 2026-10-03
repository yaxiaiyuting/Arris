/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 · E 单元测试：横屏歌词「5s 无触碰自动居中」的**纯判定**。
 *
 * 这一组用例钉的是三件事：
 *  1. **5 秒这个数**（任务书 7.2 的定值，不许在别处再写一个）；
 *  2. **拖拽中不打断**（任务书 7.3 的硬要求，且是最容易写错的一条）；
 *  3. **世代号回绕不回 0**（否则功能会静默失效，而不是报错）。
 */

package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsAutoCenterTest {

    // ------------------------------------------------------------ 时长 ----

    @Test
    fun `无触碰阈值是 5 秒`() {
        assertEquals(5_000L, LyricsAutoCenter.IDLE_TIMEOUT_MS)
    }

    @Test
    fun `从未触碰的世代是 0 且不安排计时器`() {
        assertEquals(0, LyricsAutoCenter.GENERATION_NEVER_TOUCHED)
        assertFalse(LyricsAutoCenter.shouldSchedule(LyricsAutoCenter.GENERATION_NEVER_TOUCHED))
    }

    // ------------------------------------------------------------ 计时安排 ----

    @Test
    fun `任何一次交互之后都会安排计时器`() {
        assertTrue(LyricsAutoCenter.shouldSchedule(1))
        assertTrue(LyricsAutoCenter.shouldSchedule(2))
        assertTrue(LyricsAutoCenter.shouldSchedule(Int.MAX_VALUE))
    }

    // ------------------------------------------------------------ 回正判定 ----

    @Test
    fun `手指不在屏幕上且有歌词时执行回正`() {
        assertTrue(LyricsAutoCenter.shouldRecenter(pointerDown = false, lineCount = 10, enabled = true))
    }

    @Test
    fun `★ 手指还按着时不回正（不打断用户正在进行的拖拽）`() {
        assertFalse(LyricsAutoCenter.shouldRecenter(pointerDown = true, lineCount = 10, enabled = true))
    }

    @Test
    fun `没有歌词时不回正`() {
        assertFalse(LyricsAutoCenter.shouldRecenter(pointerDown = false, lineCount = 0, enabled = true))
    }

    @Test
    fun `面板不可交互时不回正 折叠态或不可见`() {
        assertFalse(LyricsAutoCenter.shouldRecenter(pointerDown = false, lineCount = 10, enabled = false))
    }

    @Test
    fun `拖拽中且面板不可交互 两个理由都不回正`() {
        assertFalse(LyricsAutoCenter.shouldRecenter(pointerDown = true, lineCount = 10, enabled = false))
    }

    // ------------------------------------------------------------ 世代号 ----

    @Test
    fun `世代号从 0 递增到 1`() {
        assertEquals(1, LyricsAutoCenter.nextGeneration(LyricsAutoCenter.GENERATION_NEVER_TOUCHED))
    }

    @Test
    fun `世代号连续递增`() {
        var g = LyricsAutoCenter.GENERATION_NEVER_TOUCHED
        repeat(50) { g = LyricsAutoCenter.nextGeneration(g) }
        assertEquals(50, g)
    }

    @Test
    fun `★ 世代号回绕到 1 而不是 0（回 0 会让计时器永远不再安排 功能静默失效）`() {
        val wrapped = LyricsAutoCenter.nextGeneration(Int.MAX_VALUE)
        assertEquals(1, wrapped)
        assertTrue(LyricsAutoCenter.shouldSchedule(wrapped))
    }

    @Test
    fun `负数世代号（不该出现）也回到 1`() {
        assertEquals(1, LyricsAutoCenter.nextGeneration(-7))
        assertTrue(LyricsAutoCenter.shouldSchedule(LyricsAutoCenter.nextGeneration(-7)))
    }

    @Test
    fun `回绕之后仍然可以继续递增`() {
        var g = Int.MAX_VALUE
        g = LyricsAutoCenter.nextGeneration(g)
        g = LyricsAutoCenter.nextGeneration(g)
        assertEquals(2, g)
    }

    // ---------------------------------------------- 与真实时序的对照 ----

    @Test
    fun `按下再抬起会产生两个新世代（计时从松手那一刻重新开始）`() {
        var g = LyricsAutoCenter.GENERATION_NEVER_TOUCHED
        g = LyricsAutoCenter.nextGeneration(g)   // Press
        val afterPress = g
        g = LyricsAutoCenter.nextGeneration(g)   // Release
        assertTrue(g > afterPress)
        // 按下时安排的那次计时会在到点时看到 pointerDown == true 而放弃；
        // 抬起后的新世代重新安排 —— 两次判定都通过 shouldRecenter 表达。
        assertFalse(LyricsAutoCenter.shouldRecenter(pointerDown = true, lineCount = 5, enabled = true))
        assertTrue(LyricsAutoCenter.shouldRecenter(pointerDown = false, lineCount = 5, enabled = true))
    }
}
