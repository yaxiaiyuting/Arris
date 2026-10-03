/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LyricSubtitleText] 的单测（v1.9.3）。
 *
 * 前半是纯规则用例，后半是**真实样本端到端**：拿 v1.9.2 那 4 份真实夹具
 * （`app/src/test/resources/lyric-tracks/`）跑一遍 [LyricTrackMerge.merge]，
 * 再把合并结果按渲染层的取用方式（timeMs 配对）过一遍 [LyricSubtitleText.visibleRomanization]。
 * 也就是说：这些用例断言的就是「开关打开后到底会多出几行」，不是估的。
 */
class LyricSubtitleTextTest {

    // ------------------------------------------------------------ 纯规则

    @Test
    fun `关掉开关_任何输入都返回空串_渲染路径与 v1_9_2 逐字节一致`() {
        assertEquals("", LyricSubtitleText.visibleRomanization("紫荆花飘扬", "zi ging fa piu yoeng", show = false))
        assertEquals("", LyricSubtitleText.visibleRomanization("hello", "he llo", show = false))
        assertEquals("", LyricSubtitleText.visibleRomanization("", "anything", show = false))
    }

    @Test
    fun `纯空白音译不占行_无音译的歌打开开关也不会多出空行`() {
        assertEquals("", LyricSubtitleText.visibleRomanization("hello", "", show = true))
        assertEquals("", LyricSubtitleText.visibleRomanization("hello", "   ", show = true))
        assertEquals("", LyricSubtitleText.visibleRomanization("hello", "\t\n ", show = true))
    }

    @Test
    fun `与原文相同的音译不显示_英文歌的 romalrc 等于原文时不重复一遍`() {
        assertEquals("", LyricSubtitleText.visibleRomanization("Hello from the other side", "Hello from the other side", show = true))
        assertEquals("", LyricSubtitleText.visibleRomanization("Hello", "  Hello  ", show = true))
        // 只是「像」还不够，必须逐字相同才丢
        assertTrue(LyricSubtitleText.visibleRomanization("Hello", "Hello!", show = true).isNotEmpty())
    }

    @Test
    fun `正常音译原样返回_不做 trim 不改写数据`() {
        assertEquals("zi ging fa piu yoeng", LyricSubtitleText.visibleRomanization("紫荆花飘扬", "zi ging fa piu yoeng", show = true))
        assertEquals(" nae pu meun", LyricSubtitleText.visibleRomanization("내 품은", " nae pu meun", show = true))
    }

    @Test
    fun `副文本顺序_译文在上音译在下_且不含空元素`() {
        assertEquals(listOf("我的怀抱是你的避难所", "nae pu meun"), LyricSubtitleText.subtitleLines("我的怀抱是你的避难所", "nae pu meun"))
        assertEquals(listOf("zi ging fa piu yoeng"), LyricSubtitleText.subtitleLines("", "zi ging fa piu yoeng"))
        assertEquals(listOf("我的怀抱是你的避难所"), LyricSubtitleText.subtitleLines("我的怀抱是你的避难所", ""))
        assertEquals(emptyList<String>(), LyricSubtitleText.subtitleLines("", ""))
        assertEquals(emptyList<String>(), LyricSubtitleText.subtitleLines("  ", "\n"))
        assertTrue(LyricSubtitleText.subtitleLines("a", "b").size <= LyricSubtitleText.MAX_SUBTITLES)
    }

    @Test
    fun `a11yText_音译为空时与 v1_9_2 的表达式逐字节一致`() {
        // v1.9.2 的表达式：if (translation.isEmpty()) text else "text\ntranslation"
        assertEquals("紫荆花飘扬", LyricSubtitleText.a11yText("紫荆花飘扬", "", ""))
        assertEquals("hello\n你好", LyricSubtitleText.a11yText("hello", "你好", ""))
        // 开了音译才多一行，且排在译文之后
        assertEquals("hello\n你好\nni hao", LyricSubtitleText.a11yText("hello", "你好", "ni hao"))
        assertEquals("hello\nni hao", LyricSubtitleText.a11yText("hello", "", "ni hao"))
    }

    // ------------------------------------------------- 真实样本端到端

    private data class Row(val t: Long = 0L, val x: String = "")
    private data class Fixture(
        val main: List<Row> = emptyList(),
        val ttml: List<Row> = emptyList(),
        val netmain: List<Row> = emptyList(),
        val nettrackTlyric: List<Row>? = null,
        val nettrackRomalrc: List<Row>? = null,
    )

    private fun load(songId: Long): Fixture {
        val text = javaClass.getResourceAsStream("/lyric-tracks/" + songId + ".json")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        return Gson().fromJson(text, Fixture::class.java)
    }

    private fun List<Row>.lines(): List<LrcLine> = map { LrcLine(it.t, it.x) }

