/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.4.0 · E：跨源身份匹配与版权优先排序。**纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.crosssource

import com.takahashirinta.ncrust.search.TrackAvailability
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey

/**
 * 跨源匹配与排序的**唯一算法落点**（v2.4.0 · E）。
 *
 * ## 三步走：召回 → 校验 → 分级
 *
 * 探针（`probe-artist-mapping.md` P6）给出的最重要一条结论是：
 * **名字只用来召回，绝不用来判定**。所以每个匹配入口都是同一个形状：
 *
 * 1. **召回**（[recallArtists] / 同归一化名分桶）：宁可多收，不可漏收；
 * 2. **校验**：用**结构判据**（专辑列表重合张数、曲目名集合重合、时长差）确认；
 * 3. **分级**：把校验的强度映射成 [MatchConfidence]，低于 `MEDIUM` 就不许合并。
 *
 * ## 与任务书的偏离（逐条，有探针依据）
 *
 * | 任务书原文 | 本实现 | 依据 |
 * |---|---|---|
 * | 单曲 `EXACT` = 「songId 或 mediaId 直接一致」 | 跨源场景下**不存在**这种情形（两个 id 空间独立），所以 `EXACT` 改为「曲名 + 艺人 + 时长 ≤2s + 版本标记一致」 | `probe-song-mapping.md` S1 |
 * | 单曲 `HIGH` = 「歌名 + 艺人 + 专辑一致」 | 改为「曲名 + 艺人 + 时长一致，仅版本标记不同」——**专辑信息在专辑页是冗余的**（同一页里所有曲目的专辑都一样），在搜索页又经常缺失，用时长更稳 | `probe-song-mapping.md` S5：时长差 ≤2s 占 80%，>30s 的 15 条全是同名不同版本 |
 * | 专辑 `EXACT` = 「专辑名完全一致 + 艺人一致 + 曲目数一致」 | 再加一条「曲目名集合重合率 ≥90%」 | `probe-album-mapping.md`：曲目数只一致 41/48，单靠它会把 7 对错判成 EXACT |
 * | 艺人 `EXACT` = 「两源都有唯一同名且专辑列表高度重合」 | 照做，并**显式**把「唯一」定义为「专辑数并列最高的候选只有一个」 | 同上 |
 *
 * ## 有界性（铁律 6）
 *
 * 所有「逐个候选试」的循环都有显式上限（[MAX_ARTIST_CANDIDATES]），
 * 且**一旦命中 `HIGH` 就提前结束** —— 艺人页的候选校验每多试一个就多一次网络请求，
 * 不设上限等于把页面加载时间交给服务端的同名数据。
 */
object CrossSourceMatcher {

    // ------------------------------------------------------------------ 输入 ----

    /**
     * 艺人候选。
     *
     * @property albumNames 该艺人**在它自己那个源上**的专辑名列表。它是唯一的强判据来源。
     *   拿不到（网络失败 / 接口变了）时传**空列表** —— 空列表会让分级落到 `LOW`，
     *   也就是「不合并」。这是有意的：**校验数据缺失时宁可分开，也不给错艺人**。
     */
    data class ArtistCandidate(
        val key: ArtistKey,
        val albumNames: List<String>,
        /** 该源的「专辑数」标签（ncm `albumSize` / QQ `albumNum`），只用于候选排序。 */
        val albumCountHint: Int = 0,
    )

    /**
     * 专辑候选。
     *
     * @property trackNames 曲目名列表（归一化前的原文）。
     * @property trackCount 服务端声明的曲目数（可能为 null ⇒ 不作为 EXACT 的必要条件）。
     */
    data class AlbumCandidate(
        val key: AlbumKey,
        val trackNames: List<String>,
        val trackCount: Int? = null,
        val rawName: String = key.name,
        val artistNames: List<String> = emptyList(),
    )

    /** 曲目候选。`durationMs` 为 null 表示该源没给时长（ncm `dt` / QQ `interval`）。 */
    data class TrackCandidate(
        val key: TrackKey,
        val name: String,
        val artists: List<String> = emptyList(),
        val durationMs: Long? = null,
        val albumName: String? = null,
    )

