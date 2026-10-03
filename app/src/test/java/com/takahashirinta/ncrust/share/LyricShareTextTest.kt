/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * [LyricShareText] 的单测（v3.3.0 · 用户需求第 9 条）。
 *
 * 「格式化」是用户唯一能直接看到的东西（复制出来粘到别处的那段文本），
 * 但它同时受四个开关影响（表头 / 时间戳 / 译文 / 音译），组合起来 16 种。
 * 这些用例把每一种开关**单独**钉一遍，再钉两个边界（空歌词、只有译文）。
 */
class LyricShareTextTest {

    private val track = LyricShareTrack(name = "成都", artist = "赵雷", album = "无法长大")

    private val lines = listOf(
        LyricShareLine(timeMs = 12_300, text = "让我掉下眼泪的", translation = "The one that makes me cry"),
        LyricShareLine(timeMs = 16_800, text = "不止昨夜的酒", translation = ""),
        LyricShareLine(timeMs = 20_000, text = "余路还要走多久"),
    )

    // ------------------------------------------------------------ 整体形状

    @Test
    fun `默认格式_表头 + 空行 + 歌词 + 空行 + 署名`() {
        val text = LyricShareText.format(track, lines, LyricShareOptions(), credit = "来自 Ncrust")
        assertEquals(
            """
            成都 - 赵雷

            让我掉下眼泪的
            The one that makes me cry
            不止昨夜的酒
            余路还要走多久

            来自 Ncrust
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `不带时间戳是默认_复制到聊天窗里不该出现方括号`() {
        val text = LyricShareText.format(track, lines, LyricShareOptions(), credit = "")
        assertFalse(text.contains("["))
        assertFalse(text.contains("12:30"))
    }

    @Test
    fun `带时间戳_用 LRC 原生的 mm_ss_厘秒格式_可以直接粘回编辑器`() {
        val text = LyricShareText.format(
            track,
            lines,
            LyricShareOptions(includeTimestamps = true, includeHeader = false, includeCredit = false),
            credit = "",
        )
        val first = text.lines().first()
        assertEquals("[00:12.30] 让我掉下眼泪的", first)
        assertTrue(text.contains("[00:16.80] 不止昨夜的酒"))
    }

    @Test
    fun `时间戳格式_零点_超过一小时_与负值兜底`() {
        assertEquals("[00:00.00]", LyricShareText.timestamp(0))
        assertEquals("[01:06.45]", LyricShareText.timestamp(66_450))
        // 61 分钟：分钟位不进位到小时，保持 LRC 的 mm 语义（LrcParser 也按 mm 读）。
        assertEquals("[61:01.00]", LyricShareText.timestamp(3_661_000))
        // 负值（时钟回拨 / 脏数据）不许产出 `[-1:-1.-1]` 这种字符串。
        assertEquals("[00:00.00]", LyricShareText.timestamp(-500))
    }

    // ------------------------------------------------------------ 开关

    @Test
    fun `表头开关_关掉之后第一行就是歌词`() {
        val text = LyricShareText.format(
            track,
            lines,
            LyricShareOptions(includeHeader = false, includeCredit = false),
            credit = "来自 Ncrust",
        )
        assertEquals("让我掉下眼泪的", text.lines().first())
    }

    @Test
    fun `署名开关_关掉之后不出现署名行`() {
        val withCredit = LyricShareText.format(track, lines, LyricShareOptions(), credit = "来自 Ncrust")
        val without = LyricShareText.format(
            track,
            lines,
            LyricShareOptions(includeCredit = false),
            credit = "来自 Ncrust",
        )
        assertTrue(withCredit.endsWith("来自 Ncrust"))
        assertFalse(without.contains("Ncrust"))
    }

    @Test
    fun `译文开关_关掉之后只剩原文`() {
        val text = LyricShareText.format(
            track,
            lines,
            LyricShareOptions(includeTranslation = false, includeHeader = false, includeCredit = false),
            credit = "",
        )
        assertFalse(text.contains("The one that makes me cry"))
        assertEquals(listOf("让我掉下眼泪的", "不止昨夜的酒", "余路还要走多久"), text.lines())
    }

    @Test
    fun `音译开关_默认关_打开后跟在原文后面`() {
        val two = listOf(
            LyricShareLine(0, "紫荆花飘扬", romanization = "zi ging fa piu yoeng", translation = "Bauhinia flutters"),
            LyricShareLine(1000, "飞向蓝天", romanization = "fei hoeng laam tin"),
        )
        val off = LyricShareText.format(
            track,
            lines = two,
            options = LyricShareOptions(includeHeader = false, includeCredit = false, includeTranslation = false),
            credit = "",
        )
        assertEquals(listOf("紫荆花飘扬", "飞向蓝天"), off.lines())

        val on = LyricShareText.format(
            track,
            lines = two,
            options = LyricShareOptions(
                includeHeader = false,
                includeCredit = false,
                includeTranslation = false,
                includeRomanization = true,
            ),
            credit = "",
        )
        assertEquals(
            listOf("紫荆花飘扬", "zi ging fa piu yoeng", "飞向蓝天", "fei hoeng laam tin"),
            on.lines(),
        )
    }

    @Test
    fun `译文与原文逐字相同时丢掉_脏 tlyric 不会把同一句印两遍`() {
        val dirty = listOf(
            LyricShareLine(0, "Hello from the other side", translation = "Hello from the other side"),
            LyricShareLine(1000, "  Hello  ", translation = "Hello"),
        )
        val text = LyricShareText.format(
            track,
            lines = dirty,
            options = LyricShareOptions(includeHeader = false, includeCredit = false),
            credit = "",
        )
        assertEquals(listOf("Hello from the other side", "Hello"), text.lines())
    }

    // ------------------------------------------------------------ 边界

    @Test
    fun `空歌词_返回空串_不产出只有表头的怪文本`() {
        // 表头仍然在（它来自曲目，不来自歌词）—— 但正文与署名之间不留出多余空行。
        val bare = LyricShareText.format(
            LyricShareTrack(""),
            lines = emptyList(),
            options = LyricShareOptions(includeHeader = false, includeCredit = false),
            credit = "",
        )
        assertEquals("", bare)

        val withHeader = LyricShareText.format(track, emptyList(), LyricShareOptions(), credit = "来自 Ncrust")
        assertEquals("成都 - 赵雷\n\n来自 Ncrust", withHeader)
    }

    @Test
    fun `只有译文没有原文的行_正文为空时整行跳过_不产出空白行`() {
        val onlyTranslation = listOf(
            LyricShareLine(0, "", translation = "只有译文"),
            LyricShareLine(1000, "有原文", translation = "有译文"),
        )
        val text = LyricShareText.body(
            onlyTranslation,
            LyricShareOptions(includeHeader = false, includeCredit = false),
        )
        assertEquals(listOf("有原文", "有译文"), text.lines())
    }

    @Test
    fun `纯空白歌词行被跳过_末尾不留多余空行`() {
        val blank = listOf(
            LyricShareLine(0, "   ", translation = "   "),
            LyricShareLine(1000, "正常一行"),
            LyricShareLine(2000, ""),
        )
        val text = LyricShareText.body(blank, LyricShareOptions())
        assertEquals("正常一行", text)
    }

    @Test
    fun `曲目字段缺失_标题退化而不是产出孤零零的破折号`() {
        assertEquals("成都 - 赵雷", LyricShareTrack("成都", "赵雷").title)
        assertEquals("成都", LyricShareTrack("成都").title)
        assertEquals("赵雷", LyricShareTrack("", "赵雷").title)
        assertEquals("", LyricShareTrack("").title)
        // 没有表头可写时，format 不许写出一个空行 + 空行。
        val noName = LyricShareText.format(
            LyricShareTrack(""),
            listOf(LyricShareLine(0, "一句歌词")),
            LyricShareOptions(includeCredit = false),
            credit = "",
        )
        assertEquals("一句歌词", noName)
    }

    // ------------------------------------------------------------ 文件命名

    @Test
    fun `文件名_含歌名与时刻_同一首歌连出两张不会互相覆盖`() {
        val a = LyricShareText.fileName(track, "20261003-214800")
        val b = LyricShareText.fileName(track, "20261003-214801")
        assertEquals("ncrust-lyrics-成都-20261003-214800.png", a)
        assertTrue(a != b)
    }

    @Test
    fun `文件名_非法字符全部换成短横_并且不出现连续短横或首尾短横`() {
        val messy = LyricShareTrack(name = "A/B: C?D*E \"F\" <G>|H\\I\nJ")
        assertEquals("A-B-C-D-E-F-G-H-I-J", LyricShareText.slugify(messy.name))
        assertEquals("ncrust-lyrics-A-B-C-D-E-F-G-H-I-J-20261003-214800.png", LyricShareText.fileName(messy, "20261003-214800"))
    }

    @Test
    fun `文件名_空歌名或全非法字符_回落 lyrics 而不是空段`() {
        assertEquals("lyrics", LyricShareText.slugify(""))
        assertEquals("lyrics", LyricShareText.slugify("   "))
        assertEquals("lyrics", LyricShareText.slugify("///???"))
        assertEquals("ncrust-lyrics-lyrics-20261003-214800.png", LyricShareText.fileName(LyricShareTrack(""), "20261003-214800"))
    }

    @Test
    fun `文件名_超长歌名被截断到上限_不撞文件系统名字限制`() {
        val long = LyricShareTrack(name = "长".repeat(200))
        val slug = LyricShareText.slugify(long.name)
        assertEquals(LyricShareText.MAX_NAME_CHARS, slug.length)
        assertTrue(slug.length <= 24)
    }

    @Test
    fun `时刻戳_时区可注入_单测因此完全确定`() {
        assertEquals("19700101-000000", LyricShareText.stamp(0L, TimeZone.getTimeZone("UTC")))
        assertEquals(
            "20261003-214800",
            // 2026-10-03 21:48:00 +08:00 = 1791035280000（用固定时刻，不读系统时钟）
            LyricShareText.stamp(1_791_035_280_000L, TimeZone.getTimeZone("Asia/Shanghai")),
        )
    }
}
