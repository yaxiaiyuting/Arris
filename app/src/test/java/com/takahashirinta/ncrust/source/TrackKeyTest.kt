/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.source

import com.takahashirinta.ncrust.network.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TrackKey] 身份语义单测（v2.1.5）。
 *
 * 这一组用例是「跨源切歌歌词串台」修复的地基：只要
 * `TrackKey(NETEASE, 123) != TrackKey(QQMUSIC, 123)` 与
 * 「载荷不参与判等」这两条被钉死，剩下的闸门逻辑就都有意义了。
 */
class TrackKeyTest {

    // ---------- 相等性：含 source ----------

    @Test
    fun same_source_and_id_are_equal() {
        assertEquals(TrackKey(MusicSource.NETEASE, 123L), TrackKey(MusicSource.NETEASE, 123L))
        assertEquals(
            TrackKey(MusicSource.QQMUSIC, 456L),
            TrackKey(MusicSource.QQMUSIC, 456L),
        )
    }

    /**
     * **本版最重要的一条**：ncm 的 123 与 QQ 的 123 是两首歌。
     *
     * 旧代码用裸 `Long` 判等，这两个值相等 ⇒ 跨源切歌时「还是不是当前歌」判成 true，
     * 旧音源的歌词于是被当成当前歌的歌词落地。
     */
    @Test
    fun same_id_on_different_sources_are_NOT_equal() {
        assertNotEquals(
            TrackKey(MusicSource.NETEASE, 123L),
            TrackKey(MusicSource.QQMUSIC, 123L),
        )
        assertNotEquals(
            TrackKey(MusicSource.NETEASE, 123L).hashCode(),
            TrackKey(MusicSource.QQMUSIC, 123L).hashCode(),
        )
    }

    @Test
    fun different_ids_are_not_equal() {
        assertNotEquals(TrackKey(MusicSource.NETEASE, 1L), TrackKey(MusicSource.NETEASE, 2L))
    }

    // ---------- 相等性：不含载荷 ----------

    /**
     * 载荷（QQ 的 songmid / media_mid）**不参与判等**。
     *
     * 理由不是「无所谓」，而是两个方向都会出错：算进相等性时，同一条曲目在不同路径上
     * 载荷可以一个有、一个没有（冷启动从持久化状态恢复时缺 songmid，随后由 songDetail 补齐），
     * 那会把同一首歌判成「切歌了」，**本该显示的歌词被整包丢弃**。
     */
    @Test
    fun payload_does_not_affect_identity() {
        val bare = TrackKey(MusicSource.QQMUSIC, 456L)
        val withMid = TrackKey(MusicSource.QQMUSIC, 456L, sourceId = "0039MnYb0qxYhV")
        val withBoth = TrackKey(
            MusicSource.QQMUSIC, 456L,
            sourceId = "0039MnYb0qxYhV", mediaId = "0039MnYb0qxYhV",
        )
        assertEquals(bare, withMid)
        assertEquals(withMid, withBoth)
        assertEquals(bare.hashCode(), withBoth.hashCode())
    }

    @Test
    fun payload_is_still_readable() {
        val k = TrackKey(MusicSource.QQMUSIC, 456L, "mid-1", "media-2")
        assertEquals("mid-1", k.sourceId)
        assertEquals("media-2", k.mediaId)
    }

    // ---------- tag / 日志 ----------

    @Test
    fun tag_encodes_source_and_id() {
        assertEquals("netease:123", TrackKey(MusicSource.NETEASE, 123L).tag)
        assertEquals("qqmusic:456", TrackKey(MusicSource.QQMUSIC, 456L).tag)
    }

    @Test
    fun toString_includes_payload_for_diagnostics() {
        val k = TrackKey(MusicSource.QQMUSIC, 456L, "mid-1")
        assertTrue(k.toString().contains("qqmusic:456"))
        assertTrue(k.toString().contains("mid-1"))
    }

    // ---------- of()：音源推断 ----------

    @Test
    fun of_falls_back_to_netease_for_unknown_key() {
        // v2.1.0 之前持久化的数据没有 source 字段，唯一正确的解释是 ncm。
        assertEquals(MusicSource.NETEASE, TrackKey.of(null, 123L).source)
        assertEquals(MusicSource.NETEASE, TrackKey.of("", 123L).source)
        assertEquals(MusicSource.NETEASE, TrackKey.of("spotify", 123L).source)
    }

    @Test
    fun of_honours_explicit_source_key() {
        assertEquals(MusicSource.QQMUSIC, TrackKey.of("qqmusic", 456L).source)
    }

