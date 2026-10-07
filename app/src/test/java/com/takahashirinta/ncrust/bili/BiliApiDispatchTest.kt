/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P0-C：B 站网络调用的**线程口径**回归单测。
 */

package com.takahashirinta.ncrust.bili

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.SourceRouter
import com.takahashirinta.ncrust.source.musicSource
import com.takahashirinta.ncrust.source.trackKey
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v3.2.0 · P0-C 的**行为**判据：B 站那条腿的阻塞传输绝不能在调用线程上跑。
 *
 * ## 它测的是什么（以及为什么不是「源码扫描」）
 *
 * P0-C 的根因是「`BiliApi` 的同步 `execute()` 被 `Main.immediate` 作用域调用」：
 * 在真机上主线程的 socket 操作会被 `StrictMode.enableDeathOnNetwork()` 拒绝
 * （`NetworkOnMainThreadException`），异常又被 `runCatching` 吞成「0 条」——
 * 用户看到的是「B 站搜不到歌」且没有任何错误。
 *
 * 判据有两种写法：
 * 1. **源码扫描**（`BiliApiIoContractTest`）：断言对外方法是 `suspend` + 内部 `withContext(IO)`；
 * 2. **行为验证**（本文件）：注入一个**不发一个字节**的假传输层，记录它被调用时所在的线程名，
 *    然后从一条**单线程的「假主线程」**上发起调用 —— 只要传输层没有出现在那条线程上，
 *    「阻塞的代码不在调用线程上跑」就被真正证明了。
 *
 * ② 比 ① 强，所以两个都留。本文件的**前提断言**（`callerThread == 假主线程名`、
 * `requestCount > 0`）保证它不会变成一条空转的用例。
 *
 * ## 为什么完全离线也成立
 *
 * `BiliApi.clientForTest` 注入的 client 的 interceptor 直接返回罐头响应、**不调用
 * `chain.proceed`** ⇒ 没有 DNS、没有 socket、没有超时，结果对任何出口都一致。
 * 罐头响应里的 bvid/aid/结构都照真实响应抄（含一条 `type:"ketang"` 的干扰项）。
 */
class BiliApiDispatchTest {

