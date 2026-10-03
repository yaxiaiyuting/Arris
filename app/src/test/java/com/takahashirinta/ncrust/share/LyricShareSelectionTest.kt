/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LyricShareSelection] 的单测（v3.3.0 · 用户需求第 9 条）。
 *
 * 这一组用例要钉死的是「选哪几句」这件事的**边界**，因为它在模拟器上最难验：
 * 要复现「歌首点分享」得刚好在歌曲前几秒按下去，要复现「整首超长」得找到一首 20 行以上的歌。
 * 全部位置与行数在这里是字面量，跑一次 JVM 测试就覆盖完。
 */
class LyricShareSelectionTest {

    /** 造一份「第 i 行时间戳 = i * 1000ms」的歌词，正文用 L1…Ln。 */
    private fun lines(n: Int): List<LyricShareLine> =
        (1..n).map { LyricShareLine(timeMs = (it - 1) * 1000L, text = "L$it") }

    // ------------------------------------------------------------ indexAt

    @Test
    fun `前奏期_位置早于第一行_返回 -1 与面板不高亮任何一行一致`() {
        // lines() 的第一行在 0ms，所以这里另造一份「第一句在 5 秒」的歌词 ——
        // 那才是真实的前奏形态（LRC 的第一行极少落在 0:00）。
        val late = listOf(
            LyricShareLine(timeMs = 5_000, text = "A"),
            LyricShareLine(timeMs = 6_000, text = "B"),
        )
        assertEquals(-1, LyricShareSelection.indexAt(0, late))
        assertEquals(-1, LyricShareSelection.indexAt(4_999, late))
        // 正好踩在第一行上就是第一行（不是 -1、也不是第二行）
        assertEquals(0, LyricShareSelection.indexAt(5_000, late))
        // 第一行在 0ms 的那种歌词：位置 0 就是第一行。
        assertEquals(0, LyricShareSelection.indexAt(0, lines(5)))
    }

    @Test
    fun `位置恰好落在行时间戳_返回该行_落在两行之间返回前一行`() {
        val l = lines(5)
        assertEquals(0, LyricShareSelection.indexAt(500, l))
        assertEquals(1, LyricShareSelection.indexAt(1000, l))
        assertEquals(1, LyricShareSelection.indexAt(1999, l))
        assertEquals(2, LyricShareSelection.indexAt(2000, l))
    }

    @Test
    fun `唱完之后_位置晚于最后一行_停在末句`() {
        val l = lines(5)
        assertEquals(4, LyricShareSelection.indexAt(4000, l))
        assertEquals(4, LyricShareSelection.indexAt(999_999, l))
    }

    @Test
    fun `空歌词表_索引恒为 -1_不抛异常`() {
        assertEquals(-1, LyricShareSelection.indexAt(1234, emptyList()))
    }

    // ------------------------------------------------------------ windowIndices

    @Test
    fun `歌首_窗口整体后移而不是缩短_行数恒定`() {
        // center=0、半径 3 ⇒ 期望 0..6（7 行），而不是只有 0..3。
        assertEquals(0..6, LyricShareSelection.windowIndices(total = 10, center = 0, radius = 3))
    }

    @Test
    fun `歌尾_窗口整体前移而不是缩短_行数恒定`() {
        assertEquals(3..9, LyricShareSelection.windowIndices(total = 10, center = 9, radius = 3))
    }

    @Test
    fun `中间_窗口以当前行为正中`() {
        assertEquals(2..8, LyricShareSelection.windowIndices(total = 20, center = 5, radius = 3))
    }

    @Test
    fun `全文不足一个窗口_返回全部行`() {
        assertEquals(0..3, LyricShareSelection.windowIndices(total = 4, center = 0, radius = 3))
        assertEquals(0..3, LyricShareSelection.windowIndices(total = 4, center = 3, radius = 3))
    }

    @Test
    fun `空表_窗口是空区间`() {
        assertTrue(LyricShareSelection.windowIndices(total = 0, center = 0, radius = 3).isEmpty())
        assertTrue(LyricShareSelection.windowIndices(total = 0, center = 5, radius = 3).isEmpty())
    }

    @Test
    fun `越界的 center 被夹回表内_不产生非法下标`() {
        assertEquals(0..6, LyricShareSelection.windowIndices(total = 10, center = -5, radius = 3))
        assertEquals(3..9, LyricShareSelection.windowIndices(total = 10, center = 99, radius = 3))
    }

