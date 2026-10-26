package com.takahashirinta.ncrust.audit

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.crosssource.*
import com.takahashirinta.ncrust.network.NcmApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.qq.QqClient
import com.takahashirinta.ncrust.qq.QqIdentity
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.musicSource
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScheduledCatalogAuditProbe {
    private open class MemoryContext : ContextWrapper(null) {
        private val stores=mutableMapOf<String,SharedPreferences>()
        override fun getApplicationContext(): Context=this
        override fun getSharedPreferences(name: String,mode: Int): SharedPreferences=synchronized(stores) {
            stores.getOrPut(name) { MemoryPrefs() }
        }
    }
    private val restore=mutableListOf<()->Unit>()
    private val clients=mutableListOf<OkHttpClient>()
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply { isAccessible=true }
    private fun lazyValue(o:Any,n:String,v:Any) {
        val lazy=field(o,n+"\$delegate").get(o) as Lazy<*>
        val old=lazy.value; val f=field(lazy,"_value"); f.set(lazy,v);restore.add { f.set(lazy,old) }
    }
    private fun fake(answer:(Request)->String):OkHttpClient=OkHttpClient.Builder().addInterceptor { c ->
        Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("audit")
            .body(answer(c.request()).toResponseBody()).build()
    }.build().also { clients+=it }
    private fun configure(context:Context,rest:(Request)->String,qq:(Request)->String,search:(Request)->String) {
        val app=field(QqClient,"appContext");val old=app.get(QqClient);app.set(QqClient,context);restore.add { app.set(QqClient,old) }
        lazyValue(RetrofitClient,"plainClient",fake { """{"code":200,"privileges":[]}""" })
        val api=Retrofit.Builder().baseUrl("https://fixture.invalid/").client(fake(rest))
            .addConverterFactory(GsonConverterFactory.create()).build().create(NcmApi::class.java)
        lazyValue(RetrofitClient,"api",api)
        lazyValue(QqClient,"http",fake(qq));lazyValue(QqClient,"searchHttp",fake(search))
    }
    @After fun cleanup() { restore.asReversed().forEach { it() };clients.forEach { it.dispatcher.executorService.shutdown() } }
    private fun qqReply(mid:String,id:Int,artist:String):String =
        """{"req":{"code":0,"data":{"totalNum":1,"songList":[{"songInfo":{"id":$id,"mid":"song-$mid","name":"Same Track","interval":200,"singer":[{"id":3,"name":"$artist"}],"album":{"mid":"$mid","name":"Shared"}}}]}}}"""
    private fun neAlbum(id:Int,artist:String):String=
        """{"code":200,"album":{"id":$id,"name":"Shared","artist":{"id":1,"name":"$artist"}},"songs":[{"id":$id,"name":"Same Track","ar":[{"id":1,"name":"$artist"}],"al":{"id":$id,"name":"Shared"},"dt":200000}]}"""

    @Test fun albumCandidateBorrowsTheAnchorArtistAndConfirmsADifferentArtist() = runBlocking {
        val context=MemoryContext()
        configure(context,
            rest={ r -> check(r.url.encodedPath=="/api/v1/album/10");neAlbum(10,"Alpha") },
            qq={ r -> val b=Buffer();r.body!!.writeTo(b);val req=JSONObject(b.readUtf8()).getJSONObject("req")
                if(req.getJSONObject("param").has("albumMid")) qqReply("wrong-album",99,"Beta") else """{"req":{"code":0,"data":{}}}""" },
            search={ """{"data":{"album":{"list":[{"albumID":99,"albumMID":"wrong-album","albumName":"Shared","singerName":"Beta","song_count":1}]}}}""" })
        val page=CatalogAggregator.loadAlbum(context,AlbumKey(MusicSource.NETEASE,"10","Shared",ArtistKey(MusicSource.NETEASE,"1","Alpha")))
        assertEquals(MatchConfidence.HIGH,page.merged.confidence)
        assertEquals("wrong-album",page.merged.aliases.single().id)
        assertTrue(page.songs.any { it.song.musicSource==MusicSource.QQMUSIC && it.song.artists!!.any { a -> a.name=="Beta" } })
        println("AUDIT catalog artist: Beta's same-title album becomes a HIGH alias of Alpha because the candidate is given Alpha's artist name")
    }
    @Test fun qqAnchorFindsButOmitsItsNeteaseCounterpartTracks() = runBlocking {
        val context=MemoryContext();val requestedMids=mutableListOf<String>()
        configure(context,
            rest={ r -> when(r.url.encodedPath) {
                "/api/cloudsearch/pc" -> """{"result":{"albums":[{"id":20,"name":"Shared","artist":{"id":1,"name":"Alpha"},"size":1}]}}"""
                "/api/v1/album/20" -> neAlbum(20,"Alpha")
                else -> error("unexpected ${r.url}")
            } },
            qq={ r -> val b=Buffer();r.body!!.writeTo(b);val param=JSONObject(b.readUtf8()).getJSONObject("req").getJSONObject("param")
                if(param.has("albumMid")) { val mid=param.getString("albumMid");requestedMids+=mid
                    if(mid=="20") qqReply(mid,999,"Unrelated") else qqReply(mid,88,"Alpha")
                } else """{"req":{"code":0,"data":{}}}""" },
            search={ error("unexpected search ${it.url}") })
        val page=CatalogAggregator.loadAlbum(context,AlbumKey(MusicSource.QQMUSIC,"qq-anchor","Shared",ArtistKey(MusicSource.QQMUSIC,"singer","Alpha")))
        assertEquals(MusicSource.NETEASE,page.merged.aliases.single().source)
        assertEquals("20",page.merged.aliases.single().id)
        assertFalse("counterpart is not fetched as QQ either",requestedMids.contains("20"))
        assertFalse(page.songs.any { it.song.musicSource==MusicSource.NETEASE })
        assertTrue(page.songs.any { it.song.musicSource==MusicSource.QQMUSIC })
        assertEquals(0,page.totalCounts[MusicSource.NETEASE] ?: 0)
        println("AUDIT catalog source: QQ-origin page recognizes Netease album 20 but never includes its tracks; output is QQ-only")
    }
    @Test fun concurrentFirstDeviceRequestsReturnDifferentPersistentIdentities() = runBlocking {
        val entered=CountDownLatch(2);val release=CountDownLatch(1)
        val memory=MemoryPrefs()
        val gate=object:SharedPreferences by memory {
            override fun getString(key:String,fallback:String?):String? {
                val old=synchronized(memory) { memory.getString(key,fallback) }
                if(key=="qq_device_seed" && old==null) { entered.countDown();check(release.await(5,TimeUnit.SECONDS)) }
                return old
            }
        }
        val context=object:MemoryContext() {
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=gate
        }
        val a=async(Dispatchers.Default) { QqIdentity.deviceId(context) }
        val b=async(Dispatchers.Default) { QqIdentity.deviceId(context) }
        try {
            assertTrue(entered.await(4,TimeUnit.SECONDS));release.countDown()
            val first=a.await();val second=b.await()
            assertNotEquals(first,second)
            val saved=QqIdentity.deviceId(context)
            assertTrue(saved==first || saved==second)
            println("AUDIT QQ identity: two concurrent first reads each commit successfully and return different device ids; commit is not compare-and-set")
        } finally { release.countDown();a.cancelAndJoin();b.cancelAndJoin() }
    }
}