    /** 一对配上的曲目。 */
    data class TrackPair(
        val anchor: TrackCandidate,
        val other: TrackCandidate,
        val verdict: MatchVerdict,
    )

    // ------------------------------------------------------------------ 常量 ----

    /**
     * 艺人候选最多校验几个。取 3 是「够用且有界」：
     * 实测最坏的同名场景是 `李健`（QQ 有 9 个同名字号），
     * 而按专辑数降序后真身一定在第一个，第二、三个只是兜底。
     */
    const val MAX_ARTIST_CANDIDATES = 3

    /** 时长容差（毫秒）。探针实测：≤2s 占 184/230，>30s 的 15 条全是同名不同版本。 */
    const val DURATION_TOLERANCE_MS = 2_000L

    /** 「专辑列表高度重合」的判据：至少 3 张，且达到较小一侧的一半。 */
    const val ARTIST_EXACT_MIN_OVERLAP = 3

    /** 「专辑曲目名集合高度重合」的判据。 */
    const val ALBUM_TRACK_RATIO = 0.9

    // ------------------------------------------------------------------ 艺人 ----

    /**
     * 名称**包含**召回，按 `albumCountHint` 降序（稳定排序 ⇒ 同一份数据每次结果一致）。
     *
     * 包含关系（而不是相等）是为了 `邓紫棋` ↔ `G.E.M.邓紫棋` 这类「艺名 ≠ 真名」；
     * 排序用专辑数是为了让真身排在仿冒号前面（实测真身专辑数远大于仿冒号）。
     */
    fun recallArtists(anchorName: String, candidates: List<ArtistCandidate>): List<ArtistCandidate> =
        candidates
            .filter { NameNormalizer.containsName(anchorName, it.key.name) }
            .sortedByDescending { it.albumCountHint }

    /**
     * 给一对艺人候选定级。
     *
     * @param unique 召回结果里「专辑数并列最高」的候选只有一个。只有它才可能给 `EXACT` ——
     *   这正是任务书里「两源都有**唯一**同名」那句的可执行版本。
     */
    fun gradeArtist(
        anchorName: String,
        anchorAlbums: Collection<String>,
        candidate: ArtistCandidate,
        unique: Boolean,
    ): MatchVerdict {
        if (!NameNormalizer.containsName(anchorName, candidate.key.name)) {
            return MatchVerdict(MatchConfidence.NONE, "名称无法召回")
        }
        val anchorSet = anchorAlbums.mapNotNull { normOrNull(it) }.toSet()
        val candSet = candidate.albumNames.mapNotNull { normOrNull(it) }.toSet()
        val overlap = anchorSet.count { it in candSet }

        // 一侧的专辑列表拿不到 ⇒ 无法校验 ⇒ 只能给 LOW（= 不合并）。
        if (anchorSet.isEmpty() || candSet.isEmpty()) {
            return MatchVerdict(MatchConfidence.LOW, "仅同名：一侧专辑列表不可用，无法校验")
        }
        val smaller = minOf(anchorSet.size, candSet.size)
        if (overlap >= ARTIST_EXACT_MIN_OVERLAP && unique && overlap * 2 >= smaller) {
            return MatchVerdict(
                MatchConfidence.EXACT,
                "唯一同名候选 + 专辑重合 $overlap 张（占较小侧 ${overlap * 100 / smaller}%）",
            )
        }
        if (overlap >= ARTIST_EXACT_MIN_OVERLAP) {
            return MatchVerdict(MatchConfidence.HIGH, "同名 + 专辑重合 $overlap 张")
        }
        if (overlap >= 1) {
            return MatchVerdict(MatchConfidence.MEDIUM, "同名 + 专辑重合 $overlap 张")
        }
        return MatchVerdict(MatchConfidence.LOW, "仅同名（专辑零重合）")
    }

