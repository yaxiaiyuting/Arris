package com.takahashirinta.ncrust.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 · A：音源枚举与标识符编解码的纯逻辑单测。
 *
 * 这些用例的重点**不是**「新功能能用」，而是「旧数据不会被读错」：
 * v2.1.0 之前持久化的队列 JSON 没有 source 字段、离线缓存 key 是 `song:<id>:<level>`，
 * 一旦解析成别的音源，老用户升级后就是「歌单还在、一首都放不出来」。
 */
class MusicSourceTest {

    // ---------- MusicSource.fromKey：向后兼容是主诉求 ----------

    @Test
    fun `null 与空串回落ncm`() {
        assertEquals(MusicSource.NETEASE, MusicSource.fromKey(null))
        assertEquals(MusicSource.NETEASE, MusicSource.fromKey(""))
    }

    @Test
    fun `不认识的值回落ncm而不是抛异常`() {
        assertEquals(MusicSource.NETEASE, MusicSource.fromKey("spotify"))
        assertEquals(MusicSource.NETEASE, MusicSource.fromKey("NETEASE")) // 大小写敏感是有意的
        assertEquals(MusicSource.NETEASE, MusicSource.fromKey(" qqmusic"))
    }

    @Test
    fun `已知 key 正确解析`() {
        assertEquals(MusicSource.NETEASE, MusicSource.fromKey("netease"))
        assertEquals(MusicSource.QQMUSIC, MusicSource.fromKey("qqmusic"))
    }

    @Test
    fun `key 与 ordinal 解耦——key 是写进持久化数据的稳定值`() {
        assertEquals("netease", MusicSource.NETEASE.key)
        assertEquals("qqmusic", MusicSource.QQMUSIC.key)
        // 断言字面量而不是内部一致性：改动这两个字符串等于让所有历史数据指错音源。
    }

    @Test
    fun `只有 qm需要 sourceId`() {
        assertTrue(MusicSource.QQMUSIC.requiresSourceId)
        assertFalse(MusicSource.NETEASE.requiresSourceId)
    }

    @Test
    fun `selectable 覆盖全部取值`() {
        assertEquals(MusicSource.values().toList(), MusicSource.selectable)
    }

    // ---------- trackKey ----------

    @Test
    fun `trackKey 往返`() {
        assertEquals(MusicSource.NETEASE to 123L, SourceIds.parseTrackKey(SourceIds.trackKey(MusicSource.NETEASE, 123L)))
        assertEquals(MusicSource.QQMUSIC to 456L, SourceIds.parseTrackKey(SourceIds.trackKey(MusicSource.QQMUSIC, 456L)))
    }

    @Test
    fun `parseTrackKey 拒绝坏输入`() {
        assertNull(SourceIds.parseTrackKey(null))
        assertNull(SourceIds.parseTrackKey(""))
        assertNull(SourceIds.parseTrackKey("netease"))
        assertNull(SourceIds.parseTrackKey("netease:"))
        assertNull(SourceIds.parseTrackKey("netease:abc"))
        assertNull(SourceIds.parseTrackKey("netease:0"))
        assertNull(SourceIds.parseTrackKey("netease:-5"))
        assertNull(SourceIds.parseTrackKey("spotify:5")) // 未知音源不猜
        assertNull(SourceIds.parseTrackKey(":5"))
    }

    // ---------- mediaId ----------

    @Test
    fun `ncm的 mediaId 与 v2_0_2 逐字节相同`() {
        // 这条断言是「老用户升级后当前播放项/通知栏不失效」的守门人。
        assertEquals("song:123", SourceIds.mediaId(MusicSource.NETEASE, 123L))
    }

    @Test
    fun `qm的 mediaId 带音源段`() {
        assertEquals("song:qqmusic:456", SourceIds.mediaId(MusicSource.QQMUSIC, 456L))
    }

    @Test
    fun `mediaId 对非正 id 返回 null`() {
        assertNull(SourceIds.mediaId(MusicSource.NETEASE, 0L))
        assertNull(SourceIds.mediaId(MusicSource.QQMUSIC, -1L))
    }

    @Test
    fun `mediaId 往返`() {
        for (source in MusicSource.values()) {
            val id = 987654321L
            assertEquals(source to id, SourceIds.parseMediaId(SourceIds.mediaId(source, id)))
        }
    }

