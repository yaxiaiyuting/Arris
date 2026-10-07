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

/**
 * v3.4.8 · 问题 2：**字幕语言的选择**（视频轨的「多语言歌词」）。
 *
 * ## 样本是实测原文
 *
 * `BV1GJ411x7h7`（cid 137649199）在 2026-10-07 登录态下 `player/wbi/v2` 返回
 * **12 条**语言轨；`BV1uT4y1P7CX`（cid 287639008）返回 **60 条**。
 * 这里抄的是前者的 12 条（字段名、`lan`、`lan_doc`、`type` 逐字保留，
 * 只把 `subtitle_url` 的签名查询串截掉）。
 *
 * 修复前 `parseFirstSubtitleUrl` 写死「**优先含 `zh` 的那条**」——
 * 一个日语视频、一个英语视频、一个想在中文视频上看英文字幕的用户，
 * 全都只能拿到中文轨，且**没有任何开关**。这一组用例守的就是那条开关。
 */
class BiliSubtitleLangTest {

    private companion object {
        /** 实测：`BV1GJ411x7h7` / cid 137649199，12 条语言轨。 */
        val TWELVE_TRACKS = """
        {"code":0,"message":"OK","data":{"subtitle":{"allow_submit":false,"subtitles":[
          {"id":1,"lan":"zh-CN","lan_doc":"中文（中国）","type":0,"ai_type":0,"subtitle_url":"//aisubtitle.hdslb.com/bfs/subtitle/zh-CN.json"},
          {"id":2,"lan":"zh-Hans","lan_doc":"中文（简体）","type":0,"ai_type":0,"subtitle_url":"//aisubtitle.hdslb.com/bfs/subtitle/zh-Hans.json"},
          {"id":3,"lan":"zh-Hant","lan_doc":"中文（繁體）","type":0,"ai_type":0,"subtitle_url":"//aisubtitle.hdslb.com/bfs/subtitle/zh-Hant.json"},
          {"id":4,"lan":"zh-HK","lan_doc":"中文（中國香港）","type":0,"ai_type":0,"subtitle_url":"//aisubtitle.hdslb.com/bfs/subtitle/zh-HK.json"},
          {"id":5,"lan":"en-US","lan_doc":"English(US)","type":0,"ai_type":0,"subtitle_url":"//aisubtitle.hdslb.com/bfs/subtitle/en-US.json"},
          {"id":6,"lan":"ja","lan_doc":"日本語","type":0,"ai_type":0,"subtitle_url":"//aisubtitle.hdslb.com/bfs/subtitle/ja.json"},
          {"id":7,"lan":"ko","lan_doc":"한국어","type":0,"ai_type":0,"subtitle_url":"//aisubtitle.hdslb.com/bfs/subtitle/ko.json"},
          {"id":8,"lan":"de-DE","lan_doc":"Deutsch(DE)","type":0,"ai_type":0,"subtitle_url":"//aisubtitle.hdslb.com/bfs/subtitle/de-DE.json"},
          {"id":9,"lan":"ru","lan_doc":"Русский","type":0,"ai_type":0,"subtitle_url":"//aisubtitle.hdslb.com/bfs/subtitle/ru.json"},
          {"id":10,"lan":"iw","lan_doc":"עִבְרִית","type":0,"ai_type":0,"subtitle_url":"//aisubtitle.hdslb.com/bfs/subtitle/iw.json"},
          {"id":11,"lan":"ca","lan_doc":"Català","type":0,"ai_type":0,"subtitle_url":"//aisubtitle.hdslb.com/bfs/subtitle/ca.json"},
          {"id":12,"lan":"ase","lan_doc":"美国手语","type":0,"ai_type":0,"subtitle_url":"//aisubtitle.hdslb.com/bfs/subtitle/ase.json"}
        ]}}}
        """.trimIndent()

        /** 实测：只有 AI 轨的视频（`BV1cN4y167BJ`）。`lan` 带 `ai-` 前缀、`type=1`。 */
        val AI_ONLY = """
        {"code":0,"data":{"subtitle":{"subtitles":[
          {"id":1,"lan":"ai-zh","lan_doc":"中文","type":1,"ai_type":0,"subtitle_url":"//aisubtitle.hdslb.com/bfs/ai_subtitle/prod/x.json"}
        ]}}}
        """.trimIndent()

        fun urlOf(lan: String) = "//aisubtitle.hdslb.com/bfs/subtitle/$lan.json"
    }

