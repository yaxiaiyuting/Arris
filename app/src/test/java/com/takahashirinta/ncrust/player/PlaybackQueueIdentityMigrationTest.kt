/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * H14 残余单测：**队列读路径**的 B 站视频腿补位（`PlaybackStateManager.migrateQueueSongs`）。
 *
 * 队列 JSON 里只有 `List<SongItem>`，没有独立的 trackKey —— 判重 / 待播槽位 / 切歌定位
 * 都由 `TrackKey.ofSong(song)` 现算，所以「身份」就是 `song.id` 本身。
 * 这条用例钉住：只补位、只补一次、AU 与非 B 站条目一个都不动、不需要迁移时返回同一实例。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.bili.BiliTrack
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.source.trackKeyOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueIdentityMigrationTest {

    private val aid = 117336711367421L
    private val auid = 22760301L
    private val bvPayload = "bv:BV1EC4y1R7ax:25931154534"

    private fun legacyAvId() = SourceIds.biliId(aid)
    private fun modernAvId() = BiliTrack.numericIdOf(auid = null, aid = aid)

    private fun song(
        id: Long,
        source: MusicSource,
        sourceId: String? = null,
        name: String = "曲名",
    ) = SongItem(
        id = id,
        name = name,
        artists = listOf(ArtistItem(1L, "艺人")),
        album = AlbumItem(9L, "专辑", "https://x/y.jpg"),
        duration = 180_000L,
        source = source.key,
        sourceId = sourceId,
    )

    private fun legacyVideo() = song(legacyAvId(), MusicSource.BILIBILI, bvPayload)
    private fun modernVideo() = song(modernAvId(), MusicSource.BILIBILI, bvPayload)
    private fun audioZone() = song(SourceIds.biliId(auid), MusicSource.BILIBILI, "au:$auid")
    private fun ncm() = song(1_959_528_822L, MusicSource.NETEASE)

    // ------------------------------------------------------------------ 补位 ----

    @Test
    fun `队列里的旧视频腿条目补上位 60，且取链载荷原样保留`() {
        val migrated = PlaybackStateManager.migrateQueueSongs(listOf(legacyVideo())).single()

        assertEquals(modernAvId(), migrated.id)
        assertEquals("取链只认 sourceId，改它等于让旧条目不可播", bvPayload, migrated.sourceId)
        assertEquals(MusicSource.BILIBILI.key, migrated.source)
        assertEquals(
            "迁移后必须与「现在搜出来的同一首」是同一个身份",
            TrackKey.ofSong(modernVideo()),
            migrated.trackKeyOf(),
        )
    }

    @Test
    fun `AU 条目不动`() {
        val au = audioZone()
        val migrated = PlaybackStateManager.migrateQueueSongs(listOf(au)).single()

        assertSame(au, migrated)
        assertEquals(SourceIds.biliId(auid), migrated.id)
        assertTrue((migrated.id and SourceIds.BILI_AV_PARTITION_FLAG) == 0L)
    }

    @Test
    fun `已经补过位的条目再读一遍不变（幂等）`() {
        val once = PlaybackStateManager.migrateQueueSongs(listOf(legacyVideo()))
        val twice = PlaybackStateManager.migrateQueueSongs(once)
        val thrice = PlaybackStateManager.migrateQueueSongs(twice)

        assertEquals(modernAvId(), thrice.single().id)
        assertSame("没有可迁移的条目时必须返回同一个列表实例", once, twice)
        assertSame(once, thrice)
    }

    @Test
    fun `非 B 站与非法条目一个都不动`() {
        val ncmSong = ncm()
        val qq = song(SourceIds.qqId(97_773L, "0039MnYb0qxYhV"), MusicSource.QQMUSIC, "0039MnYb0qxYhV")
        val list = listOf(ncmSong, qq)
        val out = PlaybackStateManager.migrateQueueSongs(list)

        assertSame("没有任何条目需要迁移 ⇒ 原样返回", list, out)
        assertEquals(ncmSong.id, out[0].id)
        assertEquals(qq.id, out[1].id)
    }

    @Test
    fun `载荷缺失的旧视频腿条目保持原样 —— 判不出腿就不猜`() {
        val orphan = song(legacyAvId(), MusicSource.BILIBILI, null)
        val out = PlaybackStateManager.migrateQueueSongs(listOf(orphan))

        assertEquals(legacyAvId(), out.single().id)
    }

    @Test
    fun `混合队列：只改该改的那一条，顺序与其它条目引用不变`() {
        val av = legacyVideo()
        val au = audioZone()
        val ncmSong = ncm()
        val out = PlaybackStateManager.migrateQueueSongs(listOf(av, au, ncmSong))

        assertEquals(3, out.size)
        assertEquals(listOf(modernAvId(), au.id, ncmSong.id), out.map { it.id })
        assertSame(au, out[1])
        assertSame(ncmSong, out[2])
        assertNotEquals(
            "入参列表不能被就地改写（调用方可能还持着它做快照比较）",
            modernAvId(),
            av.id,
        )
    }

    @Test
    fun `空队列不分配新列表`() {
        val empty = emptyList<SongItem>()
        assertSame(empty, PlaybackStateManager.migrateQueueSongs(empty))
    }

    @Test
    fun `单条迁移：不需要改时返回同一实例`() {
        val au = audioZone()
        assertSame(au, PlaybackStateManager.migrateQueueSong(au))
        assertEquals(modernAvId(), PlaybackStateManager.migrateQueueSong(legacyVideo()).id)
    }
}
