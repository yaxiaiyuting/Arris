/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LyricTrackMerge] 的分轨合并单测（v1.9.2）。
 *
 * 前 5 个用例用**真实样本**（`app/src/test/resources/lyric-tracks/` 下的 4 份 json，由
 * `tools/gen-merge-fixtures.py` 从调研落盘的原始响应生成，不是手写编造）：
 * TTML 侧的行/译文/音译按 TtmlScanner 语义预先解析好，ncm 侧按 LrcParser 语义解析好 ——
 * 夹具就是两个解析器的**输出**，所以这里测的是合并本身，不重复测解析。
 *
 * 期望的行数不是估的：`tools/expected-merge.py` 用同一套语义独立复算过一遍
 * （22704409 译文 64 / 音译 52，1959528822 音译 29 = TTML 16 + ncm 补 13，Faded 54，Numb 49）。
 */
class LyricTrackMergeTest {

    // ------------------------------------------------------------------ 夹具

    private data class Row(val t: Long = 0L, val x: String = "")
    private data class Fixture(
        val songId: Long = 0L,
        val note: String = "",
        val main: List<Row> = emptyList(),
        val ttml: List<Row> = emptyList(),
        val netmain: List<Row> = emptyList(),
        val nettrackTlyric: List<Row>? = null,
        val nettrackRomalrc: List<Row>? = null,
    )

    private fun load(songId: Long): Fixture {
        val path = "/lyric-tracks/SONGID.json"
        val stream = javaClass.getResourceAsStream(path.replace("SONGID", songId.toString()))
            ?: error("夹具缺失：/lyric-tracks/ 下没有这首（应由 tools/gen-merge-fixtures.py 生成）")
        val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
        return Gson().fromJson(text, Fixture::class.java)
    }

    private fun List<Row>.toLines(): List<LrcLine> = map { LrcLine(it.t, it.x) }
    private fun List<LrcLine>.times(): Set<Long> = mapTo(HashSet()) { it.timeMs }

    /** 主轨的时间戳集合 = 渲染层能挂上副文本的全部位置。 */
    private fun assertAllOnMainTimeline(main: List<LrcLine>, track: LyricTrack) {
        val mainTimes = main.times()
        for (line in track.lines) {
            assertTrue(
                "副文本行的 timeMs=" + line.timeMs + " 不在主轨时间轴上",
                line.timeMs in mainTimes
            )
        }
        assertEquals("同一时间戳不该有两行副文本", track.lines.size, track.lines.times().size)
    }

    // ------------------------------------------------- 真实样本：缺陷的直接修复

    @Test
    fun `真实——22704409 DAY BY DAY：TTML 79 行逐字但零翻译，译文轨必须回退ncm tlyric`() {
        val f = load(22704409)
        val main = f.main.toLines()
        val tlyric = f.nettrackTlyric!!.toLines()
        assertEquals("夹具前提：TTML 这份没有 x-translation", 0, f.ttml.size)
        assertTrue("夹具前提：ncm 有译文", tlyric.isNotEmpty())

        val track = LyricTrackMerge.merge(main, f.ttml.toLines(), f.netmain.toLines(), tlyric)

        // v1.9.0 的行为是 emptyList（TTML 的 0 行译文整体覆盖），这里必须不再是空的。
        assertEquals(LyricTrackSource.NETEASE, track.source)
        assertEquals("探针实测：82 行 ncm 主轨里 64 行能按文本配上", 64, track.lines.size)
        assertAllOnMainTimeline(main, track)
        // 首行对照（两源时间轴不同：TTML 这一句起于 16690ms）
        assertEquals(16690L, track.lines.first().timeMs)
        assertEquals("我的怀抱是你的避难所", track.lines.first().text)
        // 副文本轨不带逐字（它没有词级数据，给了就是假信息）
        assertTrue(track.lines.all { it.words.isEmpty() })
    }

    @Test
    fun `真实——22704409：音译轨同样回退（TTML 无 x-roman，ncm 53 行 romalrc）`() {
        val f = load(22704409)
        val main = f.main.toLines()
        val romalrc = f.nettrackRomalrc!!.toLines()

        val track = LyricTrackMerge.merge(main, f.ttml.toLines(), f.netmain.toLines(), romalrc)

        assertEquals(LyricTrackSource.NETEASE, track.source)
        assertEquals(52, track.lines.size)
        assertAllOnMainTimeline(main, track)
        assertEquals("nae pu meun neo e ge pi nan cheo", track.lines.first().text)
    }

