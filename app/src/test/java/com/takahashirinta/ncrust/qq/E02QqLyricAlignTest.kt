/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 2026-10-27 审查 E02：QQ 译文/音译对齐**重复选中前一行**、甚至放弃完全匹配。
 */

package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.lyric.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `QqApi.alignToMainLines` 的对齐判据（E02）。纯逻辑，无网络、无 Android。
 *
 * ## 旧实现的两处错（都不会崩，只会让译文错位）
 *
 * 1. **命中之后游标不推进** ⇒ 同一条副文本被后面每一行反复选中；
 * 2. **窗口里只看得见游标那一条** ⇒ 「优先完全匹配」做不到：一条时间戳完全相同的
 *    候选会被前面那条"在容差内但不是最近"的候选挤掉。
 *
 * 报告里的两个真实场景都在这里复现（夹具是自造的，不含任何真实歌词）。
 */
class E02QqLyricAlignTest {

    private fun lines(vararg pairs: Pair<Long, String>): List<LrcLine> =
        pairs.map { (t, text) -> LrcLine(timeMs = t, text = text) }

    /**
     * 主、副两轨都是 `0/1000/2000/3000ms`。
     *
     * 旧实现产出 `0/0/1/2`（每条都往前借一行）；正确结果必须是逐行一一对应。
     */
    @Test
    fun `时间戳逐行相同时 副文本不许重复选中前一行`() {
        val main = lines(0L to "m0", 1000L to "m1", 2000L to "m2", 3000L to "m3")
        val sub = lines(0L to "s0", 1000L to "s1", 2000L to "s2", 3000L to "s3")
        val out = QqApi.alignToMainLines(main, sub)
        assertEquals(listOf("s0", "s1", "s2", "s3"), out.map { it.text })
        // 时间戳被改写成主轨那一行（渲染层按 timeMs 精确配对）。
        assertEquals(listOf(0L, 1000L, 2000L, 3000L), out.map { it.timeMs })
        // 每条副文本只用一次。
        assertEquals(out.map { it.text }.size, out.map { it.text }.toSet().size)
    }

    /**
     * 主轨只有一条 2000ms；副轨有 1000ms 与 2000ms。
     *
     * 旧实现会选中 1000ms 那条（差值 1000 ≤ 容差），而时间戳**完全相同**的 2000ms
     * 永远轮不到 —— 这就是报告里「甚至放弃完全匹配」。
     */
    @Test
    fun `窗口内优先完全匹配 而不是游标上那条更远的时间戳`() {
        val main = lines(2000L to "m")
        val sub = lines(1000L to "远的", 2000L to "精确的")
        val out = QqApi.alignToMainLines(main, sub)
        assertEquals(1, out.size)
        assertEquals("精确的", out.single().text)
        assertEquals(2000L, out.single().timeMs)
    }

    /** 容差之外（差值 > toleranceMs）整行丢弃：宁可少一行译文，也不要贴错。 */
    @Test
    fun `容差之外整行丢弃`() {
        val main = lines(0L to "m0", 10_000L to "m1")
        val sub = lines(0L to "s0")
        val out = QqApi.alignToMainLines(main, sub, toleranceMs = 1500L)
        assertEquals(listOf("s0"), out.map { it.text })
    }

    /** 同差时保留**先出现的**（稳定、可复现），而不是随机挑一条。 */
    @Test
    fun `同差保留先出现的候选`() {
        val main = lines(1000L to "m")
        val sub = lines(500L to "先", 1500L to "后")
        val out = QqApi.alignToMainLines(main, sub)
        assertEquals("先", out.single().text)
    }

    /** 缺行（副轨比主轨少）时，多出来的主轨行没有译文 —— 不许借用别人的。 */
    @Test
    fun `副轨缺行时宁可没有译文 也不许借用相邻行`() {
        val main = lines(0L to "m0", 1000L to "m1", 2000L to "m2")
        val sub = lines(0L to "s0", 2000L to "s2")
        val out = QqApi.alignToMainLines(main, sub)
        assertEquals(listOf("s0", "s2"), out.map { it.text })
        assertEquals(listOf(0L, 2000L), out.map { it.timeMs })
    }

    /** 空输入不产出（也不崩）。 */
    @Test
    fun `空轨返回空表`() {
        assertTrue(QqApi.alignToMainLines(emptyList(), lines(0L to "s")).isEmpty())
        assertTrue(QqApi.alignToMainLines(lines(0L to "m"), emptyList()).isEmpty())
    }

    /**
     * 时间漂移（副轨整体晚 300ms，实测的常见形态）：每行仍然配到**自己那一句**，
     * 且时间戳被改写成主轨的。
     */
    @Test
    fun `整体时间漂移时仍然逐行配对自己那一句`() {
        val main = lines(0L to "m0", 2000L to "m1", 4000L to "m2")
        val sub = lines(300L to "s0", 2300L to "s1", 4300L to "s2")
        val out = QqApi.alignToMainLines(main, sub)
        assertEquals(listOf("s0", "s1", "s2"), out.map { it.text })
        assertEquals(listOf(0L, 2000L, 4000L), out.map { it.timeMs })
    }
}
