/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.5 · A：R8 单字母 key 三个落点的迁移单测。
 */

package com.takahashirinta.ncrust.cache

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OfflineTrackCodec] 的守卫。样本全部取自**真机 / mapping.txt 取证过的形状**，
 * 不是构造出来的「理想老数据」。
 *
 * 真机样本（`docs/verification/v2.5.4/EVIDENCE.md` 记下的 `ncrust_offline.xml` 摘录）：
 *
 * ```json
 * [{"a":1959528822,"b":"…","e":…,"f":"exhigh","g":"song:1959528822:exhigh","i":1790344822158}]
 * ```
 *
 * ★ 注意 **没有 `h`**：`approxBytes` 值为 null ⇒ Gson 整条 key 省掉。
 * 按「有几个 key 就按顺序对几个字段」去解，从 `i` 开始就全错位。
 */
class OfflineTrackCodecTest {

    // ------------------------------------------------------- 稳定名字（写路径的契约）

    /**
     * ★ **新写入的 key 是字段本名，不是单字母。**
     *
     * 字段改名会让这条用例变红，而不是让用户的离线清单消失。
     */
    @Test
    fun `新写入的条目 key 是字段本名而不是单字母`() {
        // ⚠️ 必须传**全部字段**：Gson 会把 null 字段的整条 key 省掉
        // （真机上 `h`/approxBytes 就是这么消失的），少传一个字段这条断言就会误报。
        val json = OfflineTrackCodec.encode(
            listOf(
                track(
                    songId = 1959528822L,
                    name = "残酷な天使のテーゼ",
                    artist = "高橋洋子",
                    albumPicUrl = "https://p1.music.126.net/x.jpg",
                    durationMs = 240_000L,
                    level = "exhigh",
                    cacheKey = "song:1959528822:exhigh",
                    approxBytes = 4096L,
                    completedAt = 1790344822158L,
                ),
            ),
        )
        for (key in OfflineTrackCodec.stableKeys()) {
            assertTrue("落盘 JSON 缺稳定 key '$key'：$json", json.contains("\"$key\""))
        }
        assertFalse(
            "落盘 JSON 里出现了单字母 key —— 字段名又被交给 R8 了：$json",
            Regex("(?<=[{,])\\s*\"[a-z]\"\\s*:").containsMatchIn(json),
        )
    }

    @Test
    fun `稳定 key 与旧单字母 key 的数量一致且都不重复`() {
        assertEquals(9, OfflineTrackCodec.stableKeys().size)
        assertEquals(9, OfflineTrackCodec.legacyKeys().size)
        assertEquals(OfflineTrackCodec.stableKeys().size, OfflineTrackCodec.stableKeys().distinct().size)
        assertEquals(OfflineTrackCodec.legacyKeys().size, OfflineTrackCodec.legacyKeys().distinct().size)
        // 旧字母表就是 a..i，一个不漏（mapping.txt 取证）。
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g", "h", "i"), OfflineTrackCodec.legacyKeys())
    }

    @Test
    fun `编码解码往返一致`() {
        val original = track(
            songId = 1959528822L,
            name = "歌名",
            artist = "艺人",
            albumPicUrl = "https://p1.music.126.net/x.jpg",
            durationMs = 240_000L,
            level = "exhigh",
            cacheKey = "song:1959528822:exhigh",
            completedAt = 1790344822158L,
        )
        val round = OfflineTrackCodec.decode(OfflineTrackCodec.encode(listOf(original)))
        assertEquals(1, round.size)
        assertEquals(original, round[0])
    }

    // ------------------------------------------------------- 旧单字母（真机形状）

