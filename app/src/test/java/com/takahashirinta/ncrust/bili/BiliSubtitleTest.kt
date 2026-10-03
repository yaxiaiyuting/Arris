package com.takahashirinta.ncrust.bili

import com.takahashirinta.ncrust.lyric.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BiliSubtitle] 的行为契约。
 *
 * ## 测试数据来源（**全部是实测原文，不是构造的**）
 *
 * 2026-10，本仓库自己的 curl，登录态（`nav` 返回 `isLogin=true`）：
 *
 * | 样本 | 内容 | 实测形状 |
 * |---|---|---|
 * | `BV1cN4y167BJ` 赵雷《成都》官方 MV | `aid=877869754 cid=1381738635` | 52 条，其中 46 条 `music≈1.0`（歌词）、6 条 `music=0.0`（开场对白） |
 * | `BV1mV411y78a` 《经典老歌 1~100》 | — | 71 条，**0 条** `music>0` ⇒ 不是歌词 |
 * | `BV1BKhH6qEh9` 《144 首华语金曲》 | — | **6997 条**，6969 条 `music>0` ⇒ 合集，见 [BiliSubtitle.LYRIC_CUE_MAX] |
 *
 * 这些数字被写进断言，是为了让「判据被改坏」在单测里立刻暴露 ——
 * 而不是等用户发现 MV 开头的对白变成了歌词。
 */
class BiliSubtitleTest {

    // ---------------------------------------------------------------- 解析 ----

    @Test
    fun `解析真实字幕 JSON 浮点秒换成整数毫秒`() {
        // 实测前两条原文（含开场对白）
        val body = """
            {"font_size":0.4,"font_color":"#FFFFFF","type":"json","lang":"zh",
             "body":[{"from":7.94,"to":8.96,"sid":1,"location":2,"content":"昨晚他来了吗","music":0.0},
                     {"from":59.46,"to":66.84,"sid":6,"location":2,"content":"♪ 让我掉下眼泪的不止昨夜的酒 ♪","music":0.9999998387097035}]}
        """.trimIndent()

        val cues = BiliSubtitle.parseCues(body)

        assertEquals(2, cues.size)
        assertEquals(7_940L, cues[0].fromMs)
        assertEquals(8_960L, cues[0].toMs)
        assertEquals("昨晚他来了吗", cues[0].text)
        assertEquals(0.0, cues[0].music, 1e-9)
        // 59.46s → 59460ms，不是 59459（浮点截断的经典翻车点）
        assertEquals(59_460L, cues[1].fromMs)
        assertEquals(66_840L, cues[1].toMs)
    }

    @Test
    fun `浮点秒的截断不产生差一毫秒`() {
        // 实测过的翻车值：1.001 * 1000.0 在双精度下是 1000.9999999999999，
        // `.toLong()` 截断得到 1000，比真实值少 1ms。所有**奇数毫秒**都命中
        // （1.001/1.003/1.005/… 全中），占千分位精度取值的大约一半 ——
        // 这是系统性单向偏移，不是偶发噪声。
        //
        // 反例（不要用它们写这条测试）：0.29*1000.0 恰好是 290.0、7.94*1000.0 恰好是 7940.0，
        // 用它们断言会**恒绿**，测不出这个缺陷。
        val body = """
            {"body":[{"from":1.001,"to":1.003,"content":"甲","music":1.0},
                     {"from":2.005,"to":2.007,"content":"乙","music":1.0}]}
        """.trimIndent()

        val cues = BiliSubtitle.parseCues(body)

        assertEquals("1.001s 必须解析成 1001ms 而不是 1000ms", 1_001L, cues[0].fromMs)
        assertEquals(1_003L, cues[0].toMs)
        assertEquals(2_005L, cues[1].fromMs)
        assertEquals(2_007L, cues[1].toMs)
    }

    @Test
    fun `缺 content 或 from 的条目被跳过`() {
        val body = """
            {"body":[{"from":1.0,"to":2.0,"content":"有效","music":1.0},
                     {"from":3.0,"to":4.0,"content":"   ","music":1.0},
                     {"to":5.0,"content":"没有起始时间","music":1.0},
                     {"from":6.0,"content":"缺 to 也要能用","music":1.0}]}
        """.trimIndent()

        val cues = BiliSubtitle.parseCues(body)

        assertEquals("只应留下第 1 条与第 4 条", 2, cues.size)
        assertEquals("有效", cues[0].text)
        assertEquals("缺 to 时 toMs 取 fromMs", 6_000L, cues[1].toMs)
    }

