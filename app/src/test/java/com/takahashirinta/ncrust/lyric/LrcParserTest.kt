package com.takahashirinta.ncrust.lyric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LrcParser] 的行为契约。
 *
 * 本文件是 v3.3.0 补的**回归保护**：这个解析器从上游起就存在，却一直没有单测，
 * 于是「一行多时间戳」这个形态被漏了整整一条产品线（B 站音源的《成都》是最早
 * 被抓到的实例，但 ncm / QQ 的 LRC 同样会走到这条路径）。
 *
 * ## 被钉住的缺陷（修复前）
 *
 * 旧实现用 `regex.find(line)`，**只取行首第一个时间戳**，并把之后的
 * `[02:44.32]` 原样留在歌词正文里。实测样本（B 站音频区 au39《成都》）：
 *
 * ```
 * [01:06.45][02:44.32]分别总是在九月
 * [01:37.57][03:15.56][03:50.79][04:06.51][04:47.01]和我在成都的街头走一走
 * ```
 *
 * 22 行里 13 行是多戳、共 44 个时间点 ⇒ 旧实现只产出 22 条、**丢掉 21 个时间点**，
 * 且每条多戳行的文本都带 `[02:44.32]` 字面量。用户可见的两个症状：
 * ① 屏上出现方括号；② 副歌在 02:44 / 03:15 / 03:50 / 04:47 整段消失。
 *
 * ## 判据（本文件断言的就是这些）
 *
 * 1. 一行里的**每一个**时间戳都产出一条 [LrcLine]；
 * 2. 产出的文本里**不含**时间戳字面量；
 * 3. 纯时间戳行（`[00:10.00]` 后面没字）**不产出**条目 —— 旧实现也跳过它，这是既有语义；
 * 4. 文本为空 ⇒ 不产出（旧语义：`if (text.isNotEmpty())`）；
 * 5. 结果按时间升序（多戳行展开后天然乱序，必须重排）；
 * 6. 2 位厘秒 `[00:33.26]` 与 3 位毫秒 `[00:33.260]` 都要正确。
 */
class LrcParserTest {

    // ---------- 1 / 2：多时间戳行的核心契约 ----------

    @Test
    fun `一行多时间戳 每一个时间戳都产出一条且文本不含方括号`() {
        val lines = LrcParser.parse("[01:06.45][02:44.32]分别总是在九月")

        assertEquals("两个时间戳应产出两条", 2, lines.size)
        assertEquals(66_450L, lines[0].timeMs)
        assertEquals(164_320L, lines[1].timeMs)
        assertTrue(
            "文本不得带时间戳字面量，实际=${lines.map { it.text }}",
            lines.all { it.text == "分别总是在九月" && !it.text.contains("[") }
        )
    }

    @Test
    fun `五个时间戳的副歌行 五个时间点全部保留`() {
        // 真实样本里最长的一条（B 站 au39《成都》）
        val lines = LrcParser.parse(
            "[01:37.57][03:15.56][03:50.79][04:06.51][04:47.01]和我在成都的街头走一走"
        )

        assertEquals("五个时间戳应产出五条", 5, lines.size)
        assertEquals(listOf(97_570L, 195_560L, 230_790L, 246_510L, 287_010L), lines.map { it.timeMs })
        assertTrue("五条文本应完全一致且无方括号", lines.all { it.text == "和我在成都的街头走一走" })
    }

    @Test
    fun `真实样本成都 不丢时间点也不留方括号`() {
        // 取自本仓库 docs/verification/v3.1.0/bili-research/evidence/21-lyric-au39.txt 的
        // `data` 字段正文（逐字复制，含全部多戳行）。22 行 / 44 个时间戳。
        val raw = """
            [00:33.26]让我掉下眼泪的
            [00:36.93]不止昨夜的酒
            [00:40.71]让我依依不舍的
            [00:44.47]不止你的温柔
            [00:48.82]余路还要走多久
            [00:52.88]你攥着我的手
            [00:56.63]让我感到为难的
            [01:00.43]是挣扎的自由
            [01:06.45][02:44.32]分别总是在九月
            [01:10.33][02:48.29]回忆是思念的愁
            [01:14.27][02:52.13]深秋嫩绿的垂柳
            [01:17.97][02:55.92]亲吻着我额头
            [01:22.23][02:59.86]在那座阴雨的小城里
            [01:26.12][03:03.77]我从未忘记你
            [01:30.02][03:07.75]成都带不走的只有你
            [01:37.57][03:15.56][03:50.79][04:06.51][04:47.01]和我在成都的街头走一走
            [01:45.64][03:23.28][03:58.73][04:14.28][04:55.41]直到所有的灯都熄灭了也不停留
            [01:53.48][03:31.24][04:22.21]你会挽着我的衣袖
            [01:57.15][03:35.03][04:25.96]我会把手揣进裤兜
            [02:01.15][03:38.99][04:29.78]走到玉林路的尽头
            [02:05.04][03:42.81]坐在小酒馆的门口
            [04:33.79]走过小酒馆的门口
        """.trimIndent()

        val lines = LrcParser.parse(raw)

        // 44 个时间戳，全部有文本 ⇒ 44 条。旧实现只给 22 条。
        assertEquals("44 个时间点应全部保留", 44, lines.size)
        assertTrue(
            "不得有任何一行的文本带时间戳字面量",
            lines.none { it.text.contains("[") || it.text.contains("]") }
        )
        // 副歌第二遍（02:44.32）必须在，且文本正确 —— 这正是旧实现丢掉的那一段
        val chorus2 = lines.single { it.timeMs == 164_320L }
        assertEquals("分别总是在九月", chorus2.text)
        // 严格升序
        assertEquals(
            "结果必须按时间升序",
            lines.map { it.timeMs }.sorted(),
            lines.map { it.timeMs }
        )
    }

