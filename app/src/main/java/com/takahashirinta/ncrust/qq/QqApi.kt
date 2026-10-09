/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · B：qm 业务接口（搜索 / 取链 / 歌词 / 资料）。
 */

package com.takahashirinta.ncrust.qq

import android.util.Log
import com.takahashirinta.ncrust.BuildConfig
import com.takahashirinta.ncrust.cache.OfflineKeys
import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.lyric.LrcParser
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.SongUrlResult
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import org.json.JSONArray
import org.json.JSONObject

/**
 * qm 的业务接口（v2.1.0 · B）。
 *
 * 全部端点的请求形状都是**实测**得到的（2026-09，`musicu.fcg` 客户端协议），
 * 不是从文档推断：搜索用 web 身份、取链与歌词用客户端身份，两套身份各自在自己的
 * 场景下验证过（见 [QqClient.musicu] 的 `appIdentity` 参数）。
 *
 * ## 一条硬纪律：取不到 URL 就返回 null，**绝不返回坏链接**
 *
 * 这是从 ncm 侧继承的铁律（见 `SongUrlFetcher` 的 KDoc）：拿一个 HTML 错误页或
 * 空流给 ExoPlayer，表现是**无限缓冲**（用户看到的是「卡住」而不是「跳过」）。
 * QQ 侧还有一个类似的坑：无权限时服务端会给 30 秒试听片段 ——
 * 本实现**只接受服务端明确返回的 purl**，不做任何「猜一个 URL 试试」的兜底。
 */
object QqApi {

    private const val TAG = "QqApi"

    /**
     * v3.3.0 · 需求 2：QQ 旧版搜索的 `t` 参数里「按歌词搜索」的取值。
     *
     * 实测（2026-10）：`t=7` 时响应把条目装在 `data.lyric.list`（`t=0` 是 `data.song.list`）。
     * 其余取值（1=专辑、12=MV 等）本应用不用。
     */
    private const val LYRICS_SEARCH_TYPE = 7

    /** 取链失败时服务端给的业务码（实测匿名态）。 */
    const val RESULT_NEED_LOGIN_OR_VIP = 104003

    /**
     * 取链失败的原因。
     *
     * ⚠️ v3.2.0 **改变了它的角色**：v2.1.4 引入时它「只用于日志与 UI 提示，不参与播放决策」，
     * 而全仓库唯一的读取处就是本文件自己的赋值 —— 也就是说它连日志都没进过。
     * 现在 [rejection] 是播放链**唯一的**分流判据（见 [QqRejection] 与
     * `player/ResolveFailure.kt`），因为「要会员」与「网络挂了」在 v3.1.0 眼里完全一样。
     */
    data class UrlFailure(
        val resultCode: Int,
        val tips: String,
        val fileType: QqFileType?,
        /** v3.2.0 · P0：分类结论。 */
        val rejection: QqRejection = QqRejection.UNKNOWN,
    )

    @Volatile
    var lastUrlFailure: UrlFailure? = null
        private set

    /** v3.2.0：最近一次取链的分类结论。**每次 [fetchPlayUrl] 都会写**（成功写 [QqRejection.NONE]）。 */
    @Volatile
    var lastRejection: QqRejection = QqRejection.NONE
        private set

    // ---------------- 搜索 ----------------

    /**
     * 搜索歌曲。**旧版 GET 优先，`musicu.fcg` 兜底。**
     *
     * 顺序是有实测依据的（2026-09）：新版 `DoSearchForQQMusicMobile` 连续 6 次只成功 1 次
     * （其余 `code:2001`，疑似按出口 IP 限流），旧版 `client_search_cp` 3/3 稳定。
     * 两条通道的字段名在 `new_json=1` 下完全一致（`mid`/`file.media_mid`/`pay.pay_play`），
     * 所以共用同一套映射；哪条先成功用哪条的结果，只有第一条拿不到东西时才试第二条。
     */
    suspend fun searchSongs(keyword: String, limit: Int, page: Int = 1): List<SongItem> {
        if (keyword.isBlank()) return emptyList()
        val n = limit.coerceIn(1, 60)
        val p = page.coerceAtLeast(1)
        // `page` 一路传到旧版 GET 的 `p=`（见 QqRequests.legacySearchUrl）。
        // ⚠️ **`musicu` 兜底那条路不支持翻页**：它的信封是按「单曲搜索」实测出来的，
        // 翻页语义（`page_num`）没有实测依据，所以第 2 页起**只走旧版 GET** ——
        // 拿没验证过的通道去赌，失败形态会是「翻页翻出第一页的内容」，
        // 那比「翻页没结果」更难查。

        val legacy = runCatching {
            QqClient.legacyGet(QqRequests.legacySearchUrl(keyword, n, p))
        }.getOrNull()
        legacy?.let { json ->
            val songs = QqSongMapper.songsFromLegacySearch(json)
            if (songs.isNotEmpty()) {
                if (BuildConfig.DEBUG) Log.d(TAG, "search(legacy) '$keyword' -> ${songs.size}")
                return songs
            }
        }

        val songs = searchViaMusicu(keyword, n, p)
        if (BuildConfig.DEBUG) Log.d(TAG, "search(musicu) '$keyword' -> ${songs.size}")
        return songs
    }

    /**
     * 只问 QQ 那边这个关键词**总共有多少首**（v3.4.11）。
     *
     * 走的是与 [searchSongs] **同一条**旧版 GET（`client_search_cp`，`n=1` 把正文压到最小），
     * 只读 `data.song.totalnum` —— 不新发明请求形状，也就不会引入一个没实测过的通道。
     *
     * 实测（2026-10-10，匿名）：`w=周杰伦` → `totalnum = 999`。
     * 取不到返回 null（**不是 0**）：「不知道总数」与「总数是 0」是两件事，
     * 界面在 null 时回落到「已载 N 首」。
     */
    suspend fun searchTotalCount(keyword: String): Int? {
        if (keyword.isBlank()) return null
        val json = runCatching {
            QqClient.legacyGet(QqRequests.legacySearchUrl(keyword, limit = 1, page = 1))
        }.getOrNull() ?: return null
        return QqSongMapper.totalCountOf(json, "song")
    }

