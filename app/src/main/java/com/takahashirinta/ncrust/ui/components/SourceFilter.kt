/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
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
 * 而角标是 v2.1.0 · E 才补上的（用户当时的原话：「现在有了稻香，似乎是网易云的搜索结果？」）。
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
 * 因为「网易云没有这首歌」与「这次搜索里 B 站一条都没有」是两句不同的话。
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
    }
}
