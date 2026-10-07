/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
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

    /**
     * v3.4.5 · P0：B 站合成 id 的形状 —— `(1L shl 61) or auid`。
     *
     * auid 取的是 B 站音频区真实存在的量级（`BiliModels.numericId` 用它拼 id）；
     * 关键性质只有一条：**bit61 置位、bit62 清零**。
     */
    private val realBiliId = SourceIds.biliId(22760301L)

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

    /** 双向：**每个 id 至多被一个目标接受**；带标志位但无上报目标的音源两边都拒。 */
    @Test
    fun `每个合法 id 至多被一个目标接受`() {
        for (id in listOf(neteaseId, 1L, 999_999_999L, realQqId, realBiliId)) {
            val toNetease = ReportGate.mayReport(ReportGate.Target.NETEASE_WEBLOG, id)
            val toQq = ReportGate.mayReport(ReportGate.Target.QQ, id)
            assertTrue("id=$id 被两个目标同时接受", !(toNetease && toQq))
        }
    }

    // ------------------------------------------------------------ v3.4.5：B 站漏网

    /**
     * ★ v3.4.5 · P0 的回归守卫：B 站合成 id **不许**上报给 ncm。
     *
     * 旧判据是负向的 `!isQq`，而 B 站 id 的 bit62 是**清零**的 ⇒ 被放行。
     * 这条用例在旧实现下会红（这正是它存在的意义）。
     */
    @Test
    fun `B站合成 id 不许上报给ncm`() {
        assertTrue("B 站合成 id 必须是正数 ⇒ 旧的 songId 大于 0 卫语句拦不住", realBiliId > 0L)
        assertFalse("B 站 id 的 bit62 必须是清零的，否则本用例测不到东西", SourceIds.isQqId(realBiliId))
        assertTrue("B 站 id 必须被认成 B 站", SourceIds.isBiliId(realBiliId))
        assertFalse(
            "B 站合成 id 上报给了 ncm webLog —— 与 v2.5.5 修掉的 QQ 那次同形（跨源数据泄露）",
            ReportGate.mayReport(ReportGate.Target.NETEASE_WEBLOG, realBiliId),
        )
        assertNotNull(ReportGate.blockReason(ReportGate.Target.NETEASE_WEBLOG, realBiliId))
    }

    /** B 站**没有**上报目标，所以两个方向都必须拒绝（旧契约的「恰好一个」在这里会失败）。 */
    @Test
    fun `B站 id 两个目标都拒绝`() {
        assertFalse(ReportGate.mayReport(ReportGate.Target.NETEASE_WEBLOG, realBiliId))
        assertFalse(ReportGate.mayReport(ReportGate.Target.QQ, realBiliId))
    }

    /** 正向白名单的边界：`isNeteaseId` 只认 ncm 自己，且 `id <= 0` 一律不是。 */
    @Test
    fun `isNeteaseId 只接受ncm自己的 id`() {
        assertTrue(SourceIds.isNeteaseId(neteaseId))
        assertFalse(SourceIds.isNeteaseId(realQqId))
        assertFalse(SourceIds.isNeteaseId(realBiliId))
        for (bad in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertFalse("id=$bad 不是任何真实曲目", SourceIds.isNeteaseId(bad))
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

    /**
     * 边界扫描：`2^61 - 1` 是 **ncm id 空间的上界**（bit61 以下），必须仍被接受。
     *
     * ⚠️ v3.4.5 修正了这条用例：它原来断言的是 `2^62 - 1` 属 ncm 侧 —— 那个结论
     * 在只有 bit62 一个标志位时成立，但 **bit61 是 v3.1.0 加的 B 站标志位**，
     * 而 `2^62 - 1` 的 bit61 是**置位**的（它是 `0x3FFF…F`，低 62 位全 1）。
     * 所以那条老断言按今天的结构判据是**错的**：`2^62 - 1` 属 B 站空间。
     * 真实 ncm songId 是 10^9 量级，离 2^61 还有九个数量级，这个上界没有任何实际风险。
     */
    @Test
    fun `2 的 61 次方减一 仍属ncm侧`() {
        val top = (1L shl 61) - 1L
        assertFalse(SourceIds.isQqId(top))
        assertFalse(SourceIds.isBiliId(top))
        assertTrue(SourceIds.isNeteaseId(top))
        assertTrue(ReportGate.mayReport(ReportGate.Target.NETEASE_WEBLOG, top))
    }

    /** `2^61` 本身是裸的 B 站标志位 —— 不许因为它「不像真实 id」就放行给 ncm。 */
    @Test
    fun `裸标志位 2 的 61 次方判归 B站侧`() {
        val bare = 1L shl 61
        assertTrue(SourceIds.isBiliId(bare))
        assertFalse(ReportGate.mayReport(ReportGate.Target.NETEASE_WEBLOG, bare))
    }

    // ------------------------------------------------------------ 计数器

    @Test
    fun `计数器按目标与 id 形态归类`() {
        val c = ReportGateCounter(clock = { 1_000L })
        c.onBlocked(ReportGate.Target.NETEASE_WEBLOG, realQqId)   // QQ→ncm
        c.onBlocked(ReportGate.Target.NETEASE_WEBLOG, realQqId)
        c.onBlocked(ReportGate.Target.NETEASE_WEBLOG, realBiliId) // B站→ncm（v3.4.5 新分栏）
        c.onBlocked(ReportGate.Target.QQ, neteaseId)              // ncm→QQ
        c.onBlocked(ReportGate.Target.NETEASE_WEBLOG, -1L)        // 脏 id
        c.onReported()

        val s = c.snapshot().canonical()
        assertEquals(5L, s.blockedTotal)
        assertEquals(2L, s.blockedQqToNetease)
        assertEquals("B 站的拦截必须单独分栏，不许并进 QQ 那一栏", 1L, s.blockedBiliToNetease)
        assertEquals(1L, s.blockedNeteaseToQq)
        assertEquals(1L, s.blockedInvalidId)
        assertEquals(1L, s.reportedTotal)
        assertEquals(1_000L, s.firstSeenAtMs)
        assertEquals(ReportGateCounters.SCHEMA_VERSION, s.schemaVersion)
    }

    /** 老快照（无 `blockedBiliToNetease` 这个 key）读出来必须是 0，而不是崩。 */
    @Test
    fun `老快照缺 B站分栏时归零`() {
        val old = ReportGateCounters(blockedTotal = 7L, blockedQqToNetease = 7L)
        val c = old.canonical()
        assertEquals(0L, c.blockedBiliToNetease)
        assertEquals(2, c.schemaVersion)
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
