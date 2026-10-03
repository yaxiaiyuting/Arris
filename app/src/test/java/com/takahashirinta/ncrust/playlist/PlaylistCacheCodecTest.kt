/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.0：歌单缓存的**隔离 + 迁移 + TTL** 单测（「加字段 = 加迁移逻辑 = 加单测」）。
 */

package com.takahashirinta.ncrust.playlist

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.Playlist
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.qq.QqPlaylistStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaylistCacheCodec] 的单测（v2.2.0）。
 *
 * 三条必须被钉住的契约：
 * 1. **缓存 key 隔离**：`source + ownerId + playlistId` 三者缺一不可；
 * 2. **迁移**：老 schema（没有 ownerId）**丢弃而不猜**，更高 schema **拒绝解释**；
 * 3. **离线可看**：TTL 过期只影响「新不新鲜」，**不影响能不能读出来**。
 */
class PlaylistCacheCodecTest {

    private val ownerA = "10001"
    private val ownerB = "20002"
    private val now = 1_700_000_000_000L

    private fun playlist(id: String, owner: String = ownerA, name: String = "pl-$id") = Playlist(
        key = PlaylistKey(MusicSource.QQMUSIC, id, owner),
        name = name,
        coverUrl = "https://y.gtimg.cn/x.jpg",
        trackCount = 3,
        isOwned = true,
        isFavorite = id == "6364233546",
        updatedAt = 1_618_134_646L,
        dirId = 1L,
    )

    private fun song(id: Long, mid: String = "mid$id", mediaMid: String = "mm$id") = SongItem(
        id = id,
        name = "song-$id",
        artists = listOf(ArtistItem(id = 1L, name = "artist")),
        album = AlbumItem(id = 2L, name = "album", picUrl = "https://y.gtimg.cn/a.jpg"),
        duration = 269_000L,
        source = MusicSource.QQMUSIC.key,
        sourceId = mid,
        mediaId = mediaMid,
        memberOnly = false,
    )

    // ------------------------------------------------------------ key 隔离 ----

    /**
     * **缓存 key 必须带 source + ownerId + playlistId。**
     * 少任何一段都会让两个不同的东西共用一个槽位。
     */
    @Test
    fun `缓存 key 由 source ownerId playlistId 三段构成`() {
        val k = QqPlaylistStore.detailKey(MusicSource.QQMUSIC, ownerA, "8591804616")
        assertTrue("必须含音源段", k.contains(MusicSource.QQMUSIC.key))
        assertTrue("必须含账号段", k.contains(ownerA))
        assertTrue("必须含歌单段", k.contains("8591804616"))
        assertEquals("detail:qqmusic:$ownerA:8591804616", k)
    }

    @Test
    fun `同一歌单在不同账号下的缓存 key 不同`() {
        val a = QqPlaylistStore.detailKey(MusicSource.QQMUSIC, ownerA, "1")
        val b = QqPlaylistStore.detailKey(MusicSource.QQMUSIC, ownerB, "1")
        assertNotEquals("双账号隔离的第一道闸门", a, b)
    }

    @Test
    fun `不同音源的同 id 歌单缓存 key 不同`() {
        val qq = QqPlaylistStore.detailKey(MusicSource.QQMUSIC, ownerA, "1")
        val ne = QqPlaylistStore.detailKey(MusicSource.NETEASE, ownerA, "1")
        assertNotEquals(qq, ne)
    }

    @Test
    fun `列表缓存 key 也带账号`() {
        assertNotEquals(
            QqPlaylistStore.listKey(MusicSource.QQMUSIC, ownerA),
            QqPlaylistStore.listKey(MusicSource.QQMUSIC, ownerB),
        )
    }

    // --------------------------------------------------------------- 往返 ----

    @Test
    fun `列表编码解码往返保真`() {
        val list = listOf(playlist("6364233546"), playlist("8591804616", name = "新建歌单1"))
        val raw = PlaylistCacheCodec.encodeList(ownerA, list, now)
        val read = PlaylistCacheCodec.decodeList(raw, ownerA, now)
        assertTrue(read is PlaylistCacheCodec.ListRead.Ok)
        val ok = read as PlaylistCacheCodec.ListRead.Ok
        assertEquals(2, ok.playlists.size)
        assertEquals("6364233546", ok.playlists[0].key.id)
        assertEquals(ownerA, ok.playlists[0].key.ownerId)
        assertEquals(MusicSource.QQMUSIC, ok.playlists[0].key.source)
        assertTrue("我喜欢 标记必须往返", ok.playlists[0].isFavorite)
        assertEquals(1L, ok.playlists[0].dirId)
        assertTrue(ok.fresh)
    }