    @Test
    fun `非 JSON 与缺 body 一律返回空表而不抛异常`() {
        assertTrue(BiliSubtitle.parseCues(null).isEmpty())
        assertTrue(BiliSubtitle.parseCues("").isEmpty())
        assertTrue(BiliSubtitle.parseCues("<html>412</html>").isEmpty())
        assertTrue(BiliSubtitle.parseCues("""{"code":0,"message":"success"}""").isEmpty())
        assertTrue(BiliSubtitle.parseCues("""{"body":"不是数组"}""").isEmpty())
    }

    @Test
    fun `music 缺失的条目按 0 处理`() {
        val body = """{"body":[{"from":1.0,"to":2.0,"content":"没有 music 字段"}]}"""
        val cues = BiliSubtitle.parseCues(body)

        assertEquals(1, cues.size)
        assertEquals(0.0, cues[0].music, 1e-9)
        assertFalse("缺 music 且无 ♪ ⇒ 不是歌词", BiliSubtitle.isLyricCue(cues[0]))
    }

    // ---------------------------------------------------------------- 判据 ----

    @Test
    fun `music 近似 1 判为歌词 —— 不得用浮点等值比较`() {
        // 实测值就是这种形状：0.9999998387097035。
        // 写成 `music == 1.0` 这一条会红，那正是本用例存在的意义。
        val cue = BiliSubtitleCue(fromMs = 0L, toMs = 1000L, text = "让我掉下眼泪的", music = 0.9999998387097035)

        assertTrue(BiliSubtitle.isLyricCue(cue))
    }

    @Test
    fun `music 为零但有 ♪ 标记时兜底判为歌词`() {
        // 手传 CC 字幕可能不带 music 字段，但会带 ♪。兜底判据就是为它留的。
        val cue = BiliSubtitleCue(fromMs = 0L, toMs = 1000L, text = "♪ 手传字幕 ♪", music = 0.0)

        assertTrue(BiliSubtitle.isLyricCue(cue))
    }

    @Test
    fun `对白既无 music 也无 ♪ 判为非歌词`() {
        val cue = BiliSubtitleCue(fromMs = 7_940L, toMs = 8_960L, text = "昨晚他来了吗", music = 0.0)

        assertFalse(BiliSubtitle.isLyricCue(cue))
    }

    // ---------------------------------------------------------------- 筛选 ----

    @Test
    fun `成都 MV 的对白被滤掉 歌词保留`() {
        // 按实测比例复刻：52 条里 6 条对白 + 46 条歌词（0.885 ≥ 0.5）。
        val dialogues = (1..6).map {
            BiliSubtitleCue(it * 1_000L, it * 1_000L + 900L, "对白$it", 0.0)
        }
        val lyrics = (10..55).map {
            BiliSubtitleCue(it * 1_000L, it * 1_000L + 800L, "♪ 歌词第${it - 9}句 ♪", 0.9999999)
        }

        val kept = BiliSubtitle.lyricCues(dialogues + lyrics)

        assertEquals("6 条对白必须全部被滤掉", 46, kept.size)
        assertTrue("留下的都必须是歌词", kept.all { it.text.contains("♪") })
        assertFalse("对白不得混进来", kept.any { it.text.startsWith("对白") })
    }

    @Test
    fun `MV 合集的对白视频判为不是歌词`() {
        // 实测 BV1mV411y78a：71 条，0 条 music>0。
        val cues = (1..71).map { BiliSubtitleCue(it * 2_000L, it * 2_000L + 1_500L, "解说词$it", 0.0) }

        assertTrue("一份歌词都没有时返回空表", BiliSubtitle.lyricCues(cues).isEmpty())
    }

    @Test
    fun `条目太少时不算歌词`() {
        // 两条 ♪ 的短预告不该被当成一首歌的歌词（LYRIC_CUE_MIN = 3）。
        val cues = listOf(
            BiliSubtitleCue(0L, 1_000L, "♪ 甲 ♪", 1.0),
            BiliSubtitleCue(1_000L, 2_000L, "♪ 乙 ♪", 1.0),
        )

        assertTrue(BiliSubtitle.lyricCues(cues).isEmpty())
    }

