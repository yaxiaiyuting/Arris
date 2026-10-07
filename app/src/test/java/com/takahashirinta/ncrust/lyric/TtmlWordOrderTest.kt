/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.9.0：**词表顺序不变量** —— 与冻结的渲染层之间的契约测试。
 *
 * ## 为什么单独一个测试文件
 *
 * [TtmlScannerTest] 验的是「TTML 解析对不对」，本文件验的是「解析结果能不能安全喂给渲染层」。
 * 两件事的失败后果完全不同：前者是歌词不对，后者是**逐字高亮倒着扫**（用户直接可见的错乱）。
 *
 * ## 被保护的不变量
 *
 * 渲染路径 [NcrustLyricsPanel] 会调 `SweepTrack.build(words, ...)`，而
 * [SweepTrack.build] 的第一件事是
 * `words.sortedWith(compareBy({ it.startMs }, { it.charStart }))`（SweepTrack.kt:244）。
 * 也就是说**渲染层有自己的排序**，它不接受「文本顺序」这个输入 —— 它认时间。
 *
 * 于是解析器必须保证：**按文本顺序给出的词，其 `startMs` 也单调不减**。
 * 若做不到，排序后 `charStart` 会回退，二分出来的折线就会把光标往回插值。
 * 本版渲染层**一行不许改**（任务书硬约束），所以这条不变量只能由解析器兜住。
 *
 * 现实中的违反来源只有一个：`ttm:role="x-bg"` 背景人声（见 [TtmlScanner] 的 KDoc）。
 */
class TtmlWordOrderTest {

    /**
     * 13 个真实文件，全部是从 https://amlldb.bikonoo.com/ncm-lyrics/<id>.ttml 直接 curl 的**原文**，
     * 未做任何手工改造。覆盖了本版实测到的全部形态：
     * `x-translation`（5 个）、`x-roman`（1 个）、`x-bg`（4 个）、裸数字秒 / 1 位分钟（1974443814）。
     */
    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("ttml/$name")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private val fixtures = listOf(
        // 逐字 + 背景人声（本版 x-bg 取舍的主要样本）
        "1901371647.ttml",  // 孤勇者
        "347230.ttml",      // 海阔天空（x-roman，且 x-roman 无 begin/end）
        "1974443814.ttml",  // 我记得（裸数字秒 + 1 位分钟）
        // 实测命中 TTML 的另外 10 首
        "1959528822.ttml", "108463.ttml", "2645500113.ttml", "208902.ttml", "316100.ttml",
        "19438337.ttml", "1951069525.ttml", "31654478.ttml", "16686599.ttml", "36990266.ttml",
    )

    /** 实测 5 个文件含 `ttm:role="x-translation"`，这里是全部命中样本中的那 5 个。 */
    private val withTranslation = listOf(
        "108463.ttml", "19438337.ttml", "31654478.ttml", "16686599.ttml", "36990266.ttml",
    )

    /**
     * 全量真实文件扫描：**每一行**的词表都必须同时满足两条单调性。
     *
     * 这是本文件最重要的一条 —— 它不针对某个已知的坏样本，而是把所有真实数据过一遍，
     * 将来 AMLL 投稿里出现新的时序花样也会在这里被挡下。
     */
    @Test
    fun `真实文件全量——每一行的 words 都同时满足 startMs 与 charStart 单调`() {
        var checkedLines = 0
        var checkedWords = 0
        for (name in fixtures) {
            val doc = TtmlParser.parse(fixture(name))
            assertNotNull("fixture $name 解析失败", doc)
            for (line in doc!!.lines) {
                checkedLines++
                var lastStart = Long.MIN_VALUE
                var lastEnd = 0
                for (w in line.words) {
                    checkedWords++
                    assertTrue(
                        "$name 行「${line.text}」词「${w.text}」startMs 倒退：${w.startMs} < $lastStart",
                        w.startMs >= lastStart
                    )
                    assertTrue(
                        "$name 行「${line.text}」词「${w.text}」charStart 与前一词重叠：${w.charStart} < $lastEnd",
                        w.charStart >= lastEnd
                    )
                    assertTrue("词区间必须非空", w.charEndExclusive > w.charStart)
                    assertTrue(
                        "词区间越界：${w.charEndExclusive} > ${line.text.length}",
                        w.charEndExclusive <= line.text.length
                    )
                    assertEquals(
                        "词文本必须等于行文本的切片",
                        line.text.substring(w.charStart, w.charEndExclusive),
                        w.text
                    )
                    lastStart = w.startMs
                    lastEnd = w.charEndExclusive
                }
            }
        }
        // 防止 fixture 被换成空文件后测试变成「空转通过」
        assertTrue("扫描到的行数太少（$checkedLines），fixture 可能没加载到", checkedLines >= 100)
        assertTrue("扫描到的词数太少（$checkedWords）", checkedWords >= 1000)
    }