    private companion object {
        /** 「假主线程」的名字。真实设备上这里是 `main`，但主线程不可控，所以用单线程池代替。 */
        const val FAKE_MAIN = "ncrust-p0c-fake-main"

        /** 假传输层被调用的线程名（**唯一**的观测点）。 */
        val seenThreads = Collections.synchronizedList(ArrayList<String>())
        val requestCount = AtomicInteger(0)

        /** 真实 `nav` 响应的形状（`wbi_img` 两个 32 位十六进制文件名，`mixinKey` 要求长度 ≥ 64）。 */
        val NAV = """{"code":-101,"message":"账号未登录","data":{"wbi_img":{""" +
            """"img_url":"https://i0.hdslb.com/bfs/wbi/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png",""" +
            """"sub_url":"https://i0.hdslb.com/bfs/wbi/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.png"}}}"""

        /**
         * 真实搜索响应的形状 + 一条非 video 的干扰项（`cleanTitle` 会去掉 `<em>` 高亮）。
         *
         * ⚠️ `<em class=keyword>` 故意**不带引号**：Kotlin 的原始字符串（`"""…"""`）
         * 不处理 `\"` 转义，写引号会直接产出非法 JSON ⇒ 解析静默返回 0 条、
         * 用例变成「测了个空」（这条在本版真的踩过一次）。
         */
        val SEARCH = """{"code":0,"message":"0","data":{"result":[""" +
            """{"type":"video","bvid":"BV1xx411c7mD","aid":117336711367421,"title":"<em class=keyword>周杰伦</em> - 晴天","author":"UP1","pic":"","duration":"269"},""" +
            """{"type":"video","bvid":"BV1yy411c7mE","aid":114260223006676,"title":"第二首","author":"UP2","pic":"","duration":75},""" +
            """{"type":"ketang","bvid":"","aid":0,"title":"课堂（必须被丢掉）"}]}}"""

        val LYRIC = """{"code":0,"data":"[00:01.00]第一行\n[00:02.00]第二行"}"""
        val AUDIO_INFO = """{"code":0,"data":{"id":2478206,"title":"成都（Cover赵雷）","author":"Roro_Zhang","duration":258000,"cover":"","lyric":""}}"""
    }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, FAKE_MAIN) }
    private val fakeMain = executor.asCoroutineDispatcher()

    @Before
    fun setUp() {
        seenThreads.clear()
        requestCount.set(0)
        BiliApi.clearWbiKeysForTest()
        BiliApi.clearBuvidForTest()
        BiliPrefs.setEnabledForTest(true)
        // 一个什么网都不发的 client：按 URL 发罐头响应。
        BiliApi.clientForTest = clientWith { url ->
            when {
                url.contains("/x/web-interface/nav") -> NAV
                url.contains("/wbi/search/type") -> SEARCH
                url.contains("/web/song/lyric") -> LYRIC
                url.contains("/web/song/info") -> AUDIO_INFO
                else -> """{"code":0}"""
            }
        }
    }

    @After
    fun tearDown() {
        BiliApi.clientForTest = null
        BiliApi.clearWbiKeysForTest()
        BiliApi.clearBuvidForTest()
        BiliPrefs.setEnabledForTest(false)
        executor.shutdownNow()
    }

    // ------------------------------------------------------------------ 判据 1：搜索

    @Test
    fun `搜索的阻塞传输不在调用线程上跑 —— P0-C 的回归判据`() {
        var callerThread = "?"
        val songs = runBlocking(fakeMain) {
            callerThread = Thread.currentThread().name
            SourceRouter.searchSongs(MusicSource.BILIBILI, "周杰伦", 5)
        }

        // 前提：调用确实发生在「假主线程」上（否则这条用例什么都没测到）。
        assertTrue(
            "用例前提：调用必须发生在假主线程上（实际 $callerThread）",
            callerThread.startsWith(FAKE_MAIN),
        )
        assertEquals("用例前提：必须真的发出了请求（nav + search）", 2, requestCount.get())
        assertFalse(
            "传输层绝不能跑在调用线程上（P0-C 的根因）：seen=$seenThreads",
            seenThreads.any { it.startsWith(FAKE_MAIN) },
        )
        assertTrue(
            "传输层应当跑在 Dispatchers.IO 的线程池上：seen=$seenThreads",
            seenThreads.all { it.startsWith("DefaultDispatcher") },
        )

        // 顺带把「解析 + 映射」也钉住：这是真实响应形状，2 条 video + 1 条被丢掉的 ketang。
        assertEquals("真实响应必须解析出两条（映射层本身没问题）", 2, songs.size)
        assertTrue("音源必须是 bilibili", songs.all { it.musicSource == MusicSource.BILIBILI })
        assertTrue("id 必须带 B 站标志位（位 61）", songs.all { SourceIds.isBiliId(it.id) })
        assertEquals(
            listOf(
                "bilibili:${SourceIds.biliId(117336711367421L)}",
                "bilibili:${SourceIds.biliId(114260223006676L)}",
            ),
            songs.map { it.trackKey },
        )
        assertEquals("LazyColumn 的 key（item.id）必须唯一", 2, songs.map { it.id }.distinct().size)
        assertEquals("标题里的 <em> 高亮必须被清掉", "周杰伦 - 晴天", songs[0].name)
    }

    @Test
    fun `音频区详情的阻塞传输不在调用线程上跑`() {
        val songs = runBlocking(fakeMain) {
            // 关键词形状是 auid（≥3 位数字）⇒ 走 `BiliApi.audioInfo`，不发搜索请求。
            SourceRouter.searchSongs(MusicSource.BILIBILI, "au2478206", 1)
        }
        assertEquals("用例前提：音频区那条腿只发一次请求", 1, requestCount.get())
        assertFalse(
            "传输层绝不能跑在调用线程上：seen=$seenThreads",
            seenThreads.any { it.startsWith(FAKE_MAIN) },
        )
        assertEquals(1, songs.size)
        assertEquals(SourceIds.biliId(2478206L), songs[0].id)
        assertEquals("成都（Cover赵雷）", songs[0].name)
    }

    @Test
    fun `歌词的阻塞传输不在调用线程上跑 —— 播放路径是同一个 P0`() {
        val song = biliAudioSong()
        var lyric: String? = null
        runBlocking(fakeMain) { lyric = BiliSourceProvider.fetchLyric(song) }
        assertTrue("用例前提：必须真的发了请求", requestCount.get() >= 1)
        assertFalse(
            "传输层绝不能跑在调用线程上：seen=$seenThreads",
            seenThreads.any { it.startsWith(FAKE_MAIN) },
        )
        assertNotNull(lyric)
        assertTrue("歌词正文必须是 LRC（不是 URL）", lyric!!.contains("[00:01.00]第一行"))
    }

    // ---------------------------------------------- 判据 2：失败/取消/开关的既有契约

    @Test
    fun `开关关闭时零请求 —— 调度修复不得动摇铁律 24`() {
        BiliPrefs.setEnabledForTest(false)
        val songs = runBlocking(fakeMain) {
            SourceRouter.searchSongs(MusicSource.BILIBILI, "周杰伦", 5)
        }
        assertTrue(songs.isEmpty())
        assertEquals("关掉开关时一个请求都不发", 0, requestCount.get())
        assertTrue("关掉开关时连线程都不该用", seenThreads.isEmpty())
    }

    @Test
    fun `传输异常被隔离成空列表 —— 换了线程之后契约依然是绝不抛`() {
        BiliApi.clientForTest = clientThrowing(IOException("boom"))
        val songs = runBlocking(fakeMain) {
            SourceRouter.searchSongs(MusicSource.BILIBILI, "周杰伦", 5)
        }
        assertTrue("B 站挂掉只能是「这一轮没有结果」", songs.isEmpty())
        assertFalse(
            "异常路径同样不该跑在调用线程上：seen=$seenThreads",
            seenThreads.any { it.startsWith(FAKE_MAIN) },
        )
    }

    @Test
    fun `取消是控制流不是失败 —— CancellationException 必须原样抛出`() {
        BiliApi.clientForTest = clientThrowing(CancellationException("fake cancel"))
        var caught: Throwable? = null
        // 在协程**内部**捕获：这样用例断言的是「异常有没有被吞」，而不是 runBlocking 的收尾语义。
        runBlocking(fakeMain) {
            try {
                SourceRouter.searchSongs(MusicSource.BILIBILI, "周杰伦", 5)
            } catch (e: Throwable) {
                caught = e
            }
        }
        assertNotNull("取消被吞成了空列表（结构化并发被破坏，且会打假日志）", caught)
        assertTrue("必须是取消而不是别的异常：$caught", caught is CancellationException)
    }

    @Test
    fun `预算用完时返回 null 而不是空列表 —— TIMEOUT 不许被写成 DONE 加 0`() {
        // 假传输层故意慢 400ms：预算 60ms 到点时，`withTimeoutOrNull` 必须回 null。
        // null 在 SearchViewModel 里被映射成 TIMEOUT（「搜索超时」+ 重试入口），
        // 而空列表会被映射成 DONE + 0（「B站 0 首」）—— 后者对用户是一句假话（v2.5.5 的纪律）。
        BiliApi.clientForTest = clientWith({ url ->
            if (url.contains("/wbi/search/type")) Thread.sleep(400) else Unit
            if (url.contains("/x/web-interface/nav")) NAV else SEARCH
        })
        val outcome = runBlocking(fakeMain) {
            withTimeoutOrNull(60L) { SourceRouter.searchSongs(MusicSource.BILIBILI, "周杰伦", 5) }
        }
        assertNull("超时必须回 null（TIMEOUT），不能回空列表（DONE+0）：$outcome", outcome)
    }

    // ------------------------------------------------------------------ 假传输层

    /** 只在**非调用线程**上被调用的假传输层；每个请求都记录线程名与次数。 */
    private fun clientWith(handler: (String) -> String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                seenThreads += Thread.currentThread().name
                requestCount.incrementAndGet()
                canned(request, handler(request.url.toString()))
            }
            .build()

    private fun clientThrowing(error: Throwable): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    seenThreads += Thread.currentThread().name
                    requestCount.incrementAndGet()
                    throw error
                }
            })
            .build()

    private fun canned(request: okhttp3.Request, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    /** 一条 B 站**音频区**曲目（`sourceId = au:<auid>`）—— 取词那条腿只认它。 */
    private fun biliAudioSong(): SongItem = SongItem(
        id = SourceIds.biliId(2478206L),
        name = "成都（Cover赵雷）",
        artists = emptyList(),
        album = null,
        duration = 258000L,
        source = MusicSource.BILIBILI.key,
        sourceId = "au:2478206",
        mediaId = null,
    )
}
