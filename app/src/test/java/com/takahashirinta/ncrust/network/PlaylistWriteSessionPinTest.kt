package com.takahashirinta.ncrust.network

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 歌单写操作的**会话钉住**回归（H02）。
 *
 * 旧实现只有「创建 / 加曲」两条路径接受调用方钉住的快照，删除 / 批量删除 / 改名 / 改描述 /
 * 改隐私 / 移除曲目都在 `PlaylistWriteGate` 里**等到轮到自己时才读当前 Cookie** ——
 * 排队期间换号，A 的编辑就会带 B 的凭据发出去。
 *
 * 修复后公共入口在**进入门控之前**取快照（调用方不传也一样），门控释放后按登录代次复核。
 * 这里用真实的 `PlaylistWriteGate` 与真实写入口，只拦截 HTTP，不 mock 业务层。
 */
class PlaylistWriteSessionPinTest {

    private class MemoryContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, MemoryPrefs>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            synchronized(stores) { stores.getOrPut(name) { MemoryPrefs() } }
    }

    private lateinit var ctx: MemoryContext
    private val restorers = mutableListOf<() -> Unit>()
    private val cookies = Collections.synchronizedList(mutableListOf<String>())

    private fun field(owner: Any, name: String) =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Before
    fun setup() {
        ctx = MemoryContext()
        cookies.clear()
        field(CookieManager, "app").set(CookieManager, null)
        CookieManager.init(ctx)
        CookieManager.saveCookie(ctx, "MUSIC_U=fixture-A; __csrf=fixture-csrf-A")
        intercept { request ->
            cookies += request.header("Cookie").orEmpty()
            """{"code":200}"""
        }
    }

    @After
    fun cleanup() {
        restorers.asReversed().forEach { it() }
        CookieManager.clearCookie(ctx)
        field(CookieManager, "app").set(CookieManager, null)
    }

    private fun intercept(answer: (Request) -> String) {
        val delegate = field(RetrofitClient, "plainClient\$delegate").get(RetrofitClient) as Lazy<*>
        val original = delegate.value
        val valueField = delegate.javaClass.getDeclaredField("_value").apply { isAccessible = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("write fixture").body(answer(request).toResponseBody()).build()
        }.build()
        valueField.set(delegate, client)
        restorers += {
            valueField.set(delegate, original)
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `queued rename is not sent with the next accounts credential`() = runBlocking {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            PlaylistWriteGate.run {
                held.complete(Unit)
                release.await()
            }
        }
        held.await()

        // UNDISPATCHED：真正进入 API 并把快照钉在「用户点下这一下」的时刻，然后停在门控上。
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            PlaylistEditApi.renamePlaylist(999, "fixture-name")
        }
        assertFalse("写操作应当停在门控上", queued.isCompleted)

        CookieManager.clearCookie(ctx)
        CookieManager.saveCookie(ctx, "MUSIC_U=fixture-B; __csrf=fixture-csrf-B")
        release.complete(Unit)
        holder.join()

        assertFalse("换号后这条写操作必须作废", withTimeout(10_000) { queued.await() })
        assertTrue("A 的改名被 B 的凭据重发了：$cookies", cookies.isEmpty())
    }

    @Test
    fun `queued rename still lands after same account renewal`() = runBlocking {
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            PlaylistWriteGate.run {
                held.complete(Unit)
                release.await()
            }
        }
        held.await()

        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            PlaylistEditApi.renamePlaylist(999, "fixture-name")
        }
        assertFalse(queued.isCompleted)

        // 同账号续期：只换票（revision 变、登录代次不变）—— 这不是换号，写操作照发。
        val before = CookieManager.snapshot()
        assertTrue(
            CookieManager.commitRefresh(
                ctx, before, "MUSIC_U=fixture-A2; __csrf=fixture-csrf-A2", System.currentTimeMillis(),
            )
        )
        release.complete(Unit)
        holder.join()

        assertTrue("同账号续期把排队的写操作丢掉了", withTimeout(10_000) { queued.await() })
        assertTrue("续期后仍属于 A 账号：${cookies}", cookies.single().contains("fixture-A"))
    }
}