    /**
     * 夹具里只有一个 `ttml` 槽：它装的是**这次要测的那条副文本轨**——
     * 1959528822 装的是 16 行 x-roman（note 里写明），其余三首装的是译文。
     * 所以要复现「渲染层拿到的音译轨」，只有这一首该把 ttml 槽当音译传进去；
     * 另三首的 TTML 没有音译数据，音译轨只能来自 ncm romalrc（Faded / Numb 连这个 key 都没有）。
     */
    private fun ttmlRomanTrackFor(songId: Long): List<Row> =
        if (songId == 1959528822L) load(songId).ttml else emptyList()

    /** 合并后的音译轨，按渲染层的取用方式（timeMs）建索引。 */
    private fun romanByTime(songId: Long): Pair<List<LrcLine>, Map<Long, String>> {
        val f = load(songId)
        val track = LyricTrackMerge.merge(
            f.main.lines(),
            ttmlRomanTrackFor(songId).lines(),
            f.netmain.lines(),
            f.nettrackRomalrc?.lines() ?: emptyList(),
        )
        return f.main.lines() to track.lines.associate { it.timeMs to it.text }
    }

    /** 开关打开时，这首歌实际会多渲染多少行音译。 */
    private fun visibleCount(rows: List<LrcLine>, roman: Map<Long, String>, show: Boolean = true): Int =
        rows.count { LyricSubtitleText.visibleRomanization(it.text, roman[it.timeMs] ?: "", show).isNotEmpty() }

    @Test
    fun `真实——1959528822 紫荆花盛开：开启后 29 行粤拼全部可见，关闭后 0 行`() {
        val (main, roman) = romanByTime(1959528822L)
        assertEquals("夹具前提：合并后音译轨 29 行（TTML 16 + ncm 补 13）", 29, roman.size)
        assertEquals("29 行粤拼都不与中文原文相同，全部可见", 29, visibleCount(main, roman))
        assertEquals("关掉开关必须一行都不多", 0, visibleCount(main, roman, show = false))
    }

    @Test
    fun `真实——22704409 DAY BY DAY：开启后 52 行韩文罗马音全部可见，关闭后 0 行`() {
        val (main, roman) = romanByTime(22704409L)
        assertEquals("夹具前提：合并后音译轨 52 行", 52, roman.size)
        assertEquals(52, visibleCount(main, roman))
        assertEquals(0, visibleCount(main, roman, show = false))
    }

    @Test
    fun `真实——36990266 Faded：无音译，开关打开后每行都没有音译副文本`() {
        val (main, roman) = romanByTime(36990266L)
        assertTrue("夹具前提：这首诗没有音译", roman.isEmpty())
        assertEquals("无音译的歌打开开关也不产生任何空行", 0, visibleCount(main, roman))
        for (row in main) {
            val visible = LyricSubtitleText.visibleRomanization(row.text, roman[row.timeMs] ?: "", show = true)
            assertEquals("", visible)
            // 副文本列表里不能出现空元素：挂上去的就是要画出来的
            for (sub in LyricSubtitleText.subtitleLines("", visible)) {
                assertTrue(sub.isNotBlank())
            }
        }
    }

    @Test
    fun `真实——16686599 Numb：有译文无音译，开音译不改变任何一行`() {
        val (main, roman) = romanByTime(16686599L)
        assertTrue("夹具前提：这首没有音译轨", roman.isEmpty())
        assertEquals(0, visibleCount(main, roman))
        // 译文槽不受影响：合并出的译文轨照常进入副文本列表，且打开音译开关也不会改变它
        val f = load(16686599L)
        val translationTrack = LyricTrackMerge.merge(
            f.main.lines(), f.ttml.lines(), f.netmain.lines(), f.nettrackTlyric!!.lines()
        )
        assertTrue("译文轨本身非空", translationTrack.lines.isNotEmpty())
        val tMap = translationTrack.lines.associate { it.timeMs to it.text }
        var checked = 0
        for (row in main) {
            val t = tMap[row.timeMs] ?: continue
            val romanVisible = LyricSubtitleText.visibleRomanization(row.text, "", show = true)
            assertEquals(
                "没有音译数据时，副文本列表只该有译文这一条",
                listOf(t),
                LyricSubtitleText.subtitleLines(t, romanVisible),
            )
            checked++
        }
        assertTrue("至少要真的核对到几行译文", checked > 0)
    }

    @Test
    fun `真实——音译轨一律落在主轨时间轴上，不会出现孤行`() {
        for (songId in listOf(1959528822L, 22704409L, 36990266L, 16686599L)) {
            val (main, roman) = romanByTime(songId)
            val times = main.mapTo(HashSet()) { it.timeMs }
            for (t in roman.keys) {
                assertTrue("song=" + songId + " 音译行 timeMs=" + t + " 不在主轨上", t in times)
            }
        }
    }
}