    // ---------------------------------------------------------------- 解析

    @Test
    fun `解析出全部 12 条语言轨且顺序不变`() {
        val tracks = BiliSubtitle.parseSubtitleTracks(TWELVE_TRACKS)
        assertEquals(12, tracks.size)
        assertEquals(
            listOf("zh-CN", "zh-Hans", "zh-Hant", "zh-HK", "en-US", "ja", "ko", "de-DE", "ru", "iw", "ca", "ase"),
            tracks.map { it.lan },
        )
        assertEquals("中文（中国）", tracks[0].lanDoc)
        assertEquals("English(US)", tracks[4].lanDoc)
        assertTrue("手传 CC 轨不是 AI 轨", tracks.none { it.isAi })
    }

    @Test
    fun `AI 轨的两个判据取或 —— lan 前缀与 type 各能单独认出来`() {
        val t = BiliSubtitle.parseSubtitleTracks(AI_ONLY).single()
        assertTrue(t.isAi)
        assertEquals("ai-zh", t.lan)
        // 缺 `lan` 前缀但 `type=1` 也要认（服务端改字段时不能静默失效）。
        val byType = BiliSubtitle.parseSubtitleTracks(
            """{"code":0,"data":{"subtitle":{"subtitles":[{"lan":"zh","type":1,"subtitle_url":"//x/a.json"}]}}}"""
        ).single()
        assertTrue(byType.isAi)
        // 反过来：有 `ai-` 前缀但 `type` 缺失（optInt 给 0）也要认。
        val byLan = BiliSubtitle.parseSubtitleTracks(
            """{"code":0,"data":{"subtitle":{"subtitles":[{"lan":"ai-en","subtitle_url":"//x/a.json"}]}}}"""
        ).single()
        assertTrue(byLan.isAi)
    }

    @Test
    fun `没有 URL 或没有语言的条目被丢掉`() {
        val tracks = BiliSubtitle.parseSubtitleTracks(
            """{"code":0,"data":{"subtitle":{"subtitles":[
              {"lan":"en-US","lan_doc":"English","subtitle_url":""},
              {"lan":"","lan_doc":"?","subtitle_url":"//x/a.json"},
              {"lan":"ja","lan_doc":"日本語","subtitle_url":"//x/ja.json"}
            ]}}}"""
        )
        assertEquals("只有第三条是有效轨", 1, tracks.size)
        assertEquals("ja", tracks.single().lan)
    }

    @Test
    fun `坏输入返回空表而不抛异常`() {
        assertTrue(BiliSubtitle.parseSubtitleTracks(null).isEmpty())
        assertTrue(BiliSubtitle.parseSubtitleTracks("").isEmpty())
        assertTrue(BiliSubtitle.parseSubtitleTracks("not json").isEmpty())
        assertTrue(BiliSubtitle.parseSubtitleTracks("""{"code":-352}""").isEmpty())
        assertTrue(BiliSubtitle.parseSubtitleTracks("""{"code":0,"data":{}}""").isEmpty())
        assertTrue(BiliSubtitle.parseSubtitleTracks("""{"code":0,"data":{"subtitle":{"subtitles":[]}}}""").isEmpty())
    }

    // ---------------------------------------------------------------- 偏好展开

    @Test
    fun `自动档跟随应用语言 其次中文 再其次英文`() {
        // 界面语言是英文 ⇒ 先找英文轨，再去重保序地接上中文兜底。
        // （`preferredTags` 用 `LinkedHashSet` 去重：`en-US` 既是应用语言的首选、
        //   又在兜底清单里，**只能出现一次** —— 否则匹配循环会白跑一遍。）
        assertEquals(
            listOf("en-US", "en-GB", "en", "zh-CN", "zh-Hans", "zh"),
            BiliSubtitle.preferredTags(BiliSubtitleLang.AUTO, "en-US"),
        )
        val tags = BiliSubtitle.preferredTags(BiliSubtitleLang.AUTO, "en-US")
        assertEquals("偏好表必须无重复", tags.size, tags.toSet().size)
        // 界面语言是日语 ⇒ 日语优先，但中文仍在兜底里（B 站是中文内容平台）。
        val ja = BiliSubtitle.preferredTags(BiliSubtitleLang.AUTO, "ja-JP")
        assertEquals("ja", ja.first())
        assertTrue("中文必须留在兜底里", ja.contains("zh-CN"))
    }

