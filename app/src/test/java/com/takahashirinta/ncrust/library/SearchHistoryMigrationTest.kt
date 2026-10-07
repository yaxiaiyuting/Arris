/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.4 · B：搜索历史音源恢复的守卫单测。
 */

package com.takahashirinta.ncrust.library

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.isResolvable
import com.takahashirinta.ncrust.source.musicSource
import com.takahashirinta.ncrust.source.trackKeyOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「从历史记录恢复出来的歌还是原来那首歌吗」。
 *
 * 这组用例对应任务书 §4 的三条验收：**恢复时 source 正确 / 旧数据迁移有单测 / 不丢历史**。
 */
class SearchHistoryMigrationTest {

    private val qqRawSongId = 102_795_182L

    private fun qqId(raw: Long = qqRawSongId) = SourceIds.qqId(raw, "0039MnYb0qxYhV")

    private fun item(
        id: Long,
        source: String? = null,
        sourceId: String? = null,
        mediaId: String? = null,
        title: String = "t",
    ) = SearchHistoryManager.HistoryItem(
        id = id, title = title, coverUrl = null, subtitle = null,
        timestamp = 1L, source = source, sourceId = sourceId, mediaId = mediaId,
    )

    // ------------------------------------------------------- 新写入的条目 ----

    @Test
    fun `QQ 曲目写入历史后仍能还原出 songmid 与音源`() {
        val it = item(qqId(), source = "qqmusic", sourceId = "0039MnYb0qxYhV", mediaId = "003Qui1q2u1Zho")
        val song = SearchHistoryMigration.toSongItem(it)
        assertEquals(MusicSource.QQMUSIC, song.musicSource)
        assertEquals("0039MnYb0qxYhV", song.sourceId)
        assertEquals("003Qui1q2u1Zho", song.mediaId)
        assertTrue("有 songmid 就必须可解析", song.isResolvable)
        assertEquals(MusicSource.QQMUSIC, song.trackKeyOf().source)
    }

    @Test
    fun `QQ 曲目往返后队列身份与写入前完全一致`() {
        // 这是本版要修的那件事的可执行版本：写入前的 SongItem 与从历史还原出来的
        // SongItem 必须在**队列身份**上相等（v2.5.3 起身份由 TrackKey 承载）。
        val original = SongItem(
            id = qqId(), name = "残酷な天使のテーゼ", artists = null, album = null, duration = null,
            source = "qqmusic", sourceId = "0039MnYb0qxYhV", mediaId = "003Qui1q2u1Zho",
        )
        val restored = SearchHistoryMigration.toSongItem(
            item(
                qqId(), source = "qqmusic", sourceId = "0039MnYb0qxYhV",
                mediaId = "003Qui1q2u1Zho", title = "残酷な天使のテーゼ",
            )
        )
        assertEquals(original.trackKeyOf(), restored.trackKeyOf())
    }

    @Test
    fun `ncm曲目恢复出的 source 仍是 null（与 v2_1_0 之前的数据逐字段相等）`() {
        val song = SearchHistoryMigration.toSongItem(item(657666, source = "netease"))
        assertEquals(MusicSource.NETEASE, song.musicSource)
        assertNull("ncm 一侧刻意写 null，见 SongSourceExt.songRefOf 的约定", song.source)
    }

    // ------------------------------------------------------ 旧条目的迁移 ----

    @Test
    fun `老条目缺 source 且 id 带 bit62 时推断为 QQ`() {
        val it = item(qqId())
        assertEquals(MusicSource.QQMUSIC, SearchHistoryMigration.effectiveSource(it))
        assertTrue("老条目不可推断 songmid ⇒ 必须标成不完整", SearchHistoryMigration.isIncomplete(it))
    }

    @Test
    fun `老条目缺 source 且 id 无标志位时按ncm处理`() {
        val it = item(657666)
        assertEquals(MusicSource.NETEASE, SearchHistoryMigration.effectiveSource(it))
        assertFalse(SearchHistoryMigration.isIncomplete(it))
    }

