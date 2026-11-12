package com.takahashirinta.ncrust.crosssource

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.network.NcmApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.qq.QqClient
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.musicSource
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class CatalogRequestReuseTest {
    private class FixtureContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, MemoryPrefs>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            synchronized(stores) { stores.getOrPut(name) { MemoryPrefs() } }
    }

    private val restore = mutableListOf<() -> Unit>()
    private val ne = MusicSource.NETEASE
    private val qq = MusicSource.QQMUSIC

    @After fun reset() = restore.asReversed().forEach { it() }

    private fun set(owner: Any, name: String, value: Any?) {
        val field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        val original = field.get(owner)
        field.set(owner, value)
        restore += { field.set(owner, original) }
    }

    private fun replaceLazy(owner: Any, name: String, value: Any) {
        val delegate = owner.javaClass.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
            .get(owner) as Lazy<*>
        delegate.value
        set(delegate, "_value", value)
    }

    private fun client(answer: (Request) -> String): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(answer(request).toResponseBody()).build()
        }.build().also { client -> restore += { client.dispatcher.executorService.shutdown() } }

    private fun fixture(
        netease: (Request) -> String,
        qqSearch: (Request) -> String,
        qqDetails: (Request) -> String,
    ): Context {
        val context = FixtureContext()
        set(QqClient, "appContext", context)
        val api = Retrofit.Builder().baseUrl("https://catalog.invalid/").client(client(netease))
            .addConverterFactory(GsonConverterFactory.create()).build().create(NcmApi::class.java)
        replaceLazy(RetrofitClient, "api", api)
        replaceLazy(RetrofitClient, "plainClient", client { """{"code":200,"privileges":[]}""" })
        replaceLazy(QqClient, "searchHttp", client(qqSearch))
        replaceLazy(QqClient, "http", client(qqDetails))
        return context
    }

    @Test fun albumReusesTheWinningTrackListAndSkipsConflictingArtist() = runBlocking {
        val detailRequests = CopyOnWriteArrayList<String>()
        val context = fixture(
            netease = { """{"code":200,"songs":[{"id":1,"name":"Fixture Track","dt":180000}]}""" },
            qqSearch = {
                """{"data":{"album":{"list":[
                    {"albumMID":"conflict","albumName":"Fixture Album","song_count":1,"singerName":"Other Artist"},
                    {"albumMID":"paired","albumName":"Fixture Album","song_count":1,"singerName":"Fixture Artist"}
                ]}}}"""
            },
            qqDetails = { request ->
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                val param = JSONObject(buffer.readUtf8()).getJSONObject("req").getJSONObject("param")
                val mid = param.optString("albumMid")
                if (mid.isNotEmpty()) detailRequests.add(mid)
                """{"req":{"code":0,"data":{"songList":[{"songInfo":
                    {"id":202,"mid":"fixtureSong","name":"Fixture Track","interval":180}}]}}}"""
            },
        )
        val artist = ArtistKey(ne, "3", "Fixture Artist")
        val page = CatalogAggregator.loadAlbum(context, AlbumKey(ne, "1", "Fixture Album", artist))
        assertEquals(listOf("paired"), page.merged.aliases.map { it.id })
        assertEquals(listOf("paired"), detailRequests.toList())
        assertTrue(page.songs.any { it.song.musicSource == qq })
    }

    @Test fun artistReusesTheWinningAlbumList() = runBlocking {
        val albumRequests = AtomicInteger()
        val context = fixture(
            netease = { request ->
                if (request.url.encodedPath.endsWith("/artist/albums/1")) {
                    """{"hotAlbums":[{"id":10,"name":"Fixture Album","size":1,"artist":{"name":"Fixture Artist"}}]}"""
                } else """{"result":{"songs":[]}}"""
            },
            qqSearch = { request ->
                if (request.url.queryParameter("singermid") == "paired") {
                    albumRequests.incrementAndGet()
                    """{"data":{"list":[{"albumMID":"qqAlbum","albumName":"Fixture Album",
                        "song_count":1,"singerName":"Fixture Artist"}]}}"""
                } else {
                    """{"data":{"singer":{"list":[{"singerMID":"paired","singerName":"Fixture Artist","albumNum":1}]}}}"""
                }
            },
            qqDetails = { """{"req":{"code":0,"data":{}}}""" },
        )
        val page = CatalogAggregator.loadArtist(context, ArtistKey(ne, "1", "Fixture Artist"))
        assertEquals(listOf("paired"), page.merged.aliases.map { it.id })
        assertEquals(1, albumRequests.get())
        assertEquals("qqAlbum", page.albums.single().keyOf(qq)?.id)
    }
}