    /**
     * v3.3.0 · 需求 2：**按歌词搜索**（`t=7`）。
     *
     * 实测（2026-10）：`client_search_cp?t=7&w=让我掉下眼泪的` 返回 `data.lyric.list`，
     * 条目字段与 `t=0` 的单曲结果**同形**（`mid`/`id`/`title`/`singer`/`album`），
     * 所以映射复用 [QqSongMapper.songsFromLegacyLyricSearch]，不另写一套。
     *
     * ⚠️ **只走旧版 GET，不回落到 `musicu`**：`musicu.fcg` 的搜索信封
     * （[QqRequests.searchEnvelope]）是按**单曲**搜索实测出来的配方，
     * 它的 `t` 语义**没有实测依据**。拿未验证的通道去赌，失败形态会是
     * 「QQ 歌词搜索静默返回 0 条」—— 与本仓库修过的那些「阴性结果伪装成没有数据」
     * 是同一个形状。旧版通道实测可用，所以这条能力**只挂在能被证明的那条路上**。
     *
     * 失败一律返回空列表（与 [searchSongs] 同一条契约：绝不抛给聚合调用方）。
     */
    suspend fun searchSongsByLyric(keyword: String, limit: Int, page: Int = 1): List<SongItem> {
        if (keyword.isBlank()) return emptyList()
        val n = limit.coerceIn(1, 60)
        val p = page.coerceAtLeast(1)
        val json = runCatching {
            QqClient.legacyGet(QqRequests.legacySearchUrl(keyword, n, p, type = LYRICS_SEARCH_TYPE))
        }.getOrNull() ?: return emptyList()
        val songs = QqSongMapper.songsFromLegacyLyricSearch(json)
        if (BuildConfig.DEBUG) Log.d(TAG, "searchLyric(legacy) '$keyword' -> ${songs.size}")
        return songs
    }

    /**
     * `musicu.fcg` 通道。
     *
     * **信封 key 用 module 名、且不带 `comm`** —— 这是实测出来的配方：
     * 带 Web comm 会被判成通道不匹配而返回 0 条（`code:2001`）。
     * 这与本文件其它接口（vkey/歌词）必须带 comm 正好相反，别顺手统一。
     */
    private suspend fun searchViaMusicu(keyword: String, n: Int, p: Int): List<SongItem> {
        val envelope = QqRequests.searchEnvelope(keyword, n, p, newSearchId())
        val response = QqClient.musicuEnvelope(envelope, appIdentity = false) ?: return emptyList()
        return QqSongMapper.songsFromSearchResponse(JSONObject().put("req", response))
    }

    private const val SEARCH_MODULE = "music.search.SearchCgiService"

    private val searchCounter: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 搜索 id：实测是 64 位量级的随机数（高 5 位 + 中段 + 当天毫秒）。
     * 服务端似乎不校验它，但照形状生成是零成本的保险；[searchCounter] 保证同一毫秒内
     * 连续调用也不会撞 id（撞了服务端可能返回上一页）。
     */
    private fun newSearchId(): String {
        val a = (1..20).random().toLong() * 18_014_398_509_481_984L
        val b = (0..4_194_304).random().toLong() * 4_294_967_296L
        val c = System.currentTimeMillis() % (24 * 60 * 60 * 1000L)
        return (a + b + c + searchCounter.incrementAndGet()).toString()
    }

    // ---------------- 取播放 URL ----------------

    /**
     * 取该曲在 [level] 档位下的可播放 URL。
     *
     * ## 两个实测踩出来的坑，直接决定了这里的写法
     *
     * 1. **服务端不会自动降级音质。** 请求 `M800` 而无权限时它返回空 `purl` +
     *    `result=104003`，**不会**顺手给你 128k。所以降级必须由客户端做，而且要把
     *    多个档位**一次性批量放进同一个 `filename[]`**（一次往返），再按优先级取
     *    第一个 `result==0 && purl` 非空的。逐个档位发请求的话，一首会员曲最坏要
     *    发 8 次往返才轮到能放的那一档。
     *
     * 2. **文件名只能用 `file.media_mid`，不能用 `song.mid`。** 两者实测经常不同，
     *    而**用错时服务端照样返回 purl 与 vkey、不报任何错** ——
     *    直到 CDN 下载才 `404 file not exist`，在播放器里表现为「缓冲一会儿然后报错」。
     *    正因如此，这里**不做「media_mid 失败就用 mid 再试一次」的兜底**：
     *    那个「兜底」拿到的 purl 是坏的，它只会把一次干净的失败换成一个诡异的播放错误。
     *    只有连 `media_id` 都没有（老数据、手工构造的条目）时才退回 `sourceId`。
     */
    suspend fun fetchPlayUrl(song: SongItem, level: String): SongUrlResult? {
        // v2.5.4 · C：取链期埋点（唯一落点）。全部是 AtomicLong 自增，无 IO、无网络。
        QqProbeCounters.onResolveAttempt()
        val songMid = song.sourceId
        if (songMid == null) {
            // songmid 缺失是**硬失败**：不兜底、不重试（见上面 KDoc 的第 2 条）。
            QqProbeCounters.onResolveMissingSongMid()
            // v3.2.0：这是「结构性取不到」，与权限/版权无关 —— 分类为 UNRESOLVABLE。
            recordRejection(QqRejection.UNKNOWN, null, null, null)
            return null
        }
        // 没有 media_id（v2.1.0 之前落盘的队列条目）时才退回 songmid
        val mediaIdOfSong = song.mediaId?.takeIf { it.isNotEmpty() }
        if (mediaIdOfSong == null) QqProbeCounters.onResolveMediaMidFallback()
        val mediaMid = mediaIdOfSong ?: songMid

        val types = QqQuality.attemptsFor(level)
        val batch = requestVkeyBatch(songMid, mediaMid, types, requestedLevel = level)
        // v3.2.0 · P0：**请求本身失败**与「请求成功但被拒」必须分开。
        // v3.1.0 把两者都写成 `null`，于是「网络抖了一下」在播放链眼里与「这首歌要会员」
        // 完全一样 —— 前者该重试，后者该提示，处置相反。
        val info = batch.entries
        if (info == null) {
            recordRejection(
                rejection = batch.transportRejection,
                resultCode = null,
                tips = batch.transportMessage,
                fileType = null,
            )
            return null
        }

        // 按**请求时的优先级**挑，而不是按响应顺序 —— 响应顺序是服务端的实现细节，
        // 依赖它等于把「用户选无损却拿到 128k」变成一个随机事件。
        val rejections = ArrayList<QqRejectionInput>(types.size)
        for (fileType in types) {
            val entry = info[fileType] ?: continue
            val purl = entry.optString("purl").takeIf { it.isNotEmpty() }
            if (purl == null) {
                // v3.2.0：逐档位分类。**记录而不是丢弃** —— 被拒的原因就是界面上要对用户说的话。
                val input = rejectionInputOf(entry)
                rejections += input
                lastUrlFailure = UrlFailure(
                    resultCode = entry.optInt("result", 0),
                    tips = entry.optString("tips"),
                    fileType = fileType,
                    rejection = classifyQqRejection(input),
                )
                continue
            }
            val url = buildUrl(purl)
            val actualLevel = QqQuality.ncrustLevelOf(fileType)
            QqProbeCounters.onResolveOk()
            Log.i(TAG, "vkey ok: requested=$level actual=$actualLevel prefix=${fileType.prefix} mid=$mediaMid")
            lastUrlFailure = null
            lastRejection = QqRejection.NONE
            // 与 ncm 侧同一个离线缓存 key 机制：挂上它，media3 的 SimpleCache
            // 才能把「同一首歌 + 同一档位」的轮换 URL 认成同一份缓存。
            return SongUrlResult(
                url = OfflineKeys.withKey(url, song.id, actualLevel),
                actualLevel = actualLevel,
                // v2.2.1 · P0：这个档位是**从真正取回的文件名前缀反推的**（`RS01…flac` → hires），
                // 它自己就是证据、不是服务端标签。标出来之后，QualityAssessment 才敢在
                // 「请求母带、实拿 Hi-Res」时如实打上「已降级」，而不是像以前那样沉默
                // （用户原话：「开了母带只能出极高，和免费用户没区别」）。
                levelFromFile = true,
                // QQ 的 vkey 响应**没有码率字段**（实测：`midurlinfo[]` 里没有 br），
                // 但档位前缀本身就决定了码率 —— M500 恒为 128k mp3、M800 恒为 320k mp3、
                // C400 是 96k AAC。填这些**由档位确定的已知值**，界面才能把
                // 「请求超清母带、实际退回 320k」如实显示成「更好」而不是继续挂着母带。
                // FLAC 档位（F000/RS01/AI00/Q000/Q001）无法从前缀得知码率，留 0 = 未知：
                // 那种情况下界面会按服务端标签显示，而标签在这些档位上就是权威的。
                br = QqQuality.knownBitrateOf(fileType),
                type = fileType.ext,
                songMaxLevel = null,
            )
        }
        QqProbeCounters.onResolveFail()
        // v3.2.0 · P0：把**逐档位**的结论收敛成一条，并且**写进 lastRejection** 让
        // QqMusicSourceProvider 能把它翻译成跨音源的失败分类（铁律 20/21 的落点）。
        val batchRejection = classifyQqBatch(rejections)
        lastRejection = batchRejection
        Log.w(
            TAG,
            "no playable url for ${SourceIds.trackKey(MusicSource.QQMUSIC, song.id)} at level=$level " +
                "rejection=$batchRejection levels=${types.size}",
        )
        return null
    }

