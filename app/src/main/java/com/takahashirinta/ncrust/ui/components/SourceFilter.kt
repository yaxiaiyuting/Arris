/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · B：聚合搜索的**音源筛选**。纯逻辑，JVM 可单测。
 */

package com.takahashirinta.ncrust.ui.components

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.ui.i18n.Strings

/**
 * 「只看某个音源」的筛选档（v3.1.0 · B）。
 *
 * ## 为什么现在才需要它
 *
 * v2.1.0 起聚合搜索就有两个源，而**筛选一直没做** —— 那两个源的结果行只差一个角标，
 * 而角标是 v2.1.0 · E 才补上的（用户当时的原话：「现在有了稻香，似乎是 ncm 的搜索结果？」）。
 * 到 v3.1.0 有了第三个源，用户要「只看 B 站」的诉求就变成一个**必须**有的开关：
 * B 站的结果按设计**追加在最后**（见 `SearchRanking.order` 的三源重载），
 * 不加筛选的话它永远在列表尾部，等于看不见。
 *
 * ## 它是**纯本地筛选**，不重新发请求
 *
 * 切换筛选只改「渲染哪一部分已到达的结果」。理由有两条：
 * 1. 重新请求会把另外两个源已经拿到的结果清掉（v2.1.0 · hotfix 3 花了一版才修好的形状）；
 * 2. 用户切筛选时**不该**产生新的网络流量 —— 那三个请求刚刚才发过。
 *
 * 代价是「筛选后为空」有诚实提示的责任：`emptyHint` 与搜索无结果的提示分开写，
 * 因为「ncm 没有这首歌」与「这次搜索里 B 站一条都没有」是两句不同的话。
 */
enum class SourceFilter {
    /** 不过滤（= v3.0.0 的行为）。 */
    ALL,

    NETEASE,

    QQMUSIC,

    /**
     * 只看 B 站。
     *
     * ⚠️ B 站音源**关闭**时这一档不出现在 UI 上（见 [visibleIn]）——
     * 给一个关掉的源留一个永远空的筛选档，是在给用户制造一次无意义的点击。
     */
    BILIBILI,
    ;

    /** 这一档是否接受某个音源的行。 */
    fun accepts(source: MusicSource): Boolean = when (this) {
        ALL -> true
        NETEASE -> source == MusicSource.NETEASE
        QQMUSIC -> source == MusicSource.QQMUSIC
        BILIBILI -> source == MusicSource.BILIBILI
    }

    /** 按音源过滤一张列表。**保序**（筛选不重排 —— 顺序是 `SearchRanking` 的职责）。 */
    fun <T> filter(items: List<T>, sourceOf: (T) -> MusicSource): List<T> =
        if (this == ALL) items else items.filter { accepts(sourceOf(it)) }

    /** 这一档在 UI 上要不要出现。B 站档只在音源启用时出现。 */
    fun visibleIn(biliEnabled: Boolean): Boolean = this != BILIBILI || biliEnabled

    /** 档位文案。**唯一的文案出口**（UI 不自己写 `when`）。 */
    fun label(strings: Strings): String = when (this) {
        ALL -> strings.source.aggFilterBoth
        NETEASE -> strings.source.aggFilterNetease
        QQMUSIC -> strings.source.aggFilterQq
        BILIBILI -> strings.source.aggFilterBili
    }

