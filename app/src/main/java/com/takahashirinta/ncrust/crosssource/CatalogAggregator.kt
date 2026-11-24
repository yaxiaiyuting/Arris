/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.4.0 · A/B/C/D：艺人页 / 专辑页 / 单曲页的**双源聚合编排**。
 */

package com.takahashirinta.ncrust.crosssource

import android.content.Context
import android.util.Log
import com.takahashirinta.ncrust.network.NeteaseAvailabilityApi
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.qq.QqAlbum
import com.takahashirinta.ncrust.qq.QqCatalogApi
import com.takahashirinta.ncrust.search.TrackAvailability
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.source.musicSource
import com.takahashirinta.ncrust.source.songRefOf

/**
 * 聚合后的一行专辑（v2.4.0 · A/B）。
 *
 * @property key 展示与点击用的主身份。**它决定点进去打开哪一源、哪一个 id**。
 * @property aliases 另一源上的同一张专辑（`confidence < MEDIUM` 时为空）。
 * @property confidence 匹配置信度；`< MEDIUM` 时 UI **必须**把它当两行展示。
 */
data class AggregatedAlbum(
    val key: AlbumKey,
    val aliases: List<AlbumKey> = emptyList(),
    val name: String,
    val picUrl: String? = null,
    val trackCount: Int? = null,
    val publishDate: String? = null,
    val company: String? = null,
    val confidence: MatchConfidence = MatchConfidence.NONE,
    val reason: String = "",
) {
    val isDual: Boolean get() = aliases.isNotEmpty()
    fun keyOf(source: MusicSource): AlbumKey? =
        if (key.source == source) key else aliases.firstOrNull { it.source == source }
}

/**
 * 聚合后的一页（v2.4.0）。
 *
 * @property preferredSource 「点进去应该用哪一源」的默认值。它由**可播放性**决定，
 *   不由 id 空间或匹配置信度决定 —— 这是本版要修的那条体验（「不再默认 ncm」）。
 * @property availabilityNote 为什么选这一源的一句话说明（可追溯；空串表示没有特别理由）。
 */
data class ArtistPage(
    val merged: MergedArtist,
    val albums: List<AggregatedAlbum> = emptyList(),
    val songs: List<AggregatedSong> = emptyList(),
    val preferredSource: MusicSource? = null,
    val availabilityNote: String = "",
    val playableCounts: Map<MusicSource, Int> = emptyMap(),
    val totalCounts: Map<MusicSource, Int> = emptyMap(),
)

/** 专辑页的聚合结果。 */
data class AlbumPage(
    val merged: MergedAlbum,
    val songs: List<AggregatedSong> = emptyList(),
    val preferredSource: MusicSource? = null,
    val availabilityNote: String = "",
    val playableCounts: Map<MusicSource, Int> = emptyMap(),
    val totalCounts: Map<MusicSource, Int> = emptyMap(),
)

/** 单曲页的聚合结果：两源版本 + 默认该播哪一个。 */
data class TrackPage(
    val merged: MergedTrack,
    val versions: List<AggregatedSong> = emptyList(),
    val preferredKey: TrackKey? = null,
)

/**
 * 三个详情页的双源聚合编排（v2.4.0）。
 *
 * ## 设计取舍：探测什么、不探测什么
 *
 * 「版权可用性优先」要求知道每首歌**现在**能不能播，而探测是有成本的。
 * 本类只在一处花这个钱：
 *
 * | 页面 | 探测对象 | 请求数 | 为什么不是别的 |
 * |---|---|---|---|
 * | 艺人页 | 该艺人的**曲目列表**（v4.1.12 起 ncm ≤200 首 + QQ ≤30 首，**每侧**只探前 [PROBE_LIMIT] 首） | ncm 1~2 页取歌 + 1 批探测 + QQ 1 批探测 | **不逐张专辑探测** —— 44 张专辑 × 2 源 = 88 次请求，艺人页会变成不可用。专辑行的音源偏好因此由**艺人级**的可播放比例决定 |
 * | 专辑页 | 该专辑的**曲目列表** | ncm ≤1 批 + QQ ≤1 批 | 一屏之内，成本与主请求同级 |
 * | 单曲页 | 两源各自的**那一首** | ≤2 次 | 只有一首 |
 *
 * ## 失败隔离（铁律 5）
 *
 * 任何一个探测失败都只让**那一部分**退回 `UNKNOWN`：
 * 列表照常渲染、角标不显示、**不阻塞播放链路**。所有 IO 都包在 `runCatching` 里。
 *
 * ## 缓存
 *
 * **匹配结果**（谁和谁是同一个）走 [MatchCacheStore]（TTL 7 天）；
 * **可用性**不落盘（它会随 VIP 状态与版权变化），每次进页面重探。
 */
object CatalogAggregator {

    private const val TAG = "CatalogAggregator"

    /**
     * 艺人页 ncm 曲目**一页**取多少首（v4.1.12）。
     *
     * 实测 `/eapi/v1/artist/songs` 一页上限是 200（`limit=200` 回 200，`limit=300` 也只回 200），
     * 一页 100 的响应约 438KB、200 约 845KB（详见 `PlaylistApi.getArtistSongIdsOrNull` 的表）。
     * 取 100 而不是 200：一次 200 首要在**一个响应**里付 845KB，而两页 100 可以
     * 在第一页就短页/失败时提前收手（小艺人多半 1 页就到底）。
     */
    internal const val ARTIST_SONGS_PAGE = 100

    /**
     * 艺人页 ncm 曲目最多翻几页（2 页 = 200 首）。
     *
     * 为什么不是无限翻：`offset` 分页本身可用（实测 offset=100/200 各回 100 条且不重复），
     * 但艺人页是「一进来就要渲染」的页面 —— 周杰伦有 1000+ 首，翻到底要 10 页 / 4MB，
     * 而用户不会滑到第 200 行以后。停在 200 首，剩下的交给搜索。
     */
    internal const val ARTIST_SONGS_MAX_PAGES = 2

    /**
     * **每一侧**最多探测多少首（v4.1.12：从「两侧合计 60」改成「每侧 100」）。
     *
     * 为什么必须按侧算：旧值 60 是「合并后总数」的上限，艺人页两侧各 30 首时正好够。
     * ncm 一侧抬到 100~200 首之后，同一个 60 会把 QQ 那一侧整个挤出预算 ——
     * QQ 曲目全部落进 `UNKNOWN`。而 [preferredSource] 正是按 `playable` 计数决定
     * 「默认切到哪一源」的，那等于**悄悄废掉特性 D 的跨源判定**
     * （周杰伦这类「ncm 无版权、QQ 能播」的艺人会一直默认 ncm）。
     *
     * 按侧算之后**请求数与改动前一致**（ncm 1 批 + QQ 1 批，`NeteaseAvailabilityApi.BATCH = 100`），
     * 只是 ncm 那一批从 60 首变成 100 首。超过部分留在 `UNKNOWN`（宁可少标，不拖住页面）。
     */
    private const val PROBE_LIMIT = 100

