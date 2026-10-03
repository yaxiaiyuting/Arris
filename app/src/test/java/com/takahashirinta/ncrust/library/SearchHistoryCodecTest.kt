/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.4 · B：搜索历史落盘形状与迁移的守卫单测。
 */

package com.takahashirinta.ncrust.library

import com.google.gson.Gson
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `search_history` 的落盘契约。
 *
 * ## 这一组用例真正在防的是什么
 *
 * 不是「能读能写」——那种用例在真机上也会绿。防的是**字段名不再由 R8 决定**：
 *
 * - v2.5.2 的真机上，这张表的 JSON 是 `{"a":…,"b":…,"c":…,"d":…,"e":…}`
 *   （S6 取证 + `mapping/release/mapping.txt` 双重确认）；
 * - 本版加了 3 个字段并把 key 固定成字段本名，于是**同一台设备上并存两种形状**；
 * - 第三种形状（R8 换了字母表）也必须能读 —— 只是丢了封面/艺人名的精度，
 *   而不是丢光用户的历史。
 *
 * 三种形状各有一组用例，还有一组「混在一起」的。
 */
class SearchHistoryCodecTest {

    private fun item(
        id: Long,
        title: String = "t",
        cover: String? = null,
        subtitle: String? = null,
        timestamp: Long = 1L,
        source: String? = null,
        sourceId: String? = null,
        mediaId: String? = null,
    ) = SearchHistoryManager.HistoryItem(
        id = id, title = title, coverUrl = cover, subtitle = subtitle,
        timestamp = timestamp, source = source, sourceId = sourceId, mediaId = mediaId,
    )

    // ------------------------------------------------------------ 新形状 ----

    @Test
    fun `新写入的条目 key 是字段本名而不是单字母`() {
        val json = SearchHistoryCodec.encode(
            listOf(item(657666, "残酷な天使のテーゼ", source = "netease"))
        )
        // 这条断言就是「字段名不再由 R8 决定」的可执行版本：
        // 它会在 `@SerializedName` 被去掉、或有人把 codec 换回裸 Gson 时变红。
        assertTrue("落盘缺 id key：$json", json.contains("\"id\":"))
        assertTrue("落盘缺 title key：$json", json.contains("\"title\":"))
        assertTrue("落盘缺 source key：$json", json.contains("\"source\":\"netease\""))
        assertTrue("落盘出现了旧单字母 key：$json", !json.contains("\"a\":"))
    }

    @Test
    fun `新形状往返不丢任何字段`() {
        val src = item(
            4611686018530183086L, "残酷な天使のテーゼ", "https://y.qq.com/a.jpg", "高橋洋子",
            1790344822158L, "qqmusic", "0039MnYb0qxYhV", "003Qui1q2u1Zho"
        )
        val back = SearchHistoryCodec.decode(SearchHistoryCodec.encode(listOf(src)))
        assertEquals(1, back.size)
        assertEquals(src, back[0])
    }

    @Test
    fun `null 字段照旧整条 key 不写（与 v1 形状同构）`() {
        val json = SearchHistoryCodec.encode(listOf(item(657666, "x", source = "netease")))
        assertTrue("coverUrl 是 null 就不该写出 key：$json", !json.contains("coverUrl"))
        assertTrue("subtitle 是 null 就不该写出 key：$json", !json.contains("subtitle"))
    }

    // ------------------------------------------------------ 真机 v1 形状 ----

    /** S6 `/data/data/…/shared_prefs/search_history.xml` 的 `songs` 原样（v2.5.2 写入）。 */
    private val realDeviceV1 =
        """[{"a":4611686018530183086,"b":"残酷な天使のテーゼ",""" +
            """"c":"https://y.qq.com/music/photo_new/T002R500x500M000000pmPam3gTtA5_1.jpg",""" +
            """"d":"高橋洋子","e":1790344822158},{"a":657666,"b":"残酷な天使のテーゼ",""" +
            """"c":"http://p1.music.126.net/Mn6LGBzwfGW1GOFC_lg7sw==/109951172618401349.jpg",""" +
            """"d":"高橋洋子","e":1790344385651}]"""

    @Test
    fun `真机 v1 形状（单字母 key）两条都能读出来`() {
        val back = SearchHistoryCodec.decode(realDeviceV1)
        assertEquals(2, back.size)
        assertEquals("残酷な天使のテーゼ", back[0].title)
        assertEquals(1790344822158L, back[0].timestamp)
        assertEquals("高橋洋子", back[0].subtitle)
        assertNotNull(back[0].coverUrl)
    }

    @Test
    fun `真机 v1 形状读出来的条目 source 是缺失而不是ncm`() {
        // 关键区分：`null` 的语义是「v2.5.4 之前写入的老条目」，
        // 不是「ncm」。判音源由 SearchHistoryMigration 负责（走 bit62）。
        val back = SearchHistoryCodec.decode(realDeviceV1)
        assertNull(back[0].source)
        assertNull(back[0].sourceId)
        assertEquals(MusicSource.QQMUSIC, SearchHistoryMigration.effectiveSource(back[0]))
        assertEquals(MusicSource.NETEASE, SearchHistoryMigration.effectiveSource(back[1]))
    }

    @Test
    fun `v1 缺 coverUrl 与 subtitle 时仍按位置读对 id 与 timestamp`() {
        val json = """[{"a":123,"b":"只有标题","e":999}]"""
        val back = SearchHistoryCodec.decode(json)
        assertEquals(1, back.size)
        assertEquals(123L, back[0].id)
        assertEquals("只有标题", back[0].title)
        assertEquals(999L, back[0].timestamp)
        assertNull(back[0].coverUrl)
        assertNull(back[0].subtitle)
    }

