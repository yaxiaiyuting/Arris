/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.3.0 · 用户需求第 10 条：播放统计「有效收听时长」口径的守卫单测。
 */

package com.takahashirinta.ncrust.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * [StatsMath] 的守卫。
 *
 * 这一组用例的**唯一目的**是把「有效收听时长」那几条边界钉死在代码里：
 * 暂停、seek 前跳、进度回退、时钟回拨、单次增量上限、跨零点、夏令时。
 * 每一条都对应一个「如果写错了，统计页就会说谎」的具体故障 ——
 * 注释里写的是那个故障，不是「本函数返回 X」。
 */
class StatsMathTest {

    private val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
    private val newYork = TimeZone.getTimeZone("America/New_York")

    /** 在指定时区里造一个确定的 epoch 毫秒（不用 java.time：minSdk 24 且没有 desugaring）。 */
    private fun at(
        zone: TimeZone,
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
        second: Int = 0,
        ms: Int = 0,
    ): Long {
        val cal = Calendar.getInstance(zone)
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        cal.set(Calendar.MILLISECOND, ms)
        return cal.timeInMillis
    }

    // ============================================================ 收听增量 ====

    /** 暂停停表：位置没动不是判据，「在不在播」才是。 */
    @Test
    fun `暂停期间不产生任何收听时长`() {
        assertEquals(
            0L,
            StatsMath.listenDelta(prevPosMs = 10_000L, posMs = 10_500L, wallDeltaMs = 500L, playing = false),
        )
    }

    /** 正常播放：位置增量 ≈ 墙上时钟，照记。 */
    @Test
    fun `正常播放时按位置增量记账`() {
        assertEquals(
            500L,
            StatsMath.listenDelta(prevPosMs = 10_000L, posMs = 10_500L, wallDeltaMs = 500L, playing = true),
        )
    }

    /**
     * seek 前跳：位置跑得比墙上时钟快。
     *
     * 这是本页最容易说谎的一处 —— 若用「增量大小」当判据（比如「> 3 秒就算 seek」），
     * 主线程卡顿后的一次正常补偿就会被丢掉；若完全不判，拖一下进度条就能刷出半小时。
     */
    @Test
    fun `进度前跳不计入`() {
        // 拖到 +120 秒，而墙上只过了 0.5 秒
        assertEquals(
            0L,
            StatsMath.listenDelta(
                prevPosMs = 10_000L,
                posMs = 130_000L,
                wallDeltaMs = 500L,
                playing = true,
            ),
        )
    }

    /** 小幅前跳（1 秒拖一下）只允许 tolerance 之内的部分过去，绝不放行整段。 */
    @Test
    fun `小幅拖动最多只会计入容差之内的量`() {
        // 位置 +800ms，墙上 500ms ⇒ 800 <= 500 + 1000 ⇒ 记 800（一次性误差上界 = tolerance）
        assertEquals(
            800L,
            StatsMath.listenDelta(prevPosMs = 10_000L, posMs = 10_800L, wallDeltaMs = 500L, playing = true),
        )
        // 位置 +5 秒，墙上 500ms ⇒ 5000 > 1500 ⇒ 一点都不记
        assertEquals(
            0L,
            StatsMath.listenDelta(prevPosMs = 10_000L, posMs = 15_000L, wallDeltaMs = 500L, playing = true),
        )
    }

    /**
     * 进度回退（用户把进度条拖回开头）：**不重复计**。
     *
     * 已经累计过的那一段也不回滚 —— 用户确实听过，回滚会让「拖回开头重听」
     * 变成负贡献，那比多算更反直觉。
     */
    @Test
    fun `进度回退不产生负增量也不回滚已记录的值`() {
        assertEquals(
            0L,
            StatsMath.listenDelta(prevPosMs = 120_000L, posMs = 5_000L, wallDeltaMs = 500L, playing = true),
        )
        assertEquals(
            0L,
            StatsMath.listenDelta(prevPosMs = 10_000L, posMs = 10_000L, wallDeltaMs = 500L, playing = true),
        )
    }