    companion object {
        /** 当前该显示哪几档（顺序固定 = 声明顺序）。 */
        fun visible(biliEnabled: Boolean): List<SourceFilter> =
            values().filter { it.visibleIn(biliEnabled) }

        // ------------------------------------------------------------------ v3.2.0 · P0-D ----

        /**
         * 筛选档**该不该挂载**（v3.2.0 · P0-D）。**纯函数**，有单测。
         *
         * ## 这条判据修的是什么（P0-D 的根因）
         *
         * 旧代码把筛选档画在结果 `LazyColumn` 的**第一个 item** 里
         * （`SearchScreen.kt` 的 `if (songs.isNotEmpty()) item(key = "source-filter")`），
         * 而整条 `LazyColumn` 又被 `if (visibleSongs.isEmpty() && !isLoading)` 挡着。
         * 于是用户点「只看 B 站」之后，只要这一轮没有 B 站的行：
         * `visibleSongs` 空 ⇒ 整条列表（连筛选档一起）被换成空态 Box
         * ⇒ **筛选档自己消失，没有任何路径点回「双源」** ⇒ 用户描述为「卡死 / 无法退出」。
         *
         * 判据的错在**因果颠倒**：用「筛选后的结果」决定「筛选档自己的可见性」，
         * 等于让筛选档在它最该出现的那一刻消失。
         *
         * ## 正确的判据
         *
         * 只有一条：**这一轮有没有可筛的东西**（`totalSongs > 0`）**且**可用档位多于一个。
         *
         * - 刻意**不看** `visibleSongs`：筛选后为空 ⇒ 筛选档必须还在（这一条就是本 P0）；
         * - 刻意**不看** `isLoading`：加载中切筛选是合法的（纯本地操作，不发请求，
         *   见 [SourceFilter] 的类文档），所以加载中也要能看见它；
         * - `totalSongs == 0` 时不画：一排点下去只会得到另一个空列表的档位是在误导用户，
         *   此时由空态文案负责说明（见 [emptyKind] 与 [emptyText]）。
         *
         * @param totalSongs 本轮搜索的**总**条数（筛选前）。
         * @param biliEnabled B 站音源开关（关掉时那一档不出现，见 [visibleIn]）。
         */
        fun shouldShowFilterRow(totalSongs: Int, biliEnabled: Boolean): Boolean =
            totalSongs > 0 && visible(biliEnabled).size > 1

        /**
         * 空态属于哪一类（v3.2.0 · P0-D）。**纯函数**，`null` = 不空。
         *
         * 「本次搜索没有结果」与「当前筛选下没有结果」是两句不同的话 ——
         * 旧代码两处都渲染 `strings.searchSongsEmpty`，于是被筛选档「坑」了之后，
         * 用户看到的是一句与他的动作无关的提示（[SourceFilter] 的 KDoc 把 `emptyHint`
         * 写成过承诺，但那条字符串**从未存在过**；这里先把它变成一个可单测的判据，
         * 文案的 i18n key 见 v3.2.0 交付报告）。
         *
         * @param totalSongs 筛选前的总条数。
         * @param visibleSongs 筛选后的条数。
         */
        fun emptyKind(totalSongs: Int, visibleSongs: Int): SearchEmptyKind? = when {
            visibleSongs > 0 -> null
            totalSongs == 0 -> SearchEmptyKind.NO_RESULT
            else -> SearchEmptyKind.FILTERED_OUT
        }
    }

    /**
     * 「筛选后为空」的诚实文案（v3.2.0 · P0-D）。
     *
     * v3.2.0 定稿：走 `SourceStrings.searchFilterEmpty`（8 语言逐条写好，
     * 由 `StringsConstructorBudgetTest` 的组预算与 `StringsMigrationTest` 的组规模守着）。
     *
     * ⚠️ 这里**曾经**是「`只看 B 站` · `0 首`」两个既有字符串的拼接 —— 那是 i18n 归口期间的
     * 临时占位（没有裸中文字面量，所以 8 语言不漏翻）。定稿换掉它的理由是：拼接出来的
     * 「0 首」读起来像**统计行**，而这一句是**空态说明**，两者在同一屏上会让人以为筛选没生效。
     */
    fun emptyText(strings: Strings): String = strings.searchFilterEmpty(label(strings))
}

/**
 * 空态的种类（v3.2.0 · P0-D）。判据在 [SourceFilter.emptyKind]，UI 只负责按它画。
 */
enum class SearchEmptyKind {
    /** 这一轮真的什么都没搜到 —— 文案是「搜索歌曲」那一类。 */
    NO_RESULT,

    /** 搜到了东西，但当前筛选档下没有 —— 文案必须**指名当前档位**，否则用户不知道发生了什么。 */
    FILTERED_OUT,
}
