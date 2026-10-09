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
    fun keyOf(source: MusicSource): AlbumKey? = (listOf(key) + aliases).firstOrNull { it.source == source }
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
    val availabilityNote: String = "",
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
 * | 艺人页 | 该艺人的**曲目列表**（两源合并后 ≤60 首） | ncm ≤1 批 + QQ ≤2 批 | **不逐张专辑探测** —— 44 张专辑 × 2 源 = 88 次请求，艺人页会变成不可用。专辑行的音源偏好因此由**艺人级**的可播放比例决定 |
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

    /** 一页最多探测多少首。超过的部分留在 `UNKNOWN`（宁可少标，不拖住页面）。 */
    private const val PROBE_LIMIT = 60

    // ================================================================== 艺人页 ==================================================================

    /**
     * 拉取并聚合一个艺人页。
     *
     * @param anchor 用户点进来的那个艺人身份。**它永远是主身份**（标题、头像、默认口径都用它）。
     */
    suspend fun loadArtist(context: Context, anchor: ArtistKey): ArtistPage {
        val anchorAlbums = neOrQqAlbums(anchor)
        val candidate = findCounterpartArtist(context, anchor, anchorAlbums)

        val merged = MergedArtist(
            primary = anchor,
            aliases = candidate?.takeIf { it.second.mergeable }?.let { listOf(it.first.key) }.orEmpty(),
            name = anchor.name,
            confidence = candidate?.second?.confidence ?: MatchConfidence.NONE,
            reason = candidate?.second?.reason.orEmpty(),
            albumOverlap = candidate?.third ?: 0,
        )
        if (candidate != null && candidate.second.mergeable) {
            runCatching { MatchCacheStore.putArtist(context, merged) }
        }

        // ---- 两源的专辑列表 ----
        val neAlbums = if (anchor.source == MusicSource.NETEASE) anchorAlbums else emptyList()
        val qqAlbums = if (anchor.source == MusicSource.QQMUSIC) anchorAlbums else
            candidate?.first?.let { qqAlbumsOf(it.key.id) }.orEmpty()
        val albums = if (anchor.source == MusicSource.NETEASE) {
            assembleAlbums(neAlbums, MusicSource.NETEASE, qqAlbums, MusicSource.QQMUSIC)
        } else {
            assembleAlbums(qqAlbums, MusicSource.QQMUSIC, neAlbums, MusicSource.NETEASE)
        }

        // ---- 两源的曲目列表 + 可用性 ----
        val neSongs = if (anchor.source == MusicSource.NETEASE) neteaseArtistSongs(anchor) else emptyList()
        val qqSongs = if (anchor.source == MusicSource.QQMUSIC) qqArtistSongs(anchor) else
            candidate?.first?.let { qqArtistSongs(it.key) }.orEmpty()
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
        val anchorTracks = albumTrackCandidates(anchor)
        val counterpart = findCounterpartAlbum(context, anchor, anchorTracks)

        val merged = MergedAlbum(
            primary = anchor,
            aliases = counterpart?.takeIf { it.second.mergeable }?.let { listOf(it.first.key) }.orEmpty(),
            name = anchor.name,
            confidence = counterpart?.second?.confidence ?: MatchConfidence.NONE,
            reason = counterpart?.second?.reason.orEmpty(),
            trackOverlap = counterpart?.third ?: 0,
        )
        if (counterpart != null && counterpart.second.mergeable) {
            runCatching { MatchCacheStore.putAlbum(context, merged) }
        }

        val neSongs = if (anchor.source == MusicSource.NETEASE) neAlbumSongs(anchor.id) else emptyList()
        val qqSongs = if (anchor.source == MusicSource.QQMUSIC) qqAlbumSongs(anchor.id) else
            counterpart?.first?.let { qqAlbumSongs(it.key.id) }.orEmpty()

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
        val probed = runCatching { probe(versions.map { it.song }) }.getOrDefault(emptyMap())
        val withAvail = versions.map { v ->
            v.copy(
                availability = probed[TrackKey.fromSong(v.song)] ?: TrackAvailability.UNKNOWN,
                availabilityReason = if (probed.containsKey(TrackKey.fromSong(v.song))) "预检" else "未探测",
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
            availabilityNote = availabilityNote(
                withAvail.groupingBy { it.song.musicSource }.eachCount()
                    .filterValues { it > 0 }.mapValues { it.value },
                withAvail.groupingBy { it.song.musicSource }.eachCount(),
            ),
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
        val used = HashSet<String>()
        val out = ArrayList<AggregatedAlbum>(primary.size)
        for (a in primary) {
            var best: Pair<QqAlbum, MatchVerdict>? = null
            for (b in secondary) {
                if (b.mid in used) continue
                val verdict = CrossSourceMatcher.gradeAlbum(
                    albumCandidateOf(a, primarySource),
                    albumCandidateOf(b, secondarySource),
                    trackOverlapRatio = trackRatioOf(a, b),
                )
                if (verdict.confidence == MatchConfidence.NONE) continue
                if (best == null || rank(verdict.confidence) > rank(best.second.confidence)) {
                    best = b to verdict
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
                picUrl = coverOf(a.mid),
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
                picUrl = coverOf(b.mid),
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
        val probed = runCatching { probe(neSongs + qqSongs) }.getOrDefault(emptyMap())
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

        val pairs = CrossSourceMatcher.pairTracks(
            anchor = neSongs.map { trackCandidateOf(it) },
            other = qqSongs.map { trackCandidateOf(it) },
        )
        // ★ 只有**真的合并了**（mergeable）的次源曲目才从列表里消失。
        //   第一版把「配上了但置信度不够」的也算进去，结果是：一首 LOW 匹配的 QQ 曲目
        //   既没有作为合并结果出现、也没有作为独立行出现 —— **凭空少了一首歌**。
        //   那正是铁律 17 要防的反面：匹配不确定时应该「分开展示」，而不是「消失」。
        val mergeablePairs = pairs.filter { it.verdict.mergeable }
        val pairByNe = pairs.associateBy { it.anchor.key }
        val absorbedQq = mergeablePairs.map { it.other.key }.toHashSet()
        val qqByKey = qqSongs.associateBy { TrackKey.fromSong(it) }

        for (s in neSongs) {
            val key = TrackKey.fromSong(s)
            val pair = pairByNe[key]
            val mergeable = pair?.verdict?.mergeable == true
            val otherKey = pair?.other?.key
            val otherAvailability = otherKey?.let { probed[it] } ?: TrackAvailability.UNKNOWN
            val selfAvailability = probed[key] ?: TrackAvailability.UNKNOWN

            // ★ 合并之后「这一行放哪一源的哪一首」由**可播放性**决定（特性 D 的核心）：
            //   ncm 无版权、QQ 能播时，这一行的 `song` 必须是 QQ 那一首 ——
            //   否则界面上标着「可播放」、点下去播的还是那个播不了的版本。
            //   平级时保持主源（用户是从主源进来的）。
            val preferOther = mergeable && otherKey != null &&
                CrossSourceMatcher.availabilityOrder(otherAvailability) <
                CrossSourceMatcher.availabilityOrder(selfAvailability) &&
                qqByKey.containsKey(otherKey)

            val displaySong = if (preferOther) qqByKey.getValue(otherKey!!) else s
            val displayKey = TrackKey.fromSong(displaySong)
            val displayAvailability = if (preferOther) otherAvailability else selfAvailability
            val otherKeys = when {
                !mergeable -> emptyList()
                preferOther -> listOf(key)
                else -> listOfNotNull(otherKey)
            }
            rows += AggregatedSong(
                song = displaySong,
                availability = displayAvailability,
                confidence = pair?.verdict?.confidence ?: MatchConfidence.NONE,
                matchReason = pair?.verdict?.reason.orEmpty(),
                mergedKeys = otherKeys,
                availabilityReason = if (probed.containsKey(displayKey)) "预检" else "未探测",
            )
        }
        for (s in qqSongs) {
            val key = TrackKey.fromSong(s)
            if (key in absorbedQq) continue // 已经作为 ncm 那一行的合并结果出现
            rows += AggregatedSong(
                song = s,
                availability = probed[key] ?: TrackAvailability.UNKNOWN,
                mergedKeys = emptyList(),
                availabilityReason = if (probed.containsKey(key)) "预检" else "未探测",
            )
        }

        val ranked = CrossSourceMatcher.rankByAvailability(rows) { it.availability }
        val playable = ranked.filter { it.availability == TrackAvailability.PLAYABLE }
            .groupingBy { it.song.musicSource }.eachCount()
        val totals = ranked.groupingBy { it.song.musicSource }.eachCount()
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

    private suspend fun probe(songs: List<SongItem>): Map<TrackKey, TrackAvailability> {
        val limited = songs.distinctBy { TrackKey.fromSong(it) }.take(PROBE_LIMIT)
        if (limited.isEmpty()) return emptyMap()
        val ne = runCatching { NeteaseAvailabilityApi.probeAvailability(limited) }
            .onFailure { Log.w(TAG, "netease probe failed", it) }.getOrDefault(emptyMap())
        val qq = runCatching { QqCatalogApi.probeAvailability(limited) }
            .onFailure { Log.w(TAG, "qq probe failed", it) }.getOrDefault(emptyMap())
        return ne + qq
    }

    private suspend fun neOrQqAlbums(anchor: ArtistKey): List<QqAlbum> =
        if (anchor.source == MusicSource.NETEASE) neArtistAlbums(anchor.id) else qqAlbumsOf(anchor.id)

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
            )
        }
    }.onFailure { Log.w(TAG, "ne artist albums failed", it) }.getOrDefault(emptyList())

    private suspend fun qqAlbumsOf(singerMid: String): List<QqAlbum> =
        runCatching { QqCatalogApi.artistAlbums(singerMid) }
            .onFailure { Log.w(TAG, "qq artist albums failed", it) }.getOrDefault(emptyList())

    private suspend fun neteaseArtistSongs(anchor: ArtistKey): List<SongItem> = runCatching {
        RetrofitClient.api.search(keyword = anchor.name, type = 1, limit = 30).result?.songs.orEmpty()
            .filter { s -> s.artists?.any { it.name == anchor.name } == true }
    }.onFailure { Log.w(TAG, "ne artist songs failed", it) }.getOrDefault(emptyList())

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

    private suspend fun albumTrackCandidates(anchor: AlbumKey): List<CrossSourceMatcher.TrackCandidate> =
        when (anchor.source) {
            MusicSource.NETEASE -> neAlbumSongs(anchor.id).map { trackCandidateOf(it) }
            MusicSource.QQMUSIC -> qqAlbumSongs(anchor.id).map { trackCandidateOf(it) }
            // v3.1.0：B 站没有「专辑」这个概念（音频区只有单曲与歌单），跨源匹配对它无意义。
            // 这里**不是** `else`：将来再加音源时编译器会再把这一处指出来（v2.6.2 的纪律）。
            MusicSource.BILIBILI -> emptyList()
        }

    // ---------------------------------------------------------- 对端查找 ----

    /** 找到另一源上的同一艺人：**召回 → 逐个校验（≤3）→ 取最好的一档**。 */
    private suspend fun findCounterpartArtist(
        context: Context,
        anchor: ArtistKey,
        anchorAlbums: List<QqAlbum>,
    ): Triple<CrossSourceMatcher.ArtistCandidate, MatchVerdict, Int>? {
        val other = MusicSource.otherThan(anchor.source) ?: return null
        // 缓存优先：命中就不再发请求。
        runCatching { MatchCacheStore.artist(context, anchor) }.getOrNull()?.let { cached ->
            val alias = cached.aliases.firstOrNull() ?: return@let
            return Triple(
                CrossSourceMatcher.ArtistCandidate(alias, emptyList()),
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

        var best: Triple<CrossSourceMatcher.ArtistCandidate, MatchVerdict, Int>? = null
        for (candidate in recalled.take(CrossSourceMatcher.MAX_ARTIST_CANDIDATES)) {
            val albums = when (other) {
                MusicSource.QQMUSIC -> qqAlbumsOf(candidate.key.id)
                MusicSource.NETEASE -> neArtistAlbums(candidate.key.id)
                // 结构上不可达：`other` 来自 MusicSource.otherThan（只遍历 loginSources）。
                // 仍然显式写出来，理由同 albumTrackCandidates。
                MusicSource.BILIBILI -> emptyList()
            }
            val withAlbums = candidate.copy(albumNames = albums.map { it.name })
            val verdict = CrossSourceMatcher.gradeArtist(anchor.name, anchorNames, withAlbums, unique)
            val overlap = withAlbums.albumNames.mapNotNull { NameNormalizer.normalizeName(it).takeIf { n -> n.isNotEmpty() } }
                .toSet().let { set -> anchorNames.count { NameNormalizer.normalizeName(it) in set } }
            if (best == null || rank(verdict.confidence) > rank(best.second.confidence)) {
                best = Triple(withAlbums, verdict, overlap)
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
        anchorTracks: List<CrossSourceMatcher.TrackCandidate>,
    ): Triple<CrossSourceMatcher.AlbumCandidate, MatchVerdict, Int>? {
        val other = MusicSource.otherThan(anchor.source) ?: return null
        runCatching { MatchCacheStore.album(context, anchor) }.getOrNull()?.let { cached ->
            val alias = cached.aliases.firstOrNull() ?: return@let
            return Triple(
                CrossSourceMatcher.AlbumCandidate(alias, emptyList()),
                MatchVerdict(cached.confidence, cached.reason),
                cached.trackOverlap,
            )
        }

        val anchorNames = anchorTracks.map { it.name }
        val anchorArtist = anchor.artistKey?.name.orEmpty()
        val candidates: List<QqAlbum> = when (other) {
            // 结构上不可达（同 findCounterpartAlbum）；显式分支见 albumTrackCandidates 的说明。
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

        var best: Triple<CrossSourceMatcher.AlbumCandidate, MatchVerdict, Int>? = null
        for (c in candidates.take(3)) {
            val tracks = when (other) {
                MusicSource.QQMUSIC -> qqAlbumSongs(c.mid)
                MusicSource.NETEASE -> neAlbumSongs(c.mid)
                MusicSource.BILIBILI -> emptyList()
            }
            val candidate = CrossSourceMatcher.AlbumCandidate(
                key = AlbumKey(other, c.mid, c.name),
                trackNames = tracks.map { it.name },
                trackCount = c.trackCount,
                rawName = c.name,
                artistNames = listOfNotNull(c.singerName, anchorArtist.takeIf { it.isNotEmpty() }),
            )
            val ratio = CrossSourceMatcher.trackOverlapRatio(anchorNames, candidate.trackNames)
            val verdict = CrossSourceMatcher.gradeAlbum(
                CrossSourceMatcher.AlbumCandidate(anchor, anchorNames, null, anchor.name, listOfNotNull(anchorArtist)),
                candidate,
                ratio,
            )
            // v3.4.11：**歌手必须先对上**才允许进入「同名匹配」。
            //
            // 这一步是用户要求的「优先原唱 / 用搜索的歌手名字匹配专辑作者」，而它此前
            // 在结构上做不到：`candidate.artistNames` 里塞的是
            // `listOfNotNull(c.singerName, anchorArtist)` —— **候选自己的歌手与锚点的歌手
            // 被合成了一个列表**，判据分不清「这个候选是同一个人唱的」还是「我们只是想
            // 找一个同歌手的」。现在用 `AlbumLookup` 单独判一次歌手，把它作为
            // 「同名但不同歌手」的排除条件。
            val artistMatches = AlbumLookup.score(
                queryName = anchor.name,
                queryArtist = anchorArtist.takeIf { it.isNotEmpty() },
                candidate = AlbumLookup.Candidate(
                    id = 0L,
                    name = c.name,
                    artistName = c.singerName,
                    trackCount = c.trackCount,
                ),
            )
            // 锚点有歌手、候选也有歌手、但两者对不上 ⇒ **跳过这个候选**（同名不同人）。
            // 只在两边都已知时才排除：信息不足时保持旧行为（宁可少排除，也不要凭猜测丢候选）。
            val artistConflict = anchorArtist.isNotEmpty() &&
                !c.singerName.isNullOrEmpty() &&
                artistMatches == null
            if (artistConflict) continue
            val overlap = candidate.trackNames.mapNotNull { NameNormalizer.normalizeName(it).takeIf { n -> n.isNotEmpty() } }
                .toSet().let { set -> anchorNames.count { NameNormalizer.normalizeName(it) in set } }
            if (best == null || rank(verdict.confidence) > rank(best.second.confidence)) {
                best = Triple(candidate, verdict, overlap)
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

    private fun albumCandidateOf(album: QqAlbum, source: MusicSource) =
        CrossSourceMatcher.AlbumCandidate(
            key = AlbumKey(source, album.mid, album.name),
            trackNames = emptyList(),
            trackCount = album.trackCount,
            rawName = album.name,
            artistNames = listOfNotNull(album.singerName),
        )

    /**
     * 专辑曲目名集合的重合率。
     *
     * 艺人页的专辑列表**没有曲目名**（`/api/artist/albums/{id}` 只给 `size`），
     * 所以这里返回 0 —— 那不是「不重合」，而是「不知道」。
     * 调用方（[assembleAlbums]）因此只能靠「名 + 艺人」判到 `MEDIUM`/`LOW`，
     * **这正是有意的**：艺人页的 44 张专辑里逐张去拉曲目表是 44 次请求，
     * 而专辑页自己会做一次精确的曲目级校验。
     */
    private fun trackRatioOf(a: QqAlbum, b: QqAlbum): Double =
        if (a.name.isNotEmpty() && NameNormalizer.sameName(a.name, b.name)) 1.0 else 0.0

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

    private fun coverOf(mid: String): String? =
        mid.takeIf { it.isNotEmpty() }?.let { "https://y.qq.com/music/photo_new/T002R500x500M000$it.jpg" }

    private fun rank(confidence: MatchConfidence): Int = when (confidence) {
        MatchConfidence.EXACT -> 4
        MatchConfidence.HIGH -> 3
        MatchConfidence.MEDIUM -> 2
        MatchConfidence.LOW -> 1
        MatchConfidence.NONE -> 0
    }
}