    private data class Counterpart<Key, Item>(
        val key: Key,
        val verdict: MatchVerdict,
        val overlap: Int,
        val items: List<Item> = emptyList(),
    )

    // ================================================================== 艺人页 ==================================================================

    /**
     * 拉取并聚合一个艺人页。
     *
     * @param anchor 用户点进来的那个艺人身份。**它永远是主身份**（标题、头像、默认口径都用它）。
     */
    suspend fun loadArtist(context: Context, anchor: ArtistKey): ArtistPage {
        val anchorAlbums = albumsOfSide(anchor)
        val candidate = findCounterpartArtist(context, anchor, anchorAlbums)
        val alias = candidate?.takeIf { it.verdict.mergeable }

        val merged = MergedArtist(
            primary = anchor,
            aliases = listOfNotNull(alias?.key),
            name = anchor.name,
            confidence = candidate?.verdict?.confidence ?: MatchConfidence.NONE,
            reason = candidate?.verdict?.reason.orEmpty(),
            albumOverlap = candidate?.overlap ?: 0,
        )
        if (alias != null) {
            runCatching { MatchCacheStore.putArtist(context, merged) }
        }

        // ---- 两源的专辑列表（H06）----
        // 旧写法按**锚点**分支：锚点是 ncm 才去取对端 QQ 的专辑，锚点是 QQ 时对端的
        // ncm 那份根本没人取 —— 于是「以 QQ 为主源」的艺人页只有 QQ 的专辑，
        // 而 alias 明明已经记下了那个 ncm 艺人。现在两侧先列成 [artistSides]，
        // 再**按各自 source 分派取数**：谁都不会漏，也不会拿 ncm 的数字 id
        // 去当 QQ 的 singerMID（那是「查到别人的专辑」的形状）。
        val sides = artistSides(anchor, alias?.key)
        val albumsBySide = sides.associateWith { key ->
            if (key == anchor) anchorAlbums
            else alias?.items?.takeIf { it.isNotEmpty() } ?: albumsOfSide(key)
        }
        val neAlbums = sides.filter { it.source == MusicSource.NETEASE }.flatMap { albumsBySide[it].orEmpty() }
        val qqAlbums = sides.filter { it.source == MusicSource.QQMUSIC }.flatMap { albumsBySide[it].orEmpty() }
        val albums = if (anchor.source == MusicSource.NETEASE) {
            assembleAlbums(neAlbums, MusicSource.NETEASE, qqAlbums, MusicSource.QQMUSIC)
        } else {
            assembleAlbums(qqAlbums, MusicSource.QQMUSIC, neAlbums, MusicSource.NETEASE)
        }

        // ---- 两源的曲目列表 + 可用性（同 H06）----
        val songsBySide = sides.associateWith { songsOfSide(it) }
        val allSongs = sides.flatMap { songsBySide[it].orEmpty() }
        val neSongs = allSongs.filter { it.musicSource == MusicSource.NETEASE }
        val qqSongs = allSongs.filter { it.musicSource == MusicSource.QQMUSIC }
        val (songs, playable, totals) = assembleSongs(neSongs, qqSongs)

        val preferred = preferredSource(playable, totals, anchor.source)
        return ArtistPage(
            merged = merged,
            albums = albums,
            songs = songs,
            preferredSource = preferred,
            availabilityNote = availabilityNote(playable, totals),
            playableCounts = playable,
            totalCounts = totals,
        )
    }

    // ================================================================== 专辑页 ==================================================================

    /**
     * 拉取并聚合一个专辑页。
     *
     * @param anchor 用户点进来的那张专辑。
     */
    suspend fun loadAlbum(context: Context, anchor: AlbumKey): AlbumPage {
        // 锚点曲目只取一次：它既是匹配判据（曲目名集合），也是最终列表里主源那一侧。
        // 旧实现为此各取一次（匹配判据一次、装配再一次），弱网下两次响应
        // 还可能不一致 —— 那会让「匹配说重合 95%、列表却少两首」这种形态出现。
        val anchorSongs = albumSongsOf(anchor.source, anchor.id)
        val counterpart = findCounterpartAlbum(context, anchor, anchorSongs)
        val alias = counterpart?.takeIf { it.verdict.mergeable }

        val merged = MergedAlbum(
            primary = anchor,
            aliases = listOfNotNull(alias?.key),
            name = anchor.name,
            confidence = counterpart?.verdict?.confidence ?: MatchConfidence.NONE,
            reason = counterpart?.verdict?.reason.orEmpty(),
            trackOverlap = counterpart?.overlap ?: 0,
        )
        if (alias != null) {
            runCatching { MatchCacheStore.putAlbum(context, merged) }
        }

        // H06：两侧都取，且**按每一侧自己的 source 分派**。
        // 旧写法是 `neSongs = if (锚点是 ncm) … else emptyList()` —— 以 QQ 为主源进专辑页时，
        // 网易侧的曲目整块缺失（有对面 alias，列表里却只有 QQ），
        // 而「对面那几个版本」正是用户进双源专辑页要看的东西。
        val sides = albumSides(anchor, alias?.key)
        val songsBySide = sides.associateWith { key ->
            if (key == anchor) anchorSongs
            else alias?.items?.takeIf { it.isNotEmpty() } ?: albumSongsOf(key.source, key.id)
        }
        val allSongs = sides.flatMap { songsBySide[it].orEmpty() }
        val neSongs = allSongs.filter { it.musicSource == MusicSource.NETEASE }
        val qqSongs = allSongs.filter { it.musicSource == MusicSource.QQMUSIC }

        val (songs, playable, totals) = assembleSongs(neSongs, qqSongs)
        val preferred = preferredSource(playable, totals, anchor.source)
        return AlbumPage(
            merged = merged,
            songs = songs,
            preferredSource = preferred,
            availabilityNote = availabilityNote(playable, totals),
            playableCounts = playable,
            totalCounts = totals,
        )
    }

    // ================================================================== 单曲页 ==================================================================