    /**
     * 主线程卡顿 3 秒后位置一次补上 3 秒：**必须照记**。
     *
     * 这一条是「用 wall-delta 而不是固定阈值」的理由本身。用固定阈值（比如 2 秒）的实现
     * 会在这里丢掉 3 秒真实收听，而且丢的是低端机上最常见的那种片段。
     */
    @Test
    fun `主线程卡顿后的一次补偿照记`() {
        assertEquals(
            3_000L,
            StatsMath.listenDelta(
                prevPosMs = 10_000L,
                posMs = 13_000L,
                wallDeltaMs = 3_000L,
                playing = true,
            ),
        )
    }

    /** 单次可记增量有硬上限：系统时钟被往前拨一小时时，位置增量也会跟着「变得合法」。 */
    @Test
    fun `单次增量超过上限一律不记`() {
        val huge = StatsMath.MAX_COUNTABLE_DELTA_MS + 1
        assertEquals(
            0L,
            StatsMath.listenDelta(
                prevPosMs = 0L,
                posMs = huge,
                // 墙上时钟也被拨了同样多 ⇒ 只靠 wall-delta 判不出来，必须有第二道闸
                wallDeltaMs = huge,
                playing = true,
            ),
        )
    }

    /** 时钟回拨：wall-delta 归零，而不是变成负数（负数会让正常播放被判成前跳，统计从此全空）。 */
    @Test
    fun `时钟回拨时墙上增量归零`() {
        assertEquals(0L, StatsMath.wallDeltaOf(prevNowMs = 1_000L, nowMs = 900L))
        assertEquals(500L, StatsMath.wallDeltaOf(prevNowMs = 1_000L, nowMs = 1_500L))
        // 归零之后正常的 500ms 采样仍然落在容差内 —— 采样不会因为改时间而中断
        assertEquals(
            500L,
            StatsMath.listenDelta(
                prevPosMs = 0L,
                posMs = 500L,
                wallDeltaMs = StatsMath.wallDeltaOf(1_000L, 900L),
                playing = true,
            ),
        )
    }

    // ============================================================ 播放计数 ====

    /** 听了 5 秒就切走：**时长照记，但不计一次播放**（这是任务书点名要阈值的那一条）。 */
    @Test
    fun `听了五秒就切走不算一次播放`() {
        assertFalse(StatsMath.countsAsPlay(listenedMs = 5_000L, durationMs = 240_000L))
    }

    /** 累计 30 秒即算一次（行业口径，见 StatsMath 的 KDoc）。 */
    @Test
    fun `累计三十秒算一次播放`() {
        assertTrue(StatsMath.countsAsPlay(listenedMs = 30_000L, durationMs = 240_000L))
    }

    /** 比例判据：短曲（20 秒的间奏）听满 80% 也算 —— 否则「完整听完一首」反而没记录。 */
    @Test
    fun `短曲听满八成算一次播放`() {
        assertTrue(StatsMath.countsAsPlay(listenedMs = 16_000L, durationMs = 20_000L))
        assertFalse(StatsMath.countsAsPlay(listenedMs = 15_900L, durationMs = 20_000L))
    }

    /** 时长元数据还没到（duration = 0）时，比例判据不成立，但仍受 30 秒绝对阈值保护。 */
    @Test
    fun `时长未知时只能靠绝对阈值`() {
        assertFalse(StatsMath.countsAsPlay(listenedMs = 20_000L, durationMs = 0L))
        assertTrue(StatsMath.countsAsPlay(listenedMs = 30_000L, durationMs = 0L))
        assertFalse(StatsMath.countsAsPlay(listenedMs = 0L, durationMs = 0L))
    }

    // ============================================================== 日分桶 ====

    /** 本地日：23:59 与次日 00:01 必须落在两个不同的桶里。 */
    @Test
    fun `跨零点的两个时刻落在不同的日桶`() {
        val before = at(shanghai, 2026, 2, 14, 23, 59, 59)
        val after = at(shanghai, 2026, 2, 15, 0, 1, 0)
        assertEquals("2026-02-14", StatsMath.dayKey(before, shanghai))
        assertEquals("2026-02-15", StatsMath.dayKey(after, shanghai))
    }