    @Test
    fun `占比不足时不算歌词`() {
        // 3 条歌词 + 7 条对白 = 0.3 < 0.5 ⇒ 不是歌词（哪怕绝对条数到了 3）。
        val lyrics = (1..3).map { BiliSubtitleCue(it * 1_000L, it * 1_000L + 500L, "♪ 词$it ♪", 1.0) }
        val talk = (1..7).map { BiliSubtitleCue((it + 10) * 1_000L, (it + 10) * 1_000L + 500L, "话$it", 0.0) }

        assertTrue(BiliSubtitle.lyricCues(lyrics + talk).isEmpty())
    }

    @Test
    fun `合集视频的条目数被截断到上限`() {
        // 实测 BV1BKhH6qEh9 有 6997 条。截断是**有界保护**，不是正确性修复。
        val cues = (1..6_997).map {
            BiliSubtitleCue(it * 100L, it * 100L + 80L, "♪ 合集第${it}句 ♪", 0.9999999)
        }

        val kept = BiliSubtitle.lyricCues(cues)

        assertEquals(BiliSubtitle.LYRIC_CUE_MAX, kept.size)
        assertTrue("截断必须保留前段（保持时间升序）", kept.first().fromMs < kept.last().fromMs)
    }

    @Test
    fun `空字幕返回空表`() {
        assertTrue(BiliSubtitle.lyricCues(emptyList()).isEmpty())
    }

    // ---------------------------------------------------------------- LRC ----

    @Test
    fun `字幕转 LRC 剥掉演唱标记并写出时间戳`() {
        val cues = listOf(
            BiliSubtitleCue(59_460L, 66_840L, "♪ 让我掉下眼泪的不止昨夜的酒 ♪", 0.9999999),
            BiliSubtitleCue(67_300L, 74_820L, "♪ 让我依依不舍的不止你的温柔 ♪", 0.9999999),
        )

        val lrc = BiliSubtitle.lyricLrc(cues)

        assertEquals(
            "[00:59.46]让我掉下眼泪的不止昨夜的酒\n" +
                "[01:07.30]让我依依不舍的不止你的温柔\n",
            lrc
        )
        assertFalse("♪ 不得留在歌词正文里", lrc.contains("♪"))
    }

    @Test
    fun `生成的 LRC 能被既有解析器读回且行数一致`() {
        // 这是本模块与 LrcParser 之间的**接口契约**：生成的东西必须能被既有解析器吃下。
        val cues = (1..20).map {
            BiliSubtitleCue(it * 3_000L, it * 3_000L + 2_500L, "♪ 第${it}句歌词 ♪", 1.0)
        }

        val lrc = BiliSubtitle.lyricLrc(cues)
        val lines = LrcParser.parse(lrc)

        assertEquals("20 条字幕必须解析出 20 行歌词", 20, lines.size)
        assertEquals(3_000L, lines[0].timeMs)
        assertEquals("第1句歌词", lines[0].text)
        assertEquals(60_000L, lines[19].timeMs)
        assertEquals("第20句歌词", lines[19].text)
        assertTrue("解析结果必须严格升序", lines.zipWithNext().all { (a, b) -> a.timeMs < b.timeMs })
    }

    @Test
    fun `正文里含换行时拆成同时间戳的多行`() {
        // AI 字幕偶有把两句塞进一条的情况。拆开而不是丢弃 ——
        // 这正好用上 v3.3.0 在 LrcParser 修好的「一行多时间戳」的逆形态。
        val cues = listOf(BiliSubtitleCue(5_000L, 9_000L, "♪ 第一句\n第二句 ♪", 1.0))

        val lrc = BiliSubtitle.lyricLrc(cues)

        assertEquals("[00:05.00]第一句\n[00:05.00]第二句\n", lrc)
        assertEquals("两条同刻歌词都要被解析出来", 2, LrcParser.parse(lrc).size)
    }