    /**
     * 没有音源字符串、但 id 带 QQ 标志位 ⇒ 判成 qm。
     *
     * 这条修的是「冷启动恢复 QQ 曲目永远没歌词」：`PlaybackStateManager` 只存得下裸 id，
     * 旧逻辑按「null ⇒ ncm」处理，于是拿一个 `2^62` 量级的 id 去问 ncm 的歌词接口。
     * 标志位让这个二义性根本不存在。
     */
    @Test
    fun of_infers_qqmusic_from_flagged_id() {
        val qqId = SourceIds.qqId(123456L, "0039MnYb0qxYhV")
        assertEquals(MusicSource.QQMUSIC, TrackKey.of(null, qqId).source)
        // 显式传了音源时以它为准（调用方比标志位知道得多）。
        assertEquals(MusicSource.QQMUSIC, TrackKey.of("qqmusic", qqId).source)
        assertEquals(MusicSource.NETEASE, TrackKey.of("netease", 5257138L).source)
    }

    // ---------- fromMediaId：自动接续时唯一可信的来源 ----------

    @Test
    fun from_media_id_round_trip() {
        assertEquals(
            TrackKey(MusicSource.NETEASE, 123L),
            TrackKey.fromMediaId(SourceIds.mediaId(MusicSource.NETEASE, 123L)),
        )
        assertEquals(
            TrackKey(MusicSource.QQMUSIC, 456L),
            TrackKey.fromMediaId(SourceIds.mediaId(MusicSource.QQMUSIC, 456L)),
        )
    }

    @Test
    fun from_media_id_rejects_garbage_instead_of_guessing() {
        assertNull(TrackKey.fromMediaId(null))
        assertNull(TrackKey.fromMediaId(""))
        assertNull(TrackKey.fromMediaId("https://example.com/a.mp3"))
        // 未知音源前缀绝不能猜成 ncm —— 猜错就是拿别人的歌去取词。
        assertNull(TrackKey.fromMediaId("song:spotify:456"))
        assertNull(TrackKey.fromMediaId("song:0"))
    }

    // ---------- sourceOfId：结构性判据 ----------

    /**
     * 「带标志位 ⇒ qm」是**结构性**结论，不是启发式。
     *
     * ncm 的真实 id 都在十亿量级（远小于 `2^40`），永远触不到位 62；
     * 这里用一批真实 id 与边界值把这条不变量钉死。
     */
    @Test
    fun netease_ids_are_never_flagged() {
        val realNeteaseIds = listOf(
            1L, 287035L, 5257138L, 1959528822L, 33894312L,
            1_000_000_000L, 9_999_999_999L, // 远超真实规模，仍未触到位 62
            // v3.1.0：边界值从 `QQ_ID_FLAG - 1` 换成 `BILI_ID_FLAG - 1`。
            // 前者恰好把**位 61**置了起来，而位 61 从 v3.1.0 起归 B 站 ——
            // 它因此不再是「ncm 的边界值」（见下面那条新断言）。
            SourceIds.BILI_ID_FLAG - 1L,    // 位 61 / 62 都为 0 的上沿
        )
        for (id in realNeteaseIds) {
            assertFalse("netease id $id must not look like a QQ id", SourceIds.isQqId(id))
            assertFalse("netease id $id must not look like a Bilibili id", SourceIds.isBiliId(id))
            assertEquals(MusicSource.NETEASE, SourceIds.sourceOfId(id))
        }
        // v3.1.0：位 61 是 B 站。`QQ_ID_FLAG - 1` 把位 61 也置起来了 ⇒ 它现在**正确地**
        // 被判成 B 站。这条断言把这个边界写下来，而不是让下一个读到这里的人以为它是 ncm。
        assertEquals(MusicSource.BILIBILI, SourceIds.sourceOfId(SourceIds.QQ_ID_FLAG - 1L))
        assertEquals(MusicSource.QQMUSIC, SourceIds.sourceOfId(SourceIds.QQ_ID_FLAG))
        assertEquals(MusicSource.QQMUSIC, SourceIds.sourceOfId(SourceIds.qqId(456L, "mid")))
    }

    // ---------- fromSong ----------

    @Test
    fun from_song_carries_all_four_fields() {
        val song = SongItem(
            id = SourceIds.qqId(456L, "mid-1"),
            name = "n", artists = null, album = null, duration = null,
            source = "qqmusic", sourceId = "mid-1", mediaId = "media-1",
        )
        val k = TrackKey.fromSong(song)
        assertEquals(MusicSource.QQMUSIC, k.source)
        assertEquals("mid-1", k.sourceId)
        assertEquals("media-1", k.mediaId)
    }

    @Test
    fun from_netease_song_defaults_to_netease() {
        val song = SongItem(
            id = 5257138L, name = "屋顶", artists = null, album = null, duration = null,
            source = null, sourceId = null, mediaId = null,
        )
        assertEquals(TrackKey(MusicSource.NETEASE, 5257138L), TrackKey.fromSong(song))
    }

    // ---------- 哨兵 ----------

    @Test
    fun none_sentinel_is_not_a_real_track() {
        assertEquals(TrackKey.NONE, TrackKey(MusicSource.NETEASE, -1L))
        assertNotEquals(TrackKey.NONE, TrackKey(MusicSource.NETEASE, 1L))
    }
}