    @Test
    fun `真实——1959528822 紫荆花盛开：TTML 有 16 行音译时也不丢ncm那 41 行（逐行合并）`() {
        val f = load(1959528822)
        val main = f.main.toLines()
        val ttmlRomans = f.ttml.toLines()
        val romalrc = f.nettrackRomalrc!!.toLines()
        assertEquals("夹具前提：TTML 这 16 行 x-roman 是 ncm 那份的子集", 16, ttmlRomans.size)
        assertEquals(41, romalrc.size)

        val track = LyricTrackMerge.merge(main, ttmlRomans, f.netmain.toLines(), romalrc)

        // 轨级二选一（只按任务书表格字面）会只留 16 行、丢掉 13 行能对上的 ncm 音译。
        assertEquals(LyricTrackSource.MIXED, track.source)
        assertEquals("TTML 16 行 + 缺口补 13 行", 29, track.lines.size)
        assertAllOnMainTimeline(main, track)
        // TTML 那 16 行必须一行不少地原样在（含内容与时间戳）。
        val kept = track.lines.filter { it in ttmlRomans }
        assertEquals(16, kept.size)
        assertEquals(ttmlRomans, kept)
        // 补进来的行是 ncm 有、TTML 没有的那几句（例：首句 紫荆花飘扬）。
        val filled = track.lines.filter { it !in ttmlRomans }
        assertEquals(13, filled.size)
        assertTrue(
            "首句音译应当由 ncm 补上",
            filled.any { it.timeMs == 17920L && it.text == "zi ging fa piu yoeng" }
        )
    }

    @Test
    fun `真实——36990266 Faded：TTML 译文覆盖满时与旧版逐行相同（不重复、不新增）`() {
        val f = load(36990266)
        val main = f.main.toLines()
        val ttmlTranslations = f.ttml.toLines()
        val tlyric = f.nettrackTlyric!!.toLines()
        assertEquals("两源译文 54/54 完全相同是这首的前提", 54, ttmlTranslations.size)

        val track = LyricTrackMerge.merge(main, ttmlTranslations, f.netmain.toLines(), tlyric)

        assertEquals(LyricTrackSource.TTML, track.source)
        // v1.9.0 的原表达式：doc.translations.filter { it.timeMs in lineTimes }
        val v190 = ttmlTranslations.filter { it.timeMs in main.times() }
        assertEquals("必须与 v1.9.0 的输出完全一致", v190, track.lines)
        assertEquals(54, track.lines.size)
    }

    @Test
    fun `真实——16686599 Numb：两源行数不等（47 行主轨、49 行译文）时不崩、不多不少`() {
        val f = load(16686599)
        val main = f.main.toLines()
        val ttmlTranslations = f.ttml.toLines()
        assertTrue("TTML 译文条数多于主轨行数（含同刻重复）", ttmlTranslations.size > main.size)

        val track = LyricTrackMerge.merge(
            main, ttmlTranslations, f.netmain.toLines(), f.nettrackTlyric!!.toLines()
        )

        assertEquals(LyricTrackSource.TTML, track.source)
        assertEquals(ttmlTranslations.filter { it.timeMs in main.times() }, track.lines)
        assertEquals(49, track.lines.size)
    }

    // --------------------------------------------------------------- 合成用例

    @Test
    fun `合成——两源时间轴完全不同也按文本配对，时间戳一律取主轨的`() {
        val main = listOf(LrcLine(1_000, "a"), LrcLine(2_000, "b"))
        val neteaseMain = listOf(LrcLine(800_000, "a"), LrcLine(900_000, "b"))
        val neteaseTrack = listOf(LrcLine(800_000, "TA"), LrcLine(900_000, "TB"))

        val track = LyricTrackMerge.merge(main, emptyList(), neteaseMain, neteaseTrack)

        assertEquals(listOf(1_000L, 2_000L), track.lines.map { it.timeMs })
        assertEquals(listOf("TA", "TB"), track.lines.map { it.text })
        assertTrue("副文本轨自己的时间戳不得外泄", track.lines.none { it.timeMs >= 800_000 })
    }

    @Test
    fun `合成——文本对不上就逐行丢弃，不填充不猜`() {
        val main = listOf(LrcLine(1_000, "A"), LrcLine(2_000, "B"), LrcLine(3_000, "C"))
        val neteaseMain = listOf(LrcLine(500, "A"), LrcLine(600, "完全不同的一句"))
        val neteaseTrack = listOf(LrcLine(500, "ta"), LrcLine(600, "tx"))

        val track = LyricTrackMerge.merge(main, emptyList(), neteaseMain, neteaseTrack)

        assertEquals(1, track.lines.size)
        assertEquals(LrcLine(1_000, "ta"), track.lines.single())
        assertEquals(LyricTrackSource.NETEASE, track.source)
    }