    /** ★ 真机形状：`a b e f g i`，**缺 `h`**。必须逐字母认，绝不能按位置。 */
    @Test
    fun `认得住真机上的 a b e f g i 形状（缺 h）`() {
        val legacy = """[{"a":1959528822,"b":"残酷な天使のテーゼ","e":240000,
            "f":"exhigh","g":"song:1959528822:exhigh","i":1790344822158}]"""
        val tracks = OfflineTrackCodec.decode(legacy)
        assertEquals(1, tracks.size)
        val t = tracks[0]
        assertEquals(1959528822L, t.songId)
        assertEquals("残酷な天使のテーゼ", t.name)
        assertEquals(240_000L, t.durationMs)
        assertEquals("exhigh", t.level)
        assertEquals("song:1959528822:exhigh", t.cacheKey)
        assertEquals(1790344822158L, t.completedAt)
        // ★ 关键：`i` 是 completedAt，不是 level。按位置读会把它错位到 h 的槽位。
        assertNull("approxBytes 缺失必须是 null，不能吃到 i 的值", t.approxBytes)
    }

    /** 九字段齐全的老形状。 */
    @Test
    fun `认得九字段齐全的老形状`() {
        val legacy = """[{"a":1,"b":"n","c":"ar","d":"https://x/y.jpg","e":100,
            "f":"lossless","g":"song:1:lossless","h":4096,"i":999}]"""
        val t = OfflineTrackCodec.decode(legacy).single()
        assertEquals(1L, t.songId)
        assertEquals("n", t.name)
        assertEquals("ar", t.artist)
        assertEquals("https://x/y.jpg", t.albumPicUrl)
        assertEquals(100L, t.durationMs)
        assertEquals("lossless", t.level)
        assertEquals("song:1:lossless", t.cacheKey)
        assertEquals(4096L, t.approxBytes)
        assertEquals(999L, t.completedAt)
    }

    /** 老形状里 `a`（主键）缺失或非正 ⇒ 丢弃，不猜。 */
    @Test
    fun `老形状主键非法时丢弃`() {
        assertTrue(OfflineTrackCodec.decode("""[{"b":"x","c":"y"}]""").isEmpty())
        assertTrue(OfflineTrackCodec.decode("""[{"a":0,"b":"x"}]""").isEmpty())
        assertTrue(OfflineTrackCodec.decode("""[{"a":-5,"b":"x"}]""").isEmpty())
    }

    // ------------------------------------------------------- 未知字母表（兜底）

    /** 未知单字母集合（R8 换了字母表）⇒ 按声明顺序兜底，**不许整段丢光**。 */
    @Test
    fun `未知字母表时按声明顺序兜底而不是整段丢光`() {
        val unknown = """[{"p":777,"q":"Song","r":"Artist",
            "s":"https://x/cover.jpg","t":123,"u":"hires","v":"song:777:hires","w":88}]"""
        val t = OfflineTrackCodec.decode(unknown).single()
        assertEquals("主键必须读出来（这是兜底的全部意义）", 777L, t.songId)
        assertEquals("Song", t.name)
        assertEquals("Artist", t.artist)
        assertEquals("https://x/cover.jpg", t.albumPicUrl)
        assertEquals("song:777:hires", t.cacheKey)
        assertEquals(88L, t.completedAt)
    }

    // ------------------------------------------------------- 坏数据逐条丢弃

    /**
     * ★ **一条坏、其余照读。** 旧实现是 `runCatching` 包住整个 `fromJson`：
     * 一个字节坏了，用户整份离线清单消失。
     */
    @Test
    fun `坏条目逐条丢弃不影响其余条目`() {
        val mixed = """
            [
              {"songId":1,"name":"A"},
              {"songId":0,"name":"坏主键"},
              "不是对象",
              {"songId":3,"name":"C"}
            ]
        """.trimIndent()
        val tracks = OfflineTrackCodec.decode(mixed)
        assertEquals(2, tracks.size)
        assertEquals(listOf(1L, 3L), tracks.map { it.songId })
    }