    /** 同一时刻在不同时区属于不同的「今天」—— 分桶必须按设备本地时区，不是 UTC。 */
    @Test
    fun `同一时刻在不同时区可能落在不同的日桶`() {
        val instant = at(shanghai, 2026, 2, 14, 8, 0, 0) // = 2026-02-14 00:00 UTC
        assertEquals("2026-02-14", StatsMath.dayKey(instant, shanghai))
        assertEquals("2026-02-13", StatsMath.dayKey(instant, TimeZone.getTimeZone("America/New_York")))
    }

    /** 不跨天的一段仍然只切出一片。 */
    @Test
    fun `同一天内的一段只切出一片`() {
        val start = at(shanghai, 2026, 2, 14, 10, 0, 0)
        val slices = StatsMath.splitAcrossDays(start, start + 600_000L, shanghai)
        assertEquals(1, slices.size)
        assertEquals("2026-02-14", slices[0].dayKey)
        assertEquals(600_000L, slices[0].ms)
    }

    /** 跨零点的一段必须切开，且**总和不丢**（这是列上最容易差一秒的地方）。 */
    @Test
    fun `跨零点的一段被切成两片且总和不丢`() {
        val start = at(shanghai, 2026, 2, 14, 23, 30, 0)
        val end = at(shanghai, 2026, 2, 15, 0, 30, 0)
        val slices = StatsMath.splitAcrossDays(start, end, shanghai)
        assertEquals(2, slices.size)
        assertEquals("2026-02-14", slices[0].dayKey)
        assertEquals(30 * 60_000L, slices[0].ms)
        assertEquals("2026-02-15", slices[1].dayKey)
        assertEquals(30 * 60_000L, slices[1].ms)
        assertEquals(end - start, slices.sumOf { it.ms })
    }

    /** 夏令时切换日：跨过它仍然按「本地日」切，总和不丢（那一天只有 23 小时）。 */
    @Test
    fun `夏令时切换日跨天仍然正确`() {
        // 2026-03-08 是美东夏令时开始的那一天（本地 02:00 直接跳到 03:00）。
        val start = at(newYork, 2026, 3, 8, 0, 30, 0)
        val end = start + 24L * 60L * 60L * 1_000L
        val slices = StatsMath.splitAcrossDays(start, end, newYork)
        assertEquals(2, slices.size)
        assertEquals("2026-03-08", slices[0].dayKey)
        assertEquals("2026-03-09", slices[1].dayKey)
        assertEquals(end - start, slices.sumOf { it.ms })
    }

    /** 空区间 / 时钟回拨：返回一条 0 毫秒的记录而不是空列表（调用方据此知道「发生了但时长为 0」）。 */
    @Test
    fun `空区间返回一条零毫秒记录而不是空列表`() {
        val t = at(shanghai, 2026, 2, 14, 12, 0, 0)
        val same = StatsMath.splitAcrossDays(t, t, shanghai)
        assertEquals(1, same.size)
        assertEquals("2026-02-14", same[0].dayKey)
        assertEquals(0L, same[0].ms)

        val backwards = StatsMath.splitAcrossDays(t, t - 60_000L, shanghai)
        assertEquals(1, backwards.size)
        assertEquals(0L, backwards[0].ms)
    }

    /**
     * 有界性：时钟被拨到十年后也**不许**展开成三千多个桶。
     *
     * 这条不是理论风险 —— 采样增量本来 ≤10 秒，能出现十年跨度只有一种来源：
     * 系统时间被改。真发生时页面必须还能打开。
     */
    @Test
    fun `极端跨度被片数上限截断且总长守恒`() {
        val start = at(shanghai, 2026, 2, 14, 0, 0, 0)
        val end = start + 10L * 365L * 24L * 60L * 60L * 1_000L
        val slices = StatsMath.splitAcrossDays(start, end, shanghai)
        assertTrue("片数必须有上限，实际 ${slices.size}", slices.size <= 400)
        assertEquals(end - start, slices.sumOf { it.ms })
    }