    /**
     * 找出同一首歌的另一个源版本。
     *
     * 入口是**用户点的那一首**（`primary`）。找不到对应版本时 `versions` 只有一条 ——
     * 那不是失败，而是诚实的结果（铁律 17）。
     */
    suspend fun loadTrack(song: SongItem): TrackPage {
        val primary = TrackKey.fromSong(song)
        val other = MusicSource.otherThan(primary.source)
        val versions = ArrayList<AggregatedSong>()
        versions += AggregatedSong(song = song, confidence = MatchConfidence.NONE, matchReason = "当前版本")

        var mergedTrack = MergedTrack(primary, emptyList(), MatchConfidence.NONE)
        if (other != null) {
            val candidates = searchTracks(other, song)
            val anchor = trackCandidateOf(song)
            val pairs = CrossSourceMatcher.pairTracks(listOf(anchor), candidates)
            val best = pairs.maxByOrNull { rank(it.verdict.confidence) }
            if (best != null) {
                val otherSong = candidateToSong(best.other)
                if (otherSong != null) {
                    versions += AggregatedSong(
                        song = otherSong,
                        confidence = best.verdict.confidence,
                        matchReason = best.verdict.reason,
                    )
                    if (best.verdict.mergeable) {
                        mergedTrack = MergedTrack(primary, listOf(best.other.key), best.verdict.confidence, best.verdict.reason)
                    }
                }
            }
        }

        // ---- 可用性：两首各探一次 ----
        // 单曲页只有一两个版本，按侧拆开只是为了跟 [probe] 的新签名对齐（两侧各 1 首）。
        val probed = runCatching {
            probe(
                neSongs = versions.map { it.song }.filter { it.musicSource == MusicSource.NETEASE },
                qqSongs = versions.map { it.song }.filter { it.musicSource == MusicSource.QQMUSIC },
            )
        }.getOrDefault(emptyMap())
        val withAvail = versions.map { v ->
            val availability = probed[TrackKey.fromSong(v.song)]
            v.copy(
                availability = availability ?: TrackAvailability.UNKNOWN,
                availabilityReason = if (availability != null) "预检" else "未探测",
            )
        }

        val preferred = CrossSourceMatcher.pickPlayable(
            versions = withAvail,
            sourceOf = { it.song.musicSource },
            availabilityOf = { it.availability },
            preferred = null,
        )
        return TrackPage(
            merged = mergedTrack,
            versions = withAvail,
            preferredKey = preferred?.let { TrackKey.fromSong(it.song) },
        )
    }

    // ================================================================== 纯装配 ==================================================================

    /**
     * 把两侧的专辑列表装配成一行行（v2.4.0 · A）。
     *
     * **纯函数**，单测直接钉。规则：
     * 1. 以 ncm 一侧的顺序为骨架（它是主源，顺序即相关性）；
     * 2. 每张专辑在 QQ 一侧找**最好的一档**匹配，`mergeable` 才合并；
     * 3. QQ 独有的专辑**追加在末尾**，不插进主序列 —— 插进去会打乱服务端相关性顺序，
     *    而「相关性顺序」是用户唯一能感知的排序依据（v2.3.0 的教训：
     *    按可播放性全量重排会把翻唱/Live 顶到最精确匹配前面）。
     */
    fun assembleAlbums(
        primary: List<QqAlbum>,
        primarySource: MusicSource,
        secondary: List<QqAlbum>,
        secondarySource: MusicSource,
    ): List<AggregatedAlbum> {
        val secondaryByName = HashMap<String, MutableList<QqAlbum>>()
        if (primary.isNotEmpty()) {
            for (album in secondary) {
                val name = NameNormalizer.normalizeName(album.name)
                if (name.isNotEmpty()) secondaryByName.getOrPut(name) { ArrayList() }.add(album)
            }
        }
        val used = HashSet<String>()
        val out = ArrayList<AggregatedAlbum>(primary.size)
        for (a in primary) {
            var best: Pair<QqAlbum, MatchVerdict>? = null
            val name = if (secondaryByName.isEmpty()) "" else NameNormalizer.normalizeName(a.name)
            val candidates = secondaryByName[name]
            if (candidates != null) {
                val anchor = albumCandidateOf(a, primarySource)
                for (b in candidates) {
                    if (b.mid in used) continue
                    val verdict = CrossSourceMatcher.gradeAlbum(
                        anchor,
                        albumCandidateOf(b, secondarySource),
                        // 延续艺人页按专辑名召回时的打分规则。
                        trackOverlapRatio = 1.0,
                    )
                    if (verdict.confidence == MatchConfidence.NONE) continue
                    if (best == null || rank(verdict.confidence) > rank(best.second.confidence)) {
                        best = b to verdict
                    }
                }
            }
            // 只有 `mergeable`（≥MEDIUM）才真的合并成一行；LOW 只记录判据，不合并。
            val alias = best?.takeIf { it.second.mergeable }?.let {
                used += it.first.mid
                AlbumKey(secondarySource, it.first.mid, it.first.name, null)
            }
            out += AggregatedAlbum(
                key = AlbumKey(primarySource, a.mid, a.name),
                aliases = listOfNotNull(alias),
                name = a.name,
                picUrl = coverOf(a, primarySource),
                trackCount = a.trackCount,
                publishDate = a.publishDate,
                company = a.company,
                confidence = best?.second?.confidence ?: MatchConfidence.NONE,
                reason = best?.second?.reason.orEmpty(),
            )
        }
        for (b in secondary) {
            if (b.mid in used) continue
            out += AggregatedAlbum(
                key = AlbumKey(secondarySource, b.mid, b.name),
                name = b.name,
                picUrl = coverOf(b, secondarySource),
                trackCount = b.trackCount,
                publishDate = b.publishDate,
                company = b.company,
                confidence = MatchConfidence.NONE,
                reason = "仅${secondarySource.key}有",
            )
        }
        return out
    }

    /**
     * 把两侧的曲目装配成一行行 + 探一次可用性（v2.4.0 · A/B/D）。
     *
     * 顺序 = **按可用性稳定排序**（`PLAYABLE` > `UNKNOWN` > `MEMBER_ONLY` > `NO_COPYRIGHT`），
     * 同档保持接口原顺序。ncm 的曲目排在 QQ 之前（同档时主源优先）——
     * 这条让「同样能播时先看到自己点进来的那一源」，与用户的心理预期一致。
     */
    suspend fun assembleSongs(
        neSongs: List<SongItem>,
        qqSongs: List<SongItem>,
    ): Triple<List<AggregatedSong>, Map<MusicSource, Int>, Map<MusicSource, Int>> {
        val probed = runCatching { probe(neSongs, qqSongs) }.getOrDefault(emptyMap())
        return assembleSongsWithProbe(neSongs, qqSongs, probed)
    }