    @Test
    fun `非 JSON 与非数组输入返回空表而不抛`() {
        assertTrue(OfflineTrackCodec.decode(null).isEmpty())
        assertTrue(OfflineTrackCodec.decode("").isEmpty())
        assertTrue(OfflineTrackCodec.decode("{not json").isEmpty())
        assertTrue(OfflineTrackCodec.decode("""{"a":1}""").isEmpty())
    }

    /** 空对象（一个 key 都没有）不进任何一条读法。 */
    @Test
    fun `空对象条目被丢弃`() {
        assertNull(OfflineTrackCodec.decodeEntry(JsonParser.parseString("{}").asJsonObject))
    }

    // ------------------------------------------------------- 索引层（与 v2.0.0 行为对齐）

    /** 索引装载：老形状也能进索引，而且顺序按 `completedAt` 倒序。 */
    @Test
    fun `索引能从真机老形状装载并保持倒序`() {
        val legacy = """[{"a":1,"b":"old","i":100},{"a":2,"b":"new","i":200}]"""
        val idx = OfflineLibraryIndex.fromJson(legacy)
        assertEquals(2, idx.size())
        assertEquals(listOf(2L, 1L), idx.list().map { it.songId })
    }

    /** 主键非法的条目在装载时丢弃，索引里不留 0 号。 */
    @Test
    fun `索引装载丢弃非法主键`() {
        val idx = OfflineLibraryIndex.fromJson("""[{"a":0,"b":"x"},{"a":5,"b":"y"}]""")
        assertEquals(1, idx.size())
        assertEquals(5L, idx.list().single().songId)
    }

    /** 空 / 坏 JSON ⇒ 空索引（不是 null、不抛）。 */
    @Test
    fun `空或坏 JSON 得到空索引`() {
        assertEquals(0, OfflineLibraryIndex.fromJson(null).size())
        assertEquals(0, OfflineLibraryIndex.fromJson("").size())
        assertEquals(0, OfflineLibraryIndex.fromJson("{oops").size())
    }

    /** 往返：索引 toJson → fromJson 得到同一批 id（`OfflineLibrary` 的真机恢复路径）。 */
    @Test
    fun `索引往返保持同一批 id`() {
        val idx = OfflineLibraryIndex()
        idx.upsert(track(songId = 11L, completedAt = 100L))
        idx.upsert(track(songId = 22L, completedAt = 200L))
        val restored = OfflineLibraryIndex.fromJson(idx.toJson())
        assertEquals(setOf(11L, 22L), restored.songIds())
        assertEquals(200L, restored.get(22L)?.completedAt)
    }

    // ------------------------------------------------------- 稳定性

    /** `decodeEntry` 对稳定名字与旧字母两条路给出**同一个对象**（迁移等价性）。 */
    @Test
    fun `稳定形状与旧形状解出同一条记录`() {
        val stable = JsonParser.parseString(
            """{"songId":42,"name":"N","artist":"A","albumPicUrl":"https://c",
                "durationMs":7,"level":"hires","cacheKey":"song:42:hires",
                "approxBytes":9,"completedAt":8}""",
        ).asJsonObject
        val legacy = JsonParser.parseString(
            """{"a":42,"b":"N","c":"A","d":"https://c","e":7,"f":"hires",
                "g":"song:42:hires","h":9,"i":8}""",
        ).asJsonObject
        val a = OfflineTrackCodec.decodeEntry(stable)
        val b = OfflineTrackCodec.decodeEntry(legacy)
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(a, b)
    }

    private fun track(
        songId: Long,
        name: String? = "n",
        artist: String? = null,
        albumPicUrl: String? = null,
        durationMs: Long? = null,
        level: String? = null,
        cacheKey: String? = null,
        approxBytes: Long? = null,
        completedAt: Long? = null,
    ) = OfflineTrack(
        songId = songId,
        name = name,
        artist = artist,
        albumPicUrl = albumPicUrl,
        durationMs = durationMs,
        level = level,
        cacheKey = cacheKey,
        approxBytes = approxBytes,
        completedAt = completedAt,
    )
}