    @Test
    fun `自动档区分繁简 —— 台湾正体优先繁体轨`() {
        val tw = BiliSubtitle.preferredTags(BiliSubtitleLang.AUTO, "zh-TW")
        assertEquals("zh-Hant", tw.first())
        val cn = BiliSubtitle.preferredTags(BiliSubtitleLang.AUTO, "zh-CN")
        assertEquals("zh-CN", cn.first())
        // 万叶假名是 ja 的变体，按 ja 处理。
        assertEquals("ja", BiliSubtitle.preferredTags(BiliSubtitleLang.AUTO, "ja-MY").first())
    }

    @Test
    fun `应用语言取不到时退化成修复前的行为（中文优先）`() {
        // 这是升级上来的老用户看到的默认观感：与 v3.4.7 **逐字一致**。
        listOf(null, "", "   ", "fr-FR").forEach { code ->
            assertEquals(
                "appLanguage=$code",
                "zh-CN",
                BiliSubtitle.preferredTags(BiliSubtitleLang.AUTO, code).first(),
            )
        }
    }

    @Test
    fun `关闭档返回空表 且 fetches 为假`() {
        assertTrue(BiliSubtitle.preferredTags(BiliSubtitleLang.OFF, "zh-CN").isEmpty())
        assertFalse(BiliSubtitleLang.OFF.fetches)
        BiliSubtitleLang.values().filter { it != BiliSubtitleLang.OFF }.forEach {
            assertTrue("${it.key} 必须抓取", it.fetches)
        }
    }

    @Test
    fun `枚举 key 与设置注册表的候选逐字一致`() {
        assertEquals(
            listOf("auto", "zh-Hans", "zh-Hant", "en", "ja", "ko", "off"),
            BiliSubtitleLang.values().map { it.key },
        )
        assertEquals(BiliSubtitleLang.ZH_HANT, BiliSubtitleLang.of("zh-Hant"))
        assertEquals(BiliSubtitleLang.OFF, BiliSubtitleLang.of(" OFF "))
        assertEquals(BiliSubtitleLang.AUTO, BiliSubtitleLang.DEFAULT)
        org.junit.Assert.assertNull(BiliSubtitleLang.of("fr"))
        org.junit.Assert.assertNull(BiliSubtitleLang.of(null))
    }

    // ---------------------------------------------------------------- 选择

    private fun pick(lang: BiliSubtitleLang, app: String? = "zh-CN", allowAi: Boolean = true) =
        BiliSubtitle.pickTrack(
            BiliSubtitle.parseSubtitleTracks(TWELVE_TRACKS),
            BiliSubtitle.preferredTags(lang, app),
            allowAi,
        )

    @Test
    fun `每一档都真的选到对应语言的轨`() {
        assertEquals("zh-CN", pick(BiliSubtitleLang.ZH_HANS)?.lan)
        assertEquals("zh-Hant", pick(BiliSubtitleLang.ZH_HANT)?.lan)
        assertEquals("en-US", pick(BiliSubtitleLang.EN)?.lan)
        assertEquals("ja", pick(BiliSubtitleLang.JA)?.lan)
        assertEquals("ko", pick(BiliSubtitleLang.KO)?.lan)
    }

    @Test
    fun `自动档在不同界面语言下选到不同的轨 —— 这就是这次修复的可见效果`() {
        assertEquals("zh-CN", pick(BiliSubtitleLang.AUTO, "zh-CN")?.lan)
        assertEquals("zh-Hant", pick(BiliSubtitleLang.AUTO, "zh-TW")?.lan)
        assertEquals("en-US", pick(BiliSubtitleLang.AUTO, "en-US")?.lan)
        assertEquals("ja", pick(BiliSubtitleLang.AUTO, "ja-JP")?.lan)
        assertEquals("ko", pick(BiliSubtitleLang.AUTO, "ko-KP")?.lan)
        assertEquals("de-DE", pick(BiliSubtitleLang.AUTO, "de-DE")?.lan)
        assertEquals("ru", pick(BiliSubtitleLang.AUTO, "ru-RU")?.lan)
    }