    /**
     * 装配的**纯函数**部分：给定两侧曲目与探测结果，产出聚合行 + 统计。
     *
     * 与上面的 suspend 版本分开，是为了让「配对 / 排序 / 统计」这三件事能在没有网络、
     * 没有 Android 运行时的情况下被单测钉住 —— 它们是这一版最容易写错、也最贵的部分
     * （写错的后果是「给错单曲」）。
     */
    fun assembleSongsWithProbe(
        neSongs: List<SongItem>,
        qqSongs: List<SongItem>,
        probed: Map<TrackKey, TrackAvailability>,
    ): Triple<List<AggregatedSong>, Map<MusicSource, Int>, Map<MusicSource, Int>> {
        val rows = ArrayList<AggregatedSong>(neSongs.size + qqSongs.size)

        val pairs = if (neSongs.isEmpty() || qqSongs.isEmpty()) emptyList() else CrossSourceMatcher.pairTracks(
            anchor = neSongs.map { trackCandidateOf(it) },
            other = qqSongs.map { trackCandidateOf(it) },
        )
        // ★ 只有**真的合并了**（mergeable）的次源曲目才从列表里消失。
        //   第一版把「配上了但置信度不够」的也算进去，结果是：一首 LOW 匹配的 QQ 曲目
        //   既没有作为合并结果出现、也没有作为独立行出现 —— **凭空少了一首歌**。
        //   那正是铁律 17 要防的反面：匹配不确定时应该「分开展示」，而不是「消失」。
        val pairByNe = pairs.associateBy { it.anchor.key }
        val absorbedQq = pairs.mapNotNullTo(HashSet(pairs.size)) { pair ->
            pair.other.key.takeIf { pair.verdict.mergeable }
        }
        val qqByKey = if (absorbedQq.isEmpty()) emptyMap() else qqSongs.associateBy { TrackKey.fromSong(it) }

        for (s in neSongs) {
            val key = TrackKey.fromSong(s)
            val pair = pairByNe[key]
            val mergeable = pair?.verdict?.mergeable == true
            val otherKey = pair?.other?.key
            val otherAvailability = otherKey?.let { probed[it] }
            val selfAvailability = probed[key]

            // ★ 合并之后「这一行放哪一源的哪一首」由**可播放性**决定（特性 D 的核心）：
            //   ncm 无版权、QQ 能播时，这一行的 `song` 必须是 QQ 那一首 ——
            //   否则界面上标着「可播放」、点下去播的还是那个播不了的版本。
            //   平级时保持主源（用户是从主源进来的）。
            val alternative = if (mergeable && otherKey != null &&
                CrossSourceMatcher.availabilityOrder(otherAvailability ?: TrackAvailability.UNKNOWN) <
                CrossSourceMatcher.availabilityOrder(selfAvailability ?: TrackAvailability.UNKNOWN)
            ) qqByKey[otherKey] else null
            val displayAvailability = if (alternative != null) otherAvailability else selfAvailability
            val otherKeys = when {
                !mergeable -> emptyList()
                alternative != null -> listOf(key)
                else -> listOfNotNull(otherKey)
            }
            rows += AggregatedSong(
                song = alternative ?: s,
                availability = displayAvailability ?: TrackAvailability.UNKNOWN,
                confidence = pair?.verdict?.confidence ?: MatchConfidence.NONE,
                matchReason = pair?.verdict?.reason.orEmpty(),
                mergedKeys = otherKeys,
                availabilityReason = if (displayAvailability != null) "预检" else "未探测",
            )
        }
        for (s in qqSongs) {
            val key = TrackKey.fromSong(s)
            if (key in absorbedQq) continue // 已经作为 ncm 那一行的合并结果出现
            val availability = probed[key]
            rows += AggregatedSong(
                song = s,
                availability = availability ?: TrackAvailability.UNKNOWN,
                mergedKeys = emptyList(),
                availabilityReason = if (availability != null) "预检" else "未探测",
            )
        }

        val ranked = CrossSourceMatcher.rankByAvailability(rows) { it.availability }
        val playable = LinkedHashMap<MusicSource, Int>()
        val totals = LinkedHashMap<MusicSource, Int>()
        for (row in ranked) {
            val source = row.song.musicSource
            totals[source] = (totals[source] ?: 0) + 1
            if (row.availability == TrackAvailability.PLAYABLE) {
                playable[source] = (playable[source] ?: 0) + 1
            }
        }
        return Triple(ranked, playable, totals)
    }

    /**
     * 默认音源（特性 D 的核心）。
     *
     * 规则，按优先级：
     * 1. **只有一侧有可播放内容** ⇒ 选它（这就是「周杰伦默认切到 QQ」的那条）；
     * 2. 两侧都有可播放内容 ⇒ **保持主源**（不因为「多两首」就把用户从自己点进来的源拽走）；
     * 3. **两侧都没有可播放内容** ⇒ 保持主源，并在 UI 上如实说明「两源都未能确证可播放」。
     *
     * 副产物的诚实性：这条规则**只在探测成功过的时候**才生效。
     * 一次都没探到（`playableCounts` 为空且 `totalCounts` 为空）时返回 null，
     * 调用方据此**不做任何默认源切换**，也不显示「无版权」字样。
     */
    fun preferredSource(
        playable: Map<MusicSource, Int>,
        totals: Map<MusicSource, Int>,
        anchorSource: MusicSource,
    ): MusicSource? {
        if (totals.values.sum() == 0) return null
        val playableSources = playable.filterValues { it > 0 }.keys
        if (playableSources.size == 1) return playableSources.first()
        if (playableSources.isEmpty()) return anchorSource
        // 两侧都有可播放内容：保持主源（它在 playableSources 里就选它，否则选可播放最多的那一源）。
        if (anchorSource in playableSources) return anchorSource
        return playable.maxByOrNull { it.value }?.key ?: anchorSource
    }

    /** 「为什么默认这一源」的一句话；没有特别理由时返回空串（**不编理由**）。 */
    fun availabilityNote(
        playable: Map<MusicSource, Int>,
        totals: Map<MusicSource, Int>,
    ): String {
        if (totals.values.sum() == 0) return ""
        val unavailable = totals.filter { (playable[it.key] ?: 0) == 0 }.keys
        if (unavailable.size != 1) return ""
        val source = unavailable.first()
        val other = MusicSource.otherThan(source) ?: return ""
        if ((playable[other] ?: 0) == 0) return ""
        return "此源（${source.key}）本次探测没有可播放曲目，已默认切到 ${other.key}"
    }

    // ================================================================== IO ==================================================================

