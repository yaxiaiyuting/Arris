/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
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
    override suspend fun searchSongs(keyword: String, limit: Int): List<SongItem> {
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
     * 降级阶梯按 [BiliQuality.fallbackLadder] 逐级下探，**每一档只试一次**（铁律 5）。
     * 匿名下服务端一律给 192K，所以阶梯通常在第一档就命中 —— 保留它是为了
     * 「请求 FLAC 却拿不到」时不至于直接判死。
     */
    override suspend fun resolveUrl(song: SongItem, level: String): SongUrlResult? {
        if (!isEnabled) return null
        val payload = BiliTrack.parseSourceId(song.sourceId)
        if (payload == null) {
            Log.w(TAG, "unresolvable bili sourceId=${song.sourceId} id=${song.id}")
            return null
        }
        val ladder = BiliQuality.fallbackLadder(level)
        if (payload.isAudioZone) {
            val auid = payload.auid!!
            for (qn in ladder) {
                val stream = biliOrNull("audio stream failed: auid=$auid qn=${qn.qn}") {
                    BiliApi.audioStream(auid, qn.qn)
                } ?: continue
                return stream.toResult(qn)
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
        val stream = biliOrNull("playurl failed: bvid=$bvid cid=$cid") {
            BiliApi.videoAudioStream(bvid, cid)
        } ?: return null
        return stream.toResult(null)
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
     * ## 诚实降级（不是「顺手返回空」）
     *
     * 视频音轨在 B 站上**没有歌词数据源**：
     * - 音乐区视频的字幕（`player/wbi/v2` 的 `subtitle`）需要登录才给 `subtitle_url`，
     *   且绝大多数音乐区视频根本没有字幕；
     * - 音频区那条路的歌词是 `song/info` 的 `lyric` 字段，与视频无关。
     *
     * 所以这里对视频返回 **null**（= 「没有这个数据源」），对音频区返回
     * **`/audio/music-service-c/web/song/lyric` 的正文**（可能是空串 = 「这首歌确实没有歌词」）。
     * ⚠️ **不是** `song/info` 的那个 `lyric` 字段 —— 实测它是 LRC 文件的 URL，
     * 当正文用会解析出 0 行（见 [fetchLyric] 函数体里的说明）。
     * 两种情况的 UI 表现都是「暂无歌词」，但语义不同 —— 与 `LyricLoadCoordinator.State`
     * 把 [空] 与 [失败] 分开是同一条纪律。
     */
    suspend fun fetchLyric(song: SongItem): String? {
        if (!isEnabled) return null
        val payload = BiliTrack.parseSourceId(song.sourceId) ?: return null
        if (!payload.isAudioZone) return null
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

    /** [BiliStream] → [SongUrlResult]。`expiresAtMs` 在这里被带出去（铁律 26）。 */
    private fun BiliStream.toResult(requested: BiliQn?): SongUrlResult {
        // v3.2.4 · P0：把「这条流是 B 站刚发给我们的」记进 BiliCdn 的有界集合。
        //
        // 为什么必须在这里记：B 站的 CDN 会落到**与 bilibili 无关的第三方 PCDN 域名**
        // （v3.1.0 实测抓到 `b-…edge.mountaintoys.cn`），只按域名后缀判断会漏掉它们，
        // 于是那些歌连 Referer/UA 都拿不到 ⇒ 403。而「出处」是比域名更可靠的事实。
        //
        // 这是 markStream 的**唯一**生产调用点，所以集合里的每个 host 都可追溯到
        // 一次真实的 B 站取链响应；UI / 队列 / 持久化里的 host 进不来。
        BiliCdn.markStream(url)
        return SongUrlResult(
            url = url,
            actualLevel = requested?.name ?: "bili-dash",
            br = br,
            type = container,
            songMaxLevel = null,
            // ★ 实际档位来自**文件后缀/编码**（`-192k.m4a` / DASH 的 bandwidth），
            //   不是我们请求的那个 qn。匿名请求 qn=3 也只会拿到 192K ——
            //   把 levelFromFile 标成 true，QualityAssessment 才会如实显示「已降级」。
            levelFromFile = true,
            fallbackFromLevel = requested?.name?.takeIf { it != qualityLabel },
            expiresAtMs = expiresAtMs,
        )
    }
}
