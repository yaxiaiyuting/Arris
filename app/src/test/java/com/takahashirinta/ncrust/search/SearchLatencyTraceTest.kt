/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.6 · P1：搜索分段耗时（[SearchLatencyTrace]）的纯逻辑单测。
 *
 * 用**假时钟**（递增的 Long），所以断言是精确值而不是范围 ——
 * 这正是把打点抽成纯逻辑的理由：真机上只能拿到「大概几十毫秒」，
 * 而算错一段（比如把 `first_publish` 记成 `merged_publish`）在真机上
 * 只会表现为「数字看着也合理」。
 */
class SearchLatencyTraceTest {

    /** 假时钟：每次读都在上一次的基础上加 [stepMs]。 */
    private class FakeClock(private val stepMs: Long = 10L) {
        private var now = 0L
        fun read(): Long = now.also { now += stepMs }
    }

    @Test
    fun `分段耗时按打点顺序精确计算`() {
        val clock = FakeClock(stepMs = 10L)
        val trace = SearchLatencyTrace("屋顶", clock::read)

        trace.mark(SearchLatencyTrace.MARK_DISPATCH)        // 0
        trace.mark(SearchLatencyTrace.MARK_QQ_DONE)         // 10
        trace.mark(SearchLatencyTrace.MARK_FIRST_PUBLISH)   // 20
        trace.mark(SearchLatencyTrace.MARK_NETEASE_DONE)    // 30
        trace.mark(SearchLatencyTrace.MARK_MERGED_PUBLISH)  // 40
        trace.mark(SearchLatencyTrace.MARK_DONE)            // 50

        assertEquals(20L, trace.timeToFirstResultMs())
        assertEquals(10L, trace.between(SearchLatencyTrace.MARK_DISPATCH, SearchLatencyTrace.MARK_QQ_DONE))
        assertEquals(20L, trace.between(SearchLatencyTrace.MARK_FIRST_PUBLISH, SearchLatencyTrace.MARK_MERGED_PUBLISH))
        assertEquals(50L, trace.between(SearchLatencyTrace.MARK_DISPATCH, SearchLatencyTrace.MARK_DONE))
    }

    /**
     * **本版最重要的一条**：TTFR（time-to-first-result）必须取**第一次**发布，
     * 不能被后到的合并发布覆盖。
     *
     * 取第二次会让「先到先发布」这个优化在数据上**消失** ——
     * 报告的 TTFR 会恒等于较慢的那条腿，于是「优化有没有用」永远看不出来。
     * 这是本类唯一一处「宁可少算也不多算」的地方，值得一条专门的断言。
     */
    @Test
    fun `重复打点保留第一次——TTFR 不会被合并发布覆盖`() {
        val clock = FakeClock(stepMs = 100L)
        val trace = SearchLatencyTrace("q", clock::read)

        trace.mark(SearchLatencyTrace.MARK_DISPATCH)        // 0
        trace.mark(SearchLatencyTrace.MARK_FIRST_PUBLISH)   // 100
        // 合并发布时同名的 mark 被再打一次（真实代码里两支分支都可能走到）：
        trace.mark(SearchLatencyTrace.MARK_FIRST_PUBLISH)   // 200 —— 必须被忽略
        trace.mark(SearchLatencyTrace.MARK_DONE)            // 300

        assertEquals(100L, trace.timeToFirstResultMs())
    }

    @Test
    fun `没打点的分段返回 null 而不是 0`() {
        val trace = SearchLatencyTrace("q") { 0L }
        trace.mark(SearchLatencyTrace.MARK_DISPATCH)
        // `0` 是一个合法的耗时（同一毫秒内完成），所以「没测到」必须用 null 表达 ——
        // 否则报告里会把「缺数据」写成「零耗时」，那是在把未知说成已知。
        assertNull(trace.between(SearchLatencyTrace.MARK_DISPATCH, SearchLatencyTrace.MARK_DONE))
        assertNull(trace.timeToFirstResultMs())
        assertFalse(trace.has(SearchLatencyTrace.MARK_DONE))
    }

    @Test
    fun `时钟回拨不会算出负数`() {
        var now = 1_000L
        val trace = SearchLatencyTrace("q") { now.also { now -= 500L } }
        trace.mark(SearchLatencyTrace.MARK_DISPATCH)
        trace.mark(SearchLatencyTrace.MARK_DONE)
        assertEquals(0L, trace.between(SearchLatencyTrace.MARK_DISPATCH, SearchLatencyTrace.MARK_DONE))
    }

    @Test
    fun `summary 是 key=value 平铺且带 query 与 ttfr`() {
        val clock = FakeClock(stepMs = 7L)
        val trace = SearchLatencyTrace("love", clock::read)
        trace.mark(SearchLatencyTrace.MARK_DISPATCH)
        trace.mark(SearchLatencyTrace.MARK_FIRST_PUBLISH)

        val line = trace.summary()
        // 证据收集用 `grep NcrustSearchLatency` + 人眼读，所以这几段的形状要钉住：
        assertTrue(line, line.startsWith("query='love'"))
        assertTrue(line, line.contains("ttfr=7ms"))
        assertTrue(line, line.contains("dispatch->first_publish=7ms"))
    }

    @Test
    fun `没有任何打点时 summary 不崩且 ttfr 记为 -1`() {
        val trace = SearchLatencyTrace("q") { 0L }
        val line = trace.summary()
        assertTrue(line, line.contains("ttfr=-1ms"))
        assertTrue(line, line.contains("segments[]"))
    }
}
