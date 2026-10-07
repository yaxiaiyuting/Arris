/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P1：登录态**进请求头**的行为判据 + 认证网络层的线程/取消形状守卫。
 */

package com.takahashirinta.ncrust.bili

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * v3.2.0 · P1 的**行为**判据：登录凭据真的进了 B 站的请求头，未登录时一个都不进。
 *
 * ## 为什么完全离线也成立
 *
 * 注入的 `BiliApi.clientForTest` 里的 interceptor 直接返回罐头响应、**不调用 `chain.proceed`**
 * ⇒ 没有 DNS、没有 socket、没有超时，结果对任何出口都一致（同 `BiliApiDispatchTest` 的手法）。
 * 唯一的观测点是**每个请求的 `Cookie` 头**与**被请求的 URL**。
 *
 * ## 它守的是什么
 *
 * P1 的整个价值都挂在「登录之后请求带上了 `SESSDATA`」这一件事上：不带，就是匿名 ——
 * 大会员档位（FLAC）与收藏夹都不会因为"登录成功"而出现。反过来，
 * **未登录时多带一个 Cookie 头**会破坏铁律 27（v3.1.0 的匿名行为逐字不变）。
 */
class BiliAuthRequestTest {

    private val seenCookies = mutableListOf<String?>()
    private val seenUrls = mutableListOf<String>()

    @Before
    fun setUp() {
        seenCookies.clear()
        seenUrls.clear()
        BiliAuthStore.setMirrorForTest(null)
        BiliApi.clearWbiKeysForTest()
        BiliApi.clearBuvidForTest()
    }

    @After
    fun tearDown() {
        BiliApi.clientForTest = null
        BiliApi.clearWbiKeysForTest()
        BiliApi.clearBuvidForTest()
        BiliAuthStore.setMirrorForTest(null)
    }

    // ---------------------------------------------------------------- 请求头注入

    @Test
    fun `未登录时一个 Cookie 头都不带（匿名行为与上一版逐字一致）`() = runBlocking {
        BiliApi.clientForTest = stub { """{"code":0,"data":{}}""" }
        BiliApi.audioInfo(39L)
        BiliApi.audioStream(39L, 2)
        assertTrue("必须有请求发生，否则这条用例是空转的", seenCookies.size >= 2)
        for (cookie in seenCookies) {
            assertNull("未登录时不许带任何 Cookie（实测：伪造 SESSDATA 也只会得到 -101）", cookie)
        }
    }

    @Test
    fun `登录后每一个 B 站请求都带上 SESSDATA`() = runBlocking {
        BiliApi.clientForTest = stub { """{"code":0,"data":{}}""" }
        BiliAuthStore.setMirrorForTest(
            BiliCredential(sessdata = "FAKESESS", biliJct = "FAKEJCT", dedeUserId = "1234567"),
        )
        BiliApi.audioInfo(39L)
        BiliApi.audioStream(39L, 2)
        val expected = "SESSDATA=FAKESESS; bili_jct=FAKEJCT; DedeUserID=1234567"
        assertTrue(seenCookies.isNotEmpty())
        for (cookie in seenCookies) assertEquals(expected, cookie)
    }

    @Test
    fun `wbi 密钥请求（nav）也带登录态 —— 拿密钥那条路不能是匿名的`() = runBlocking {
        BiliApi.clientForTest = stub { url ->
            if (url.contains("/x/web-interface/nav")) NAV_ANON else SEARCH_EMPTY
        }
        BiliAuthStore.setMirrorForTest(BiliCredential(sessdata = "FAKESESS"))
        BiliApi.searchVideos("初音未来", 5)
        assertEquals(2, seenUrls.size)
        assertTrue(seenUrls[0].contains("/nav"))
        assertTrue(seenUrls[1].contains("w_rid="))
        for (cookie in seenCookies) assertEquals("SESSDATA=FAKESESS", cookie)
    }

