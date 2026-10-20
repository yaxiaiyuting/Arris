package com.takahashirinta.ncrust.library

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「播放全部」合成逻辑：**本地 QQ / B 站不能丢、ncm 按云端 id 顺序、tombstone 不复活**。
 *
 * 这些用例是 v3.4.12 多音源回归的防线 —— 旧实现只遍历 ncm likedIds，
 * QQ / B 站条目的合成 id 永远不在那张表里，于是「播放全部」会把它们整类静默丢掉。
 */
class LikedPlayAllTest {

    private fun song(id: Long, source: MusicSource = MusicSource.NETEASE) =
        SongItem(
            id = id,
            name = "s$id",
            artists = null,
            album = null,
            duration = null,
            source = source.key,
        )

    private fun entry(songId: Long, source: MusicSource = MusicSource.NETEASE, tombstoned: Boolean = false) =
        SavedSongEntry(
            trackKey = TrackKey(source, songId),
            origin = if (source == MusicSource.NETEASE) SavedSongOrigin.REMOTE else SavedSongOrigin.LOCAL,
            addedAt = 1L,
            tombstoned = tombstoned,
            song = song(songId, source),
        )

    @Test
    fun `local qq and bili entries are kept and come first`() {
        val qqId = MusicSource.QQMUSIC.let { 1L shl 62 } or 42L
        val biliId = 1L shl 61 or 42L
        val local = listOf(entry(qqId, MusicSource.QQMUSIC), entry(biliId, MusicSource.BILIBILI))
        val details = mapOf(1L to song(1), 2L to song(2))

        val out = selectLikedPlayAll(local, listOf(1, 2), details, emptySet())

        assertEquals(listOf(qqId, biliId, 1L, 2L), out.songs.map { it.id })
        assertTrue(out.missingCloudIds.isEmpty())
    }

    @Test
    fun `netease follows cloud id order not local table order`() {
        val local = listOf(entry(9), entry(3))
        val details = mapOf(3L to song(3), 9L to song(9), 5L to song(5))

        val out = selectLikedPlayAll(local, listOf(9, 5, 3), details, emptySet())

        assertEquals(listOf(9L, 5L, 3L), out.songs.map { it.id })
    }

    @Test
    fun `tombstoned netease id never comes back even if cloud still returns it`() {
        val local = listOf(entry(1, tombstoned = true), entry(2))
        val details = mapOf(1L to song(1), 2L to song(2))

        val out = selectLikedPlayAll(local, listOf(1, 2), details, setOf(1L))

        assertEquals(listOf(2L), out.songs.map { it.id })
        assertTrue(out.missingCloudIds.isEmpty())
    }

    @Test
    fun `local netease not in cloud is appended not dropped`() {
        val local = listOf(entry(1), entry(77))
        val details = mapOf(1L to song(1), 77L to song(77))

        val out = selectLikedPlayAll(local, listOf(1), details, emptySet())

        assertEquals(listOf(1L, 77L), out.songs.map { it.id })
    }

    @Test
    fun `missing detail ids are reported for partial feedback`() {
        val out = selectLikedPlayAll(emptyList(), listOf(1, 2, 3), mapOf(1L to song(1)), emptySet())

        assertEquals(listOf(1L), out.songs.map { it.id })
        assertEquals(listOf(2L, 3L), out.missingCloudIds)
        assertEquals(3, out.cloudCount)
    }

    @Test
    fun `duplicate cloud ids do not duplicate queue entries`() {
        val out = selectLikedPlayAll(emptyList(), listOf(1, 1, 2), mapOf(1L to song(1), 2L to song(2)), emptySet())

        assertEquals(listOf(1L, 2L), out.songs.map { it.id })
        assertEquals(2, out.cloudCount)
    }

    @Test
    fun `ncm and qq with same numeric id are distinct tracks`() {
        val qqId = 1L shl 62 or 123L
        val local = listOf(entry(qqId, MusicSource.QQMUSIC), entry(123))
        val details = mapOf(123L to song(123))

        val out = selectLikedPlayAll(local, listOf(123), details, emptySet())

        assertEquals(listOf(qqId, 123L), out.songs.map { it.id })
        assertEquals(2, out.songs.map { TrackKey.ofSong(it) }.distinct().size)
    }
}
