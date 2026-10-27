/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.8.0 · H18：**分钟位数不设上限**的时间戳回归（100 分钟之后的字幕不再被丢弃）。
 */

package com.takahashirinta.ncrust.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H18 的回归契约：`[mm:ss.xx]` 的 `mm` **没有上限**。
 *
 * ## 缺陷本体
 *
 * `LrcParser` 的两个正则写死了 `\d{2}`（分钟恰好两位），而 `BiliSubtitle.formatStamp`
 * 用的是 `"%02d:%02d.%02d".format(m, s, cs)` —— `%02d` 只规定**最小**宽度，
 * 100 分钟就会生成 `[100:00.00]`。于是：
 *
 * - `LEADING_TIME_STAMPS` 认不出这行 ⇒ 整行 `continue`；
 * - 就算改成只放宽那一处，`TIME_STAMP` 也会一个都匹配不到 ⇒ 行仍然丢。
 *
 * 两条正则必须一起改（[multiDigitMinutesAreParsed] 与 [mixedDigitWidthsInOneLine]
 * 分别覆盖「整行认出来」与「行内时间戳逐个展开」）。
 *
 * 探针（06:43 轮 H18）：99:59、100:00、100:02 三条有效音乐字幕，音乐筛选保留全部三条，
 * 旧解析器只留下第一条。
 */
class LrcParserLongMinutesTest {

    @Test
    fun `三位分钟的时间戳必须被解析而不是整行丢弃`() {
        val lines = LrcParser.parse(
            """
            [99:59.00]最后一句
            [100:00.00]第一百分钟
            [100:02.50]再两秒
            """.trimIndent()
        )
        assertEquals("三条都必须保留", 3, lines.size)
        assertEquals(listOf(99L * 60_000 + 59_000L, 100L * 60_000, 100L * 60_000 + 2_500L), lines.map { it.timeMs })
        assertEquals(listOf("最后一句", "第一百分钟", "再两秒"), lines.map { it.text })
    }

    @Test
    fun `一行多时间戳时三位分钟也能逐个展开`() {
        // 副歌复用的写法：同一句在多个时间点出现，其中一条已在 100 分钟之后。
        val lines = LrcParser.parse("[01:37.57][100:00.00][120:30.25]和我在成都的街头走一走")
        assertEquals("三个时间点一个都不能少", 3, lines.size)
        assertEquals(
            listOf(97_570L, 100L * 60_000, 120L * 60_000 + 30_250L),
            lines.map { it.timeMs },
        )
        assertTrue("正文里不许残留方括号", lines.none { it.text.contains('[') })
    }

    @Test
    fun `两位分钟与三位小数仍然照旧`() {
        val lines = LrcParser.parse("[01:06.45]短句\n[00:33.260]毫秒")
        assertEquals(2, lines.size)
        // 解析结果按时间升序（多戳行展开后时间天然不单调，见 parse 的 KDoc）。
        assertEquals(33_260L, lines[0].timeMs)
        assertEquals("毫秒", lines[0].text)
        assertEquals(66_450L, lines[1].timeMs)
        assertEquals("短句", lines[1].text)
    }

    @Test
    fun `更长的分钟同样成立 —— 上限不是被从 2 挪到 3`() {
        val lines = LrcParser.parse("[1234:56.78]超长音频")
        assertEquals(1, lines.size)
        assertEquals(1234L * 60_000 + 56_780L, lines[0].timeMs)
    }

    @Test
    fun `元信息行与空正文仍不产出条目`() {
        val lines = LrcParser.parse(
            """
            [ti:标题]
            [ar:艺人]
            [100:00.00]
            [100:01.00]   
            [100:02.00]真的歌词
            """.trimIndent()
        )
        assertEquals("只有带正文的那一行产出条目", 1, lines.size)
        assertEquals("真的歌词", lines.single().text)
    }
}
