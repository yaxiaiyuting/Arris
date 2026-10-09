/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · B：B 站音源的 Provider 实现。
 */

package com.takahashirinta.ncrust.bili

import android.util.Log
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.SongUrlResult
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.MusicSourceProvider

/**
 * B 站音源（v3.1.0 · B）。
 *
 * ## 它为什么是「两条腿」
 *
 * 见 [BiliApi] 的类文档：B 站**没有**音频区搜索接口，能搜到的只有视频；
 * 而只有音频区给 LRC 歌词。所以：
 *
 * | 动作 | 音频区曲目（`sourceId = au:<auid>`） | 视频音轨（`sourceId = bv:<bvid>:<cid>`） |
 * |---|---|---|
 * | [searchSongs] | 只有当**关键词本身就是一个 auid / B 站音频链接**时命中（直链语义） | 正常关键词搜索的默认结果 |
 * | [resolveUrl] | `song/url`（192K m4a，TTL 来自 URL 的 `deadline`） | `playurl` 的 DASH `audio[]` |
 * | 歌词 | `song/info` 的 `lyric` 字段（LRC） | 没有数据源 ⇒ 空（**诚实降级**，见 [fetchLyric]） |
 *
 * ## 三条契约（与 [MusicSourceProvider] 一致，这里逐条落实）
 *
 * 1. **绝不抛异常**：所有入口都包在 `runCatching` 里，失败返回 null / 空列表。
 * 2. **导出的 [SongItem] 必须带上音源**：[BiliTrack.toSongItem] 是唯一出口。
 * 3. **不把平台内部标识当 id**：`bvid` 放 [SongItem.sourceId]，id 是合成数字 id。
 *
 * ## 独立开关（铁律 24）
 *
 * [isEnabled] 是 [BiliPrefs] 的内存镜像。**每一条对外路径的第一行都判它** ——
 * 关掉之后 B 站不仅不出现在搜索结果里，而且**一个请求都不会发出去**
 * （`BiliSourceProviderTest.关掉开关之后一个请求都不发` 用一个会计数的假传输层钉住）。
 */
object BiliSourceProvider : MusicSourceProvider {

    private const val TAG = "BiliSource"

    override val source: MusicSource = MusicSource.BILIBILI

    /**
     * B 站在本版**没有账号体系接入**（匿名可用，见 `bili-research/bili-auth.md`）。
     *
     * 返回 `false` 是**诚实**的答案，而不是「未实现的占位」：
     * UI 若为它显示「未登录」，那一行说的是事实 —— 我们确实没有登录态。
     * 但调用方**不该**把 B 站显示成一个「可以去登录」的音源
     * （设置页的登录入口按 `MusicSource.loginSources` 过滤，B 站不在其中）。
     */
    override val isLoggedIn: Boolean get() = false

    /** 用户是否启用了 B 站音源。**所有对外路径的第一道闸门。** */
    val isEnabled: Boolean get() = BiliPrefs.isEnabled()

