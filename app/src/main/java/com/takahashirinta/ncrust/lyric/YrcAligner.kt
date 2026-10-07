/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

/**
 * yrc 行 ↔ LRC 行的对齐（v1.6.0 · D4）。**纯逻辑**：无 Android / Compose 依赖，JVM 可单测。
 *
 * ## 为什么需要它（实测数据）
 *
 * v1.5.x 的 [YrcParser.attachWords] 要求「yrc 行数 == lrc 行数」，否则整首放弃逐字。
 * 2026-09 用用户真实收藏单曲库 100 首实测（探针 `tools/probe-yrc-newalign.py`）：
 *
 * | 指标 | 行数必须相等（v1.5.x） | 本文件（LCS 对齐） |
 * |---|---|---|
 * | 有 yrc 的歌 | 43/100 | 43/100 |
 * | 逐字真正挂上去的歌（≥50% 行） | 37/100 | **42/100** |
 * | 行级挂载率 | 1538/1974 = 77.9% | **1776/1974 = 90.0%** |
 *
 * 差距的来源是**两份资产的行集合并不总是相等**：yrc 常常少一行（纯音乐/间奏没有逐字轨）、
 * 多几行（重复段），或者 LRC 多一行「作词 : xxx」元信息。这些歌在 v1.5.x 里一行逐字都拿不到，
 * 而按**文本内容**做最长公共子序列（LCS）匹配后，绝大多数行都能重新配上。
 *
 * ## 三条约束
 *
 * 1. **保序**：LCS 天然给出单调递增的下标对，不会出现「第 3 行配到第 9 行、第 4 行配到第 2 行」；
 * 2. **只看文本，不看时间戳**：两份数据的时间戳本来就差 20–530 ms（[YrcParser] 的 KDoc 有实测），
 *    时间戳只用来做**事后**漂移过滤，不做匹配依据；
 * 3. **绝不猜**：LCS 只匹配「归一化后完全相等」的行（归一化 = 去掉所有空白字符，因为 yrc 的
 *    行文本是词的原始拼接、LRC 那份补回了空格）。匹配不上的行就是没有逐字，不做过桥式推断。
 */
internal object YrcAligner {

    /** 一对下标：[lrcIndex] 行用 [yrcIndex] 行的逐词时间轴。 */
    data class LinePair(val lrcIndex: Int, val yrcIndex: Int)

    /**
     * 归一化：删掉所有空白字符（含全角空格）。
     *
     * 只做这一件事是**故意**的 —— 实测两份文本的差异 99% 就是空格（`听见冬天的离开` vs
     * `听见 冬天的离开`）。如果再去标点、去大小写，就会出现「本来不同源的两行被判成同一行」
     * 的风险，那正是「错位高亮」的来源，宁可少挂几行。
     */
    fun normalize(text: String): String {
        var sb: StringBuilder? = null
        for (i in text.indices) {
            if (text[i].isWhitespace()) {
                if (sb == null) {
                    sb = StringBuilder(text.length)
                    sb.append(text, 0, i)
                }
            } else {
                sb?.append(text[i])
            }
        }
        return sb?.toString() ?: text
    }

    /** 行数相等时的「行序对齐」：i 配 i。v1.5.x 的既有行为，保留为候选之一。 */
    fun indexPairs(size: Int): List<LinePair> = List(size) { LinePair(it, it) }

    /**
     * 最长公共子序列匹配（保序）。返回的下标对个数 = LCS 长度。
     *
     * 复杂度 O(n·m)；n、m 是行数（实测最长 100 行上下），一次对齐 ≤ 10⁴ 次整数比较，
     * 在 IO 线程上可以忽略。
     */
    fun lcsPairs(lrcTexts: List<String>, yrcTexts: List<String>): List<LinePair> {
        val n = lrcTexts.size
        val m = yrcTexts.size
        if (n == 0 || m == 0) return emptyList()
        val a = lrcTexts.map { normalize(it) }
        val b = yrcTexts.map { normalize(it) }
        // dp[i][j] = a[i..] 与 b[j..] 的 LCS 长度
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = ArrayList<LinePair>(minOf(n, m))
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += LinePair(i, j); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }

    /**
     * 漂移过滤：只保留「lrc 行时刻」与「yrc 行起始」相差不超过 [maxDriftMs] 的配对。
     *
     * 文本匹配已经很强了，这一步是**第二道闸**：万一服务端把两份不同源的资产（比如 remix 版
     * 的 LRC 配了原版的 yrc）凑在一首里，文本仍可能撞上，但时间戳会整体错开。
     */
    fun filterByDrift(
        pairs: List<LinePair>,
        lrcTimes: List<Long>,
        yrcStarts: List<Long>,
        maxDriftMs: Long
    ): List<LinePair> = pairs.filter { p ->
        val lt = lrcTimes.getOrNull(p.lrcIndex) ?: return@filter false
        val yt = yrcStarts.getOrNull(p.yrcIndex) ?: return@filter false
        kotlin.math.abs(yt - lt) <= maxDriftMs
    }
}
