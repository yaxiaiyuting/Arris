/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.5 · B：跨音源上报闸门的单测。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.source.SourceIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReportGate] / [ReportGateCounter] 的纯逻辑守卫。
 *
 * ## 这一组用例对应的**真机事实**（不是假想）
 *
 * `docs/verification/v2.5.5/probe-playreporter.md` 在 PLC110（Android 16 / v2.5.4 vc45）上
 * 实测到：冷启动从 `ncrust_playback_state` 恢复出一首 QQ 曲目
 * （`restore -> track=qqmusic:4611686018987997` 形状的 bit62 id），
 * 自然播完时 `PlayReporter` 打出了 `weblog resp: 200` —— 也就是
 * **一个 QQ 合成 id 真的被 POST 给了 ncm 的 webLog**。
 *
 * 所以这里的边界值取的是**那台设备上真实出现过的 id 形态**，不是随手写的 12345。
 */
class ReportGateTest {

    /** 真机取证过的形状：`(1L shl 62) or 357600093`。 */
    private val realQqId = SourceIds.qqId(357600093L, "0039MnYb0qxYhV")

    /** 普通的 ncm id。 */
    private val neteaseId = 503572L

    // ------------------------------------------------------------ bit62 是唯一判据

    @Test
    fun `bit62 合成 id 是正数 所以旧的 songId 大于 0 卫语句拦不住它`() {
        // 这条断言就是整个 bug 的根因：`songId <= 0` 结构上不可能拦住 QQ 合成 id。
        assertTrue("bit62 合成 id 必须是正数", realQqId > 0L)
        assertTrue(SourceIds.isQqId(realQqId))
        // ncm 的 id 空间远小于 2^40，永远触不到位 62。
        assertFalse(SourceIds.isQqId(neteaseId))
        assertEquals(1L shl 62, realQqId and (1L shl 62))
    }

    // ------------------------------------------------------------ 主方向：QQ id → ncm

    @Test
    fun `QQ 合成 id 不许上报给ncm`() {
        assertFalse(
            "QQ 合成 id 上报给了 ncm webLog —— 跨源数据泄露（铁律：跨源 id 不得上报给非本源服务）",
            ReportGate.mayReport(ReportGate.Target.NETEASE_WEBLOG, realQqId),
        )
        assertNotNull(
            "被拦下时必须给得出原因（本地日志要能直接回答为什么）",
            ReportGate.blockReason(ReportGate.Target.NETEASE_WEBLOG, realQqId),
        )
    }

    @Test
    fun `ncm id 正常上报给ncm`() {
        assertTrue(ReportGate.mayReport(ReportGate.Target.NETEASE_WEBLOG, neteaseId))
        assertNull(ReportGate.blockReason(ReportGate.Target.NETEASE_WEBLOG, neteaseId))
    }

    // ------------------------------------------------------------ 反方向：ncm id → QQ

    @Test
    fun `ncm id 不许上报给 QQ`() {
        assertFalse(
            "ncm id 上报给了 QQ —— 反方向同样禁止",
            ReportGate.mayReport(ReportGate.Target.QQ, neteaseId),
        )
        assertNotNull(ReportGate.blockReason(ReportGate.Target.QQ, neteaseId))
    }

    @Test
    fun `QQ 合成 id 可以上报给 QQ`() {
        assertTrue(ReportGate.mayReport(ReportGate.Target.QQ, realQqId))
        assertNull(ReportGate.blockReason(ReportGate.Target.QQ, realQqId))
    }

    /** 双向对称：**每个 id 恰好被一个目标接受**，不可能两边都收、也不可能两边都不收。 */
    @Test
    fun `每个合法 id 恰好被一个目标接受`() {
        for (id in listOf(neteaseId, 1L, 999_999_999L, realQqId)) {
            val toNetease = ReportGate.mayReport(ReportGate.Target.NETEASE_WEBLOG, id)
            val toQq = ReportGate.mayReport(ReportGate.Target.QQ, id)
            assertTrue("id=$id 被两个目标同时接受", !(toNetease && toQq))
            assertTrue("id=$id 被两个目标同时拒绝", toNetease || toQq)
        }
    }