    /**
     * v3.2.0 · P0-C：失败隔离的**唯一**入口（替代原来散落的 `runCatching`）。
     *
     * ## 为什么 `runCatching` 在本版不能再用了（两条硬理由）
     *
     * 1. **它把 `CancellationException` 当成失败一起吃掉。** v3.1.0 时无所谓 ——
     *    `BiliApi` 全是同步阻塞调用，整条腿没有挂起点，取消不会在里面发生。
     *    但本版给 `BiliApi` 加了真挂起点（`withContext(Dispatchers.IO)`），取消从此会在
     *    **这里**出现，而「取消是控制流不是错误」是本仓库 v2.5.5 就写下的纪律
     *    （`SearchViewModel` 里那句「取消必须原样抛出，不能落进 `catch (e: Exception)`」）。
     *    吞掉它的两个具体后果：① 用户每敲一个字（500ms debounce 后的 `searchJob.cancel()`）
     *    都会打一条 "search failed" 的**假日志**；② 嵌套超时抛出的
     *    `TimeoutCancellationException`（job 本身没被取消的那种）会被改写成「真的 0 条」——
     *    那正是 v2.5.5 用一整版修出来的「PENDING/TIMEOUT 不许显示成 DONE+0」的同一件事。
     * 2. **B 站每一次失败都必须有日志。** v3.1.0 的 P0-C 就是「异常被吞、用户只看到 B站 0 首、
     *    连一行错误都没有」：`BiliApi.wbiKeys` 的 `runCatching` 把主线程网络异常折叠成了 null。
     *    这里显式记 `Log.w`，让下一次同类问题在 logcat 里就能定位。
     *
     * ⚠️ 如实说明：Kotlin 的 `runCatching` 是 `inline` 的，所以「在 suspend 函数里包一个
     * suspend 调用」其实**编译得过**（本文件原来就是这么写的）。也就是说第 1 条不是编译问题，
     * 而是**语义**问题 —— 判据不能建立在「刚好能编译」的形状上。
     *
     * 契约不变（[MusicSourceProvider]）：**绝不把异常抛给调用方**（取消除外）。
     * 每一步也都仍然**有界**：这里的 try/catch 不引入任何重试，
     * 重试次数只由 `BiliQuality.fallbackLadder`（≤ 4 档）与 `BiliApi.signedGet`
     * 的「412/-403 只重签一次」决定（铁律 5）。
     */
    private suspend inline fun <T : Any> biliOrNull(tag: String, block: () -> T?): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, tag, e)
        null
    }

    /** 同 [biliOrNull]，失败时给一张空表（列表类接口专用）。 */
    private suspend inline fun <T : Any> biliOrEmpty(tag: String, block: () -> List<T>): List<T> = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, tag, e)
        emptyList()
    }

    /**
     * 搜索。
     *
     * 两条路径，按**关键词的形状**分派（这是音频区唯一可达的入口）：
     * 1. 关键词是 `au123456` 或含 `bilibili.com/audio/au123456` 的链接 ⇒ 直接按 auid 取详情；
     * 2. 其余 ⇒ Wbi 签名的视频搜索。
     *
     * ⚠️ 开关判据**必须是第一行**（铁律 24）：关掉时一个请求都不发 ——
     * v3.2.0 把调度挪进 `BiliApi` 之后，这条顺序**一个字没动**，
     * 由 `BiliSourceProviderTest.关掉开关之后一个请求都不发 —— 搜索返回空` 继续守着。
     */
    /**
     * v3.4.11：`page` **有意忽略** —— 以下两种情况它都没有意义：
     * ① `parseAuidKeyword` 命中的是「按 auid 直取一条」，本来就只有一条；
     * ② 视频搜索接口的 `page_size` 上限是 50 且**没有页码参数**（`BiliApi.searchVideos`
     *    只发 `page_size`）。所以 B 站的「加载更多」由 [hasMorePages] 的默认 `false`
     *    关掉 —— 界面上不会出现一个点了没反应的按钮。
     */
    override suspend fun searchSongs(
        keyword: String,
        limit: Int,
        page: Int,
        /** B 站**不填**总数：这条路径（视频搜索）的响应里没有可用的总数字段。 */
        totalOut: MutableMap<String, Int>?,
    ): List<SongItem> {
        if (!isEnabled || keyword.isBlank() || limit <= 0) return emptyList()
        parseAuidKeyword(keyword)?.let { auid ->
            val track = biliOrNull("audio info failed: auid=$auid") { BiliApi.audioInfo(auid) }
            return listOfNotNull(track?.toSongItem())
        }
        return biliOrEmpty("search failed: $keyword") { BiliApi.searchVideos(keyword, limit) }
            .mapNotNull { it.toSongItem() }
    }

    /**
     * 取链。
     *
     * ## 两条腿各有各的档位语义
     *
     * | 腿 | 档位怎么用 |
     * |---|---|
     * | **音频区**（`au:<auid>`） | 映射成 `qn`（0/1/2/3），按 [BiliQuality.fallbackLadder] 逐级下探 |
     * | **视频轨**（`bv:<bvid>:<cid>`） | v3.4.8 起**真的按档位选流**：无损档位拿 `dash.flac.audio`（大会员 Hi-Res），有损档位拿 `dash.audio[]` 里带宽最高的那条 |
     *
     * ⚠️ 视频轨那一侧在 v3.4.8 之前**完全不看 [level]** —— 它把 `dash.audio[]` 里
     * 带宽最高的一条直接交出去，于是「用户开了大会员、选了无损」也只能拿到 192K AAC。
     * 这就是「B 站音源无论如何都播放普通版本」的根因（详见 [BiliParse.parseDashAudios]）。
     *
     * ## 用户设置的两个作用点（v3.4.8 · 问题 3）
     *
     * 1. [BiliPrefs.qualityCap]：把全局档位换算成本源的**有效档位**
     *    （`BiliQualityCapRules.applyCap`）—— 它同时管住两条腿；
     * 2. [BiliPrefs.preferFlac]：只在视频轨「FLAC 与 AAC 都有」时决定选哪个。
     *
     * 两者都**只影响选择**，不改写服务端给的任何事实：拿不到 FLAC 时仍然按
     * `fallbackLadder` / AAC 兜底，并由 `SongUrlResult.levelFromFile` 如实标出「已降级」。
     */
    override suspend fun resolveUrl(song: SongItem, level: String): SongUrlResult? {
        if (!isEnabled) return null
        val payload = BiliTrack.parseSourceId(song.sourceId)
        if (payload == null) {
            Log.w(TAG, "unresolvable bili sourceId=${song.sourceId} id=${song.id}")
            return null
        }
        // v3.4.8：用户设置在这里**一次性**换算成有效档位，两条腿共用同一个值 ——
        // 分成两处各算一次，就会出现「音频区被限到 320K、视频轨还是无损」这种漂移。
        val effectiveLevel = BiliQualityCapRules.applyCap(level, BiliPrefs.qualityCap())
        val ladder = BiliQuality.fallbackLadder(effectiveLevel)
        if (payload.isAudioZone) {
            val auid = payload.auid!!
            for (qn in ladder) {
                val stream = biliOrNull("audio stream failed: auid=$auid qn=${qn.qn}") {
                    BiliApi.audioStream(auid, qn.qn)
                } ?: continue
                return stream.toResult(qn, effectiveLevel)
            }
            Log.w(TAG, "audio zone gave no stream: auid=$auid level=$level")
            return null
        }
        // 视频音轨：cid 可能已经在载荷里（预载时带下来的），没有就问一次。
        val bvid = payload.bvid ?: return null
        val cid = payload.cid ?: biliOrNull("view failed: bvid=$bvid") { BiliApi.videoCid(bvid) }
        if (cid == null || cid <= 0L) {
            Log.w(TAG, "no cid for bvid=$bvid")
            return null
        }
        val candidates = biliOrNull("playurl failed: bvid=$bvid cid=$cid") {
            BiliApi.videoAudioStreams(bvid, cid)
        } ?: return null
        val stream = BiliQuality.selectStream(candidates, effectiveLevel, BiliPrefs.preferFlac())
            ?: return null
        if (stream.kind != BiliAudioKind.AAC) {
            // Hi-Res / 杜比到手时的**唯一一条**可追溯记录：用户报障「还是普通音质」时，
            // 这一行能直接回答「服务端到底给没给 flac」——
            // 修复前这个信息在整条链路上不存在（代码根本没读那个字段）。
            Log.i(
                TAG,
                "video stream picked: $bvid/$cid kind=${stream.kind} br=${stream.br} " +
                    "of=${candidates.size} level=$effectiveLevel(requested=$level)",
            )
        }
        return stream.toVideoResult(effectiveLevel)
    }

    /**
     * 元数据补全。
     *
     * 队列从持久化恢复时只有 id 与 `sourceId`，标题/封面都是空的 —— 这里补齐。
     * 视频那一路的元数据在搜索时就拿到了（`SongItem` 进队列时带着），
     * 只有音频区需要再问一次 `song/info`（它同时把**歌词**带回来，见 [fetchLyric]）。
     */
    override suspend fun songDetail(song: SongItem): SongItem? {
        if (!isEnabled) return null
        val payload = BiliTrack.parseSourceId(song.sourceId) ?: return null
        if (!payload.isAudioZone) return song
        val auid = payload.auid!!
        val track = biliOrNull("audio info failed: auid=$auid") { BiliApi.audioInfo(auid) } ?: return null
        return track.toSongItem()
    }

    /**
     * 取歌词（**行级 LRC 原文**，解析交给既有的 `LrcParser`）。
     *
     * ## 两条腿各有各的歌词数据源（v3.3.0 补齐了视频那条）
     *
     * | 曲目形态 | 数据源 | 失败语义 |
     * |---|---|---|
     * | **音频区**（`au:<auid>`） | `/audio/music-service-c/web/song/lyric` 的 `data` 正文 | 空串 = 「这首歌确实没有歌词」 |
     * | **视频轨**（`bv:<bvid>:<cid>`） | **视频字幕**（`player/wbi/v2` → `ai-zh` 字幕 JSON → LRC） | null = 「没有可用字幕」 |
     *
     * ⚠️ **v3.1.0 的结论「视频音轨在 B 站上没有歌词数据源」是错的**，本版据实测推翻：
     * 漏掉的是**字幕**。实测（2026-10，登录态）12 条音乐视频里 **8 条有字幕**、
     * 其中 **5 条的字幕正文就是带时间轴的逐句歌词**（绝大多数是 B 站 AI 自动生成的
     * `ai-zh`）。真实样本见 [BiliSubtitle] 的类文档。
     *
     * 推翻它而不是留着，是因为那一版**只查了音频区**就下了「平台没有这个能力」的结论 ——
     * 这正是仓库那条「平台假设必须 A/B 对照」纪律要防的形状。
     *
     * ⚠️ 音频区那条路**不是** `song/info` 的 `lyric` 字段 —— 实测它是 LRC 文件的 **URL**，
     * 当正文用会解析出 0 行（见下面 [BiliParse.looksLikeUrl] 的守卫说明）。
     *
     * ## 三义性：null（没有数据源/取不到）vs 空串（确实没有歌词）
     *
     * 调用方（`PlayerViewModel.loadBiliLyrics`）按这个区分 [LyricLoadCoordinator.markEmpty]
     * 与 `fail` —— 把「网络失败」说成「这首歌没有歌词」会让重试按钮永久置灰，
     * 那是本版要从另一头修掉的缺陷。所以这里**绝不把失败折叠成空串**。
     */
    suspend fun fetchLyric(song: SongItem): String? {
        if (!isEnabled) return null
        val payload = BiliTrack.parseSourceId(song.sourceId) ?: return null
        if (payload.isAudioZone) {
            // ★ 走 `/song/lyric` 拿**正文**。
            //
            // 曾经写成「取 `song/info` 的 lyric 字段」——那是一个**已修的缺陷**：
            // 该字段实测是 LRC 文件的 **URL**（`…/149994607539.lrc`），把它交给 `LrcParser`
            // 会解析出 0 行 ⇒ 界面永远「暂无歌词」，而且不报任何错。
            // 证据：`docs/verification/v3.1.0/bili-research/evidence/02-songinfo-au39.txt`
            // 与 `21-lyric-au39.txt`（后者是 `/song/lyric` 的真实正文）。
            val raw = biliOrNull("lyric failed: auid=${payload.auid}") { BiliApi.audioLyric(payload.auid!!) }
            if (raw == null) return null
            // 个别曲目服务端在 `data` 里给的是 .lrc 的 URL。**不在取词路径上再发一次网络**：
            // 那会把一次播放变成两次往返，而且失败面还多一个。如实返回 null（没有可解析的正文），
            // 让界面显示「暂无歌词」—— 比拿 URL 去解析出 0 行要诚实。
            if (BiliParse.looksLikeUrl(raw)) return null
            return raw
        }
        // 视频轨：字幕 → LRC。
        val bvid = payload.bvid ?: return null
        // ── v3.4.8（问题 2）：**用户不想抓字幕时，一个请求都不发** ────────────────────
        //
        // 判断必须在 `videoCid` **之前**：取 cid 本身就是一次网络往返，
        // 而「用户关掉了字幕抓取」是一个纯本地的决定，没有任何理由为它付费。
        // 返回值是 null（= 「没有数据源」）而不是空串（= 「这首歌确实没有歌词」）：
        // 关掉之后视频轨**确实没有任何歌词数据源**，这正是 null 的语义；
        // 用空串会让上层把它记成「稳定空态」，将来用户打开开关也不会重取。
        val subtitlePref = BiliPrefs.subtitleLang()
        if (!subtitlePref.fetches) {
            Log.i(TAG, "subtitle disabled by user: $bvid")
            return null
        }
        // ── v3.3.1 · P0：cid **必须像取流那条路一样补问一次** ────────────────────────
        //
        // 这是我上一版引入的缺陷（用户实测「B站歌词还是拉取不上，账号已登录」）。
        //
        // 背景：视频搜索接口**不返回 cid**（`BiliParse.parseSearchTracks` 只给 bvid/aid），
        // 所以 `toSourceId` 产出的是 `bv:<bvid>:**0**`，而 `parseSourceId` 里的
        // `takeIf { it > 0L }` 会把它还原成 `null`。也就是说**正常播放路径下
        // `payload.cid` 恒为 null**。
        //
        // 取流那条路早就知道这一点，所以它写的是
        // `payload.cid ?: biliOrNull { BiliApi.videoCid(bvid) }`（见 [fetchStream]）；
        // 而我在取词这条路上只写了 `payload.cid ?: return null` ——
        // 于是**字幕链路一次请求都没发过**，用户看到的永远是「暂无歌词」，
        // 与「这首歌没有字幕」在界面上完全同形。
        //
        // 教训属于仓库已有的那一条：**同一个前提在两条链路上不能有两种写法**。
        // 取流要 cid、取词也要 cid，那就都得补问。
        val cid = payload.cid ?: biliOrNull("view failed for subtitle: bvid=$bvid") {
            BiliApi.videoCid(bvid)
        }
        if (cid == null || cid <= 0L) {
            Log.w(TAG, "subtitle skipped: no cid for bvid=$bvid")
            return null
        }
        // ⚠️ 这里**不能**用 `biliOrNull { videoSubtitleUrl(...) } ?: return null`。
        //
        // `videoSubtitleUrl` 把两种完全不同的情况折叠成了同一个 null：
        // 「请求失败/风控」（可重试）与「**这个视频没有字幕**」（稳定属性，重试不会变）。
        // 上层按这个区分处置（null ⇒ `fail` 保持可点；空串 ⇒ `markEmpty` 稳定空态），
        // 折叠之后「视频本来就没字幕」的用户会看到一个永远点不出结果的按钮
        // —— 那正是 v3.3.0 从另一头修掉的缺陷形状。
        //
        // 所以这里自己接异常：**抛了** ⇒ 取不到（null）；**没抛且返回空** ⇒
        // 「字幕列表拿到了，但里面没有可用字幕」⇒ 稳定空态（空串）。
        val url = try {
            // v3.4.8：把用户的语言偏好展开成**有序标签表**再交给传输层。
            // 展开（产品语义）与匹配（协议知识）都住在 `BiliSubtitle` 里 —— 纯逻辑、有单测；
            // `BiliApi` 只负责把那串标签带进请求与选择，不自己解释「auto 是什么意思」。
            BiliApi.videoSubtitleUrl(
                bvid = bvid,
                cid = cid,
                preferredTags = BiliSubtitle.preferredTags(
                    subtitlePref,
                    com.takahashirinta.ncrust.ui.i18n.currentLanguageCode(),
                ),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "subtitle list failed: $bvid/$cid", e)
            return null
        }
        if (url.isNullOrBlank()) {
            // 未登录时服务端的行为就是这一支：`code:0` + 空的 `subtitles` 数组（实测）。
            // 它是**稳定**的 —— 要么这个视频没有字幕，要么当前身份拿不到。
            Log.i(TAG, "subtitle none: $bvid/$cid（列表为空 ⇒ 稳定空态，不给可点的重试）")
            return ""
        }
        val body = biliOrNull("subtitle body failed: $bvid/$cid") { BiliApi.subtitleBody(url) } ?: return null
        val cues = BiliSubtitle.parseCues(body)
        val lyric = BiliSubtitle.lyricCues(cues)
        // 有字幕但判定「不是歌词」（MV 合集的对白、访谈）⇒ 空串 = 「这首确实没有歌词正文」。
        // **不是** null：字幕源是通的，只是内容不是歌词 —— 重试一百次也不会变。
        if (lyric.isEmpty()) {
            Log.i(TAG, "subtitle is not lyrics: $bvid/$cid cues=${cues.size}")
            return ""
        }
        Log.i(TAG, "subtitle -> lyric: $bvid/$cid cues=${cues.size} kept=${lyric.size}")
        return BiliSubtitle.lyricLrc(lyric)
    }

    /**
     * 关键词 → auid。
     *
     * 认三种形状（都是「用户明确给了一个音频」的写法）：
     * `au2478206` / `2478206`（**纯数字不认** —— 那更像一首歌名的一部分，猜错会把搜索变成一次必然失败的取详情）/ `bilibili.com/audio/au2478206`。
     *
     * ⚠️ 纯数字**故意不认**：搜索框里输入「105」的人想搜的是歌，不是 auid=105 的音频。
     */
    internal fun parseAuidKeyword(keyword: String): Long? {
        val v = keyword.trim()
        val m = Regex("(?:^|/|\\b)au(\\d{3,})$", RegexOption.IGNORE_CASE).find(v)
            ?: Regex("bilibili\\.com/audio/au(\\d+)", RegexOption.IGNORE_CASE).find(v)
            ?: return null
        return m.groupValues[1].toLongOrNull()?.takeIf { it > 0L }
    }

    /**
     * [BiliStream] → [SongUrlResult]（**音频区**那一腿）。`expiresAtMs` 在这里被带出去（铁律 26）。
     *
     * `actualLevel` 仍然是请求的那个 `qn` 名字：音频区这条路的「实际档位」判据是
     * **URL 文件名**（`qualityLabelOfFileName` 得到 `320K` / `192K` / `128K` / `FLAC`），
     * 它不是 `QualityLadder` 的档位名，硬塞进 `actualLevel` 会让
     * `QualityAssessment.levels.indexOf` 得到 -1。真正「如实标出降级」的载体是
     * [SongUrlResult.fallbackFromLevel] —— 它在 `requested.name != qualityLabel` 时非空。
     */
    private fun BiliStream.toResult(requested: BiliQn?, requestedLevel: String): SongUrlResult {
        // ★ 真实档位来自**文件名**（服务端自己写进去的 `-320k.m4a`），不是我们请求的 qn。
        //   服务端会静默降级（请求 qn=3 拿到 type:2），按请求档位回显就是 v2.1.4 修掉的
        //   「标签写高」——界面写着无损、耳朵听到 320K。
        val actual = BiliQuality.levelOfAudioLabel(qualityLabel)
        return toResult(
            actualLevel = actual ?: requested?.name ?: "bili-dash",
            // 两套词表（本应用档位 vs 文件名标签）不可直接比字符串：曾经 `"lossless" != "FLAC"`
            // 让**每一次成功拿到 FLAC 都被记成一次降级**。所以先归一化再比刻度。
            fallbackFromLevel = actual
                ?.let { BiliQuality.fallbackFrom(requestedLevel, it) }
                ?: requested?.name?.takeIf { it != qualityLabel },
        )
    }

    /**
     * [BiliStream] → [SongUrlResult]（**视频轨**那一腿，v3.4.8）。
     *
     * 与音频区那条路的**唯一**区别是 `actualLevel` 的来源：视频轨没有「请求的 qn」
     * 这回事（DASH 是按档位从候选里挑），所以档位只能由**挑中的那条流自己**反推
     * （[BiliQuality.levelOf]）。这与 `levelFromFile = true` 的声明是一致的 ——
     * 修复前这里写的是请求档位，那条声明是假的。
     *
     * @param requestedLevel 用户（经音质上限换算后）请求的档位，只用于
     *   [SongUrlResult.fallbackFromLevel]：挑中的流够不上它时如实记下来，
     *   界面据此显示「已降级」。
     */
    private fun BiliStream.toVideoResult(requestedLevel: String): SongUrlResult {
        val actual = BiliQuality.levelOf(this)
        return toResult(
            actualLevel = actual,
            // 只在**真的降级**时记：请求 lossless 而视频只有 Hi-Res FLAC 时
            // 实际档位比请求**高**（hires > lossless），那不是 fallback。
            fallbackFromLevel = BiliQuality.fallbackFrom(requestedLevel, actual),
        )
    }

    /**
     * 两条腿共用的出口。`BiliCdn.markStream` 在这里、且**只在这里**被调用。
     *
     * v3.2.4 · P0：把「这条流是 B 站刚发给我们的」记进 BiliCdn 的有界集合。
     *
     * 为什么必须在这里记：B 站的 CDN 会落到**与 bilibili 无关的第三方 PCDN 域名**
     * （v3.1.0 实测抓到 `b-…edge.mountaintoys.cn`），只按域名后缀判断会漏掉它们，
     * 于是那些歌连 Referer/UA 都拿不到 ⇒ 403。而「出处」是比域名更可靠的事实。
     *
     * 这是 markStream 的**唯一**生产调用点，所以集合里的每个 host 都可追溯到
     * 一次真实的 B 站取链响应；UI / 队列 / 持久化里的 host 进不来。
     */
    private fun BiliStream.toResult(actualLevel: String, fallbackFromLevel: String?): SongUrlResult {
        BiliCdn.markStream(url)
        return SongUrlResult(
            url = url,
            actualLevel = actualLevel,
            br = br,
            type = container,
            songMaxLevel = null,
            // ★ 实际档位来自**文件后缀/编码**（`-192k.m4a` / DASH 的 `kind` + `bandwidth`），
            //   不是服务端给的标签。标成 true，`QualityAssessment` 才会如实显示「已降级」。
            levelFromFile = true,
            fallbackFromLevel = fallbackFromLevel,
            expiresAtMs = expiresAtMs,
        )
    }
}