    @Test
    fun `主语言子标签也能命中 —— 服务端只给 en 时 en-US 的偏好要能选到它`() {
        val onlyEn = BiliSubtitle.parseSubtitleTracks(
            """{"code":0,"data":{"subtitle":{"subtitles":[
              {"lan":"zh-CN","subtitle_url":"//x/zh.json"},
              {"lan":"en","subtitle_url":"//x/en.json"}
            ]}}}"""
        )
        val picked = BiliSubtitle.pickTrack(onlyEn, BiliSubtitle.preferredTags(BiliSubtitleLang.EN, null))
        assertEquals("en", picked?.lan)
    }

    @Test
    fun `偏好全不命中时回落到第一条手传轨 而不是空手而归`() {
        // 界面语言是法语（应用里没有这一档）⇒ AUTO 展开成中文优先。
        // 若这个视频只有手语轨，AUTO 仍然应该给出**某一条**，而不是「没有字幕」。
        val aseOnly = BiliSubtitle.parseSubtitleTracks(
            """{"code":0,"data":{"subtitle":{"subtitles":[
              {"lan":"ase","lan_doc":"美国手语","type":0,"subtitle_url":"//x/ase.json"}
            ]}}}"""
        )
        val picked = BiliSubtitle.pickTrack(aseOnly, BiliSubtitle.preferredTags(BiliSubtitleLang.EN, null))
        assertEquals("ase", picked?.lan)
    }

    @Test
    fun `AI 轨优先让位给手传轨 但只有 AI 时仍然用它`() {
        val mixed = BiliSubtitle.parseSubtitleTracks(
            """{"code":0,"data":{"subtitle":{"subtitles":[
              {"lan":"ai-zh","lan_doc":"中文","type":1,"subtitle_url":"//x/ai.json"},
              {"lan":"fr","lan_doc":"Français","type":0,"subtitle_url":"//x/fr.json"}
            ]}}}"""
        )
        // 偏好里没有 fr ⇒ 兜底应先给手传轨（fr），而不是按顺序给 ai-zh。
        assertEquals(
            "兜底必须先给手传 CC 轨",
            "fr",
            BiliSubtitle.pickTrack(mixed, BiliSubtitle.preferredTags(BiliSubtitleLang.JA, null))?.lan,
        )
        // 只有 AI 轨时仍然用它 —— 实测 BV1cN4y167BJ 就是这种形态，而它的正文就是完整歌词。
        val aiOnly = BiliSubtitle.parseSubtitleTracks(AI_ONLY)
        assertEquals(
            "ai-zh",
            BiliSubtitle.pickTrack(aiOnly, BiliSubtitle.preferredTags(BiliSubtitleLang.JA, null))?.lan,
        )
        // allowAi=false 时**才**放弃它。
        org.junit.Assert.assertNull(
            BiliSubtitle.pickTrack(aiOnly, BiliSubtitle.preferredTags(BiliSubtitleLang.JA, null), allowAi = false),
        )
    }

    @Test
    fun `显式点名 AI 轨不受 allowAi 开关影响`() {
        val aiOnly = BiliSubtitle.parseSubtitleTracks(AI_ONLY)
        assertEquals(
            "用户显式要 ai-zh 时不能被隐式开关拒掉",
            "ai-zh",
            BiliSubtitle.pickTrack(aiOnly, listOf("ai-zh"), allowAi = false)?.lan,
        )
    }

    @Test
    fun `空轨表与空偏好都不抛`() {
        org.junit.Assert.assertNull(BiliSubtitle.pickTrack(emptyList(), listOf("zh-CN")))
        assertEquals(
            "空偏好 = 按服务端顺序取第一条手传轨（不是不抓）",
            "zh-CN",
            BiliSubtitle.pickTrack(BiliSubtitle.parseSubtitleTracks(TWELVE_TRACKS), emptyList())?.lan,
        )
    }
}