    @Test
    fun `buvid3 与登录凭据合并成一条 Cookie 头（指纹不覆盖身份）`() = runBlocking {
        BiliApi.clientForTest = stub { url ->
            if (url.contains("/x/frontend/finger/spi")) FINGER else """{"code":0}"""
        }
        BiliAuthStore.setMirrorForTest(BiliCredential(sessdata = "FAKESESS"))
        BiliApi.videoAudioStreams("BV1xx411c7mD", 1L)
        assertEquals("取指纹那次只有身份", "SESSDATA=FAKESESS", seenCookies[0])
        assertEquals(
            "取流那次是身份 + 指纹，且指纹在后（顺序固定 ⇒ 可断言）",
            "SESSDATA=FAKESESS; buvid3=FAKEBUV",
            seenCookies[1],
        )
    }

    // ---------------------------------------------------------------- 三个接口的解析穿透（罐头响应）

    @Test
    fun `qrGenerate 从罐头响应里拿到 url 与 key`() = runBlocking {
        BiliApi.clientForTest = stub { GENERATE_REAL }
        val code = BiliAuthApi.qrGenerate()
        assertEquals("4789f53cc92fcb2191a94ba57aed76c3", code?.qrcodeKey)
        assertEquals(
            "https://account.bilibili.com/h5/account-h5/auth/scan-web" +
                "?navhide=1&callback=close&qrcode_key=4789f53cc92fcb2191a94ba57aed76c3&from=",
            code?.url,
        )
        // 这一步**不带**任何 Cookie（还没有身份）。
        assertNull(seenCookies[0])
    }

    @Test
    fun `qrPoll 把 Set-Cookie 与 data_url 两个载体都读出来`() = runBlocking {
        BiliApi.clientForTest = stubWithSetCookie({ POLL_SUCCESS_DOC }, SET_COOKIE_DOC)
        val poll = BiliAuthApi.qrPoll("4789f53cc92fcb2191a94ba57aed76c3")
        assertTrue("实际 $poll", poll is BiliQrPoll.Success)
        assertEquals("FAKE%2CSESSDATA%2CVALUE", (poll as BiliQrPoll.Success).credential.sessdata)
        assertEquals("FAKEJCT", poll.credential.biliJct)
        assertEquals("轮询用的是 query 参数", true, seenUrls[0].contains("qrcode_key="))
    }

    @Test
    fun `qrPoll 遇到网络异常返回 NETWORK 失败而不是抛出去`() = runBlocking {
        BiliApi.clientForTest = throwing(IOException("timeout"))
        val poll = BiliAuthApi.qrPoll("k")
        assertEquals(BiliQrPoll.Failed.Reason.NETWORK, (poll as BiliQrPoll.Failed).reason)
        assertTrue(poll.detail.contains("IOException"))
    }

    @Test
    fun `qrPoll 的空 key 不发请求`() = runBlocking {
        BiliApi.clientForTest = stub { """{"code":0,"data":{"code":86101}}""" }
        val poll = BiliAuthApi.qrPoll("  ")
        assertTrue(poll is BiliQrPoll.Failed)
        assertTrue("空 key 不该白打一次请求", seenCookies.isEmpty())
    }

    @Test
    fun `CancellationException 从三个接口原样穿透（不吞成失败）`() = runBlocking {
        BiliApi.clientForTest = throwing(CancellationException("停止"))
        for (call in listOf<suspend () -> Any?>(
            { BiliAuthApi.qrGenerate() },
            { BiliAuthApi.qrPoll("k") },
            { BiliAuthApi.navWithCookie("SESSDATA=S") },
        )) {
            try {
                call()
                fail("取消必须原样抛出")
            } catch (e: CancellationException) {
                assertEquals("停止", e.message)
            }
        }
    }

    @Test
    fun `navWithCookie 把给定的 Cookie 头原样发出去`() = runBlocking {
        BiliApi.clientForTest = stub { NAV_ANON }
        val result = BiliAuthApi.navWithCookie("SESSDATA=FAKESESS; bili_jct=FAKEJCT")
        assertEquals(BiliNavResult.NotLoggedIn, result)
        assertEquals("SESSDATA=FAKESESS; bili_jct=FAKEJCT", seenCookies[0])
        assertTrue(seenUrls[0].endsWith("/x/web-interface/nav"))
    }

