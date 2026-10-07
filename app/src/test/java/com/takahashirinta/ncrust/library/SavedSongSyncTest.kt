/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.0 · P0 单元测试：收藏库的**七条同步规则** + tombstone 语义 + 排序口径 +
 * 跨源写闸门。任务书 3.2 的七条规则逐条对应一个（或多个）用例，
 * 用例名后面括号里是规则的编号。
 *
 * 「只加不减 + tombstone」是本版的硬要求（铁律 22），所以「删掉的歌不会复活」
 * 这一条在这里被**从三个不同角度**各钉了一次：规则 4、重启（编解码往返）、
 * 以及「用户重新添加」（规则 7）。
 */

package com.takahashirinta.ncrust.library

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSongSyncTest {

    private val now = 1_700_000_000_000L

    private fun song(
        id: Long,
        name: String = "t$id",
        source: MusicSource = MusicSource.NETEASE,
        mid: String? = null,
    ) = SongItem(
        id = id,
        name = name,
        artists = listOf(ArtistItem(1L, "艺人")),
        album = AlbumItem(2L, "专辑", "https://x/y.jpg"),
        duration = 180_000L,
        source = if (source == MusicSource.NETEASE) null else source.key,
        sourceId = mid,
    )

    private fun remote(
        id: Long,
        addedAt: Long = now,
        tombstoned: Boolean = false,
    ) = SavedSongEntry(
        trackKey = TrackKey(MusicSource.NETEASE, id),
        origin = SavedSongOrigin.REMOTE,
        addedAt = addedAt,
        tombstoned = tombstoned,
        song = song(id),
    )

    private fun local(
        id: Long,
        addedAt: Long = now,
        tombstoned: Boolean = false,
        source: MusicSource = MusicSource.NETEASE,
        mid: String? = null,
    ) = SavedSongEntry(
        trackKey = TrackKey(source, id),
        origin = SavedSongOrigin.LOCAL,
        addedAt = addedAt,
        tombstoned = tombstoned,
        song = song(id, source = source, mid = mid),
    )

    private fun qqRaw(): Long = SourceIds.qqId(1234567890L, "0039MnYb0qxYhV")

    private fun merge(
        localList: List<SavedSongEntry>,
        remoteIds: List<Long>,
        remoteSongs: Map<Long, SongItem> = emptyMap(),
    ) = SavedSongSync.merge(localList, remoteIds, remoteSongs, now)

    // -------------------------------------------------------------- 规则 1 ----

    @Test
    fun `rule1 云端有 本地无 不在 tombstone 时追加为 REMOTE`() {
        val out = merge(emptyList(), listOf(10L, 20L), mapOf(10L to song(10L), 20L to song(20L)))
        assertEquals(listOf(10L, 20L), out.map { it.trackKey.id })
        out.forEach {
            assertEquals(SavedSongOrigin.REMOTE, it.origin)
            assertFalse(it.tombstoned)
            assertEquals(now, it.addedAt)
        }
    }

    @Test
    fun `rule1 云端有但这一批还没取到详情时 先不加 下次再补（不是丢数据）`() {
        val out = merge(emptyList(), listOf(10L, 20L), mapOf(20L to song(20L)))
        assertEquals(listOf(20L), out.map { it.trackKey.id })
        // 第二次刷新把 10 的详情补上 ⇒ 它回来了（证明上一轮没丢，只是没到）。
        val again = merge(out, listOf(10L, 20L), mapOf(10L to song(10L), 20L to song(20L)))
        assertEquals(listOf(10L, 20L), again.map { it.trackKey.id })
    }

    // -------------------------------------------------------------- 规则 2 ----

    @Test
    fun `rule2 云端有 本地有 时保留且不动 addedAt 与 origin`() {
        val old = remote(10L, addedAt = 1L)
        val out = merge(listOf(old), listOf(10L), mapOf(10L to song(10L)))
        assertEquals(1, out.size)
        assertSame("规则 2 说「不动」——不能重建对象", old, out.single())
        assertEquals(1L, out.single().addedAt)
    }

    @Test
    fun `rule2 云端顺序变化时既有条目跟着云端重排（与 v2_6_0 之前逐字一致）`() {
        val a = remote(1L)
        val b = remote(2L)
        val c = remote(3L)
        val out = merge(listOf(a, b, c), listOf(3L, 1L, 2L), mapOf(3L to song(3L), 1L to song(1L), 2L to song(2L)))
        assertEquals(listOf(3L, 1L, 2L), out.map { it.trackKey.id })
    }

    // -------------------------------------------------------------- 规则 3 ----

    @Test
    fun `rule3 云端无 本地有 时保留（只加不减）`() {
        val kept = remote(99L)
        val out = merge(listOf(kept), listOf(10L), mapOf(10L to song(10L)))
        assertEquals(setOf(10L, 99L), out.map { it.trackKey.id }.toSet())
        assertTrue("本地有、云端无的条目必须原样保留", out.any { it === kept })
    }

    @Test
    fun `rule3 空云端列表不删任何本地条目`() {
        val list = listOf(remote(1L), local(2L))
        val out = merge(list, emptyList())
        assertEquals("一条都不许少", setOf(1L, 2L), out.map { it.trackKey.id }.toSet())
        // 顺序口径：`LOCAL`（手动新增）恒在最前。云端列表为空时的这一次重排是
        // **一次性**的 —— 之后再怎么刷新，1 与 2 的相对位置都不变（见下一条用例）。
        assertEquals(listOf(2L, 1L), out.map { it.trackKey.id })
    }

    /**
     * 顺序**不抖动**：同一份输入反复 merge 必须得到同一个顺序。
     *
     * 这一条防的是「每进一次库页列表就重排一次」那种体验缺陷 ——
     * 它在单测里看不出来（每次结果都「合理」），只会在真机上被用户看见。
     */
    @Test
    fun `反复 merge 的顺序是不动点`() {
        var acc = merge(
            listOf(remote(1L), local(2L), remote(3L)),
            listOf(3L, 1L),
            mapOf(3L to song(3L), 1L to song(1L)),
        )
        val first = acc.map { it.trackKey.id }
        repeat(5) {
            acc = merge(acc, listOf(3L, 1L), mapOf(3L to song(3L), 1L to song(1L)))
            assertEquals("第 N 次 merge 改了顺序 —— 列表会每次刷新都抖一下", first, acc.map { it.trackKey.id })
        }
    }

    // -------------------------------------------------------------- 规则 4 ----

    @Test
    fun `rule4 云端有 但在 tombstone 里 时跳过（删掉的歌不复活）`() {
        val dead = remote(10L, tombstoned = true)
        val out = merge(listOf(dead), listOf(10L), mapOf(10L to song(10L)))
        assertEquals(1, out.size)
        assertTrue("tombstone 必须留着（否则下一轮又会被当成『本地无』加回来）", out.single().tombstoned)
        assertFalse("tombstone 的条目不上屏", out.single().isVisible)
    }

    @Test
    fun `rule4 重复刷新十次 tombstone 都不复活`() {
        var acc = listOf(remote(10L, tombstoned = true))
        repeat(10) {
            acc = merge(acc, listOf(10L), mapOf(10L to song(10L)))
        }
        assertEquals(1, acc.size)
        assertTrue(acc.single().tombstoned)
    }

    // -------------------------------------------------------------- 规则 5 ----

    @Test
    fun `rule5 手动新增为 LOCAL 且插到最前`() {
        val out = SavedSongSync.addManual(listOf(remote(1L)), song(7L), now)
        assertEquals(listOf(7L, 1L), out.map { it.trackKey.id })
        assertEquals(SavedSongOrigin.LOCAL, out.first().origin)
    }

    @Test
    fun `rule5 手动新增的 QQ 曲目在同步里永远保留`() {
        val qq = local(qqRaw(), source = MusicSource.QQMUSIC, mid = "0039MnYb0qxYhV")
        // ★ 这就是本版 P0 的判据：云端 likedIds 里**永远**不会有这个 bit62 的 id。
        val out = merge(listOf(qq), listOf(1L, 2L), mapOf(1L to song(1L), 2L to song(2L)))
        assertEquals(3, out.size)
        val survivor = out.single { it.trackKey.source == MusicSource.QQMUSIC }
        assertTrue(survivor.isVisible)
        assertEquals(SavedSongOrigin.LOCAL, survivor.origin)
        // LOCAL 排在最前（与 saveSong 的既有语义一致）。
        assertEquals(qq.trackKey, out.first().trackKey)
    }

    @Test
    fun `rule5 手动新增幂等（重复点不改顺序也不改 origin）`() {
        val once = SavedSongSync.addManual(listOf(remote(1L)), song(7L), now)
        val twice = SavedSongSync.addManual(once, song(7L), now + 500L)
        assertSame("重复添加必须是零变化（引用相等）", once, twice)
    }

    @Test
    fun `rule5 云端同步来的歌被再加一次时不降级成 LOCAL`() {
        val fromCloud = remote(7L)
        val before = listOf(fromCloud)
        val out = SavedSongSync.addManual(before, song(7L), now + 1)
        assertSame("已可见的条目再加一次是零变化", before, out)
        assertEquals(SavedSongOrigin.REMOTE, out.single().origin)
    }

    // -------------------------------------------------------------- 规则 6 ----

    @Test
    fun `rule6 手动删除设 tombstone 而不是物理删除`() {
        val one = remote(1L)
        val out = SavedSongSync.remove(listOf(one, remote(2L)), TrackKey(MusicSource.NETEASE, 1L))
        assertEquals("长度不变 —— 物理删除会让下一次同步把它复活", 2, out.size)
        assertTrue(out.first { it.trackKey.id == 1L }.tombstoned)
        assertFalse(out.first { it.trackKey.id == 2L }.tombstoned)
    }

    @Test
    fun `rule6 删除幂等且零变化时返回同一实例`() {
        val list = listOf(remote(1L))
        val key = TrackKey(MusicSource.NETEASE, 1L)
        val once = SavedSongSync.remove(list, key)
        assertNotEquals(list, once)
        assertSame("再删一次必须零变化（否则会多发一次 like(false) 请求）", once, SavedSongSync.remove(once, key))
        assertSame("删一个不存在的 key 也必须零变化", list, SavedSongSync.remove(list, TrackKey(MusicSource.NETEASE, 42L)))
    }

    @Test
    fun `rule6 删除 QQ 条目用 bit62 反推出来的身份`() {
        val qq = local(qqRaw(), source = MusicSource.QQMUSIC, mid = "x")
        // 调用点只传裸 song.id，所以音源必须能从 id 反推（TrackKey.of(null, id)）。
        val out = SavedSongSync.remove(listOf(qq), TrackKey.of(null, qqRaw()))
        assertTrue(out.single().tombstoned)
    }

    // -------------------------------------------------------------- 规则 7 ----

    @Test
    fun `rule7 用户重新添加同一首 清除 tombstone 并回到最前`() {
        val dead = remote(7L, tombstoned = true)
        val out = SavedSongSync.addManual(listOf(remote(1L), dead), song(7L), now + 9)
        assertEquals(listOf(7L, 1L), out.map { it.trackKey.id })
        val revived = out.first()
        assertFalse("重新添加必须清除 tombstone", revived.tombstoned)
        assertTrue(revived.isVisible)
        assertEquals(SavedSongOrigin.LOCAL, revived.origin)
        assertEquals(now + 9, revived.addedAt)
    }

    @Test
    fun `rule7 复活之后再同步 云端也有的情况下不会被打回 tombstone`() {
        var acc = SavedSongSync.addManual(listOf(remote(7L, tombstoned = true)), song(7L), now + 9)
        acc = merge(acc, listOf(7L), mapOf(7L to song(7L)))
        assertEquals(1, acc.size)
        assertTrue(acc.single().isVisible)
    }

    // ------------------------------------------------------- 排序与容量有界 ----

    @Test
    fun `排序口径 本地手动新增 云端顺序 云端没有的本地 再 tombstone 尾巴`() {
        val manual = local(500L, addedAt = now + 5)
        val inCloud = remote(1L, addedAt = now + 1)
        val notInCloud = remote(400L, addedAt = now + 2)
        val dead = remote(300L, addedAt = now + 3, tombstoned = true)
        val out = merge(
            listOf(inCloud, notInCloud, manual, dead),
            listOf(2L, 1L),
            mapOf(2L to song(2L), 1L to song(1L)),
        )
        assertEquals(
            "① 手动新增 → ② 云端顺序 → ③ 云端没有的本地 → ④ tombstone 尾巴",
            listOf(500L, 2L, 1L, 400L, 300L),
            out.map { it.trackKey.id },
        )
    }

    @Test
    fun `tombstone 尾巴有界（最多 MAX_TOMBSTONES 条 按 addedAt 新到旧）`() {
        val many = (1..(SavedSongSync.MAX_TOMBSTONES + 120)).map {
            remote(it.toLong(), addedAt = it.toLong(), tombstoned = true)
        }
        val out = merge(many, emptyList())
        assertEquals(SavedSongSync.MAX_TOMBSTONES, out.size)
        // 保留的是**最新**的删除（addedAt 大的）。
        assertTrue(out.all { it.tombstoned })
        assertEquals(SavedSongSync.MAX_TOMBSTONES + 120L, out.first().addedAt)
        assertEquals(121L, out.last().addedAt)
    }

    // ---------------------------------------------------- 分页补详情（纯追加） ----

    @Test
    fun `appendRemote 只追加 不重排 不复活 tombstone`() {
        val list = listOf(remote(1L), remote(2L, tombstoned = true))
        val out = SavedSongSync.appendRemote(list, listOf(song(2L), song(3L), song(4L)), now)
        assertEquals(listOf(1L, 2L, 3L, 4L), out.map { it.trackKey.id })
        assertTrue("已 tombstone 的 id 不许被分页复活", out[1].tombstoned)
        assertTrue(out[0] === list[0] && out[1] === list[1])
    }

    @Test
    fun `appendRemote 对已有 id 幂等`() {
        val list = listOf(remote(1L))
        val once = SavedSongSync.appendRemote(list, listOf(song(1L)), now)
        assertSame(list, once)
    }

    // -------------------------------------------------------- 跨源写闸门 ----

    @Test
    fun `闸门 ncm id 可以发写请求`() {
        assertTrue(SavedSongSync.isRemoteLikeEligible(1L))
        assertTrue(SavedSongSync.isRemoteLikeEligible(3_000_000_000L))
    }

    @Test
    fun `闸门 QQ 合成 id 不许发给ncm的写接口`() {
        val qq = SourceIds.qqId(1234567890L, "0039MnYb0qxYhV")
        assertTrue("QQ 合成 id 是正数 ⇒ 旧卫语句拦不住它", qq > 0L)
        assertFalse(SavedSongSync.isRemoteLikeEligible(qq))
    }

    /**
     * ★ v3.4.5 · P0 的回归守卫：**B 站**合成 id 同样不许发给 ncm 的写接口。
     *
     * 旧判据 `songId > 0 && !isQqId(songId)` 对 B 站 id 返回 true（bit62 清零）——
     * 于是「加入曲库」或点心形会把 `2^61 + auid` POST 到 `/eapi/radio/like`。
     * 这条用例在旧实现下会红。
     */
    @Test
    fun `闸门 B站合成 id 不许发给ncm的写接口`() {
        val bili = SourceIds.biliId(22760301L)
        assertTrue("B 站合成 id 是正数 ⇒ 旧的 id>0 卫语句拦不住它", bili > 0L)
        assertFalse("B 站 id 的 bit62 必须清零，否则本用例测不到东西", SourceIds.isQqId(bili))
        assertFalse(SavedSongSync.isRemoteLikeEligible(bili))
    }

    @Test
    fun `闸门 非法 id 一律拒绝`() {
        assertFalse(SavedSongSync.isRemoteLikeEligible(0L))
        assertFalse(SavedSongSync.isRemoteLikeEligible(-1L))
    }

    /**
     * ★ 闸门判据与 `ReportGate`（v2.5.5 · B）**同源**：对任意 id，
     * 「能不能上报给 ncm」与「能不能发写请求给 ncm」必须给出同一个答案。
     *
     * 两条链路各写一份判据迟早会分叉（一条修了另一条没修）——
     * 而它们的根因是同一个（v2.5.5 是 bit62，v3.4.5 是 bit61 的 B 站）。
     * 这里用穷举把它钉住，**样本必须含三种音源的 id 形态**。
     */
    @Test
    fun `闸门判据与 ReportGate 的ncm方向逐值一致`() {
        val samples = buildList {
            addAll(listOf(-1L, 0L, 1L, 999L, 3_000_000_000L))
            addAll((1..50).map { SourceIds.qqId(it.toLong(), "mid$it") })
            addAll((1..50).map { SourceIds.biliId(it.toLong()) })
        }
        samples.forEach { id ->
            assertEquals(
                "id=$id 上两条链路的判据分叉了",
                com.takahashirinta.ncrust.player.ReportGate.mayReport(
                    com.takahashirinta.ncrust.player.ReportGate.Target.NETEASE_WEBLOG, id,
                ),
                SavedSongSync.isRemoteLikeEligible(id),
            )
        }
    }
}
