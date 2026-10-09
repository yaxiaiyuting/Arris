/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.11：搜索分页的**纯逻辑**（无 IO、无 Android 依赖、JVM 可单测）。
 */

package com.takahashirinta.ncrust.search

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.trackKey

/**
 * 搜索结果分页的纯逻辑（v3.4.11）。**无 IO、无 Android 依赖、JVM 可单测。**
 *
 * ## 为什么值得单独抽出来
 *
 * 用户报障原话：「每次拉歌曲只拉三十首也太少了吧，而且下拉还不刷新新的歌」。
 * 根因是**搜索结果只有一页**：`cloudsearch` 的 `offset` 恒为 0，界面上也没有
 * 「还有更多」的出口。修法涉及三件事，而这三件事都容易写错、且写错了不会崩：
 *
 * | 关注点 | 写错的表现 |
 * |---|---|
 * | `offset` 的算法（页码 ↔ 偏移） | 翻到第二页拿到第一页的内容，看起来像「加载更多没反应」 |
 * | 跨页去重 | 同一条歌出现两次（服务端分页边界会重发），而且列表 key 冲突 |
 * | 「还有没有下一页」的判据 | 按钮该在时不在（漏页）或该走时还在（点了没反应） |
 *
 * 三条都在这里做成纯函数，由 `SearchPagingTest` 钉住 —— 而 `SearchViewModel`
 * 自己依赖 `viewModelScope` 与 `android.util.Log`，JVM 单测里跑不起来
 * （本仓库对这类逻辑的既有做法就是「抽成纯函数 + 单测」，见 `PlayerDragSnap` / `PreloadSlot`）。
 */
object SearchPaging {

    /**
     * 每页条数。
     *
     * 30 是**有依据的**：QQ 那条腿用旧版 GET，它的页码 `p=` 与每页条数 `n=` 耦合
     * （`offset = (p-1) * n`），所以两个音源要共用同一个页大小，否则
     * 「第一页 100 条、第二页 30 条」这种口径错位从一开始就存在。
     * 而 30 这个值是 QQ 旧接口实测稳定的量级。
     */
    const val PAGE_SIZE = 30

    /**
     * 页码（**1 起**）→ 接口要的 `offset`。
     *
     * 页码非法（0、负数）时按第一页处理：**宁可多取一次第一页，也不要发一个负 offset**
     * —— 后者在服务端可能被当成「从末尾倒数」或者直接报错，两种都难查。
     */
    fun offsetOf(page: Int, pageSize: Int = PAGE_SIZE): Int {
        val safePage = if (page < 1) 1 else page
        val safeSize = if (pageSize < 1) PAGE_SIZE else pageSize
        return (safePage - 1) * safeSize
    }

    /**
     * 这一页**是否可能还有下一页**：判据是「这一页被填满了」。
     *
     * ## 为什么只能这样判（以及它的代价）
     *
     * 两个音源的搜索响应里都**没有稳定的总条数字段**可按：
     * ncm 的 `result.songCount` 只在部分返回里出现，qm 旧版搜索压根不给。
     * 所以判据只能是「取回了满一页 ⇒ 可能还有」。
     *
     * 代价**不对称、而且方向是对的**：最后一页会多给用户一次点击
     * （点了发现没有新的），但**不会漏掉任何一页**。反过来判（「不满一页才算有下一页」）
     * 会让最后一整页永远取不到 —— 那是缺陷，而多一次点击只是体验上的小瑕疵。
     */
    fun hasMore(pageItemCount: Int, pageSize: Int = PAGE_SIZE): Boolean {
        val safeSize = if (pageSize < 1) PAGE_SIZE else pageSize
        return pageItemCount >= safeSize
    }

    /**
     * 三条腿里**任意一条**填满了这一页 ⇒ 还有下一页。
     *
     * 为什么取「或」而不是「与」：三条腿各自独立分页，只要有一条还有货，
     * 「加载更多」就还有意义（另一条腿没有新的，只是这一页它对结果没有贡献）。
     */
    fun hasMoreAny(vararg pageItemCounts: Int, pageSize: Int = PAGE_SIZE): Boolean =
        pageItemCounts.any { hasMore(it, pageSize) }

    /**
     * 跨页追加 + 按身份去重。
     *
     * 去重是**必需**的：服务端的 `offset` 分页在数据变动时会重发边界上的条目
     * （用户在两次点击之间新增/删除了一条，分页窗口就错位一格）。
     * 不去重的表现是列表里同一条歌出现两次，而且 Compose 的 `items(key = { it.id })`
     * 会因 key 重复抛异常。
     *
     * 顺序：**保留先出现的那一条**（`distinctBy` 的既有语义），
     * 与「上一页在前」的直觉一致。
     */
    fun appendDistinct(existing: List<SongItem>, page: List<SongItem>): List<SongItem> =
        (existing + page).distinctBy { it.trackKey }

    /**
     * 按**身份**去重（不追加）。给「重新取这一页并替换」那条路径用。
     */
    fun distinct(list: List<SongItem>): List<SongItem> = list.distinctBy { it.trackKey }
}