    // ------------------------------------------------------------ 非法 id

    @Test
    fun `非正 id 两个方向都拒绝`() {
        for (id in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFalse(ReportGate.mayReport(ReportGate.Target.NETEASE_WEBLOG, id))
            assertFalse(ReportGate.mayReport(ReportGate.Target.QQ, id))
            assertNotNull(ReportGate.blockReason(ReportGate.Target.NETEASE_WEBLOG, id))
        }
    }

    /** `Long.MAX_VALUE` 的 bit62 是置位的（它 > 2^62）—— 按结构判据它属于 QQ 侧。 */
    @Test
    fun `Long 最大值按 bit62 判归 QQ 侧`() {
        assertTrue(SourceIds.isQqId(Long.MAX_VALUE))
        assertFalse(ReportGate.mayReport(ReportGate.Target.NETEASE_WEBLOG, Long.MAX_VALUE))
        assertTrue(ReportGate.mayReport(ReportGate.Target.QQ, Long.MAX_VALUE))
    }

    /** `2^62` 本身（裸标志位、rawSongId 为 0）也必须是 QQ 侧 —— 不许因为它「不像真实 id」放行。 */
    @Test
    fun `裸标志位 2 的 62 次方也判归 QQ 侧`() {
        val bare = 1L shl 62
        assertTrue(SourceIds.isQqId(bare))
        assertFalse(ReportGate.mayReport(ReportGate.Target.NETEASE_WEBLOG, bare))
    }

    /** 边界扫描：`2^62 - 1`（ncm 永远到不了的最大正数）必须仍属 ncm 侧。 */
    @Test
    fun `2 的 62 次方减一 仍属ncm侧`() {
        val below = (1L shl 62) - 1L
        assertFalse(SourceIds.isQqId(below))
        assertTrue(ReportGate.mayReport(ReportGate.Target.NETEASE_WEBLOG, below))
    }

    // ------------------------------------------------------------ 计数器

    @Test
    fun `计数器按目标与 id 形态归类`() {
        val c = ReportGateCounter(clock = { 1_000L })
        c.onBlocked(ReportGate.Target.NETEASE_WEBLOG, realQqId)   // QQ→ncm
        c.onBlocked(ReportGate.Target.NETEASE_WEBLOG, realQqId)
        c.onBlocked(ReportGate.Target.QQ, neteaseId)              // ncm→QQ
        c.onBlocked(ReportGate.Target.NETEASE_WEBLOG, -1L)        // 脏 id
        c.onReported()

        val s = c.snapshot().canonical()
        assertEquals(4L, s.blockedTotal)
        assertEquals(2L, s.blockedQqToNetease)
        assertEquals(1L, s.blockedNeteaseToQq)
        assertEquals(1L, s.blockedInvalidId)
        assertEquals(1L, s.reportedTotal)
        assertEquals(1_000L, s.firstSeenAtMs)
        assertEquals(ReportGateCounters.SCHEMA_VERSION, s.schemaVersion)
    }

    /** 拦截率的分母是「拦截 + 放行」；没有任何样本时返回 0.0 而不是 NaN。 */
    @Test
    fun `拦截率分母是拦截加放行`() {
        val c = ReportGateCounter(clock = { 1L })
        assertEquals(0.0, c.snapshot().blockRate(), 0.0)
        c.onBlocked(ReportGate.Target.NETEASE_WEBLOG, realQqId)
        c.onReported()
        assertEquals(0.5, c.snapshot().blockRate(), 1e-9)
    }

    /** verdict 在「判据反了」（全拦、零放行）时必须点出来，而不是报一个好看的数字。 */
    @Test
    fun `verdict 在全拦零放行时提示判据可能反了`() {
        val c = ReportGateCounter(clock = { 1L })
        c.onBlocked(ReportGate.Target.NETEASE_WEBLOG, realQqId)
        assertTrue(c.snapshot().verdict().contains("判据可能反了"))
    }

    // ------------------------------------------------------------ 落盘迁移

