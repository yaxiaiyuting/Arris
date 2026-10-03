/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.2 · P0：收藏专辑表新增 `albumMid` 的**迁移单测**（AGENTS.md：加字段 = 加迁移逻辑 = 加单测）。
 */

package com.takahashirinta.ncrust.library

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ncrust_library` / `saved_albums` 新增 `albumMid` 的迁移契约（v2.6.2 · P0）。
 *
 * ## 为什么这张表要加这个字段
 *
 * 这张表的每一条都是「用户明确收藏过的一张专辑」。收藏页点进去走的是
 * `NavRoutes.album(source, id)` 那条**带音源**的两段路由 —— 而两段路由的 `id`
 * 对 QQ 必须是 `albumMID`。少了它，将来 QQ 专辑进这张表时只能靠数字 id 猜源，
 * 那正是本版修掉的形状（陈奕迅 `22276` → 陈小云）。
 *
 * ## 三种落盘形状与它们的结论
 *
 * | 形状 | 例子 | `albumMid` | 语义 |
 * |---|---|---|---|
 * | v2.6.2 起的新形状 | `{"albumId":1,…,"albumMid":"004Z85XP1c25b7"}` | 该值 | 身份可信 |
 * | v2.2.x~v2.6.1 的稳定名字 | `{"albumId":1,"name":"x",…}`（无 `albumMid`） | `null` | **身份不可信** |
 * | v1 的 R8 单字母 | `{"a":1,"b":"x","c":"y","d":"z","e":3}` | `null` | **身份不可信** |
 *
 * 第三行那条是本次最容易写错的地方：v1 的结构**只写过五个字段**，所以
 * **不往 `LEGACY_KEYS` 里补第六个字母**（补 `f` 等于发明一条没有取证支撑的映射）。
 * 这条纪律由 [legacyKeys 里没有第六个字母] 与 [v1 单字母形状读出来的身份不可信] 两条用例钉住。
 *
 * ## 与 v1.9.2 音译字段那条教训的关系
 *
 * 判「老条目」只看**字段缺失**（key 不在），**不看空串** —— 空串是"服务端确实没有"
 * 的权威结论，把它当缺失会让每次都重取。所以读路径用
 * `takeIf { it.isNotBlank() }`，[空串 albumMid 读成 null] 就是这条的可执行版本。
 */
class SavedAlbumMidMigrationTest {

    private val qqMid = "004Z85XP1c25b7"

    // ------------------------------------------------------------ 1. 读：三种形状

    @Test
    fun `有 albumMid 的条目身份可信`() {
        val json = """[{"albumId":6451,"name":"What's Going On…?","picUrl":"https://c","artist":"陈奕迅","songCount":15,"albumMid":"$qqMid"}]"""
        val album = SavedAlbumCodec.decode(json).single()
        assertEquals(qqMid, album.mid)
        assertTrue("有字符串身份 ⇒ 可信", album.identityTrusted)
    }

    @Test
    fun `老稳定名字形状（没有 albumMid 这个 key）读成 null 且身份不可信`() {
        // 这是 v2.2.x ~ v2.6.1 落盘的真实形状。
        val json = """[{"albumId":6451,"name":"What's Going On…?","picUrl":"https://c","artist":"陈奕迅","songCount":15}]"""
        val album = SavedAlbumCodec.decode(json).single()
        assertNull("字段缺失 ⇒ null（不是空串）", album.mid)
        assertFalse("身份不可信 ⇒ 调用方必须跳搜索，绝不拿数字 id 去别的源猜", album.identityTrusted)
    }

    @Test
    fun `v1 单字母形状读出来的身份不可信`() {
        // `mapping.txt` 取证的真机形状：`library.AlbumInfo -> G4.a`，字段 a~e。
        val json = """[{"a":6451,"b":"专辑名","c":"https://c","d":"艺人","e":15}]"""
        val album = SavedAlbumCodec.decode(json).single()
        assertEquals(6451L, album.albumId)
        assertEquals("专辑名", album.name)
        assertNull("v1 从没写过第六个字段 ⇒ 只能是 null", album.mid)
        assertFalse(album.identityTrusted)
    }

    @Test
    fun `legacyKeys 里没有第六个字母`() {
        // 「凭空补一个 f」是把 bug 固化进契约 —— 比读不出来更糟：
        // 它会安静地把某个别的字段读成专辑身份。这条用例让"顺手补一个"必须显式改测试。
        assertEquals(listOf("a", "b", "c", "d", "e"), SavedAlbumCodec.legacyKeys())
        assertEquals(
            listOf("albumId", "name", "picUrl", "artist", "songCount", "albumMid"),
            SavedAlbumCodec.stableKeys(),
        )
    }

    // ------------------------------------------------------------ 2. 写的形状

    @Test
    fun `写时统一带 albumMid——有身份就落盘`() {
        val album = AlbumInfo(
            albumId = 6451L, name = "x", picUrl = "https://c", artist = "a", songCount = 1, mid = qqMid,
        )
        val json = SavedAlbumCodec.encode(listOf(album))
        assertTrue("有身份必须写出去：$json", json.contains("\"albumMid\":\"$qqMid\""))
        assertEquals(listOf(album), SavedAlbumCodec.decode(json))
    }

    @Test
    fun `没有身份时不写这个 key——避免把「字段缺失」与「字段为 null」混成一种形状`() {
        val album = AlbumInfo(albumId = 6451L, name = "x", picUrl = "https://c", artist = "a", songCount = 1)
        val json = SavedAlbumCodec.encode(listOf(album))
        assertFalse("本该跳过的可空字段被写出来了：$json", json.contains("albumMid"))
    }

    @Test
    fun `编码解码往返保留 albumMid`() {
        val albums = listOf(
            AlbumInfo(albumId = 1L, name = "同源", picUrl = "p", artist = "a", songCount = 3, mid = qqMid),
            AlbumInfo(albumId = 2L, name = "无身份", picUrl = "p", artist = "a", songCount = 4, mid = null),
        )
        assertEquals(albums, SavedAlbumCodec.decode(SavedAlbumCodec.encode(albums)))
    }

    // ------------------------------------------------------------ 3. 空串 vs 缺失

    @Test
    fun `空串 albumMid 读成 null`() {
        // 与「缺失」同一处置：没有可用的字符串身份。
        val json = """[{"albumId":1,"name":"x","picUrl":"p","artist":"a","songCount":1,"albumMid":""}]"""
        val album = SavedAlbumCodec.decode(json).single()
        assertNull(album.mid)
        assertFalse(album.identityTrusted)
    }

    @Test
    fun `纯空白 albumMid 读成 null`() {
        val json = """[{"albumId":1,"name":"x","picUrl":"p","artist":"a","songCount":1,"albumMid":"   "}]"""
        assertNull(SavedAlbumCodec.decode(json).single().mid)
    }

    // ------------------------------------------------------------ 4. 声明顺序兜底

    @Test
    fun `未知字母表带第六个值时按声明顺序读出 albumMid`() {
        val unknown = """[{"p":321,"q":"专辑","r":"https://c","s":"艺人","t":7,"u":"$qqMid"}]"""
        val a = SavedAlbumCodec.decode(unknown).single()
        assertEquals(321L, a.albumId)
        assertEquals(7, a.songCount)
        assertEquals("第 6 个声明位就是 albumMid", qqMid, a.mid)
        assertTrue(a.identityTrusted)
    }

    @Test
    fun `未知字母表只有五个值时 albumMid 为 null 而不是去猜`() {
        // **不许**按"像不像 base62"猜第 5 个值是不是 mid —— 那正是铁律 14 禁止的猜。
        val unknown = """[{"p":321,"q":"专辑","r":"https://c","s":"艺人","t":7}]"""
        val a = SavedAlbumCodec.decode(unknown).single()
        assertNull(a.mid)
        assertFalse(a.identityTrusted)
    }

    @Test
    fun `声明顺序兜底也把空串当成缺失`() {
        val unknown = """[{"p":321,"q":"专辑","r":"https://c","s":"艺人","t":7,"u":""}]"""
        assertNull(SavedAlbumCodec.decode(unknown).single().mid)
    }

    // ------------------------------------------------------------ 5. 坏数据隔离

    @Test
    fun `albumMid 是非法类型时整条仍然读得出来（缺的不是身份的一半）`() {
        // `albumMid` 缺失/读不出的处置是「身份不可信」，**不是**「丢掉这条收藏」——
        // albumId 才是 LazyColumn 的 key。这条用例防的是"顺手把 null 也当坏条目丢"。
        val weird = JsonParser.parseString(
            """{"albumId":9,"name":"n","picUrl":"p","artist":"a","songCount":2,"albumMid":{"o":1}}""",
        ).asJsonObject
        val album = SavedAlbumCodec.decodeEntry(weird)
        assertEquals(9L, album!!.albumId)
        assertNull(album.mid)
        assertFalse(album.identityTrusted)
    }

    @Test
    fun `坏条目逐条丢弃的既有行为不受影响`() {
        val mixed = """[{"albumId":1,"name":"A","albumMid":"$qqMid"},{"albumId":0,"name":"坏"},{"albumId":3,"name":"C"}]"""
        val albums = SavedAlbumCodec.decode(mixed)
        assertEquals(listOf(1L, 3L), albums.map { it.albumId })
        assertEquals(qqMid, albums[0].mid)
        assertNull(albums[1].mid)
    }
}
