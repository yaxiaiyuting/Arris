/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5.0 · B：逐字歌词（yrc）解析与对齐的单测。
 *
 * 所有 yrc / lrc 样本都是 2026-09 从真实响应里逐字抄下来的（不是编造的），
 * 覆盖两个真实坑：
 *  1. yrc 的行时间戳与 lrc **不相等**（《屋顶》相差 20–310 ms）→ 只能按行序对齐；
 *  2. yrc 拼出来的行文本比 lrc 少空格（`听见冬天的离开` vs `听见 冬天的离开`）→
 *     词的字符区间必须重新映射到 lrc 文本上。
 */
class YrcParserTest {

    /** 《屋顶》真实样本：yrc 行起始 24080，lrc 行起始 23970，差 110 ms。 */
    private val wudingYrc = "[24080,5080](24080,280,0)半(24360,210,0)夜(24570,560,0)睡(25130,300,0)不(25430,350,0)着(25780,950,0)觉(26730,280,0)把(27010,410,0)心(27420,410,0)情(27830,290,0)哼(28120,370,0)成(28490,670,0)歌"

    /** 《遇见》真实样本：yrc 无空格，lrc 有空格，且时间戳差 28 ms。 */
    private val yujianYrc = "[24870,4290](24870,390,0)听(25260,390,0)见(25650,900,0)冬(26550,390,0)天(26940,390,0)的(27330,390,0)离(27720,600,0)开"

