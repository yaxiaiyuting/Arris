package com.takahashirinta.ncrust.account

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.account.*
import com.takahashirinta.ncrust.auth.*
import com.takahashirinta.ncrust.cache.*
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Local intercepted production-entry regressions; no real accounts or network. */
class SessionAndHomeBoundaryTest {
    private class MemoryContext : ContextWrapper(null) {
        val stores = mutableMapOf<String, MemoryPrefs>()
        var afterDailyRead: (() -> Unit)? = null
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val base = synchronized(stores) { stores.getOrPut(name) { MemoryPrefs() } }
            if (name != "ncrust_home_cache") return base
            return object : SharedPreferences by base {
                override fun getString(key: String, defValue: String?): String? {
                    val result = base.getString(key, defValue)
                    if (key == "daily_songs") {
                        val action = afterDailyRead
                        afterDailyRead = null
                        action?.invoke()
                    }
                    return result
                }
            }
        }
    }
    private fun field(o: Any, name: String) = o.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private suspend fun settle(o: Any) {
        val scope = field(o, "scope").get(o) as CoroutineScope
        withTimeout(6000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
    }
    private suspend fun fixture(body: suspend (MemoryContext) -> Unit) {
        val context = MemoryContext()
        val guard = field(CookieManager, "guard").get(CookieManager)
        val originals = listOf(CookieManager to "app", NeteaseTokenRefresher to "app", SessionMonitor to "appContext",
            SessionMonitor to "lastCheckAt", SessionMonitor to "lastRevision", SessionMonitor to "retryAfterAt",
            guard to "revision", guard to "cookie", guard to "loginGeneration")
            .map { (o,n) -> Triple(o,field(o,n),field(o,n).get(o)) }
        @Suppress("UNCHECKED_CAST")
        val state = field(SessionMonitor,"_state").get(SessionMonitor) as MutableStateFlow<SessionState>
        val previous = state.value
        ContentCache.clearAll()
        field(CookieManager,"app").set(CookieManager,null)
        try {
            CookieManager.saveCookie(context,"MUSIC_U=fixture-account-A; __csrf=fixture-csrf")
            context.getSharedPreferences("ncrust_prefs",0).edit().putLong("session_refreshed_at",System.currentTimeMillis()).commit()
            body(context)
        } finally {
            settle(SessionMonitor);settle(NeteaseTokenRefresher)
            ContentCache.clearAll()
            state.value=previous
            originals.asReversed().forEach { (o,f,v) -> f.set(o,v) }
        }
    }

    @Test fun businessFailureUsesShortRetryAndNeverSuccessfulTtl() = runBlocking {
        fixture { context ->
            var healthy=false;var requests=0
            val lazy=field(RetrofitClient,"plainClient\$delegate").get(RetrofitClient) as Lazy<*>
            val old=lazy.value;val value=field(lazy,"_value")
            val fake=OkHttpClient.Builder().dns(object : Dns { override fun lookup(hostname: String): List<java.net.InetAddress> = throw AssertionError("DNS prohibited") }).addInterceptor { chain ->
                requests++
                val r=chain.request()
                val payload = if (!healthy) """{"code":500,"message":"fixture service unavailable"}"""
                    else if (r.url.encodedPath.endsWith("/w/nuser/account/get"))
                        """{"code":200,"account":{"id":10001},"profile":{"userId":10001,"nickname":"fixture"}}"""
                    else """{"code":200,"data":{}}"""
                Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("local fixture")
                    .body(payload.toResponseBody()).build()
            }.build()
            value.set(lazy,fake)
            try {
                @Suppress("UNCHECKED_CAST")
                val state=field(SessionMonitor,"_state").get(SessionMonitor) as MutableStateFlow<SessionState>
                state.value=SessionState.Unknown
                SessionMonitor.refresh(context,true);settle(SessionMonitor)
                assertEquals(1,requests)
                assertEquals(SessionState.Unknown,state.value)
                healthy=true
                assertEquals(-1L, field(SessionMonitor,"lastCheckAt").getLong(SessionMonitor))
                SessionMonitor.onResume(context);settle(SessionMonitor)
                assertEquals("short cooldown prevents immediate hammering",1,requests)
                field(SessionMonitor,"retryAfterAt").setLong(SessionMonitor,0L)
                SessionMonitor.onResume(context);settle(SessionMonitor)
                assertTrue(state.value is SessionState.Valid)
                assertTrue(requests>1)
            } finally { value.set(lazy,old);fake.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun accountChangeWhileReadingSnapshotCannotPublishPreviousAccount() = runBlocking {
        fixture { context ->
            val ownerA=CookieManager.accountOwner()
            context.getSharedPreferences("ncrust_home_cache",0).edit()
                .putString("owner",ownerA)
                .putString("daily_songs","""[{"id":424242,"name":"account A private recommendation"}]""").commit()
            context.afterDailyRead={ CookieManager.saveCookie(context,"MUSIC_U=fixture-account-B; __csrf=fixture-csrf-B") }
            assertFalse(HomeSnapshot.restoreIntoCache(context))
            val ownerB=CookieManager.accountOwner()
            assertNotEquals(ownerA,ownerB)
            assertTrue(ContentCache.ownerMatches(ownerB))
            assertNull(ContentCache.homeDailySongs)
            assertFalse(context.getSharedPreferences("ncrust_home_cache",0).contains("daily_songs"))
        }
    }

    @Test fun matchingSnapshotRestoresNormallyWithoutAccountChange() = runBlocking {
        fixture { context ->
            val owner=CookieManager.accountOwner()
            context.getSharedPreferences("ncrust_home_cache",0).edit().putString("owner",owner)
                .putString("daily_songs","""[{"id":707,"name":"same owner"}]""").commit()
            assertTrue(HomeSnapshot.restoreIntoCache(context))
            assertTrue(ContentCache.ownerMatches(owner))
            assertEquals(707L,ContentCache.homeDailySongs!!.single().id)
        }
    }
}