    /**
     * 详情缓存存的是**歌曲**，[PlaylistTrack] 由它**无损反推** —— 两者必须一一对应。
     * 这条测试防的是「反推时丢条目导致下标错位」（那会让 order 与歌曲对不上）。
     */
    @Test
    fun `详情编码解码后歌曲与身份一一对应`() {
        val songs = listOf(song(263167477L, "002n3u4D2u8DLn", "002n3u4D2u8DLn"),
            song(102792543L, "0036avMK009ptj", "002OVQbr00xbs6"))
        val raw = PlaylistCacheCodec.encodeDetail(ownerA, songs, now, complete = true, total = 2)
        val read = PlaylistCacheCodec.decodeDetail(raw, ownerA, now)
        assertTrue(read is PlaylistCacheCodec.DetailRead.Ok)
        val ok = read as PlaylistCacheCodec.DetailRead.Ok
        assertEquals(2, ok.songs.size)
        assertEquals(2, ok.tracks.size)
        assertEquals(ok.songs.size, ok.tracks.size)
        ok.tracks.forEachIndexed { i, t ->
            assertEquals("order 必须等于下标", i, t.order)
            assertEquals(ok.songs[i].id, t.trackKey.id)
        }
        // ★ v2.1.0 的教训：mid 与 media_mid 必须各存各的，不得合并
        assertEquals("0036avMK009ptj", ok.songs[1].sourceId)
        assertEquals("002OVQbr00xbs6", ok.songs[1].mediaId)
        assertEquals("002OVQbr00xbs6", ok.tracks[1].trackKey.mediaId)
        assertTrue(ok.complete)
        assertEquals(2, ok.total)
    }

    // ------------------------------------------------- v2.6.1 · P0：艺人 mid ----

    /**
     * v2.6.1 · P0：**艺人 `mid` 必须穿过这层扁平 DTO**。
     *
     * 详情缓存不是「直接把 `SongItem` 丢给 Gson」，而是手工映射到扁平 DTO，
     * 所以 `ArtistItem` 上新增的字段**不会**自动跟过来。漏掉这一处的表现是
     * 「QQ 歌单详情 → 长按曲目 → 转到歌手」从「进 QQ 艺人页」退化成「跳搜索」——
     * 而搜索页在真机上看起来完全正常，只有点进去才知道降级了。
     *
     * ncm 一侧不受影响（它的身份就是 `id`），所以更不能靠「ncm 没事」来发现。
     */
    @Test
    fun `艺人 mid 穿过详情缓存往返保真`() {
        val withMid = song(97773L, "0039MnYb0qxYhV", "003Qui1q2u1Zho").copy(
            artists = listOf(ArtistItem(id = 4558L, name = "周杰伦", mid = "0025NhlN2yWrP4")),
        )
        val raw = PlaylistCacheCodec.encodeDetail(ownerA, listOf(withMid), now, complete = true, total = 1)
        val ok = PlaylistCacheCodec.decodeDetail(raw, ownerA, now) as PlaylistCacheCodec.DetailRead.Ok
        assertEquals("0025NhlN2yWrP4", ok.songs.single().artists!!.single().mid)
        assertEquals(4558L, ok.songs.single().artists!!.single().id)
    }

