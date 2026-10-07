/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.4.0 · A/B/D：双源装配与「版权可用性优先」的回归单测（**纯函数部分，无网络**）。
 */

package com.takahashirinta.ncrust.crosssource

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.qq.QqAlbum
import com.takahashirinta.ncrust.search.TrackAvailability
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.source.musicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAggregatorTest {

    private val ne = MusicSource.NETEASE
    private val qq = MusicSource.QQMUSIC

    private fun qqAlbum(mid: String, name: String, tracks: Int, singer: String = "周杰伦") =
        QqAlbum(id = 0L, mid = mid, name = name, trackCount = tracks, singerName = singer)

    private fun neSong(id: Long, name: String, artists: List<String>, ms: Long) = SongItem(
        id = id, name = name,
        artists = artists.map { ArtistItem(id = null, name = it) },
        album = null,
        duration = ms, source = ne.key,
    )

    private fun qqSong(id: Long, mid: String, name: String, artists: List<String>, ms: Long) = SongItem(
        id = id, name = name,
        artists = artists.map { ArtistItem(id = null, name = it) },
        album = null,
        duration = ms, source = qq.key, sourceId = mid,
    )

    // ============================================================== 专辑装配 ==============================================================

    @Test
    fun `专辑装配 同名同曲目数合成一行 且主源顺序不变`() {
        val primary = listOf(qqAlbum("a1", "叶惠美", 11), qqAlbum("a2", "范特西", 10))
        val secondary = listOf(qqAlbum("b1", "范特西", 10), qqAlbum("b2", "叶惠美", 11))
        val rows = CatalogAggregator.assembleAlbums(primary, ne, secondary, qq)
        assertEquals(2, rows.size)
        assertEquals(listOf("叶惠美", "范特西"), rows.map { it.name })
        assertEquals(ne, rows[0].key.source)
        assertTrue(rows[0].isDual)
        assertEquals("b2", rows[0].keyOf(qq)?.id)
    }

    @Test
    fun `专辑装配 次源独有的追加在末尾 不插进主序列`() {
        val primary = listOf(qqAlbum("a1", "叶惠美", 11))
        val secondary = listOf(qqAlbum("b9", "只在次源", 3))
        val rows = CatalogAggregator.assembleAlbums(primary, ne, secondary, qq)
        assertEquals(2, rows.size)
        assertEquals("只在次源", rows[1].name)
        assertEquals(qq, rows[1].key.source)
        assertFalse(rows[1].isDual)
        assertTrue(rows[1].reason.contains("仅"))
    }

    @Test
    fun `专辑装配 同名不同艺人时不合并（宁可分开展示）`() {
        val primary = listOf(qqAlbum("a1", "叶惠美", 11, singer = "周杰伦"))
        val secondary = listOf(qqAlbum("b1", "叶惠美", 11, singer = "夜未央"))
        val rows = CatalogAggregator.assembleAlbums(primary, ne, secondary, qq)
        assertEquals(2, rows.size)
        assertFalse(rows[0].isDual)
        assertFalse(rows[0].confidence.mergeable)
    }

    @Test
    fun `专辑装配 一张次源专辑不会被两行抢走`() {
        val primary = listOf(qqAlbum("a1", "叶惠美", 11), qqAlbum("a2", "叶惠美", 11))
        val secondary = listOf(qqAlbum("b1", "叶惠美", 11))
        val rows = CatalogAggregator.assembleAlbums(primary, ne, secondary, qq)
        assertEquals(1, rows.count { it.isDual })
    }

    // ============================================================== 曲目装配 ==============================================================

    @Test
    fun `曲目装配 按可用性排序 可播放排最前`() {
        val neSongs = listOf(neSong(1, "晴天", listOf("周杰伦"), 269_000))
        val qqSongs = listOf(qqSong(2, "m2", "晴天", listOf("周杰伦"), 269_000))
        val probed = mapOf(
            TrackKey(ne, 1) to TrackAvailability.NO_COPYRIGHT,
            TrackKey(qq, 2, "m2") to TrackAvailability.PLAYABLE,
        )
        val (rows, playable, totals) = CatalogAggregator.assembleSongsWithProbe(neSongs, qqSongs, probed)
        assertEquals(1, rows.size) // 两源合并成同一行（另一源记在 mergedKeys 里）
        // ★ 这一行必须是**能播的那一源**，而不是「主源」：
        //   否则界面标着「可播放」、点下去播的还是那个无版权的版本。
        assertEquals(TrackAvailability.PLAYABLE, rows[0].availability)
        assertEquals(qq, rows[0].song.musicSource)
        assertEquals(listOf("netease:1"), rows[0].mergedKeys.map { it.tag })
        assertEquals(1, playable[qq])
        assertEquals(0, playable[ne] ?: 0)
        // 合并后这一行归属 QQ，所以按源的计数里没有 ncm —— 它只是被合并掉了。
        assertEquals(1, totals[qq])
        assertEquals(null, totals[ne])
    }

    @Test
    fun `曲目装配 未合并的次源曲目单独成行 并排在未探测之后`() {
        val neSongs = listOf(neSong(1, "晴天", listOf("周杰伦"), 269_000))
        val qqSongs = listOf(qqSong(2, "m2", "完全不同的歌", listOf("别人"), 100_000))
        val probed = mapOf(TrackKey(ne, 1) to TrackAvailability.PLAYABLE)
        val (rows, _, totals) = CatalogAggregator.assembleSongsWithProbe(neSongs, qqSongs, probed)
        assertEquals(2, rows.size)
        assertEquals(TrackAvailability.PLAYABLE, rows[0].availability)
        assertEquals("完全不同的歌", rows[1].song.name)
        assertEquals(1, totals[ne])
        assertEquals(1, totals[qq])
    }

    @Test
    fun `曲目装配 平级时保持主源（两侧都能播 不把用户从自己点进来的源拽走）`() {
        val neSongs = listOf(neSong(1, "晴天", listOf("周杰伦"), 269_000))
        val qqSongs = listOf(qqSong(2, "m2", "晴天", listOf("周杰伦"), 269_000))
        val probed = mapOf(
            TrackKey(ne, 1) to TrackAvailability.PLAYABLE,
            TrackKey(qq, 2, "m2") to TrackAvailability.PLAYABLE,
        )
        val (rows, _, _) = CatalogAggregator.assembleSongsWithProbe(neSongs, qqSongs, probed)
        assertEquals(1, rows.size)
        assertEquals(ne, rows[0].song.musicSource)
    }

    @Test
    fun `曲目装配 主源更差时换成次源 但仍保留主源的 key（可切回）`() {
        val neSongs = listOf(neSong(1, "晴天", listOf("周杰伦"), 269_000))
        val qqSongs = listOf(qqSong(2, "m2", "晴天", listOf("周杰伦"), 269_000))
        val probed = mapOf(
            TrackKey(ne, 1) to TrackAvailability.MEMBER_ONLY,
            TrackKey(qq, 2, "m2") to TrackAvailability.PLAYABLE,
        )
        val (rows, _, _) = CatalogAggregator.assembleSongsWithProbe(neSongs, qqSongs, probed)
        assertEquals(1, rows.size)
        assertEquals(qq, rows[0].song.musicSource)
        assertEquals("netease:1", rows[0].mergedKeys.single().tag)
    }

    @Test
    fun `曲目装配 合并后的行携带另一源的 key（可追溯 + 可切源）`() {
        val neSongs = listOf(neSong(1, "晴天", listOf("周杰伦"), 269_000))
        val qqSongs = listOf(qqSong(2, "0039MnYb0qxYhV", "晴天", listOf("周杰伦"), 269_000))
        val (rows, _, _) = CatalogAggregator.assembleSongsWithProbe(neSongs, qqSongs, emptyMap())
        assertEquals(1, rows.size)
        assertTrue(rows[0].hasOtherSource)
        assertEquals("qqmusic:2", rows[0].mergedKeys.first().tag)
        assertTrue(rows[0].confidence.mergeable)
    }

    @Test
    fun `曲目装配 低置信度时不写 mergedKeys（不许合并）`() {
        val neSongs = listOf(neSong(1, "晴天", listOf("周杰伦"), 269_000))
        val qqSongs = listOf(qqSong(2, "m2", "晴天", listOf("别人"), 269_000))
        val (rows, _, _) = CatalogAggregator.assembleSongsWithProbe(neSongs, qqSongs, emptyMap())
        assertEquals(2, rows.size)
        assertFalse(rows[0].hasOtherSource)
        assertFalse(rows[0].confidence.mergeable)
    }

    @Test
    fun `曲目装配 探测缺席时是 UNKNOWN 而不是不可播`() {
        val neSongs = listOf(neSong(1, "晴天", listOf("周杰伦"), 269_000))
        val (rows, playable, totals) = CatalogAggregator.assembleSongsWithProbe(neSongs, emptyList(), emptyMap())
        assertEquals(TrackAvailability.UNKNOWN, rows[0].availability)
        assertEquals(0, playable.values.sum())
        assertEquals(1, totals.values.sum())
        assertEquals("未探测", rows[0].availabilityReason)
    }

    @Test
    fun `曲目装配 纯函数不改动输入`() {
        val neSongs = listOf(neSong(1, "a", listOf("x"), 1_000))
        CatalogAggregator.assembleSongsWithProbe(neSongs, emptyList(), emptyMap())
        assertEquals(1, neSongs.size)
    }

    // ============================================================== 默认音源 ==============================================================

    @Test
    fun `默认音源 只有一侧可播时切到那一侧（周杰伦场景）`() {
        val preferred = CatalogAggregator.preferredSource(
            playable = mapOf(qq to 12),
            totals = mapOf(ne to 11, qq to 12),
            anchorSource = ne,
        )
        assertEquals(qq, preferred)
    }

    @Test
    fun `默认音源 两侧都能播时保持主源 不把用户从自己点进来的源拽走`() {
        val preferred = CatalogAggregator.preferredSource(
            playable = mapOf(ne to 11, qq to 12),
            totals = mapOf(ne to 11, qq to 12),
            anchorSource = ne,
        )
        assertEquals(ne, preferred)
    }

    @Test
    fun `默认音源 两侧都不可播时保持主源`() {
        val preferred = CatalogAggregator.preferredSource(
            playable = emptyMap(),
            totals = mapOf(ne to 11, qq to 12),
            anchorSource = ne,
        )
        assertEquals(ne, preferred)
    }

    @Test
    fun `默认音源 一次都没探到时返回 null（不做任何默认切换）`() {
        assertNull(CatalogAggregator.preferredSource(emptyMap(), emptyMap(), ne))
    }

    // ---------------------------------------------------------------- 说明文案 ----

    @Test
    fun `可用性说明 只在一侧探测到零可播时才给理由（不编理由）`() {
        val note = CatalogAggregator.availabilityNote(
            playable = mapOf(qq to 12),
            totals = mapOf(ne to 11, qq to 12),
        )
        assertTrue(note.isNotBlank())
        assertTrue(note.contains("netease"))
    }

    @Test
    fun `可用性说明 两侧都能播时不编理由`() {
        assertEquals("", CatalogAggregator.availabilityNote(
            playable = mapOf(ne to 1, qq to 1), totals = mapOf(ne to 1, qq to 1)))
    }

    @Test
    fun `可用性说明 没探测过时一个字都不说`() {
        assertEquals("", CatalogAggregator.availabilityNote(emptyMap(), emptyMap()))
    }

    // ---------------------------------------------------------------- 转换 ----

    @Test
    fun `候选转 SongItem 时保留音源与载荷（别让下游把它当ncm）`() {
        val candidate = CrossSourceMatcher.TrackCandidate(
            key = TrackKey(qq, 97773, "0039MnYb0qxYhV", "003Qui1q2u1Zho"),
            name = "晴天",
            artists = listOf("周杰伦"),
            durationMs = 269_000,
            albumName = "叶惠美",
        )
        val song = CatalogAggregator.candidateToSong(candidate)
        assertEquals(qq, song?.musicSource)
        assertEquals("0039MnYb0qxYhV", song?.sourceId)
        assertEquals("003Qui1q2u1Zho", song?.mediaId)
        assertEquals(269_000L, song?.duration)
    }

    @Test
    fun `候选转 SongItem 时空名字返回 null（宁可没有 也不要一行空歌）`() {
        val candidate = CrossSourceMatcher.TrackCandidate(TrackKey(qq, 1, "m"), "", emptyList(), null)
        assertNull(CatalogAggregator.candidateToSong(candidate))
    }

    @Test
    fun `毫秒时间戳转年份（ncm是毫秒 QQ 是字符串 两源不能直接比）`() {
        // 探针实测：叶惠美 ncm publishTime = 1059580800000（毫秒），QQ pubTime = "2003-07-31"。
        assertEquals("2003", CatalogAggregator.yearOf(1_059_580_800_000L))
        assertNull(CatalogAggregator.yearOf(0L))
        assertNull(CatalogAggregator.yearOf(-1L))
    }

    @Test
    fun `曲目候选转换带上时长与专辑（单曲匹配靠它们）`() {
        val candidate = CatalogAggregator.trackCandidateOf(neSong(186016, "晴天", listOf("周杰伦"), 269_000))
        assertEquals(269_000L, candidate.durationMs)
        assertEquals(listOf("周杰伦"), candidate.artists)
        assertEquals(ne, candidate.key.source)
    }
}
