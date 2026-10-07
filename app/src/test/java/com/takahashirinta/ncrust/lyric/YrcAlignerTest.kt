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
 * v1.6.0 · D4：yrc ↔ LRC 行对齐（[YrcAligner]）的纯逻辑单测。
 *
 * 这些用例覆盖的是「行集合不相等」的真实形态 —— v1.5.x 在这里整首放弃逐字，
 * 2026-09 实测 100 首里有 6 首因此一行逐字都拿不到。
 */
class YrcAlignerTest {

    @Test
    fun `归一化——删掉所有空白，含全角空格`() {
        assertEquals("听见冬天的离开", YrcAligner.normalize("听见 冬天的离开"))
        assertEquals("听见冬天的离开", YrcAligner.normalize("听见\u3000冬天的离开"))
        assertEquals("ab", YrcAligner.normalize("\t a\n b \r"))
        assertEquals("", YrcAligner.normalize("   "))
    }

    @Test
    fun `归一化——没有空白时原样返回（不新建字符串）`() {
        val s = "半夜睡不着觉"
        assertTrue(s === YrcAligner.normalize(s))
    }

    @Test
    fun `行序对齐——i 配 i`() {
        assertEquals(
            listOf(YrcAligner.LinePair(0, 0), YrcAligner.LinePair(1, 1), YrcAligner.LinePair(2, 2)),
            YrcAligner.indexPairs(3)
        )
    }

    @Test
    fun `LCS——行集合相等时一一配对`() {
        val a = listOf("甲", "乙", "丙")
        assertEquals(listOf(YrcAligner.LinePair(0, 0), YrcAligner.LinePair(1, 1), YrcAligner.LinePair(2, 2)),
            YrcAligner.lcsPairs(a, a))
    }

    @Test
    fun `LCS——yrc 多一行时跳过它，其余仍然一一对应`() {
        val lrc = listOf("甲", "乙", "丙")
        val yrc = listOf("甲", "插", "乙", "丙")
        assertEquals(
            listOf(YrcAligner.LinePair(0, 0), YrcAligner.LinePair(1, 2), YrcAligner.LinePair(2, 3)),
            YrcAligner.lcsPairs(lrc, yrc)
        )
    }

    @Test
    fun `LCS——lrc 多一行（作词元信息）时跳过它`() {
        val lrc = listOf("作词 : 某人", "甲", "乙")
        val yrc = listOf("甲", "乙")
        assertEquals(
            listOf(YrcAligner.LinePair(1, 0), YrcAligner.LinePair(2, 1)),
            YrcAligner.lcsPairs(lrc, yrc)
        )
    }

    @Test
    fun `LCS——空格差异不影响配对`() {
        val lrc = listOf("听见 冬天的离开", "半夜 睡不着觉")
        val yrc = listOf("听见冬天的离开", "半夜睡不着觉")
        assertEquals(
            listOf(YrcAligner.LinePair(0, 0), YrcAligner.LinePair(1, 1)),
            YrcAligner.lcsPairs(lrc, yrc)
        )
    }

    @Test
    fun `LCS——重复句（副歌）保序配对，不会交叉`() {
        val lrc = listOf("副歌", "主歌", "副歌", "尾奏")
        val yrc = listOf("副歌", "主歌", "副歌", "尾奏")
        val pairs = YrcAligner.lcsPairs(lrc, yrc)
        assertEquals(4, pairs.size)
        // 保序：lrc 与 yrc 下标都严格递增
        assertTrue(pairs.zipWithNext().all { (p, q) -> p.lrcIndex < q.lrcIndex && p.yrcIndex < q.yrcIndex })
    }

    @Test
    fun `LCS——完全不同的两份文本配不出任何对`() {
        assertTrue(YrcAligner.lcsPairs(listOf("甲", "乙"), listOf("丙", "丁")).isEmpty())
        assertTrue(YrcAligner.lcsPairs(emptyList(), listOf("甲")).isEmpty())
        assertTrue(YrcAligner.lcsPairs(listOf("甲"), emptyList()).isEmpty())
    }

    @Test
    fun `LCS——结果永远保序且下标合法（对任意输入）`() {
        val lrc = listOf("a", "b", "a", "c", "b")
        val yrc = listOf("b", "a", "c", "b", "a")
        val pairs = YrcAligner.lcsPairs(lrc, yrc)
        assertTrue(pairs.isNotEmpty())
        assertTrue(pairs.all { it.lrcIndex in lrc.indices && it.yrcIndex in yrc.indices })
        assertTrue(pairs.zipWithNext().all { (p, q) -> p.lrcIndex < q.lrcIndex && p.yrcIndex < q.yrcIndex })
        assertTrue(pairs.all { lrc[it.lrcIndex] == yrc[it.yrcIndex] })
    }

    @Test
    fun `漂移过滤——只保留时间接近的配对`() {
        val pairs = listOf(YrcAligner.LinePair(0, 0), YrcAligner.LinePair(1, 1), YrcAligner.LinePair(2, 2))
        val lrcTimes = listOf(0L, 1_000L, 2_000L)
        val yrcStarts = listOf(100L, 5_000L, 2_400L)
        assertEquals(
            listOf(YrcAligner.LinePair(0, 0), YrcAligner.LinePair(2, 2)),
            YrcAligner.filterByDrift(pairs, lrcTimes, yrcStarts, maxDriftMs = 500L)
        )
    }

    @Test
    fun `漂移过滤——下标越界时丢弃该对，不抛异常`() {
        val pairs = listOf(YrcAligner.LinePair(9, 0), YrcAligner.LinePair(0, 9))
        assertEquals(emptyList<YrcAligner.LinePair>(),
            YrcAligner.filterByDrift(pairs, listOf(0L), listOf(0L), maxDriftMs = 1_000L))
    }
}