    /**
     * 批量可用性探测（v4.1.12：**按侧**限流，见 [PROBE_LIMIT]）。
     *
     * 两侧分开传进来不是为了省参数，而是因为预算必须按侧独立：
     * 艺人页 ncm 一侧现在有 100~200 首，只要还按「合并后取前 N 首」算，
     * QQ 那一侧就永远排在 ncm 之后、永远探不到（详见 [PROBE_LIMIT]）。
     */
    private suspend fun probe(
        neSongs: List<SongItem>,
        qqSongs: List<SongItem>,
    ): Map<TrackKey, TrackAvailability> {
        val ne = neSongs.distinctBy { TrackKey.fromSong(it) }.take(PROBE_LIMIT)
        val qq = qqSongs.distinctBy { TrackKey.fromSong(it) }.take(PROBE_LIMIT)
        if (ne.isEmpty() && qq.isEmpty()) return emptyMap()
        // 单侧空的时候**不发请求**：`probeAvailability(emptyList())` 现在也返回空 Map，
        // 但少一次「必然白跑」的调用，且不会在日志里留下一条误导性的失败。
        val neResult = if (ne.isEmpty()) emptyMap() else {
            runCatching { NeteaseAvailabilityApi.probeAvailability(ne) }
                .onFailure { Log.w(TAG, "netease probe failed", it) }.getOrDefault(emptyMap())
        }
        val qqResult = if (qq.isEmpty()) emptyMap() else {
            runCatching { QqCatalogApi.probeAvailability(qq) }
                .onFailure { Log.w(TAG, "qq probe failed", it) }.getOrDefault(emptyMap())
        }
        return neResult + qqResult
    }

    /**
     * 艺人页要从**哪几侧**取专辑/曲目（H06 的纯逻辑落点，单测直接钉）。
     *
     * 主源永远在内；对端只在「确实找到另一源的同一艺人」且源不同时才加入。
     * 旧实现没有这一层 —— 取哪几侧是由 `if (锚点 == ncm)` 这样的分支在三个地方
     * 各写一遍拼出来的，漏写一处就是「列表少一半」（H06 正是漏了 QQ 主源那一侧）。
     */
    fun artistSides(anchor: ArtistKey, counterpart: ArtistKey?): List<ArtistKey> =
        listOf(anchor) + listOfNotNull(counterpart?.takeIf { it.source != anchor.source })

    /** 专辑页的同一件事（见 [artistSides]）。 */
    fun albumSides(anchor: AlbumKey, counterpart: AlbumKey?): List<AlbumKey> =
        listOf(anchor) + listOfNotNull(counterpart?.takeIf { it.source != anchor.source })

    /**
     * 某一侧的**专辑列表**。分派只看传入身份的 source —— 调用方不再自己写 if。
     */
    private suspend fun albumsOfSide(key: ArtistKey): List<QqAlbum> = when (key.source) {
        MusicSource.NETEASE -> neArtistAlbums(key.id)
        MusicSource.QQMUSIC -> qqAlbumsOf(key.id)
        // B 站没有「艺人页专辑列表」这个概念（v3.1.0 起它只有曲目与歌单）。
        // 不是 `else`：将来再加音源时编译器会把这一处重新指出来（同 [albumsOfSide] 里 `BILIBILI -> emptyList()` 的纪律）。
        MusicSource.BILIBILI -> emptyList()
    }

    /** 某一侧的**曲目列表**（同上）。 */
    private suspend fun songsOfSide(key: ArtistKey): List<SongItem> = when (key.source) {
        MusicSource.NETEASE -> neteaseArtistSongs(key)
        MusicSource.QQMUSIC -> qqArtistSongs(key)
        MusicSource.BILIBILI -> emptyList()
    }

    /** 某一侧的**专辑曲目**（同上）。 */
    private suspend fun albumSongsOf(source: MusicSource, albumId: String): List<SongItem> =
        when (source) {
            MusicSource.NETEASE -> neAlbumSongs(albumId)
            MusicSource.QQMUSIC -> qqAlbumSongs(albumId)
            MusicSource.BILIBILI -> emptyList()
        }

    private suspend fun neArtistAlbums(artistId: String): List<QqAlbum> = runCatching {
        val id = artistId.toLongOrNull() ?: return@runCatching emptyList()
        val resp = RetrofitClient.api.getArtistAlbums(id, limit = 100, offset = 0)
        (resp.hotAlbums ?: emptyList()).map {
            QqAlbum(
                id = it.id ?: 0L,
                mid = (it.id ?: 0L).toString(),
                name = it.name.orEmpty(),
                trackCount = it.size,
                publishDate = it.publishTime?.let { ms -> yearOf(ms) },
                // ncm 的 `/api/artist/albums/{id}` **不返回厂牌**（实测：`ArtistAlbumItem`
                // 只有 id/name/picUrl/publishTime/size/artist）。这里如实留 null，
                // 不去别处补一个「看起来像厂牌」的字段 —— 探针已经证明两源的 company
                // 口径不同（8/34 一致），它本来就不该参与判定。
                company = null,
                singerName = it.artist?.name,
                // E03：ncm 的封面是**这个字段**给的。聚合层不能拿它的十进制 id 去拼
                // 腾讯的 `M000<albumMID>.jpg` 模板 —— 那是「封面归属错源」。
                coverUrl = it.picUrl,
            )
        }
    }.onFailure { Log.w(TAG, "ne artist albums failed", it) }.getOrDefault(emptyList())

    private suspend fun qqAlbumsOf(singerMid: String): List<QqAlbum> =
        runCatching { QqCatalogApi.artistAlbums(singerMid) }
            .onFailure { Log.w(TAG, "qq artist albums failed", it) }.getOrDefault(emptyList())

    /**
     * 艺人页 ncm 一侧的曲目（v4.1.12 · 单曲不再截断到 30 首）。
     *
     * 主路径是**按艺人 id** 分页取（[neArtistSongsById]），失败/取空才回落到旧实现
     * （按艺人名搜一次、上限 30 首）。两个理由：
     *
     * 1. 名字搜索**必然**混进别人的翻唱与同名艺人 —— 那句
     *    `artists.any { name == anchor.name }` 只能滤掉「列表里的艺名不匹配」；
     * 2. 名字搜索的页上限是 30，这是用户报的「歌手详情里单曲只有三十首」的直接原因。
     *
     * 回落必须打一行 `Log.i`：这是「个别艺人 id 取不到」的兜底路径，
     * 线上真出现时，唯一能回答「为什么这个艺人的列表又变回 30 首」的线索就是它。
     */
    private suspend fun neteaseArtistSongs(anchor: ArtistKey): List<SongItem> {
        val artistId = anchor.id.toLongOrNull()?.takeIf { it > 0L }
        var reason = if (artistId == null) "艺人 id 不是数字（${anchor.id}）" else ""
        val byId = if (artistId == null) {
            emptyList()
        } else {
            runCatching { neArtistSongsById(artistId) }
                .onFailure { Log.w(TAG, "ne artist songs by id failed", it) }
                .getOrElse {
                    reason = "按 id 取歌抛异常（${it.message ?: it.javaClass.simpleName}）"
                    emptyList()
                }
        }
        if (byId.isNotEmpty()) return byId

        Log.i(
            TAG,
            "ncm 艺人曲目回落到按名字搜索：${anchor.name}（id=${anchor.id}）；原因：" +
                reason.ifEmpty { "按 id 一页都没取到（接口失败或该艺人确实没有曲目）" },
        )
        // 回落路径**保持改动前的原样**（limit=30 + 艺名精确过滤）：它是兜底，不是主路径，
        // 不在这条路上偷偷扩大口径 —— 否则「回落到搜索」这件事会变得比主路径还难解释。
        return runCatching {
            RetrofitClient.api.search(keyword = anchor.name, type = 1, limit = 30).result?.songs.orEmpty()
                .filter { s -> s.artists?.any { it.name == anchor.name } == true }
        }.onFailure { Log.w(TAG, "ne artist songs by name failed", it) }.getOrDefault(emptyList())
    }