    @Test
    fun `navWithCookie 空 cookie 不发请求 —— 那只会拿到一个恒定的 -101`() = runBlocking {
        BiliApi.clientForTest = stub { NAV_ANON }
        assertEquals(BiliNavResult.NotLoggedIn, BiliAuthApi.navWithCookie(""))
        assertTrue(seenCookies.isEmpty())
    }

    // ---------------------------------------------------------------- 源码形状守卫（P0-C 的纪律）

    @Test
    fun `BiliAuthApi 的三个网络方法都是 suspend 且内部换到 Dispatchers_IO`() {
        val src = source("bili/BiliAuthApi.kt")
        for (name in listOf("qrGenerate", "qrPoll", "navWithCookie")) {
            // 从 `object BiliAuthApi` 之后找：接口里的同名声明没有实现体。
            val body = memberBody(src, name, after = "object BiliAuthApi")
            assertTrue(
                "$name 必须是 suspend（P0-C：同步 execute 落在主线程上会被 StrictMode 直接杀）",
                Regex("""(?:override\s+)?suspend\s+fun\s+$name\s*\(""").containsMatchIn(body),
            )
            assertTrue(
                "$name 必须在函数体内 withContext(Dispatchers.IO)",
                body.contains("withContext(Dispatchers.IO)"),
            )
        }
    }

    @Test
    fun `认证网络层绝不自建 client 也绝不复用ncm那套`() {
        // 只看**代码**：类文档里当然会提到 RetrofitClient / OkHttpClient（那是在解释为什么不用它们），
        // 把注释算进来只会得到一条自己打自己的守卫。
        val code = codeOnly(source("bili/BiliAuthApi.kt"))
        assertFalse(
            "代码里不许出现 RetrofitClient：它的拦截器无条件注入 ncm 的 Referer/UA/Cookie，" +
                "而 v3.1.0 实测「拿 ncm Referer 请求 B 站 ⇒ 403」",
            code.contains("RetrofitClient"),
        )
        assertFalse("代码里不许新建第二个 OkHttpClient（必须复用 BiliApi 那个独立 client）", code.contains("OkHttpClient"))
        assertTrue(
            "必须走 BiliApi 的独立 client + UA/Referer 约定",
            code.contains("BiliApi.getRaw("),
        )
    }

    @Test
    fun `每一个 catch CancellationException 后面都必须 throw e`() {
        for (rel in listOf("bili/BiliAuthApi.kt", "bili/BiliQrLogin.kt")) {
            val lines = source(rel).lines()
            lines.forEachIndexed { i, line ->
                if (!line.contains("catch (e: CancellationException)")) return@forEachIndexed
                val tail = lines.subList(i, minOf(i + 3, lines.size)).joinToString("\n")
                assertTrue(
                    "$rel:${i + 1} 吞掉了取消：\n$tail",
                    tail.contains("throw e"),
                )
            }
        }
    }

    // ---------------------------------------------------------------- 罐头响应小工具

    private companion object {
        val NAV_ANON =
            """{"code":-101,"message":"账号未登录","data":{"isLogin":false,"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png","sub_url":"https://i0.hdslb.com/bfs/wbi/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png"}}}"""
        val SEARCH_EMPTY = """{"code":0,"data":{"result":[]}}"""
        val FINGER = """{"code":0,"data":{"b_3":"FAKEBUV","b_4":"FAKEBUV4"}}"""
        val GENERATE_REAL =
            """{"code":0,"message":"OK","ttl":1,"data":{"url":"https://account.bilibili.com/h5/account-h5/auth/scan-web?navhide=1\u0026callback=close\u0026qrcode_key=4789f53cc92fcb2191a94ba57aed76c3\u0026from=","qrcode_key":"4789f53cc92fcb2191a94ba57aed76c3"}}"""
        val POLL_SUCCESS_DOC =
            """{"code":0,"message":"0","ttl":1,"data":{"url":"https://passport.biligame.com/crossDomain?DedeUserID=1234567\u0026bili_jct=FAKEJCT\u0026gourl=x","refresh_token":"FAKEREFRESH","timestamp":1662363009601,"code":0,"message":""}}"""
        val SET_COOKIE_DOC = listOf(
            "SESSDATA=FAKE%2CSESSDATA%2CVALUE; Path=/; Domain=bilibili.com; HttpOnly; Secure",
            "bili_jct=FAKEJCT; Path=/; Domain=bilibili.com",
            "DedeUserID=1234567; Path=/; Domain=bilibili.com",
        )
    }