    /**
     * 老 JSON 缺字段 ⇒ `null` ⇒ [ReportGateCounters.canonical] 归零。
     * 与 `QqFallbackCountersTest` 同一条纪律（「缺失」与「显式 0」语义相同）。
     */
    @Test
    fun `落盘快照的 canonical 把 null 归零`() {
        val partial = ReportGateCounters(blockedTotal = 7L, blockedQqToNetease = 7L)
        val c = partial.canonical()
        assertEquals(7L, c.blockedTotal)
        assertEquals(7L, c.blockedQqToNetease)
        assertEquals(0L, c.blockedNeteaseToQq)
        assertEquals(0L, c.blockedInvalidId)
        assertEquals(0L, c.reportedTotal)
        assertEquals(ReportGateCounters.SCHEMA_VERSION, c.schemaVersion)
    }

    /** seed 是**累加**而不是赋值：`PlayReporter` 是 fire-and-forget，不保证 seed 先跑。 */
    @Test
    fun `seed 累加而不是覆盖`() {
        val c = ReportGateCounter(clock = { 1L })
        c.onBlocked(ReportGate.Target.NETEASE_WEBLOG, realQqId)
        c.seed(ReportGateCounters(blockedTotal = 5L, blockedQqToNetease = 5L, reportedTotal = 3L))
        val s = c.snapshot().canonical()
        assertEquals(6L, s.blockedTotal)
        assertEquals(6L, s.blockedQqToNetease)
        assertEquals(3L, s.reportedTotal)
    }

    /** 坏 JSON 不抛：返回 null，调用方按「还没有样本」处理。 */
    @Test
    fun `坏 JSON 解码返回 null 而不是抛异常`() {
        assertNull(ReportGateStore.decode(null))
        assertNull(ReportGateStore.decode(""))
        assertNull(ReportGateStore.decode("{not json"))
    }

    /**
     * ★ **落盘 key 必须是字段本名**，不是 R8 混淆出来的单字母。
     *
     * 这是 v2.5.4 规则 1 的守卫形状：字段改名让用例变红，而不是让统计静默归零。
     * 断言的是**确切的 key 名**（不是「包含」）。
     */
    @Test
    fun `落盘 JSON 的 key 是稳定字段名而不是单字母`() {
        val json = ReportGateStore.encode(
            ReportGateCounters(
                blockedTotal = 1L,
                blockedQqToNetease = 2L,
                blockedNeteaseToQq = 3L,
                blockedInvalidId = 4L,
                reportedTotal = 5L,
                firstSeenAtMs = 6L,
                lastUpdatedAtMs = 7L,
                schemaVersion = 1,
            ),
        )
        for (key in listOf(
            "blockedTotal", "blockedQqToNetease", "blockedNeteaseToQq",
            "blockedInvalidId", "reportedTotal", "firstSeenAtMs", "lastUpdatedAtMs", "schemaVersion",
        )) {
            assertTrue("落盘 JSON 缺稳定 key '$key'：$json", json.contains("\"$key\""))
        }
        // 单字母 key 一个都不许出现（`"a":` 这种形状就是被 R8 混淆过的）。
        assertFalse(
            "落盘 JSON 里出现了单字母 key —— 字段名又被交给 R8 了：$json",
            Regex("(?<=[{,])\\s*\"[a-z]\"\\s*:").containsMatchIn(json),
        )
    }

    /** 往返：编码 → 解码 → 数值不变（同一份 schema）。 */
    @Test
    fun `编码解码往返一致`() {
        val original = ReportGateCounters(
            blockedTotal = 11L,
            blockedQqToNetease = 9L,
            blockedNeteaseToQq = 1L,
            blockedInvalidId = 1L,
            reportedTotal = 42L,
            firstSeenAtMs = 100L,
            lastUpdatedAtMs = 200L,
            schemaVersion = ReportGateCounters.SCHEMA_VERSION,
        )
        val round = ReportGateStore.decode(ReportGateStore.encode(original))
        assertNotNull(round)
        assertEquals(original.canonical(), round!!.canonical())
    }
}