    /**
     * v2.1.4：**一次问全档位的诊断探针**（只有 DEBUG 包会调它）。
     *
     * 与 [fetchPlayUrl] 的区别是它不挑、不降级、不返回任何东西 —— 它只把
     * 「服务端对**每一个**档位分别怎么答」打全。用户报「超清母带只能出极高」时，
     * 这一条日志就能把三种可能切开：
     *
     * - 所有档位都 `104003` ⇒ 登录态/权限问题（这个账号或这条通道没被放行）；
     * - 免费与无损档 `0`、只有 `AI00`/`Q000`/`Q001` `104003` ⇒ 档位前缀或会员档没放行；
     * - `101404` ⇒ `comm.cv` 不对（客户端版本被判非法）。
     *
     * 为什么不能靠 [fetchPlayUrl] 的日志代替：那条路一旦在低档位拿到 purl 就返回了，
     * 高档位的结果不会被记录 —— 而「为什么没拿到高档位」恰恰是要看的东西。
     */
    suspend fun diagnoseQuality(song: SongItem): Boolean {
        val songMid = song.sourceId ?: return false
        val mediaMid = song.mediaId?.takeIf { it.isNotEmpty() } ?: songMid
        // 按「高 → 低」问：第一条拿到 purl 的档位就是该曲的真实上限（null 会被 composeUrl 兜底）。
        val probe = (QqQuality.ladderFor("dolby") + QqFileType.values().toList()).distinct()
        Log.i(
            TAG,
            "vkey.diag ===== 探针开始 songMid=$songMid mediaMid=$mediaMid probe=" +
                probe.joinToString(",") { it.prefix },
        )
        val info = requestVkeyBatch(songMid, mediaMid, probe, requestedLevel = "diagnose")
        if (info.entries == null) {
            Log.w(TAG, "vkey.diag ===== 探针结束：请求本身失败（登录态/网络/模块错误）")
            return false
        }
        val best = probe.firstOrNull { info.entries[it]?.optString("purl")?.isNotEmpty() == true }
        Log.i(
            TAG,
            "vkey.diag ===== 探针结束 最高可用档位=" + (best?.let { it.prefix + "(" + it.label + ")" } ?: "无"),
        )
        return true
    }

    /**
     * 一次请求把 [types] 里所有档位都问一遍，返回 `档位 → midurlinfo 条目`。
     *
     * 按响应条目自带的 `filename` 反查档位（[QqQuality.fileTypeOfFileName]），
     * 不依赖响应顺序与请求顺序一致。
     *
     * ## v3.2.0：返回值从 `Map?` 改成 [VkeyBatch]
     *
     * 旧签名把「**请求本身失败**（网络/HTTP/响应畸形）」与「**响应里没有可用的条目**」
     * 折叠成同一个 `null`。这两件事的处置完全相反（前者重试、后者提示），
     * 所以现在分开：[VkeyBatch.entries] 为 null 表示前者，
     * 非 null 但缺档位表示后者。
     */
    private data class VkeyBatch(
        val entries: Map<QqFileType, JSONObject>?,
        /** [entries] 为 null 时，这次失败该归到哪一类（网络 / 凭证）。 */
        val transportRejection: QqRejection = QqRejection.NETWORK,
        /** 传输层的一句话原因（只进日志）。 */
        val transportMessage: String? = null,
    )

    /**
     * 取链请求 + v3.4.9 的**一次性续期重试**。
     *
     * ## 为什么重试放在这一层而不是 `fetchPlayUrl`
     *
     * 「服务端拒绝了这张票」这个事实只有本函数知道（逐档位的服务端 `result` 码与
     * 传输层状态码都在它手里）。放在 `fetchPlayUrl` 里判，就要么把整批 `midurlinfo`
     * 再传出去一层，要么在外面重复判一遍 —— 后者正是本仓库修过的
     * 「同一个判据写两遍、然后分叉」的形状。
     *
     * ## 重试**恰好一次**
     *
     * 重试走的是 [requestVkeyBatchOnce]（不带重试的那一层），所以
     * 「拒绝 → 续期 → 再拒绝」不可能变成循环 —— 出口在结构上只有那一个。
     */
    private suspend fun requestVkeyBatch(
        songMid: String,
        mediaMid: String,
        types: List<QqFileType>,
        /** 只用于日志（v2.1.4）：诊断时打的是用户请求档位，正常路径打的是同一个值。 */
        requestedLevel: String = "",
    ): VkeyBatch {
        val first = requestVkeyBatchOnce(songMid, mediaMid, types, requestedLevel)
        if (!isAuthRejection(first)) return first
        // 走到这里 = 本地有票据、服务端却把我们当成没登录（或直接 401/403）。
        // v3.4.9 之前这里就是终点：用户看到「此源无版权/要会员」，然后被迫重新登录。
        Log.i(TAG, "vkey 被服务端拒绝（$first.transportRejection），尝试静默续期登录态")
        val refreshed = QqTokenRefresher.refreshAfterRejection(QqClient.appContextOrNull())
        if (refreshed != QqTokenRefresher.RefreshResult.REFRESHED) {
            Log.i(TAG, "静默续期未成功：${refreshed}（不重试，按原结论上报）")
            return first
        }
        Log.i(TAG, "静默续期成功，用新票据重试一次取链")
        // 递归的**出口**：这里调的是 `...Once`（不带重试的那一层），
        // 所以「拒绝 → 刷新 → 再拒绝」不可能变成循环。
        return requestVkeyBatchOnce(songMid, mediaMid, types, requestedLevel)
    }

