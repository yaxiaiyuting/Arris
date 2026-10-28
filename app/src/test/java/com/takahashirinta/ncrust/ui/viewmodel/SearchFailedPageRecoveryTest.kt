package com.takahashirinta.ncrust.ui.viewmodel

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

class SearchFailedPageRecoveryTest {
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
    @Test fun failedNeteasePageIsRetriedBeforeAdvancing()=runBlocking {
        val offsets=mutableListOf<Int>()
        fixture({ request ->
            val body=Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val offset=Regex("(?:^|&)offset=(\\d+)").find(body)!!.groupValues[1].toInt()
            offsets+=offset
            val rows=JSONArray().apply { repeat(30) { put(JSONObject().put("id",1000+offset+it).put("name","fixture")) } }
            (if(offset==30 && offsets.count { it == 30 } == 1) 500 else 200) to JSONObject().put("result",JSONObject().put("songs",rows)).toString()
        }) { vm ->
            assertTrue(vm.page(1,false,1));assertTrue(vm.page(1,true,2));assertTrue(vm.page(1,true,3))
            assertEquals(listOf(0,30,30),offsets)
            assertTrue(vm.songs.value.any { it.id==1030L })
            assertFalse(vm.songs.value.any { it.id==1060L })
            assertEquals(60,vm.sourceCounts.value!!.neteaseCount)
        }
    }
    @Test fun businessFailureIsAnErrorInsteadOfASuccessfulEmptySearch()=runBlocking {
        fixture(reply={200 to """{"code":500,"message":"fixture temporary failure"}"""},qqRows=0) { vm ->
            assertFalse(vm.page(1,false,1))
            assertTrue(vm.songs.value.isEmpty());assertNotNull(vm.error.value);assertTrue(vm.hasMore.value)
            assertEquals("ERROR",vm.sourceCounts.value!!.neteaseStatus.name)
        }
    }
}