    /**
     * 完整走一遍艺人匹配：召回 → 逐个校验 → 取最好的一档。
     *
     * @return 配上时返回 `(候选, 判据)`；一个都没配上返回 null（调用方据此**分开展示**）。
     *   注意 `LOW` 的结果**也会返回** —— UI 需要知道「我们试过了、但不敢合并」，
     *   以便显示「另一源可能存在同名艺人」这类可追溯信息。是否合并由 [MatchVerdict.mergeable] 决定。
     */
    fun matchArtist(
        anchorName: String,
        anchorAlbums: Collection<String>,
        candidates: List<ArtistCandidate>,
    ): Pair<ArtistCandidate, MatchVerdict>? {
        val recalled = recallArtists(anchorName, candidates)
        if (recalled.isEmpty()) return null
        val topHint = recalled.first().albumCountHint
        val unique = recalled.count { it.albumCountHint == topHint } == 1
        var best: Pair<ArtistCandidate, MatchVerdict>? = null
        for (candidate in recalled.take(MAX_ARTIST_CANDIDATES)) {
            val verdict = gradeArtist(anchorName, anchorAlbums, candidate, unique)
            best = best?.takeIf { it.second.rank >= verdict.rank } ?: (candidate to verdict)
            if (verdict.confidence == MatchConfidence.EXACT) break
        }
        return best
    }

    // ------------------------------------------------------------------ 专辑 ----

    /**
     * 给一对专辑候选定级。
     *
     * @param trackOverlapRatio 两侧**曲目名集合**的重合率；由调用方算好传进来
     *   （因为它需要两侧的曲目列表，而候选里已经带着了）。
     */
    fun gradeAlbum(
        anchor: AlbumCandidate,
        candidate: AlbumCandidate,
        trackOverlapRatio: Double,
    ): MatchVerdict {
        if (!NameNormalizer.sameName(anchor.rawName, candidate.rawName)) {
            // 归一化后仍不等 ⇒ 连召回都不算。**不做「包含即匹配」**：
            // 「叶惠美」与「叶惠美（钢琴版）」归一化后相等（括注被剥掉了），
            // 而「叶惠美」与「叶惠美现场」不相等 —— 后者本来就该分开。
            return MatchVerdict(MatchConfidence.NONE, "专辑名归一化后不相等")
        }
        val artistStrong = NameNormalizer.artistsOverlap(anchor.artistNames, candidate.artistNames)
        val artistWeak = artistStrong || NameNormalizer.containsName(
            anchor.artistNames.firstOrNull(), candidate.artistNames.firstOrNull(),
        )
        val nameRawEqual = anchor.rawName == candidate.rawName
        val countEqual = anchor.trackCount != null && anchor.trackCount == candidate.trackCount
        val tracksAgree = trackOverlapRatio >= ALBUM_TRACK_RATIO

        if (nameRawEqual && artistStrong && countEqual && tracksAgree) {
            return MatchVerdict(
                MatchConfidence.EXACT,
                "专辑名完全相同 + 艺人一致 + 曲目数一致(${anchor.trackCount}) + 曲目名重合 ${(trackOverlapRatio * 100).toInt()}%",
            )
        }
        if (artistStrong && tracksAgree) {
            return MatchVerdict(
                MatchConfidence.HIGH,
                "专辑名归一化后一致 + 艺人一致 + 曲目名重合 ${(trackOverlapRatio * 100).toInt()}%",
            )
        }
        if (artistStrong) {
            return MatchVerdict(
                MatchConfidence.MEDIUM,
                "专辑名归一化后一致 + 艺人一致（曲目名重合仅 ${(trackOverlapRatio * 100).toInt()}%）",
            )
        }
        if (artistWeak) {
            return MatchVerdict(MatchConfidence.MEDIUM, "专辑名归一化后一致 + 艺人模糊匹配")
        }
        return MatchVerdict(MatchConfidence.LOW, "仅专辑名一致（艺人不同）")
    }

    /** 曲目名集合的重合率（分母取较小一侧；两侧都空时返回 0，**不返回 1**）。 */
    fun trackOverlapRatio(a: Collection<String>, b: Collection<String>): Double {
        val sa = a.mapNotNull { normOrNull(it) }.toSet()
        val sb = b.mapNotNull { normOrNull(it) }.toSet()
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        return sa.count { it in sb }.toDouble() / minOf(sa.size, sb.size)
    }

    // ------------------------------------------------------------------ 单曲 ----

