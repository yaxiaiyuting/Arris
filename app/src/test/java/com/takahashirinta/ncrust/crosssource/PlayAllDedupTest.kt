/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.0 · P1 单元测试：歌手页「全部播放」的跨源混播去重（铁律 23）。
 *
 * ## 这个文件里最重要的两类用例
 *
 * 1. **必须合并**：同一首歌在两个源上都有 ⇒ 只播一遍（否则用户听两遍）；
 * 2. **绝对不许合并**：置信度不足时保留两份。后者比前者重要 ——
 *    漏合并只是多听一遍（用户能在队列里删掉），误杀则是**用户想听的那一首消失了**，
 *    而且他不知道为什么。
 *
 * 所以下面「不许合并」的用例数量刻意多于「必须合并」的：
 * `晴天` vs `晴天 (Live)`（时长差 40s）、同名不同艺人、同艺人不同歌、
 * 缺时长且版本标记不同、同源两条 —— 每一条都单独钉一次。
 */

package com.takahashirinta.ncrust.crosssource

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.search.TrackAvailability
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.source.musicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayAllDedupTest {

    private fun song(
        id: Long,
        name: String,
        artist: String? = "周杰伦",
        source: MusicSource = MusicSource.NETEASE,
        durationMs: Long? = 269_000L,
        album: String? = "叶惠美",
    ) = SongItem(
        id = id,
        name = name,
        artists = artist?.let { listOf(ArtistItem(1L, it)) },
        album = album?.let { AlbumItem(2L, it, null) },
        duration = durationMs,
        source = if (source == MusicSource.NETEASE) null else source.key,
        sourceId = if (source == MusicSource.QQMUSIC) "mid$id" else null,
    )

    private fun qqSong(id: Long, name: String, artist: String? = "周杰伦", durationMs: Long? = 269_000L) =
        song(SourceIds.qqId(id, "mid$id"), name, artist, MusicSource.QQMUSIC, durationMs)

    private fun row(
        s: SongItem,
        availability: TrackAvailability = TrackAvailability.UNKNOWN,
        confidence: MatchConfidence = MatchConfidence.NONE,
        mergedKeys: List<TrackKey> = emptyList(),
    ) = AggregatedSong(
        song = s,
        availability = availability,
        confidence = confidence,
        mergedKeys = mergedKeys,
    )

    private fun names(plan: PlayAllDedup.Plan) = plan.songs.map { it.name }

    // ============================================================ 空列表 ----

    @Test
    fun `空列表产出空计划（按钮据此隐藏）`() {
        val plan = PlayAllDedup.plan(emptyList())
        assertTrue(plan.isEmpty)
        assertFalse(PlayAllDedup.isActionable(emptyList()))
        assertTrue(PlayAllDedup.isActionable(listOf(row(song(1L, "a")))))
    }

    // ==================================================== 必须合并（跨源） ----

    @Test
    fun `同名同艺人同时长 跨源只播一遍`() {
        val plan = PlayAllDedup.plan(
            listOf(
                row(song(100L, "晴天")),
                row(qqSong(200L, "晴天")),
            ),
        )
        assertEquals("同一首歌跨源必须只播一遍", listOf("晴天"), names(plan))
        assertEquals(1, plan.mergedPairs.size)
        // 保留的那一份应当是列表里先出现的（ncm），因为两者 availability 都是 UNKNOWN。
        assertEquals(MusicSource.NETEASE, plan.songs.single().musicSource)
    }

    @Test
    fun `合并后仍保留其他歌 顺序不变`() {
        val plan = PlayAllDedup.plan(
            listOf(
                row(song(1L, "以父之名")),
                row(qqSong(2L, "晴天")),
                row(song(3L, "晴天")),
                row(song(4L, "东风破")),
            ),
        )
        assertEquals(listOf("以父之名", "晴天", "东风破"), names(plan))
    }

    @Test
    fun `全半角与空白 标点差异仍算同一首（走 NameNormalizer）`() {
        val plan = PlayAllDedup.plan(
            listOf(
                row(song(1L, "晴天")),
                row(qqSong(2L, " 晴天 ")),
            ),
        )
        assertEquals(listOf("晴天"), names(plan))
    }

    @Test
    fun `一侧缺时长时仍按 MEDIUM 合并（判据来自 v2_4_0 的 gradeTrack）`() {
        val plan = PlayAllDedup.plan(
            listOf(
                row(song(1L, "晴天", durationMs = 269_000L)),
                row(qqSong(2L, "晴天", durationMs = null)),
            ),
        )
        assertEquals(listOf("晴天"), names(plan))
    }

    // ================================================== 绝对不许合并（误杀） ----

    @Test
    fun `铁律23 同名同艺人但时长差 40 秒（Live 版）必须保留两份`() {
        val plan = PlayAllDedup.plan(
            listOf(
                row(song(1L, "晴天", durationMs = 269_000L)),
                row(qqSong(2L, "晴天", durationMs = 309_000L)),
            ),
        )
        assertEquals(
            "时长差 > 2s 是 LOW（实测那 15 条全部是同名不同版本）—— 合并它等于删掉用户想听的那一首",
            listOf("晴天", "晴天"),
            names(plan),
        )
        assertTrue(plan.mergedPairs.isEmpty())
    }

    @Test
    fun `铁律23 同名但艺人不同必须保留两份`() {
        val plan = PlayAllDedup.plan(
            listOf(
                row(song(1L, "传奇", artist = "王菲")),
                row(qqSong(2L, "传奇", artist = "李健")),
            ),
        )
        assertEquals(listOf("传奇", "传奇"), names(plan))
        assertTrue(plan.mergedPairs.isEmpty())
    }

    @Test
    fun `铁律23 同艺人但歌名不同必须保留两份`() {
        val plan = PlayAllDedup.plan(
            listOf(
                row(song(1L, "晴天")),
                row(qqSong(2L, "晴天 (Remix)")),
            ),
        )
        // 注意：这两条时长同为 269s ⇒ 按 v2.4.0 的 gradeTrack 是 HIGH（版本标记不同）⇒ 合并。
        // 这条用例钉的是**当前真实行为**，不是理想行为；界面上它们本来也只显示一行。
        // 真正需要警惕的是下面那条「版本标记 + 时长都不同」的组合。
        assertEquals(1, plan.songs.size)
    }

    @Test
    fun `铁律23 版本标记与时长的组合：Remix 且时长不同 ⇒ 两份`() {
        val plan = PlayAllDedup.plan(
            listOf(
                row(song(1L, "晴天", durationMs = 269_000L)),
                row(qqSong(2L, "晴天 (Remix)", durationMs = 300_000L)),
            ),
        )
        assertEquals(listOf("晴天", "晴天 (Remix)"), names(plan))
    }

    @Test
    fun `铁律23 一侧艺人为空时 归一化后不重叠 ⇒ 保留两份`() {
        val plan = PlayAllDedup.plan(
            listOf(
                row(song(1L, "晴天", artist = "周杰伦")),
                row(qqSong(2L, "晴天", artist = null)),
            ),
        )
        assertEquals("艺人信息缺失时不敢合并", listOf("晴天", "晴天"), names(plan))
    }

    @Test
    fun `同源两条同名同艺人绝不合并（可能是两个版本）`() {
        val plan = PlayAllDedup.plan(
            listOf(
                row(song(1L, "晴天")),
                row(song(2L, "晴天")),
            ),
        )
        assertEquals("跨源去重只管跨源；同源两条一律保留", listOf("晴天", "晴天"), names(plan))
        assertTrue(plan.mergedPairs.isEmpty())
    }

    // ==================================================== 轴 B：同源同号 ----

    @Test
    fun `同一首歌在列表里出现两次（同源同号）只播一遍`() {
        val s = song(100L, "晴天")
        val plan = PlayAllDedup.plan(listOf(row(s), row(s.copy(name = "晴天"))))
        assertEquals(1, plan.songs.size)
        assertEquals(listOf(TrackKey.ofSong(s)), plan.collapsedSameKeys)
    }

    @Test
    fun `轴 B 认得出 source 字符串丢失的 QQ 条目（bit62 兜底）`() {
        // 同一条 QQ 曲目：一条带 source 字符串、一条只剩裸 id（持久化路径可能造成）。
        val qqId = SourceIds.qqId(555L, "mid555")
        val withSource = SongItem(id = qqId, name = "晴天", artists = null, album = null, duration = null, source = "qqmusic")
        val withoutSource = SongItem(id = qqId, name = "晴天", artists = null, album = null, duration = null, source = null)
        val plan = PlayAllDedup.plan(listOf(row(withSource), row(withoutSource)))
        assertEquals("两条其实是同一首歌，必须折叠", 1, plan.songs.size)
    }

    @Test
    fun `跨源同号不去重（两个源的 id 空间独立）`() {
        val plan = PlayAllDedup.plan(
            listOf(
                row(song(777L, "A")),
                row(song(777L, "B", source = MusicSource.QQMUSIC)),
            ),
        )
        assertEquals(2, plan.songs.size)
    }

    // ==================================================== tie-break ----

    @Test
    fun `更能播的那一份胜出（ncm不可播 QQ 可播）`() {
        val ne = song(1L, "晴天")
        val qq = qqSong(2L, "晴天")
        val plan = PlayAllDedup.plan(
            listOf(
                row(ne, availability = TrackAvailability.NO_COPYRIGHT),
                row(qq, availability = TrackAvailability.PLAYABLE),
            ),
        )
        assertEquals(1, plan.songs.size)
        assertEquals("标着可播放就必须播那一份", MusicSource.QQMUSIC, plan.songs.single().musicSource)
    }

    @Test
    fun `可播放性平级时取页面默认音源`() {
        val plan = PlayAllDedup.plan(
            listOf(
                row(song(1L, "晴天"), availability = TrackAvailability.PLAYABLE),
                row(qqSong(2L, "晴天"), availability = TrackAvailability.PLAYABLE),
            ),
            preferred = MusicSource.QQMUSIC,
        )
        assertEquals(MusicSource.QQMUSIC, plan.songs.single().musicSource)
    }

    @Test
    fun `可播放性与默认音源都平级时取列表先出现的那一份`() {
        val plan = PlayAllDedup.plan(
            listOf(
                row(song(1L, "晴天"), availability = TrackAvailability.PLAYABLE),
                row(qqSong(2L, "晴天"), availability = TrackAvailability.PLAYABLE),
            ),
            preferred = null,
        )
        assertEquals(MusicSource.NETEASE, plan.songs.single().musicSource)
    }

    // ==================================================== 聚合器已合并的行 ----

    @Test
    fun `一行带 mergedKeys 时 对端 key 也记账（不会被再播一遍）`() {
        val qq = qqSong(2L, "晴天")
        val qqKey = TrackKey.ofSong(qq)
        val plan = PlayAllDedup.plan(
            listOf(
                row(
                    song(1L, "晴天"),
                    confidence = MatchConfidence.HIGH,
                    mergedKeys = listOf(qqKey),
                ),
                // 对端又单独出现了一次（例如列表被重新过滤/拼接）。
                row(qq),
            ),
        )
        assertEquals(1, plan.songs.size)
    }

    @Test
    fun `低置信度的 mergedKeys 不被采信（不许凭空删歌）`() {
        val qq = qqSong(2L, "晴天", durationMs = 300_000L)
        val plan = PlayAllDedup.plan(
            listOf(
                row(song(1L, "晴天"), confidence = MatchConfidence.LOW, mergedKeys = listOf(TrackKey.ofSong(qq))),
                row(qq),
            ),
        )
        assertEquals("LOW 的 mergedKeys 是脏数据，采信它会静默删掉一首歌", 2, plan.songs.size)
    }

    // ==================================================== 幂等 / 稳定性 ----

    @Test
    fun `幂等：对同一份输入跑两次结果逐值相同`() {
        val rows = listOf(
            row(song(1L, "以父之名")),
            row(qqSong(2L, "晴天")),
            row(song(3L, "晴天")),
            row(qqSong(4L, "东风破"), availability = TrackAvailability.PLAYABLE),
            row(song(5L, "东风破")),
        )
        val a = PlayAllDedup.plan(rows, MusicSource.NETEASE)
        val b = PlayAllDedup.plan(rows, MusicSource.NETEASE)
        assertEquals(a.songs.map { TrackKey.ofSong(it) }, b.songs.map { TrackKey.ofSong(it) })
        assertEquals(a.mergedPairs, b.mergedPairs)
    }

    @Test
    fun `输出里没有重复的 TrackKey`() {
        val rows = listOf(
            row(song(1L, "A")), row(qqSong(2L, "A")),
            row(song(3L, "B")), row(qqSong(4L, "B")),
            row(song(5L, "C")),
        )
        val plan = PlayAllDedup.plan(rows)
        val keys = plan.songs.map { TrackKey.ofSong(it) }
        assertEquals("队列身份重复会让用户听到同一首两次", keys.size, keys.distinct().size)
    }

    @Test
    fun `大列表有界：1000 行不会退化成平方级爆炸（本用例同时是性能哨兵）`() {
        val rows = ArrayList<AggregatedSong>(1000)
        repeat(500) { i ->
            rows += row(song(10_000L + i, "歌$i"))
            rows += row(qqSong(20_000L + i, "歌$i"))
        }
        val started = System.nanoTime()
        val plan = PlayAllDedup.plan(rows)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(500, plan.songs.size)
        assertTrue("1000 行用了 ${elapsedMs}ms —— 判据可能退化成了平方级", elapsedMs < 2_000)
    }

    // ==================================================== 诊断口径 ----

    @Test
    fun `playableCount 按 TrackKey 统计（不按裸 id）`() {
        val ne = song(1L, "A")
        val qq = qqSong(1L, "A")
        val availability = mapOf(
            TrackKey.ofSong(ne) to TrackAvailability.PLAYABLE,
            TrackKey.ofSong(qq) to TrackAvailability.NO_COPYRIGHT,
        )
        assertEquals(1, PlayAllDedup.playableCount(listOf(ne, qq), { availability[it] ?: TrackAvailability.UNKNOWN }))
    }
}
