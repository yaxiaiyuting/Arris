/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.11：专辑「按名字 + 歌手」解析的**纯逻辑**（无 IO、无 Android 依赖、JVM 可单测）。
 */

package com.takahashirinta.ncrust.crosssource

/**
 * 从一列**同名专辑**里挑出「正主」的判据（v3.4.11）。**纯函数，JVM 可单测。**
 *
 * ## 为什么需要它（用户报障）
 *
 * > 「专辑界面也做和单曲界面类似的搜索处理，优先原唱，原歌手，
 * >   用搜索的歌手名字匹配专辑作者等等这些搜索功能」
 *
 * 根因是**同名专辑**：一张专辑在两个平台上会有「原版 / 精选 / 现场 / 翻唱合集 /
 * 重制版」同时存在，名字一模一样。而在此之前：
 *
 * - `AlbumDetailScreen` 只拿 `album/{albumId}` 直取元信息，**它给的歌手名没有被用来
 *   定位专辑**；而且那个直取一旦失败（下架 / 换 id），页面就再也拿不到名字 ——
 *   没有名字，`CatalogAggregator` 连给对端构造搜索关键词都做不到；
 * - `findCounterpartAlbum` 搜对端时**只按专辑名**，候选排序里
 *   `artistNames` 虽然被传进 `CrossSourceMatcher.gradeAlbum`，但取的是
 *   `listOfNotNull(candidate.singerName, anchorArtist)` —— 两边都在一个列表里，
 *   判据分不清「候选自己的歌手」与「锚点的歌手」。
 *
 * 所以这里把「怎么才算同一张专辑」写成一条**排序规则**，而不是散在调用点的 if 里：
 * ① 专辑名规范化后完全相同；② 歌手名规范化后完全相同（**这一条就是「用搜索的歌手名字
 * 匹配专辑作者」**）；③ 曲目数相同（同版本的两个平台条目通常一致）。
 *
 * ## 为什么是「打分排序」而不是「精确过滤」
 *
 * 歌手字段在两个平台都可能带**合作艺人**（`A / B`、`A feat. B`、`A、B`），
 * 精确相等会把正确答案滤掉。所以按分数排序、取最高分，并**要求名称必须命中**
 * （名称不同就是另一张专辑，那一条不做妥协）。
 */
object AlbumLookup {

    /** 一个候选专辑（两个平台各有一份，这里统一成同一形状）。 */
    data class Candidate(
        val id: Long,
        val name: String,
        /** 专辑作者（平台给的歌手名，可能带合作艺人）。 */
        val artistName: String? = null,
        val trackCount: Int? = null,
    )

    /** 打分结果。 */
    data class Scored(val candidate: Candidate, val score: Int, val reason: String)

    /**
     * 给候选打分。**名称不命中直接返回 null**（那是另一张专辑，不该参与排序）。
     *
     * 分值（累加）：
     * | 条件 | 分 |
     * |---|---|
     * | 名称规范化后完全相同 | 必选（否则 null） |
     * | 歌手规范化后完全相同 | +4 |
     * | 歌手「包含」关系（`A / B` vs `A`） | +2 |
     * | 曲目数相同（且两边都已知） | +1 |
     *
     * 歌手权重明显高于曲目数：同名专辑里「同一个歌手的」几乎总是正主，
     * 而曲目数会因为「精选版多两首」而不等 —— 反过来加权会把正主排到后面。
     */
    fun score(
        queryName: String,
        queryArtist: String?,
        candidate: Candidate,
        /** 锚点自己的曲目数（可为 null = 未知）。**未知时不参与比较** —— 见下面的注释。 */
        queryTrackCount: Int? = null,
    ): Scored? {
        // 注意：这是**块体**函数，末尾必须显式 `return`（写成表达式体会让
        // 上面的 `return null` 与下面的构造打架 —— 编译器会报「需要 return 表达式」）。
        val qName = NameNormalizer.normalizeName(queryName)
        val cName = NameNormalizer.normalizeName(candidate.name)
        if (qName.isEmpty() || cName.isEmpty() || qName != cName) return null

        var score = 0
        val parts = ArrayList<String>(3)
        parts += "同名"
        val qArtist = queryArtist?.let { NameNormalizer.normalizeName(it) }?.takeIf { it.isNotEmpty() }
        val cArtist = candidate.artistName?.let { NameNormalizer.normalizeName(it) }?.takeIf { it.isNotEmpty() }
        if (qArtist != null && cArtist != null) {
            when {
                qArtist == cArtist -> {
                    score += 4
                    parts += "同歌手"
                }
                qArtist.contains(cArtist) || cArtist.contains(qArtist) -> {
                    // 合作艺人：`A / B` vs `A`。包含关系是可用的信号，但比完全相同弱。
                    score += 2
                    parts += "歌手包含"
                }
            }
        }
        // 曲目数：**两边都已知**且相同才 +1。
        // ⚠️ 未知（null）绝不能当成 0 去比 —— 那会让「不知道」凭空输给「知道」，
        // 而「不知道」在真实数据里是常态（QQ 的搜索结果经常不给 size）。
        if (queryTrackCount != null && candidate.trackCount != null &&
            queryTrackCount == candidate.trackCount
        ) {
            score += 1
            parts += "同曲目数"
        }
        return Scored(candidate, score, parts.joinToString("+"))
    }

    /**
     * 挑出最可信的那一张。**没有任何候选名称命中时返回 `null`**（调用方保留直取的结果）。
     *
     * 平手时保留**先出现的**（`sortedByDescending` 是稳定排序）：
     * 平台自己的排序通常把正主放在前面，我们没有更强的依据去推翻它。
     */
    fun best(
        queryName: String,
        queryArtist: String?,
        candidates: List<Candidate>,
        queryTrackCount: Int? = null,
    ): Scored? =
        candidates
            .mapNotNull { score(queryName, queryArtist, it, queryTrackCount) }
            // `maxByOrNull` 在平手时返回**第一个**最大值（Kotlin 的实现语义），
            // 也就是「保留先出现的」—— 平台自己的排序通常把正主放前面，
            // 我们没有更强的依据去推翻它。这条有单测钉着（`同分时保留先出现的那个`）。
            .maxByOrNull { it.score }
}