    // ---------- 3：纯时间戳行不产出（既有语义） ----------

    @Test
    fun `纯时间戳行不产出条目`() {
        val lines = LrcParser.parse("[00:10.00]\n[00:20.00]有词的\n[00:30.00]")

        assertEquals("只有中间那条有文本", 1, lines.size)
        assertEquals(20_000L, lines[0].timeMs)
        assertEquals("有词的", lines[0].text)
    }

    @Test
    fun `空白文本行不产出条目`() {
        // 文本是空格 ⇒ trim 后为空 ⇒ 跳过（既有语义）
        val lines = LrcParser.parse("[00:10.00]   \n[00:20.00]正常")

        assertEquals(1, lines.size)
        assertEquals("正常", lines[0].text)
    }

    // ---------- 4：时间格式 ----------

    @Test
    fun `两位厘秒按十毫秒解释`() {
        val lines = LrcParser.parse("[00:33.26]两位\n[00:33.260]三位")

        assertEquals(33_260L, lines[0].timeMs)
        assertEquals(33_260L, lines[1].timeMs)
    }

    @Test
    fun `超过一小时的分钟数不溢出`() {
        // 78 分钟 = 1 小时 18 分。ncm 的现场曲目会超过 60 分钟。
        val lines = LrcParser.parse("[78:30.00]很长的现场")

        assertEquals(78 * 60_000L + 30_000L, lines[0].timeMs)
    }

    // ---------- 5：元信息行与容错 ----------

    @Test
    fun `元信息行被忽略`() {
        val lines = LrcParser.parse(
            "[ti:成都]\n[ar:赵雷]\n[by:someone]\n[offset:0]\n[00:33.26]让我掉下眼泪的"
        )

        assertEquals("四行元信息都不该产出条目", 1, lines.size)
        assertEquals("让我掉下眼泪的", lines[0].text)
    }

    @Test
    fun `无时间戳的噪声行被忽略`() {
        val lines = LrcParser.parse("纯粹的一行说明\n[00:33.26]正常\n[不合法]也跳过")

        assertEquals(1, lines.size)
        assertEquals("正常", lines[0].text)
    }

    @Test
    fun `空输入返回空表`() {
        assertTrue(LrcParser.parse("").isEmpty())
    }

    @Test
    fun `时间戳之间夹空格也能解析`() {
        // 有些来源写 `[01:06.45] [02:44.32] 文本`（戳之间带空格）
        val lines = LrcParser.parse("[01:06.45] [02:44.32] 分别总是在九月")

        assertEquals(2, lines.size)
        assertTrue(lines.all { it.text == "分别总是在九月" })
    }

    // ---------- 6：拆分不得影响统一时间戳行 ----------

    @Test
    fun `统一时间戳行行为与旧实现逐字节一致`() {
        val lines = LrcParser.parse("[00:33.26]让我掉下眼泪的\n[00:36.93]不止昨夜的酒")

        assertEquals(2, lines.size)
        assertEquals(33_260L, lines[0].timeMs)
        assertEquals("让我掉下眼泪的", lines[0].text)
        assertEquals(36_930L, lines[1].timeMs)
        assertEquals("不止昨夜的酒", lines[1].text)
        // LRC 解析结果的 endMs 恒为 null（只有 TTML 会填）
        assertTrue("LRC 行的 endMs 应为 null", lines.all { it.endMs == null })
        assertTrue("LRC 行没有逐字数据", lines.all { it.words.isEmpty() })
    }

    @Test
    fun `同一时间戳重复出现时各产出一条`() {
        // 多戳行展开后可能与别的行撞同一时刻，不去重（时间轴允许同刻多条）
        val lines = LrcParser.parse("[00:10.00][00:20.00]甲\n[00:20.00]乙")

        assertEquals(3, lines.size)
        assertEquals(listOf(10_000L, 20_000L, 20_000L), lines.map { it.timeMs })
    }
}
