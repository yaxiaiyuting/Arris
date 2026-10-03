/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.9.0：三级回退链纯决策（[LyricSourceChain]）的单测。
 *
 * 这份用例锁死的是「有逐字就绝不用整行」这条产品语义，以及「关掉的源连顺序都不进」这条隐私语义。
 * 决策与解析被刻意分开（见 [LyricCandidate]），所以这里不需要网络、不需要 XML，也不需要真机。
 *
 * ⚠️ 调用契约：[LyricSourceChain.pick] 的入参拿不到 [LyricSourcePrefs]，它**只认列表顺序**。
 * 生产路径必须先按 [LyricSourceChain.order] 排列候选（本文件的 arrange 复刻了这一步），
 * 因此「关闭 TTML 后 TTML 永不被选」是靠 arrange 这一步的过滤保证的 —— 相关用例同时断言了
 * 「TTML 不在 order 里」与「过滤后 TTML 不进列表」，两件事一起才构成完整的不变量。
 */
class LyricSourceChainTest {

    private val yrc = LyricSourceKind.YRC
    private val ttml = LyricSourceKind.TTML
    private val lrc = LyricSourceKind.LRC

    private fun candidate(kind: LyricSourceKind, lineCount: Int, hasWordLevel: Boolean = false) =
        LyricCandidate(kind = kind, lineCount = lineCount, hasWordLevel = hasWordLevel)

    /** 复刻生产调用路径：先按 order 排定，再从候选池里取；池中没有的 kind 直接不进链。 */
    private fun arrange(prefs: LyricSourcePrefs, pool: List<LyricCandidate>): List<LyricCandidate> =
        LyricSourceChain.order(prefs).mapNotNull { kind -> pool.firstOrNull { it.kind == kind } }

    @Test
    fun `order——默认的ttml优先链是ttml、yrc、lrc`() {
        val expected = listOf(ttml, yrc, lrc)
        assertEquals(expected, LyricSourceChain.order(LyricSourcePrefs()))
        // 显式写全参数，防止将来默认值被改动时这条用例悄悄跟着变
        assertEquals(expected, LyricSourceChain.order(LyricSourcePrefs(ttmlEnabled = true, ttmlFirst = true)))
    }

    @Test
    fun `order——yrc优先链是yrc、ttml、lrc`() {
        assertEquals(
            listOf(yrc, ttml, lrc),
            LyricSourceChain.order(LyricSourcePrefs(ttmlEnabled = true, ttmlFirst = false)),
        )
    }

    @Test
    fun `order——关闭ttml时链上只有yrc与lrc`() {
        val chain = LyricSourceChain.order(LyricSourcePrefs(ttmlEnabled = false))
        assertEquals(listOf(yrc, lrc), chain)
        assertFalse("关掉的源不该出现在尝试顺序里（否则仍会发请求）", chain.contains(ttml))
    }

    @Test
    fun `pick——ttml只有整句时让位给有逐字的yrc`() {
        // TTML 行数更多、也解析成功了，但没有逐字 —— 不能因为「排前面」就赢
        val pool = listOf(
            candidate(ttml, lineCount = 120, hasWordLevel = false),
            candidate(yrc, lineCount = 40, hasWordLevel = true),
            candidate(lrc, lineCount = 40, hasWordLevel = false),
        )
        assertEquals(yrc, LyricSourceChain.pick(arrange(LyricSourcePrefs(), pool))?.kind)
    }

    @Test
    fun `pick——两边都有逐字时按ttmlFirst决定`() {
        val pool = listOf(
            candidate(ttml, lineCount = 40, hasWordLevel = true),
            candidate(yrc, lineCount = 40, hasWordLevel = true),
            candidate(lrc, lineCount = 40, hasWordLevel = false),
        )
        assertEquals(ttml, LyricSourceChain.pick(arrange(LyricSourcePrefs(ttmlFirst = true), pool))?.kind)
        assertEquals(yrc, LyricSourceChain.pick(arrange(LyricSourcePrefs(ttmlFirst = false), pool))?.kind)
    }

    @Test
    fun `pick——只有整行lrc可用时选lrc`() {
        val pool = listOf(candidate(lrc, lineCount = 40, hasWordLevel = false))
        assertEquals(lrc, LyricSourceChain.pick(arrange(LyricSourcePrefs(), pool))?.kind)
    }

    @Test
    fun `pick——空列表与全是零行都返回null`() {
        assertNull(LyricSourceChain.pick(emptyList()))
        assertNull(LyricSourceChain.pick(listOf<LyricCandidate>()))
        // 纯音乐 / 解析失败：解析成功但一行都没有，等价于不可用
        val allEmpty = listOf(
            candidate(ttml, lineCount = 0, hasWordLevel = true),
            candidate(yrc, lineCount = 0, hasWordLevel = true),
            candidate(lrc, lineCount = 0, hasWordLevel = false),
        )
        assertNull(LyricSourceChain.pick(arrange(LyricSourcePrefs(), allEmpty)))
    }

    @Test
    fun `pick——都没有逐字时按顺序取第一个有行的`() {
        val pool = listOf(
            candidate(ttml, lineCount = 30, hasWordLevel = false),
            candidate(yrc, lineCount = 30, hasWordLevel = false),
        )
        assertEquals(ttml, LyricSourceChain.pick(arrange(LyricSourcePrefs(ttmlFirst = true), pool))?.kind)
        assertEquals(yrc, LyricSourceChain.pick(arrange(LyricSourcePrefs(ttmlFirst = false), pool))?.kind)
    }

    @Test
    fun `pick——关闭ttml时池里的ttml候选被挡在链外`() {
        val prefs = LyricSourcePrefs(ttmlEnabled = false, ttmlFirst = true)
        // 故意给 TTML 最优的候选（行数最多 + 有逐字），确认它仍然进不了链
        val pool = listOf(
            candidate(ttml, lineCount = 999, hasWordLevel = true),
            candidate(yrc, lineCount = 12, hasWordLevel = false),
            candidate(lrc, lineCount = 12, hasWordLevel = false),
        )
        val arranged = arrange(prefs, pool)
        assertEquals(listOf(yrc, lrc), arranged.map { it.kind })
        assertEquals(yrc, LyricSourceChain.pick(arranged)?.kind)
    }

    @Test
    fun `pick——只认传入列表的顺序而不认kind`() {
        // 这是在给契约上保险：pick 没有 prefs，顺序错了它不会纠正，所以顺序必须由 order 决定
        val reversed = listOf(
            candidate(lrc, lineCount = 5, hasWordLevel = false),
            candidate(yrc, lineCount = 5, hasWordLevel = false),
        )
        assertEquals(lrc, LyricSourceChain.pick(reversed)?.kind)
        // 同一份候选，只要先过一遍 order 排列，结果就该翻回来 —— 这正是调用契约的重要性
        val arranged = arrange(LyricSourcePrefs(), reversed)
        assertEquals(listOf(yrc, lrc), arranged.map { it.kind })
        assertEquals(yrc, LyricSourceChain.pick(arranged)?.kind)
    }
}
