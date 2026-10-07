/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.4.0 · A/B/D：qm 目录接口与**批量可播放性预检**的 IO 层。
 */

package com.takahashirinta.ncrust.qq

import android.util.Log
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.search.TrackAvailability
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.source.musicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * qm 的目录查询（v2.4.0 · A/B）。
 *
 * ## 契约（与 `MusicSourceProvider` 同一套，铁律 5）
 *
 * **绝不抛异常给调用方**：网络失败、风控、形状变化一律返回空列表 / null。
 * 详情页多一个空 tab 是可恢复的体验；让整个页面崩掉不是。
 *
 * ## 为什么这些端点不放进 `MusicSourceProvider`
 *
 * 那个接口的注释写得很清楚：它只抽「两个音源**都有、且语义一致**」的能力，
 * 目的是让「一首歌能放出来」这条链路可路由。艺人与专辑的**目录**查询在
 * ncm 是 Retrofit REST、在 QQ 是 `musicu` + 旧版 CGI，
 * 请求形状与错误语义都不同；硬塞进同一个接口只会得到一个「每个方法都要判音源」的空壳。
 * 跨源聚合的编排放在 `crosssource` 包里，由它按音源分派到各自的门面。
 */
object QqCatalogApi {

    private const val TAG = "QqCatalogApi"

    /** 一次拿多少张专辑。实测 100 能把周杰伦的 43 张一次拿全。 */
    private const val ALBUM_PAGE = 100

    /** 曲目上限。QQ 单张专辑实测最多几十首，200 是安全上限。 */
    private const val TRACK_LIMIT = 200

    /**
     * 一次预检最多几首。
     *
     * 取 40 而不是 100：预检要带 `filename[]`（每首一个 `M500<media_mid>.mp3`），
     * 40 个文件名的请求体约 4KB，仍在 URL/body 的常规范围内；
     * 一屏列表（`DetailScaffold` 的常见首屏）通常 10~30 首，所以 40 覆盖得住，
     * 超出部分由下一批补齐。**这个上限是「不把请求撑爆」与「不把往返次数拉高」的折中**，
     * 改大之前先在真机上量一次请求耗时。
     */
    private const val PROBE_BATCH = 40

    fun isAvailable(): Boolean = QqClient.isLoggedIn() || QqAccountAvailability.allowAnonymousSearch

    /** 艺人搜索。失败返回空列表。 */
    suspend fun searchArtists(keyword: String, limit: Int = 20): List<QqArtist> {
        if (keyword.isBlank()) return emptyList()
        return runCatching {
            val json = QqClient.legacyGet(
                QqCatalogRequests.legacySingerSearchUrl(keyword, limit.coerceIn(1, 60), 1),
            )
            QqCatalogMapper.artistsFromSearch(json)
        }.onFailure { Log.w(TAG, "searchArtists failed", it) }.getOrDefault(emptyList())
    }

    /** 艺人专辑列表。失败返回空列表。 */
    suspend fun artistAlbums(singerMid: String, num: Int = ALBUM_PAGE): List<QqAlbum> {
        if (singerMid.isBlank()) return emptyList()
        return runCatching {
            val json = QqClient.legacyGet(
                QqCatalogRequests.legacySingerAlbumUrl(singerMid, num, begin = 0),
            )
            QqCatalogMapper.albumsFromSingerAlbum(json)
        }.onFailure { Log.w(TAG, "artistAlbums failed", it) }.getOrDefault(emptyList())
    }

    /** 艺人曲目列表（「热门单曲」tab）。失败返回空列表。 */
    suspend fun artistSongs(singerMid: String): List<SongItem> {
        if (singerMid.isBlank()) return emptyList()
        return runCatching {
            val payload = QqClient.musicu(
                QqCatalogRequests.singerDetailEnvelope(singerMid),
                appIdentity = true,
            )
            QqCatalogMapper.songsFromSingerDetail(payload).take(TRACK_LIMIT)
        }.onFailure { Log.w(TAG, "artistSongs failed", it) }.getOrDefault(emptyList())
    }

    /** 专辑曲目。失败返回 null（调用方保留缓存里的旧数据）。 */
    suspend fun albumDetail(albumMid: String): QqAlbumDetail? {
        if (albumMid.isBlank()) return null
        return runCatching {
            val payload = QqClient.musicu(
                QqCatalogRequests.albumSongListEnvelope(albumMid, num = TRACK_LIMIT),
                appIdentity = true,
            )
            payload?.let { QqCatalogMapper.albumDetailFromSongList(it, albumMid) }
        }.onFailure { Log.w(TAG, "albumDetail failed", it) }.getOrNull()
    }

