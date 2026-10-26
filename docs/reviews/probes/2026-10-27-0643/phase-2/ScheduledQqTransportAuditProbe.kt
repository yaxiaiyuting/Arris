package com.takahashirinta.ncrust.audit

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.ResolveFailureKind
import com.takahashirinta.ncrust.qq.*
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.*
import okhttp3.*
import okio.Buffer
import okio.BufferedSource
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScheduledQqTransportAuditProbe {
    private class MemoryContext:ContextWrapper(null) {
        private val stores=mutableMapOf<String,SharedPreferences>()
        override fun getApplicationContext():Context=this
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=stores.getOrPut(name) { MemoryPrefs() }
    }
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply { isAccessible=true }
    @Test fun unrelatedSuccessfulMusicuCallTurnsAVkey403IntoANetworkFailure() = runBlocking {
        val context=MemoryContext();val restore=mutableListOf<()->Unit>()
        val release=CountDownLatch(1);val classified403=CountDownLatch(1)
        var gateEnabled=false
        val app=field(QqClient,"appContext");val originalApp=app.get(QqClient)
        QqClient.init(context);restore.add { app.set(QqClient,originalApp) }
        QqAuthStore.replaceCookie(context,"uin=10001; qqmusic_key=fixture-key")
        val lazy=field(QqClient,"http\$delegate").get(QqClient) as Lazy<*>
        val original=lazy.value;val value=field(lazy,"_value")
        val fake=OkHttpClient.Builder().addInterceptor { c ->
            val raw=Buffer();c.request().body!!.writeTo(raw)
            val healthy=JSONObject(raw.readUtf8()).getJSONObject("req").optString("module")=="fixtureHealthy"
            val text=if(healthy) """{"req":{"code":0,"data":{}}}""" else "forbidden"
            val blockThis=gateEnabled && !healthy
            val body=object:ResponseBody() {
                private val buffer=Buffer().writeUtf8(text)
                override fun contentType():MediaType?=null
                override fun contentLength():Long=text.toByteArray().size.toLong()
                override fun source():BufferedSource=buffer
                override fun close() {
                    super.close()
                    if(blockThis) { classified403.countDown();check(release.await(5,TimeUnit.SECONDS)) }
                }
            }
            Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(if(healthy) 200 else 403)
                .message("audit").body(body).build()
        }.build()
        value.set(lazy,fake);restore.add { value.set(lazy,original);fake.dispatcher.executorService.shutdown() }
        val song=SongItem(SourceIds.qqId(101,"fixture-mid"),"fixture",null,null,200000,source="qqmusic",sourceId="fixture-mid")
        var work:Deferred<com.takahashirinta.ncrust.player.ResolveOutcome>?=null
        try {
            assertEquals(ResolveFailureKind.AUTH_EXPIRED,QqApi.resolveOutcome(song,"standard").failure!!.kind)
            gateEnabled=true
            val request=async(Dispatchers.Default) { QqApi.resolveOutcome(song,"standard") };work=request
            assertTrue(classified403.await(4,TimeUnit.SECONDS))
            assertEquals(403,QqClient.lastHttpStatus)
            assertNotNull(QqClient.musicu(JSONObject().put("module","fixtureHealthy").put("method","fixture").put("param",JSONObject()),true))
            assertEquals(200,QqClient.lastHttpStatus)
            release.countDown()
            val result=withTimeout(5000) { request.await() }
            assertEquals(ResolveFailureKind.NETWORK,result.failure!!.kind)
            println("AUDIT QQ transport: same vkey HTTP403 is AUTH_EXPIRED in isolation, NETWORK when a healthy profile-style request overwrites shared HTTP state before classification")
        } finally {
            release.countDown();work?.cancelAndJoin();QqAuthStore.clear(context)
            restore.asReversed().forEach { it() }
        }
    }
}