    /** 罐头传输层：记录每个请求的 `Cookie` 头与 URL，然后直接给出 `handler(url)` 的响应。 */
    private fun stub(handler: (String) -> String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                seenCookies += request.header("Cookie")
                seenUrls += request.url.toString()
                canned(request, handler(request.url.toString()))
            }
            .build()

    private fun stubWithSetCookie(handler: (String) -> String, setCookies: List<String>): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                seenCookies += request.header("Cookie")
                seenUrls += request.url.toString()
                canned(request, handler(request.url.toString()), setCookies)
            }
            .build()

    private fun throwing(error: Throwable): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response = throw error
            })
            .build()

    private fun canned(
        request: okhttp3.Request,
        body: String,
        setCookies: List<String> = emptyList(),
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
        for (c in setCookies) builder.addHeader("Set-Cookie", c)
        return builder.build()
    }

    // ---------------------------------------------------------------- 源码扫描小工具

    /** 读源码文本（**找不到文件直接失败**：静默跳过 = 假防线）。 */
    private fun source(rel: String): String {
        val f = File(appSourceRoot(), rel)
        assertTrue("源码文件不见了：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    // 去掉注释行（行首 `//`、KDoc 的 `*` 续行、块注释起始行），只留可执行代码 —— 守卫只该守代码。
    private fun codeOnly(src: String): String =
        src.lines()
            .filterNot {
                val t = it.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")

    /**
     * 取名为 [name] 的成员函数体。
     *
     * [after] 用来跳过**接口里的同名声明**（`BiliAuthEndpoints` 先声明、`object BiliAuthApi`
     * 再实现；从第一个匹配开始找会拿到没有 `withContext` 的那一条，守卫就变成了假防线）。
     */
    private fun memberBody(src: String, name: String, after: String? = null): String {
        val offset = if (after == null) 0 else src.indexOf(after).also {
            assertTrue("源码里找不到标记 $after —— 守卫必须跟着结构改名，不许静默跳过", it >= 0)
        }
        val lines = src.substring(offset).lines()
        val head = Regex(
            """^ {4}(?:(?:private|internal|public|protected|override)\s+)*(?:suspend\s+)?fun\s+""" +
                Regex.escape(name) + """\s*\(""",
        )
        val start = lines.indexOfFirst { head.containsMatchIn(it) }
        assertTrue("找不到成员 $name —— 守卫必须跟着它改名，不许静默跳过", start >= 0)
        val boundary = Regex(
            """^ {4}(?:/\*\*|@|(?:private|internal|public|protected|override)\s|fun\s|const\s|val\s|var\s|data\s|object\s|enum\s|class\s)""",
        )
        var end = start + 1
        while (end < lines.size && !boundary.containsMatchIn(lines[end])) end++
        return lines.subList(start, end).joinToString("\n")
    }

    /** `app/src/main/java/com/takahashirinta/ncrust`；找不到直接失败（静默跳过 = 假防线）。 */
    private fun appSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java/com/takahashirinta/ncrust")
            if (candidate.isDirectory) return candidate
            if (dir.name == "app") {
                val local = File(dir, "src/main/java/com/takahashirinta/ncrust")
                if (local.isDirectory) return local
            }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 app/src/main/java/com/takahashirinta/ncrust（user.dir=${System.getProperty("user.dir")}）")
    }
}