    // -------------------------------------------------- 未知 key 集合 ----

    @Test
    fun `R8 换了字母表也能读（按声明顺序兜底，URL 判为封面）`() {
        // 这是「第三种形状」：key 既不是稳定名也不是 a~e。
        // 兜底判据只用两条 —— 第 1 个是 id（数字）、最后 1 个是 timestamp（数字），
        // 中间「像 URL 的」是封面。
        val json = """[{"p":42,"q":"标题","r":"https://c.jpg","s":"艺人","t":777}]"""
        val back = SearchHistoryCodec.decode(json)
        assertEquals(1, back.size)
        assertEquals(42L, back[0].id)
        assertEquals("标题", back[0].title)
        assertEquals("https://c.jpg", back[0].coverUrl)
        assertEquals("艺人", back[0].subtitle)
        assertEquals(777L, back[0].timestamp)
    }

    @Test
    fun `兜底顺序下艺人名不会被误判成封面`() {
        val json = """[{"p":42,"q":"标题","r":"只是一首歌","s":"https://c.jpg","t":777}]"""
        val back = SearchHistoryCodec.decode(json)
        assertEquals(1, back.size)
        assertEquals("https://c.jpg", back[0].coverUrl)
        assertEquals("只是一首歌", back[0].subtitle)
    }

    // ------------------------------------------------------------ 混合 ----

    @Test
    fun `新旧两种形状混在同一条 JSON 里也能全部读出`() {
        val mixed = """[{"id":111,"title":"新","source":"qqmusic","sourceId":"mid"},""" +
            """{"a":222,"b":"老","d":"艺人","e":333}]"""
        val back = SearchHistoryCodec.decode(mixed)
        assertEquals(2, back.size)
        assertEquals("qqmusic", back[0].source)
        assertEquals("mid", back[0].sourceId)
        assertNull(back[1].source)
        assertEquals("艺人", back[1].subtitle)
    }

    // ------------------------------------------------------------ 坏数据 ----

    @Test
    fun `坏条目只丢那一条而不是整段`() {
        // 旧实现是 `catch { mutableListOf() }` —— 一个字节坏了用户整段历史消失。
        val json = """[{"id":1,"title":"好的"},{"nonsense":true},{"id":3,"title":"也好"}]"""
        val back = SearchHistoryCodec.decode(json)
        assertEquals(2, back.size)
        assertEquals(1L, back[0].id)
        assertEquals(3L, back[1].id)
    }

    @Test
    fun `id 缺失或非正的条目被丢弃`() {
        assertEquals(0, SearchHistoryCodec.decode("""[{"title":"没有 id"}]""").size)
        assertEquals(0, SearchHistoryCodec.decode("""[{"id":0,"title":"id 是 0"}]""").size)
        assertEquals(0, SearchHistoryCodec.decode("""[{"id":-5,"title":"负 id"}]""").size)
    }

    @Test
    fun `空串与非法 JSON 一律返回空列表而不是抛异常`() {
        assertEquals(0, SearchHistoryCodec.decode(null).size)
        assertEquals(0, SearchHistoryCodec.decode("").size)
        assertEquals(0, SearchHistoryCodec.decode("not json").size)
        assertEquals(0, SearchHistoryCodec.decode("""{"songs":[]}""").size)
    }

    @Test
    fun `timestamp 缺失补 0（语义是"很久以前"，会被 TTL 立刻剪掉）`() {
        val back = SearchHistoryCodec.decode("""[{"id":9,"title":"x"}]""")
        assertEquals(0L, back[0].timestamp)
    }

    // -------------------------------------------------------- 契约自证 ----

    @Test
    fun `落盘 key 名单是显式的且与旧单字母名单不重叠`() {
        val stable = SearchHistoryCodec.stableKeys()
        val legacy = SearchHistoryCodec.legacyKeys()
        assertEquals(listOf("id", "title", "coverUrl", "subtitle", "timestamp"), stable)
        assertEquals(listOf("a", "b", "c", "d", "e"), legacy)
        assertTrue("两套 key 不该有交集", stable.intersect(legacy.toSet()).isEmpty())
    }

    @Test
    fun `codec 的 bit62 判据与 SourceIds 同源`() {
        assertEquals(SourceIds.isQqId(4611686018530183086L), SearchHistoryCodec.isQqId(4611686018530183086L))
        assertTrue(SearchHistoryCodec.isQqId(4611686018530183086L))
        assertTrue(!SearchHistoryCodec.isQqId(657666L))
    }

    @Test
    fun `Gson 直接反序列化裸 HistoryItem 读不出新形状（说明手写 codec 是必需的）`() {
        // 这条用例把「为什么不能用 Gson 反射整个 List<HistoryItem>」钉成一个事实：
        // 反射适配器只认字段名（会被 R8 改），认不出我们固定的 @SerializedName key
        // ——因为注解在 DTO 上，不在 HistoryItem 上。
        val type: java.lang.reflect.Type =
            object : com.google.gson.reflect.TypeToken<List<SearchHistoryManager.HistoryItem>>() {}.type
        val reflective: List<SearchHistoryManager.HistoryItem> = Gson().fromJson(realDeviceV1, type)
        assertTrue(
            "若这条变绿说明 Gson 能直接读旧形状，手写 codec 的必要性需要重新论证",
            reflective.isEmpty() || reflective[0].id != 4611686018530183086L
        )
    }
}