    /**
     * 本字段出现**之前**落盘的条目里没有 `mid` 这个 key（Gson 走 Unsafe、不调用构造函数）。
     * 读出来必须是 `null`（=「没有字符串身份」⇒ 跳搜索），**不能**是空串，
     * 也**不能**抛异常 —— 那会让老用户的 QQ 歌单详情整包打不开。
     */
    @Test
    fun `老缓存条目缺 mid 时读出 null 而不是崩或空串`() {
        val withMid = song(97773L, "0039MnYb0qxYhV", "003Qui1q2u1Zho").copy(
            artists = listOf(ArtistItem(id = 4558L, name = "周杰伦", mid = "0025NhlN2yWrP4")),
        )
        val raw = PlaylistCacheCodec.encodeDetail(ownerA, listOf(withMid), now, complete = true, total = 1)
        // 模拟旧版本写下的 JSON：把 artists 里那个新 key 抹掉，只留 id/name。
        //
        // 两层嵌套：信封是 `{…,"songsJson":"<转义后的歌曲数组>"}`（数组必须以**字符串**字段
        // 承载，见「R8 泛型签名回归」那条用例），所以要先解外层再解内层。
        // 用 JSON 树而不是正则改写：正则会因为字段顺序 / 空白差异**静默不生效**，
        // 而「夹具没生效的测试」比没有测试更糟 —— 它会一直绿。
        val envelope = com.google.gson.JsonParser.parseString(raw).asJsonObject
        val songsArray = com.google.gson.JsonParser
            .parseString(envelope.get("songsJson").asString).asJsonArray
        var stripped = 0
        songsArray.forEach { el ->
            el.asJsonObject.getAsJsonArray("artists")?.forEach { a ->
                if (a.asJsonObject.remove("mid") != null) stripped++
            }
        }
        assertEquals("夹具没生效：一个 mid 都没抹掉", 1, stripped)
        envelope.addProperty("songsJson", songsArray.toString())
        val legacy = envelope.toString()
        assertFalse("夹具没生效：mid 还在 JSON 里", legacy.contains("0025NhlN2yWrP4"))
        val ok = PlaylistCacheCodec.decodeDetail(legacy, ownerA, now) as PlaylistCacheCodec.DetailRead.Ok
        val artist = ok.songs.single().artists!!.single()
        assertEquals(4558L, artist.id)
        assertEquals("周杰伦", artist.name)
        assertNull("缺字段 ⇒ null（不是空串）", artist.mid)
    }

    // --------------------------------------------------------------- 隔离 ----

    /**
     * 读侧第二次校验：JSON 里的 ownerId 与当前账号不一致 ⇒ **整包丢弃**。
     * 防的是「同一份 JSON 被写进了错误的 key」这种写侧 bug。
     */
    @Test
    fun `读到别的账号的缓存必须丢弃`() {
        val raw = PlaylistCacheCodec.encodeList(ownerA, listOf(playlist("1")), now)
        val read = PlaylistCacheCodec.decodeList(raw, expectedOwnerId = ownerB, now = now)
        assertTrue(read is PlaylistCacheCodec.ListRead.Dropped)
        assertEquals(
            PlaylistCacheCodec.DropReason.OWNER_MISMATCH,
            (read as PlaylistCacheCodec.ListRead.Dropped).reason,
        )
    }

    @Test
    fun `详情读到别的账号的缓存也必须丢弃`() {
        val raw = PlaylistCacheCodec.encodeDetail(ownerA, listOf(song(1L)), now, true, 1)
        val read = PlaylistCacheCodec.decodeDetail(raw, expectedOwnerId = ownerB, now = now)
        assertEquals(
            PlaylistCacheCodec.DropReason.OWNER_MISMATCH,
            (read as PlaylistCacheCodec.DetailRead.Dropped).reason,
        )
    }

    // --------------------------------------------------------------- 迁移 ----

    /**
     * **v1 → v2 迁移：丢弃，不猜。**
     *
     * v1 条目没有 `ownerId`。给它「补上当前账号」是最危险的迁移 ——
     * 它把「不知道属于谁」断言成「属于当前账号」，于是 A 的歌单会在 B 下显示出来。
     * 所以正确行为是**丢弃并重新拉取**。
     */
    @Test
    fun `v1 无 ownerId 的老缓存被丢弃而不是补默认账号`() {
        val legacy = """
            {"version":1,"savedAt":$now,"playlists":[
              {"id":"1","source":"qqmusic","name":"旧歌单","trackCount":2}
            ]}
        """.trimIndent()
        val read = PlaylistCacheCodec.decodeList(legacy, expectedOwnerId = ownerA, now = now)
        assertEquals(
            PlaylistCacheCodec.DropReason.LEGACY_NO_OWNER,
            (read as PlaylistCacheCodec.ListRead.Dropped).reason,
        )
    }

    /** 「字段缺失」等同于 v0：Gson 不调用构造函数，缺 `version` 时读到的是 0。 */
    @Test
    fun `缺少 version 字段按老 schema 处理`() {
        val noVersion = """{"savedAt":$now,"playlists":[]}"""
        val read = PlaylistCacheCodec.decodeList(noVersion, expectedOwnerId = ownerA, now = now)
        assertEquals(
            PlaylistCacheCodec.DropReason.LEGACY_NO_OWNER,
            (read as PlaylistCacheCodec.ListRead.Dropped).reason,
        )
    }