    @Test
    fun `合成——ncm那一行没有内容时只丢这一行，其余照常`() {
        val main = listOf(LrcLine(1_000, "A"), LrcLine(2_000, "B"))
        val neteaseMain = listOf(LrcLine(500, "A"), LrcLine(600, "B"))
        // B 那一行 ncm 没有译文（tlyric 行数常少于 lrc）
        val neteaseTrack = listOf(LrcLine(500, "ta"))

        val track = LyricTrackMerge.merge(main, emptyList(), neteaseMain, neteaseTrack)

        assertEquals(listOf(LrcLine(1_000, "ta")), track.lines)
    }

    @Test
    fun `合成——重复行（副歌）按出现顺序配对，不按文本去重`() {
        val main = listOf(LrcLine(1_000, "chorus"), LrcLine(2_000, "verse"), LrcLine(3_000, "chorus"))
        val neteaseMain = listOf(LrcLine(900, "chorus"), LrcLine(1_500, "verse"), LrcLine(2_500, "chorus"))
        val neteaseTrack = listOf(LrcLine(900, "c1"), LrcLine(1_500, "v"), LrcLine(2_500, "c2"))

        val track = LyricTrackMerge.merge(main, emptyList(), neteaseMain, neteaseTrack)

        assertEquals(listOf(1_000L, 2_000L, 3_000L), track.lines.map { it.timeMs })
        assertEquals(listOf("c1", "v", "c2"), track.lines.map { it.text })
    }

    @Test
    fun `合成——TTML 覆盖满时即使ncm有内容也不重复`() {
        val main = listOf(LrcLine(1_000, "A"))
        val ttml = listOf(LrcLine(1_000, "TA"))
        val neteaseMain = listOf(LrcLine(900, "A"))
        val neteaseTrack = listOf(LrcLine(900, "na"))

        val track = LyricTrackMerge.merge(main, ttml, neteaseMain, neteaseTrack)

        assertEquals(LyricTrackSource.TTML, track.source)
        assertEquals(listOf(LrcLine(1_000, "TA")), track.lines)
    }

    @Test
    fun `合成——TTML 部分覆盖时缺口补齐、已有行不动（MIXED）`() {
        val main = listOf(LrcLine(1_000, "A"), LrcLine(2_000, "B"), LrcLine(3_000, "C"))
        val ttml = listOf(LrcLine(1_000, "TA"))
        val neteaseMain = listOf(LrcLine(100, "A"), LrcLine(200, "B"), LrcLine(300, "C"))
        val neteaseTrack = listOf(LrcLine(100, "na"), LrcLine(200, "nb"), LrcLine(300, "nc"))

        val track = LyricTrackMerge.merge(main, ttml, neteaseMain, neteaseTrack)

        assertEquals(LyricTrackSource.MIXED, track.source)
        assertEquals(
            listOf(LrcLine(1_000, "TA"), LrcLine(2_000, "nb"), LrcLine(3_000, "nc")),
            track.lines,
        )
    }

    @Test
    fun `合成——空输入返回空轨且没有来源标记（不产生空轨+有来源的假信息）`() {
        val main = listOf(LrcLine(1_000, "A"))
        val empty = LyricTrackMerge.merge(main, emptyList(), emptyList(), emptyList())
        assertEquals(emptyList<LrcLine>(), empty.lines)
        assertNull(empty.source)

        val noMain = LyricTrackMerge.merge(
            emptyList(), emptyList(), listOf(LrcLine(1, "A")), listOf(LrcLine(1, "t"))
        )
        assertEquals(emptyList<LrcLine>(), noMain.lines)
        assertNull(noMain.source)
    }

    @Test
    fun `合成——TTML 有轨但全部对不上主轨时间戳时，回退仍然生效`() {
        val main = listOf(LrcLine(1_000, "A"))
        // TTML 的译文时间戳落在别处（对不上主轨）⇒ 不能用，应当走回退
        val ttml = listOf(LrcLine(9_999, "TA"))
        val neteaseMain = listOf(LrcLine(100, "A"))
        val neteaseTrack = listOf(LrcLine(100, "na"))

        val track = LyricTrackMerge.merge(main, ttml, neteaseMain, neteaseTrack)

        assertEquals(LyricTrackSource.NETEASE, track.source)
        assertEquals(listOf(LrcLine(1_000, "na")), track.lines)
    }
}
