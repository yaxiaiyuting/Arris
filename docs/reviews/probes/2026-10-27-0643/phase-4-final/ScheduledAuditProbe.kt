package com.takahashirinta.ncrust.audit

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.library.AlbumInfo
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.PlaylistEditApi
import com.takahashirinta.ncrust.network.PlaylistWriteGate
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.player.PlayReporter
import com.takahashirinta.ncrust.player.ReportGateStats
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
import java.util.concurrent.atomic.AtomicReference

/** Isolated local HTTP interception. A passing test characterizes a defect, not a repair. */
class ScheduledAuditProbe {
    private class MemoryContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, SharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = synchronized(stores) {
            stores.getOrPut(name) { MemoryPrefs() }
        }
    }
    private val context = MemoryContext()
    private val restore = mutableListOf<() -> Unit>()
    private val release = CountDownLatch(1)
    private fun field(o: Any, n: String) = o.javaClass.getDeclaredField(n).apply { isAccessible = true }
    private fun replace(o: Any, n: String, v: Any?) {
        val f = field(o,n); val old=f.get(o); f.set(o,v); restore.add { f.set(o,old) }
    }
    private fun intercept(name: String, action: (Request) -> String) {
        val lazy=field(RetrofitClient,name+"\$delegate").get(RetrofitClient) as Lazy<*>
        val old=lazy.value; val value=field(lazy,"_value")
        val fake=OkHttpClient.Builder().addInterceptor { c ->
            Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("audit")
                .body(action(c.request()).toResponseBody()).build()
        }.build()
        value.set(lazy,fake); restore.add { value.set(lazy,old); fake.dispatcher.executorService.shutdown() }
    }
    private suspend fun settleWrites() {
        val scope=field(LibraryManager,"ioScope").get(LibraryManager) as CoroutineScope
        withTimeout(6000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
    }
    @Before fun setup() {
        replace(CookieManager,"app",null)
        CookieManager.saveCookie(context,"MUSIC_U=fixture-A; __csrf=fixture-A-csrf")
        listOf("cachedEntries","cachedAlbums","cachedLikedIds","pendingAppContext","flushJob").forEach { replace(LibraryManager,it,null) }
        intercept("plainClient") { error("unexpected outbound ${it.url}") }
    }
    @After fun cleanup() = runBlocking {
        release.countDown(); settleWrites()
        CookieManager.clearCookie(context); restore.asReversed().forEach { it() }
    }
    @Test fun albumUnlikeCanBeOvertakenByTheOlderSubscribe() = runBlocking {
        val entered=CountDownLatch(1); val unlikeDone=CountDownLatch(1)
        val cloud=AtomicReference<Boolean?>(null)
        intercept("plainClient") { r ->
            when(r.url.encodedPath) {
                "/eapi/album/sub" -> { entered.countDown(); check(release.await(5,TimeUnit.SECONDS)); cloud.set(true) }
                "/eapi/album/unsub" -> { cloud.set(false); unlikeDone.countDown() }
                else -> error("unexpected ${r.url}")
            }
            """{"code":200}"""
        }
        LibraryManager.subscribeAlbum(context,AlbumInfo(77,"fixture","","",1))
        assertTrue(entered.await(4,TimeUnit.SECONDS))
        LibraryManager.removeAlbum(context,77)
        assertTrue(unlikeDone.await(4,TimeUnit.SECONDS))
        assertEquals(false,cloud.get())
        release.countDown(); settleWrites()
        assertEquals(true,cloud.get())
        assertTrue(LibraryManager.getSavedAlbums(context).isEmpty())
        println("AUDIT album intent: local removed, simulated cloud ends subscribed because old sub commits last")
    }
    @Test fun queuedRenameUsesTheNewAccountsCredential() = runBlocking {
        val holding=CompletableDeferred<Unit>(); val unblock=CompletableDeferred<Unit>()
        val sentCookie=AtomicReference<String?>()
        intercept("plainClient") { r ->
            assertEquals("/eapi/playlist/update/name",r.url.encodedPath)
            sentCookie.set(r.header("Cookie")); """{"code":200}"""
        }
        val holder=launch(Dispatchers.Default) { PlaylistWriteGate.run { holding.complete(Unit); unblock.await() } }
        holding.await()
        val rename=async(start=CoroutineStart.UNDISPATCHED) { PlaylistEditApi.renamePlaylist(55,"A-intent") }
        try {
            CookieManager.clearCookie(context)
            CookieManager.saveCookie(context,"MUSIC_U=fixture-B; __csrf=fixture-B-csrf")
            unblock.complete(Unit); holder.join()
            assertTrue(withTimeout(6000) { rename.await() })
            assertTrue(sentCookie.get().orEmpty().contains("MUSIC_U=fixture-B"))
            println("AUDIT playlist owner: A's queued rename is sent using B credential")
        } finally { unblock.complete(Unit); holder.cancelAndJoin(); rename.cancelAndJoin() }
    }
    @Test fun delayedScrobbleUsesTheNextLoggedInAccount() = runBlocking {
        val entered=CountDownLatch(1); val sent=CountDownLatch(1)
        val sentCookie=AtomicReference<String?>()
        val counter=ReportGateStats.counter
        replace(counter,"clock",{ entered.countDown(); check(release.await(5,TimeUnit.SECONDS)); 1234L })
        intercept("plainClient") { r ->
            assertEquals("clientlog.music.163.com",r.url.host)
            sentCookie.set(r.header("Cookie")); sent.countDown(); """{"code":200}"""
        }
        PlayReporter.reportStart(101)
        assertTrue(entered.await(4,TimeUnit.SECONDS))
        CookieManager.clearCookie(context)
        CookieManager.saveCookie(context,"MUSIC_U=fixture-B; __csrf=fixture-B-csrf")
        release.countDown()
        assertTrue(sent.await(4,TimeUnit.SECONDS))
        Thread.getAllStackTraces().keys.filter { it.name=="ncrust-scrobble" }.forEach { it.join(4000) }
        assertTrue(sentCookie.get().orEmpty().contains("MUSIC_U=fixture-B"))
        println("AUDIT scrobble owner: a report created under A is emitted under B after the worker delay")
    }
    @Test fun neteasePlaylistWithAFailedDetailBatchIsStillMarkedFullySynced() = runBlocking {
        val key=com.takahashirinta.ncrust.source.PlaylistKey(com.takahashirinta.ncrust.source.MusicSource.NETEASE,"55","10001")
        val meta=com.takahashirinta.ncrust.local.LocalPlaylistStore.createPlaylist(context,key,"fixture",1)
        intercept("plainClient") { r -> when(r.url.encodedPath) {
            "/eapi/v6/playlist/detail" -> """{"code":200,"playlist":{"trackCount":2,"trackIds":[{"id":101},{"id":202}],"tracks":[{"id":101,"name":"first","dt":200000}]}}"""
            "/eapi/v3/song/detail" -> """{"code":500,"message":"fixture temporary failure"}"""
            else -> error("unexpected ${r.url}")
        } }
        val outcome=com.takahashirinta.ncrust.local.LocalPlaylistRepository.sync(context,meta,false,2000)
        assertTrue(outcome is com.takahashirinta.ncrust.local.LocalSyncOutcome.Success)
        assertEquals(1,(outcome as com.takahashirinta.ncrust.local.LocalSyncOutcome.Success).total)
        val saved=com.takahashirinta.ncrust.local.LocalPlaylistStore.readPlaylists(context).single()
        assertEquals(2000L,saved.lastSyncedAt)
        assertFalse(com.takahashirinta.ncrust.local.LocalPlaylistSync.shouldAutoSync(saved.lastSyncedAt,2001))
        println("AUDIT Netease sync: playlist declares 2 tracks, second detail returns code500, yet Success(total=1) advances full-sync TTL")
    }
    @Test fun likedSongPaginationConsumesAFailedBatchAndDoesNotRetryIt() = runBlocking {
        replace(LibraryManager,"cachedLikedIds",listOf(101L,202L))
        replace(LibraryManager,"likedIdCursor",0)
        var healthy=false;var requests=0
        intercept("plainClient") { r ->
            assertEquals("/eapi/v3/song/detail",r.url.encodedPath);requests++
            if(!healthy) """{"code":500}""" else """{"code":200,"songs":[{"id":101,"name":"first"},{"id":202,"name":"second"}]}"""
        }
        assertTrue(LibraryManager.loadMoreLikedSongs(context).isEmpty())
        assertEquals(2,field(LibraryManager,"likedIdCursor").get(LibraryManager))
        val failedRequests=requests;healthy=true
        assertTrue(LibraryManager.loadMoreLikedSongs(context).isEmpty())
        assertEquals("recovered network is never used because the failed page was consumed",failedRequests,requests)
        println("AUDIT liked pagination: failed detail batch advances cursor to the end; next scroll never retries even with a healthy response")
    }
    @Test fun truncatedNeteaseTrackIdHeadIsAlsoMarkedFullySynced() = runBlocking {
        val key=com.takahashirinta.ncrust.source.PlaylistKey(com.takahashirinta.ncrust.source.MusicSource.NETEASE,"55","10001")
        val meta=com.takahashirinta.ncrust.local.LocalPlaylistStore.createPlaylist(context,key,"fixture",1)
        intercept("plainClient") { r ->
            assertEquals("/eapi/v6/playlist/detail",r.url.encodedPath)
            """{"code":200,"playlist":{"trackCount":2,"trackIds":[{"id":101}],"tracks":[{"id":101,"name":"first","dt":200000}]}}"""
        }
        val outcome=com.takahashirinta.ncrust.local.LocalPlaylistRepository.sync(context,meta,false,2000)
        assertTrue(outcome is com.takahashirinta.ncrust.local.LocalSyncOutcome.Success)
        assertEquals(1,(outcome as com.takahashirinta.ncrust.local.LocalSyncOutcome.Success).total)
        assertEquals(2000L,com.takahashirinta.ncrust.local.LocalPlaylistStore.readPlaylists(context).single().lastSyncedAt)
        println("AUDIT Netease completeness: declared trackCount=2 with a one-id head is accepted without filling the missing id and advances TTL")
    }
    @Test fun ordinaryPlaylistNameContainingLikedMusicShadowsTheRealHeartPlaylist()=runBlocking {
        intercept("plainClient") { r -> when {
            r.url.encodedPath.endsWith("/user/playlist") -> """{"code":200,"playlist":[{"id":111,"name":"以前喜欢的音乐精选","specialType":0,"creator":{"userId":10001}},{"id":999,"name":"我喜欢的音乐","specialType":5,"creator":{"userId":10001}}]}"""
            r.url.encodedPath.endsWith("/playlist/detail") -> """{"code":200,"playlist":{"id":111,"trackCount":1,"trackIds":[{"id":101}]}}"""
            else -> error("unexpected ${r.url}")
        } }
        assertEquals(111L,com.takahashirinta.ncrust.network.PlaylistApi.getLikedPlaylistId(10001))
        val read=com.takahashirinta.ncrust.network.PlaylistApi.getLikedTrackIds(10001)
        assertEquals(listOf(101L),(read as com.takahashirinta.ncrust.network.PlaylistApi.LikedIdsResult.Success).ids)
        println("AUDIT heart playlist identity: a normal playlist whose name contains 喜欢的音乐, when preceding the real specialType5 heart playlist, wins selection and supplies the liked-id table")
    }
}
