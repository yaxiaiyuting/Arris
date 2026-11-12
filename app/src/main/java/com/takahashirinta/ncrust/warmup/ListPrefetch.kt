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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 按网络类型预取列表首屏封面，同一列表短时间内只预取一次。
 */
object ListPrefetch {

    private const val TAG = "ListPrefetch"

    /** WiFi 下的预取条数。5 = 一张列表首屏大约可见的行数。 */
    const val WIFI_LIMIT = 5

    /** 移动数据下的预取条数。2 = 「第一屏不空」与「别花流量」的折中。 */
    private const val MOBILE_LIMIT = 2

    /** 封面解码尺寸，与 `AppWarmup.COVER_PX` 同口径（列表 tile 约 160dp）。 */
    private const val COVER_PX = 320

    /** 同一张列表在这个窗口内只预取一次（用户来回切 tab 不该反复打网络）。 */
    private const val DEDUPE_WINDOW_MS = 60_000L

    /** 去重表的上限。超过就整体清空 —— 它只是一个「最近预取过谁」的备忘，不需要 LRU 精度。 */
    private const val DEDUPE_MAX = 32

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 访问时持有 recent 的锁。 */
    private val recent = HashMap<String, Long>()

    private fun limitFor(app: Context): Int {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
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
        if (synchronized(recent) { wasPrefetchedRecently(listKey, System.currentTimeMillis()) }) return
        val app = context.applicationContext
        scope.launch {
            val limit = limitFor(app)
            if (limit <= 0) return@launch
            val now = System.currentTimeMillis()
            synchronized(recent) {
                // 并发进入同一列表时只让先登记的一次发请求。
                if (wasPrefetchedRecently(listKey, now)) return@launch
                if (recent.size >= DEDUPE_MAX) recent.clear()
                recent[listKey] = now
            }
            prefetchCovers(app, songs.take(limit))
        }
    }

    private fun wasPrefetchedRecently(listKey: String, now: Long): Boolean =
        recent[listKey]?.let { now - it < DEDUPE_WINDOW_MS } ?: false

    private suspend fun prefetchCovers(context: Context, songs: List<SongItem>) {
        val urls = songs.mapNotNullTo(LinkedHashSet(songs.size)) {
            CoverUrls.small(it.album?.picUrl)?.takeIf(String::isNotBlank)
        }
        if (urls.isEmpty()) return
        val loader = runCatching { Coil.imageLoader(context) }.getOrNull() ?: return
        val tasks = urls.map { url ->
            url to suspend {
                loader.execute(coverRequest(context, url))
                Unit
            }
        }
        runCatching { BoundedParallel.runAll(tasks) }
            .onFailure { Log.w(TAG, "prefetch failed for ${tasks.size} tasks", it) }
    }

    /** 预取单张封面（播放路径上的「这首歌的封面」用它）。失败静默。 */
    suspend fun prefetchCover(context: Context, rawUrl: String?) {
        val url = CoverUrls.small(rawUrl)?.takeIf(String::isNotBlank) ?: return
        val loader = runCatching { Coil.imageLoader(context) }.getOrNull() ?: return
        runCatching { loader.execute(coverRequest(context, url)) }
    }

    private fun coverRequest(context: Context, url: String): ImageRequest =
        ImageRequest.Builder(context).data(url).size(COVER_PX, COVER_PX).build()
}
