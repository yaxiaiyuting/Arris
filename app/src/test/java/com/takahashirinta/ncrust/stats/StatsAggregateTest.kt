/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0 · 用户需求第 10 条：播放统计聚合器的守卫单测。
 */

package com.takahashirinta.ncrust.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StatsAggregate] 的守卫。
 *
 * 这一层负责三件会静静算错的事：**维度合并**（天 / 平台 / 单曲必须同时更新）、
 * **有界淘汰**（上限之外的数据必须真的消失，且不能带走总量）、
 * **恢复**（老落盘数据里的字段缺失要能回落，而不是把用户清零）。
 */
class StatsAggregateTest {

    private val netease = "netease"
    private val qq = "qqmusic"

    private fun listen(days: List<Pair<String, Long>>) = days.map { DaySlice(it.first, it.second) }

    @Test
    fun `一次收听同时更新天 平台 单曲三个维度`() {
        val agg = StatsAggregate()
        agg.addListen(
            slices = listen(listOf("2026-02-14" to 60_000L)),
            songKey = "netease:1",
            sourceKey = netease,
            name = "某首歌",
            artist = "某人",
            durationMs = 240_000L,
            nowMs = 1_000L,
        )

        val snap = agg.snapshot()
        assertEquals(60_000L, snap.totalMs)
        assertEquals(60_000L, snap.byDay["2026-02-14"])
        assertEquals(60_000L, snap.bySource[netease])
        assertEquals(60_000L, snap.songs.getValue("netease:1").ms)
        assertEquals(0L, snap.songs.getValue("netease:1").plays)
        assertEquals("2026-02-14", snap.firstDayKey)
        // 还没有「一次播放」——时长与次数是两个独立维度（口径见 StatsMath）。
        assertEquals(0L, snap.totalPlays)
    }

    @Test
    fun `跨零点的一段会同时记进两天`() {
        val agg = StatsAggregate()
        agg.addListen(
            slices = listen(listOf("2026-02-14" to 30_000L, "2026-02-15" to 20_000L)),
            songKey = "netease:1",
            sourceKey = netease,
            name = "n",
            artist = "a",
            durationMs = 0L,
            nowMs = 0L,
        )
        val snap = agg.snapshot()
        assertEquals(50_000L, snap.totalMs)
        assertEquals(2, snap.activeDays)
        assertEquals("2026-02-14", snap.firstDayKey)
    }

    /**
     * 无缝预载切歌时 `currentSongName` 会比位置晚一帧到。
     * 用空串覆盖会让 Top-N 里已经显示正常的歌名突然变成空白 —— 必须保留旧值。
     */
    @Test
    fun `空歌名不会覆盖已有歌名`() {
        val agg = StatsAggregate()
        agg.addListen(listen(listOf("2026-02-14" to 1_000L)), "netease:1", netease, "真名", "真人", 1L, 0L)
        agg.addListen(listen(listOf("2026-02-14" to 1_000L)), "netease:1", netease, "", "", 0L, 1L)
        val song = agg.snapshot().songs.getValue("netease:1")
        assertEquals("真名", song.name)
        assertEquals("真人", song.artist)
        // 时长也取见过的最大值：起播头几百毫秒里 duration 还是 0
        assertEquals(1L, song.durationMs)
        assertEquals(2_000L, song.ms)
    }

    @Test
    fun `全零切片不改变任何计数`() {
        val agg = StatsAggregate()
        agg.addListen(listen(listOf("2026-02-14" to 0L)), "netease:1", netease, "n", "a", 0L, 0L)
        assertEquals(StatsSnapshot.EMPTY, agg.snapshot())
        assertNull(agg.snapshot().firstDayKey)
    }

    @Test
    fun `播放次数分别落在总数 平台 单曲三处`() {
        val agg = StatsAggregate()
        agg.addListen(listen(listOf("2026-02-14" to 60_000L)), "netease:1", netease, "n", "a", 60_000L, 0L)
        agg.addPlay("netease:1", netease)
        agg.addPlay("netease:1", netease)

        val snap = agg.snapshot()
        assertEquals(2L, snap.totalPlays)
        assertEquals(2L, snap.playsBySource[netease])
        assertEquals(2L, snap.songs.getValue("netease:1").plays)
        // 次数不影响时长
        assertEquals(60_000L, snap.totalMs)
    }

    /** 没有单曲条目的 `addPlay` 不许凭空造一条「0 毫秒、空名字」的记录（那在 Top-N 里就是一行空白）。 */
    @Test
    fun `对未知单曲的播放计数不造空条目`() {
        val agg = StatsAggregate()
        agg.addPlay("qqmusic:9", qq)
        val snap = agg.snapshot()
        assertEquals(1L, snap.totalPlays)
        assertEquals(1L, snap.playsBySource[qq])
        assertTrue(snap.songs.isEmpty())
    }

