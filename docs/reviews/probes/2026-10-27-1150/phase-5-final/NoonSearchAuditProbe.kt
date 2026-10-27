package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.network.*
import com.takahashirinta.ncrust.player.SongUrlResult
import com.takahashirinta.ncrust.source.*
import com.takahashirinta.ncrust.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class NoonSearchAuditProbe {
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply { isAccessible=true }
    private suspend fun SearchViewModel.page(type:Int,append:Boolean,page:Int):Boolean = suspendCoroutineUninterceptedOrReturn { continuation ->
        javaClass.getDeclaredMethod("searchByType",Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Int::class.javaPrimitiveType,kotlin.coroutines.Continuation::class.java)
            .apply { isAccessible=true }.invoke(this,type,append,page,continuation)
    }
    private suspend fun fixture(reply:(Request)->Pair<Int,String>,qqRows:Int=30,block:suspend (SearchViewModel)->Unit) {
        val lazy=field(RetrofitClient,"api\$delegate").get(RetrofitClient) as Lazy<*>
        val oldApi=lazy.value;val value=field(lazy,"_value")
        val client=OkHttpClient.Builder().addInterceptor { c ->
            val (code,body)=reply(c.request())
            Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(code).message("local audit").body(body.toResponseBody()).build()
        }.build()
        val api=Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client).addConverterFactory(GsonConverterFactory.create()).build().create(NcmApi::class.java)
        value.set(lazy,api)
        val providers=field(SourceRouter,"providers").get(SourceRouter) as MutableMap<MusicSource,MusicSourceProvider>
        val oldQq=providers[MusicSource.QQMUSIC]
        val oldBili=providers[MusicSource.BILIBILI]
        SourceRouter.register(object:MusicSourceProvider {
            override val source=MusicSource.BILIBILI
            override val isLoggedIn=false
            override suspend fun searchSongs(keyword:String,limit:Int,page:Int,totalOut:MutableMap<String,Int>?)=emptyList<SongItem>()
            override suspend fun resolveUrl(song:SongItem,level:String):SongUrlResult?=null
            override suspend fun songDetail(song:SongItem)=song
        })
        SourceRouter.register(object:MusicSourceProvider {
            override val source=MusicSource.QQMUSIC
            override val isLoggedIn=true
            override suspend fun searchSongs(keyword:String,limit:Int,page:Int,totalOut:MutableMap<String,Int>?):List<SongItem> {
                delay(40)
                return (0 until minOf(limit,qqRows)).map { SongItem(SourceIds.qqId(page*1000L+it,"fixture-$page-$it"),"fixture",null,null,200000,source="qqmusic",sourceId="fixture-$page-$it") }
            }
            override suspend fun resolveUrl(song:SongItem,level:String):SongUrlResult?=null
            override suspend fun songDetail(song:SongItem)=song
        })
        val vm=SearchViewModel()
        (field(vm,"_query").get(vm) as MutableStateFlow<String>).value="fixture"
        try { block(vm) } finally {
            value.set(lazy,oldApi)
            if(oldQq==null) providers.remove(MusicSource.QQMUSIC) else SourceRouter.register(oldQq)
            if(oldBili==null) providers.remove(MusicSource.BILIBILI) else SourceRouter.register(oldBili)
            client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun failedNeteasePageIsStillConfirmedAndSkippedDespiteThePerSourceCursor()=runBlocking {
        val offsets=mutableListOf<Int>()
        fixture({ request ->
            val body=Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val offset=Regex("(?:^|&)offset=(\\d+)").find(body)!!.groupValues[1].toInt()
            offsets+=offset
            val rows=JSONArray().apply { repeat(30) { put(JSONObject().put("id",1000+offset+it).put("name","fixture")) } }
            (if(offset==30) 500 else 200) to JSONObject().put("result",JSONObject().put("songs",rows)).toString()
        }) { vm ->
            assertTrue(vm.page(1,false,1));assertTrue(vm.page(1,true,2));assertTrue(vm.page(1,true,3))
            assertEquals(listOf(0,30,60),offsets)
            assertFalse(vm.songs.value.any { it.id==1030L })
            assertTrue(vm.songs.value.any { it.id==1060L })
            assertEquals(60,vm.sourceCounts.value!!.neteaseCount)
            println("AUDIT S9 residual: real searchByType requests offsets 0,30(failed),60 instead of retrying 30; accumulateAndPublish confirms an empty failed page and erases pendingPage. The previous duplicate source count is fixed: 60 unique Netease rows and count=60")
        }
    }
    @Test fun clearingTheQueryDoesNotStopALateAlbumResultFromRepopulatingThePage()=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fixture({ _ ->
            entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))
            200 to """{"result":{"albums":[{"id":1234,"name":"late album"}],"albumCount":1}}"""
        }) { vm ->
            (field(vm,"_currentType").get(vm) as MutableStateFlow<Int>).value=10
            val old=async(Dispatchers.Default) { vm.page(10,false,1) }
            field(vm,"searchJob").set(vm,old)
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS))
                vm.clearQuery();assertEquals("",vm.query.value);assertTrue(vm.albums.value.isEmpty())
                release.countDown();assertTrue(old.await())
                assertEquals("",vm.query.value);assertEquals(1234L,vm.albums.value.single().id)
                println("AUDIT clear query: real clearQuery clears the input and lists, but the already-started real album HTTP result publishes afterward and repopulates the blank-query page; no visible browser or Main dispatcher is used")
            } finally { release.countDown();old.cancelAndJoin() }
        }
    }
    @Test fun anHttpSuccessWithABusinessErrorBecomesASuccessfulEmptySearch()=runBlocking {
        fixture(reply={200 to """{"code":500,"message":"fixture temporary failure"}"""},qqRows=0) { vm ->
            assertTrue(vm.page(1,false,1))
            assertTrue(vm.songs.value.isEmpty());assertNull(vm.error.value);assertFalse(vm.hasMore.value)
            assertEquals("DONE",vm.sourceCounts.value!!.neteaseStatus.name)
            println("AUDIT S9 status boundary: HTTP 200/code500 without result is counted as a successful empty Netease search, error=null, badge DONE, hasMore=false; actual JSON model discards business code")
        }
    }
}
