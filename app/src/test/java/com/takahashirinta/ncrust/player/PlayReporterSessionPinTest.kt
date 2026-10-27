package com.takahashirinta.ncrust.player

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import java.util.Collections
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 播放上报的**会话钉住**回归（H03）。
 *
 * 旧实现只在调用线程用 cookie 判断「有没有登录」，真正发包的 `ncrust-scrobble` 线程里
 * `eapiPostOsx` 又读一次当前凭据 —— 排队期间换号，A 的收听记录就发给了 B。
 * 修复后 `send` 在产生上报时捕获快照，线程入口按登录代次复核，凭据也来自这份快照。
 *
 * 断言对象是**发出去的请求**：跨账号必须一条都不发；同账号续期必须照发且仍是同一账号。
 */
class PlayReporterSessionPinTest {

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
        val delegate = field(RetrofitClient, "plainClient\$delegate").get(RetrofitClient) as Lazy<*>
        val original = delegate.value
        val valueField = delegate.javaClass.getDeclaredField("_value").apply { isAccessible = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            cookies += request.header("Cookie").orEmpty()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("scrobble fixture").body("""{"code":200}""".toResponseBody()).build()
        }.build()
        valueField.set(delegate, client)
        restorers += {
            valueField.set(delegate, original)
            client.dispatcher.executorService.shutdown()
        }
    }

    @After
    fun cleanup() {
        restorers.asReversed().forEach { it() }
        CookieManager.clearCookie(ctx)
        field(CookieManager, "app").set(CookieManager, null)
    }

    private fun logs() = PlayReporter.playLogs(101, playedMs = 45_000)

    @Test
    fun `report produced for the previous account is dropped`() {
        // A 产生这条上报（真实生产路径里 send 就是在调用线程取快照）。
        val pinned = CookieManager.snapshot()
        CookieManager.clearCookie(ctx)
        CookieManager.saveCookie(ctx, "MUSIC_U=fixture-B; __csrf=fixture-csrf-B")

        assertFalse(PlayReporter.dispatchReport(pinned, logs()))
        assertTrue("A 的收听记录被发给了 B：$cookies", cookies.isEmpty())
    }

    @Test
    fun `report still lands after same account renewal`() {
        val pinned = CookieManager.snapshot()
        // 同账号续期：只换票（revision 变、登录代次不变），不是换号。
        assertTrue(
            CookieManager.commitRefresh(
                ctx, pinned, "MUSIC_U=fixture-A2; __csrf=fixture-csrf-A2", System.currentTimeMillis(),
            )
        )
        assertEquals("续期不该推进登录代次", pinned.loginGeneration, CookieManager.snapshot().loginGeneration)

        assertTrue(PlayReporter.dispatchReport(pinned, logs()))
        assertEquals(1, cookies.size)
        val cookie = cookies.single()
        assertTrue("续期后仍是 A 账号，必须用 A 的票：$cookie", cookie.contains("fixture-A"))
        assertFalse("绝不能带 B 的凭据", cookie.contains("fixture-B"))
    }

}