    @Test
    fun `parseMediaId 认历史形状 song_id 为ncm`() {
        // v2.1.0 之前 ExoPlayer 里挂的就是这个形状，升级后正在播的歌不能变成「无法解析」。
        assertEquals(MusicSource.NETEASE to 42L, SourceIds.parseMediaId("song:42"))
    }

    @Test
    fun `parseMediaId 拒绝坏输入而不是猜成ncm`() {
        assertNull(SourceIds.parseMediaId(null))
        assertNull(SourceIds.parseMediaId(""))
        assertNull(SourceIds.parseMediaId("album:42"))
        assertNull(SourceIds.parseMediaId("song:"))
        assertNull(SourceIds.parseMediaId("song:abc"))
        assertNull(SourceIds.parseMediaId("song:0"))
        assertNull(SourceIds.parseMediaId("song:-3"))
        assertNull(SourceIds.parseMediaId("song:spotify:3"))
        assertNull(SourceIds.parseMediaId("song:qqmusic:"))
        assertNull(SourceIds.parseMediaId("song:qqmusic:xyz"))
    }

    // ---------- qm 的数字 id（id 命名空间隔离） ----------

    @Test
    fun `QQ id 落在ncm永远到不了的区间`() {
        val id = SourceIds.qqId(102065756L, "0039MnYb0qxYhV")
        assertTrue(SourceIds.isQqId(id))
        assertTrue("QQ id 必须大于任何可能的 ncm id", id > 1_000_000_000_000_000L)
        assertTrue("必须是正数，避免与「非正 id 视为无效」的既有守卫冲突", id > 0L)
    }

    @Test
    fun `QQ id 可无损反解回真实 songid`() {
        for (raw in listOf(1L, 97773L, 102065756L, 3_000_000_000L)) {
            val id = SourceIds.qqId(raw, "mid")
            assertEquals(raw, SourceIds.qqRawId(id))
        }
    }

    @Test
    fun `ncm id 不会被误判成 QQ id`() {
        for (raw in listOf(1L, 247936L, 3_399_937_943L, 1L shl 40)) {
            assertFalse(SourceIds.isQqId(raw))
            assertNull(SourceIds.qqRawId(raw))
        }
    }

    @Test
    fun `两组 id 空间不相交——这正是离线缓存与歌词缓存不必改 key 的原因`() {
        val neteaseIds = listOf(1L, 97773L, 102065756L, 3_399_937_943L).toSet()
        val qqIds = listOf("0039MnYb0qxYhV", "0039MnYb0qxYhW", "abc").map {
            SourceIds.qqId(0L, it) // 只给 mid、不给 songid 的最坏情况
        }.toSet()
        assertTrue("两组 id 不得有交集", (neteaseIds intersect qqIds).isEmpty())
        assertEquals(3, qqIds.size)
    }

    @Test
    fun `缺 songid 时用 mid 散列兜底且是确定性的`() {
        val a = SourceIds.qqId(0L, "0039MnYb0qxYhV")
        assertEquals(a, SourceIds.qqId(0L, "0039MnYb0qxYhV"))
        assertNotEquals(a, SourceIds.qqId(0L, "0039MnYb0qxYhW"))
        assertTrue(SourceIds.isQqId(a))
        assertTrue(SourceIds.qqRawId(a)!! > 0L)
    }

    @Test
    fun `非法 rawSongId 一律走散列兜底而不是造出坏 id`() {
        for (bad in listOf(0L, -1L, Long.MIN_VALUE, SourceIds.QQ_ID_FLAG, Long.MAX_VALUE)) {
            val id = SourceIds.qqId(bad, "mid-x")
            assertTrue("rawSongId=$bad 必须仍然得到合法 QQ id", SourceIds.isQqId(id))
            val raw = SourceIds.qqRawId(id)!!
            assertTrue("反解出的 raw 必须落在低 62 位内", raw in 1L until SourceIds.QQ_ID_FLAG)
        }
    }

    @Test
    fun `散列兜底不会溢出标志位`() {
        // 覆盖各种长度的 mid，确认结果永远落在低 62 位（否则或上标志位会互相污染）。
        for (mid in listOf("", "a", "0039MnYb0qxYhV", "z".repeat(200), "\u00ff".repeat(8))) {
            val raw = SourceIds.qqRawId(SourceIds.qqId(0L, mid))!!
            assertTrue("mid 长度 ${mid.length} 越界", raw in 1L until SourceIds.QQ_ID_FLAG)
        }
    }
}