    /** 给一对曲目候选定级。规则见类文档的偏离表。 */
    fun gradeTrack(anchor: TrackCandidate, candidate: TrackCandidate): MatchVerdict {
        if (!NameNormalizer.sameName(anchor.name, candidate.name)) {
            return MatchVerdict(MatchConfidence.NONE, "曲名归一化后不相等")
        }
        val artistOk = NameNormalizer.artistsOverlap(anchor.artists, candidate.artists)
        val sameEdition = NameNormalizer.hasEditionMarker(anchor.name) ==
            NameNormalizer.hasEditionMarker(candidate.name)
        val delta = durationDelta(anchor, candidate)
        val durationOk = delta != null && delta <= DURATION_TOLERANCE_MS

        if (!artistOk) {
            // 同名 + 时长也一致，但艺人写法完全不同 ⇒ 可能是合唱/翻唱，也可能是同名不同曲。
            // 只给 LOW（= 不合并），因为「给错单曲」比「不合并」严重得多（铁律 17）。
            return if (durationOk) {
                MatchVerdict(MatchConfidence.LOW, "仅曲名 + 时长一致（艺人不同）")
            } else {
                MatchVerdict(MatchConfidence.LOW, "仅曲名一致")
            }
        }
        if (durationOk && sameEdition) {
            return MatchVerdict(MatchConfidence.EXACT, "曲名 + 艺人 + 时长差 ${delta}ms + 版本标记一致")
        }
        if (durationOk) {
            return MatchVerdict(MatchConfidence.HIGH, "曲名 + 艺人 + 时长一致，版本标记不同")
        }
        if (delta == null) {
            return MatchVerdict(MatchConfidence.MEDIUM, "曲名 + 艺人一致（一侧没有时长，无法校验）")
        }
        // 艺人一致但时长差很大：**这几乎一定是同名不同版本**（伴奏 / Live / 加长版）。
        // 实测 >30s 的 15 条全部属于这一类，所以这里只给 LOW。
        return MatchVerdict(MatchConfidence.LOW, "曲名 + 艺人一致，但时长差 ${delta}ms（疑似不同版本）")
    }

