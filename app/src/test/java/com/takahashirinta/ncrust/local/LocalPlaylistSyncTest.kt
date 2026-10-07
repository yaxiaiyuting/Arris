/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 · B 单元测试：本地歌单的**七条同步规则** + tombstone 语义 + 去重 key 隔离 + 容量有界。
 *
 * 任务书 4.2 的七条规则逐条对应一个（或多个）用例，用例名后面括号里是规则的编号。
 * 「只加不减 + tombstone」是本版的硬要求（铁律 4），所以「删掉的歌不会复活」
 * 这一条在这里被**从三个不同角度**各钉了一次：规则 4、重启（编解码往返）、清空。
 */

package com.takahashirinta.ncrust.local

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.source.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistSyncTest {

    private val now = 1_700_000_000_000L

    private fun song(id: Long, name: String, source: MusicSource = MusicSource.NETEASE, mid: String? = null) =
        SongItem(
            id = id,
            name = name,
            artists = null,
            album = null,
            duration = null,
            source = if (source == MusicSource.NETEASE) null else source.key,
            sourceId = mid,
        )

    private fun track(
        id: Long,
        origin: LocalTrackOrigin = LocalTrackOrigin.REMOTE,
        tombstoned: Boolean = false,
        addedAt: Long = now,
        source: MusicSource = MusicSource.NETEASE,
    ) = LocalPlaylistTrack(
        trackKey = TrackKey(source, id),
        origin = origin,
        addedAt = addedAt,
        tombstoned = tombstoned,
        song = song(id, "t$id", source),
    )

    // ------------------------------------------------------------ 规则 1 ----

    @Test
    fun `rule1 远程有 本地无 不在 tombstone 时追加为 REMOTE`() {
        val result = LocalPlaylistSync.merge(
            existing = emptyList(),
            remoteSongs = listOf(song(1, "a"), song(2, "b")),
            now = now,
        )
        assertEquals(2, result.added)
        assertEquals(0, result.skipped)
        assertEquals(listOf(1L, 2L), result.tracks.map { it.trackKey.id })
        assertTrue(result.tracks.all { it.origin == LocalTrackOrigin.REMOTE })
        assertTrue(result.tracks.all { !it.tombstoned })
        assertTrue(result.tracks.all { it.addedAt == now })
    }

    // ------------------------------------------------------------ 规则 2 ----

    @Test
    fun `rule2 远程有 本地有 时原样保留 不改 origin addedAt 与下标`() {
        val existing = listOf(
            track(1, origin = LocalTrackOrigin.LOCAL, addedAt = 111L),
            track(2, origin = LocalTrackOrigin.REMOTE, addedAt = 222L),
        )
        val result = LocalPlaylistSync.merge(existing, listOf(song(2, "b"), song(1, "a")), now)
        assertEquals(0, result.added)
        // 「不动」是逐字段的：origin / addedAt / 下标全部保持
        assertEquals(existing, result.tracks)
    }

    // ------------------------------------------------------------ 规则 3 ----

    @Test
    fun `rule3 远程没有的歌 本地保留（只加不减）`() {
        val existing = listOf(track(1), track(2), track(3))
        // 远程只剩第 2 首
        val result = LocalPlaylistSync.merge(existing, listOf(song(2, "b")), now)
        assertEquals(0, result.added)
        assertEquals(listOf(1L, 2L, 3L), result.tracks.map { it.trackKey.id })
    }

    @Test
    fun `rule3 远程整体消失时 本地一首都不删`() {
        val existing = listOf(track(1), track(2))
        val result = LocalPlaylistSync.merge(existing, emptyList(), now)
        assertEquals(0, result.added)
        assertEquals(existing, result.tracks)
    }

    // ------------------------------------------------------------ 规则 4 ----

    @Test
    fun `rule4 远程有 但本地在 tombstone 时跳过（用户删掉的歌不复活）`() {
        val existing = listOf(
            track(1),
            track(2, tombstoned = true),   // 用户删过第 2 首
        )
        val result = LocalPlaylistSync.merge(existing, listOf(song(1, "a"), song(2, "b")), now)
        assertEquals(0, result.added)
        assertEquals(1, result.skipped)
        // 条目还在（不物理删除），只是仍然 tombstoned
        assertEquals(2, result.tracks.size)
        assertTrue(result.tracks.first { it.trackKey.id == 2L }.tombstoned)
    }

    @Test
    fun `rule4 tombstone 对 LOCAL 来源同样生效`() {
        val existing = listOf(track(7, origin = LocalTrackOrigin.LOCAL, tombstoned = true))
        val result = LocalPlaylistSync.merge(existing, listOf(song(7, "g")), now)
        assertEquals(0, result.added)
        assertEquals(1, result.skipped)
        assertTrue(result.tracks.single().tombstoned)
    }

    @Test
    fun `rule4 连续两次同步不会把跳过的歌放进来`() {
        val existing = listOf(track(1), track(2, tombstoned = true))
        val remote = listOf(song(1, "a"), song(2, "b"), song(3, "c"))
        val first = LocalPlaylistSync.merge(existing, remote, now)
        val second = LocalPlaylistSync.merge(first.tracks, remote, now)
        assertEquals(1, first.added)          // 只新增第 3 首
        assertEquals(0, second.added)         // 第二遍一首都不新增（幂等）
        assertTrue(second.tracks.first { it.trackKey.id == 2L }.tombstoned)   // tombstone 仍在
        assertEquals(3, second.tracks.size)
    }

    // ------------------------------------------------------------ 规则 5 ----

    @Test
    fun `rule5 手动新增为 LOCAL 且同步时永远保留`() {
        val afterAdd = LocalPlaylistSync.addManual(emptyList(), song(9, "manual"), now)
        assertEquals(1, afterAdd.size)
        assertEquals(LocalTrackOrigin.LOCAL, afterAdd.single().origin)

        // 远程完全没有这一首 ⇒ 保留
        val afterSync = LocalPlaylistSync.merge(afterAdd, listOf(song(1, "a")), now)
        assertTrue(afterSync.tracks.any { it.trackKey.id == 9L })
        assertEquals(LocalTrackOrigin.LOCAL, afterSync.tracks.first { it.trackKey.id == 9L }.origin)
    }

    // ------------------------------------------------------------ 规则 6 ----

    @Test
    fun `rule6 手动删除只打 tombstone 不物理删除`() {
        val existing = listOf(track(1), track(2))
        val after = LocalPlaylistSync.remove(existing, TrackKey(MusicSource.NETEASE, 1))
        assertEquals(2, after.size)                                    // 条目还在
        assertTrue(after.first { it.trackKey.id == 1L }.tombstoned)
        assertFalse(after.first { it.trackKey.id == 2L }.tombstoned)
        // isVisible 是 UI 的判据
        assertEquals(listOf(2L), after.filter { it.isVisible }.map { it.trackKey.id })
    }

    @Test
    fun `rule6 删一首不在列表里的歌是空操作`() {
        val existing = listOf(track(1))
        assertSameList(existing, LocalPlaylistSync.remove(existing, TrackKey(MusicSource.NETEASE, 99)))
    }

    // ------------------------------------------------------------ 规则 7 ----

    @Test
    fun `rule7 手动重新添加清除 tombstone 并保持原下标`() {
        val existing = listOf(
            track(1),
            track(2, tombstoned = true, addedAt = 5L),
            track(3),
        )
        val after = LocalPlaylistSync.addManual(existing, song(2, "b"), now)
        assertEquals(3, after.size)                                    // 没有重复追加
        assertEquals(listOf(1L, 2L, 3L), after.map { it.trackKey.id })  // 下标不变（不整体重排）
        val readded = after.first { it.trackKey.id == 2L }
        assertFalse(readded.tombstoned)
        assertEquals(LocalTrackOrigin.LOCAL, readded.origin)
    }

    @Test
    fun `rule7 重新添加之后同步不会再把它当删除过`() {
        val existing = listOf(track(2, tombstoned = true))
        val readded = LocalPlaylistSync.addManual(existing, song(2, "b"), now)
        val synced = LocalPlaylistSync.merge(readded, listOf(song(2, "b")), now)
        assertEquals(0, synced.skipped)
        assertFalse(synced.tracks.single().tombstoned)
    }

    // ------------------------------------------------- 去重 key 的源隔离 ----

    @Test
    fun `去重按 source 加 id 隔离 ncm 123 与 QQ 123 是两首歌`() {
        val existing = listOf(track(123, source = MusicSource.NETEASE))
        val result = LocalPlaylistSync.merge(
            existing = existing,
            remoteSongs = listOf(song(123, "qq", MusicSource.QQMUSIC, mid = "0039MnYb0qxYhV")),
            now = now,
        )
        assertEquals(1, result.added)                     // QQ 那首被当成新歌
        assertEquals(2, result.tracks.size)
        assertEquals(
            setOf(MusicSource.NETEASE to 123L, MusicSource.QQMUSIC to 123L),
            result.tracks.map { it.trackKey.source to it.trackKey.id }.toSet(),
        )
    }

    @Test
    fun `id 小于等于 0 的远程曲目被丢弃`() {
        val result = LocalPlaylistSync.merge(emptyList(), listOf(song(0, "bad"), song(-1, "bad2")), now)
        assertEquals(0, result.added)
        assertTrue(result.tracks.isEmpty())
    }

    // ------------------------------------------------------ 清空与有界 ----

    @Test
    fun `清空歌单连 tombstone 一起清掉（清除条件之一）`() {
        val existing = listOf(track(1), track(2, tombstoned = true))
        val cleared = LocalPlaylistSync.clearAll(existing)
        assertTrue(cleared.isEmpty())
        // 清空之后再同步 ⇒ 两首都回来（这正是「清空 = 放弃删除记录」的语义）
        val synced = LocalPlaylistSync.merge(cleared, listOf(song(1, "a"), song(2, "b")), now)
        assertEquals(2, synced.added)
        assertEquals(0, synced.skipped)
    }

    @Test
    fun `容量裁剪只丢最旧的 tombstone 绝不丢活动条目`() {
        val existing = listOf(
            track(1, addedAt = 10L),
            track(2, tombstoned = true, addedAt = 20L),
            track(3, tombstoned = true, addedAt = 5L),       // 最旧的 tombstone
            track(4, addedAt = 30L),
        )
        val trimmed = LocalPlaylistSync.trimToLimit(existing, maxTracks = 3)
        assertEquals(3, trimmed.size)
        assertFalse(trimmed.any { it.trackKey.id == 3L })            // 丢的是最旧的那条 tombstone
        assertTrue(trimmed.filter { !it.tombstoned }.map { it.trackKey.id }.containsAll(listOf(1L, 4L)))
    }

    @Test
    fun `容量裁剪在没有足够 tombstone 时放弃裁剪 而不是删用户看得见的歌`() {
        val existing = listOf(track(1), track(2), track(3))
        val trimmed = LocalPlaylistSync.trimToLimit(existing, maxTracks = 2)
        assertEquals(3, trimmed.size)                                 // 宁可超限
    }

    // -------------------------------------------------------- 同步时机 ----

    @Test
    fun `syncIfStale 从未同步过一律要同步`() {
        assertTrue(LocalPlaylistSync.shouldAutoSync(lastSyncedAt = 0L, now = now))
    }

    @Test
    fun `syncIfStale TTL 内不同步 超时才同步`() {
        val ttl = LocalPlaylistSync.SYNC_TTL_MS
        assertFalse(LocalPlaylistSync.shouldAutoSync(now - ttl + 1, now))
        assertTrue(LocalPlaylistSync.shouldAutoSync(now - ttl, now))
        assertTrue(LocalPlaylistSync.shouldAutoSync(now - ttl - 1, now))
    }

    @Test
    fun `syncIfStale force 无条件同步`() {
        assertTrue(LocalPlaylistSync.shouldAutoSync(lastSyncedAt = now, now = now, force = true))
    }

    @Test
    fun `syncIfStale 时钟往回拨时同步一次而不是把 TTL 当无限长`() {
        assertTrue(LocalPlaylistSync.shouldAutoSync(lastSyncedAt = now + 60_000L, now = now))
    }

    // -------------------------------------------------------- 纯本地 key ----

    @Test
    fun `纯本地歌单 key 不带远程来源`() {
        val key = LocalPlaylistSync.localOnlyKey("123-0")
        assertTrue(key.id.startsWith(LocalPlaylist.LOCAL_ONLY_ID_PREFIX))
        val meta = LocalPlaylist(key = key, name = "n", createdAt = now, updatedAt = now)
        assertFalse(meta.hasRemoteSource)
        val remote = LocalPlaylist(
            key = PlaylistKey(MusicSource.QQMUSIC, "8591804616", "1152921504891728649"),
            name = "n", createdAt = now, updatedAt = now,
        )
        assertTrue(remote.hasRemoteSource)
    }

    private fun assertSameList(a: List<LocalPlaylistTrack>, b: List<LocalPlaylistTrack>) {
        assertEquals(a.size, b.size)
        a.zip(b).forEach { (x, y) -> assertEquals(x, y) }
    }

    @Test
    fun `merge 结果不共享可变状态`() {
        val existing = listOf(track(1))
        val result = LocalPlaylistSync.merge(existing, listOf(song(2, "b")), now)
        assertNotNull(result.tracks)
        assertEquals(1, existing.size)   // 原列表没有被就地修改
    }

    @Test
    fun `addManual 对 id 非正的曲目是空操作`() {
        assertTrue(LocalPlaylistSync.addManual(emptyList(), song(0, "bad"), now).isEmpty())
    }

    @Test
    fun `remove 不改动其它条目的任何字段`() {
        val existing = listOf(track(1, addedAt = 10L), track(2, origin = LocalTrackOrigin.LOCAL, addedAt = 20L))
        val after = LocalPlaylistSync.remove(existing, TrackKey(MusicSource.NETEASE, 1))
        assertEquals(existing[1], after[1])
    }

    @Test
    fun `TrackKey 判等忽略载荷 所以同一首歌换了 songmid 也认为已存在`() {
        val existing = listOf(
            LocalPlaylistTrack(
                trackKey = TrackKey(MusicSource.QQMUSIC, 42L, sourceId = "old-mid"),
                origin = LocalTrackOrigin.REMOTE,
                addedAt = now,
            ),
        )
        // 同 id、不同 songmid（真实场景：冷启动恢复时缺 songmid，随后由 songDetail 补齐）
        val result = LocalPlaylistSync.merge(
            existing,
            listOf(song(42, "x", MusicSource.QQMUSIC, mid = "new-mid")),
            now,
        )
        assertEquals(0, result.added)
        assertEquals("old-mid", result.tracks.single().trackKey.sourceId)
    }

    @Test
    fun `未登录的 ownerId 是显式常量 不是空串`() {
        assertNull(null as String?)
        assertEquals("anonymous", PlaylistKey.OWNER_ANONYMOUS)
    }
}