    /**
     * 定向用例：文本序在后的背景人声时间**早于**前面的主唱 ⇒ 该词丢弃，其余保持可用。
     *
     * 结构仿真实文件：x-bg 是**容器** span，内部才是真正的词 span。
     */
    @Test
    fun `时间倒退的背景人声被丢弃，主唱词序不变`() {
        val doc = TtmlParser.parse(
            "<tt xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\"><body><div>" +
                "<p begin=\"00:01.000\" end=\"00:05.000\">" +
                "<span begin=\"00:01.000\" end=\"00:02.000\">去</span>" +
                "<span begin=\"00:02.000\" end=\"00:03.000\">吗</span>" +
                "<span ttm:role=\"x-bg\"><span begin=\"00:00.500\" end=\"00:00.900\">(去</span>" +
                "<span begin=\"00:00.900\" end=\"00:01.200\">吗</span></span>" +
                "</p></div></body></tt>"
        )!!
        val line = doc.lines.single()
        // 行文本完整保留（含背景人声），只有**词**被过滤
        assertEquals("去吗(去吗", line.text)
        assertEquals("时间倒退的背景词必须被丢弃，只剩主唱两个词", 2, line.words.size)
        assertEquals(listOf("去", "吗"), line.words.map { it.text })
        assertEquals(listOf(1000L, 2000L), line.words.map { it.startMs })
        // 词区间仍然指向行文本里正确的位置（背景文本那一段只是没有词覆盖）
        assertEquals("去", line.text.substring(line.words[0].charStart, line.words[0].charEndExclusive))
        assertEquals("吗", line.text.substring(line.words[1].charStart, line.words[1].charEndExclusive))
    }

    /** 背景人声**时间顺排**时不该被误杀 —— 过滤只针对倒退，不做「一律丢弃 x-bg」。 */
    @Test
    fun `时间顺排的背景人声被保留`() {
        val doc = TtmlParser.parse(
            "<tt xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\"><body><div>" +
                "<p begin=\"00:01.000\" end=\"00:05.000\">" +
                "<span begin=\"00:01.000\" end=\"00:02.000\">去</span>" +
                "<span ttm:role=\"x-bg\"><span begin=\"00:02.000\" end=\"00:03.000\">(背景</span></span>" +
                "<span begin=\"00:03.000\" end=\"00:04.000\">吗</span>" +
                "</p></div></body></tt>"
        )!!
        val line = doc.lines.single()
        assertEquals(3, line.words.size)
        assertEquals(listOf(1000L, 2000L, 3000L), line.words.map { it.startMs })
        assertTrue("charStart 仍单调", line.words.zipWithNext().all { it.first.charEndExclusive <= it.second.charStart })
    }

    /**
     * 词时长必须按**过滤后**的邻居算。
     *
     * 若先算时长再过滤，被丢弃的那个词的时间会被当成前一个词的「下一个词 begin」，
     * 于是一个只唱到 02.000 的词会拿到 1.5 秒的时长（而不是 1 秒），扫过速度变慢。
     */
    @Test
    fun `丢弃词后，前一个词的时长按剩下的邻居重算`() {
        val doc = TtmlParser.parse(
            "<tt xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\"><body><p begin=\"00:01.000\">" +
                "<span begin=\"00:01.000\" end=\"00:02.000\">甲</span>" +
                "<span ttm:role=\"x-bg\"><span begin=\"00:00.500\">(幽</span></span>" +
                "<span begin=\"00:03.000\" end=\"00:04.000\">乙</span>" +
                "</p></body></tt>"
        )!!
        val line = doc.lines.single()
        assertEquals(2, line.words.size)
        // 甲 有自己的 end=02.000 ⇒ 1000ms，不能被被丢弃的 00.500 干扰
        assertEquals(1000L, line.words[0].durationMs)
        assertEquals("甲", line.words[0].text)
    }

