package com.takahashirinta.ncrust.stats

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.local.*
import com.takahashirinta.ncrust.bili.*
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.playlist.PlaylistCacheCodec
import com.takahashirinta.ncrust.qq.QqPlaylistRepository
import com.takahashirinta.ncrust.qq.QqPlaylistStore
import com.takahashirinta.ncrust.source.*
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** Audit-only: asserts defects still present after the v3.7.7 fixes. */
class FollowupBoundaryProbe {
    private class GatedPrefs(private val memory: SharedPreferences = MemoryPrefs()) : SharedPreferences by memory {
        @Volatile var beforePut: (() -> Unit)? = null
        val removed = CountDownLatch(1)
        override fun edit(): SharedPreferences.Editor {
            val editor = memory.edit()
            return object : SharedPreferences.Editor by editor {
                var removing = false
                override fun putString(key: String, value: String?): SharedPreferences.Editor {
                    beforePut?.let { action -> beforePut = null; action() }
                    editor.putString(key, value); return this
                }
                override fun remove(key: String): SharedPreferences.Editor {
                    removing = true; editor.remove(key); return this
                }
                override fun commit(): Boolean {
                    val ok = editor.commit(); if (removing) removed.countDown(); return ok
                }
            }
        }
    }
    private class MemoryContext(val stats: GatedPrefs = GatedPrefs(), val local: SharedPreferences? = null) : ContextWrapper(null) {
        private val stores = mutableMapOf<String, SharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = synchronized(stores) {
            if (name == "ncrust_stats") stats else if (name == "ncrust_local_playlists" && local != null) local
            else stores.getOrPut(name) { MemoryPrefs() }
        }
    }
    private fun song() = SongItem(SourceIds.qqId(101, "fixture-mid"), "fixture", null, null, 200000,
        source = "qqmusic", sourceId = "fixture-mid", mediaId = "fixture-file")
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Test fun degradedQqDetailIsReportedAsSuccessfulFreshLocalSync() = runBlocking {
        val ctx = MemoryContext()
        val key = PlaylistKey(MusicSource.QQMUSIC, "333", "10001")
        val now = System.currentTimeMillis()
        val playlist = LocalPlaylistStore.createPlaylist(ctx, key, "fixture", now)
        QqPlaylistRepository.init(ctx)
        QqPlaylistStore.writeDetail(ctx, key, listOf(song()), now, complete = false, total = 100)
        val before = QqPlaylistRepository.loadDetail(key, 0L, false) as com.takahashirinta.ncrust.playlist.PlaylistResult.Data
        assertEquals(com.takahashirinta.ncrust.playlist.PlaylistDegradation.TRUNCATED, before.outcome.degradation)
        val result = LocalPlaylistRepository.sync(ctx, playlist, false, now)
        assertTrue(result is LocalSyncOutcome.Success)
        val saved = LocalPlaylistStore.readPlaylists(ctx).single()
        assertEquals(now, saved.lastSyncedAt)
        assertFalse(LocalPlaylistSync.shouldAutoSync(saved.lastSyncedAt, now + 1000))
        println("AUDIT local sync: known incomplete 1/100 QQ cache -> Success and 10-minute fresh timestamp")
    }

