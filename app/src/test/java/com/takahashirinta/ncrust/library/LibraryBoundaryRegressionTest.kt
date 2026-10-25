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
import org.json.JSONArray

/** Regression tests call the real managers with isolated preferences and intercepted HTTP fixtures. */
class LibraryBoundaryRegressionTest {
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
        SourceRouter.register(NeteaseSourceProvider)
    }
    @After fun cleanup() {
        stopFlush()
        restorers.asReversed().forEach { it() }
        resetLibrary()
        field(CookieManager, "app").set(CookieManager, null)
        (field(SongUrlFetcher, "transportCooldownUntil").get(SongUrlFetcher) as AtomicLong).set(0)
        (field(SongUrlFetcher, "transportFailures").get(SongUrlFetcher) as MutableSet<*>).clear()
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

    @Test fun coldSongWritePreservesUnreadDiskCollection() {
        val p = ctx.getSharedPreferences("ncrust_library", 0)
        p.edit().putString("saved_songs", SavedSongCodec.encode(
            SavedSongSync.addManual(emptyList(), song(101), 1L)
        )).commit()
        assertEquals(SavedSongOutcome.ADDED, LibraryManager.saveSong(ctx, song(202)))
        runBlocking { (field(LibraryManager, "flushJob").get(LibraryManager) as Job).join() }
        val remaining = SavedSongCodec.decode(p.getString("saved_songs", null)).map { it.trackKey.id }
        assertEquals(setOf(101L, 202L), remaining.toSet())
    }

    @Test fun firstAlbumMutationUpdatesTrueSource() {
        val p = ctx.getSharedPreferences("ncrust_library", 0)
        p.edit().putString("saved_albums", SavedAlbumCodec.encode(listOf(album(101)))).commit()
        LibraryManager.subscribeAlbum(ctx, album(202))
        writeSnapshot()
        assertEquals(listOf(202L, 101L), LibraryManager.getSavedAlbums(ctx).map { it.albumId })
        assertEquals(listOf(202L, 101L), SavedAlbumCodec.decode(p.getString("saved_albums", null)).map { it.albumId })
    }

    @Test fun albumBusinessFailurePreservesCacheAndReportsFailure() = runBlocking {
        val p = ctx.getSharedPreferences("ncrust_library", 0)
        p.edit().putString("saved_albums", SavedAlbumCodec.encode(listOf(album(101)))).commit()
        LibraryManager.getSavedAlbums(ctx)
        CookieManager.saveCookie(ctx, "MUSIC_U=fixture; __csrf=fixture")
        intercept(RetrofitClient, "plainClient") { request ->
            if (request.url.encodedPath.endsWith("/w/nuser/account/get"))
                """{"code":200,"account":{"id":123}}"""
            else """{"code":500,"message":"fixture failure"}"""
        }
        assertFalse(LibraryManager.refreshFromCloud(ctx))
        writeSnapshot()
        assertEquals(listOf(101L), LibraryManager.getSavedAlbums(ctx).map { it.albumId })
        assertEquals(listOf(101L), SavedAlbumCodec.decode(p.getString("saved_albums", null)).map { it.albumId })
    }

    @Test fun qqFavoriteFailureRetainsCacheAndIsNotFresh() = runBlocking {
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
        assertFalse(result.outcome.fresh)
        assertEquals(com.takahashirinta.ncrust.playlist.PlaylistDegradation.OFFLINE, result.outcome.degradation)
        val disk = QqPlaylistStore.readList(ctx, "10001", System.currentTimeMillis()) as PlaylistCacheCodec.ListRead.Ok
        assertEquals(listOf("111", "222"), disk.playlists.map { it.key.id })
        assertFalse(disk.fresh)
        assertEquals(3, calls.get())
    }

    @Test fun coldBatchAddAndRemovalPreserveDiskEntriesAndTombstones() {
        val p = ctx.getSharedPreferences("ncrust_library", 0)
        p.edit().putString("saved_songs", SavedSongCodec.encode(
            SavedSongSync.addManualAll(emptyList(), listOf(song(101), song(202)), 1L))).commit()
        assertTrue(LibraryManager.removeSong(ctx, 101))
        LibraryManager.saveSongs(ctx, listOf(song(303)))
        runBlocking { (field(LibraryManager, "flushJob").get(LibraryManager) as Job).join() }
        val entries = SavedSongCodec.decode(p.getString("saved_songs", null))
        assertEquals(setOf(202L, 303L), entries.filter { it.isVisible }.map { it.trackKey.id }.toSet())
        assertTrue(entries.single { it.trackKey.id == 101L }.tombstoned)
    }

    @Test fun confirmedEmptyAlbumsCanClearOldCache() = runBlocking {
        val p = ctx.getSharedPreferences("ncrust_library", 0)
        p.edit().putString("saved_albums", SavedAlbumCodec.encode(listOf(album(101)))).commit()
        CookieManager.saveCookie(ctx, "MUSIC_U=fixture; __csrf=fixture")
        intercept(RetrofitClient, "plainClient") { request -> when {
            request.url.encodedPath.endsWith("/w/nuser/account/get") -> """{"code":200,"account":{"id":123}}"""
            request.url.encodedPath.endsWith("/album/sublist") -> """{"code":200,"data":[],"hasMore":false}"""
            else -> """{"code":500}"""
        } }
        assertTrue(LibraryManager.refreshFromCloud(ctx))
        writeSnapshot()
        assertTrue(SavedAlbumCodec.decode(p.getString("saved_albums", null)).isEmpty())
    }

    @Test fun albumReadConsumesAllPages() = runBlocking {
        val pages = AtomicInteger()
        intercept(RetrofitClient, "plainClient") {
            if (pages.incrementAndGet() == 1) """{"code":200,"data":[{"id":1},{"id":2}],"hasMore":true}"""
            else """{"code":200,"data":[{"id":3}],"hasMore":false}"""
        }
        assertEquals(listOf(1L, 2L, 3L), PlaylistApi.getSubscribedAlbums(limit = 2).map { it.albumId })
        assertEquals(2, pages.get())
    }

    @Test fun failedSecondAlbumPageRetainsEntireOldCache() = runBlocking {
        val p = ctx.getSharedPreferences("ncrust_library", 0)
        p.edit().putString("saved_albums", SavedAlbumCodec.encode(listOf(album(101), album(202)))).commit()
        CookieManager.saveCookie(ctx, "MUSIC_U=fixture; __csrf=fixture")
        val pages = AtomicInteger()
        intercept(RetrofitClient, "plainClient") { request -> when {
            request.url.encodedPath.endsWith("/w/nuser/account/get") -> """{"code":200,"account":{"id":123}}"""
            request.url.encodedPath.endsWith("/album/sublist") && pages.incrementAndGet() == 1 ->
                """{"code":200,"data":[{"id":999}],"hasMore":true}"""
            else -> """{"code":500}"""
        } }
        assertFalse(LibraryManager.refreshFromCloud(ctx))
        assertEquals(listOf(101L, 202L), LibraryManager.getSavedAlbums(ctx).map { it.albumId })
        assertEquals(2, pages.get())
    }

    @Test fun repeatingAlbumPageTerminatesWithoutAcceptingPartialData() = runBlocking {
        val pages = AtomicInteger()
        intercept(RetrofitClient, "plainClient") { pages.incrementAndGet(); """{"code":200,"data":[{"id":1}],"hasMore":true}""" }
        try { PlaylistApi.getSubscribedAlbums(); fail("repeating page must fail") }
        catch (_: IOException) { }
        assertEquals(2, pages.get())
    }

    @Test fun missingAlbumArrayIsFailureRatherThanEmptySuccess() = runBlocking {
        intercept(RetrofitClient, "plainClient") { """{"code":200}""" }
        try { PlaylistApi.getSubscribedAlbums(); fail("missing array must fail") }
        catch (_: IOException) { }
    }

    private fun prepareQqFavoritePages(answer: (Int) -> String) {
        QqClient.init(ctx); QqPlaylistRepository.init(ctx)
        QqAuthStore.saveCookie(ctx, "uin=10001; qqmusic_key=fixture")
        val oldFavorite = Playlist(PlaylistKey(MusicSource.QQMUSIC, "222", "10001"), "old", null, 0, false, false, 0)
        QqPlaylistStore.writeList(ctx, "10001", listOf(oldFavorite), System.currentTimeMillis())
        intercept(QqClient, "http") { request ->
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            val req = JSONObject(buffer.readUtf8()).getJSONObject("req")
            when (req.getString("method")) {
                "GetLoginUserInfo" -> """{"req":{"code":0,"data":{"info":{"nick":"fixture"}}}}"""
                "GetPlaylistByUin" -> """{"req":{"code":0,"data":{"v_playlist":[{"dirId":201,"tid":111,"dirName":"mine"}]}}}"""
                "CgiGetDiss" -> """{"req":{"code":0,"songlist":[],"dirinfo":{"id":111,"dirid":201,"encrypt_uin":"fixture"}}}"""
                "CgiGetPlaylistFavInfo" -> answer(req.getJSONObject("param").getInt("offset"))
                else -> error("unexpected method")
            }
        }
    }
    private fun favoritePage(start: Int, size: Int): String {
        val rows = JSONArray()
        repeat(size) { rows.put(JSONObject().put("tid", start + it).put("title", "fixture")) }
        return JSONObject().put("req", JSONObject().put("code", 0).put("data", JSONObject().put("v_list", rows))).toString()
    }

    @Test fun qqConfirmedEmptyFavoritesRemoveOldFavorites() = runBlocking {
        prepareQqFavoritePages { favoritePage(0, 0) }
        val result = QqPlaylistRepository.loadList(true) as PlaylistResult.Data
        assertTrue(result.outcome.fresh)
        assertNull(result.outcome.degradation)
        assertEquals(listOf("111"), result.outcome.data.map { it.key.id })
    }

    @Test fun qqFavoritesReadBeyondFiftyAndOnlyCompleteResultIsFresh() = runBlocking {
        val offsets = mutableListOf<Int>()
        prepareQqFavoritePages { offset -> offsets += offset; favoritePage(1000 + offset, if (offset == 0) 50 else 1) }
        val result = QqPlaylistRepository.loadList(true) as PlaylistResult.Data
        assertEquals(listOf(0, 50), offsets)
        assertEquals(52, result.outcome.data.size)
        assertTrue(result.outcome.fresh)
        assertFalse(result.outcome.data.any { it.key.id == "222" })
    }

    @Test fun qqSecondPageFailurePreservesCachedFavoritesAndKeepsNewPartialData() = runBlocking {
        prepareQqFavoritePages { offset -> if (offset == 0) favoritePage(1000, 50) else """{"req":{"code":500}}""" }
        val result = QqPlaylistRepository.loadList(true) as PlaylistResult.Data
        assertEquals(52, result.outcome.data.size)
        assertTrue(result.outcome.data.any { it.key.id == "222" })
        assertFalse(result.outcome.fresh)
        assertNotNull(result.outcome.degradation)
        val disk = QqPlaylistStore.readList(ctx, "10001", System.currentTimeMillis()) as PlaylistCacheCodec.ListRead.Ok
        assertFalse(disk.fresh)
        assertEquals(52, disk.playlists.size)
    }

    @Test fun qqMissingFavoritesArrayDoesNotEraseOldCache() = runBlocking {
        prepareQqFavoritePages { """{"req":{"code":0,"data":{}}}""" }
        val result = QqPlaylistRepository.loadList(true) as PlaylistResult.Data
        assertTrue(result.outcome.data.any { it.key.id == "222" })
        assertFalse(result.outcome.fresh)
    }
}