    /**
     * 这次「请求本身失败/被拒」是不是**登录态**问题（v3.4.9）。
     *
     * 两个判据都要求**本地持有票据**：
     * - `AUTH_EXPIRED`：HTTP 401/403；
     * - `NEED_LOGIN` 且 `isLoggedIn()`：服务端在 `result=104003` 之外还让我们去登录，
     *   而我们手里明明有票 ⇒ 那张票在服务端已经不算数了。
     *
     * **`NEED_VIP` 故意不在列**：它是「票有效、权益不够」——开通会员才能解决，
     * 拿它去触发续期只会让「非会员点会员歌」每次都换一次票（腾讯侧看到的是
     * 一个不停续期的账号，而用户的权益一点没变）。
     * 同理，本地**没有**票据时（`isLoggedIn()` 为 false）也不刷新：
     * 那是「真的没登录」，该做的是让用户去登录。
     */
    private fun isAuthRejection(batch: VkeyBatch): Boolean {
        val entries = batch.entries
        if (entries == null) return batch.transportRejection == QqRejection.AUTH_EXPIRED
        if (!QqClient.isLoggedIn()) return false
        val inputs = entries.values.map { rejectionInputOf(it) }
        if (inputs.isEmpty()) return false
        return classifyQqBatch(inputs) == QqRejection.NEED_LOGIN
    }