    /** 用户装过更新版本又降级回来：**拒绝解释**比自己猜一遍安全。 */
    @Test
    fun `更高的 schema 版本被拒绝解释`() {
        val future = """
            {"version":${PlaylistCacheCodec.SCHEMA_VERSION + 1},"ownerId":"$ownerA",
             "savedAt":$now,"playlists":[{"id":"1","source":"qqmusic","ownerId":"$ownerA"}]}
        """.trimIndent()
        val read = PlaylistCacheCodec.decodeList(future, expectedOwnerId = ownerA, now = now)
        assertEquals(
            PlaylistCacheCodec.DropReason.FUTURE_SCHEMA,
            (read as PlaylistCacheCodec.ListRead.Dropped).reason,
        )
    }

    @Test
    fun `版本判定三条分支`() {
        assertEquals(
            PlaylistCacheCodec.VersionVerdict.Current,
            PlaylistCacheCodec.checkVersion(PlaylistCacheCodec.SCHEMA_VERSION),
        )
        assertEquals(
            PlaylistCacheCodec.VersionVerdict.LegacyNoOwner,
            PlaylistCacheCodec.checkVersion(1),
        )
        assertEquals(
            PlaylistCacheCodec.VersionVerdict.LegacyNoOwner,
            PlaylistCacheCodec.checkVersion(0),
        )
        assertEquals(
            PlaylistCacheCodec.VersionVerdict.Future,
            PlaylistCacheCodec.checkVersion(PlaylistCacheCodec.SCHEMA_VERSION + 1),
        )
    }

    /**
     * **条目级**的「缺字段 ≠ 空值」：一条 `source` 缺失的条目要被丢掉，
     * 而**不能**因为 [MusicSource.fromKey] 对未知值回落 ncm、就被静默当成 ncm 歌单。
     */
    @Test
    fun `条目缺 source 时丢弃而不是回落ncm`() {
        // 内层数组存成**字符串**（playlistsJson）—— 这是 codec 的当前形状，
        // 为的是绕开 R8 丢泛型签名导致 Gson 产出 LinkedTreeMap 的 release 崩溃
        // （见 PlaylistCacheCodec.ListEnvelope 的 KDoc 与 proguard-rules.pro）。
        val inner = """[{"id":"1","ownerId":"$ownerA","source":"qqmusic","name":"好条目"},""" +
            """{"id":"2","ownerId":"$ownerA","name":"坏条目——没有 source"}]"""
        val mixed = """
            {"version":${PlaylistCacheCodec.SCHEMA_VERSION},"ownerId":"$ownerA","savedAt":$now,
             "playlistsJson":${org.json.JSONObject.quote(inner)}}
        """.trimIndent()
        val read = PlaylistCacheCodec.decodeList(mixed, expectedOwnerId = ownerA, now = now)
        val ok = read as PlaylistCacheCodec.ListRead.Ok
        assertEquals("坏条目必须被丢掉", 1, ok.playlists.size)
        assertEquals("1", ok.playlists[0].key.id)
        assertEquals(MusicSource.QQMUSIC, ok.playlists[0].key.source)
    }

    @Test
    fun `详情条目 id 非法时丢弃`() {
        val inner = """[{"id":0,"source":"qqmusic"},{"id":5,"source":"qqmusic","name":"ok"}]"""
        val bad = """
            {"version":${PlaylistCacheCodec.SCHEMA_VERSION},"ownerId":"$ownerA","savedAt":$now,
             "complete":true,"total":2,
             "songsJson":${org.json.JSONObject.quote(inner)}}
        """.trimIndent()
        val ok = PlaylistCacheCodec.decodeDetail(bad, ownerA, now) as PlaylistCacheCodec.DetailRead.Ok
        assertEquals(1, ok.songs.size)
        assertEquals(1, ok.tracks.size)
        // order 保留原位置（id=0 的那条占了第 0 位，id=5 的是第 1 位）
        assertEquals(1, ok.tracks[0].order)
    }

    // ------------------------------------------------------------- 坏数据 ----

    @Test
    fun `坏 JSON 与空值都按丢弃处理且不抛异常`() {
        assertEquals(
            PlaylistCacheCodec.DropReason.NONE,
            (PlaylistCacheCodec.decodeList(null, ownerA, now) as PlaylistCacheCodec.ListRead.Dropped).reason,
        )
        assertEquals(
            PlaylistCacheCodec.DropReason.NONE,
            (PlaylistCacheCodec.decodeList("", ownerA, now) as PlaylistCacheCodec.ListRead.Dropped).reason,
        )
        assertEquals(
            PlaylistCacheCodec.DropReason.MALFORMED,
            (PlaylistCacheCodec.decodeList("{not json", ownerA, now) as PlaylistCacheCodec.ListRead.Dropped).reason,
        )
        assertEquals(
            PlaylistCacheCodec.DropReason.MALFORMED,
            (PlaylistCacheCodec.decodeList("[]", ownerA, now) as PlaylistCacheCodec.ListRead.Dropped).reason,
        )
    }

