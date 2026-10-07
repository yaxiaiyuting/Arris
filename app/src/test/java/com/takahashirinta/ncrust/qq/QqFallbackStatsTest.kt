/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.4 · C：QQ 兜底统计的守卫单测（纯 JVM）。
 */

package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.source.SourceIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 计数器本身、快照归一化、比率口径、并发安全。
 *
 * ## 这一组用例在防什么
 *
 * 1. **口径漂移**：`noSongIdRate` / `fallbackPlayableRate` 的分子分母一旦各写一份，
 *    报告里的数字与 UI 上的数字就会不一样；
 * 2. **落盘迁移**：`QqFallbackCounters` 的字段必须能被老 JSON 缺字段地读出来
 *    （Gson 走 Unsafe），这是「加字段 = 加迁移逻辑 = 加单测」的落点；
 * 3. **并发**：自增来自 `Dispatchers.IO` 与 ExoPlayer 主线程两侧，
 *    `@Volatile var` + `+=` 会丢计数 —— 用例用 8 线程 × 1000 次把它钉住；
 * 4. **兜底判据**：`isSynthesizedQqId` 必须是纯函数、且与 `qqId` 的定义同源。
 */
class QqFallbackStatsTest {

    private val mid = "0039MnYb0qxYhV"

    // ------------------------------------------------------------ 计数 ----

    @Test
    fun `解析期计数分开记 songid 与 media_mid 两件事`() {
        val c = QqFallbackCounter()
        c.onParsed(hadSongId = true, hadMediaMid = true)
        c.onParsed(hadSongId = false, hadMediaMid = true)
        c.onParsed(hadSongId = false, hadMediaMid = false)
        val s = c.snapshot().canonical()
        assertEquals(3L, s.parsedTotal)
        assertEquals(2L, s.parsedNoSongId)
        assertEquals(1L, s.parsedNoMediaMid)
    }

    @Test
    fun `取链期四类结果各自独立计数`() {
        val c = QqFallbackCounter()
        c.onResolveAttempt(); c.onResolveMissingSongMid()
        c.onResolveAttempt(); c.onResolveMediaMidFallback(); c.onResolveOk()
        c.onResolveAttempt(); c.onResolveFail()
        val s = c.snapshot().canonical()
        assertEquals(3L, s.resolveTotal)
        assertEquals(1L, s.resolveNoSongMid)
        assertEquals(1L, s.resolveMediaMidFallback)
        assertEquals(1L, s.resolveOk)
        assertEquals(1L, s.resolveFail)
    }

    @Test
    fun `首次与最近时间戳只在真的有事件时推进`() {
        var now = 1000L
        val c = QqFallbackCounter { now }
        assertEquals(0L, c.snapshot().firstSeenAtMs)
        c.onParsed(hadSongId = true, hadMediaMid = true)
        assertEquals(1000L, c.snapshot().firstSeenAtMs)
        now = 5000L
        c.onResolveAttempt()
        assertEquals(1000L, c.snapshot().firstSeenAtMs)
        assertEquals(5000L, c.snapshot().lastUpdatedAtMs)
    }

    // ------------------------------------------------------------ 比率 ----

    @Test
    fun `分母为 0 时比率是 0 而不是 NaN`() {
        val s = QqFallbackCounters().canonical()
        assertEquals(0.0, s.noSongIdRate(), 1e-9)
        assertEquals(0.0, s.fallbackPlayableRate(), 1e-9)
    }

    @Test
    fun `无 songid 占比的分母是解析出的全部 QQ 曲目`() {
        val s = QqFallbackCounters(parsedTotal = 200L, parsedNoSongId = 5L).canonical()
        assertEquals(0.025, s.noSongIdRate(), 1e-9)
    }

    @Test
    fun `兜底可播率的分母是取链尝试过的兜底曲目而不是解析总数`() {
        // 解析出 100 首兜底曲目、其中只有 4 首被点开过（3 成 1 败），
        // 3 首真的出声 —— 可播率必须是 3/4，不是 3/100。
        val s = QqFallbackCounters(
            parsedTotal = 1000L, parsedNoSongId = 100L,
            resolveOk = 3L, resolveFail = 1L,
            fallbackPlaybackConfirmed = 3L,
        ).canonical()
        assertEquals(0.75, s.fallbackPlayableRate(), 1e-9)
    }

    @Test
    fun `结论串在四种样本形态下都可读且互不混淆`() {
        assertTrue(
            QqFallbackCounters().canonical().verdict().contains("样本为 0")
        )
        assertTrue(
            QqFallbackCounters(parsedTotal = 10L, parsedNoSongId = 0L).canonical()
                .verdict().contains("从未触发")
        )
        assertTrue(
            QqFallbackCounters(parsedTotal = 10L, parsedNoSongId = 1L).canonical()
                .verdict().contains("取链样本为 0")
        )
        assertTrue(
            QqFallbackCounters(
                parsedTotal = 10L, parsedNoSongId = 1L, resolveOk = 1L, fallbackPlaybackConfirmed = 1L
            ).canonical().verdict().contains("100% 成功")
        )
    }

    // -------------------------------------------------------- 落盘迁移 ----

    @Test
    fun `老 JSON 缺字段读出来是 null，归一化后是 0`() {
        // Gson 走 Unsafe 反序列化、不调用构造函数：v2.5.4 之后新增的字段
        // 在老 JSON 里根本不存在。这里直接喂一个"只有两个 key"的老形状。
        val restored = QqProbeStore.decode("""{"parsedTotal":7,"parsedNoSongId":2}""")
        assertNotNull(restored)
        assertNull("没写过的字段必须是 null，不是 0", restored!!.resolveOk)
        val c = restored.canonical()
        assertEquals(7L, c.parsedTotal)
        assertEquals(2L, c.parsedNoSongId)
        assertEquals(0L, c.resolveOk)
        assertEquals(QqFallbackCounters.SCHEMA_VERSION, c.schemaVersion)
    }

