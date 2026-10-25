package com.takahashirinta.ncrust.library

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.SongUrlFetcher
import com.takahashirinta.ncrust.player.ResolveFailureKind
import com.takahashirinta.ncrust.playlist.PlaylistCacheCodec
import com.takahashirinta.ncrust.playlist.PlaylistResult
import com.takahashirinta.ncrust.qq.*
import com.takahashirinta.ncrust.source.*
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/** Audit-only characterization: assertions describe defects observed at 763987a, not desired behavior. */
class SourceReviewBoundaryProbe {
    private class MemoryContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, MemoryPrefs>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            synchronized(stores) { stores.getOrPut(name) { MemoryPrefs() } }
    }
    private lateinit var ctx: MemoryContext
    private val restorers = mutableListOf<() -> Unit>()
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun stopFlush() = runBlocking {
        (field(LibraryManager, "flushJob").get(LibraryManager) as? Job)?.let { it.cancel(); it.join() }
    }
    private fun resetLibrary() {
        stopFlush()
        listOf("cachedEntries", "cachedAlbums", "cachedLikedIds", "pendingAppContext", "flushJob").forEach {
            field(LibraryManager, it).set(LibraryManager, null)
        }
        field(LibraryManager, "likedIdCursor").setInt(LibraryManager, 0)
    }
    private fun writeSnapshot() {
        LibraryManager.javaClass.getDeclaredMethod("flushToDisk").apply { isAccessible = true }.invoke(LibraryManager)
    }
    @Before fun setup() {
        ctx = MemoryContext()
        resetLibrary()
        field(CookieManager, "app").set(CookieManager, null)
        CookieManager.init(ctx)
    }
    @After fun cleanup() {
        stopFlush()
        restorers.asReversed().forEach { it() }
        resetLibrary()
        field(CookieManager, "app").set(CookieManager, null)
    }
    private fun intercept(owner: Any, lazyName: String, answer: (Request) -> String) {
        val delegate = field(owner, lazyName + "$" + "delegate").get(owner) as Lazy<*>
        val original = delegate.value
        val valueField = delegate.javaClass.getDeclaredField("_value").apply { isAccessible = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("audit fixture")
                .body(answer(request).toResponseBody()).build()
        }.build()
        valueField.set(delegate, client)
        restorers += { valueField.set(delegate, original); client.dispatcher.executorService.shutdown() }
    }
    private fun song(id: Long) = SongItem(id = id, name = "fixture-$id", artists = null, album = null, duration = 200000)
    private fun album(id: Long) = AlbumInfo(id, "fixture-$id", "", "fixture", 1)

    @Test fun coldSongWriteReplacesUnreadDiskCollection() {
        val p = ctx.getSharedPreferences("ncrust_library", 0)
        p.edit().putString("saved_songs", SavedSongCodec.encode(
            SavedSongSync.addManual(emptyList(), song(101), 1L)
        )).commit()
        assertEquals(SavedSongOutcome.ADDED, LibraryManager.saveSong(ctx, song(202)))
        runBlocking { (field(LibraryManager, "flushJob").get(LibraryManager) as Job).join() }
        val remaining = SavedSongCodec.decode(p.getString("saved_songs", null)).map { it.trackKey.id }
        assertEquals(listOf(202L), remaining)
        println("AUDIT cold write: persisted [101] -> [202], old local song lost")
    }

    @Test fun firstAlbumMutationChangesDetachedCopy() {
        val p = ctx.getSharedPreferences("ncrust_library", 0)
        p.edit().putString("saved_albums", SavedAlbumCodec.encode(listOf(album(101)))).commit()
        LibraryManager.subscribeAlbum(ctx, album(202))
        writeSnapshot()
        assertEquals(listOf(101L), LibraryManager.getSavedAlbums(ctx).map { it.albumId })
        assertEquals(listOf(101L), SavedAlbumCodec.decode(p.getString("saved_albums", null)).map { it.albumId })
        println("AUDIT cold album: subscribe 202 discarded from memory and persistence")
    }

    @Test fun albumBusinessFailureClearsCacheAndReportsSuccess() = runBlocking {
        val p = ctx.getSharedPreferences("ncrust_library", 0)
        p.edit().putString("saved_albums", SavedAlbumCodec.encode(listOf(album(101)))).commit()
        LibraryManager.getSavedAlbums(ctx)
        CookieManager.saveCookie(ctx, "MUSIC_U=fixture; __csrf=fixture")
        intercept(RetrofitClient, "plainClient") { request ->
            if (request.url.encodedPath.endsWith("/w/nuser/account/get"))
                """{"code":200,"account":{"id":123}}"""
            else """{"code":500,"message":"fixture failure"}"""
        }
        assertTrue(LibraryManager.refreshFromCloud(ctx))
        writeSnapshot()
        assertTrue(LibraryManager.getSavedAlbums(ctx).isEmpty())
        assertTrue(SavedAlbumCodec.decode(p.getString("saved_albums", null)).isEmpty())
        println("AUDIT album code 500: persisted [101] -> [], refresh returned true")
    }

    @Test fun secondTransportFailureEscapesNetworkClassification() = runBlocking {
        val calls = AtomicInteger()
        val cooldown = field(SongUrlFetcher, "transportCooldownUntil").get(SongUrlFetcher) as AtomicLong
        cooldown.set(0)
        intercept(RetrofitClient, "plainClient") { calls.incrementAndGet(); throw IOException("fixture offline") }
        val outcome = SourceRouter.resolveUrlOutcome(song(987654321), "standard")
        assertEquals(2, calls.get())
        assertEquals(ResolveFailureKind.UNKNOWN, outcome.failure?.kind)
        assertFalse(SongUrlFetcher.lastAttemptFailedOnTransport(987654321))
        assertEquals(0L, cooldown.get())
        println("AUDIT double IOException: UNKNOWN, transport flag false, cooldown unset")
    }

    @Test fun qqFavoriteFailureOverwritesCompleteCacheAsFreshSuccess() = runBlocking {
        QqClient.init(ctx)
        QqPlaylistRepository.init(ctx)
        QqAuthStore.saveCookie(ctx, "uin=10001; qqmusic_key=fixture")
        val mine = Playlist(PlaylistKey(MusicSource.QQMUSIC, "111", "10001"), "mine", null, 0, true, true, 0, 201)
        val favorite = Playlist(PlaylistKey(MusicSource.QQMUSIC, "222", "10001"), "favorite", null, 0, false, false, 0)
        QqPlaylistStore.writeList(ctx, "10001", listOf(mine, favorite), System.currentTimeMillis())
        val calls = AtomicInteger()
        intercept(QqClient, "http") { request ->
            calls.incrementAndGet()
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            val method = JSONObject(buffer.readUtf8()).getJSONObject("req").getString("method")
            when (method) {
                "GetLoginUserInfo" -> """{"req":{"code":0,"data":{"info":{"nick":"fixture"}}}}"""
                "GetPlaylistByUin" -> """{"req":{"code":0,"data":{"v_playlist":[{"dirId":201,"dirName":"mine","tid":111,"songNum":0}]}}}"""
                else -> throw IOException("fixture favorites detail unavailable")
            }
        }
        val result = QqPlaylistRepository.loadList(forceRefresh = true) as PlaylistResult.Data
        assertTrue(result.outcome.fresh)
        assertNull(result.outcome.degradation)
        val disk = QqPlaylistStore.readList(ctx, "10001", System.currentTimeMillis()) as PlaylistCacheCodec.ListRead.Ok
        assertEquals(listOf("111"), disk.playlists.map { it.key.id })
        assertEquals(3, calls.get())
        println("AUDIT QQ partial failure: complete [111,222] -> fresh [111], no degradation")
    }
}