    @Test
    fun `解析——词起始是绝对毫秒，行文本是词的拼接`() {
        val parsed = YrcParser.parse(wudingYrc)
        assertEquals(1, parsed.size)
        val line = parsed[0]
        assertEquals(24080L, line.startMs)
        assertEquals(5080L, line.durationMs)
        assertEquals("半夜睡不着觉把心情哼成歌", line.text)
        // 12 个汉字 = 12 个词，逐字轨是真的逐字（不是逐词分组）
        assertEquals(12, line.words.size)
        // 首个词与行首同刻，末词 28490+670 = 29160 = 24080+5080，首尾都对得上 ——
        // 这就是「词起始是绝对毫秒而不是相对行首」的判据。
        assertEquals(24080L, line.words.first().startMs)
        assertEquals(29160L, line.words.last().startMs + line.words.last().durationMs)
        assertEquals(24080L + 5080L, line.startMs + line.durationMs)
        // 单调递增
        assertTrue(line.words.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
    }

    @Test
    fun `解析——逗号分隔符后面带空格的信息行要去掉首尾空白`() {
        val parsed = YrcParser.parse("[0,1000](0,1000,0) 作曲 : 周杰伦")
        assertEquals(1, parsed.size)
        assertEquals("作曲 : 周杰伦", parsed[0].text)
    }

    @Test
    fun `解析——零时长的信息行不丢`() {
        val parsed = YrcParser.parse("[0,0](0,0,0) 作词 : 五月天 阿信")
        assertEquals(1, parsed.size)
        assertEquals(0L, parsed[0].durationMs)
        assertEquals("作词 : 五月天 阿信", parsed[0].text)
    }

    @Test
    fun `对齐——行数不等时不再整首放弃（v1_6_0）：文本相同的行照挂，其余行原样`() {
        // v1.5.x 在这里整首放弃（行数 1 vs 2）。实测这种「yrc 多/少几行」的歌占多数，
        // 是覆盖率真正的瓶颈 —— 现在按文本 LCS 匹配：两行都是同一句，lrc 的那一行挂上，
        // 多出来的那一行 yrc 数据被忽略。
        val lrc = listOf(LrcLine(23970, "半夜睡不着觉把心情哼成歌"))
        val twoLines = wudingYrc + "\n" + wudingYrc
        val merged = YrcParser.attachWords(lrc, twoLines)
        assertEquals(1, merged.size)
        assertEquals(12, merged[0].words.size)
        assertEquals(23970L, merged[0].timeMs)
        assertEquals("半夜睡不着觉把心情哼成歌", merged[0].text)
    }

    @Test
    fun `对齐——yrc 少一行（间奏没有逐字轨）时其余行仍然挂上`() {
        // 真实形态：yrc 只覆盖唱词，lrc 多一行纯音乐标记。
        val lrc = listOf(
            LrcLine(1000, "第一句"),
            LrcLine(5000, "中间是间奏"),
            LrcLine(9000, "第二句"),
        )
        val yrc = "[1000,1000](1000,500,0)第(1500,500,0)一(2000,500,0)句\n" +
            "[9000,1000](9000,500,0)第(9500,500,0)二(10000,500,0)句"
        val merged = YrcParser.attachWords(lrc, yrc)
        assertEquals(3, merged.size)
        assertEquals(3, merged[0].words.size)
        assertTrue(merged[1].words.isEmpty())
        assertNull(merged[1].endMs)
        assertEquals(3, merged[2].words.size)
        // 行文本与时间戳不变
        assertEquals(listOf(1000L, 5000L, 9000L), merged.map { it.timeMs })
        assertEquals(listOf("第一句", "中间是间奏", "第二句"), merged.map { it.text })
    }

    @Test
    fun `对齐——yrc 多几行（重复段）时按文本重新配对，不按行号硬套`() {
        val lrc = listOf(LrcLine(1000, "甲"), LrcLine(2000, "乙"), LrcLine(3000, "丙"))
        val yrc = "[1000,500](1000,500,0)甲\n" +
            "[1500,500](1500,500,0)插\n" +          // yrc 多出来的插句
            "[2000,500](2000,500,0)乙\n" +
            "[3000,500](3000,500,0)丙"
        val merged = YrcParser.attachWords(lrc, yrc)
        assertEquals(listOf("甲", "乙", "丙"), merged.map { it.text })
        assertEquals(3, merged.count { it.words.isNotEmpty() })
        // 乙 的逐词时刻必须来自 yrc 的第 3 行（2000ms），不能错位到插句（1500ms）
        assertEquals(2000L, merged[1].words.first().startMs)
    }

    @Test
    fun `对齐——LCS 更差时回落到行序对齐（保证 v1_5_x 不回归）`() {
        // 真实形态（《You Never Can Tell》）：两份文本归一化后不相等（lrc 带后缀），
        // 但词仍能逐个定位 —— LCS 配不上，行序对齐能挂上，此时必须选行序对齐。
        val lrc = listOf(LrcLine(24870, "听见 冬天的离开（Live）"))
        val merged = YrcParser.attachWords(lrc, yujianYrc)
        assertEquals(1, merged.size)
        assertEquals(7, merged[0].words.size)
        assertTrue(merged[0].words.all { it.charEndExclusive <= "听见 冬天的离开（Live）".length })
    }

    @Test
    fun `对齐——两条路都没有可用配对时原样返回`() {
        val lrc = listOf(LrcLine(0, "完全不同的歌词"))
        assertSame(lrc, YrcParser.attachWords(lrc, wudingYrc))
    }

    @Test
    fun `对齐——时间戳相差 110ms 也按行序挂上，且不改行文本与时间戳`() {
        val lrc = listOf(LrcLine(23970, "半夜睡不着觉把心情哼成歌"))
        val merged = YrcParser.attachWords(lrc, wudingYrc)
        assertEquals(1, merged.size)
        assertEquals(23970L, merged[0].timeMs)
        assertEquals("半夜睡不着觉把心情哼成歌", merged[0].text)
        assertEquals(12, merged[0].words.size)
        assertEquals(24080L, merged[0].words.first().startMs)
        // endMs 来自 yrc 的行时长，仍然是绝对毫秒
        assertEquals(29160L, merged[0].endMs)
    }

    @Test
    fun `对齐——yrc 少空格时字符区间要映射到 lrc 文本上`() {
        val lrc = listOf(LrcLine(24898, "听见 冬天的离开"))
        val merged = YrcParser.attachWords(lrc, yujianYrc)
        assertEquals(1, merged.size)
        val text = "听见 冬天的离开"
        val w = merged[0].words
        assertEquals(7, w.size)
        // 关键：区间必须落在 lrc 的文本上（含那个空格），而不是 yrc 的 7 字拼接串上 ——
        // 逐个词回切 lrc 文本，取出来的必须正好是该词本身。
        assertEquals(listOf("听", "见", "冬", "天", "的", "离", "开"),
            w.map { text.substring(it.charStart, it.charEndExclusive) })
        assertEquals(0, w[0].charStart)
        assertEquals(8, w[6].charEndExclusive)
        // 区间必须单调不重叠
        assertTrue(w.zipWithNext().all { (a, b) -> a.charEndExclusive <= b.charStart })
    }

    @Test
    fun `对齐——yrc 词文本带首尾空格时用 trim 后的词定位（S6 真机踩到的坑）`() {
        // 真实样本：yrc 把空格粘在前一段尾部，LRC 那份是 trim 过的。
        // 修复前《修炼爱情》70 行只挂上 47 行，就是栽在这种行上。
        val yrc = "[0,1000](0,300,0) 作词 (300,300,0): (600,400,0)易家扬"
        val lrc = listOf(LrcLine(0, "作词 : 易家扬"))
        val merged = YrcParser.attachWords(lrc, yrc)
        val w = merged[0].words
        assertEquals(3, w.size)
        assertEquals(listOf("作词", ":", "易家扬"), w.map { it.text })
        assertEquals(listOf("作词", ":", "易家扬"),
            w.map { "作词 : 易家扬".substring(it.charStart, it.charEndExclusive) })
        assertEquals(0, w[0].charStart)
        assertEquals(8, w[2].charEndExclusive) // 作词 : 易家扬 共 8 字
    }

    @Test
    fun `对齐——纯空白的词段直接跳过，不影响其余词的区间`() {
        val yrc = "[0,1000](0,300,0)独 (300,300,0)不(600,400,0)同"
        val lrc = listOf(LrcLine(0, "独 不同"))
        val w = YrcParser.attachWords(lrc, yrc)[0].words
        assertEquals(listOf("独", "不", "同"), w.map { it.text })
        assertTrue(w.zipWithNext().all { (a, b) -> a.charEndExclusive <= b.charStart })
    }

    @Test
    fun `对齐——某个词在 lrc 文本里找不到时该行放弃逐字（不给错位高亮）`() {
        // lrc 文本里没有「离开」，mapRanges 会在第 4 个词上失败
        val lrc = listOf(LrcLine(24898, "听见 冬天的走开"))
        val merged = YrcParser.attachWords(lrc, yujianYrc)
        assertEquals(1, merged.size)
        assertTrue(merged[0].words.isEmpty())
        assertNull(merged[0].endMs)
    }

    @Test
    fun `对齐——没有 yrc 或行数不等时原样返回（等价于旧版渲染）`() {
        val lrc = listOf(LrcLine(0, "a"), LrcLine(1000, "b"))
        assertSame(lrc, YrcParser.attachWords(lrc, ""))
        assertEquals(lrc, YrcParser.attachWords(lrc, wudingYrc))
    }

    @Test
    fun `解析——乱码与空行不会抛异常`() {
        assertTrue(YrcParser.parse("").isEmpty())
        assertTrue(YrcParser.parse("not a yrc at all").isEmpty())
        assertTrue(YrcParser.parse("[1000,2000]完全没有词的括号").isEmpty())
        assertEquals(1, YrcParser.parse("\n\n[0,0](0,0,0)x\n\n").size)
    }
}