    // ------------------------------------------------------------ linesForText

    @Test
    fun `文本_当前唱段_取前后各三行`() {
        val l = lines(20)
        val got = LyricShareSelection.linesForText(l, positionMs = 5500, scope = LyricShareScope.CURRENT_SECTION)
        assertEquals(listOf("L3", "L4", "L5", "L6", "L7", "L8", "L9"), got.map { it.text })
    }

    @Test
    fun `文本_整首_一律不截断`() {
        val l = lines(137)
        val got = LyricShareSelection.linesForText(l, positionMs = 0, scope = LyricShareScope.WHOLE_SONG)
        assertEquals(137, got.size)
        assertEquals("L1", got.first().text)
        assertEquals("L137", got.last().text)
    }

    @Test
    fun `文本_空歌词_返回空表`() {
        assertTrue(
            LyricShareSelection.linesForText(emptyList(), 0, LyricShareScope.CURRENT_SECTION).isEmpty()
        )
        assertTrue(
            LyricShareSelection.linesForText(emptyList(), 0, LyricShareScope.WHOLE_SONG).isEmpty()
        )
    }

    @Test
    fun `文本_前奏期_从第一行开始给足上下文`() {
        val l = lines(20)
        val got = LyricShareSelection.linesForText(l, positionMs = 0, scope = LyricShareScope.CURRENT_SECTION)
        assertEquals(7, got.size)
        assertEquals("L1", got.first().text)
    }

    // ------------------------------------------------------------ selectionForPoster

    @Test
    fun `出图_整首不超上限_全给且当前行下标正确`() {
        val l = lines(12)
        val sel = LyricShareSelection.selectionForPoster(l, positionMs = 4000, scope = LyricShareScope.WHOLE_SONG)
        assertEquals(12, sel.lines.size)
        assertEquals(4, sel.currentIndex)
        assertEquals(4, sel.centerIndex)
    }

    @Test
    fun `出图_整首超上限_退化成以当前行为中心的上限行数`() {
        val l = lines(60)
        val sel = LyricShareSelection.selectionForPoster(
            l,
            positionMs = 30_000,
            scope = LyricShareScope.WHOLE_SONG,
            maxLines = 20,
        )
        assertEquals(20, sel.lines.size)
        // 原表下标 30 的正文是 L31（lines() 里第 i 行叫 L(i+1)），窗口是 21..40 ⇒ 相对下标 9。
        assertEquals(30, sel.centerIndex)
        assertEquals(9, sel.currentIndex)
        assertEquals("L22", sel.lines.first().text)
        assertEquals("L31", sel.lines[sel.currentIndex].text)
        assertEquals("L41", sel.lines.last().text)
    }

    @Test
    fun `出图_当前唱段_窗口外的当前行下标是 -1_不越界`() {
        // 人为构造「center 不在窗口里」是不可能的（窗口永远含 center），
        // 所以这里验证的是**反过来的性质**：只要窗口非空，下标一定落在 0..size-1。
        val l = lines(9)
        val sel = LyricShareSelection.selectionForPoster(l, positionMs = 8000, scope = LyricShareScope.CURRENT_SECTION)
        assertTrue(sel.currentIndex in sel.lines.indices)
        assertEquals("L9", sel.lines[sel.currentIndex].text)
    }

    @Test
    fun `出图_空歌词_返回空选段且下标为 -1`() {
        val sel = LyricShareSelection.selectionForPoster(emptyList(), 0, LyricShareScope.CURRENT_SECTION)
        assertTrue(sel.lines.isEmpty())
        assertEquals(-1, sel.currentIndex)
        assertEquals(-1, sel.centerIndex)
    }

    @Test
    fun `出图_只有译文没有原文的行_原文为空时仍然占位由格式化层决定`() {
        // 这一条钉的是**分层**：选段不负责判断「这行值不值得画」，
        // 它只按位置切窗口；过滤空行是 LyricShareText / 渲染层的事。
        val l = listOf(
            LyricShareLine(0, "原文一", translation = "译文一"),
            LyricShareLine(1000, "", translation = "只有译文"),
            LyricShareLine(2000, "原文三"),
        )
        val sel = LyricShareSelection.selectionForPoster(l, 900, LyricShareScope.CURRENT_SECTION)
        assertEquals(3, sel.lines.size)
        assertEquals("只有译文", sel.lines[1].translation)
    }
}