    /**
     * **真实数据补齐**：`ttm:role="x-translation"` 的解析。
     *
     * 实现者当时只有三个真实文件、其中 `x-translation` 出现 0 次，所以那条路径只用**手写 XML**
     * 覆盖过，属于「未验证」。这里用实测含翻译的 5 个真实文件把它补上。
     *
     * 两件事都要成立：翻译轨道**有内容**，且它的文本**没有漏进正文行文本**。
     */
    @Test
    fun `真实文件——x-translation 升成翻译轨道，且不漏进正文`() {
        var totalTracks = 0
        for (name in withTranslation) {
            val doc = TtmlParser.parse(fixture(name))!!
            assertTrue("$name 应当解析出翻译轨道", doc.translations.isNotEmpty())
            totalTracks += doc.translations.size
            // 翻译行必须自带时间戳（取父 <p> 的 begin），否则调用方无法与正文配对
            assertTrue("$name 的翻译行必须有时间戳", doc.translations.all { it.timeMs >= 0 })
            assertTrue("$name 的翻译行不该带逐字词", doc.translations.all { it.words.isEmpty() })
            // 翻译文本不得出现在任何正文行里（否则行文本被污染、字符区间会错位）
            val lineTexts = doc.lines.map { it.text }.toSet()
            val leaked = doc.translations.count { it.text in lineTexts }
            assertEquals("$name 有 $leaked 条翻译漏进了正文", 0, leaked)
            // 正文字数必须仍与行数一致、且每行非空
            assertTrue(doc.lines.all { it.text.isNotBlank() })
        }
        assertTrue("5 个真实文件合计翻译轨道太少（$totalTracks）", totalTracks >= 20)
    }

    /**
     * **配对契约**：翻译 / 音译行的时间戳必须**精确落在**某个正文行的时间戳上。
     *
     * 这是接入层做的那件事的前提 —— `PlayerViewModel.applyTtmlLyrics` 用
     * `doc.translations.filter { it.timeMs in lineTimes }` 配对（`LyricsView` 也按 timeMs
     * `associateBy`）。解析器把轨道时间戳取成**父 `<p>` 的 begin**，正是为了让这个精确相等成立。
     *
     * 这条断言一旦挂掉，症状是「翻译与音译在真机上一行都不显示」—— 而单看「translations 非空」
     * 是发现不了的（那正是本文件前面那条用例覆盖的范围）。
     */
    @Test
    fun `真实文件——翻译轨道的时间戳必须精确落在某个正文行上`() {
        for (name in fixtures) {
            val doc = TtmlParser.parse(fixture(name))!!
            val lineTimes = doc.lines.mapTo(HashSet()) { it.timeMs }
            val unmatchedTr = doc.translations.filter { it.timeMs !in lineTimes }
            val unmatchedRo = doc.romans.filter { it.timeMs !in lineTimes }
            assertTrue(
                "$name 有 ${unmatchedTr.size}/${doc.translations.size} 条翻译的行时间戳对不上正文",
                unmatchedTr.isEmpty()
            )
            assertTrue(
                "$name 有 ${unmatchedRo.size}/${doc.romans.size} 条音译的行时间戳对不上正文",
                unmatchedRo.isEmpty()
            )
        }
    }

    /** **真实数据补齐**：`ttm:role="x-roman"` 的解析（海阔天空 347230 实测含音译）。 */
    @Test
    fun `真实文件——x-roman 升成音译轨道`() {
        val doc = TtmlParser.parse(fixture("347230.ttml"))!!
        assertTrue("海阔天空应当解析出音译轨道", doc.romans.isNotEmpty())
        assertTrue("音译行不该带逐字词", doc.romans.all { it.words.isEmpty() })
        val lineTexts = doc.lines.map { it.text }.toSet()
        assertEquals("音译文本漏进了正文", 0, doc.romans.count { it.text in lineTexts })
    }

    /**
     * `TtmlDoc` 的三个轨道都必须**按时间升序**。
     *
     * 调用方（PlayerViewModel / LyricsView）按时间配对正文与翻译，顺序错乱会让译文的行号偏移。
     */
    @Test
    fun `真实文件——lines 与 translations 与 romans 三条轨道都按时间升序`() {
        for (name in fixtures) {
            val doc = TtmlParser.parse(fixture(name))!!
            for ((label, track) in listOf("lines" to doc.lines, "translations" to doc.translations, "romans" to doc.romans)) {
                assertTrue(
                    "$name 的 $label 轨道未按时间升序",
                    track.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs }
                )
            }
        }
    }
}
