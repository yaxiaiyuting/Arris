/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.0 · T4：动态字号纯逻辑的契约。
 *
 * 关键不变量（比"某个具体数字"重要，改阈值时先看这里）：
 *  1. **无振荡**：放大档只在"放大后仍占 1 行"时才成立（fill ≤ 0.82、×1.15 后 ≤ 1）；
 *  2. **稳定**：倍率只是文本与版面的函数，与"谁是当前行"无关（同一输入永远同一输出）；
 *  3. **退化安全**：空白/宽度未知/字号未知一律回基准 1.0，绝不 NaN、绝不除零；
 *  4. **A-/A+ 不被替换**：倍率是乘在用户基准字号上的浮动，且被绝对上限夹住。
 */
class DynamicLyricFontTest {

    /** PCL110 竖屏播放器：约 340dp 文本宽 ≈ 900px；基准 32sp ≈ 112px。 */
    private val widthPx = 900f
    private val fontPx = 112f

    @Test
    fun `short line grows, long line shrinks, medium stays`() {
        assertEquals(
            DynamicLyricFont.SCALE_LARGE,
            DynamicLyricFont.scaleFor("晚安", widthPx, fontPx)
        )
        // 12 个汉字 ≈ 12em，单行容量 900/112 ≈ 8em ⇒ 2 行 ⇒ 基准
        assertEquals(
            DynamicLyricFont.SCALE_BASE,
            DynamicLyricFont.scaleFor("一二三四五六七八九十一二", widthPx, fontPx)
        )
        // 40 个汉字 ≈ 40em ⇒ 5 行 ⇒ 缩小
        assertEquals(
            DynamicLyricFont.SCALE_SMALL,
            DynamicLyricFont.scaleFor("字".repeat(40), widthPx, fontPx)
        )
    }

    @Test
    fun `growing never turns a single line into two`() {
        // 扫描所有"估算 1 行"的填充率，放大后的填充率必须仍然 ≤ 1（= 仍是 1 行）
        for (chars in 1..8) {
            val text = "字".repeat(chars)
            val fill = DynamicLyricFont.estimateWidthEm(text) / (widthPx / fontPx)
            if (fill <= DynamicLyricFont.SINGLE_LINE_FILL_LIMIT) {
                val scale = DynamicLyricFont.scaleFor(text, widthPx, fontPx)
                assertEquals(DynamicLyricFont.SCALE_LARGE, scale)
                assertTrue("fill=$fill 放大后越界", fill * scale <= 1.0001f)
                assertEquals(
                    "放大后仍应是 1 行（chars=$chars）",
                    1,
                    DynamicLyricFont.estimatedLines(text, widthPx, fontPx * scale)
                )
            }
        }
    }

    @Test
    fun `latin text is estimated wider than its character count suggests`() {
        // 26 个 ASCII 字母 ≈ 0.55~0.66em/字 ⇒ 明显大于 13em（"半角 = 0.5em"的朴素估算）
        val em = DynamicLyricFont.estimateWidthEm("abcdefghijklmnopqrstuvwxyz")
        assertTrue("em=$em", em in 13f..20f)
        // 大写更宽
        assertTrue(DynamicLyricFont.estimateWidthEm("ABC") > DynamicLyricFont.estimateWidthEm("abc"))
        // 空格按 0.3em 而不是 1em（否则英文长句会被系统性判成多行）
        assertTrue(DynamicLyricFont.estimateWidthEm("a b") < DynamicLyricFont.estimateWidthEm("a中b"))
    }

    @Test
    fun `cjk and full width punctuation count as one em each`() {
        assertEquals(4f, DynamicLyricFont.estimateWidthEm("一二三四"), 0.001f)
        assertEquals(1f, DynamicLyricFont.estimateWidthEm("　"), 0.001f)   // 全角空格
        assertEquals(1f, DynamicLyricFont.estimateWidthEm("，"), 0.001f)
        // 谚文音节 / 假名同样算全角
        assertEquals(1f, DynamicLyricFont.estimateWidthEm("あ"), 0.001f)
        assertEquals(1f, DynamicLyricFont.estimateWidthEm("한"), 0.001f)
    }

    @Test
    fun `surrogate pairs count once`() {
        // U+20000（CJK 扩展 B）是代理对，必须算 1 个全角字而不是 2 个
        val em = DynamicLyricFont.estimateWidthEm("\uD840\uDC00")
        assertEquals(1f, em, 0.001f)
    }

    @Test
    fun `unknown layout degrades to base scale`() {
        assertEquals(DynamicLyricFont.SCALE_BASE, DynamicLyricFont.scaleFor("短", 0f, fontPx))
        assertEquals(DynamicLyricFont.SCALE_BASE, DynamicLyricFont.scaleFor("短", widthPx, 0f))
        assertEquals(DynamicLyricFont.SCALE_BASE, DynamicLyricFont.scaleFor("   ", widthPx, fontPx))
        assertEquals(DynamicLyricFont.SCALE_BASE, DynamicLyricFont.scaleFor("", widthPx, fontPx))
        assertEquals(1, DynamicLyricFont.estimatedLines("短", -1f, fontPx))
    }

    @Test
    fun `scale is a pure function of text and layout`() {
        val a = DynamicLyricFont.scaleFor("晚安", widthPx, fontPx)
        repeat(5) { assertEquals(a, DynamicLyricFont.scaleFor("晚安", widthPx, fontPx)) }
        // 窄版面下同一句话该缩小 —— 版面是唯一让它变化的输入
        assertEquals(DynamicLyricFont.SCALE_SMALL, DynamicLyricFont.scaleFor("一二三四五六七八九十一二", 300f, fontPx))
    }

    @Test
    fun `clamp keeps the original font under the absolute cap`() {
        // A+ 的 1.5 档 ⇒ 原文 48sp，放大档会被压回 1.0（用户已经手动放大过了）
        assertEquals(1.0f, DynamicLyricFont.clampScale(DynamicLyricFont.SCALE_LARGE, 48f), 0.0001f)
        // 默认 32sp：1.15 倍 = 36.8sp，远低于上限，原样保留
        assertEquals(DynamicLyricFont.SCALE_LARGE, DynamicLyricFont.clampScale(DynamicLyricFont.SCALE_LARGE, 32f), 0.0001f)
        // 40sp 基准：1.15 倍 = 46sp < 48sp，保留
        assertEquals(DynamicLyricFont.SCALE_LARGE, DynamicLyricFont.clampScale(DynamicLyricFont.SCALE_LARGE, 40f), 0.0001f)
        // 缩小档永远不被上限影响
        assertEquals(DynamicLyricFont.SCALE_SMALL, DynamicLyricFont.clampScale(DynamicLyricFont.SCALE_SMALL, 48f), 0.0001f)
        // 基准字号非法时不炸
        assertEquals(DynamicLyricFont.SCALE_LARGE, DynamicLyricFont.clampScale(DynamicLyricFont.SCALE_LARGE, 0f), 0.0001f)
    }

    @Test
    fun `thresholds stay inside sane bounds`() {
        assertTrue(DynamicLyricFont.SCALE_SMALL < DynamicLyricFont.SCALE_BASE)
        assertTrue(DynamicLyricFont.SCALE_LARGE > DynamicLyricFont.SCALE_BASE)
        assertTrue(DynamicLyricFont.SINGLE_LINE_FILL_LIMIT * DynamicLyricFont.SCALE_LARGE <= 1f)
        assertTrue(DynamicLyricFont.ABSOLUTE_MAX_ORIGINAL_SP in 40f..56f)
    }
}