    @Test
    fun `纯标记条目在转 LRC 时被丢弃`() {
        val cues = listOf(
            BiliSubtitleCue(1_000L, 2_000L, "♪", 1.0),
            BiliSubtitleCue(3_000L, 4_000L, "♪ 有内容 ♪", 1.0),
        )

        assertEquals("[00:03.00]有内容\n", BiliSubtitle.lyricLrc(cues))
    }

    @Test
    fun `时间戳格式覆盖超过一小时的现场曲目`() {
        // 78 分 30 秒。分钟数不得被截断成两位。
        assertEquals("[78:30.00]", BiliSubtitle.formatStamp(78 * 60_000L + 30_000L))
        assertEquals("[00:00.00]", BiliSubtitle.formatStamp(0L))
        assertEquals("[00:00.01]", BiliSubtitle.formatStamp(10L))
        assertEquals("[01:00.00]", BiliSubtitle.formatStamp(60_000L))
    }

    @Test
    fun `负时间戳被夹到零而不是产生负数分钟`() {
        assertEquals("[00:00.00]", BiliSubtitle.formatStamp(-5_000L))
    }

    @Test
    fun `剥离标记时保留正文内部空格`() {
        // 「让我 掉下 眼泪」这类词间空格是内容，不能被吃掉
        // （v1.5.0 为 yrc 与 lrc 的空格差异踩过一次，整首放弃逐字）。
        assertEquals("让我 掉下 眼泪的", BiliSubtitle.stripNoteMark("♪ 让我 掉下 眼泪的 ♪"))
        // 正文**中间**的标记也剥掉，但不引入新的空白塌陷：
        // 标记两侧原有的空格原样留下（这里剥完是 "头  尾"，两个空格 = 标记占位留下的）。
        // 断言写成实际语义而不是我最初以为的 "头 尾" —— 后者是我凭直觉写错的期望值。
        assertEquals("头  尾", BiliSubtitle.stripNoteMark("头 ♪ 尾"))
        assertEquals("", BiliSubtitle.stripNoteMark("♪ ♪"))
    }

    @Test
    fun `字幕 URL 的协议相对形状被补成 https`() {
        // 实测原文（截断）：服务端给的是 `//` 开头的协议相对 URL。
        // 不补协议直接交给 OkHttp ⇒ IllegalArgumentException: Expected URL scheme（真机崩溃形状）。
        val real = "//aisubtitle.hdslb.com/bfs/ai_subtitle/prod/8778697541381738635" +
            "2f726d37542008f41121f8243de3e022?auth_key=1791034834-4edf0f5dd4f24848a21ec77225365f9c-0-7bfc700a76bb4d1ea6f9227465a274e6"

        assertEquals("https:$real", BiliSubtitle.normalizeSubtitleUrl(real))
    }

    @Test
    fun `字幕 URL 补全的其它形状与拒绝形状`() {
        // 已经是绝对 URL ⇒ 原样返回
        assertEquals(
            "https://aisubtitle.hdslb.com/x.json",
            BiliSubtitle.normalizeSubtitleUrl("https://aisubtitle.hdslb.com/x.json"),
        )
        assertEquals(
            "http://example.com/x.json",
            BiliSubtitle.normalizeSubtitleUrl("http://example.com/x.json"),
        )
        // 裸路径 ⇒ 按字幕 CDN 补全
        assertEquals(
            "https://aisubtitle.hdslb.com/bfs/ai_subtitle/x.json",
            BiliSubtitle.normalizeSubtitleUrl("/bfs/ai_subtitle/x.json"),
        )
        // 两侧空白被 trim
        assertEquals(
            "https://aisubtitle.hdslb.com/x.json",
            BiliSubtitle.normalizeSubtitleUrl("  //aisubtitle.hdslb.com/x.json  "),
        )
        // 不认识的形状一律空串 —— **不猜 host**，猜错就是一个静默 404
        assertEquals("", BiliSubtitle.normalizeSubtitleUrl("aisubtitle.hdslb.com/x.json"))
        assertEquals("", BiliSubtitle.normalizeSubtitleUrl("javascript:alert(1)"))
        assertEquals("", BiliSubtitle.normalizeSubtitleUrl(null))
        assertEquals("", BiliSubtitle.normalizeSubtitleUrl(""))
        assertEquals("", BiliSubtitle.normalizeSubtitleUrl("   "))
    }
}
