/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · P0-C：列表预加载（进入列表时预取前 N 首的封面与元数据）。
 */

package com.takahashirinta.ncrust.warmup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import coil.Coil
import coil.request.ImageRequest
import com.takahashirinta.ncrust.network.BoundedParallel
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSourceProvider
import com.takahashirinta.ncrust.source.SourceRouter
import com.takahashirinta.ncrust.source.isResolvable
import com.takahashirinta.ncrust.source.musicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 列表预加载（v3.1.0 · P0-C）。**只预取封面与元数据，绝不预取 URL。**
 *
 * ## 为什么不预取 URL（这不是保守，是必需）
 *
 * URL 有时效：ncm 实测会轮换、B 站的直链自带 `deadline`（实测 `now + 7200s`）。
 * 用户「进入歌单」之后可能几分钟才点第一首，也可能直接划走 ——
 * 预取一批 URL 等于制造一批**必然过期**的条目，它们要么被 TTL 判据丢掉（白花流量），
 * 要么在临界点上被用掉（播到一半 403）。铁律 22 与探针结论都指向同一条：
 * **URL 只在「下一首」这一个确定的位置上预取**（见 `PlayerViewModel.preloadNextSong`）。
 *
 * ## N 为什么按网络类型分档（WiFi 5 / 移动 2 / 离线 0）
 *
 * 一张列表首屏大约可见 4~5 行，所以 WiFi 下取 5 刚好覆盖首屏；
 * 移动数据下取 2 是「既让第一屏不空，又不为一次滚动花掉几百 KB」的折中
 * （用户点进列表往往只是看一眼）。离线时**一个请求都不发**。
 *
 * ## 与 `AppWarmup` 的关系
 *
 * `AppWarmup` 预取的是**首页**那 18 张封面（冷启动专用）。本对象是它的一般化：
 * 任何列表都能调，且带**按列表身份的去重**（同一张列表滚动时不重复预取）。
 */
object ListPrefetch {

    private const val TAG = "ListPrefetch"

    /** WiFi 下的预取条数。5 = 一张列表首屏大约可见的行数。 */
    const val WIFI_LIMIT = 5

    /** 移动数据下的预取条数。2 = 「第一屏不空」与「别花流量」的折中。 */
    const val MOBILE_LIMIT = 2

    /** 封面解码尺寸，与 `AppWarmup.COVER_PX` 同口径（列表 tile 约 160dp）。 */
    private const val COVER_PX = 320

    /** 同一张列表在这个窗口内只预取一次（用户来回切 tab 不该反复打网络）。 */
    private const val DEDUPE_WINDOW_MS = 60_000L

    /** 去重表的上限。超过就整体清空 —— 它只是一个「最近预取过谁」的备忘，不需要 LRU 精度。 */
    private const val DEDUPE_MAX = 32

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** listKey → 上次预取时刻。只在 IO 线程访问。 */
    private val recent = LinkedHashMap<String, Long>()

    /**
     * 本次该预取多少首。
     *
     * 判据与 `PlayerViewModel.isOnWifi()` 同源（读 `NetworkCapabilities`），
     * 但**判不出网络时返回 0**：宁可什么都不预取，也不要在计费网络上按 WiFi 的量去拉。
     */
    fun limitFor(context: Context): Int {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return 0
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return 0
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> WIFI_LIMIT
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> WIFI_LIMIT
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> MOBILE_LIMIT
            else -> 0
        }
    }

    /**
     * 进入一张列表时的入口。**立刻返回**（预取在后台跑），失败完全静默。
     *
     * @param listKey 列表身份（例如 `search:晴天` / `album:12345`）。用于去重。
     * @param songs 列表内容。只有前面的若干首会被碰。
     */
    fun prefetchList(context: Context, listKey: String, songs: List<SongItem>) {
        if (listKey.isBlank() || songs.isEmpty()) return
        val app = context.applicationContext
        val limit = limitFor(app)
        if (limit <= 0) return
        scope.launch {
            val now = System.currentTimeMillis()
            synchronized(recent) {
                val last = recent[listKey]
                if (last != null && now - last < DEDUPE_WINDOW_MS) return@launch
                if (recent.size >= DEDUPE_MAX) recent.clear()
                recent[listKey] = now
            }
            prefetchBlocking(app, songs.take(limit))
        }
    }

    /**
     * 预取一组歌曲的封面（+ 缺元数据的那些补一次 `songDetail`）。
     *
     * 并发上限走 [BoundedParallel.DEFAULT_MAX_CONCURRENCY]（4）—— 铁律 23。
     * **一个封面失败不影响别的**（[BoundedParallel] 的失败隔离），整体也绝不抛。
     */
    suspend fun prefetchBlocking(context: Context, songs: List<SongItem>) {
        if (songs.isEmpty()) return
        val loader = runCatching { Coil.imageLoader(context) }.getOrNull() ?: return
        val tasks = ArrayList<Pair<String, suspend () -> Boolean?>>(songs.size)
        for (song in songs) {
            val url = CoverUrls.small(song.album?.picUrl.orEmpty())
            if (!url.isNullOrBlank()) {
                tasks += "cover:${song.id}" to {
                    runCatching {
                        loader.execute(
                            ImageRequest.Builder(context)
                                .data(url)
                                .size(COVER_PX, COVER_PX)
                                .build(),
                        )
                    }
                    true
                }
            }
            // 元数据缺失的条目（从持久化恢复的队列、离线索引）补一次详情。
            // 判据是「连名字都没有」—— 有名有姓的条目再问一次纯属浪费。
            if (song.name.isBlank() && song.isResolvable) {
                tasks += "detail:${song.id}" to {
                    runCatching { detailOf(song) }.getOrNull()?.let { true }
                }
            }
        }
        if (tasks.isEmpty()) return
        runCatching { BoundedParallel.runAll(tasks) }
            .onFailure { Log.w(TAG, "prefetch failed for ${tasks.size} tasks", it) }
    }

    /** 预取单张封面（播放路径上的「这首歌的封面」用它）。失败静默。 */
    suspend fun prefetchCover(context: Context, rawUrl: String?) {
        val url = CoverUrls.small(rawUrl.orEmpty()) ?: return
        val loader = runCatching { Coil.imageLoader(context) }.getOrNull() ?: return
        runCatching {
            loader.execute(
                ImageRequest.Builder(context).data(url).size(COVER_PX, COVER_PX).build(),
            )
        }
    }

    private suspend fun detailOf(song: SongItem): SongItem? {
        val provider: MusicSourceProvider = SourceRouter.provider(song.musicSource) ?: return null
        return provider.songDetail(song)
    }

    /** 只给单测/诊断：清掉去重表。 */
    internal fun clearDedupeForTest() {
        synchronized(recent) { recent.clear() }
    }

    /** 只给诊断（探针）用的阻塞入口 —— 生产代码**不要**走它。 */
    internal fun prefetchBlockingForProbe(context: Context, songs: List<SongItem>) {
        runBlocking { prefetchBlocking(context, songs) }
    }
}
