/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.5 · G：聚合搜索的**单源状态与统计行**。纯逻辑，JVM 可单测。
 */

package com.takahashirinta.ncrust.ui.components

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.ui.i18n.Strings

/**
 * 单个音源这一轮检索的状态。
 *
 * ## 为什么需要它（用户报告的现象）
 *
 * 搜「晴天」→ 界面立刻显示「ncm 30 首 · **qm 0 首**」，
 * 而底部托盘正在播的那首《晴天》就是 qm 源；约 5 秒后 QQ 结果才出现、数字才更新。
 *
 * 那 5 秒里「0 首」是**假话**：它把「还没回来」显示成了「真的没有」。
 * 用户据此判断「qm 搜不到这首歌」，而实际上 QQ 只是慢。
 *
 * 根因在 `SearchViewModel` 的发布顺序（v2.1.0 · hotfix 3 的产物 —— 那个 hotfix 本身是对的：
 * 它让主源到手即发布、不再陪 QQ 一起转圈）。问题出在**发布时用 0 代表了「未知」**：
 * 0 与「确实一首都没有」在类型上无法区分。
 *
 * 修法不是改发布顺序（那个顺序是对的），而是给统计量一个能表达「未知」的类型：
 * [SourceSearchStatus.PENDING] 与「[SourceSearchStatus.DONE] + 0」是两件事。
 */
enum class SourceSearchStatus {
    /** 还没回来。**显示「搜索中…」，不显示 0。** */
    PENDING,

    /** 回来了。计数是权威的（0 就是真的 0）。 */
    DONE,

    /** **超时**（预算用完）。计数不可信，界面要给一条可操作的重试提示。 */
    TIMEOUT,

    /**
     * **失败**（网络错误 / 服务端报错）。
     *
     * 与 [TIMEOUT] 分开是探针给的（`probe-search-qq-latency.md` §3 遗留①）：
     * 把「连不上」显示成「超时」等于替用户编了一个原因 ——
     * 「服务端标签不可信」这条纪律在客户端对自己说的话上同样成立。
     * 两者的**处置相同**（都要用户点重试），所以界面上只差一个词。
     */
    ERROR,

    /** 这一轮**没有发起**这个源的请求（未登录且不允许匿名）。不算失败，也不显示计数。 */
    SKIPPED,
}

/**
 * 聚合搜索这一轮的**逐源统计**。
 *
 * @property neteaseCount ncm 返回条数（仅 [neteaseStatus] == [SourceSearchStatus.DONE] 时权威）。
 * @property qqCount QQ 返回条数（同上）。
 */