    /**
     * 按艺人 id 取 ncm 曲目：分页取 id → 一次批量详情 → 按热门顺序摆回。
     *
     * 详情走 `/eapi/v3/song/detail`（[PlaylistApi.getSongsByIdsOrNull]）而不是自己解析
     * `/eapi/v1/artist/songs` 的 `songs[]`：那个端点回的是**旧形状**
     * （`artists[]` / `album` / `duration`），而 `parseSongTrack` 是 ncm 曲目的唯一映射，
     * 它顺带把 `privilege` / `originCoverType` 带出来 —— 在这里写第二套映射，
     * 「同一首歌在艺人页和其他页字段不一样」是迟早的事。
     * 代价是多一次请求（实测 100 首详情 217KB / 200 首 428KB）。
     */
    private suspend fun neArtistSongsById(artistId: Long): List<SongItem> {
        val ids = collectArtistSongIds { limit, offset ->
            PlaylistApi.getArtistSongIdsOrNull(artistId, limit, offset)
        }
        if (ids.isEmpty()) return emptyList()
        val songs = PlaylistApi.getSongsByIdsOrNull(ids) ?: return emptyList()
        return orderSongsByIds(ids, songs)
    }

    /**
     * 分页收集艺人曲目 id（v4.1.12）。**无网络**：取一页的函数由调用方注入，单测直接钉。
     *
     * 停条件（任一成立即停，绝不无限翻）：
     * 1. 取页失败（`null`）—— 保留已拿到的部分，不让一页失败把整页干掉；
     * 2. 空页 —— 服务端确认没有更多（实测越界 offset 回 0 条 + `more=false`）；
     * 3. 短页 —— 这一页不满 [pageSize]，说明已经到底；
     * 4. 整页都是重复 id —— 偏移分页「回卷」了，继续翻只会把同样的歌再取一遍；
     * 5. 到 [maxPages] 上限。
     *
     * @return 去重后的 id，保持服务端返回顺序（= 热门顺序）。
     */
    internal suspend fun collectArtistSongIds(
        pageSize: Int = ARTIST_SONGS_PAGE,
        maxPages: Int = ARTIST_SONGS_MAX_PAGES,
        fetchPage: suspend (limit: Int, offset: Int) -> List<Long>?,
    ): List<Long> {
        if (pageSize <= 0 || maxPages <= 0) return emptyList()
        val out = ArrayList<Long>(pageSize * maxPages)
        val seen = HashSet<Long>(pageSize * maxPages)
        for (page in 0 until maxPages) {
            val ids = fetchPage(pageSize, page * pageSize) ?: break
            if (ids.isEmpty()) break
            var fresh = 0
            for (id in ids) {
                if (id > 0L && seen.add(id)) {
                    out += id
                    fresh++
                }
            }
            if (ids.size < pageSize) break
            if (fresh == 0) break
        }
        return out
    }

    /**
     * 把批量详情的结果**按热门顺序摆回去**（v4.1.12，纯函数）。
     *
     * 实测 `/eapi/v3/song/detail` 会按请求里 `c` 的顺序返回（2026-11-24，100 首与 200 首
     * 各测一次，顺序完全一致），但那是**观察**不是契约。一旦服务端换了顺序，
     * 艺人页的「热门顺序」会静默变成「详情接口的顺序」，而没人会把这当成 bug 报上来。
     * 所以这里显式按 id 重排一次，顺带丢掉响应里多出来的曲目与重复 id。
     */
    internal fun orderSongsByIds(ids: List<Long>, songs: List<SongItem>): List<SongItem> {
        if (ids.isEmpty() || songs.isEmpty()) return emptyList()
        val byId = HashMap<Long, SongItem>(songs.size)
        for (song in songs) {
            if (song.id > 0L && !byId.containsKey(song.id)) byId[song.id] = song
        }
        val out = ArrayList<SongItem>(ids.size)
        val emitted = HashSet<Long>(ids.size)
        for (id in ids) {
            if (!emitted.add(id)) continue
            byId[id]?.let { out += it }
        }
        return out
    }

    private suspend fun qqArtistSongs(anchor: ArtistKey): List<SongItem> =
        runCatching { QqCatalogApi.artistSongs(anchor.id) }
            .onFailure { Log.w(TAG, "qq artist songs failed", it) }.getOrDefault(emptyList())

    private suspend fun neAlbumSongs(albumId: String): List<SongItem> = runCatching {
        val id = albumId.toLongOrNull() ?: return@runCatching emptyList()
        RetrofitClient.api.getAlbumDetail(id).songs.orEmpty().map { s ->
            SongItem(
                id = s.id,
                name = s.name,
                artists = s.artists,
                album = s.album,
                duration = s.getDurationMs(),
                source = MusicSource.NETEASE.key,
            )
        }
    }.onFailure { Log.w(TAG, "ne album songs failed", it) }.getOrDefault(emptyList())

    private suspend fun qqAlbumSongs(albumMid: String): List<SongItem> =
        runCatching { QqCatalogApi.albumDetail(albumMid)?.songs.orEmpty() }
            .onFailure { Log.w(TAG, "qq album songs failed", it) }.getOrDefault(emptyList())

    // ---------------------------------------------------------- 对端查找 ----