    /** 单曲表按 `lastAtMs` 做 LRU 淘汰；**总量与平台量不参与淘汰**。 */
    @Test
    fun `单曲表超上限时淘汰最久未更新的`() {
        val agg = StatsAggregate()
        repeat(5) { i ->
            agg.addListen(
                slices = listen(listOf("2026-02-14" to 1_000L)),
                songKey = "netease:$i",
                sourceKey = netease,
                name = "s$i",
                artist = "a",
                durationMs = 0L,
                nowMs = i.toLong(),
            )
        }
        agg.prune(maxSongs = 3)
        val snap = agg.snapshot()
        assertEquals(3, snap.songs.size)
        assertNull(snap.songs["netease:0"])
        assertNull(snap.songs["netease:1"])
        assertNotNull(snap.songs["netease:4"])
        // 被淘汰的歌的时长仍然留在总量与平台量里 —— 否则「总共听了多久」会随淘汰缩水
        assertEquals(5_000L, snap.totalMs)
        assertEquals(5_000L, snap.bySource[netease])
    }

    /** 日桶只保留最近的 N 天，且**总量不受影响**（总量是标量，这是聚合方案的代价与保证）。 */
    @Test
    fun `日桶超上限时只保留最近若干天`() {
        val agg = StatsAggregate()
        // 造 5 天，每天 1 秒
        listOf("2026-02-10", "2026-02-11", "2026-02-12", "2026-02-13", "2026-02-14").forEach { day ->
            agg.addListen(listen(listOf(day to 1_000L)), "netease:1", netease, "n", "a", 0L, 0L)
        }
        agg.prune(maxSongs = 100, maxDayBuckets = 2)
        val snap = agg.snapshot()
        assertEquals(setOf("2026-02-13", "2026-02-14"), snap.byDay.keys)
        assertEquals(5_000L, snap.totalMs)
        // 被裁掉的日桶不再参与「活跃天数」—— 页面上那个数的语义就是「留着的这些天」
        assertEquals(2, snap.activeDays)
        // 但「统计自」仍然是真实的最早一天（它是单独存的，不靠 byDay 反推）
        assertEquals("2026-02-10", snap.firstDayKey)
    }

    /**
     * `firstDayKey` 取**最小**而不是「第一个写入的」。
     *
     * 时钟回拨之后，后写入的那一天会更早 —— 用「首次写入」会让「统计自」显示成
     * 比实际更晚的一天，用户看到的日期与自己记忆对不上。
     */
    @Test
    fun `firstDayKey 取最早的一天而不是第一个写入的`() {
        val agg = StatsAggregate()
        agg.addListen(listen(listOf("2026-02-14" to 1_000L)), "netease:1", netease, "n", "a", 0L, 0L)
        agg.addListen(listen(listOf("2026-02-01" to 1_000L)), "netease:1", netease, "n", "a", 0L, 0L)
        assertEquals("2026-02-01", agg.snapshot().firstDayKey)
    }

    @Test
    fun `快照是值拷贝 取完之后再累加不会改动它`() {
        val agg = StatsAggregate()
        agg.addListen(listen(listOf("2026-02-14" to 1_000L)), "netease:1", netease, "n", "a", 0L, 0L)
        val before = agg.snapshot()
        agg.addListen(listen(listOf("2026-02-14" to 1_000L)), "netease:1", netease, "n", "a", 0L, 0L)
        assertEquals(1_000L, before.totalMs)
        assertEquals(2_000L, agg.snapshot().totalMs)
    }

    @Test
    fun `从快照恢复会带回全部维度并裁掉超限数据`() {
        val snapshot = StatsSnapshot(
            totalMs = 10_000L,
            totalPlays = 3L,
            firstDayKey = "2026-02-14",
            byDay = mapOf("2026-02-14" to 10_000L),
            bySource = mapOf(netease to 10_000L),
            playsBySource = mapOf(netease to 3L),
            songs = mapOf(
                "netease:1" to SongStat("netease:1", "n", "a", 10_000L, 3L, 5L, 200_000L),
                // 一条「什么都没有」的条目：恢复时必须被丢掉（它在 Top-N 里占额度不显示东西）
                "netease:2" to SongStat("netease:2", "", "", 0L, 0L, 0L, 0L),
            ),
        )
        val restored = StatsAggregate.from(snapshot).snapshot()
        assertEquals(10_000L, restored.totalMs)
        assertEquals(3L, restored.totalPlays)
        assertEquals("2026-02-14", restored.firstDayKey)
        assertEquals(setOf("netease:1"), restored.songs.keys)
        assertEquals(200_000L, restored.songs.getValue("netease:1").durationMs)
    }

    @Test
    fun `恢复时负的总量被归零而不是原样带进来`() {
        val restored = StatsAggregate
            .from(StatsSnapshot(totalMs = -5L, totalPlays = -1L))
            .snapshot()
        assertEquals(0L, restored.totalMs)
        assertEquals(0L, restored.totalPlays)
        assertTrue(restored.isEmpty)
    }
}
