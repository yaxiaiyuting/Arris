package com.takahashirinta.ncrust.audit

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.qq.*
import com.takahashirinta.ncrust.source.*
import com.takahashirinta.ncrust.playlist.*
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScheduledQqPagingAuditProbe {
    private class MemoryContext:ContextWrapper(null) {
        private val stores=mutableMapOf<String,SharedPreferences>()
        override fun getApplicationContext():Context=this
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=stores.getOrPut(name) { MemoryPrefs() }
    }
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply { isAccessible=true }
    private fun <T> intercepted(block:(JSONObject)->String,run:(MemoryContext)->T):T {
        val context=MemoryContext();val app=field(QqClient,"appContext");val oldApp=app.get(QqClient)
        val repoApp=field(QqPlaylistRepository,"appContext");val oldRepo=repoApp.get(QqPlaylistRepository)
        val lazy=field(QqClient,"http\$delegate").get(QqClient) as Lazy<*>
        val old=lazy.value;val value=field(lazy,"_value")
        val fake=OkHttpClient.Builder().addInterceptor { c ->
            val bytes=Buffer();c.request().body!!.writeTo(bytes)
            val response=block(JSONObject(bytes.readUtf8()).getJSONObject("req"))
            Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("audit")
                .body(response.toResponseBody()).build()
        }.build()
        QqClient.init(context);QqPlaylistRepository.init(context);value.set(lazy,fake)
        try { return run(context) } finally {
            value.set(lazy,old);app.set(QqClient,oldApp);repoApp.set(QqPlaylistRepository,oldRepo)
            fake.dispatcher.executorService.shutdown()
        }
    }
    @Test fun skippedRawSongRowsMakeTheNextOffsetOverlapThePreviousPage() {
        val offsets=mutableListOf<Int>()
        intercepted({ req ->
            val begin=req.getJSONObject("param").getInt("song_begin");offsets.add(begin)
            val rows=when(begin) {
                0 -> """[{"id":999,"name":"unplayable no mid"},{"id":101,"mid":"fixture-mid101","name":"A"}]"""
                1 -> """[{"id":101,"mid":"fixture-mid101","name":"A"},{"id":202,"mid":"fixture-mid202","name":"B"}]"""
                2 -> """[{"id":202,"mid":"fixture-mid202","name":"B"}]"""
                else -> error("unexpected offset $begin")
            }
            """{"req":{"code":0,"data":{"total_song_num":3,"hasmore":${if(begin==0) 1 else 0},"dirinfo":{"id":55,"dirid":1},"songlist":$rows}}}"""
        }) { _ -> runBlocking {
            val result=QqPlaylistApi.fetchAllPages(PlaylistKey(MusicSource.QQMUSIC,"55","10001"),55,1,pageSize=2)
            assertTrue(result is QqPlaylistResult.Ok)
            val songs=(result as QqPlaylistResult.Ok).value.songs
            assertEquals(listOf(0,1),offsets)
            assertEquals(listOf("fixture-mid101","fixture-mid101","fixture-mid202"),songs.map { it.sourceId })
            println("AUDIT QQ playlist offset: one unmappable raw row advances offset by 1 instead of 2, fetching the prior song again and returning a duplicate")
        } }
    }
    @Test fun missingSongArrayIsCachedAsAFreshCompleteEmptyPlaylist() {
        intercepted({ req ->
            val module=req.getString("module")
            if(module==QqRequests.USER_INFO_MODULE) """{"req":{"code":0,"data":{"info":{"nick":"fixture"}}}}"""
            else """{"req":{"code":0,"data":{"total_song_num":100,"dirinfo":{"id":55,"dirid":1,"songnum":100}}}}"""
        }) { context -> runBlocking {
            QqAuthStore.replaceCookie(context,"uin=10001; qqmusic_key=fixture-key")
            try {
                val key=PlaylistKey(MusicSource.QQMUSIC,"55","10001")
                val result=QqPlaylistRepository.loadDetail(key,1,true)
                assertTrue(result is PlaylistResult.Data)
                val outcome=(result as PlaylistResult.Data).outcome
                assertTrue(outcome.data.songs.isEmpty())
                assertNull(outcome.degradation)
                val cached=QqPlaylistStore.readDetail(context,key,System.currentTimeMillis()) as PlaylistCacheCodec.DetailRead.Ok
                assertTrue(cached.complete);assertTrue(cached.fresh);assertEquals(100,cached.total)
                println("AUDIT QQ completeness: no songlist with declared total100 is accepted and cached as complete=true, fresh=true, zero songs, without degradation")
            } finally { QqAuthStore.clear(context) }
        } }
    }
}