    @Test
    fun `encode 与 decode 往返保持全部字段`() {
        val src = QqFallbackCounters(
            parsedTotal = 1L, parsedNoSongId = 2L, parsedNoMediaMid = 3L,
            resolveTotal = 4L, resolveNoSongMid = 5L, resolveMediaMidFallback = 6L,
            resolveOk = 7L, resolveFail = 8L, fallbackPlaybackConfirmed = 9L,
            fallbackPlaybackFailed = 10L, firstSeenAtMs = 11L, lastUpdatedAtMs = 12L,
            schemaVersion = 1,
        )
        assertEquals(src, QqProbeStore.decode(QqProbeStore.encode(src)))
    }

    @Test
    fun `坏 JSON 返回 null 而不是抛异常`() {
        assertNull(QqProbeStore.decode(null))
        assertNull(QqProbeStore.decode(""))
        assertNull(QqProbeStore.decode("not json"))
    }

    @Test
    fun `seed 让进程重启后继续累加而不是从零开始`() {
        val c = QqFallbackCounter()
        c.seed(QqFallbackCounters(parsedTotal = 100L, parsedNoSongId = 5L, firstSeenAtMs = 42L))
        c.onParsed(hadSongId = false, hadMediaMid = true)
        val s = c.snapshot().canonical()
        assertEquals(101L, s.parsedTotal)
        assertEquals(6L, s.parsedNoSongId)
        assertEquals("首见时间必须沿用落盘的那一份", 42L, s.firstSeenAtMs)
    }

    // ------------------------------------------------------------ 并发 ----

    @Test
    fun `八线程并发自增不丢计数`() {
        val c = QqFallbackCounter()
        val threads = 8
        val perThread = 1000
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                start.await()
                repeat(perThread) {
                    c.onParsed(hadSongId = false, hadMediaMid = true)
                    c.onResolveAttempt()
                }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("并发用例超时", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        val s = c.snapshot().canonical()
        assertEquals((threads * perThread).toLong(), s.parsedTotal)
        assertEquals((threads * perThread).toLong(), s.parsedNoSongId)
        assertEquals((threads * perThread).toLong(), s.resolveTotal)
    }

    // -------------------------------------------------------- 兜底判据 ----

    @Test
    fun `没有 songid 的曲目 id 就是散列兜底，判据认得出`() {
        val id = SourceIds.qqId(0L, mid)
        assertTrue(SourceIds.isQqId(id))
        assertTrue(SourceIds.isSynthesizedQqId(id, mid))
    }

    @Test
    fun `有真实 songid 的曲目不是兜底`() {
        val id = SourceIds.qqId(102_795_182L, mid)
        assertTrue(SourceIds.isQqId(id))
        assertFalse("服务端给了 songid 就不该被判成兜底", SourceIds.isSynthesizedQqId(id, mid))
    }

    @Test
    fun `兜底判据对缺失或错误的 songmid 一律返回 false`() {
        val id = SourceIds.qqId(0L, mid)
        assertFalse(SourceIds.isSynthesizedQqId(id, null))
        assertFalse(SourceIds.isSynthesizedQqId(id, ""))
        assertFalse("换一个 songmid 就不是同一首歌的兜底 id 了", SourceIds.isSynthesizedQqId(id, "other"))
        assertFalse("ncm id 永远不是 QQ 兜底", SourceIds.isSynthesizedQqId(657666L, mid))
    }

    @Test
    fun `isSynthesizedQqId 是纯函数_同一输入恒等`() {
        val id = SourceIds.qqId(0L, mid)
        assertEquals(SourceIds.isSynthesizedQqId(id, mid), SourceIds.isSynthesizedQqId(id, mid))
        // 与 qqId 的定义同源：兜底 id 恒等于 QQ_ID_FLAG or hash(songmid)。
        assertEquals(SourceIds.QQ_ID_FLAG or (SourceIds.qqRawId(id) ?: 0L), id)
    }

    // ------------------------------------------------------ 进程内单例 ----

    @Test
    fun `单例埋点只对兜底曲目计数且同曲只计一次`() {
        QqProbeCounters.resetForTest()
        val fallbackId = SourceIds.qqId(0L, mid)
        val normalId = SourceIds.qqId(102_795_182L, mid)
        // 普通曲目：一次都不该记
        repeat(5) { QqProbeCounters.onPlaybackTick(normalId, mid) }
        assertEquals(0L, QqProbeCounters.snapshot().canonical().fallbackPlaybackConfirmed)
        // 兜底曲目：2Hz 心跳打十次，只记一次
        repeat(10) { QqProbeCounters.onPlaybackTick(fallbackId, mid) }
        assertEquals(1L, QqProbeCounters.snapshot().canonical().fallbackPlaybackConfirmed)
        // 失败同理
        repeat(3) { QqProbeCounters.onPlaybackFailure(fallbackId, mid) }
        repeat(3) { QqProbeCounters.onPlaybackFailure(normalId, mid) }
        assertEquals(1L, QqProbeCounters.snapshot().canonical().fallbackPlaybackFailed)
        QqProbeCounters.resetForTest()
    }

    @Test
    fun `songmid 缺失时确认信号不计数（判不出来就不计）`() {
        QqProbeCounters.resetForTest()
        val fallbackId = SourceIds.qqId(0L, mid)
        repeat(5) { QqProbeCounters.onPlaybackTick(fallbackId, null) }
        assertEquals(0L, QqProbeCounters.snapshot().canonical().fallbackPlaybackConfirmed)
        QqProbeCounters.resetForTest()
    }
}
