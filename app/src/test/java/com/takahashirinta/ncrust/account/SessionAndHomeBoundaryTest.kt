package com.takahashirinta.ncrust.account

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.account.*
import com.takahashirinta.ncrust.auth.*
import com.takahashirinta.ncrust.cache.*
import com.takahashirinta.ncrust.network.DailyRecommendation
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import com.takahashirinta.ncrust.warmup.HomeFeed
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Local intercepted production-entry regressions; no real accounts or network. */
class SessionAndHomeBoundaryTest {
    private class MemoryContext : ContextWrapper(null) {
        val stores = mutableMapOf<String, MemoryPrefs>()
        var afterSnapshotRead: (() -> Unit)? = null
        val fullSnapshotReads = AtomicInteger()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val base = synchronized(stores) { stores.getOrPut(name) { MemoryPrefs() } }
            if (name != "ncrust_home_cache") return base
            return object : SharedPreferences by base {
                override fun getString(key: String, defValue: String?): String? {
                    val value = base.snapshot()[key] ?: return defValue
                    return value as String
                }

                override fun getAll(): MutableMap<String, *> {
                    fullSnapshotReads.incrementAndGet()
                    val result = base.snapshot().toMutableMap()
                    val action = afterSnapshotRead
                    afterSnapshotRead = null
                    action?.invoke()
                    return result
                }
            }
        }
    }
    private fun field(o: Any, name: String) = o.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private suspend fun settle(o: Any, scopeName: String = "scope") {
        val scope = field(o, scopeName).get(o) as CoroutineScope
        withTimeout(6000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
    }
    private suspend fun fixture(body: suspend CoroutineScope.(MemoryContext) -> Unit) = coroutineScope {
        val context = MemoryContext()
        val guard = field(CookieManager, "guard").get(CookieManager)
        val originals = listOf(CookieManager to "app", NeteaseTokenRefresher to "app", SessionMonitor to "appContext",
            SessionMonitor to "lastCheckAt", SessionMonitor to "lastRevision", SessionMonitor to "retryAfterAt",
            guard to "current")
            .map { (o,n) -> Triple(o,field(o,n),field(o,n).get(o)) }
        @Suppress("UNCHECKED_CAST")
        val state = field(SessionMonitor,"_state").get(SessionMonitor) as MutableStateFlow<SessionState>
        val previous = state.value
        @Suppress("UNCHECKED_CAST")
        val loginGenerations = field(CookieManager, "_loginGenerations").get(CookieManager) as MutableStateFlow<Long>
        val previousGeneration = loginGenerations.value
        ContentCache.clearAll()
        field(CookieManager,"app").set(CookieManager,null)
        try {
            CookieManager.saveCookie(context,"MUSIC_U=fixture-account-A; __csrf=fixture-csrf")
            context.getSharedPreferences("ncrust_prefs",0).edit().putLong("session_refreshed_at",System.currentTimeMillis()).commit()
            body(context)
        } finally {
            settle(SessionMonitor);settle(NeteaseTokenRefresher)
            ContentCache.clearAll()
            state.value=previous
            originals.asReversed().forEach { (o,f,v) -> f.set(o,v) }
            loginGenerations.value = previousGeneration
        }
    }

    @Test fun businessFailureUsesShortRetryAndNeverSuccessfulTtl() = runBlocking {
        fixture { context ->
            var healthy=false;var requests=0
            val lazy=field(RetrofitClient,"plainClient\$delegate").get(RetrofitClient) as Lazy<*>
            val old=lazy.value;val value=field(lazy,"_value")
            val fake=OkHttpClient.Builder().dns(object : Dns { override fun lookup(hostname: String): List<java.net.InetAddress> = throw AssertionError("DNS prohibited") }).addInterceptor { chain ->
                requests++
                val r=chain.request()
                val payload = if (!healthy) """{"code":500,"message":"fixture service unavailable"}"""
                    else if (r.url.encodedPath.endsWith("/w/nuser/account/get"))
                        """{"code":200,"account":{"id":10001},"profile":{"userId":10001,"nickname":"fixture"}}"""
                    else """{"code":200,"data":{}}"""
                Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("local fixture")
                    .body(payload.toResponseBody()).build()
            }.build()
            value.set(lazy,fake)
            try {
                @Suppress("UNCHECKED_CAST")
                val state=field(SessionMonitor,"_state").get(SessionMonitor) as MutableStateFlow<SessionState>
                state.value=SessionState.Unknown
                SessionMonitor.refresh(context,true);settle(SessionMonitor)
                assertEquals(1,requests)
                assertEquals(SessionState.Unknown,state.value)
                healthy=true
                assertEquals(-1L, field(SessionMonitor,"lastCheckAt").getLong(SessionMonitor))
                SessionMonitor.onResume(context);settle(SessionMonitor)
                assertEquals("short cooldown prevents immediate hammering",1,requests)
                field(SessionMonitor,"retryAfterAt").setLong(SessionMonitor,0L)
                SessionMonitor.onResume(context);settle(SessionMonitor)
                assertTrue(state.value is SessionState.Valid)
                assertTrue(requests>1)
            } finally { value.set(lazy,old);fake.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun accountChangeWhileReadingSnapshotCannotPublishPreviousAccount() = runBlocking {
        fixture { context ->
            val ownerA=CookieManager.accountOwner()
            context.getSharedPreferences("ncrust_home_cache",0).edit()
                .putString("owner",ownerA)
                .putString("daily_songs","""[{"id":424242,"name":"account A private recommendation"}]""").commit()
            context.afterSnapshotRead={ CookieManager.saveCookie(context,"MUSIC_U=fixture-account-B; __csrf=fixture-csrf-B") }
            assertFalse(HomeSnapshot.restoreIntoCache(context))
            val ownerB=CookieManager.accountOwner()
            assertNotEquals(ownerA,ownerB)
            assertTrue(ContentCache.ownerMatches(ownerB))
            assertNull(ContentCache.homeDailySongs)
            assertFalse(context.getSharedPreferences("ncrust_home_cache",0).contains("daily_songs"))
        }
    }

    @Test fun matchingSnapshotRestoresNormallyWithoutAccountChange() = runBlocking {
        fixture { context ->
            val owner=CookieManager.accountOwner()
            context.getSharedPreferences("ncrust_home_cache",0).edit().putString("owner",owner)
                .putString("daily_songs","""[{"id":707,"name":"same owner"}]""").commit()
            assertTrue(HomeSnapshot.restoreIntoCache(context))
            assertTrue(ContentCache.ownerMatches(owner))
            assertEquals(707L,ContentCache.homeDailySongs!!.single().id)
            assertNull(ContentCache.freshHomeDailySongs(owner, DailyRecommendation.currentDayKey()))
        }
    }

    @Test fun partialSnapshotWriteDoesNotRelabelPreviousOwnerSections() = runBlocking {
        fixture { context ->
            val prefs = context.getSharedPreferences("ncrust_home_cache", 0)
            prefs.edit().putString("owner", "older-owner")
                .putString("daily_songs", """[{"id":101,"name":"older account"}]""").commit()

            HomeSnapshot.save(context, newSongs = listOf(homeSong(202)))
            settle(HomeSnapshot, "ioScope")

            assertEquals(0, context.fullSnapshotReads.get())
            assertEquals(CookieManager.accountOwner(), prefs.getString("owner", null))
            assertFalse(prefs.contains("daily_songs"))
            assertTrue(HomeSnapshot.restoreIntoCache(context))
            assertNull(ContentCache.homeDailySongs)
            assertEquals(202L, ContentCache.homeNewSongs!!.single().id)
        }
    }

    @Test fun invalidStoredOwnerDoesNotBecomeGuestData() = runBlocking {
        fixture { context ->
            CookieManager.clearCookie(context)
            val prefs = context.getSharedPreferences("ncrust_home_cache", 0)
            prefs.edit().putLong("owner", 42L)
                .putString("daily_songs", """[{"id":101,"name":"unknown account"}]""").commit()

            HomeSnapshot.save(context, newSongs = listOf(homeSong(202)))
            settle(HomeSnapshot, "ioScope")

            assertFalse(prefs.contains("owner"))
            assertFalse(prefs.contains("daily_songs"))
            assertEquals(0, context.fullSnapshotReads.get())
            assertTrue(HomeSnapshot.restoreIntoCache(context))
            assertEquals(202L, ContentCache.homeNewSongs!!.single().id)
        }
    }

    @Test fun slowSnapshotWriteKeepsNewerSectionsAndFillsUntouchedOnes() = runBlocking {
        fixture { context ->
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val olderDaily = object : AbstractList<SongItem>() {
                override val size: Int = 1
                override fun get(index: Int): SongItem {
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                    return homeSong(101)
                }
            }
            val playlist = PlaylistApi.PlaylistCard(303, "other section", "", 0)
            val scope = field(HomeSnapshot, "ioScope").get(HomeSnapshot) as CoroutineScope
            HomeSnapshot.save(context, daily = olderDaily, playlists = listOf(playlist))
            try {
                withTimeout(5_000) { entered.await() }
                val older = scope.coroutineContext[Job]!!.children.single()
                HomeSnapshot.save(context, daily = listOf(homeSong(202)))
                withTimeout(5_000) {
                    scope.coroutineContext[Job]!!.children.filter { it !== older }.toList().joinAll()
                }
            } finally {
                release.countDown()
                settle(HomeSnapshot, "ioScope")
            }

            assertTrue(HomeSnapshot.restoreIntoCache(context))
            assertEquals(202L, ContentCache.homeDailySongs!!.single().id)
            assertEquals(listOf(playlist), ContentCache.homeRecommendPlaylists)
        }
    }

    @Test fun homeReadersShareASlowRequestAndOtherSectionsCanFinish() = runBlocking {
        fixture { context ->
            val dailyEntered = CompletableDeferred<Unit>()
            val releaseDaily = CountDownLatch(1)
            val dailyRequests = AtomicInteger()
            val newSongRequests = AtomicInteger()
            withHomeTransport({ request ->
                when (request.url.encodedPath) {
                    "/eapi/v2/discovery/recommend/songs" -> {
                        dailyRequests.incrementAndGet()
                        dailyEntered.complete(Unit)
                        check(releaseDaily.await(5, TimeUnit.SECONDS))
                        """{"code":200,"recommend":[{"id":101,"name":"Daily"}]}"""
                    }
                    "/api/v1/discovery/new/songs" -> {
                        newSongRequests.incrementAndGet()
                        """{"data":[{"id":202,"name":"New"}]}"""
                    }
                    else -> error("Unexpected local fixture path")
                }
            }) {
                val session = CookieManager.snapshot()
                val warmup = async(start = CoroutineStart.UNDISPATCHED) { HomeFeed.dailySongs(context, session) }
                try {
                    withTimeout(3_000L) { dailyEntered.await() }
                    assertNull(withTimeoutOrNull(750L) { HomeFeed.dailySongs(context, session) })
                    val reentered = async(start = CoroutineStart.UNDISPATCHED) { HomeFeed.dailySongs(context, session) }
                    assertEquals(202L, HomeFeed.newSongs(context, session).single().id)
                    assertEquals(202L, HomeFeed.newSongs(context, session).single().id)
                    assertFalse(warmup.isCompleted)
                    releaseDaily.countDown()
                    assertSame(warmup.await(), reentered.await())
                    assertEquals(101L, ContentCache.homeDailySongs!!.single().id)
                    assertEquals(1, dailyRequests.get())
                    assertEquals(1, newSongRequests.get())
                } finally {
                    releaseDaily.countDown()
                }
            }
        }
    }

    @Test fun homeRequestCannotPublishAfterLoginChangesAndFailedReadsCanRetry() = runBlocking {
        fixture { context ->
            val oldEntered = CompletableDeferred<Unit>()
            val releaseOld = CountDownLatch(1)
            val newRequests = AtomicInteger()
            withHomeTransport({ request ->
                if (request.header("Cookie").orEmpty().contains("fixture-account-A")) {
                    oldEntered.complete(Unit)
                    check(releaseOld.await(5, TimeUnit.SECONDS))
                    """{"data":[{"id":101,"name":"Old"}]}"""
                } else {
                    if (newRequests.incrementAndGet() == 1) throw IOException("local fixture failure")
                    """{"data":[{"id":202,"name":"New"}]}"""
                }
            }) {
                val old = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { HomeFeed.newSongs(context, CookieManager.snapshot()) }
                }
                try {
                    withTimeout(3_000L) { oldEntered.await() }
                    CookieManager.saveCookie(context, "MUSIC_U=fixture-account-B; __csrf=fixture-csrf-B")
                    val session = CookieManager.snapshot()
                    assertTrue(runCatching { HomeFeed.newSongs(context, session) }.exceptionOrNull() is IOException)
                    assertEquals(202L, HomeFeed.newSongs(context, session).single().id)
                    releaseOld.countDown()
                    assertTrue(old.await().isFailure)
                    assertEquals(202L, ContentCache.homeNewSongs!!.single().id)
                    assertEquals(2, newRequests.get())
                } finally {
                    releaseOld.countDown()
                }
            }
        }
    }

    private suspend fun withHomeTransport(
        response: (Request) -> String,
        block: suspend CoroutineScope.() -> Unit,
    ) = coroutineScope {
        val lazy = field(RetrofitClient, "plainClient\$delegate").get(RetrofitClient) as Lazy<*>
        val previous = lazy.value
        val value = field(lazy, "_value")
        val fake = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> =
                    throw AssertionError("DNS prohibited")
            })
            .addInterceptor { chain ->
                val request = chain.request()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                    .message("local fixture").body(response(request).toResponseBody()).build()
            }.build()
        value.set(lazy, fake)
        try {
            block()
        } finally {
            settle(HomeFeed)
            settle(HomeSnapshot, "ioScope")
            value.set(lazy, previous)
            fake.dispatcher.executorService.shutdown()
        }
    }

    @Test fun dailyBatchDateSurvivesRestoreAndExpiresAtSix() = runBlocking {
        fixture { context ->
            val owner = CookieManager.accountOwner()
            val beforeSix = LocalDateTime.of(2026, 11, 12, 5, 59)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val previousDay = DailyRecommendation.currentDayKey(beforeSix)
            val nextDay = DailyRecommendation.currentDayKey(beforeSix + 60_000L)
            assertEquals("2026-11-11", previousDay)
            assertEquals("2026-11-12", nextDay)

            HomeSnapshot.save(context, daily = listOf(homeSong(101)), dailyDayKey = previousDay)
            settle(HomeSnapshot, "ioScope")
            HomeSnapshot.save(context, newSongs = listOf(homeSong(202)))
            settle(HomeSnapshot, "ioScope")
            assertTrue(HomeSnapshot.restoreIntoCache(context))

            assertEquals(101L, ContentCache.freshHomeDailySongs(owner, previousDay)!!.single().id)
            assertNull(ContentCache.freshHomeDailySongs(owner, nextDay))
            assertNull(ContentCache.freshHomeDailySongs("different owner", previousDay))

            HomeSnapshot.save(context, daily = emptyList(), dailyDayKey = nextDay)
            settle(HomeSnapshot, "ioScope")
            val prefs = context.getSharedPreferences("ncrust_home_cache", 0)
            assertFalse(prefs.contains("daily_reco_daykey"))
            assertFalse(prefs.contains("daily_songs"))
        }
    }

    private fun homeSong(id: Long) = SongItem(id, "fixture-$id", null, null, 200_000)
}
