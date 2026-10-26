package com.takahashirinta.ncrust.library

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.auth.NeteaseTokenRefresher
import com.takahashirinta.ncrust.local.*
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.XeapiState
import com.takahashirinta.ncrust.qq.QqPlaylistRepository
import com.takahashirinta.ncrust.qq.QqPlaylistStore
import com.takahashirinta.ncrust.source.*
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Passing assertions establish defects in real business entry points, not repaired behavior. */
class ContinuationAuditProbe {
    private class FailingPrefs(private val memory: SharedPreferences = MemoryPrefs()) : SharedPreferences by memory {
        var fail = false
        override fun edit(): SharedPreferences.Editor {
            val editor = memory.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean { editor.commit(); return !fail }
            }
        }
    }
    private class MemoryContext : ContextWrapper(null) {
        val local = FailingPrefs()
        private val stores = mutableMapOf<String, SharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = synchronized(stores) {
            if (name == LocalPlaylistStore.PREFS) local else stores.getOrPut(name) { MemoryPrefs() }
        }
    }
    private val context = MemoryContext()
    private val restorers = mutableListOf<() -> Unit>()
    private val release = CountDownLatch(1)
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun replace(owner: Any, name: String, value: Any?) {
        val f = field(owner, name); val old = f.get(owner); f.set(owner, value); restorers.add { f.set(owner, old) }
    }
    private fun client(name: String, answer: (Request) -> String) {
        val lazy = field(RetrofitClient, name + "\$delegate").get(RetrofitClient) as Lazy<*>
        val old = lazy.value
        val value = field(lazy, "_value")
        val fake = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("audit")
                .body(answer(chain.request()).toResponseBody()).build()
        }.build()
        value.set(lazy, fake)
        restorers.add { value.set(lazy, old); fake.dispatcher.executorService.shutdown() }
    }
    private suspend fun settleWrites() {
        val scope = field(LibraryManager, "ioScope").get(LibraryManager) as CoroutineScope
        withTimeout(5000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
    }
    @Before fun setup() {
        replace(CookieManager, "app", null)
        replace(NeteaseTokenRefresher, "app", null)
        CookieManager.saveCookie(context, "MUSIC_U=fixture-A; __csrf=fixture-csrf-A")
        replace(XeapiState, "appContext", null)
        replace(XeapiState, "cachedKeyState", null)
        listOf("cachedEntries", "cachedAlbums", "cachedLikedIds", "pendingAppContext", "flushJob").forEach { replace(LibraryManager, it, null) }
        (field(LibraryManager, "latestLikeIntent").get(LibraryManager) as MutableMap<*, *>).clear()
        (field(LibraryManager, "likeMutexes").get(LibraryManager) as MutableMap<*, *>).clear()
        client("antiCheatClient") { "null([200,1,\"fixture-token\"])" }
        client("plainClient") { error("Unexpected outbound request: ${it.url.encodedPath}") }
    }
    @After fun cleanup() = runBlocking {
        release.countDown()
        settleWrites()
        CookieManager.clearCookie(context)
        restorers.asReversed().forEach { it() }
    }

    @Test fun oldAccountWorkerDropsNewAccountsLatestLikeIntent() = runBlocking {
        val entered = CountDownLatch(1)
        val bWrites = AtomicInteger()
        client("antiCheatClient") {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            "null([200,1,\"fixture-token\"])"
        }
        client("plainClient") {
            if (it.header("Cookie").orEmpty().contains("fixture-B")) bWrites.incrementAndGet()
            """{"code":200}"""
        }
        LibraryManager.saveSong(context, com.takahashirinta.ncrust.network.SongItem(101, "fixture", null, null, 200000))
        assertTrue(entered.await(4, TimeUnit.SECONDS))
        CookieManager.clearCookie(context)
        CookieManager.saveCookie(context, "MUSIC_U=fixture-B; __csrf=fixture-csrf-B")
        assertTrue(LibraryManager.removeSong(context, 101))
        release.countDown()
        settleWrites()
        assertEquals("B's unlike was silently discarded by A's stale worker", 0, bWrites.get())
        val pending = field(LibraryManager, "latestLikeIntent").get(LibraryManager) as Map<*, *>
        assertTrue(pending.isEmpty())
        assertFalse(LibraryManager.isSongSaved(context, 101))
        println("AUDIT: B's latest unlike disappears without any B-account mutation; local tombstone remains")
    }

    @Test fun lateAlbumRefreshErasesAnAlbumSubscribedWhileReading() = runBlocking {
        val entered = CountDownLatch(1)
        val subDone = CountDownLatch(1)
        client("plainClient") { request ->
            when (request.url.encodedPath) {
                "/eapi/w/nuser/account/get" -> """{"code":200,"account":{"id":10001}}"""
                "/eapi/user/playlist" -> """{"code":200,"playlist":[]}"""
                "/eapi/album/sublist" -> {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    """{"code":200,"data":[],"hasMore":false}"""
                }
                "/eapi/album/sub" -> { subDone.countDown(); """{"code":200}""" }
                else -> error("Unexpected ${request.url.encodedPath}")
            }
        }
        val refreshing = async(Dispatchers.Default) { LibraryManager.refreshFromCloud(context) }
        try {
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            LibraryManager.subscribeAlbum(context, AlbumInfo(77, "new album", "", "", 1))
            assertTrue(subDone.await(4, TimeUnit.SECONDS))
            assertEquals(listOf(77L), LibraryManager.getSavedAlbums(context).map { it.albumId })
            release.countDown()
            assertTrue(withTimeout(5000) { refreshing.await() })
            settleWrites()
            assertTrue("old empty cloud snapshot erased a newer successful subscription", LibraryManager.getSavedAlbums(context).isEmpty())
            assertTrue(SavedAlbumCodec.decode(context.getSharedPreferences("ncrust_library", 0).getString("saved_albums", null)).isEmpty())
            println("AUDIT: older empty album snapshot overwrites a newer local/cloud-success subscription")
        } finally { release.countDown(); refreshing.cancelAndJoin() }
    }

    @Test fun failedPartialSyncStillReportsAddedTracks() = runBlocking {
        val key = PlaylistKey(MusicSource.QQMUSIC, "333", "10001")
        val meta = LocalPlaylistStore.createPlaylist(context, key, "fixture", 1)
        replace(QqPlaylistRepository, "appContext", null)
        QqPlaylistRepository.init(context)
        val song = com.takahashirinta.ncrust.network.SongItem(SourceIds.qqId(101, "mid-101"), "fixture", null, null, 200000,
            source = "qqmusic", sourceId = "mid-101", mediaId = "file-101")
        QqPlaylistStore.writeDetail(context, key, listOf(song), System.currentTimeMillis(), complete = false, total = 100)
        context.local.fail = true
        val outcome = LocalPlaylistRepository.sync(context, meta, false, 2000)
        assertTrue("persistence failure still claims partial success: $outcome", outcome is LocalSyncOutcome.Partial)
        assertEquals(1, (outcome as LocalSyncOutcome.Partial).added)
        assertTrue("nothing was committed", LocalPlaylistStore.readTracks(context, key).isEmpty())
        println("AUDIT: disk failure rolled data back but sync reports Partial(added=1,total=1)")
    }

    @Test fun pausedSeekIsSuppressedByDesktopWidgetGate() {
        val before = com.takahashirinta.ncrust.ui.widget.WidgetSnapshot(
            true, 101, "fixture", "artist", false, 10000, 200000, null, 0)
        val after = before.copy(positionMs = 90000)
        val size = com.takahashirinta.ncrust.ui.widget.WidgetSize.LARGE
        assertEquals(10, before.pushKey(size).positionSec)
        assertEquals(90, after.pushKey(size).positionSec)
        assertFalse("explicit paused seek is suppressed like an idle tick",
            com.takahashirinta.ncrust.ui.widget.WidgetPushGate.shouldPush(before.pushKey(size), after.pushKey(size)))
        println("AUDIT: paused widget position changes from 10s to 90s, but render gate returns false")
    }
}