    /** 找到另一源上的同一艺人：**召回 → 逐个校验（≤3）→ 取最好的一档**。 */
    private suspend fun findCounterpartArtist(
        context: Context,
        anchor: ArtistKey,
        anchorAlbums: List<QqAlbum>,
    ): Counterpart<ArtistKey, QqAlbum>? {
        val other = MusicSource.otherThan(anchor.source) ?: return null
        // 缓存优先：命中就不再发请求。
        runCatching { MatchCacheStore.artist(context, anchor) }.getOrNull()?.let { cached ->
            val alias = cached.aliases.firstOrNull() ?: return@let
            return Counterpart(
                alias,
                MatchVerdict(cached.confidence, cached.reason),
                cached.albumOverlap,
            )
        }

        val searchLimit = if (other == MusicSource.QQMUSIC) {
            runCatching { QqCatalogApi.searchArtists(anchor.name, 20) }.getOrDefault(emptyList())
                .map { CrossSourceMatcher.ArtistCandidate(ArtistKey(other, it.mid, it.name), emptyList(), it.albumCount) }
        } else {
            runCatching {
                RetrofitClient.api.searchArtist(keyword = anchor.name, limit = 20).result?.artists.orEmpty()
            }.getOrDefault(emptyList()).map {
                CrossSourceMatcher.ArtistCandidate(
                    ArtistKey(other, (it.id ?: 0L).toString(), it.name),
                    emptyList(),
                    it.albumSize ?: 0,
                )
            }
        }
        val recalled = CrossSourceMatcher.recallArtists(anchor.name, searchLimit)
        if (recalled.isEmpty()) return null
        val topHint = recalled.first().albumCountHint
        val unique = recalled.count { it.albumCountHint == topHint } == 1
        val anchorNames = anchorAlbums.map { it.name }

        var best: Counterpart<ArtistKey, QqAlbum>? = null
        for (candidate in recalled.take(CrossSourceMatcher.MAX_ARTIST_CANDIDATES)) {
            val albums = albumsOfSide(candidate.key)
            val withAlbums = candidate.copy(albumNames = albums.map { it.name })
            val verdict = CrossSourceMatcher.gradeArtist(anchor.name, anchorNames, withAlbums, unique)
            val overlap = withAlbums.albumNames.mapNotNull { NameNormalizer.normalizeName(it).takeIf { n -> n.isNotEmpty() } }
                .toSet().let { set -> anchorNames.count { NameNormalizer.normalizeName(it) in set } }
            if (best == null || rank(verdict.confidence) > rank(best.verdict.confidence)) {
                best = Counterpart(candidate.key, verdict, overlap, albums)
            }
            if (verdict.confidence == MatchConfidence.EXACT) break
        }
        return best
    }

    /** 找到另一源上的同一张专辑：按专辑名搜一次 → 逐个校验（≤3）。 */
    /**
     * 对端召回的搜索关键词（v3.4.11）：**专辑名 + 歌手名**。
     *
     * 只按专辑名搜会召回一堆同名专辑（原版 / 精选 / 现场 / 翻唱），
     * 而候选只评估前 3 个 —— 正主排在第 4 位以后就等于没配上。
     * 两个平台的搜索接口都支持空格分隔的多词，加了歌手不会让正主消失。
     *
     * 歌手为空时（路由与缓存都没给）**原样返回专辑名**：与 v3.4.11 之前逐字相同，
     * 不引入一个「空串拼进去变成前导空格」的新形状。
     */
    private fun searchKeywordOf(anchor: AlbumKey): String {
        val artist = anchor.artistKey?.name?.trim().orEmpty()
        return if (artist.isEmpty()) anchor.name else anchor.name + " " + artist
    }

    private suspend fun findCounterpartAlbum(
        context: Context,
        anchor: AlbumKey,
        anchorSongs: List<SongItem>,
    ): Counterpart<AlbumKey, SongItem>? {
        val other = MusicSource.otherThan(anchor.source) ?: return null
        runCatching { MatchCacheStore.album(context, anchor) }.getOrNull()?.let { cached ->
            val alias = cached.aliases.firstOrNull() ?: return@let
            return Counterpart(
                alias,
                MatchVerdict(cached.confidence, cached.reason),
                cached.trackOverlap,
            )
        }

        val anchorNames = anchorSongs.map { it.name }
        val anchorArtist = anchor.artistKey?.name?.takeIf { it.isNotEmpty() }
        val anchorCandidate = CrossSourceMatcher.AlbumCandidate(
            anchor, anchorNames, null, anchor.name, listOfNotNull(anchorArtist),
        )
        val candidates: List<QqAlbum> = when (other) {
            // 结构上不可达（同 findCounterpartAlbum）；显式分支见 [albumsOfSide] 的说明。
            MusicSource.BILIBILI -> emptyList()
            // v3.4.11：搜索关键词是**专辑名 + 歌手**（此前只有专辑名）。
            //
            // 为什么加歌手：同名专辑（原版 / 精选 / 现场 / 翻唱合集）在两个平台上同时
            // 存在，只按名字搜回来的候选里正主可能排在很后面，而后面 `take(3)` 只评估
            // 3 个 —— 翻唱合集一旦进来就可能被选中，且界面还会显示一个「已匹配」的置信度。
            // 两个平台的搜索接口都支持空格分隔的多词（实测 `范特西 周杰伦` 能搜到），
            // 而**加了词不会让正主消失**（它本来就在结果里）—— 所以这一步是净收益。
            MusicSource.QQMUSIC -> runCatching {
                QqCatalogApi.searchAlbums(searchKeywordOf(anchor), 10)
            }.getOrDefault(emptyList())
            MusicSource.NETEASE -> runCatching {
                RetrofitClient.api.searchAlbum(keyword = searchKeywordOf(anchor), limit = 10)
                    .result?.albums.orEmpty()
            }.getOrDefault(emptyList()).map {
                QqAlbum(
                    id = it.id ?: 0L,
                    mid = (it.id ?: 0L).toString(),
                    name = it.name.orEmpty(),
                    trackCount = it.size,
                    singerName = it.artist?.name,
                )
            }
        }

        var best: Counterpart<AlbumKey, SongItem>? = null
        for (c in candidates.take(3)) {
            // 两侧都提供歌手且明确冲突时，曲目列表无法改变候选结论。
            if (AlbumLookup.artistVerdict(anchorArtist, c.singerName) == AlbumLookup.ArtistVerdict.CONFLICT) continue
            val tracks = albumSongsOf(other, c.mid)
            // H05：候选的证据**只能来自候选自己**（见 [albumCandidateOf]）。
            // 信息缺失（QQ 搜索偶尔不给 singerName）会如实落到 LOW（不合并），
            // 那正是铁律 17 要的「拿不准就分开展示」。
            val candidate = albumCandidateOf(c, other, tracks.map { it.name })
            val ratio = CrossSourceMatcher.trackOverlapRatio(anchorNames, candidate.trackNames)
            val verdict = CrossSourceMatcher.gradeAlbum(anchorCandidate, candidate, ratio)
            val overlap = candidate.trackNames.mapNotNull { NameNormalizer.normalizeName(it).takeIf { n -> n.isNotEmpty() } }
                .toSet().let { set -> anchorNames.count { NameNormalizer.normalizeName(it) in set } }
            if (best == null || rank(verdict.confidence) > rank(best.verdict.confidence)) {
                best = Counterpart(candidate.key, verdict, overlap, tracks)
            }
            if (verdict.confidence == MatchConfidence.EXACT) break
        }
        return best
    }