    // ============================================================ 序列 / 占比 ====

    /** 缺口补 0 且升序：不能把「没听的那两天」压缩掉，否则读图的人以为每天都在听。 */
    @Test
    fun `最近 N 天序列缺口补零且升序`() {
        val today = at(shanghai, 2026, 2, 14, 15, 0, 0)
        val series = StatsMath.recentDaySeries(
            byDay = mapOf("2026-02-14" to 1_000L, "2026-02-11" to 5_000L),
            todayEpochMs = today,
            days = 5,
            zone = shanghai,
        )
        assertEquals(
            listOf("2026-02-10", "2026-02-11", "2026-02-12", "2026-02-13", "2026-02-14"),
            series.map { it.dayKey },
        )
        assertEquals(listOf(0L, 5_000L, 0L, 0L, 1_000L), series.map { it.ms })
    }

    /** 跨月回退：2 月 2 日往前 5 天要落到 1 月，不能出现 `2026-02--3`。 */
    @Test
    fun `最近 N 天序列跨月回退正确`() {
        val today = at(shanghai, 2026, 2, 2, 12, 0, 0)
        val series = StatsMath.recentDaySeries(emptyMap(), today, days = 4, zone = shanghai)
        assertEquals(
            listOf("2026-01-30", "2026-01-31", "2026-02-01", "2026-02-02"),
            series.map { it.dayKey },
        )
    }

    @Test
    fun `天数为零时序列为空`() {
        assertTrue(StatsMath.recentDaySeries(emptyMap(), 0L, 0, shanghai).isEmpty())
        assertTrue(StatsMath.recentDaySeries(emptyMap(), 0L, -3, shanghai).isEmpty())
    }

    @Test
    fun `柱状图刻度去掉前导零`() {
        assertEquals("14", StatsMath.dayAxisLabel("2026-02-14"))
        assertEquals("1", StatsMath.dayAxisLabel("2026-01-01"))
        assertEquals("30", StatsMath.dayAxisLabel("2026-11-30"))
    }

    @Test
    fun `占比在边界上不会产生 NaN 或超过一百`() {
        assertEquals(0, StatsMath.sharePercent(0L, 0L))
        assertEquals(0, StatsMath.sharePercent(100L, 0L))
        assertEquals(0, StatsMath.sharePercent(-5L, 100L))
        assertEquals(50, StatsMath.sharePercent(50L, 100L))
        assertEquals(100, StatsMath.sharePercent(100L, 100L))
        assertEquals(100, StatsMath.sharePercent(200L, 100L))
    }

    /** Top-N：稳定排序（并列保持输入顺序），n <= 0 返回空。 */
    @Test
    fun `TopN 并列时保持输入顺序`() {
        val items = listOf("a" to 10L, "b" to 30L, "c" to 10L, "d" to 30L)
        assertEquals(
            listOf("b", "d", "a"),
            StatsMath.topN(items, 3) { it.second }.map { it.first },
        )
        assertTrue(StatsMath.topN(items, 0) { it.second }.isEmpty())
    }

    @Test
    fun `日均分母为零时返回零`() {
        assertEquals(0L, StatsMath.averagePerActiveDay(0L, 0))
        assertEquals(0L, StatsMath.averagePerActiveDay(10_000L, 0))
        assertEquals(3_600_000L, StatsMath.averagePerActiveDay(7_200_000L, 2))
    }

    @Test
    fun `时长拆分到时分秒`() {
        assertEquals(DurationParts(0, 0, 0), StatsMath.durationParts(0L))
        assertEquals(DurationParts(0, 0, 45), StatsMath.durationParts(45_999L))
        assertEquals(DurationParts(1, 1, 1), StatsMath.durationParts(3_661_000L))
        // 负值（时钟回拨后的差值）按 0 处理，不抛异常也不出现负时长
        assertEquals(DurationParts(0, 0, 0), StatsMath.durationParts(-1_000L))
    }
}
