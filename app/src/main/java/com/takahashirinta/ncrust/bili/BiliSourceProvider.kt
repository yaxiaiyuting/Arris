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
     * 搜索。
     *
     * 两条路径，按**关键词的形状**分派（这是音频区唯一可达的入口）：
     * 1. 关键词是 `au123456` 或含 `bilibili.com/audio/au123456` 的链接 ⇒ 直接按 auid 取详情；
     * 2. 其余 ⇒ Wbi 签名的视频搜索。
     */
    override suspend fun searchSongs(keyword: String, limit: Int): List<SongItem> {
        if (!isEnabled || keyword.isBlank() || limit <= 0) return emptyList()
        parseAuidKeyword(keyword)?.let { auid ->
            val track = runCatching { BiliApi.audioInfo(auid) }.getOrNull()
            return listOfNotNull(track?.toSongItem())
        }
        return runCatching { BiliApi.searchVideos(keyword, limit) }
            .onFailure { Log.w(TAG, "search failed: $keyword", it) }
            .getOrDefault(emptyList())
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
                val stream = runCatching { BiliApi.audioStream(auid, qn.qn) }.getOrNull() ?: continue
                return stream.toResult(qn)
            }
            Log.w(TAG, "audio zone gave no stream: auid=$auid level=$level")
            return null
        }
        // 视频音轨：cid 可能已经在载荷里（预载时带下来的），没有就问一次。
        val bvid = payload.bvid ?: return null
        val cid = payload.cid ?: runCatching { BiliApi.videoCid(bvid) }.getOrNull()
        if (cid == null || cid <= 0L) {
            Log.w(TAG, "no cid for bvid=$bvid")
            return null
        }
        val stream = runCatching { BiliApi.videoAudioStream(bvid, cid) }.getOrNull() ?: return null
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
        val track = runCatching { BiliApi.audioInfo(auid) }.getOrNull() ?: return null
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
        val raw = runCatching { BiliApi.audioLyric(payload.auid!!) }.getOrNull()
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
    private fun BiliStream.toResult(requested: BiliQn?): SongUrlResult = SongUrlResult(
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
