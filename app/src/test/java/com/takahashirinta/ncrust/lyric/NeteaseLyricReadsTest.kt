/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.lyric

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.auth.NeteaseSessionChangedException
import com.takahashirinta.ncrust.network.NcmApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.model.LyricContent
import com.takahashirinta.ncrust.network.model.LyricResponse
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class NeteaseLyricReadsTest {
    private class FixtureContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, MemoryPrefs>()
        val writes = AtomicInteger()

        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val prefs = synchronized(stores) { stores.getOrPut(name) { MemoryPrefs() } }
            if (name != "ncrust_lyrics_cache") return prefs
            return object : SharedPreferences by prefs {
                override fun edit(): SharedPreferences.Editor {
                    writes.incrementAndGet()
                    return prefs.edit()
                }
            }
        }
    }

    private val full = LyricResponse(200, LyricContent("[00:01.00]Fixture"), null)
    private val context = FixtureContext()
    private val appField = CookieManager.javaClass.getDeclaredField("app").apply { isAccessible = true }
    private var originalApp: Any? = null
    private var originalCookie: String? = null

    @Before fun isolateLogin() {
        originalApp = appField.get(CookieManager)
        originalCookie = CookieManager.snapshot().cookie
        appField.set(CookieManager, null)
        CookieManager.init(context)
    }

    @After fun restoreLogin() {
        CookieManager.clearCookie(context)
        originalCookie?.let { CookieManager.saveCookie(context, it) }
        appField.set(CookieManager, originalApp)
    }

    @Test fun cancellingThePreloadLeavesOneRequestAndOneWriteForPlayback() = runBlocking {
        val song = 92_000_100L
        val session = CookieManager.snapshot()
        val started = CompletableDeferred<Unit>()
        val respond = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val preload = async {
            NeteaseLyricReads.read(context, song, session, refresh = true) { _, owner ->
                assertSame(session, owner)
                calls.incrementAndGet()
                started.complete(Unit)
                respond.await()
                full
            }
        }
        withTimeout(5_000L) { started.await() }
        val playing = async {
            NeteaseLyricReads.read(context, song, session, refresh = false) { _, _ ->
                calls.incrementAndGet()
                full
            }
        }
        withTimeout(5_000L) {
            while (NeteaseLyricReads.readerCount(song, session) != 2) delay(5L)
        }
        preload.cancelAndJoin()
        respond.complete(Unit)
        assertEquals(full.lrc?.lyric, withTimeout(5_000L) { playing.await() }?.lrc)
        assertEquals(1, calls.get())
        assertEquals(1, context.writes.get())
        assertEquals(0, NeteaseLyricReads.readerCount(song, session))
        assertEquals(
            full.lrc?.lyric,
            NeteaseLyricReads.read(context, song, session, refresh = false) { _, _ ->
                error("Expected completed lyrics in cache")
            }?.lrc,
        )
    }

    @Test fun anUnsettledResponseCanBeRetriedWithoutCachingIt() = runBlocking {
        val song = 92_000_101L
        val session = CookieManager.snapshot()
        assertNull(NeteaseLyricReads.read(context, song, session, refresh = true) { _, _ ->
            LyricResponse(301, null, null)
        })
        assertEquals(0, context.writes.get())
        assertEquals(
            full.lrc?.lyric,
            NeteaseLyricReads.read(context, song, session, refresh = false) { _, _ -> full }?.lrc,
        )
        assertEquals(1, context.writes.get())
    }

    @Test fun aNewLoginDoesNotJoinAnOldReadOrAcceptItsLateResult() = runBlocking {
        val song = 92_000_102L
        val saved = CookieManager.snapshot()
        val started = CompletableDeferred<Unit>()
        val respond = CompletableDeferred<Unit>()
        val old = async {
            try {
                NeteaseLyricReads.read(context, song, saved, refresh = true) { _, _ ->
                    started.complete(Unit)
                    respond.await()
                    full
                }
                false
            } catch (_: NeteaseSessionChangedException) {
                true
            }
        }
        try {
            withTimeout(5_000L) { started.await() }
            CookieManager.advanceGeneration(context)
            val current = CookieManager.snapshot()
            val latest = LyricResponse(200, LyricContent("latest fixture"), null)
            assertEquals(
                "latest fixture",
                withTimeout(5_000L) {
                    NeteaseLyricReads.read(context, song, current, refresh = true) { _, _ -> latest }?.lrc
                },
            )
            respond.complete(Unit)
            assertTrue(withTimeout(5_000L) { old.await() })
            assertEquals(1, context.writes.get())
            assertEquals("latest fixture", LyricsCache.get(context, song)?.lrc)
        } finally {
            respond.complete(Unit)
            old.cancelAndJoin()
        }
    }

    @Test fun retrofitRejectsAQueuedRequestFromThePreviousLogin() = runBlocking {
        val transportCalls = AtomicInteger()
        val interceptor = RetrofitClient::class.java.declaredClasses
            .single { it.simpleName == "CookieInterceptor" }
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance() as Interceptor
        val client = OkHttpClient.Builder().addInterceptor(interceptor).addInterceptor { chain ->
            val request = chain.request()
            transportCalls.incrementAndGet()
            val form = request.body as FormBody
            assertEquals("92000103", form.value(0))
            assertEquals("api/song/lyric", request.url.encodedPath.trimStart('/'))
            assertEquals("MUSIC_U=fixture-b; __csrf=b", request.header("Cookie"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":200,"lrc":{"lyric":"fixture"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://fixture.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(NcmApi::class.java)
        CookieManager.saveCookie(context, "MUSIC_U=fixture-a; __csrf=a")
        val old = CookieManager.snapshot()
        CookieManager.saveCookie(context, "MUSIC_U=fixture-b; __csrf=b")
        try {
            api.getLyric(92_000_103L, session = old)
            error("Queued request used the new account")
        } catch (_: NeteaseSessionChangedException) {
            assertEquals(0, transportCalls.get())
        }
        val reply = api.getLyric(92_000_103L, session = CookieManager.snapshot())
        assertEquals("fixture", reply.lrc?.lyric)
        assertEquals(1, transportCalls.get())
    }
}
