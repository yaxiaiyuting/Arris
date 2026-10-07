/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.9.0：AMLL TTML 扫描器（[TtmlScanner] / [TtmlParser]）的单测。
 *
 * 三个主力样本都是 2026-09 从 `https://amlldb.bikonoo.com/ncm-lyrics/<NCM_ID>.ttml` 拉的**真实原文**，
 * 原样放进 `src/test/resources/ttml/`（没有手工改造一个字节），断言里的每个数字都对得上文件：
 *
 * - `1901371647.ttml`《孤勇者》：逐字 + 2 处 `x-bg`（背景人声，容器型 span）；
 * - `347230.ttml`《海阔天空》：逐字 + 38 处 `x-roman`（**都没有 begin/end**，还有嵌在 x-bg 里的）；
 * - `1974443814.ttml`《我记得》：另一个投稿者的写法 —— 前 7 行时间用**裸数字**（`begin="28.571"`），
 *   其余用单位数分钟（`1:01.110`）。
 *
 * 手写片段只用于真实文件没覆盖到的形态（`x-translation`、实体/CDATA/自闭合、时间兜底），
 * 每条都指名了它守的是规则第几条。
 */
class TtmlScannerTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("ttml/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

    private val guYongZhe: TtmlDoc by lazy { TtmlParser.parse(fixture("1901371647.ttml"))!! }

    // ------------------------------------------------------------ 真实文件

    @Test
    fun `真实——孤勇者第一行：span 之间的空格必须保留，字符区间跟着它走`() {
        val doc = TtmlParser.parse(fixture("1901371647.ttml"))
        assertNotNull(doc)
        doc!!
        assertTrue(doc.lines.isNotEmpty())
        assertTrue(TtmlParser.hasWordLevel(doc))

        val first = doc.lines.first()
        assertEquals(22402L, first.timeMs)
        assertEquals(26962L, first.endMs)
        // 规则 1：ncm 自己的 LRC 就是「都 是勇敢的」（带空格），吞掉它逐字高亮就会错帧
        assertEquals("都 是勇敢的", first.text)
        assertEquals(4, first.words.size)

        val w0 = first.words[0]
        assertEquals("都", w0.text)
        assertEquals(22402L, w0.startMs)
        assertEquals(929L, w0.durationMs)   // 00:23.331 − 00:22.402
        assertEquals(0, w0.charStart)
        assertEquals(1, w0.charEndExclusive)

        val w1 = first.words[1]
        assertEquals("是", w1.text)
        assertEquals(2, w1.charStart)       // 中间那个空格占了 compat 下标 1
        assertEquals(3, w1.charEndExclusive)

        // 渲染层就是拿 charStart/charEndExclusive 去 getPathForRange 的：回切行文本必须正好是词本身
        assertEquals(
            first.words.map { it.text },
            first.words.map { first.text.substring(it.charStart, it.charEndExclusive) }
        )
        // 行内区间单调不重叠（与 yrc 对齐后的词同一不变量）
        assertTrue(first.words.zipWithNext().all { (a, b) -> a.charEndExclusive <= b.charStart })
    }

    @Test
    fun `真实——孤勇者整份：54 行全有正文，行按时间升序`() {
        val doc = guYongZhe
        assertEquals(54, doc.lines.size)
        assertTrue(doc.lines.all { it.text.isNotBlank() })
        assertTrue(doc.lines.all { it.words.isNotEmpty() })
        assertTrue(doc.lines.zipWithNext().all { (a, b) -> a.timeMs <= b.timeMs })
        assertTrue(doc.translations.isEmpty())
        assertTrue(doc.romans.isEmpty())
    }

    @Test
    fun `真实——孤勇者 x-bg：容器不建词，时间倒退的背景词被丢弃、主唱词序不变`() {
        val doc = guYongZhe
        // L51：p begin=03:41.272，末尾挂一个 x-bg 容器（自己只有两个子 span 之间的一个空格）
        val line = doc.lines.first { it.timeMs == 221272L }
        // **行文本完整保留**，包括背景人声那一段
        assertEquals("去吗 去啊 以最卑微的梦 (去吗 去啊)", line.text)
        assertEquals(224938L, line.endMs)

        // 但背景词 (去吗 03:42.019 / 去啊) 排在文本末尾、时间却早于主唱的「梦」，
        // 属**时间倒退** ⇒ 被 buildLine 的单调过滤丢弃（见 TtmlScanner 的 KDoc）。
        // 剩余的 8 个主唱词保持文本顺序，charStart 与 startMs 双双单调 —— 这是
        // SweepTrack.build 按 startMs 排序后仍然正确的前提（见 TtmlWordOrderTest）。
        assertEquals(8, line.words.size)
        assertEquals(
            listOf("去", "吗", "去", "啊", "以", "最卑微", "的", "梦"),
            line.words.map { it.text }
        )
        assertEquals(listOf(0, 1, 3, 4, 6, 7, 10, 11), line.words.map { it.charStart })
        assertTrue(line.words.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
        assertTrue(line.words.zipWithNext().all { (a, b) -> a.charEndExclusive <= b.charStart })
        // 被丢弃的背景文本仍在行里（只是没有词覆盖它）
        assertTrue(line.text.contains("(去吗 去啊)"))
    }

    @Test
    fun `真实——海阔天空：x-roman 进 romans 轨道，不进正文也不建词`() {
        val doc = TtmlParser.parse(fixture("347230.ttml"))!!
        assertEquals(35, doc.lines.size)
        // 38 处 x-roman > 35 行：有 3 行挂了两段罗马音（其中一段还嵌在 x-bg 容器里）
        assertEquals(38, doc.romans.size)
        assertTrue(doc.translations.isEmpty())

        // 行级轨道的时间戳取父 <p> 的 begin，调用方据此与 lines 配对
        assertEquals(18432L, doc.romans.first().timeMs)
        assertEquals("gam tin ngo  hon je lei hon syut piu gwo", doc.romans.first().text)

        val first = doc.lines.first()
        assertEquals(18432L, first.timeMs)
        assertEquals("今天我 寒夜里看雪飘过", first.text)
        assertFalse(first.text.contains("gam tin"))
        assertEquals(10, first.words.size)
        assertEquals(listOf(0, 1, 2, 4, 5, 6, 7, 8, 9, 10), first.words.map { it.charStart })
        assertEquals(11, first.words.last().charEndExclusive)
        assertTrue(TtmlParser.hasWordLevel(doc))
    }

    @Test
    fun `真实——我记得：裸数字时间是秒，p 直接挂 body 的写法也认`() {
        val doc = TtmlParser.parse(fixture("1974443814.ttml"))!!
        assertEquals(45, doc.lines.size)
        assertEquals("1974443814", doc.meta.ncmMusicId)
        // begin="28.571" ⇒ 28571 ms（ncm LRC 同一句是 [00:28.15]）
        assertEquals(28571L, doc.lines.first().timeMs)
        assertEquals("我带着比身体重的行李", doc.lines.first().text)
        assertEquals(31280L, doc.lines.first().endMs)
        assertEquals(31290L, doc.lines[1].timeMs)
        assertEquals("游入尼罗河底 经过几道闪电 看到一堆光圈", doc.lines[1].text)
        // 后面的行换成 1:01.110 这种单位数分钟写法
        assertTrue(doc.lines.any { it.timeMs == 61110L })
    }

    // ------------------------------------------------------------ 轨道与元数据

    @Test
    fun `翻译——x-translation 升成行级轨道，正文与词都不含它`() {
        val doc = TtmlParser.parse(
            "<tt xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\"><body>" +
                "<p begin=\"00:01.000\" end=\"00:03.000\">" +
                "<span begin=\"00:01.000\" end=\"00:02.000\">Hello</span>" +
                " <span begin=\"00:02.000\" end=\"00:03.000\">world</span>" +
                "<span ttm:role=\"x-translation\">你好 世界</span>" +
                "<span ttm:role=\"x-roman\">ha lou wot</span>" +
                "</p></body></tt>"
        )!!
        val line = doc.lines.single()
        assertEquals("Hello world", line.text)
        assertEquals(2, line.words.size)
        assertEquals(6, line.words[1].charStart)
        assertEquals("你好 世界", doc.translations.single().text)
        assertEquals(1000L, doc.translations.single().timeMs)
        assertEquals("ha lou wot", doc.romans.single().text)
        assertEquals(1000L, doc.romans.single().timeMs)
    }

    @Test
    fun `元数据——amll meta 的五个键都落到 TtmlMeta`() {
        val meta = guYongZhe.meta
        assertEquals("1901371647", meta.ncmMusicId)
        assertEquals("孤勇者", meta.musicName)
        assertEquals("陈奕迅", meta.artists)
        assertEquals("孤勇者", meta.album)
        assertEquals("SteamFinder", meta.author)
    }

    // ------------------------------------------------------------ 时间

    @Test
    fun `时间——MM-SS-fff、HH-MM-SS-fff、offset-time 与裸数字都换算成绝对毫秒`() {
        val doc = TtmlParser.parse(
            "<tt><body>" +
                "<p begin=\"00:01.500\" end=\"00:02.000\"><span begin=\"00:01.500\" end=\"00:02.000\">甲</span></p>" +
                "<p begin=\"01:00:02.250\" end=\"01:00:03.000\"><span begin=\"01:00:02.250\" end=\"01:00:03.000\">乙</span></p>" +
                "<p begin=\"12.3s\" end=\"13s\"><span begin=\"12.3s\" end=\"12.8s\">丙</span></p>" +
                "<p begin=\"1500ms\" end=\"1.6s\"><span begin=\"1500ms\" end=\"1600ms\">丁</span></p>" +
                "<p begin=\"10f\" end=\"20f\"><span begin=\"10f\" end=\"20f\">戊</span></p>" +
                "<p begin=\"28.571\"><span begin=\"28.571\">己</span></p>" +
                "</body></tt>"
        )!!
        val byText = doc.lines.associateBy { it.text }
        assertEquals(1500L, byText.getValue("甲").timeMs)
        assertEquals(3602250L, byText.getValue("乙").timeMs)
        assertEquals(12300L, byText.getValue("丙").timeMs)
        assertEquals(1500L, byText.getValue("丁").timeMs)
        assertEquals(333L, byText.getValue("戊").timeMs)     // 10f ÷ 30fps
        assertEquals(28571L, byText.getValue("己").timeMs)

        // 词的时间用同一套换算
        assertEquals(3602250L, byText.getValue("乙").words.single().startMs)
        assertEquals(500L, byText.getValue("甲").words.single().durationMs)
        // 己：词没有 end、后面没有词、行也没有 end ⇒ 时长 0、行 endMs null
        assertEquals(0L, byText.getValue("己").words.single().durationMs)
        assertNull(byText.getValue("己").endMs)
    }

    @Test
    fun `时间兜底——词缺 end 用下一个词 begin，末词用行 end，都没有则 0`() {
        val doc = TtmlParser.parse(
            "<tt><body><p begin=\"00:01.000\" end=\"00:04.000\">" +
                "<span begin=\"00:01.000\">一</span>" +
                "<span begin=\"00:02.000\">二</span>" +
                "<span begin=\"00:03.000\">三</span>" +
                "</p></body></tt>"
        )!!
        val words = doc.lines.single().words
        assertEquals(1000L, words[0].durationMs)   // 下一个词 2000 − 1000
        assertEquals(1000L, words[1].durationMs)   // 下一个词 3000 − 2000
        assertEquals(1000L, words[2].durationMs)   // 行 end 4000 − 3000

        val noEnd = TtmlParser.parse(
            "<tt><body><p begin=\"00:01.000\"><span begin=\"00:01.000\">一</span></p></body></tt>"
        )!!
        assertNull(noEnd.lines.single().endMs)
        assertEquals(0L, noEnd.lines.single().words.single().durationMs)
    }

    // ------------------------------------------------------------ 容错

    @Test
    fun `容错——空串、非 XML、截断的 XML、超长输入一律 null 且不抛`() {
        assertNull(TtmlParser.parse(""))
        assertNull(TtmlParser.parse("not xml at all"))
        // 截断在两处：标签中途 / 元素没闭合
        assertNull(TtmlParser.parse("<tt><body><div><p begin=\"00:01.000\"><span begin=\"00:01.000\">都"))
        assertNull(TtmlParser.parse("<tt><body><p begin=\"00:01.000\">甲</p>"))
        // 没有 <p> 的合法 TTML 也是 null（调用方据此回退 LRC）
        assertNull(TtmlParser.parse("<tt><head></head><body dur=\"10s\"></body></tt>"))
        // 超长：>4 MB 直接拒绝，避免拿 HTML 错误页当歌词解析
        assertNull(TtmlParser.parse("<tt/>" + "x".repeat(4 * 1024 * 1024)))
    }

    @Test
    fun `容错——单个 p 坏掉只跳过它自己，其余照常解析`() {
        val doc = TtmlParser.parse(
            "<tt><body>" +
                "<p begin=\"00:01.000\" end=\"00:02.000\"><span begin=\"00:01.000\" end=\"00:02.000\">好</span></p>" +
                "<p begin=\"坏掉的时间\" end=\"00:04.000\"><span begin=\"00:03.000\">坏</span></p>" +
                "<p begin=\"00:05.000\" end=\"00:06.000\"><span begin=\"00:05.000\">也</span></p>" +
                "</body></tt>"
        )!!
        assertEquals(listOf("好", "也"), doc.lines.map { it.text })
    }

    @Test
    fun `容错——实体、CDATA、注释、自闭合、单引号、乱序属性、任意前缀都吃`() {
        val doc = TtmlParser.parse(
            "<!-- 文档前面来一段注释 -->" +
                "<foo:tt xmlns:foo=\"http://www.w3.org/ns/ttml\" xmlns:ttm=\"http://www.w3.org/ns/ttml#metadata\">" +
                "<foo:body><foo:p end='00:04.000' begin='00:03.000'>" +
                "<foo:span end='00:03.500' begin='00:03.000'>A&amp;B</foo:span>" +
                "<![CDATA[<不是标签>]]>" +
                "<foo:span begin='00:03.500' end='00:04.000'><!-- 词里夹注释 -->C</foo:span>" +
                "<foo:span begin='00:04.000'/>" +
                "<foo:span begin='00:04.000' end='00:05.000'>&#22909;&#x1F600;&quot;&apos;&lt;&gt;</foo:span>" +
                "</foo:p></foo:body></foo:tt>"
        )!!
        val line = doc.lines.single()
        assertEquals("A&B<不是标签>C好😀\"'<>", line.text)
        assertEquals(listOf("A&B", "C", "好😀\"'<>"), line.words.map { it.text })
        assertEquals(listOf(0, 9, 10), line.words.map { it.charStart })   // 自闭合的空 span 不建词
        assertEquals(line.text.length, line.words.last().charEndExclusive)
        assertEquals(
            line.words.map { it.text },
            line.words.map { line.text.substring(it.charStart, it.charEndExclusive) }
        )
    }

    @Test
    fun `空白——只裁整行首尾，行内空白与字符区间一起保留`() {
        val doc = TtmlParser.parse(
            "<tt><body><p begin=\"1s\">   " +
                "<span begin=\"1s\" end=\"2s\">独</span> " +
                "<span begin=\"2s\" end=\"3s\">不</span>   " +
                "</p></body></tt>"
        )!!
        val line = doc.lines.single()
        assertEquals("独 不", line.text)
        assertEquals(listOf(0, 2), line.words.map { it.charStart })
        assertEquals(3, line.words.last().charEndExclusive)
    }

    @Test
    fun `契约——TtmlParser 与 TtmlScanner 是同一个入口`() {
        val xml = fixture("1901371647.ttml")
        val direct = TtmlScanner.parse(xml)
        assertNotNull(direct)
        assertEquals(TtmlParser.parse(xml)!!.lines.size, direct!!.lines.size)
        assertNull(TtmlScanner.parse(""))
    }
}
