package com.takahashirinta.ncrust.network

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.crypto.EapiCrypto
import com.takahashirinta.ncrust.source.SourceIds
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

class PlaylistMutationAuditProbe {
    private class MemoryContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, SharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = stores.getOrPut(name) { MemoryPrefs() }
    }
    private val context = MemoryContext()
    private lateinit var value: java.lang.reflect.Field
    private lateinit var lazyClient: Any
    private var original: Any? = null
    private var fake: OkHttpClient? = null
    private fun field(o: Any, n: String) = o.javaClass.getDeclaredField(n).apply { isAccessible = true }
    @Before fun setup() {
        field(CookieManager, "app").set(CookieManager, null)
        CookieManager.saveCookie(context, "MUSIC_U=fixture-A; __csrf=fixture-A-csrf")
        val lazy = field(RetrofitClient, "plainClient\$delegate").get(RetrofitClient) as Lazy<*>
        original = lazy.value; lazyClient = lazy; value = field(lazy, "_value")
    }
    @After fun cleanup() {
        value.set(lazyClient, original); fake?.dispatcher?.executorService?.shutdown()
        CookieManager.clearCookie(context); field(CookieManager, "app").set(CookieManager, null)
    }
    private fun intercept(block: (Request, JSONObject) -> String) {
        fake = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val hex = (request.body as FormBody).value(0)
            val data = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            val text = EapiCrypto.decryptResponse(Base64.getEncoder().encodeToString(data))
            val payload = JSONObject(text.split("-36cd479b6b5-")[1])
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("audit")
                .body(block(request, payload).toResponseBody()).build()
        }.build()
        value.set(lazyClient, fake)
    }
    /**
     * 回归（原 S18 探针）：排队中的写操作**不能**在切账号后被新账号代发。
     *
     * 修复前：A 会话排队的建歌单在共享门控释放后带 B 的 cookie 发出。
     * 修复后：`createPlaylist` 接受调用方钉住的会话，代次不符直接拒绝（返回 null），
     * 拦截器里不会再看到这条请求。
     */
    @Test fun aQueuedPlaylistCreateStopsWhenTheAccountChanges() = runBlocking {
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sentAsB = false
        intercept { request, _ ->
            sentAsB = request.header("Cookie").orEmpty().contains("MUSIC_U=fixture-B")
            """{"code":200,"id":999}"""
        }
        val holder = launch(Dispatchers.Default) { PlaylistWriteGate.run { holding.complete(Unit); release.await() } }
        holding.await()
        // UNDISTPATCHED enters the real API and suspends at the held write gate before switching.
        val pinnedA = CookieManager.snapshot()
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            PlaylistEditApi.createPlaylist("fixture-A-intent", pinned = pinnedA)
        }
        try {
            assertFalse(queued.isCompleted)
            CookieManager.clearCookie(context)
            CookieManager.saveCookie(context, "MUSIC_U=fixture-B; __csrf=fixture-B-csrf")
            release.complete(Unit); holder.join()
            assertNull("queued write must be dropped after account switch", withTimeout(5000) { queued.await() })
            assertFalse("must not be sent under B", sentAsB)
        } finally { release.complete(Unit); holder.cancelAndJoin(); queued.cancelAndJoin() }
    }
    /**
     * 回归（原 S22 探针）：网易歌单写接口**必须**在本地就拒绝 QQ/B 站合成 id。
     *
     * 修复前：两类合成 id 原样发进 `/eapi/playlist/manipulate/tracks`。
     * 修复后：`addSongs` 在唯一写出口用 `SourceIds.isNeteaseId` 拦掉，一个请求都不发。
     */
    @Test fun ncmPlaylistMutationRejectsQqAndBiliSyntheticIds() = runBlocking {
        val ids = listOf(SourceIds.qqId(101, "fixture-mid"), SourceIds.biliId(202))
        var emitted: List<Long> = emptyList()
        intercept { request, body ->
            assertEquals("/eapi/playlist/manipulate/tracks", request.url.encodedPath)
            val rows = org.json.JSONArray(body.getString("trackIds"))
            emitted = (0 until rows.length()).map { rows.getLong(it) }
            """{"code":400,"message":"fixture rejects wrong source"}"""
        }
        assertNull(withTimeout(5000) { PlaylistEditApi.addSongs(999, ids) })
        assertEquals("cross-source ids must never reach the endpoint", emptyList<Long>(), emitted)
    }
}
