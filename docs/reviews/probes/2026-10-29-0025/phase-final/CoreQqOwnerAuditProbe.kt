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

/** Executes real repository paging with every HTTP request answered locally before DNS. */
class CoreQqOwnerAuditProbe {
    private class MemoryContext:ContextWrapper(null) {
        private val stores=mutableMapOf<String,SharedPreferences>()
        override fun getApplicationContext():Context=this
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=synchronized(stores) { stores.getOrPut(name) { MemoryPrefs() } }
    }
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply { isAccessible=true }
    @Test fun favoritePagingCanReadBAndCacheTheMergedSongsUnderA() = runBlocking {
        val context=MemoryContext()
        val saved=listOf(QqClient to "appContext", QqPlaylistRepository to "appContext",QqAuthStore to "authRevision",QqAuthStore to "vipSnapshot")
            .map { (o,n) -> Triple(o,field(o,n),field(o,n).get(o)) }
        val lazy=field(QqClient,"http\$delegate").get(QqClient) as Lazy<*>
        val old=lazy.value;val value=field(lazy,"_value")
        val pageUins=mutableListOf<String>()
        val fake=OkHttpClient.Builder().dns(object : Dns { override fun lookup(hostname:String):List<java.net.InetAddress> = throw AssertionError("DNS forbidden") })
            .addInterceptor { c ->
                val bytes=Buffer();c.request().body!!.writeTo(bytes)
                val envelope=JSONObject(bytes.readUtf8());val req=envelope.getJSONObject("req")
                val text=if(req.getString("module")==QqRequests.USER_INFO_MODULE)
                    """{"req":{"code":0,"data":{"info":{"nick":"fixture-A"}}}}"""
                else {
                    val p=req.getJSONObject("param")
                    assertEquals(201,p.getInt("dirid"))
                    val uin=envelope.getJSONObject("comm").getString("uin")
                    pageUins+=uin
                    assertTrue(c.request().header("Cookie").orEmpty().contains("uin=$uin"))
                    val first=p.getInt("song_begin")==0
                    val id=if(first) 101 else 202
                    """{"req":{"code":0,"data":{"total_song_num":2,"hasmore":${if(first) 1 else 0},"dirinfo":{"id":0,"dirid":201},"songlist":[{"id":$id,"mid":"fixture-$id","name":"owner-$uin-song"}]}}}"""
                }
                Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("local audit")
                    .body(text.toResponseBody()).build()
            }.build()
        try {
            QqClient.init(context);QqPlaylistRepository.init(context);value.set(lazy,fake)
            QqAuthStore.replaceCookie(context,"uin=10001; qqmusic_key=fixture-A-key")
            val key=PlaylistKey(MusicSource.QQMUSIC,"0","10001")
            val result=QqPlaylistRepository.loadDetail(key,201,true,onPage={ page ->
                if(page.songs.any { it.name=="owner-10001-song" })
                    QqAuthStore.replaceCookie(context,"uin=20002; qqmusic_key=fixture-B-key")
            })
            assertEquals(listOf("10001","20002"),pageUins)
            assertEquals(20002L,QqAuthStore.uin(context))
            assertTrue(result is PlaylistResult.Data)
            val returned=(result as PlaylistResult.Data).outcome
            assertEquals(listOf("owner-10001-song","owner-20002-song"),returned.data.songs.map { it.name })
            val cache=QqPlaylistStore.readDetail(context,key,System.currentTimeMillis()) as PlaylistCacheCodec.DetailRead.Ok
            assertTrue(cache.complete)
            assertEquals(returned.data.songs.map { it.name },cache.songs.map { it.name })
            println("DEFECT: real QQ loadDetail reads page0 as A and page1 as B, returns mixed account favorites, and writes complete cache under key.ownerId=A. All replies local; account switch injected between pages via the real onPage callback.")
        } finally {
            QqAuthStore.clear(context);value.set(lazy,old)
            saved.asReversed().forEach { (o,f,v)->f.set(o,v) }
            fake.dispatcher.executorService.shutdown()
        }
    }
}