    // ----------------------------------------------------------------- TTL ----

    /**
     * **离线可看**：TTL 过期只把 `fresh` 置 false，**数据照样读得出来**。
     * 若过期就返回 Dropped，仓库层就没有东西可以降级展示。
     */
    @Test
    fun `TTL 过期仍能读出数据只是不新鲜`() {
        val raw = PlaylistCacheCodec.encodeList(ownerA, listOf(playlist("1")), now)
        val later = now + PlaylistCacheCodec.DEFAULT_TTL_MS + 1
        val ok = PlaylistCacheCodec.decodeList(raw, ownerA, later) as PlaylistCacheCodec.ListRead.Ok
        assertEquals("数据仍然在", 1, ok.playlists.size)
        assertFalse("但不新鲜了", ok.fresh)
    }

    @Test
    fun `TTL 边界是左闭右开`() {
        assertTrue(PlaylistCacheCodec.isFresh(now, now, 1000L))
        assertTrue(PlaylistCacheCodec.isFresh(now, now + 999L, 1000L))
        assertFalse("恰好等于 TTL 算过期", PlaylistCacheCodec.isFresh(now, now + 1000L, 1000L))
        assertFalse(PlaylistCacheCodec.isFresh(now, now + 1001L, 1000L))
    }

    /** `savedAt` 缺失（0）**一律算过期** —— 「缺字段按 miss 处理」那条规矩。 */
    @Test
    fun `缺少时间戳的老条目算过期而不是永远新鲜`() {
        assertFalse(PlaylistCacheCodec.isFresh(0L, now, PlaylistCacheCodec.DEFAULT_TTL_MS))
        assertFalse(PlaylistCacheCodec.isFresh(-1L, now, PlaylistCacheCodec.DEFAULT_TTL_MS))
    }

    /** 时钟回拨（savedAt 在未来）不信任，按过期处理；下次联网即自愈。 */
    @Test
    fun `时钟回拨按过期处理`() {
        assertFalse(PlaylistCacheCodec.isFresh(now + 10_000L, now, PlaylistCacheCodec.DEFAULT_TTL_MS))
    }

    /**
     * **release 崩溃的回归测试**（PCL110 实测，retrace 后定位到 decodeList）。
     *
     * 症状：debug 正常，release 一进歌单页就 `ClassCastException:
     * LinkedTreeMap cannot be cast to PlaylistDto`。
     * 根因：R8 对 `playlist.**` 没有 keep 规则时会丢掉 DTO 字段的**泛型签名 attribute**，
     * Gson 于是把 `List<PlaylistDto>` 当成裸 `List`、元素反序列化成 `LinkedTreeMap`。
     *
     * 修法是把内层数组存成**字符串**并用编译期捕获的 `TypeToken` 解析，
     * 从而**完全不依赖字段泛型签名**。这条测试钉住「信封里必须是字符串字段」这一形状：
     * 谁要是改回泛型 List，这里会立刻红 —— 而不是等到 release 装机才崩。
     */
    @Test
    fun `信封里的数组必须以字符串字段承载——R8 泛型签名回归`() {
        val raw = PlaylistCacheCodec.encodeList(ownerA, listOf(playlist("1")), now)
        assertTrue("列表数组必须落在 playlistsJson 字符串字段里", raw.contains("playlistsJson"))
        assertFalse("不得再出现依赖泛型签名的 playlists 数组字段", raw.contains("\"playlists\":["))
        assertTrue(
            "字符串字段仍要能被解回来",
            PlaylistCacheCodec.decodeList(raw, ownerA, now) is PlaylistCacheCodec.ListRead.Ok,
        )

        val rawDetail = PlaylistCacheCodec.encodeDetail(ownerA, listOf(song(5L)), now, true, 1)
        assertTrue("详情数组必须落在 songsJson 字符串字段里", rawDetail.contains("songsJson"))
        assertFalse("不得再出现依赖泛型签名的 songs 数组字段", rawDetail.contains("\"songs\":["))
        assertTrue(
            PlaylistCacheCodec.decodeDetail(rawDetail, ownerA, now) is PlaylistCacheCodec.DetailRead.Ok,
        )
    }
}
