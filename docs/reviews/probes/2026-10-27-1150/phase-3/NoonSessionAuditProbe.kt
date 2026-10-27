package com.takahashirinta.ncrust.audit

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.account.*
import com.takahashirinta.ncrust.auth.*
import com.takahashirinta.ncrust.network.*
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class NoonSessionAuditProbe {
    private class MemoryContext:ContextWrapper(null) {
        private val stores=mutableMapOf<String,SharedPreferences>()
        override fun getApplicationContext():Context=this
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=synchronized(stores) { stores.getOrPut(name) { MemoryPrefs() } }
    }
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply { isAccessible=true }
    private suspend fun settle(o:Any,name:String="scope") {
        val scope=field(o,name).get(o) as CoroutineScope
        withTimeout(6000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
    }
    private suspend fun <T> fixture(reply:(Request)->String,block:suspend (MemoryContext)->T):T {
        val context=MemoryContext()
        val stateFields=listOf(CookieManager to "app",NeteaseTokenRefresher to "app",SessionMonitor to "appContext",SessionMonitor to "lastCheckAt",SessionMonitor to "lastRevision")
        val originals=stateFields.map { (o,n)->Triple(o,field(o,n),field(o,n).get(o)) }
        field(CookieManager,"app").set(CookieManager,null)
        CookieManager.saveCookie(context,"MUSIC_U=fixture-A; __csrf=fixture-A-csrf")
        context.getSharedPreferences("ncrust_prefs",0).edit().putLong("session_refreshed_at",System.currentTimeMillis()).commit()
        val lazy=field(RetrofitClient,"plainClient\$delegate").get(RetrofitClient) as Lazy<*>
        val oldClient=lazy.value;val value=field(lazy,"_value")
        val fake=OkHttpClient.Builder().addInterceptor { c ->
            Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200).message("local audit")
                .body(reply(c.request()).toResponseBody()).build()
        }.build()
        value.set(lazy,fake)
        try { return block(context) } finally {
            settle(SessionMonitor);settle(NeteaseTokenRefresher)
            CookieManager.clearCookie(context);value.set(lazy,oldClient)
            originals.asReversed().forEach { (o,f,v)->f.set(o,v) }
            fake.dispatcher.executorService.shutdown()
        }
    }
    @Test fun aServerValidatedQrDeltaIsDiscardedWhileTheOldCompleteCookieSurvives()=runBlocking {
        fixture({ r ->
            assertTrue(r.url.encodedPath.endsWith("/w/nuser/account/get"))
            assertTrue(r.header("Cookie").orEmpty().contains("MUSIC_U=fixture-B"))
            """{"code":200,"account":{"id":20002},"profile":{"userId":20002}}"""
        }) { context ->
            val candidate=QrLoginProtocol.sessionCookie(listOf("MUSIC_U=fixture-B; Path=/; HttpOnly"))!!
            assertEquals(PlaylistApi.SessionCheck.Valid,PlaylistApi.validateSession(candidate))
            assertFalse(CookieManager.saveCookieMerged(context,candidate))
            assertTrue(CookieManager.getCookie(context)!!.contains("MUSIC_U=fixture-A"))
            println("AUDIT QR delta boundary: a valid account/get result for B is handed to saveCookieMerged, which rejects the missing-CSRF delta and keeps A; the UI success callback ignores this result. Production response frequency was not measured")
        }
    }
    @Test fun aFailedInitialSessionCheckPreventsAHealthyResumeRetry()=runBlocking {
        var healthy=false;var requests=0
        fixture({ r ->
            requests++
            if(!healthy) throw IOException("fixture initially offline")
            if(r.url.encodedPath.endsWith("/w/nuser/account/get"))
                """{"code":200,"account":{"id":10001},"profile":{"userId":10001,"nickname":"fixture","avatarUrl":""}}"""
            else """{"code":200,"data":{}}"""
        }) { context ->
            val state=field(SessionMonitor,"_state").get(SessionMonitor) as MutableStateFlow<SessionState>
            val original=state.value
            try {
                state.value=SessionState.Unknown
                field(SessionMonitor,"lastCheckAt").setLong(SessionMonitor,0)
                field(SessionMonitor,"lastRevision").setLong(SessionMonitor,-1)
                SessionMonitor.init(context);settle(SessionMonitor)
                assertEquals(SessionState.Unknown,state.value)
                assertTrue(requests>0);val failedRequests=requests
                healthy=true;SessionMonitor.onResume(context);settle(SessionMonitor)
                assertEquals(failedRequests,requests)
                assertEquals(SessionState.Unknown,state.value)
                SessionMonitor.refresh(context,true);settle(SessionMonitor)
                assertTrue(state.value is SessionState.Valid)
                println("AUDIT session failure throttle: failed initial check records the 30-minute throttle, so a healthy onResume sends zero requests and remains Unknown; an explicit forced check succeeds")
            } finally { state.value=original }
        }
    }
}