    /** 在另一源搜同名曲目（单曲页用）。 */
    private suspend fun searchTracks(
        source: MusicSource,
        song: SongItem,
    ): List<CrossSourceMatcher.TrackCandidate> {
        val keyword = listOfNotNull(song.name.takeIf { it.isNotBlank() }, song.artists?.firstOrNull()?.name)
            .joinToString(" ")
        if (keyword.isBlank()) return emptyList()
        return when (source) {
            MusicSource.QQMUSIC -> runCatching {
                // 复用既有的 QQ 搜索（旧版 GET 优先 + `musicu` 兜底），
                // 不再写第二条搜索路径 —— 两套搜索必然漂移。
                com.takahashirinta.ncrust.qq.QqApi.searchSongs(keyword, limit = 20)
            }.getOrDefault(emptyList())
            MusicSource.NETEASE -> runCatching {
                RetrofitClient.api.search(keyword = keyword, type = 1, limit = 20).result?.songs.orEmpty()
            }.getOrDefault(emptyList())
            // v3.1.0：B 站的「对端查找」不做 —— 音频区的搜索命中率与 ncm/QQ 的曲目
            // 不是同一套命名（标题里常年带【】与翻唱标注），跨源匹配的假阳性会直接
            // 体现为「单曲页推荐了另一首歌」。宁可没有对端，也不给错的对端。
            MusicSource.BILIBILI -> emptyList()
        }.map { trackCandidateOf(it) }
    }

    // ================================================================== 转换 ==================================================================

    /** `SongItem` → 匹配用的候选（纯）。 */
    fun trackCandidateOf(song: SongItem): CrossSourceMatcher.TrackCandidate =
        CrossSourceMatcher.TrackCandidate(
            key = TrackKey.fromSong(song),
            name = song.name,
            artists = song.artists?.mapNotNull { it.name } ?: emptyList(),
            durationMs = song.duration?.takeIf { it > 0L },
            albumName = song.album?.name,
        )

    /** 匹配候选 → 可展示的 `SongItem`（补齐音源字段，别让下游把它当成 ncm）。 */
    fun candidateToSong(candidate: CrossSourceMatcher.TrackCandidate): SongItem? {
        if (candidate.name.isBlank()) return null
        return songRefOf(
            source = candidate.key.source,
            id = candidate.key.id,
            sourceId = candidate.key.sourceId,
            mediaId = candidate.key.mediaId,
        ).copy(
            name = candidate.name,
            artists = candidate.artists.map { ArtistItem(id = null, name = it) }.takeIf { it.isNotEmpty() },
            album = candidate.albumName?.let { AlbumItem(id = null, name = it, picUrl = null) },
            duration = candidate.durationMs,
        )
    }

    /**
     * 专辑候选的**唯一构造点**（H05）。
     *
     * @param trackNames 专辑页取到的候选曲目名。艺人页只有专辑列表，传空列表。
     *
     * ⚠️ **歌手证据只能来自候选自己**：这里的 `artistNames` 只放 `album.singerName`。
     * 曾经这里被写成 `listOfNotNull(album.singerName, anchorArtist)` —— 把锚点的歌手塞进
     * 候选的艺名列表，而 `CrossSourceMatcher.gradeAlbum` 判的正是两边列表的交集，
     * 于是「艺人一致」这一条**必然成立**，同名不同歌手的专辑也能拿到 HIGH 并合并。
     * 锚点的证据由调用方（`findCounterpartAlbum`）放在锚点自己的候选里。
     */
    internal fun albumCandidateOf(
        album: QqAlbum,
        source: MusicSource,
        trackNames: List<String> = emptyList(),
    ) = CrossSourceMatcher.AlbumCandidate(
        key = AlbumKey(source, album.mid, album.name),
        trackNames = trackNames,
        trackCount = album.trackCount,
        rawName = album.name,
        artistNames = listOfNotNull(album.singerName),
    )

    /**
     * 毫秒时间戳 → 年份。
     *
     * ncm 的 `publishTime` 是**毫秒**、QQ 的 `pubTime` 是 `YYYY-MM-DD` 字符串
     * （`probe-catalog-api.md`）。两源不能直接比，展示层统一成年份字符串，
     * 免得 UI 上一边显示 `1059580800000`、一边显示 `2003-07-31`。
     */
    internal fun yearOf(epochMs: Long): String? {
        if (epochMs <= 0L) return null
        return java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
            .get(java.util.Calendar.YEAR).toString()
    }

    /**
     * 专辑行的封面（E03）。
     *
     * 两个平台的封面**不是同一个规则**：QQ 的封面是由 `albumMID` 拼一个图片模板，
     * ncm 的是接口直接给的 `picUrl`（`/api/artist/albums/{id}` 的字段）。
     *
     * 旧实现无条件调用 `coverOf(mid)`，于是网易专辑的**十进制专辑 id** 被填进腾讯的
     * `T002R500x500M000<mid>.jpg` —— 请求到的地址与这张专辑没有任何关系（不是 404，
     * 就是别人的图），封面归属与专辑源不一致。
     *
     * 现在：接口给了封面就用它（[QqAlbum.coverUrl]，ncm 解析器会填），否则**只有 QQ**
     * 才拼模板。ncm 没给封面时返回 null —— UI 显示占位图，而不是一张错源的图。
     */
    private fun coverOf(album: QqAlbum, source: MusicSource): String? {
        album.coverUrl?.takeIf { it.isNotBlank() }?.let { return it }
        if (source != MusicSource.QQMUSIC) return null
        return album.mid.takeIf { it.isNotEmpty() }?.let { QQ_ALBUM_COVER_TEMPLATE.format(it) }
    }

    /** QQ 的专辑封面模板（`albumMID` → 500×500）。ncm 的封面**不能**走它。 */
    private const val QQ_ALBUM_COVER_TEMPLATE = "https://y.qq.com/music/photo_new/T002R500x500M000%s.jpg"

    private fun rank(confidence: MatchConfidence): Int = when (confidence) {
        MatchConfidence.EXACT -> 4
        MatchConfidence.HIGH -> 3
        MatchConfidence.MEDIUM -> 2
        MatchConfidence.LOW -> 1
        MatchConfidence.NONE -> 0
    }
}
