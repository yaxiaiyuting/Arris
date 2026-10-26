package com.takahashirinta.ncrust.library

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.XeapiState
import com.takahashirinta.ncrust.network.crypto.EapiCrypto
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** All outbound calls intercepted before networking; passing assertions confirm wrong behavior. */
class CloudWriteAuditProbe {
    private class MemoryContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, SharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = synchronized(stores) {
            stores.getOrPut(name) { MemoryPrefs() }
        }
    }
    private val context = MemoryContext()
    private val restorers = mutableListOf<() -> Unit>()
    private val release = CountDownLatch(1)
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun replace(owner: Any, name: String, value: Any?) {
        val f = field(owner, name); val old = f.get(owner); f.set(owner, value); restorers.add { f.set(owner, old) }
    }
    private fun client(name: String, answer: (Request) -> String) {
        val lazy = field(RetrofitClient, name + "\$delegate").get(RetrofitClient) as Lazy<*>
        val value = field(lazy, "_value"); val before = lazy.value
        val fake = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("audit")
                .body(answer(chain.request()).toResponseBody()).build()
        }.build()
        value.set(lazy, fake)
        restorers.add { value.set(lazy, before); fake.dispatcher.executorService.shutdown() }
    }
    @Before fun setup() {
        replace(CookieManager, "app", null)
        CookieManager.saveCookie(context, "MUSIC_U=fixture-A; __csrf=fixture-csrf-A")
        replace(XeapiState, "appContext", null)
        replace(XeapiState, "cachedKeyState", null)
        listOf("cachedEntries", "cachedAlbums", "cachedLikedIds", "pendingAppContext", "flushJob").forEach { replace(LibraryManager, it, null) }
        client("antiCheatClient") { "null([200,1,\"fixture-token\"])" }
    }
    @After fun cleanup() {
        release.countDown()
        runBlocking { (field(LibraryManager, "flushJob").get(LibraryManager) as? Job)?.let { it.cancel(); it.join() } }
        CookieManager.clearCookie(context)
        restorers.asReversed().forEach { it() }
    }
    private fun likeOf(request: Request): Boolean {
        val form = request.body as FormBody
        val hex = form.value(0)
        val encrypted = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val text = EapiCrypto.decryptResponse(Base64.getEncoder().encodeToString(encrypted))
        return JSONObject(text.split("-36cd479b6b5-")[1]).getBoolean("like")
    }

    @Test fun oldLikeCanFinishAfterNewUnlikeAndReverseTheLastUserAction() = runBlocking {
        val oldEntered = CountDownLatch(1)
        val unlikeDone = CountDownLatch(1)
        val allDone = CountDownLatch(2)
        val simulatedCloud = AtomicReference<Boolean?>(null)
        client("plainClient") { request ->
            when (request.url.encodedPath) {
                "/weapi/radio/like" -> """{"code":500}"""
                "/eapi/radio/like" -> {
                    val like = likeOf(request)
                    if (like) { oldEntered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                    simulatedCloud.set(like)
                    if (!like) unlikeDone.countDown()
                    allDone.countDown()
                    """{"code":200}"""
                }
                else -> error("Unexpected request ${request.url.encodedPath}")
            }
        }
        assertEquals(SavedSongOutcome.ADDED, LibraryManager.saveSong(context, SongItem(101, "fixture", null, null, 200000)))
        assertTrue(oldEntered.await(4, TimeUnit.SECONDS))
        assertTrue(LibraryManager.removeSong(context, 101))
        assertTrue(unlikeDone.await(4, TimeUnit.SECONDS))
        assertEquals(false, simulatedCloud.get())
        release.countDown()
        assertTrue(allDone.await(4, TimeUnit.SECONDS))
        assertEquals(true, simulatedCloud.get())
        assertFalse(LibraryManager.isSongSaved(context, 101))
        println("AUDIT cloud write: real Library save -> remove leaves local tombstone, but reordered mocked server writes finish at like=true")
    }

    @Test fun oldAccountsLikeFallbackUsesTheNewAccountsCookie() = runBlocking {
        val entered = CountDownLatch(1)
        val fallbackCookie = AtomicReference<String?>()
        // JVM Android Base64 is a stub, so do not gate on successful WEAPI encryption.
        // Hold the real XEAPI preflight, then let its unavailable key state fall through.
        client("antiCheatClient") {
            assertTrue(CookieManager.snapshot().cookie.orEmpty().contains("MUSIC_U=fixture-A"))
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            "null([200,1,\"fixture-token\"])"
        }
        client("plainClient") { request ->
            when (request.url.encodedPath) {
                "/weapi/radio/like" -> """{"code":500}"""
                "/eapi/radio/like" -> {
                    fallbackCookie.set(request.header("Cookie"))
                    """{"code":200}"""
                }
                else -> error("Unexpected request ${request.url.encodedPath}")
            }
        }
        val liking = async(Dispatchers.Default) { PlaylistApi.likeSong(101, true) }
        assertTrue("XEAPI preflight entered under A", entered.await(4, TimeUnit.SECONDS))
        CookieManager.clearCookie(context)
        CookieManager.saveCookie(context, "MUSIC_U=fixture-B; __csrf=fixture-csrf-B")
        release.countDown()
        assertTrue(withTimeout(5000) { liking.await() })
        assertTrue(fallbackCookie.get().orEmpty().contains("MUSIC_U=fixture-B"))
        println("AUDIT cloud write: like started under A emits its fallback mutation with B credential after account switch")
    }
}
