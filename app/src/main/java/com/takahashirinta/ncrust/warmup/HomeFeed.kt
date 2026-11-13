/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.warmup

import android.content.Context
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.auth.NeteaseSessionChangedException
import com.takahashirinta.ncrust.auth.NeteaseSessionSnapshot
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.cache.HomeSnapshot
import com.takahashirinta.ncrust.network.DailyRecommendation
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Home, startup and search share in-flight reads. Leaving one screen only cancels its wait. */
internal object HomeFeed {
    private const val NEW_SONGS_LIMIT = 20
    private const val READ_BUDGET_MS = 8_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val daily = Section<SongItem>(
        fresh = { owner, day -> ContentCache.freshHomeDailySongs(owner, requireNotNull(day)) },
        fetch = { PlaylistApi.getDailyRecommendSongs() },
        store = { app, owner, day, items ->
            ContentCache.putHomeDailySongs(items, day)
            HomeSnapshot.save(app, daily = items, owner = owner, dailyDayKey = day)
        },
    )
    private val playlists = Section(
        fresh = { owner, _ -> ContentCache.freshHomeRecommendPlaylists(owner) },
        fetch = PlaylistApi::getRecommendPlaylists,
        store = { app, owner, _, items ->
            ContentCache.putHomeRecommendPlaylists(items)
            HomeSnapshot.save(app, playlists = items, owner = owner)
        },
    )
    private val latest = Section(
        fresh = { owner, _ -> ContentCache.freshHomeNewSongs(owner) },
        fetch = { PlaylistApi.getTopSongs(limit = NEW_SONGS_LIMIT, offset = 0) },
        store = { app, owner, _, items ->
            ContentCache.putHomeNewSongs(items)
            HomeSnapshot.save(app, newSongs = items, owner = owner)
        },
    )
    private val charts = Section(
        fresh = { _, _ -> ContentCache.toplistItems.takeIf { ContentCache.isToplistFresh() } },
        fetch = { PlaylistApi.getToplists() },
        store = { app, owner, _, items ->
            ContentCache.putToplist(items)
            HomeSnapshot.save(app, toplists = items, owner = owner)
        },
    )

    suspend fun dailySongs(context: Context, session: NeteaseSessionSnapshot) =
        daily.load(context, session, day = DailyRecommendation.currentDayKey())

    suspend fun recommendedPlaylists(context: Context, session: NeteaseSessionSnapshot, force: Boolean = false) =
        playlists.load(context, session, force)

    suspend fun newSongs(context: Context, session: NeteaseSessionSnapshot, force: Boolean = false) =
        latest.load(context, session, force)

    suspend fun toplists(context: Context, session: NeteaseSessionSnapshot, force: Boolean = false) =
        charts.load(context, session, force)

    private data class Key(val loginGeneration: Long, val day: String?)
    private data class Flight<T>(val key: Key, val result: Deferred<List<T>>)

    private class Section<T>(
        private val fresh: (owner: String?, day: String?) -> List<T>?,
        private val fetch: suspend () -> List<T>,
        private val store: (app: Context, owner: String?, day: String?, items: List<T>) -> Unit,
    ) {
        private var flight: Flight<T>? = null

        suspend fun load(
            context: Context,
            started: NeteaseSessionSnapshot,
            force: Boolean = false,
            day: String? = null,
        ): List<T> {
            val key = Key(started.loginGeneration, day)
            val deferred = synchronized(this) {
                checkLogin(started)
                val previous = flight
                if (previous?.key == key && !previous.result.isCompleted && !previous.result.isCancelled) {
                    return@synchronized previous.result
                }
                if (!force) {
                    CookieManager.ifSameLogin(started) { fresh(it.accountOwner, day) }?.let { return it }
                }

                val app = context.applicationContext
                val next = scope.async(start = CoroutineStart.LAZY) {
                    checkLogin(started)
                    val items = withTimeoutOrNull(READ_BUDGET_MS) { fetch() }
                        ?: throw IOException("Home read timed out")
                    val request = currentCoroutineContext()
                    synchronized(this@Section) {
                        request.ensureActive()
                        CookieManager.ifSameLogin(started) { live ->
                            val owner = live.accountOwner
                            ContentCache.bindOwner(owner)
                            store(app, owner, day, items)
                        } ?: throw NeteaseSessionChangedException("Home login changed during read")
                    }
                    items
                }
                flight = Flight(key, next)
                previous?.result?.cancel()
                next.invokeOnCompletion {
                    synchronized(this) { if (flight?.result === next) flight = null }
                }
                next
            }
            return deferred.await()
        }

        private fun checkLogin(started: NeteaseSessionSnapshot) {
            if (CookieManager.snapshot().loginGeneration != started.loginGeneration) {
                throw NeteaseSessionChangedException("Home login changed before read")
            }
        }
    }
}