    /**
     * 一对一的曲目配对（贪心，按置信度降序，**每条候选只能用一次**）。
     *
     * 为什么要一对一：同一张专辑里可能有「原曲 + 伴奏」两条归一化后同名，
     * 放任多对一会让两个锚点抢同一个候选，UI 上表现为「两条都指向另一源的同一首」。
     *
     * **有界**：外层是锚点条数（一屏 ≤ 数百），内层是同名桶（实测 ≤3），
     * 不存在指数或无限循环。
     */
    fun pairTracks(anchor: List<TrackCandidate>, other: List<TrackCandidate>): List<TrackPair> {
        val buckets = other.groupBy { NameNormalizer.normalizeName(it.name) }
        val used = HashSet<TrackKey>()
        val out = ArrayList<TrackPair>(anchor.size)
        for (a in anchor) {
            val bucket = buckets[NameNormalizer.normalizeName(a.name)] ?: continue
            var best: TrackPair? = null
            for (c in bucket) {
                if (c.key in used) continue
                val verdict = gradeTrack(a, c)
                if (verdict.confidence == MatchConfidence.NONE) continue
                if (best == null || verdict.rank > best.verdict.rank ||
                    (verdict.rank == best.verdict.rank && closer(a, c, best.other))
                ) {
                    best = TrackPair(a, c, verdict)
                }
            }
            if (best != null) {
                used += best.other.key
                out += best
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 排序 ----

    /**
     * 版权可用性优先排序（特性 D）。
     *
     * 顺序：**`PLAYABLE` > `UNKNOWN` > `MEMBER_ONLY` > `NO_COPYRIGHT`**。
     *
     * ## 为什么 `UNKNOWN` 排在受限项**之前**
     *
     * 这是本版最容易写错的一处。直觉是「不知道就沉底」，但那等于**凭空说它不能播** ——
     * 探测失败（网络抖动、该源没给字段）与「服务端说不能播」是两件事。
     * v2.3.0 的 `TrackAvailability.rankGroup` 已经定过同一条约定
     * （`PLAYABLE` 一组、其余一组，且注释写着「不知道就靠后，绝不往前推」），
     * 但那只用于搜索页的「沉底」；详情页需要**四档**，因为这里要显示角标、要对用户负责。
     *
     * **稳定排序**：同档内保持接口原顺序（铁律：同源内按接口原顺序）。
     */
    fun availabilityOrder(availability: TrackAvailability): Int = when (availability) {
        TrackAvailability.PLAYABLE -> 0
        TrackAvailability.UNKNOWN -> 1
        TrackAvailability.MEMBER_ONLY -> 2
        TrackAvailability.NO_COPYRIGHT -> 3
    }

    /**
     * 稳定地按可用性重排；同档保持原顺序。
     *
     * 用 `sortedWith(compareBy { ... })`（稳定）而不是 `sortedByDescending`，
     * 理由与 `ArtistReco.rankAnchors` 那次一样：**同一份服务端数据每次必须给出同一个顺序**。
     */
    fun <T> rankByAvailability(items: List<T>, availabilityOf: (T) -> TrackAvailability): List<T> =
        items.sortedWith(compareBy { availabilityOrder(availabilityOf(it)) })

    /**
     * 这一行**是不是真的被确证过能播** —— 决定 UI 允许说哪一句话。
     *
     * ## 为什么单拎一个谓词出来（真机验证抓到的一次「回显虚高」）
     *
     * 单曲页原本在「默认那一行」上无条件显示「已默认选中有版权的音源」。
     * API 24 模拟器实测反例：Adele《Strangers By Nature》在**两源都是「需会员」**
     * （QQ 拿不到 `purl`、ncm `pl==0`），而那一行照样写着「已默认选中有版权的音源」——
     * 这违反铁律 7（降级时不得回显虚高）。
     *
     * 判据只能看 [TrackAvailability.PLAYABLE]：`UNKNOWN` **不算**确证
     * （探测失败与「能播」是两件事），`MEMBER_ONLY` / `NO_COPYRIGHT` 更不算。
     * 所以 `pickPlayable` 挑出来的「默认行」在没有任何 `PLAYABLE` 时会回落到第一条 ——
     * 那是**排序回退**，不是**能力断言**，两者必须分开表达。
     */
    fun isConfirmedPlayable(availability: TrackAvailability): Boolean =
        availability == TrackAvailability.PLAYABLE

    /**
     * 在双源列表里挑「用户点这一行时该放哪个版本」。
     *
     * 规则：**能播的优先**；都能播或都不能播时，回落到用户当前的口径偏好
     * （`preferred` 非空则优先它），最后回落到原顺序的第一条。
     *
     * 这是特性 C「默认选有版权的源播放」的唯一落点。
     */
    fun <T> pickPlayable(
        versions: List<T>,
        sourceOf: (T) -> MusicSource,
        availabilityOf: (T) -> TrackAvailability,
        preferred: MusicSource? = null,
    ): T? {
        if (versions.isEmpty()) return null
        val playable = versions.filter { availabilityOf(it) == TrackAvailability.PLAYABLE }
        val pool = playable.ifEmpty { versions }
        if (preferred != null) {
            pool.firstOrNull { sourceOf(it) == preferred }?.let { return it }
        }
        return pool.first()
    }

    // ------------------------------------------------------------------ 工具 ----

    private fun normOrNull(text: String?): String? =
        text?.let { NameNormalizer.normalizeName(it) }?.takeIf { it.isNotEmpty() }

    private fun durationDelta(a: TrackCandidate, b: TrackCandidate): Long? {
        val da = a.durationMs ?: return null
        val db = b.durationMs ?: return null
        if (da <= 0L || db <= 0L) return null
        return kotlin.math.abs(da - db)
    }

    private fun closer(anchor: TrackCandidate, a: TrackCandidate, b: TrackCandidate): Boolean {
        val da = durationDelta(anchor, a) ?: Long.MAX_VALUE
        val db = durationDelta(anchor, b) ?: Long.MAX_VALUE
        return da < db
    }

    /** 置信度的可比较强度 —— `EXACT` 最强。用于「取最好的一档」。 */
    private val MatchConfidence.rank: Int
        get() = when (this) {
            MatchConfidence.EXACT -> 4
            MatchConfidence.HIGH -> 3
            MatchConfidence.MEDIUM -> 2
            MatchConfidence.LOW -> 1
            MatchConfidence.NONE -> 0
        }

    private val MatchVerdict.rank: Int get() = confidence.rank
}
