/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 · B 单元测试：本地歌单的**编解码与结构迁移**。
 *
 * 铁律 5「加字段 = 加迁移逻辑 = 加单测」的落点。本版**只有一个**新字段需要迁移关怀，
 * 但它是致命的一个：`tombstoned`。缺字段补 `true` 会让整张歌单同步不进任何歌；
 * 丢弃条目会让用户的数据消失。所以这里有两个**反向断言**专门钉住「不能那么做」。
 */

package com.takahashirinta.ncrust.local

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.source.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistCodecTest {

    private val now = 1_700_000_000_000L
    private val qqKey = PlaylistKey(MusicSource.QQMUSIC, "8591804616", "1152921504891728649")

    // -------------------------------------------------------- 歌单元数据 ----

    @Test
    fun `歌单列表编解码往返保真`() {
        val list = listOf(
            LocalPlaylist(qqKey, "QQ 歌单", now, now + 1, lastSyncedAt = now + 2, dirId = 201L),
            LocalPlaylist(LocalPlaylistSync.localOnlyKey("x-0"), "纯手动", now, now),
        )
        val back = LocalPlaylistCodec.decodePlaylists(LocalPlaylistCodec.encodePlaylists(list))
        assertEquals(list, back)
    }

    @Test
    fun `缺 ownerId 的歌单条目被丢弃 而不是补一个账号`() {
        // v2.2.0 的教训：把「不知道属于谁」断言成属于某个账号是最危险的迁移。
        val json = """[{"source":"qqmusic","id":"123","name":"lost"}]"""
        assertTrue(LocalPlaylistCodec.decodePlaylists(json).isEmpty())
    }

    @Test
    fun `空串与缺失等价 都被丢弃`() {
        val json = """[{"source":"qqmusic","id":"","ownerId":"u","name":"x"}]"""
        assertTrue(LocalPlaylistCodec.decodePlaylists(json).isEmpty())
    }

    @Test
    fun `坏 JSON 返回空列表 不抛`() {
        assertTrue(LocalPlaylistCodec.decodePlaylists("{not json").isEmpty())
        assertTrue(LocalPlaylistCodec.decodePlaylists(null).isEmpty())
        assertTrue(LocalPlaylistCodec.decodePlaylists("").isEmpty())
    }

    // ------------------------------------------------------------ 曲目 ----

    @Test
    fun `曲目编解码往返保真（含 tombstone 与载荷）`() {
        val tracks = listOf(
            LocalPlaylistTrack(
                trackKey = TrackKey(MusicSource.QQMUSIC, 42L, "mid", "media"),
                origin = LocalTrackOrigin.REMOTE,
                addedAt = now,
                tombstoned = false,
                song = com.takahashirinta.ncrust.network.SongItem(
                    id = 42L, name = "歌", artists = null, album = null, duration = 1000L,
                    source = "qqmusic", sourceId = "mid", mediaId = "media",
                ),
            ),
            LocalPlaylistTrack(
                trackKey = TrackKey(MusicSource.NETEASE, 7L),
                origin = LocalTrackOrigin.LOCAL,
                addedAt = now + 1,
                tombstoned = true,
            ),
        )
        val back = LocalPlaylistCodec.decodeTracks(LocalPlaylistCodec.encodeTracks(tracks))
        assertEquals(2, back.size)
        assertEquals(tracks[0].trackKey, back[0].trackKey)
        assertEquals(tracks[0].origin, back[0].origin)
        assertEquals(tracks[0].tombstoned, back[0].tombstoned)
        assertEquals("歌", back[0].song?.name)
        assertEquals(tracks[1].trackKey, back[1].trackKey)
        assertEquals(LocalTrackOrigin.LOCAL, back[1].origin)
        assertTrue(back[1].tombstoned)
    }

    // -------------------------------------------------- ★ 迁移：tombstoned ----

    @Test
    fun `v0 裸数组迁移：缺 tombstoned 补 false 而不是 true`() {
        // v0 的历史形状：一个裸数组，没有任何版本号，也没有 tombstoned 键。
        val v0 = """[{"source":"netease","trackId":11,"origin":"REMOTE","addedAt":100}]"""
        val back = LocalPlaylistCodec.decodeTracks(v0)
        assertEquals(1, back.size)
        assertFalse(
            "缺 tombstoned 必须补 false —— 补 true 会让整张歌单同步不进任何歌",
            back.single().tombstoned,
        )
    }

    @Test
    fun `v0 迁移不会丢弃条目（丢弃 = 用户数据静默消失）`() {
        val v0 = """[{"source":"netease","trackId":11},{"source":"netease","trackId":12}]"""
        assertEquals(2, LocalPlaylistCodec.decodeTracks(v0).size)
    }

    @Test
    fun `显式的 tombstoned 等于 true 会被保留（迁移不能把用户的删除意图抹掉）`() {
        val json = """[{"source":"netease","trackId":11,"tombstoned":true}]"""
        assertTrue(LocalPlaylistCodec.decodeTracks(json).single().tombstoned)
    }

    @Test
    fun `显式的 tombstoned 等于 false 与缺失走同一条路`() {
        val json = """[{"source":"netease","trackId":11,"tombstoned":false}]"""
        assertFalse(LocalPlaylistCodec.decodeTracks(json).single().tombstoned)
    }

    @Test
    fun `缺 origin 时按 REMOTE 处理（保守的一侧）`() {
        val json = """[{"source":"netease","trackId":11}]"""
        assertEquals(LocalTrackOrigin.REMOTE, LocalPlaylistCodec.decodeTracks(json).single().origin)
    }

    @Test
    fun `未知 origin 取值不抛 回落 REMOTE`() {
        val json = """[{"source":"netease","trackId":11,"origin":"SOMETHING_NEW"}]"""
        assertEquals(LocalTrackOrigin.REMOTE, LocalPlaylistCodec.decodeTracks(json).single().origin)
    }

    @Test
    fun `缺 source 时用 id 的标志位反推音源 不猜成ncm`() {
        val qqId = com.takahashirinta.ncrust.source.SourceIds.qqId(123L, "mid")
        val json = """[{"trackId":$qqId}]"""
        val back = LocalPlaylistCodec.decodeTracks(json).single()
        assertEquals(MusicSource.QQMUSIC, back.trackKey.source)
    }

    @Test
    fun `更高的 schema 版本被拒绝解释 而不是猜一个未来格式`() {
        val json = """{"schemaVersion":99,"tracks":[{"source":"netease","trackId":11}]}"""
        assertTrue(LocalPlaylistCodec.decodeTracks(json).isEmpty())
    }

    @Test
    fun `当前 schema 版本的信封正常解析`() {
        val json = """{"schemaVersion":${LocalPlaylistCodec.SCHEMA_VERSION},"tracks":[{"source":"netease","trackId":11}]}"""
        assertEquals(1, LocalPlaylistCodec.decodeTracks(json).size)
    }

    @Test
    fun `缺 trackId 或 id 非正的条目被丢弃`() {
        assertTrue(LocalPlaylistCodec.decodeTracks("""[{"source":"netease"}]""").isEmpty())
        assertTrue(LocalPlaylistCodec.decodeTracks("""[{"source":"netease","trackId":0}]""").isEmpty())
        assertTrue(LocalPlaylistCodec.decodeTracks("""[{"source":"netease","trackId":-5}]""").isEmpty())
    }

    @Test
    fun `曲目 坏 JSON 返回空列表 不抛`() {
        assertTrue(LocalPlaylistCodec.decodeTracks("{oops").isEmpty())
        assertTrue(LocalPlaylistCodec.decodeTracks(null).isEmpty())
        assertTrue(LocalPlaylistCodec.decodeTracks("[]").isEmpty())
    }

    @Test
    fun `载荷坏掉时条目仍在（丢载荷可以 丢条目不行）`() {
        val json = """[{"source":"netease","trackId":11,"song":"{broken"}]"""
        val back = LocalPlaylistCodec.decodeTracks(json)
        assertEquals(1, back.size)
        assertNull(back.single().song)
    }

    @Test
    fun `dirId 是载荷 能往返 但不在身份里`() {
        val pl = LocalPlaylist(qqKey, "n", now, now, lastSyncedAt = 0L, dirId = 201L)
        val back = LocalPlaylistCodec.decodePlaylists(LocalPlaylistCodec.encodePlaylists(listOf(pl))).single()
        assertEquals(201L, back.dirId)
        assertEquals(qqKey, back.key)
    }

    @Test
    fun `歌单 key 的三元组决定身份 同 id 不同账号是两个歌单`() {
        val a = LocalPlaylist(PlaylistKey(MusicSource.QQMUSIC, "1", "alice"), "n", now, now)
        val b = LocalPlaylist(PlaylistKey(MusicSource.QQMUSIC, "1", "bob"), "n", now, now)
        val back = LocalPlaylistCodec.decodePlaylists(
            LocalPlaylistCodec.encodePlaylists(listOf(a, b)),
        )
        assertEquals(2, back.size)
        assertNotNull(back.firstOrNull { it.key.ownerId == "alice" })
        assertNotNull(back.firstOrNull { it.key.ownerId == "bob" })
    }

    // ================================================================ v2.3.0 · B
    // ★ **落盘字段名是对外契约**（release-only 回归）。
    //
    // 第一次 release 真机验证时读回 `ncrust_local_playlists.xml`，发现 JSON 是
    // `{"a":"netease","b":"local:...","c":"anonymous","d":"v230test",...}`
    // —— R8 把 DTO 的字段名整体混淆成了单字母。同一个 APK 内读写自洽，所以**不崩**，
    // 但持久化结构的字段名成了「构建的副产物」：下一次构建的映射一变，
    // 老数据一条都读不出来 ⇒ 用户的本地歌单在升级时**静默消失**。
    //
    // 修法是 `app/proguard-rules.pro` 里的 `-keep class …ncrust.local.** { *; }`。
    // 下面这组用例是它的第二道防线：**字段改名会让这里变红**，
    // 而不是让用户的数据消失。debug 单测跑不到 R8，所以这里钉的是
    // 「DTO 的字段名本身」——那正是 keep 规则要保住的东西。

    @Test
    fun `落盘字段名是契约——歌单的一级 key 必须可读`() {
        val json = LocalPlaylistCodec.encodePlaylists(
            listOf(LocalPlaylist(qqKey, "n", now, now, lastSyncedAt = 1L, dirId = 2L)),
        )
        listOf("source", "id", "ownerId", "name", "createdAt", "updatedAt", "lastSyncedAt", "dirId")
            .forEach { key ->
                assertTrue("歌单 JSON 缺少字段名 \"$key\"：$json", json.contains("\"$key\""))
            }
    }

    @Test
    fun `落盘字段名是契约——曲目的一级 key 必须可读（含 tombstoned）`() {
        val json = LocalPlaylistCodec.encodeTracks(
            listOf(
                LocalPlaylistTrack(
                    trackKey = TrackKey(MusicSource.QQMUSIC, 42L, "mid", "media"),
                    origin = LocalTrackOrigin.LOCAL,
                    addedAt = now,
                    tombstoned = true,
                    song = com.takahashirinta.ncrust.network.SongItem(
                        id = 42L, name = "歌", artists = null, album = null, duration = null,
                    ),
                ),
            ),
        )
        listOf("schemaVersion", "tracks", "source", "trackId", "sourceId", "mediaId",
                "origin", "addedAt", "tombstoned", "song").forEach { key ->
            assertTrue("曲目 JSON 缺少字段名 \"$key\"：$json", json.contains("\"$key\""))
        }
        // 值也要可读（枚举用 name 落盘，不是 ordinal）——ordinal 会在中间插值时静默指错。
        assertTrue(json.contains("\"LOCAL\""))
    }

    @Test
    fun `schemaVersion 落在 JSON 里（迁移的判据不能只是内存里的常量）`() {
        val json = LocalPlaylistCodec.encodeTracks(emptyList())
        assertTrue(json.contains("\"schemaVersion\":${LocalPlaylistCodec.SCHEMA_VERSION}"))
    }

    @Test
    fun `单字母 key 的样本会被当成缺字段处理（R8 混淆后的老数据不会读成垃圾）`() {
        // 模拟 v2.3.0 首个 release 包写下的、被 R8 混淆过的那份数据。
        val obfuscated = """[{"a":"netease","b":"11","c":"REMOTE","d":100}]"""
        // 所有必填字段都读不到 ⇒ 条目被丢弃（而不是造出一条 id=0 的假记录）
        assertTrue(LocalPlaylistCodec.decodeTracks(obfuscated).isEmpty())
    }
}
