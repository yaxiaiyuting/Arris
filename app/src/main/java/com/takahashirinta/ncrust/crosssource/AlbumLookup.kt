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
     * 「歌手这一条判据」的三种结局（H05）。
     *
     * ## 为什么必须与 [score] 分开、且是**三值**
     *
     * 旧代码把「同名不同歌手」的排除条件写成 `score(...) == null` —— 而 [score] 在
     * **名称相同**时永远返回非 null（歌手不一致只不过加 0 分），于是那道闸门从未生效：
     * 同名的另一张专辑照样能拿到 `HIGH` 并作为 alias 写进缓存。
     *
     * `null` 是个二值的语言，而这里的事实是三值的：
     * - [SAME] / [CONTAINS]：两侧都给了歌手且对得上 ⇒ 歌手这条判据**成立**；
     * - [CONFLICT]：两侧都给了歌手但**对不上** ⇒ 同名不同人，这是「另一张专辑」；
     * - [UNKNOWN]：至少一侧没给歌手 ⇒ **无法判断**（不是「冲突」，也不是「一致」）。
     *
     * 把 [UNKNOWN] 与 [CONFLICT] 混在一起，要么误杀正确候选（信息缺失当成冲突），
     * 要么放行错配（信息缺失当成一致）。两者都会在界面上表现为「匹配不可信」。
     *
     * ⚠️ 已知边界：本仓库不做繁简转换（见 [NameNormalizer.artistsOverlap]），
     * `周杰伦` 与 `周杰倫` 会被判成 [CONFLICT]。这是既有口径，不在本次改动里推翻 ——
     * 但调用方（`CatalogAggregator.findCounterpartAlbum`）只在**两边都已知**时才据此跳过候选，
     * 信息不足时仍然保留候选（宁可少排除，也不凭猜测丢候选）。
     */
    enum class ArtistVerdict {
        /** 两侧都已知且归一化后完全相同。 */
        SAME,

        /** 两侧都已知且存在包含关系（合作艺人 `A / B` vs `A`）。 */
        CONTAINS,

        /** 两侧都已知但对不上 ⇒ 同名不同歌手。 */
        CONFLICT,

        /** 至少一侧没给歌手 ⇒ 判不出来。 */
        UNKNOWN,
        ;

        /** 歌手判据**正向成立**（[SAME] 或 [CONTAINS]）。 */
        val matched: Boolean get() = this == SAME || this == CONTAINS
    }

    /**
     * 只判「歌手」这一条（H05 的唯一落点；[score] 也复用它，避免两处规则漂移）。
     */
    fun artistVerdict(queryArtist: String?, candidateArtist: String?): ArtistVerdict {
        val qArtist = queryArtist?.let { NameNormalizer.normalizeName(it) }?.takeIf { it.isNotEmpty() }
            ?: return ArtistVerdict.UNKNOWN
        val cArtist = candidateArtist?.let { NameNormalizer.normalizeName(it) }?.takeIf { it.isNotEmpty() }
            ?: return ArtistVerdict.UNKNOWN
        return when {
            qArtist == cArtist -> ArtistVerdict.SAME
            qArtist.contains(cArtist) || cArtist.contains(qArtist) -> ArtistVerdict.CONTAINS
            else -> ArtistVerdict.CONFLICT
        }
    }

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
        // 歌手判据与「同名不同歌手」的排除条件（`CatalogAggregator.findCounterpartAlbum`）
        // 共用 [artistVerdict] 这一个实现：两处各写一遍必然漂移，而漂移的那一天
        // 打分说「同歌手」、闸门说「冲突」—— 表现是候选被随机丢掉。
        when (artistVerdict(queryArtist, candidate.artistName)) {
            ArtistVerdict.SAME -> {
                score += 4
                parts += "同歌手"
            }
            // 合作艺人：`A / B` vs `A`。包含关系是可用的信号，但比完全相同弱。
            ArtistVerdict.CONTAINS -> {
                score += 2
                parts += "歌手包含"
            }
            // 信息缺失与冲突都**不加分**：前者是「不知道」，后者是「不是同一个人」。
            ArtistVerdict.UNKNOWN, ArtistVerdict.CONFLICT -> Unit
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
