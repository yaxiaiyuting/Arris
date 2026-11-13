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
import android.os.SystemClock
import android.util.Log
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.takahashirinta.ncrust.network.BoundedParallel
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.network.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 按网络类型预取列表首屏封面。同一 URL 正在加载或刚成功时复用结果。
 */
object ListPrefetch {

    private const val TAG = "ListPrefetch"

    /** WiFi 下的预取条数。5 = 一张列表首屏大约可见的行数。 */
    const val WIFI_LIMIT = 5

    /** 移动数据下的预取条数。2 = 「第一屏不空」与「别花流量」的折中。 */
    private const val MOBILE_LIMIT = 2

    /** 列表封面预解码尺寸。 */
    private const val COVER_PX = 320

    private val listSlots = Semaphore(BoundedParallel.DEFAULT_MAX_CONCURRENCY)
    private val recent = RecentCoverLoads(SystemClock::elapsedRealtime)

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

    suspend fun prefetchList(context: Context, songs: List<SongItem>) {
        if (songs.isEmpty()) return
        val app = context.applicationContext
        withContext(Dispatchers.IO) {
            val limit = limitFor(app)
            if (limit <= 0) return@withContext
            prefetchCovers(app, songs.asSequence().take(limit).map { it.album?.picUrl }.asIterable())
        }
    }

    /** Cold-start and page batches share the same URL deduplication and concurrency limit. */
    internal suspend fun prefetchCovers(context: Context, covers: Iterable<String?>) {
        val urls = covers.mapNotNullTo(LinkedHashSet()) {
            CoverUrls.small(it)?.takeIf(String::isNotBlank)
        }
        if (urls.isEmpty()) return
        val loader = runCatching { Coil.imageLoader(context) }.getOrNull() ?: return
        val tasks = urls.map { url ->
            suspend {
                recent.load(url) {
                    listSlots.withPermit { loader.execute(coverRequest(context, url)) is SuccessResult }
                }
            }
        }
        BoundedParallel.runAll(tasks)
    }

    /** 播放路径预取封面，使用独立于列表的调度。 */
    suspend fun prefetchCover(context: Context, rawUrl: String?) {
        val url = CoverUrls.small(rawUrl)?.takeIf(String::isNotBlank) ?: return
        val loader = runCatching { Coil.imageLoader(context) }.getOrNull() ?: return
        try {
            loader.execute(coverRequest(context, url))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Could not prefetch playback cover", e)
        }
    }

    private fun coverRequest(context: Context, url: String): ImageRequest =
        ImageRequest.Builder(context).data(url).size(COVER_PX, COVER_PX).build()
}

internal class RecentCoverLoads(private val nowMs: () -> Long) {
    private val inFlight = HashMap<String, CompletableDeferred<Boolean>>()
    private val completed = LinkedHashMap<String, Long>()

    suspend fun load(url: String, fetch: suspend () -> Boolean) {
        while (true) {
            currentCoroutineContext().ensureActive()
            var ownsLoad = false
            val flight = synchronized(this) {
                if (completed[url]?.let { nowMs() - it in 0 until WINDOW_MS } == true) return
                inFlight.getOrPut(url) {
                    ownsLoad = true
                    CompletableDeferred()
                }
            }
            if (ownsLoad) {
                fetchOwned(url, flight, fetch)
                return
            }
            // Retry only when the owner left the page. A failed HTTP response ends this batch.
            if (!flight.await()) return
        }
    }

    private suspend fun fetchOwned(url: String, flight: CompletableDeferred<Boolean>, fetch: suspend () -> Boolean) {
        var succeeded = false
        var cancelled = false
        try {
            succeeded = fetch()
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } finally {
            synchronized(this) {
                inFlight.remove(url)
                if (succeeded) {
                    completed.remove(url)
                    completed[url] = nowMs()
                    if (completed.size > MAX_RECENT) completed.remove(completed.keys.first())
                }
            }
            flight.complete(cancelled)
        }
    }

    private companion object {
        const val WINDOW_MS = 60_000L
        const val MAX_RECENT = 32 * ListPrefetch.WIFI_LIMIT
    }
}
