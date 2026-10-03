/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.5 · A：续播进度表与收藏专辑表的 R8 key 迁移单测。
 */

package com.takahashirinta.ncrust.player

import com.google.gson.JsonParser
import com.takahashirinta.ncrust.library.AlbumInfo
import com.takahashirinta.ncrust.library.SavedAlbumCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ncrust_playback_state/song_positions`（[PlaybackPositionCodec]）的守卫。
 *
 * 取证：`mapping.txt` 里
 * `PlaybackStateManager$PositionEntry -> H4.F: posMs -> a, savedAtMs -> b`。
 *
 * 这是**后果最隐蔽**的一个落点（本版探针新发现）：字母表一变 ⇒
 * 「位置↔时间戳对调」或「续播静默失效」，而两者都不崩、不报错、日志里什么都没有。
 */
class PlaybackPositionCodecTest {

    @Test
    fun `新写入的内层 key 是稳定字段名而不是单字母`() {
        val json = PlaybackPositionCodec.encode(
            mapOf(1959528822L to PlaybackStateManager.PositionEntry(posMs = 48_631L, savedAtMs = 1L)),
        )
        assertTrue(json.contains("\"posMs\""))
        assertTrue(json.contains("\"savedAtMs\""))
        assertTrue("外层 key 必须是裸 songId", json.contains("\"1959528822\""))
        assertFalse(
            "内层出现了单字母 key —— 字段名又被交给 R8 了：$json",
            Regex("(?<=[{,])\\s*\"[a-z]\"\\s*:").containsMatchIn(json),
        )
    }

    @Test
    fun `稳定 key 与旧单字母 key 一一对应`() {
        assertEquals(listOf("posMs", "savedAtMs"), PlaybackPositionCodec.stableKeys())
        assertEquals(listOf("a", "b"), PlaybackPositionCodec.legacyKeys())
    }

    /** ★ 真机旧形状：`{"1959528822":{"a":48631,"b":1790344822158}}`。 */
    @Test
    fun `认得住旧单字母形状`() {
        val legacy = """{"1959528822":{"a":48631,"b":1790344822158}}"""
        val map = PlaybackPositionCodec.decode(legacy)
        assertEquals(1, map.size)
        val e = map[1959528822L]
        assertNotNull(e)
        assertEquals("a 必须是 posMs，不是时间戳", 48_631L, e!!.posMs)
        assertEquals(1790344822158L, e.savedAtMs)
    }

    /** 未知字母表 ⇒ 按声明顺序读（第一个数字 posMs、第二个 savedAtMs），**不许整表丢光**。 */
    @Test
    fun `未知字母表时按声明顺序兜底`() {
        val unknown = """{"42":{"z":1234,"y":5678}}"""
        val e = PlaybackPositionCodec.decode(unknown)[42L]
        assertNotNull("兜底路径必须把记录读出来", e)
        assertEquals(1234L, e!!.posMs)
        assertEquals(5678L, e.savedAtMs)
    }

    /** 只有一个数字时：posMs 有值、savedAtMs 补 0（`getSongPosition` 只看 posMs）。 */
    @Test
    fun `只有一个字段时 savedAtMs 补零`() {
        val e = PlaybackPositionCodec.decode("""{"7":{"posMs":5000}}""")[7L]
        assertNotNull(e)
        assertEquals(5000L, e!!.posMs)
        assertEquals(0L, e.savedAtMs)
    }

    /** 坏条目逐条丢弃：一个坏键不影响其余歌曲。 */
    @Test
    fun `坏条目逐条丢弃不影响其余歌曲`() {
        val mixed = """{"1":{"posMs":100,"savedAtMs":1},"notANumber":{"posMs":1},"2":"不是对象","3":{"posMs":300,"savedAtMs":3}}"""
        val map = PlaybackPositionCodec.decode(mixed)
        assertEquals(setOf(1L, 3L), map.keys)
        assertEquals(100L, map[1L]!!.posMs)
        assertEquals(300L, map[3L]!!.posMs)
    }

    @Test
    fun `非 JSON 与非对象输入返回空表而不抛`() {
        assertTrue(PlaybackPositionCodec.decode(null).isEmpty())
        assertTrue(PlaybackPositionCodec.decode("").isEmpty())
        assertTrue(PlaybackPositionCodec.decode("[1,2]").isEmpty())
        assertTrue(PlaybackPositionCodec.decode("{oops").isEmpty())
    }

    /** 主键非正（怪 prefs）⇒ 丢弃。 */
    @Test
    fun `非正主键被丢弃`() {
        assertTrue(PlaybackPositionCodec.decode("""{"0":{"posMs":1}}""").isEmpty())
        assertTrue(PlaybackPositionCodec.decode("""{"-3":{"posMs":1}}""").isEmpty())
    }

    /** 往返一致。 */
    @Test
    fun `编码解码往返一致`() {
        val original = mapOf(
            1L to PlaybackStateManager.PositionEntry(1_000L, 10L),
            2L to PlaybackStateManager.PositionEntry(2_000L, 20L),
        )
        assertEquals(original, PlaybackPositionCodec.decode(PlaybackPositionCodec.encode(original)))
    }

    /** 稳定形状与旧形状解出同一条记录（迁移等价性）。 */
    @Test
    fun `稳定形状与旧形状解出同一条记录`() {
        val stable = JsonParser.parseString("""{"posMs":48631,"savedAtMs":123}""").asJsonObject
        val legacy = JsonParser.parseString("""{"a":48631,"b":123}""").asJsonObject
        assertEquals(
            PlaybackPositionCodec.decodeEntry(stable),
            PlaybackPositionCodec.decodeEntry(legacy),
        )
    }

    @Test
    fun `空对象条目被丢弃`() {
        assertNull(PlaybackPositionCodec.decodeEntry(JsonParser.parseString("{}").asJsonObject))
    }
}

/**
 * `ncrust_library/saved_albums`（[SavedAlbumCodec]）的守卫。
 *
 * 取证：`mapping.txt` 里 `library.AlbumInfo -> G4.a`，字段 `albumId→a … songCount→e`。
 * 它**不在任何 `-keep` 覆盖的包里** —— 同文件的 `SongItem` 在 `network.**` 下被保护，
 * 而 `AlbumInfo` 是 `library/` 的顶层 data class，上一版因此把它漏了过去。
 */
class SavedAlbumCodecTest {

    private val sample = AlbumInfo(
        albumId = 123456L,
        name = "专辑名",
        picUrl = "https://p1.music.126.net/x.jpg",
        artist = "艺人",
        songCount = 12,
        // v2.6.2 · P0：ncm 一侧**没有字符串身份**，`mid` 恒为 null。
        // 夹具保持 null 是**有意的** —— 它同时覆盖「v2.6.2 之前落盘的老条目」
        // 与「ncm 专辑」两种情形，而两者的处置相同（身份不可信）。
    )

    /** v2.6.2 · P0：QQ 专辑 —— 有字符串身份（albumMID）。 */
    private val qqSample = sample.copy(mid = "000MkMni19ClKG")

    @Test
    fun `新写入的 key 是稳定字段名而不是单字母`() {
        // ⚠️ v2.6.2 起 `albumMid` 是**可空**字段，Gson 默认跳过 null ⇒ ncm 那条
        // （`mid = null`）落盘时**本来就不该有** `albumMid` 这个 key。
        // 所以「每个 stable key 都出现在 JSON 里」这条断言必须**用身份可信的样本**跑，
        // 否则它会把「可空字段被正确跳过」误判成「字段名被交给 R8 了」。
        val json = SavedAlbumCodec.encode(listOf(qqSample))
        for (key in SavedAlbumCodec.stableKeys()) {
            assertTrue("落盘 JSON 缺稳定 key '$key'：$json", json.contains("\"$key\""))
        }
        assertFalse(
            "落盘 JSON 里出现了单字母 key —— 字段名又被交给 R8 了：$json",
            Regex("(?<=[{,])\\s*\"[a-z]\"\\s*:").containsMatchIn(json),
        )
    }

    /**
     * v2.6.2 · P0：**空身份的条目不许凭空多出 `albumMid` 这个 key**。
     *
     * 这条与上一条是一对：上一条防「字段名被混淆」，这一条防「把 null 写成可见的 key」。
     * 后者会让**下一次**读到它的人分不清「字段缺失（老数据）」与「字段是 null（新数据）」——
     * 而 AGENTS.md v1.9.3 规则 2 明确要求判老条目只看字段缺失。
     */
    @Test
    fun `身份为空的条目落盘时不写 albumMid 这个 key`() {
        val json = SavedAlbumCodec.encode(listOf(sample))
        assertFalse("ncm 专辑不该写出 albumMid：$json", json.contains("\"albumMid\""))
        assertTrue("其余 5 个字段照常写出：$json", json.contains("\"albumId\""))
    }

    @Test
    fun `稳定 key 与旧单字母 key 一一对应`() {
        assertEquals(
            listOf("albumId", "name", "picUrl", "artist", "songCount", "albumMid"),
            SavedAlbumCodec.stableKeys(),
        )
        // v2.6.2：**故意不往 LEGACY_KEYS 里补第六个字母** —— v1 的结构只写过五个字段，
        // 补一个 `f` 等于发明一条没有取证支撑的映射（比读不出来更糟）。
        assertEquals(listOf("a", "b", "c", "d", "e"), SavedAlbumCodec.legacyKeys())
    }

    /** ★ 真机旧形状：`[{"a":123,"b":"专辑名","c":"https://…","d":"艺人","e":12}]`。 */
    @Test
    fun `认得住旧单字母形状`() {
        val legacy = """[{"a":123456,"b":"专辑名","c":"https://p1.music.126.net/x.jpg","d":"艺人","e":12}]"""
        val albums = SavedAlbumCodec.decode(legacy)
        assertEquals(1, albums.size)
        assertEquals(sample, albums[0])
    }

    /**
     * ★ `albumId` 非法（0 / 缺失）的条目必须丢弃。
     *
     * `LibraryScreen` 用 `key = { it.albumId }` —— 两条 `albumId == 0` 会让
     * `LazyColumn` 出现重复 key 并**直接抛异常**（收藏页打不开）。
     * 而映射错位（字母表一变）恰好就会把 `albumId` 读成 0。
     */
    @Test
    fun `albumId 非法的条目被丢弃而不是留下 0`() {
        assertTrue(SavedAlbumCodec.decode("""[{"a":0,"b":"x"}]""").isEmpty())
        assertTrue(SavedAlbumCodec.decode("""[{"b":"x","c":"y"}]""").isEmpty())
        assertTrue(SavedAlbumCodec.decode("""[{"albumId":0,"name":"x"}]""").isEmpty())
        // 混在好数据里的坏条目只丢它自己
        val mixed = """[{"a":0,"b":"坏"},{"a":9,"b":"好"}]"""
        assertEquals(listOf(9L), SavedAlbumCodec.decode(mixed).map { it.albumId })
    }

    /** 未知字母表 ⇒ 按声明顺序兜底（5 个字段全部非空，位置读安全）。 */
    @Test
    fun `未知字母表时按声明顺序兜底`() {
        val unknown = """[{"p":321,"q":"专辑","r":"https://c","s":"艺人","t":7}]"""
        val a = SavedAlbumCodec.decode(unknown).single()
        assertEquals(321L, a.albumId)
        assertEquals("专辑", a.name)
        assertEquals("https://c", a.picUrl)
        assertEquals("艺人", a.artist)
        assertEquals(7, a.songCount)
    }

    /** 字段不足 5 个的未知形状 ⇒ 丢弃（补不出可信的专辑）。 */
    @Test
    fun `未知形状字段不足时丢弃`() {
        assertTrue(SavedAlbumCodec.decode("""[{"p":1,"q":"x"}]""").isEmpty())
    }

    @Test
    fun `坏条目逐条丢弃不影响其余专辑`() {
        val mixed = """[{"albumId":1,"name":"A"},{"albumId":0,"name":"坏"},"不是对象",{"albumId":3,"name":"C"}]"""
        assertEquals(listOf(1L, 3L), SavedAlbumCodec.decode(mixed).map { it.albumId })
    }

    @Test
    fun `非 JSON 与非数组输入返回空表而不抛`() {
        assertTrue(SavedAlbumCodec.decode(null).isEmpty())
        assertTrue(SavedAlbumCodec.decode("").isEmpty())
        assertTrue(SavedAlbumCodec.decode("""{"a":1}""").isEmpty())
        assertTrue(SavedAlbumCodec.decode("{oops").isEmpty())
    }

    @Test
    fun `编码解码往返一致`() {
        assertEquals(listOf(sample), SavedAlbumCodec.decode(SavedAlbumCodec.encode(listOf(sample))))
    }

    @Test
    fun `稳定形状与旧形状解出同一条记录`() {
        val stable = JsonParser.parseString(
            """{"albumId":123456,"name":"专辑名","picUrl":"https://p1.music.126.net/x.jpg","artist":"艺人","songCount":12}""",
        ).asJsonObject
        val legacy = JsonParser.parseString(
            """{"a":123456,"b":"专辑名","c":"https://p1.music.126.net/x.jpg","d":"艺人","e":12}""",
        ).asJsonObject
        assertEquals(SavedAlbumCodec.decodeEntry(stable), SavedAlbumCodec.decodeEntry(legacy))
    }
}