    /** 专辑搜索（用于「另一源有没有这张专辑」的人工入口）。失败返回空列表。 */
    suspend fun searchAlbums(keyword: String, limit: Int = 10): List<QqAlbum> {
        if (keyword.isBlank()) return emptyList()
        return runCatching {
            val json = QqClient.legacyGet(
                QqCatalogRequests.legacyAlbumSearchUrl(keyword, limit.coerceIn(1, 60), 1),
            )
            QqCatalogMapper.albumsFromSearch(json)
        }.onFailure { Log.w(TAG, "searchAlbums failed", it) }.getOrDefault(emptyList())
    }

    /**
     * **批量可播放性预检**（v2.4.0 · D）。
     *
     * ## 它为什么必须存在
     *
     * QQ 侧**没有任何权威的版权字段**（`probe-copyright-field.md` C4：
     * `action.switch` bit0 在 97/97 上恒为 1，`action.alert` 语义无定义，
     * `pay.pay_play` 只说明「要不要钱」）。唯一诚实的判据就是去要一次播放地址，
     * 看服务端**到底给不给 `purl`**。这个接口把「要一次」做成**一次请求覆盖一批**。
     *
     * ## 三条边界（铁律 5/6）
     *
     * 1. **绝不抛异常**：失败返回空 Map，调用方把整批标成 `UNKNOWN`（不是「不能播」）；
     * 2. **有界**：单批 ≤ [PROBE_BATCH]，批数 ≤ 3（一屏最多 120 首；
     *    超过的部分**不再探测**，直接留在 `UNKNOWN`——宁可少标，不可把页面拖住）；
     * 3. **与主流程解耦**：它是可选的增强，拿不到就什么都不显示。
     *
     * @return `TrackKey → TrackAvailability`。**缺席 ≠ 不可播**：
     *   没探到的曲目不会出现在 Map 里，调用方用 `UNKNOWN` 兜底。
     */
    suspend fun probeAvailability(songs: List<SongItem>): Map<TrackKey, TrackAvailability> =
        withContext(Dispatchers.IO) {
            val targets = songs.asSequence()
                .filter { it.musicSource == MusicSource.QQMUSIC }
                .filter { !it.sourceId.isNullOrEmpty() }
                .distinctBy { TrackKey.fromSong(it) }
                .take(PROBE_BATCH * 3)
                .toList()
            if (targets.isEmpty()) return@withContext emptyMap()

            val out = HashMap<TrackKey, TrackAvailability>(targets.size)
            for (batch in targets.chunked(PROBE_BATCH)) {
                val entries = batch.map { song ->
                    // media_mid 优先，缺失才回落到 songmid —— 与取链同一条规则。
                    // 用错 mid 会拿到一个「格式正确但指向不存在文件」的 purl（v2.1.0 实测），
                    // 那会把「其实能播」误判成「会员专享」。
                    (song.sourceId.orEmpty()) to (song.mediaId ?: song.sourceId.orEmpty())
                }
                val response = runCatching {
                    QqClient.musicu(
                        QqCatalogRequests.availabilityProbeEnvelope(entries) ?: return@runCatching null,
                        appIdentity = true,
                    )
                }.onFailure { Log.w(TAG, "probeAvailability batch failed", it) }.getOrNull()
                    ?: continue

                val probed = runCatching { QqCatalogMapper.availabilityFromProbe(response) }
                    .getOrDefault(emptyMap())
                for (song in batch) {
                    val entry = probed[song.sourceId.orEmpty()] ?: continue
                    out[TrackKey.fromSong(song)] = classify(entry)
                }
            }
            out
        }

    /**
     * 预检结果 → 可用性（v2.4.0 · D）。**判据只此一处。**
     *
     * | 情形 | 判定 | 理由 |
     * |---|---|---|
     * | `purl` 非空 | `PLAYABLE` | 服务端真的给了文件 |
     * | `purl` 空 + `result == 104003` | `MEMBER_ONLY` | 需要登录 / VIP（探针实测 79/79 落在这里） |
     * | `purl` 空 + 其它 | `UNKNOWN` | **不知道**。可能是下架、地区限制、风控、或曲目根本没上架 |
     *
     * **刻意不做的事**：把「`purl` 空」判成 `NO_COPYRIGHT`。
     * QQ 侧没有任何字段能证明「这首歌没上架」；把「拿不到」说成「无版权」是撒谎，
     * 而且会误导用户放弃一个其实存在的版本。
     */
    internal fun classify(entry: QqProbeEntry): TrackAvailability = when {
        entry.playable -> TrackAvailability.PLAYABLE
        entry.result == NEED_LOGIN_OR_VIP -> TrackAvailability.MEMBER_ONLY
        else -> TrackAvailability.UNKNOWN
    }

    /** 与 `QqApi.RESULT_NEED_LOGIN_OR_VIP` 同值；这里显式写死是为了让判据自解释。 */
    internal const val NEED_LOGIN_OR_VIP = 104003
}