    @Test fun lateRemoteSyncResurrectsDeletedLocalPlaylist() = runBlocking {
        val ctx = MemoryContext()
        val key = PlaylistKey(MusicSource.NETEASE, "777", "fixture-owner")
        val playlist = LocalPlaylistStore.createPlaylist(ctx, key, "fixture", 1)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val delegate = field(RetrofitClient, "plainClient\$delegate").get(RetrofitClient) as Lazy<*>
        val old = delegate.value
        val value = delegate.javaClass.getDeclaredField("_value").apply { isAccessible = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            entered.complete(Unit)
            check(release.await(3, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"code":200,"playlist":{"tracks":[{"id":101,"name":"fixture","ar":[],"al":{},"dt":200000}]}}""".toResponseBody()).build()
        }.build()
        value.set(delegate, client)
        try {
            val syncing = async(Dispatchers.Default) { LocalPlaylistRepository.sync(ctx, playlist, true, 2000) }
            withTimeout(3000) { entered.await() }
            LocalPlaylistRepository.deletePlaylist(ctx, key)
            assertTrue(LocalPlaylistStore.readPlaylists(ctx).isEmpty())
            release.countDown()
            assertTrue(withTimeout(3000) { syncing.await() } is LocalSyncOutcome.Success)
            assertEquals(key, LocalPlaylistStore.readPlaylists(ctx).single().key)
            println("AUDIT deleted local playlist resurrected by already-running remote sync")
        } finally { release.countDown(); value.set(delegate, old); client.dispatcher.executorService.shutdown() }
    }

    @Test fun clearOwnerMissesOwnDetailAndDeletesAnotherOwnersSameNumberedPlaylist() {
        val ctx = MemoryContext()
        val own = PlaylistKey(MusicSource.QQMUSIC, "111", "10001")
        val other = PlaylistKey(MusicSource.QQMUSIC, "10001", "20002")
        QqPlaylistStore.writeDetail(ctx, own, listOf(song()), 100, true, 1)
        QqPlaylistStore.writeDetail(ctx, other, listOf(song()), 100, true, 1)
        QqPlaylistStore.clearOwner(ctx, "10001")
        assertTrue(QqPlaylistStore.readDetail(ctx, own, 100) is PlaylistCacheCodec.DetailRead.Ok)
        assertTrue(QqPlaylistStore.readDetail(ctx, other, 100) is PlaylistCacheCodec.DetailRead.Dropped)
        println("AUDIT clearOwner(10001): own detail retained; other owner 20002 playlist 10001 removed")
    }

    private suspend fun flushStats(context: Context): Unit = suspendCoroutine { continuation ->
        val method = StatsStore.javaClass.getDeclaredMethod("flushNow", Context::class.java, Continuation::class.java).apply { isAccessible = true }
        try {
            val result = method.invoke(StatsStore, context, continuation)
            if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
        } catch (e: java.lang.reflect.InvocationTargetException) { continuation.resumeWithException(e.cause ?: e) }
    }

    @Test fun clearCanBeUndoneAfterOldFlushPassedGenerationCheck() = runBlocking {
        val ctx = MemoryContext()
        field(StatsStore, "aggregate").set(StatsStore, null)
        StatsStore.addPlay(ctx, "netease:101", "netease")
        assertEquals(1L, StatsStore.snapshot(ctx).totalPlays)
        ctx.stats.beforePut = {
            StatsStore.clear(ctx)
            check(ctx.stats.removed.await(3, TimeUnit.SECONDS))
        }
        try {
            withTimeout(5000) { flushStats(ctx) }
            assertEquals(0L, StatsStore.snapshot(ctx).totalPlays)
            val disk = StatsCodec.decode(ctx.stats.getString("snapshot", null))
            assertEquals(1L, disk.totalPlays)
            println("AUDIT stats clear: memory totalPlays=0 but persisted old totalPlays=1 after generation check")
        } finally {
            (field(StatsStore, "flushJob").get(StatsStore) as? Job)?.cancel()
            field(StatsStore, "aggregate").set(StatsStore, null)
        }
    }

    private class FailingPrefs(private val memory: SharedPreferences = MemoryPrefs()) : SharedPreferences by memory {
        var fail = false
        override fun edit(): SharedPreferences.Editor {
            val base = memory.edit()
            return object : SharedPreferences.Editor by base {
                override fun putString(key: String, value: String?): SharedPreferences.Editor { base.putString(key, value); return this }
                override fun remove(key: String): SharedPreferences.Editor { base.remove(key); return this }
                override fun commit(): Boolean { base.commit(); return !fail }
            }
        }
    }

    @Test fun manualLocalAddReturnsUncommittedTracksAfterDiskFailure() {
        val storage = FailingPrefs()
        val ctx = MemoryContext(local = storage)
        val playlist = LocalPlaylistRepository.createLocalOnly(ctx, "fixture", 100)
        storage.fail = true
        val reported = LocalPlaylistRepository.addTrack(ctx, playlist.key, song(), 200)
        assertEquals(1, reported.count { it.isVisible })
        assertTrue(LocalPlaylistStore.readTracks(ctx, playlist.key).isEmpty())
        println("AUDIT manual local add returns 1 track after Store failed and rolled back to 0")
    }

    private fun lateBiliNav(result: BiliNavResult, verify: (MemoryContext) -> Unit) = runBlocking {
        val ctx = MemoryContext()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val credential = BiliCredential(sessdata = "fixture-A", dedeUserId = "10001")
        val api = object : BiliAuthEndpoints {
            override suspend fun qrGenerate() = BiliQrCode("https://fixture.invalid/qr", "fixture-key")
            override suspend fun qrPoll(qrcodeKey: String): BiliQrPoll = BiliQrPoll.Success(credential)
            override suspend fun navWithCookie(cookie: String): BiliNavResult {
                entered.complete(Unit); release.await(); return result
            }
        }
        val login = BiliQrLogin(api, sleep = {})
        login.prepare(); assertEquals(BiliQrLogin.State.CONFIRMED, login.pollOnce())
        val finishing = async { login.finishLogin(ctx) }
        try {
            withTimeout(2000) { entered.await() }
            BiliAuthStore.clear(ctx)
            BiliAuthStore.save(ctx, BiliCredential(sessdata = "fixture-B", dedeUserId = "20002"))
            BiliAuthStore.saveProfile(ctx, BiliProfile(mid = 20002, uname = "B"))
            release.complete(Unit)
            withTimeout(2000) { finishing.await() }
            verify(ctx)
        } finally { release.complete(Unit); finishing.cancel(); BiliAuthStore.clear(ctx) }
    }

    @Test fun oldBiliLoginRejectionClearsNewAccountCredential() = lateBiliNav(BiliNavResult.NotLoggedIn) { ctx ->
        assertFalse(BiliAuthStore.isLoggedIn(ctx))
        println("AUDIT delayed Bili A nav rejection clears newer B login")
    }

    @Test fun oldBiliLoginProfileOverwritesNewAccountMembership() =
        lateBiliNav(BiliNavResult.LoggedIn(BiliNav(true, uname = "A", mid = 10001, vipType = 2, vipStatus = 1))) { ctx ->
            assertEquals("20002", BiliAuthStore.credential(ctx).dedeUserId)
            assertEquals(10001L, BiliAuthStore.profile(ctx).mid)
            assertTrue(BiliAuthStore.isVip(ctx))
            println("AUDIT newer B credential paired with delayed A profile and membership")
        }
}