    private suspend fun requestVkeyBatchOnce(
        songMid: String,
        mediaMid: String,
        types: List<QqFileType>,
        /** 只用于日志（v2.1.4）：诊断时打的是用户请求档位，正常路径打的是同一个值。 */
        requestedLevel: String = "",
    ): VkeyBatch {
        if (types.isEmpty()) return VkeyBatch(null, QqRejection.UNKNOWN, "no file types")
        val request = QqRequests.vkey(
            songMid = songMid,
            mediaMid = mediaMid,
            types = types,
            uin = QqClient.uinForRequest(),
            guid = QqClient.guidForRequest(),
        )
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "vkey.diag request requested=$requestedLevel uin=" + QqClient.uinForRequest() +
                    " guid=" + QqClient.guidForRequest() +
                    " authstInjected=" + (QqCookie.musicKeyOf(QqClient.cookieForDiagnostics()) != null) +
                    " filenames=" + types.joinToString(",") { QqQuality.fileNameFor(it, mediaMid) },
            )
        }
        // v3.2.0：传输层失败也带上 HTTP 状态码 —— 401/403 是「凭证过期」而不是「网络抖动」。
        val response = QqClient.musicu(request, appIdentity = true)
            ?: return VkeyBatch(
                entries = null,
                transportRejection = classifyQqRejection(
                    QqRejectionInput(
                        transportFailed = true,
                        httpStatus = QqClient.lastHttpStatus,
                        loggedIn = QqClient.isLoggedIn(),
                    ),
                ),
                transportMessage = QqClient.lastTransportError,
            )
        val data = response.optJSONObject("data")
            ?: return VkeyBatch(null, QqRejection.UNKNOWN, "response has no data object")

        // CDN 前缀每次响应都可能不同（host 轮换），所以每次都更新。
        data.optJSONArray("sip")
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.firstOrNull { it.isNotEmpty() } }
            ?.let { lastSip = it }

        val list = data.optJSONArray("midurlinfo")
            ?: return VkeyBatch(null, QqRejection.UNKNOWN, "response has no midurlinfo array")
        val out = LinkedHashMap<QqFileType, JSONObject>()
        for (i in 0 until list.length()) {
            val entry = list.optJSONObject(i) ?: continue
            val type = QqQuality.fileTypeOfFileName(entry.optString("filename")) ?: continue
            out[type] = entry
        }
        // 选择结果由调用方决定，这里先按请求顺序记录全部逐档结果（v2.1.4 诊断）。
        if (BuildConfig.DEBUG) {
            logVkeyDiagnostics(
                requested = requestedLevel,
                songMid = songMid,
                mediaMid = mediaMid,
                types = types,
                data = data,
                selected = out.entries.firstOrNull { (t, e) ->
                    e.optInt("result", -1) == 0 && e.optString("purl").isNotEmpty()
                }?.key,
                cookie = QqClient.cookieForDiagnostics(),
            )
        }
        return VkeyBatch(out)
    }

    /**
     * 把 `purl` 拼成完整 URL：`<sip[0]><purl>`。
     *
     * ## `sip` 可能是空的（真机实测踩到，v2.1.0 hotfix 3）
     *
     * 实测三首免费曲目（《千与千寻》《城南花已开》《宫崎骏的夏天》）的 vkey 响应里
     * **`sip` 数组是空的**，但 `purl` 有值且 `result=0` —— 也就是「链是好的，只是没告诉你 CDN 域名」。
     * 早先的实现在这里直接返回 null，于是**本来能播的免费曲目被整个丢掉**
     * （表现是「点了没反应/跳歌」，而且因为它看起来像「无权限」，几乎无法排查）。
     *
     * 现在回落到固定的 CDN 域名。实测四个候选域名（http/https 各两个）都能返回
     * HTTP 200 + `audio/mpeg` + 真实 ID3 字节，所以统一用 `https`（不引 cleartext 问题）。
     */
    internal fun composeUrl(purl: String, sip: String?): String {
        if (purl.startsWith("http://") || purl.startsWith("https://")) return purl
        val host = sip?.takeIf { it.isNotEmpty() } ?: FALLBACK_CDN
        val prefix = if (host.endsWith("/")) host else "$host/"
        return prefix + purl.removePrefix("/")
    }

    private fun buildUrl(purl: String): String = composeUrl(purl, lastSip)

    // ---------------------------------------------------------------- v3.2.0 · P0 分类辅助

    /**
     * v3.2.0 · P0：[fetchPlayUrl] 的**带分类**版本。播放链应当只调这一个。
     *
     * 为什么不把分类直接塞进 `fetchPlayUrl` 的返回值：那个函数有 4 个调用点
     * （播放链、离线预热、探针、单测），改签名会把「分类」这件事的半径扩大到
     * 与它无关的地方。这里包一层，语义是：
     *
     * ```
     * resolveOutcome == Ok    <=> fetchPlayUrl != null
     * resolveOutcome == Failed <=> fetchPlayUrl == null，且带上 lastRejection
     * ```
     *
     * **结构性失败优先**：`songmid` 缺失时连请求都不发，直接判
     * [com.takahashirinta.ncrust.player.ResolveFailureKind.UNRESOLVABLE] ——
     * 这一档是唯一允许自动跳歌的（见 `player/ResolveFailure.kt` 的表），
     * 而它必须**在** [fetchPlayUrl] 之前判，否则会被 `104003` 之类的响应码盖住。
     */
    suspend fun resolveOutcome(
        song: SongItem,
        level: String,
    ): com.takahashirinta.ncrust.player.ResolveOutcome {
        val source = MusicSource.QQMUSIC
        if (song.sourceId.isNullOrBlank()) {
            // 与 QqProbeCounters 的硬失败埋点保持一致（fetchPlayUrl 里也会记一次，
            // 但那条路现在走不到了 —— 这里提前返回，所以在这里记）。
            QqProbeCounters.onResolveAttempt()
            QqProbeCounters.onResolveMissingSongMid()
            return com.takahashirinta.ncrust.player.ResolveOutcome.failed(
                com.takahashirinta.ncrust.player.ResolveFailureKind.UNRESOLVABLE,
                source,
            )
        }
        val result = fetchPlayUrl(song, level)
        if (result != null) {
            return com.takahashirinta.ncrust.player.ResolveOutcome.ok(result)
        }
        val failure = lastUrlFailure
        return com.takahashirinta.ncrust.player.ResolveOutcome.failed(
            kind = lastRejection.toResolveFailureKind(),
            source = source,
            rawCode = failure?.resultCode,
            rawMessage = failure?.tips?.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * 一个 `midurlinfo` 条目 → 分类输入。
     *
     * `loggedIn` 取的是**发起这次请求时**的本地登录态（`QqCookie.isLoggedIn`），
     * 它是「`104003` 该读成『去登录』还是『权益不足』」的唯一判据：
     * 同一个码在两种状态下含义完全不同，而客户端只有本地这一个信号能区分它们。
     */
    private fun rejectionInputOf(entry: JSONObject): QqRejectionInput = QqRejectionInput(
        // 走到这里的前提就是「这一档没有 purl」（有 purl 的那一档在循环里直接返回了）。
        hasPurl = false,
        resultCode = entry.optInt("result", 0),
        tips = entry.optString("tips").takeIf { it.isNotEmpty() },
        pneedbuy = entry.optInt("pneedbuy", 0),
        isbuy = entry.optInt("isbuy", 0),
        // `type == -1` = 30 秒试听（v3.1.0 记为未复现，保留防御分支）。
        trialType = entry.optInt("type", 0).takeIf { entry.has("type") },
        transportFailed = false,
        httpStatus = null,
        loggedIn = QqClient.isLoggedIn(),
    )

    /**
     * 写下一次失败的分类。**每一处 `return null` 之前都必须调用它** ——
     * 漏掉一处的表现是「那一种失败退回 v3.1.0 的行为（弹无版权 + 跳歌）」，
     * 而那正是本版要根除的形状。
     */
    private fun recordRejection(
        rejection: QqRejection,
        resultCode: Int?,
        tips: String?,
        fileType: QqFileType?,
    ) {
        lastRejection = rejection
        lastUrlFailure = UrlFailure(
            resultCode = resultCode ?: 0,
            tips = tips.orEmpty(),
            fileType = fileType,
            rejection = rejection,
        )
    }

    /** `sip` 缺失时的兜底 CDN。实测 `http`/`https`、`ws`/`ws6`/`aqqmusic.tc` 四个域名都可用。 */
    private const val FALLBACK_CDN = "https://ws.stream.qqmusic.qq.com/"

    @Volatile
    private var lastSip: String? = null

    /**
     * v2.1.4：把**一次真实播放取链**的请求与响应完整打出来（仅 DEBUG）。
     *
     * 为什么必须打完整：匿名态实测「所有档位都 104003」是**预期**行为
     * （见仓库外 `PHASE0-QQMUSIC-API.md` §7.2/§8），所以「取不到母带」这件事只有在
     * **登录态**下才有区分度。而登录态只有用户有 —— 开发侧没有 qm 账号。
     * 把逐档位的 `result` 与 `tips` 打全，用户贴一次日志就能定位是
     * 「所有付费档都被拒（登录态/权限）」还是「只有母带档被拒（档位前缀）」。
     *
     * 安全：不打 `purl` / `vkey`（它们是 2 小时内有效的资源令牌，贴日志即泄露）。
     * 只打 `sip` 主机名、`filename`、`result`、`tips`、`mid`。
     */
    private fun logVkeyDiagnostics(
        requested: String,
        songMid: String,
        mediaMid: String,
        types: List<QqFileType>,
        data: JSONObject,
        selected: QqFileType?,
        cookie: String?,
    ) {
        val sipHost = data.optJSONArray("sip")
            ?.let { a -> (0 until a.length()).map { a.optString(it) }.firstOrNull { it.isNotEmpty() } }
            .orEmpty()
        Log.d(
            TAG,
            "vkey.diag requested=$requested songMid=$songMid mediaMid=$mediaMid" +
                " cookieFields=" + QqCookie.fieldNamesOf(cookie) +
                " uin=" + (QqCookie.uinOf(cookie)?.toString() ?: "null") +
                " sip=" + sipHost +
                " retcode=" + data.optInt("retcode", -1) +
                " chosen=" + (selected?.prefix ?: "none"),
        )
        val list = data.optJSONArray("midurlinfo")
        for (t in types) {
            val entry = (0 until (list?.length() ?: 0))
                .mapNotNull { list!!.optJSONObject(it) }
                .firstOrNull { QqQuality.fileTypeOfFileName(it.optString("filename")) == t }
            if (entry == null) {
                Log.d(TAG, "vkey.diag   ${t.prefix}(${t.label}) 条目缺失")
                continue
            }
            val purl = entry.optString("purl")
            Log.d(
                TAG,
                "vkey.diag   ${t.prefix}(${t.label}) result=" + entry.optInt("result", -1) +
                    " purl=" + (if (purl.isEmpty()) "空" else "有(len=${purl.length})") +
                    " tips=" + entry.optString("tips") +
                    " echo=" + entry.optString("filename") +
                    " mid=" + entry.optString("mid"),
            )
        }
    }

    // ---------------- 歌词 ----------------

    /**
     * 一份 QQ 歌词包（v2.1.0 · D）。
     *
     * @property authoritative 服务端**明确**回答了这首歌的歌词情况（哪怕是「没有歌词」）。
     *   与「这次请求失败」必须分开：前者可以缓存、可以让 UI 显示「暂无歌词」，
     *   后者只能当作暂时拿不到、下次还要再试。这与 ncm 侧靠 `code == 200` 区分
     *   「权威空结果」是同一个道理（见 `PlayerViewModel` 里「只缓存 code==200 的结果」）。
     */
    data class LyricPack(
        val qrcLines: List<LrcLine>,
        val transLines: List<LrcLine>,
        val romaLines: List<LrcLine>,
        val authoritative: Boolean,
    ) {
        /** 有没有任何可用内容。 */
        val isEmpty: Boolean get() = qrcLines.isEmpty() && transLines.isEmpty() && romaLines.isEmpty()
    }

    suspend fun fetchLyric(song: SongItem): LyricPack? {
        val songMid = song.sourceId ?: return null
        val rawId = SourceIds.qqRawId(song.id) ?: 0L
        val request = QqRequests.lyric(songMid, rawId)
        val response = QqClient.musicu(request, appIdentity = true) ?: return null
        if (response.optInt("code", -1) != 0) {
            Log.w(TAG, "lyric code=${response.optInt("code", -1)} for mid=$songMid")
            return null
        }
        val data = response.optJSONObject("data") ?: return LyricPack(emptyList(), emptyList(), emptyList(), true)
        val crypt = data.optInt("crypt", 1) == 1

        // ⚠️ 实测：`qrc=0` 的歌**照样**加密，但解密出来是**行级 LRC**（不是 QRC 的 XML）。
        // 只按 QRC 解析的话这些歌会得到 0 行 —— 表现是「有歌词的歌显示暂无歌词」。
        val lyricText = decodeField(data.optString("lyric"), crypt)
        val qrc = QrcParser.parseXml(lyricText).ifEmpty { LrcParser.parse(lyricText.orEmpty()) }
        val roma = QrcParser.parseXml(decodeField(data.optString("roma"), crypt))
        val trans = LrcParser.parse(decodeField(data.optString("trans"), crypt).orEmpty())
            // 翻译轨里没有翻译的行会被服务端写成 `//`（实测），
            // 它不是歌词文本，留着只会在界面上多出一行斜杠。
            .filter { it.text.any { c -> c != '/' && !c.isWhitespace() } }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "lyric mid=$songMid qrc=${qrc.size} trans=${trans.size} roma=${roma.size}")
        }
        return LyricPack(qrc, trans, roma, authoritative = true)
    }

    /**
     * 一个歌词字段的解码：**先按密文解，解不开就当明文用**。
     *
     * 不依赖 `crypt` 标志位是因为实测它不可信：传 `crypt=0` 时服务端**照样**返回密文
     * （响应里 `crypt` 也回 0）。先用「能不能解出 zlib + UTF-8」来判，比信标志位稳。
     */
    private fun decodeField(value: String?, crypt: Boolean): String? {
        if (value.isNullOrEmpty()) return null
        QrcDecryptor.decrypt(value)?.let { return it }
        return if (crypt) null else value
    }

    /**
     * 账号的会员状态与音质权益。
     *
     * 走 `VipLogin.VipLoginInter` / `vip_login_base`：**实测匿名也能调用**（`code=0`），
     * 返回 `identity.vip`（0/1）、`identity.svip`、`identity.overdate`（到期日期字符串），
     * 以及 `music_lev_hq` / `music_lev_sq` / `music_lev_hires` / `music_lev_dolby`
     * 这几项**音质权益**。未登录时这些值都是 0/空 —— 这正是我们要的语义。
     *
     * ## 验证程度（如实标注）
     *
     * 匿名态的**结构**已实测；**登录态下 vip=1 的具体取值没有验证过**（本仓库没有
     * qm 账号）。因此本方法的结果**只用于界面显示与降级提示**，
     * 绝不参与「这首歌能不能放」的判断 —— 那个判断永远由服务端返回的 purl 决定
     * （见 [fetchPlayUrl]）。判断错了最坏是界面上的一个角标不对，不会凭空给或夺走播放权限。
     */
    suspend fun fetchProfile(): QqProfile? {
        val request = QqRequests.vip()
        val response = QqClient.musicu(request, appIdentity = true) ?: return null
        if (response.optInt("code", -1) != 0) return null
        val identity = response.optJSONObject("data")?.optJSONObject("identity") ?: return null
        val vip = identity.optInt("vip", 0)
        val svip = identity.optInt("svip", 0)
        return QqProfile(
            nick = null,
            uid = 0L,
            // 只区分「有没有会员」与原始档位，不把具体数字写死进业务判断
            // （见 QqProfile 的注释：腾讯加一档会员就会让写死的判断全错）。
            vipType = if (svip > 0) 2 else if (vip > 0) 1 else 0,
            vipExpireAt = parseOverdate(identity.optString("overdate")),
        )
    }

    /**
     * `overdate` 实测是**日期字符串**（形如 `2026-09-25`），不是时间戳。
     * 解析成秒级时间戳；解析不出返回 0（= 未知，[QqProfile.isVip] 会按「不过期」处理 ——
     * 服务端既然说了 vip=1，就不该因为我们看不懂日期而把它判成非会员）。
     */
    private fun parseOverdate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        // 已经是纯数字时间戳时直接用（腾讯侧字段格式历史上变过）
        text.toLongOrNull()?.let { return if (it > 100_000_000_000L) it / 1000L else it }
        return runCatching {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            fmt.parse(text)?.time?.div(1000L) ?: 0L
        }.getOrDefault(0L)
    }

    /**
     * 把副文本轨（翻译 / 音译）**按时间就近**贴到主轨的每一行上（v2.1.0 · D）。
     *
     * 为什么必须重写时间戳而不是原样交出：渲染层是按 `timeMs` **精确配对**副文本的
     * （`translatedLyrics.associateBy { timeMs }`）。QRC 主轨的时间戳与 `trans`（行级 LRC）
     * 的时间戳来自服务端的两份资产，实测并不逐行相同，原样交出会让译文一行都配不上。
     * 所以这里把选中的副文本行的 `timeMs` 改成**主轨那一行的时间戳**。
     *
     * 容差之外的副文本整行丢弃：宁可少一行译文，也不要把上一句的翻译贴到这一句上。
     */
    internal fun alignToMainLines(
        main: List<LrcLine>,
        sub: List<LrcLine>,
        toleranceMs: Long = 1500L,
    ): List<LrcLine> {
        if (main.isEmpty() || sub.isEmpty()) return emptyList()
        val sorted = sub.sortedBy { it.timeMs }
        val out = ArrayList<LrcLine>(main.size)
        var cursor = 0
        for (line in main) {
            // 单调游标：主轨与副轨都是按时间递增的，不需要每行都从头二分。
            while (cursor < sorted.size && sorted[cursor].timeMs < line.timeMs - toleranceMs) cursor++
            val candidate = sorted.getOrNull(cursor) ?: break
            if (kotlin.math.abs(candidate.timeMs - line.timeMs) <= toleranceMs) {
                out.add(candidate.copy(timeMs = line.timeMs))
            }
        }
        return out
    }

    private fun SongItem.trackKeyOrEmpty(): String =
        SourceIds.trackKey(MusicSource.QQMUSIC, id)

    // ---------------- 手机号验证码登录（v2.1.1） ----------------

    /**
     * 一次手机号登录尝试的结果。
     *
     * [cookie] 只在**成功且凭证完整**时非空；调用方负责落盘（[QqAuthStore.saveCookie]）——
     * 这一层刻意不碰存储，好让「登录协议」与「账号存储」各自可测、可替换。
     */
    data class PhoneLoginAttempt(
        val outcome: QqPhoneLogin.LoginOutcome,
        val cookie: String? = null,
    )

    /**
     * 一次「发短信验证码」尝试的结果。
     *
     * @property securityUrl `20276`（腾讯要求图形验证码）时**非空** —— 调用方要把它开在
     *   WebView 里让用户过验证。字段名 `securityURL` 来自实测响应骨架（[SendPhoneAuthCode]
     *   失败时它也在，只是空串），不是猜的。
     * @property errMsg 服务端的 `data.errMsg`，只用于诊断（界面不显示它，它是英文内部错误）。
     */
    data class SendCodeAttempt(
        val outcome: QqPhoneLogin.SendOutcome,
        val securityUrl: String? = null,
        val errMsg: String? = null,
    )

    /**
     * 发短信验证码。返回归类后的结局（见 [QqPhoneLogin.SendOutcome]）。
     *
     * 号码的**本地校验不在这里** —— 调用方应当先过 [QqPhoneLogin.normalizePhone]，
     * 格式不对就别发请求（省一次注定 `104400` 的往返）。
     *
     * @param captchaCookie 图形验证（`20276`）完成后从 WebView 拿回来的 cookie。
     *   风控的验证结果落在 cookie 上，不回传就等于没验证过 —— 下一次请求会**再次**被要求验证。
     */
    suspend fun sendPhoneAuthCode(phone: String, captchaCookie: String? = null): SendCodeAttempt {
        val response = QqClient.musicuLogin(QqRequests.sendPhoneAuthCode(phone), captchaCookie)
            ?: return SendCodeAttempt(QqPhoneLogin.SendOutcome.FAILED)
        val code = response.optInt("code", -1)
        val data = response.optJSONObject("data")
        val securityUrl = QqPhoneLogin.securityUrlOf(response)
        val errMsg = data?.optString("errMsg")?.takeIf { it.isNotEmpty() }
        // 20276 的现场只留**可诊断但不敏感**的部分：验证 URL 里可能带一次性 token，
        // 所以只记主机与长度，不把整条 URL 写进 logcat（与「不把凭证写进日志」同一条纪律）。
        val host = securityUrl?.let { runCatching { java.net.URL(it).host }.getOrNull() }
        Log.i(
            TAG,
            "SendPhoneAuthCode -> req.code=$code captchaHost=$host " +
                "captchaUrlLen=${securityUrl?.length ?: 0} errMsg=$errMsg",
        )
        return SendCodeAttempt(QqPhoneLogin.classifySend(code), securityUrl, errMsg)
    }

    // ---------------- QQ 音乐官方 App 扫码登录（v3.4.9） ----------------

    /**
     * 一张「QQ 音乐 App 扫码」用的二维码。
     *
     * @property png 二维码图片（PNG 字节，已从 `data:image/png;base64,` 解出来）。
     * @property qrCodeId 91 字符的 ID。**两个用途，缺一不可**：
     *   ① MQTT 的话题名后缀（`management.qrcode_login/{id}`）；
     *   ② 换凭证时 `param.qrCodeID`。
     */
    data class QqScanQrCode(val png: ByteArray, val qrCodeId: String) {
        // ByteArray 在 data class 里是引用语义 —— 显式覆盖，免得将来有人拿它比较时
        // 得到「同一张二维码却不相等」的诡异结果（与 QqQrClient.QrCode 同一条纪律）。
        override fun equals(other: Any?): Boolean =
            this === other || (other is QqScanQrCode && qrCodeId == other.qrCodeId && png.contentEquals(other.png))

        override fun hashCode(): Int = 31 * png.contentHashCode() + qrCodeId.hashCode()
    }

    /**
     * 申请二维码。失败返回 null（界面据此显示「二维码获取失败，请重试」）。
     *
     * 解析两处：`data.qrcode` 是 `data:image/png;base64,<b64>`（**要剥前缀再解 base64**，
     * 直接把整串喂给 base64 解码会得到一个坏 PNG／抛异常），`data.qrcodeID` 是那个 ID。
     */
    suspend fun requestScanQrCode(): QqScanQrCode? {
        val response = QqClient.musicu(QqRequests.createQrCode(), appIdentity = true)
            ?: return null
        if (response.optInt("code", -1) != 0) {
            Log.w(TAG, "CreateQRCode code=${response.optInt("code", -1)}")
            return null
        }
        val data = response.optJSONObject("data") ?: return null
        val raw = data.optString("qrcode").takeIf { it.isNotEmpty() } ?: return null
        val id = data.optString("qrcodeID").takeIf { it.isNotEmpty() } ?: return null
        val png = runCatching {
            android.util.Base64.decode(raw.substringAfter(',', raw), android.util.Base64.DEFAULT)
        }.getOrElse {
            Log.w(TAG, "CreateQRCode 二维码不是合法 base64", it)
            return null
        }
        if (png.size < 8 || png[0] != 0x89.toByte() || png[1] != 'P'.code.toByte()) {
            // 与 QqQrClient.requestQr 同一条守卫：拿到 HTML 错误页时不许当二维码画出来。
            Log.w(TAG, "CreateQRCode 返回的不是 PNG（${png.size} 字节）")
            return null
        }
        Log.i(TAG, "CreateQRCode ok png=${png.size}B qrCodeId=${id.length}字符")
        return QqScanQrCode(png, id)
    }

    /** 扫码换凭证的结局。 */
    data class QrScanLoginAttempt(
        val outcome: QqPhoneLogin.LoginOutcome,
        val cookie: String? = null,
    )

    /**
     * 用 MQTT 推送回来的凭据换**完整凭证 JSON** 并落盘续期凭证（v3.4.9）。
     *
     * ## 这是「扫码也能续期」的全部秘密
     *
     * 换回来的 `data` 与手机号登录**完全同形**（同一个 `Login` 方法），所以：
     * cookie 走 [QqPhoneLogin.cookieFromCredential]（同一个读取器），
     * 续期凭证走 [QqRefreshCredential.fromLoginData]（同一个解析器），
     * 之后由 [QqTokenRefresher] 自动续期 —— **这一条链路上没有任何新逻辑**，
     * 只是终于拿到了那份 JSON。
     *
     * ## 落盘由本方法负责（与手机号登录那条路**刻意不同**）
     *
     * 手机号那条路把 cookie 交给调用方（`MainActivity.onLoggedIn`）去 saveCookie；
     * 这条路**自己落全**（cookie + 续期凭证）。第一版两边都以为对方会写，
     * 结果是「扫完码、确认了、界面仍是未登录」—— 真机上一个字节都没落盘。
     * 现在契约是单向的：**`QqApi` 负责把登录态落全，调用方只刷新界面。**
     */
    suspend fun loginWithScanCode(
        qrCodeId: String,
        uin: String,
        token: String,
    ): QrScanLoginAttempt {
        val request = QqRequests.scanLogin(qrCodeId, token, uin)
        // ⚠️ 必须走 musicuLogin（comm.tmeLoginType = 6 的注入点在 QqClient 里，
        // 见 QqClient.musicuScanLogin）—— 用普通 musicu 会少那个字段，
        // 服务端会按手机号登录去解释这个 param 并静默失败。
        val response = QqClient.musicuScanLogin(request) ?: return QrScanLoginAttempt(
            QqPhoneLogin.LoginOutcome.FAILED,
        )
        val reqCode = response.optInt("code", -1)
        val outcome = QqPhoneLogin.classifyLogin(reqCode)
        Log.i(TAG, "Login(qr-scan) -> req.code=$reqCode outcome=$outcome")
        if (outcome != QqPhoneLogin.LoginOutcome.OK) return QrScanLoginAttempt(outcome)
        val data = response.optJSONObject("data")
        // 诊断（v3.4.9 真机追加）：**只打字段名，绝不打任何值** —— 票据是账号凭证。
        // 存在的意义是把「服务端到底给不给续期凭证」从一个猜测变成一行事实：
        // 实测扫码那条路回的是 openid + musickey + keyExpiresIn（3 天），
        // `refreshKey` / `refreshToken` **一个都没有** —— 那意味着这条路**换不出新票**。
        Log.i(
            TAG,
            "Login(qr-scan) data 字段 = " + (
                data?.keys()?.asSequence()?.joinToString(",") ?: "(no data)"
                ),
        )
        val cookie = QqPhoneLogin.cookieFromCredential(data)
        if (cookie == null) {
            Log.w(TAG, "Login(qr-scan) 成功但凭证不成形，拒绝落盘")
            return QrScanLoginAttempt(QqPhoneLogin.LoginOutcome.FAILED)
        }
        val ctx = QqClient.appContextOrNull()
        if (ctx != null) {
            QqRefreshStore.clear(ctx)
            // ⚠️ **cookie 必须在这里落盘**（v3.4.9 的第一版漏了这一步，真机上是
            // 「扫完码、确认了、界面仍是未登录」）：续期凭证落了盘，cookie 没有，
            // 于是 `QqAuthStore.isLoggedIn()` 恒为 false。
            // 与手机号登录那条路的差别在于：那条路的调用方（`MainActivity` 的
            // `onLoggedIn`）自己 saveCookie，而这条路的调用方只刷新界面 ——
            // 契约必须只有一个归属，这里是「QqApi 负责把登录态落全」。
            QqAuthStore.saveCookie(ctx, cookie)
            QqRefreshCredential.fromLoginData(data)?.let { credential ->
                val saved = QqRefreshStore.save(ctx, credential)
                Log.i(
                    TAG,
                    "Login(qr-scan) 已落盘 cookie+续期凭证 loggedIn=${QqAuthStore.isLoggedIn(ctx)} " +
                        "canRefresh=${saved.isRefreshable()} {" + saved.describe() + "}",
                )
            }
        }
        return QrScanLoginAttempt(QqPhoneLogin.LoginOutcome.OK, cookie)
    }

    /**
     * 用验证码换凭证。
     *
     * 成功（`req.code == 0`）时把 `req.data` 转成 cookie 串返回；**凭证不完整时按失败处理**
     * （见 [QqPhoneLogin.cookieFromCredential] 的注释：缺票据的 cookie 会让界面显示
     * 「已登录」而一取链就说没权限，比直接失败难排查得多）。
     *
     * ## v3.4.9：同一个响应里还有**续期凭证**
     *
     * 登录响应里除了拼 cookie 的那几个字段，还有 `refreshKey` / `refreshToken` /
     * `accessToken` / `openid` / `keyExpiresIn` —— 它们才是这次登录**真正的长期收益**
     * （见 [QqRefreshCredential] 的 KDoc：丢掉它们等于把「可续期的会话」降级成
     * 「一次性票据」，也就是用户报的「一周左右就掉登录」）。
     *
     * 落盘由这里负责，而不是交给调用方（UI 层）：调用方只关心「成没成」，
     * 多一个必须记得做的步骤，就多一个会漏掉的地方 ——
     * 而漏掉它的表现（一周后掉登录）在开发机上**永远复现不出来**。
     */
    suspend fun loginWithPhoneCode(
        phone: String,
        code: String,
        captchaCookie: String? = null,
    ): PhoneLoginAttempt {
        val response = QqClient.musicuLogin(QqRequests.phoneLogin(phone, code), captchaCookie)
            ?: return PhoneLoginAttempt(QqPhoneLogin.LoginOutcome.FAILED)
        val reqCode = response.optInt("code", -1)
        val outcome = QqPhoneLogin.classifyLogin(reqCode)
        Log.i(TAG, "Login(phone) -> req.code=$reqCode outcome=$outcome")
        if (outcome != QqPhoneLogin.LoginOutcome.OK) return PhoneLoginAttempt(outcome)
        val data = response.optJSONObject("data")
        val cookie = QqPhoneLogin.cookieFromCredential(data)
        return if (cookie == null) {
            // 服务端说成功、凭证却不成形：不落盘，按失败报给用户（宁可不登，也不要半份登录态）
            Log.w(TAG, "Login(phone) 成功但凭证不完整，拒绝落盘")
            PhoneLoginAttempt(QqPhoneLogin.LoginOutcome.FAILED)
        } else {
            // 落 cookie **之前**先清续期凭证：换账号时若留着上一份，
            // 下一次续期会把新账号的登录态换成旧账号的（见 QqAuthStore.saveCookie 的注释）。
            // 只在真的拿到完整新凭证之后才清 —— 登录失败的用户不该丢掉手上现成的续期能力。
            val ctx = QqClient.appContextOrNull()
            if (ctx != null) {
                QqRefreshStore.clear(ctx)
                QqRefreshCredential.fromLoginData(data)?.let { credential ->
                    val saved = QqRefreshStore.save(ctx, credential)
                    Log.i(
                        TAG,
                        "Login(phone) 已保存续期凭证 canRefresh=${saved.isRefreshable()} " +
                            "{" + saved.describe() + "}",
                    )
                }
            }
            PhoneLoginAttempt(QqPhoneLogin.LoginOutcome.OK, cookie)
        }
    }
}