    @Test
    fun `老 QQ 条目恢复出的 SongItem 不可解析，且绝不猜 songmid`() {
        val song = SearchHistoryMigration.toSongItem(item(qqId()))
        assertEquals(MusicSource.QQMUSIC, song.musicSource)
        assertNull("songmid 不可逆（FNV-1a 散列），绝不能编一个出来", song.sourceId)
        assertNull(song.mediaId)
        assertFalse("不可解析 ⇒ 上层按'不可播放'处理", song.isResolvable)
    }

    @Test
    fun `显式 source 优先于 bit62（声明优先于结构）`() {
        // 与 TrackKey.of 同一条规则：写路径已经落盘的值比结构推断更可信。
        val it = item(qqId(), source = "netease")
        assertEquals(MusicSource.NETEASE, SearchHistoryMigration.effectiveSource(it))
    }

    @Test
    fun `读不懂的 source 字符串回落ncm而不是 QQ`() {
        val it = item(qqId(), source = "listen1")
        assertEquals(MusicSource.NETEASE, SearchHistoryMigration.effectiveSource(it))
    }

    @Test
    fun `空串 source 不是"老条目"以外的第三种语义（回落ncm）`() {
        // 判「老条目」只看 null；空串走 fromKey 的回落路径。
        assertEquals(MusicSource.NETEASE, SearchHistoryMigration.effectiveSource(item(657666, source = "")))
    }

    // ------------------------------------------------------------ 去重键 ----

    @Test
    fun `老条目与新条目对同一首歌给同一个去重键`() {
        val legacy = item(qqId())
        val fresh = item(qqId(), source = "qqmusic", sourceId = "0039MnYb0qxYhV")
        assertEquals(SearchHistoryMigration.dedupeKey(legacy), SearchHistoryMigration.dedupeKey(fresh))
    }

    @Test
    fun `跨源同 id 不去重（虽然结构上撞不了号，判据本身仍必须带音源）`() {
        val qq = item(qqId(), source = "qqmusic", sourceId = "mid")
        val ne = item(qqId(), source = "netease")
        assertTrue(
            "同一首歌若被显式标成两个音源，判重键必须不同",
            SearchHistoryMigration.dedupeKey(qq) != SearchHistoryMigration.dedupeKey(ne)
        )
    }

    @Test
    fun `ncm老条目与新条目的去重键相同`() {
        assertEquals(
            SearchHistoryMigration.dedupeKey(item(657666)),
            SearchHistoryMigration.dedupeKey(item(657666, source = "netease"))
        )
    }

    // ------------------------------------------------- "不完整" 的三态 ----

    @Test
    fun `QQ 有 songmid 不算不完整，QQ 无 songmid 算，ncm永远不算`() {
        assertFalse(SearchHistoryMigration.isIncomplete(item(qqId(), source = "qqmusic", sourceId = "mid")))
        assertTrue(SearchHistoryMigration.isIncomplete(item(qqId(), source = "qqmusic", sourceId = null)))
        assertTrue(SearchHistoryMigration.isIncomplete(item(qqId(), source = "qqmusic", sourceId = "")))
        assertFalse(SearchHistoryMigration.isIncomplete(item(657666, source = "netease")))
    }

    @Test
    fun `isIncomplete 与 SongItem_isResolvable 是同一条规则`() {
        // 服务端取链的判据不能有两份，否则会出现「UI 说能播、播放器说不能播」。
        val cases = listOf(
            item(qqId(), source = "qqmusic", sourceId = "mid"),
            item(qqId(), source = "qqmusic"),
            item(657666, source = "netease"),
            item(657666),
        )
        for (c in cases) {
            assertEquals(
                "两处判据分叉了：$c",
                SearchHistoryMigration.isIncomplete(c),
                !SearchHistoryMigration.toSongItem(c).isResolvable,
            )
        }
    }

    // --------------------------------------------------------- 重建载荷 ----

    @Test
    fun `重建对象带回标题_艺人与封面`() {
        val it = SearchHistoryManager.HistoryItem(
            id = 657666, title = "残酷な天使のテーゼ", coverUrl = "https://c.jpg",
            subtitle = "高橋洋子", timestamp = 1L, source = "netease",
        )
        val song = SearchHistoryMigration.toSongItem(it)
        assertEquals("残酷な天使のテーゼ", song.name)
        assertEquals("高橋洋子", song.artists?.firstOrNull()?.name)
        assertEquals("https://c.jpg", song.album?.picUrl)
        assertNull(song.duration)
    }
}