data class SourceCounts(
    val neteaseCount: Int = 0,
    val neteaseStatus: SourceSearchStatus = SourceSearchStatus.DONE,
    val qqCount: Int = 0,
    val qqStatus: SourceSearchStatus = SourceSearchStatus.PENDING,
    /**
     * v3.1.0 · B：B 站音频区的条数。
     *
     * 默认 [SourceSearchStatus.SKIPPED]（**不是** DONE+0）是有意的：B 站音源默认关闭，
     * 关着的时候这一轮**根本没有发起**任何 B 站请求 —— 那既不是「0 首」也不是「失败」。
     * 这条默认值同时是「关掉 B 站开关 = 与 v3.0.0 逐字相同」的结构性保证：
     * [summary] 在 SKIPPED 时会**短路**掉第三段，不产生任何新文案。
     */
    val biliCount: Int = 0,
    val biliStatus: SourceSearchStatus = SourceSearchStatus.SKIPPED,
    /**
     * v3.4.11：**服务端声明的总曲库数**（这一轮只取了它的一小段）。
     *
     * 用户建议原话：「最顶部哪个音源多少个歌曲也没有实时更新，建议用抓取的
     * 总曲库数字作为哪个音源多少首的度值」。他是对的 —— 此前这里显示的是
     * 「这一轮取回了多少条」，于是「ncm 30 首」读起来像「ncm 只有 30 首」，
     * 而且翻页后那个数字**不会变**（它本来就是「这一轮」的量），
     * 用户看到的就是「没有实时更新」。
     *
     * 实测两个音源都给总数：ncm 的 `result.songCount`（周杰伦 = **273**）、
     * QQ 的 `data.song.totalnum`（同为周杰伦 = **999**）。
     *
     * `null` = 这个音源/这一轮没给 ⇒ **回落到「已载 N 首」**（如实说「已载」，
     * 不把已载量冒充总数）。
     */
    val neteaseTotal: Int? = null,
    val qqTotal: Int? = null,
) {

    /** 还有源在飞。 */
    val hasPending: Boolean
        get() = neteaseStatus == SourceSearchStatus.PENDING ||
            qqStatus == SourceSearchStatus.PENDING ||
            biliStatus == SourceSearchStatus.PENDING

    /** QQ 这一轮超时或失败了 —— 界面给一条可点重试的提示，而不是一个哑掉的 0。 */
    val qqUnavailable: Boolean
        get() = qqStatus == SourceSearchStatus.TIMEOUT || qqStatus == SourceSearchStatus.ERROR

    /**
     * v3.2.0 · P0-D：B 站这一轮超时或失败了。
     *
     * ## 为什么必须补这一条
     *
     * v3.1.0 只给 QQ 做了「超时/失败 ⇒ 给一条可点的重试」（[qqUnavailable]），
     * 而 B 站那条腿当时**恒为 0 条**（P0-C：主线程阻塞 + 异常被吞），
     * 它的 `biliStatus` 是 `DONE + 0` 而不是 `TIMEOUT` —— 于是「加一条重试出口」
     * 这件事在 B 站上既没有被想到、也没有被触发过。修好 P0-C 之后，
     * B 站会**第一次真的**出现 TIMEOUT / ERROR，那时候没有出口就是新的死角。
     */
    val biliUnavailable: Boolean
        get() = biliStatus == SourceSearchStatus.TIMEOUT || biliStatus == SourceSearchStatus.ERROR

    /**
     * v3.2.0 · P0-D：**任何一个**源超时/失败 ⇒ 统计行给一条可点的重试。
     *
     * 判据取「或」而不是给 B 站再写一遍 UI 分支：用户对失败源的处置是同一个动作
     * （点一下重查），文案也已经是逐源分开的（[neteaseText] / [qqText] / [biliText]）。
     * 既有行为**逐字不变** —— `qqUnavailable ⇒ anyUnavailable` 恒成立。
     */
    val anyUnavailable: Boolean get() = qqUnavailable || biliUnavailable

    /**
     * 统计行的文案。
     *
     * **两种形态，一个函数**：
     * - 某一源未返回 ⇒ 那一侧显示「搜索中…」/「搜索超时」/「未登录」；
     * - 两源都已返回 ⇒ 两侧都是计数（`ncm 30 首 · qm 12 首`）。
     *
     * ## 为什么不再直接调 `Strings.sourceSummary`
     *
     * `sourceSummary: (Int, Int) -> String` 是本版之前唯一的入口，它**只能表达计数** ——
     * 这正是「用 0 代表未知」的类型根源。新的 [Strings.searchSourceSummaryWithStatus]
     * 收两个**已经成文的字符串**，因此「还没回来」有一个合法的表示。
     *
     * `sourceSummary` **保留不动**（v2.5.3 的纪律：证据不足时不动手），
     * 并有单测断言「两源都 DONE 时两条路径产出**逐字相同**的文案」——
     * 换路径不许顺手改口径。
     */
    fun summary(strings: Strings): String {
        val two = strings.searchSourceSummaryWithStatus(neteaseText(strings), qqText(strings))
        // v3.1.0 · B：**关掉 B 站时这一行逐字不变**。这不是顺手优化，而是铁律 27 的落点：
        // 未启用 B 站的用户看到的统计行必须与 v3.0.0 完全相同（连一次文案拼接都不多做）。
        if (biliStatus == SourceSearchStatus.SKIPPED) return two
        return strings.source.sourceSummaryBili(two, biliText(strings))
    }

    /** ncm 侧那半句。 */
    fun neteaseText(strings: Strings): String =
        sideText(neteaseStatus, neteaseCount, strings, neteaseTotal)

    /**
     * QQ 侧那半句。
     *
     * ★ **绝不在未返回时返回 "0"** —— 这一条是本版的核心修复，由 `SourceCountsTest` 钉住。
     */
    fun qqText(strings: Strings): String =
        sideText(qqStatus, qqCount, strings, qqTotal)

    /** v3.1.0 · B：B 站侧那半句。判据与 [qqText] 完全一致（同一个 [sideText]）。 */
    fun biliText(strings: Strings): String = sideText(biliStatus, biliCount, strings)

    /**
     * 一侧那半句。
     *
     * v3.4.11：`total` 非空时显示**服务端声明的总数**（`ncm 273 首`），
     * 为空时回落到「已载 N 首」—— 后者是对「这一轮取回多少」的如实描述，
     * 不再被冒充成总数（这正是用户报的那句「没有实时更新」）。
     */
    private fun sideText(
        status: SourceSearchStatus,
        count: Int,
        strings: Strings,
        total: Int? = null,
    ): String =
        if (status == SourceSearchStatus.DONE && total != null && total > 0) {
            strings.searchSourceTotal(total)
        } else {
            sideTextByStatus(status, count, strings)
        }

    private fun sideTextByStatus(status: SourceSearchStatus, count: Int, strings: Strings): String =
        when (status) {
            SourceSearchStatus.PENDING -> strings.searchSourcePending
            SourceSearchStatus.TIMEOUT -> strings.searchSourceTimeout
            SourceSearchStatus.ERROR -> strings.searchSourceError
            SourceSearchStatus.SKIPPED -> strings.searchSourceSkipped
            SourceSearchStatus.DONE -> strings.searchSourceCount(count)
        }

    /** 音源枚举 → 是否已返回（给「点重试」这类调用点用）。 */
    fun isDone(source: MusicSource): Boolean = when (source) {
        MusicSource.NETEASE -> neteaseStatus == SourceSearchStatus.DONE
        MusicSource.QQMUSIC -> qqStatus == SourceSearchStatus.DONE
        MusicSource.BILIBILI -> biliStatus == SourceSearchStatus.DONE
    }
}
