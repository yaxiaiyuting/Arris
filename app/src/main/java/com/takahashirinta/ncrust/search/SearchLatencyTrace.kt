/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.6 · P1：搜索链路的分段耗时（纯逻辑，可单测）。
 */

package com.takahashirinta.ncrust.search

/**
 * 一次聚合搜索的**分段耗时**。
 *
 * ## 为什么需要它（探针结论，不是设计洁癖）
 *
 * v2.5.6 的搜索延迟探针（`docs/verification/v2.5.6/probe-search-latency-gap.md` §5）
 * 全仓检索 `EventListener` / TTFB → **0 命中**；搜索链路上唯一的时间戳是
 * `SearchViewModel` 里的 `startedAt` 与结尾那一条 `elapsed=…ms`。
 * 后果是那条日志**只能回答「一共多久」，无法回答「时间花在哪」**——
 * 而「TTFB 1722ms、UI 2.2s」这个 gap 到底在网络、在解析还是在渲染，
 * 正是本版要回答的问题。
 *
 * 本类把「打点」做成**纯逻辑**（时钟注入），于是：
 * ① 单测能用假时钟精确验证分段算术，不需要设备；
 * ② 生产代码里只留 `mark()` 调用，没有格式化/算术散落在业务逻辑中。
 *
 * ## 关键指标
 *
 * [timeToFirstResultMs]（`dispatch → first_publish`）= **用户第一次看到任何结果**的时刻。
 * 这才是「0 首在屏幕」那句话真正对应的量。它**不等于**任何单条腿的耗时：
 * 旧实现是「网易云腿跑完才发布」，所以它恒等于网易云腿；
 * v2.5.6 改成**先到先发布**之后，它等于 `min(网易云腿, QQ 腿)`（在有 QQ 的前提下）。
 *
 * ## 打点语义（重复打点保留**第一次**）
 *
 * 协程并发下同一名字可能被打两次（例如 QQ 先到、合并发布时又走到同一个分支）。
 * 保留第一次是有意的：本类回答的是「**什么时候第一次**能上屏」，
 * 被后到的、更慢的时刻覆盖会把结论变好看 —— 那正是要避免的自欺。
 */
internal class SearchLatencyTrace(
    private val query: String,
    /** 单调时钟。生产传 `SystemClock.elapsedRealtime`，单测传假时钟。 */
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {

    /** 打点表。`LinkedHashMap` 让 [summary] 的输出顺序 = 打点顺序（可读性）。 */
    private val marks = LinkedHashMap<String, Long>()

    /** 记录一个时刻。同名重复打点**保留第一次**（见类 KDoc）。 */
    fun mark(name: String) {
        if (!marks.containsKey(name)) marks[name] = clock()
    }

    /** [from] → [to] 的毫秒差；任一端没打点则返回 `null`（**不返回 0**，0 是合法耗时）。 */
    fun between(from: String, to: String): Long? {
        val a = marks[from] ?: return null
        val b = marks[to] ?: return null
        // 单调时钟保证非负；真出现负数说明时钟被换错，夹到 0 而不是把负数写进报告。
        return (b - a).coerceAtLeast(0L)
    }

    /** 用户第一次看到结果的时间。缺失（例如全空且没发布）时返回 `null`。 */
    fun timeToFirstResultMs(): Long? = between(MARK_DISPATCH, MARK_FIRST_PUBLISH)

    fun has(name: String): Boolean = marks.containsKey(name)

    /**
     * 渲染成一行可 grep 的日志。
     *
     * 格式刻意是 `key=value` 的平铺（不是 JSON）：证据收集用
     * `adb logcat -d | grep NcrustSearchLatency` 就够，且不受 release 包
     * 混淆/裁剪影响 —— 这一行**必须**在 release 里存在，否则性能结论只能来自
     * debug 包（铁律 16 禁止）。
     */
    fun summary(): String {
        val segments = listOf(
            MARK_DISPATCH to MARK_NETEASE_DONE,
            MARK_DISPATCH to MARK_QQ_DONE,
            // v3.1.0 · B：B 站那一侧的分段耗时。加进这张表才有得看 ——
            // 不加的话 `summary()` 里根本没有这一格，等于新源没有埋点。
            MARK_DISPATCH to MARK_BILI_DONE,
            MARK_DISPATCH to MARK_FIRST_PUBLISH,
            MARK_FIRST_PUBLISH to MARK_MERGED_PUBLISH,
            MARK_DISPATCH to MARK_DONE,
        ).mapNotNull { (a, b) -> between(a, b)?.let { "$a->$b=${it}ms" } }

        return buildString {
            append("query='").append(query).append("'")
            append(" ttfr=").append(timeToFirstResultMs() ?: -1).append("ms")
            append(" marks=").append(marks.keys.joinToString(","))
            append(" segments[").append(segments.joinToString(" ")).append(']')
        }
    }

    companion object {
        const val TAG = "NcrustSearchLatency"

        /** 请求真正发出去的那一刻（500ms 防抖之后）。 */
        const val MARK_DISPATCH = "dispatch"

        /** 网易云腿回来（成功或异常都算「回来了」）。 */
        const val MARK_NETEASE_DONE = "netease_done"

        /** QQ 腿回来（含超时/失败）。 */
        const val MARK_QQ_DONE = "qq_done"

        /** **第一次**把结果写进 `_songs` —— 用户看到第一屏结果的时刻。 */
        /**
         * v3.1.0 · B：B 站那一侧返回的时刻。
         *
         * 它是三个源里唯一需要**两通**才能出结果的（先 nav 取 wbi 密钥、再签名搜索），
         * 所以这一格与 `netease_done` / `qq_done` 的差值是「wbi 那两跳花了多少」的
         * 唯一观测点。风控命中时还会多两通（412 → 刷新密钥 → 重试），
         * 那时这一格的数字会明显偏大 —— 那是**有效信号**，不是噪声。
         */
        const val MARK_BILI_DONE = "bili_done"

        const val MARK_FIRST_PUBLISH = "first_publish"

        /** 两条腿都到齐后的合并发布。 */
        const val MARK_MERGED_PUBLISH = "merged_publish"

        /** 本轮聚合搜索结束。 */
        const val MARK_DONE = "done"
    }
}
