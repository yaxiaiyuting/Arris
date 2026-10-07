/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.0：歌单数据模型与按源分组的单测。
 */

package com.takahashirinta.ncrust.playlist

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.Playlist
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.source.PlaylistTrack
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.source.groupPlaylistsBySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaylistKey] / [PlaylistTrack] / [groupPlaylistsBySource] 的单测（v2.2.0）。
 *
 * 重点防两件事：
 * 1. **跨源/跨账号串号**（身份必须含 source 与 ownerId）；
 * 2. **跨源合并**（分组函数绝不允许把两个平台的歌单混成一组）。
 */
class PlaylistModelsTest {

    // ------------------------------------------------------------ 身份 ----

    @Test
    fun `PlaylistKey 相等性含 source 与 ownerId`() {
        val a = PlaylistKey(MusicSource.QQMUSIC, "1", "10001")
        assertEquals(a, PlaylistKey(MusicSource.QQMUSIC, "1", "10001"))
        assertNotEquals("同 id 不同音源不是同一个歌单", a, PlaylistKey(MusicSource.NETEASE, "1", "10001"))
        assertNotEquals("同 id 不同账号不是同一个歌单", a, PlaylistKey(MusicSource.QQMUSIC, "1", "20002"))
        assertNotEquals(a, PlaylistKey(MusicSource.QQMUSIC, "2", "10001"))
    }

    @Test
    fun `tag 一眼能看出音源与账号`() {
        val k = PlaylistKey(MusicSource.QQMUSIC, "8591804616", "10001")
        assertEquals("qqmusic:10001:8591804616", k.tag)
    }

    @Test
    fun `未登录用显式常量而不是空串`() {
        assertEquals("anonymous", PlaylistKey.OWNER_ANONYMOUS)
        assertTrue(PlaylistKey.isAnonymous(PlaylistKey.OWNER_ANONYMOUS))
        assertFalse(PlaylistKey.isAnonymous("10001"))
        assertFalse("空串不是合法归属", PlaylistKey.isAnonymous(""))
    }

    // ------------------------------------------------------------ 曲目 ----

    @Test
    fun `PlaylistTrack 复用 TrackKey 且等于歌曲身份`() {
        val song = SongItem(
            id = 263167477L,
            name = "甲",
            artists = null,
            album = null,
            duration = null,
            source = MusicSource.QQMUSIC.key,
            sourceId = "002n3u4D2u8DLn",
            mediaId = "002OVQbr00xbs6",
        )
        val t = PlaylistTrack.of(MusicSource.QQMUSIC, song, order = 3)
        assertTrue(t != null)
        assertEquals(MusicSource.QQMUSIC, t!!.source)
        assertEquals(TrackKey.fromSong(song), t.trackKey)
        assertEquals(3, t.order)
        assertNull("QQ 详情不返回加入时间，必须是 null 而不是 0", t.addedAt)
    }

    @Test
    fun `id 非法的歌曲不构造身份`() {
        val bad = SongItem(
            id = 0L, name = "无 id", artists = null, album = null, duration = null,
            source = MusicSource.QQMUSIC.key, sourceId = "m",
        )
        assertNull("不要造一个假 id 出来", PlaylistTrack.of(MusicSource.QQMUSIC, bad, 0))
    }

    /**
     * 身份判等**不含载荷**（与 [TrackKey] 的既有语义一致）：
     * 同一首歌在不同路径上「知道」的 mediaId 可能不同，不该被判成两首歌。
     */
    @Test
    fun `同一首歌缺 mediaId 时仍是同一个身份`() {
        val withMedia = TrackKey(MusicSource.QQMUSIC, 5L, "mid", "media")
        val withoutMedia = TrackKey(MusicSource.QQMUSIC, 5L, "mid", null)
        assertEquals(withMedia, withoutMedia)
        assertEquals(withMedia.hashCode(), withoutMedia.hashCode())
    }

    // ------------------------------------------------------------ 分组 ----

    private fun pl(source: MusicSource, id: String, owner: String = "1", name: String = "n") = Playlist(
        key = PlaylistKey(source, id, owner),
        name = name,
        coverUrl = null,
        trackCount = 1,
        isOwned = true,
        isFavorite = false,
        updatedAt = 0L,
    )

    /**
     * **不做跨源合并**：同名歌单在两个音源下必须落进不同的组。
     */
    @Test
    fun `同名歌单不会被合并到一组`() {
        val groups = groupPlaylistsBySource(
            listOf(
                pl(MusicSource.QQMUSIC, "1", name = "我喜欢的音乐"),
                pl(MusicSource.NETEASE, "1", name = "我喜欢的音乐"),
            )
        )
        assertEquals(2, groups.size)
        val qq = groups.first { it.source == MusicSource.QQMUSIC }
        val ne = groups.first { it.source == MusicSource.NETEASE }
        assertEquals(1, qq.playlists.size)
        assertEquals(1, ne.playlists.size)
        assertEquals(MusicSource.QQMUSIC, qq.playlists[0].key.source)
        assertEquals(MusicSource.NETEASE, ne.playlists[0].key.source)
    }

    @Test
    fun `组顺序稳定为 selectable 顺序且空组保留`() {
        val groups = groupPlaylistsBySource(listOf(pl(MusicSource.QQMUSIC, "1")))
        // v3.1.0：默认分组源是 loginSources（B 站没有歌单同步 ⇒ 不进库页分组）。
        assertEquals(MusicSource.loginSources, groups.map { it.source })
        val ne = groups.first { it.source == MusicSource.NETEASE }
        assertTrue("空组必须保留，UI 才能显示空状态", ne.playlists.isEmpty())
        val qq = groups.first { it.source == MusicSource.QQMUSIC }
        assertEquals(1, qq.playlists.size)
    }

    @Test
    fun `组内保持传入顺序不重排`() {
        val groups = groupPlaylistsBySource(
            listOf(
                pl(MusicSource.QQMUSIC, "3"),
                pl(MusicSource.QQMUSIC, "1"),
                pl(MusicSource.QQMUSIC, "2"),
            )
        )
        val qq = groups.first { it.source == MusicSource.QQMUSIC }
        assertEquals(listOf("3", "1", "2"), qq.playlists.map { it.key.id })
    }

    @Test
    fun `空输入返回全空组而不是空列表`() {
        val groups = groupPlaylistsBySource(emptyList())
        assertEquals(MusicSource.loginSources.size, groups.size)
        assertTrue(groups.all { it.playlists.isEmpty() })
    }
}
