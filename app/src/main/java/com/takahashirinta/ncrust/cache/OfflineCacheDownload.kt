/* Arris · Copyright (c) 2026 yaxiaiyuting, GPLv3. */
package com.takahashirinta.ncrust.cache

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.LiveUpdateKind
import com.takahashirinta.ncrust.player.LiveUpdateNotifier
import com.takahashirinta.ncrust.player.SongUrlResult
import com.takahashirinta.ncrust.player.progressPercent
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.source.isResolvable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/** One user-requested full-track cache job. Uses the same Media3 disk cache and eviction policy as playback. */
@OptIn(UnstableApi::class)
internal object OfflineCacheDownload {
    private const val DOWNLOAD_TIMEOUT_MS = 5 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var active: Task? = null

    internal data class Progress(val song: SongItem, val percent: Int?)
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress = _progress.asStateFlow()

    private class Task(val app: Context, val song: SongItem) {
        var job: Job? = null
        var writer: CacheWriter? = null
        var canceled = false
    }

    /** The cache index uses song keys. Keep this limited to sources that can reopen those keys offline. */
    fun supports(song: SongItem): Boolean {
        val source = TrackKey.ofSong(song).source
        return song.id > 0 && song.isResolvable &&
            (source == MusicSource.NETEASE || source == MusicSource.QQMUSIC)
    }

    @Synchronized
    fun start(
        context: Context,
        song: SongItem,
        resolve: suspend () -> SongUrlResult?,
        resolvedIsCurrent: () -> Boolean,
        onFinished: suspend (Boolean) -> Unit,
    ): Boolean {
        if (active != null || !supports(song)) return false
        val task = Task(context.applicationContext, song)
        active = task
        publish(task, null)
        task.job = scope.launch {
            var completed = false
            try {
                val result = withTimeout(20_000L) { resolve() } ?: return@launch
                if (!resolvedIsCurrent()) return@launch
                withTimeout(DOWNLOAD_TIMEOUT_MS) { cache(task, result, resolvedIsCurrent) }
                completed = true
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.w("OfflineCacheDownload", "Cache request timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("OfflineCacheDownload", "Cache request failed (${e.javaClass.simpleName})")
            } finally {
                if (finish(task) && !task.canceled) {
                    onFinished(completed)
                }
            }
        }
        return true
    }

    private suspend fun cache(task: Task, resolved: SongUrlResult, resolvedIsCurrent: () -> Boolean) {
        val app = task.app
        val resolvedUri = Uri.parse(resolved.url)
        val resolvedKey = requireNotNull(OfflineKeys.keyOf(resolved.url))
        check(resolvedUri.scheme in setOf("http", "https") &&
            OfflineKeys.songIdOf(resolvedKey) == task.song.id &&
            OfflineAudioCache.cacheKeyFor(resolved.url, resolvedUri.host) == resolvedKey)
        val result = OfflineUrlStore.rememberResult(app, resolved)
        val uri = Uri.parse(result.url)
        val key = requireNotNull(OfflineKeys.keyOf(result.url))
        val mediaCache = OfflineAudioCache.get(app)
        val maxBytes = OfflineAudioCache.maxBytes(app)
        val writer = CacheWriter(
            OfflineAudioCache.dataSourceFactory(app).createDataSourceForDownloading(),
            DataSpec.Builder().setUri(uri).setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build(),
            null,
        ) { length, cached, _ ->
            if (!resolvedIsCurrent() || cached > maxBytes || length > maxBytes
            ) throw java.io.IOException("Cache authorization changed or file exceeds limit")
            publish(task, progressPercent(cached, length))
        }
        synchronized(this) {
            if (active !== task) return
            task.writer = writer
        }
        runInterruptible { writer.cache() }
        coroutineContext.ensureActive()
        synchronized(this) {
            if (active !== task || !resolvedIsCurrent()) throw CancellationException()
            val length = ContentMetadata.getContentLength(mediaCache.getContentMetadata(key))
            check(length != C.LENGTH_UNSET.toLong() && length > 0L && mediaCache.isCached(key, 0L, length))
            OfflineLibrary.record(
                app, task.song.id, task.song.name, task.song.artists?.joinToString(" / ") { it.name },
                task.song.album?.picUrl, task.song.duration ?: 0L, key, result.actualLevel,
            )
        }
    }

    @Synchronized
    private fun publish(task: Task, percent: Int?) {
        if (active !== task || task.canceled) return
        val value = Progress(task.song, percent)
        if (_progress.value == value) return
        _progress.value = value
        LiveUpdateNotifier.post(task.app, LiveUpdateKind.AUDIO_CACHE, task, "离线缓存", task.song.name, percent)
    }

    @Synchronized
    private fun finish(task: Task): Boolean {
        if (active !== task) return false
        active = null
        _progress.value = null
        LiveUpdateNotifier.cancel(task.app, LiveUpdateKind.AUDIO_CACHE, task)
        return true
    }

    @Synchronized
    fun cancel(songId: Long? = null): Job? {
        val task = active ?: return null
        if (songId != null && task.song.id != songId) return null
        task.canceled = true
        task.writer?.cancel()
        task.job?.cancel()
        LiveUpdateNotifier.cancel(task.app, LiveUpdateKind.AUDIO_CACHE, task)
        return task.job
    }
}
