package com.takahashirinta.ncrust.source

import com.takahashirinta.ncrust.network.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 · A：`SongItem` 上的音源扩展属性单测。
 *
 * 重点同样在**向后兼容**：v2.1.0 之前持久化的队列条目没有 source/mid 字段，
 * 它们必须与「显式标了 ncm」的新条目在身份上完全一致，否则老用户升级后
 * 队列判重、收藏命中、离线缓存命中会全部失效。
 */
class SongSourceExtTest {

    private fun legacySong(id: Long = 123L) = SongItem(
        id = id, name = "歌", artists = null, album = null, duration = null,
    )

    private fun qqSong(id: Long = 456L, mid: String? = "0039MnYb0qxYhV") = SongItem(
        id = id, name = "歌", artists = null, album = null, duration = null,
        source = "qqmusic", sourceId = mid,
    )

    @Test
    fun `旧数据没有 source 字段时按ncm处理`() {
        assertEquals(MusicSource.NETEASE, legacySong().musicSource)
        assertEquals("netease:123", legacySong().trackKey)
        assertEquals("song:123", legacySong().mediaIdOrNull)
        assertTrue(legacySong().isResolvable)
    }

    @Test
    fun `qm曲目带 mid 时可取链`() {
        val song = qqSong()
        assertEquals(MusicSource.QQMUSIC, song.musicSource)
        assertEquals("qqmusic:456", song.trackKey)
        assertEquals("song:qqmusic:456", song.mediaIdOrNull)
        assertTrue(song.isResolvable)
    }

    @Test
    fun `qm曲目缺 mid 时不可取链`() {
        // 缺 mid 时**不能**退回 ncm 取链：id 相同不代表是同一首歌。
        assertFalse(qqSong(mid = null).isResolvable)
        assertFalse(qqSong(mid = "").isResolvable)
    }

    @Test
    fun `未知 source 字符串按ncm处理而不是当成 QQ`() {
        val song = legacySong().copy(source = "kugou", sourceId = "whatever")
        assertEquals(MusicSource.NETEASE, song.musicSource)
        assertTrue(song.isResolvable)
    }

    @Test
    fun `同为ncm的新旧条目身份一致`() {
        // 这条是「升级后队列判重仍然命中」的守门人：songRefOf(NETEASE) 的 source/sourceId
        // 必须与旧 JSON（两个字段都缺）完全一致。name 只是元数据，这里对齐掉再比。
        val fromLegacyJson = legacySong().copy(name = "")
        val fromRef = songRefOf(MusicSource.NETEASE, 123L)
        assertEquals(fromLegacyJson, fromRef)
        assertEquals(fromLegacyJson.dedupeKey, fromRef.dedupeKey)
        assertNull(fromRef.source)
        assertNull(fromRef.sourceId)
    }

    @Test
    fun `同 id 不同音源的判重键不同`() {
        val netease = legacySong(id = 999L)
        val qq = qqSong(id = 999L, mid = "aaa")
        assertNotEquals(netease.dedupeKey, qq.dedupeKey)
        // 而单看 id 是分不出来的 —— 这正是不能用 id 判重的原因。
        assertEquals(netease.id, qq.id)
    }

    @Test
    fun `id 非正时没有 mediaId`() {
        assertNull(legacySong(id = 0L).mediaIdOrNull)
        assertNull(qqSong(id = -1L).mediaIdOrNull)
    }

    @Test
    fun `songRefOf 往返保持音源与 mid`() {
        val qq = songRefOf(MusicSource.QQMUSIC, 456L, "0039MnYb0qxYhV")
        assertEquals(MusicSource.QQMUSIC, qq.musicSource)
        assertEquals("0039MnYb0qxYhV", qq.sourceId)
        assertEquals(456L, qq.id)

        val netease = songRefOf(MusicSource.NETEASE, 123L)
        assertEquals(MusicSource.NETEASE, netease.musicSource)
        assertNull(netease.sourceId)
    }
}
