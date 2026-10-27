package com.takahashirinta.ncrust.library

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.XeapiState
import com.takahashirinta.ncrust.source.NeteaseSourceProvider
import com.takahashirinta.ncrust.source.SourceRouter
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 收藏写入的**归属与串行**回归（R1 / R2 / H01）。
 *
 * 三条断言分别对应三份审查报告里的缺陷，修复前全部为红：
 *  - R1：旧账号的 worker 会把新账号刚写下的收藏意图删掉（用真实 `LibraryManager` 入口复现）；
 *  - R2：刷新期间发生的新编辑被一份更老的云端快照撤销（内存 + 落盘一起丢）；
 *  - H01：专辑 sub/unsub 仍是独立 launch，同一张专辑的两条写入会并发、可以反序落地。
 *
 * 用真实业务入口 + 拦截 HTTP 夹具，不 mock 内部状态：
 * 断言的对象是「发出去的请求」与「内存/磁盘上的收藏表」。
 */
class LibrarySessionOwnershipTest {

    private class MemoryContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, MemoryPrefs>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            synchronized(stores) { stores.getOrPut(name) { MemoryPrefs() } }
    }

    private lateinit var ctx: MemoryContext
    private val restorers = mutableListOf<() -> Unit>()

    private fun field(owner: Any, name: String) =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun resetLibrary() {
        runBlocking {
            (field(LibraryManager, "flushJob").get(LibraryManager) as? Job)?.let { it.cancel(); it.join() }
        }
        // 真源缓存必须置 null（= 没加载过）才会重新读盘；只清空集合会留下上一个用例的表。
        listOf(
            "cachedEntries", "cachedAlbums", "cachedLikedIds", "pendingAppContext", "flushJob",
        ).forEach { field(LibraryManager, it).set(LibraryManager, null) }
        listOf("latestLikeIntent", "latestAlbumIntent", "likeMutexes", "albumMutexes").forEach {
            (field(LibraryManager, it).get(LibraryManager) as MutableMap<*, *>).clear()
        }
        field(LibraryManager, "likedIdCursor").setInt(LibraryManager, 0)
    }

    /** 立即落盘（跳过 300ms 去抖），断言磁盘真相用。 */
    private fun writeSnapshot() {
        LibraryManager.javaClass.getDeclaredMethod("flushToDisk").apply { isAccessible = true }
            .invoke(LibraryManager)
    }

    private suspend fun settleWrites() {
        val scope = field(LibraryManager, "ioScope").get(LibraryManager) as CoroutineScope
        val supervisor = scope.coroutineContext[Job]!!
        withTimeout(10_000) {
            // 反复 join：worker 在 join 期间可能再派生一个子任务（落盘去抖）。
            while (true) {
                val children = supervisor.children.toList()
                if (children.isEmpty()) break
                children.joinAll()
            }
        }
    }

    @Before
    fun setup() {
        ctx = MemoryContext()
        resetLibrary()
        field(CookieManager, "app").set(CookieManager, null)
        CookieManager.init(ctx)
        CookieManager.saveCookie(ctx, "MUSIC_U=fixture-A; __csrf=fixture-csrf-A")
        // xeapi 通道需要真机上下文/公钥，JVM 里让它确定性地在建链之前失败 ——
        // 否则「这次写入走了哪个通道」会取决于同一 JVM 里别的测试有没有初始化过它。
        listOf("appContext", "cachedKeyState").forEach {
            field(XeapiState, it).set(XeapiState, null)
        }
        SourceRouter.register(NeteaseSourceProvider)
    }

    @After
    fun cleanup() {
        // 用例早退时仍可能有 worker 挂在被占住的门闩上：收尾只尽力而为，别盖住真正的失败。
        runBlocking { runCatching { withTimeout(2_000) { settleWrites() } } }
        restorers.asReversed().forEach { it() }
        CookieManager.clearCookie(ctx)
        field(CookieManager, "app").set(CookieManager, null)
        resetLibrary()
    }

    private fun intercept(owner: Any, lazyName: String, answer: (Request) -> String) {
        val delegate = field(owner, lazyName + "\$delegate").get(owner) as Lazy<*>
        val original = delegate.value
        val valueField = delegate.javaClass.getDeclaredField("_value").apply { isAccessible = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("ownership fixture").body(answer(request).toResponseBody()).build()
        }.build()
        valueField.set(delegate, client)
        restorers += {
            valueField.set(delegate, original)
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun song(id: Long) =
        SongItem(id = id, name = "fixture-$id", artists = null, album = null, duration = 200_000)

    private fun album(id: Long) = AlbumInfo(id, "fixture-$id", "", "fixture", 1)

    /** 易盾 token 的固定响应：让 xeapi 通道能走到「发写入」那一步。 */
    private val antiCheatToken = "null([200,1,\"fixture-token\"])"

    // ==================== R1：意图归属 ====================

    @Test
    fun `new accounts intent survives an older accounts stale worker`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bWrites = Collections.synchronizedList(mutableListOf<String>())
        // 挂住 A 账号旧 worker 的第一跳（易盾 token 请求在取链之前）。
        intercept(RetrofitClient, "antiCheatClient") {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            antiCheatToken
        }
        // 真正的收藏写入落在这里：按 Cookie 区分是哪个账号发的。
        intercept(RetrofitClient, "plainClient") { request ->
            request.header("Cookie").orEmpty().let { if (it.contains("fixture-B")) bWrites += it }
            """{"code":200}"""
        }

        assertEquals(SavedSongOutcome.ADDED, LibraryManager.saveSong(ctx, song(101)))
        assertTrue("A 账号的收藏请求没有进入在飞状态", entered.await(4, TimeUnit.SECONDS))

        // 切号：B 取消同一首歌 —— 这条意图必须真的发出去。
        CookieManager.clearCookie(ctx)
        CookieManager.saveCookie(ctx, "MUSIC_U=fixture-B; __csrf=fixture-csrf-B")
        assertTrue(LibraryManager.removeSong(ctx, 101))
        release.countDown()
        settleWrites()

        assertEquals("B 的最新取消意图被 A 的旧 worker 删掉了", 1, bWrites.size)
        assertTrue(
            "旧 worker 只能删自己领取的那一条，不能清空 pending 表",
            (field(LibraryManager, "latestLikeIntent").get(LibraryManager) as Map<*, *>).isEmpty()
        )
        assertFalse(LibraryManager.isSongSaved(ctx, 101))
    }

    @Test
    fun `same account renewal keeps the queued intent`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = Collections.synchronizedList(mutableListOf<String>())
        intercept(RetrofitClient, "antiCheatClient") {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            antiCheatToken
        }
        intercept(RetrofitClient, "plainClient") { request ->
            writes += request.header("Cookie").orEmpty()
            """{"code":200}"""
        }

        assertEquals(SavedSongOutcome.ADDED, LibraryManager.saveSong(ctx, song(202)))
        assertTrue(entered.await(4, TimeUnit.SECONDS))

        // 同账号续期：只换票（revision 变、登录代次不变），不是换号。
        val before = CookieManager.snapshot()
        assertTrue(
            CookieManager.commitRefresh(
                ctx, before, "MUSIC_U=fixture-A2; __csrf=fixture-csrf-A2", System.currentTimeMillis(),
            )
        )
        assertEquals("续期不该推进登录代次", before.loginGeneration, CookieManager.snapshot().loginGeneration)

        release.countDown()
        settleWrites()

        assertEquals("同账号续期把排队的收藏意图丢掉了一次", 1, writes.size)
        assertTrue("续期后仍是 A 账号，不能用别人的凭据", writes.single().contains("fixture-A"))
        assertTrue((field(LibraryManager, "latestLikeIntent").get(LibraryManager) as Map<*, *>).isEmpty())
    }

    @Test
    fun `last click wins when the intent flips back to like`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = Collections.synchronizedList(mutableListOf<String>())
        intercept(RetrofitClient, "antiCheatClient") {
            if (entered.count > 0) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            antiCheatToken
        }
        intercept(RetrofitClient, "plainClient") { request ->
            writes += request.header("Cookie").orEmpty()
            """{"code":200}"""
        }

        assertEquals(SavedSongOutcome.ADDED, LibraryManager.saveSong(ctx, song(303)))
        assertTrue(entered.await(4, TimeUnit.SECONDS))
        // true → false → true：中间的 false 被最后一次点击覆盖，根本不该发出去。
        assertTrue(LibraryManager.removeSong(ctx, 303))
        assertEquals(SavedSongOutcome.ADDED, LibraryManager.saveSong(ctx, song(303)))
        release.countDown()
        settleWrites()

        assertEquals("中间那次 false 不该发；最终状态由最后一次 true 决定", 2, writes.size)
        assertTrue(LibraryManager.isSongSaved(ctx, 303))
        assertTrue((field(LibraryManager, "latestLikeIntent").get(LibraryManager) as Map<*, *>).isEmpty())
    }

    // ==================== H01：专辑写入串行 ====================

    @Test
    fun `album subscription is serialized so unsub cannot overtake sub`() = runBlocking {
        val subEntered = CountDownLatch(1)
        val unsubEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actions = Collections.synchronizedList(mutableListOf<String>())
        intercept(RetrofitClient, "plainClient") { request ->
            when {
                request.url.encodedPath.endsWith("/album/sub") -> {
                    actions += "sub"
                    subEntered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    """{"code":200}"""
                }
                request.url.encodedPath.endsWith("/album/unsub") -> {
                    actions += "unsub"
                    unsubEntered.countDown()
                    """{"code":200}"""
                }
                else -> """{"code":200}"""
            }
        }

        LibraryManager.subscribeAlbum(ctx, album(77))
        assertTrue(subEntered.await(4, TimeUnit.SECONDS))
        LibraryManager.removeAlbum(ctx, 77)
        assertFalse(
            "同一张专辑的新意图必须等旧请求结束，不能并发发出去",
            unsubEntered.await(400, TimeUnit.MILLISECONDS)
        )
        release.countDown()
        assertTrue(unsubEntered.await(4, TimeUnit.SECONDS))
        settleWrites()

        assertEquals(listOf("sub", "unsub"), actions.toList())
        assertTrue("最终状态等于用户最后一次点击", LibraryManager.getSavedAlbums(ctx).isEmpty())
        assertTrue((field(LibraryManager, "latestAlbumIntent").get(LibraryManager) as Map<*, *>).isEmpty())
    }

    // ==================== R2：旧快照无权撤销新编辑 ====================

    private fun answerAlbumRead(albumsJson: String): (Request) -> String = { request ->
        when {
            request.url.encodedPath.endsWith("/w/nuser/account/get") -> """{"code":200,"account":{"id":10001}}"""
            request.url.encodedPath.endsWith("/user/playlist") -> """{"code":200,"playlist":[]}"""
            request.url.encodedPath.endsWith("/album/sublist") -> albumsJson
            else -> """{"code":200}"""
        }
    }

    @Test
    fun `late album snapshot cannot undo a subscription made while reading`() = runBlocking {
        val readEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val page = """{"code":200,"data":[],"hasMore":false}"""
        intercept(RetrofitClient, "plainClient") { request ->
            if (request.url.encodedPath.endsWith("/album/sublist")) {
                readEntered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            answerAlbumRead(page)(request)
        }

        val refreshing = async(Dispatchers.Default) { LibraryManager.refreshFromCloud(ctx) }
        assertTrue(readEntered.await(4, TimeUnit.SECONDS))
        // 读取还挂在网络上时，用户收藏了一张专辑（本地 + 云端都成功）。
        LibraryManager.subscribeAlbum(ctx, album(77))
        assertEquals(listOf(77L), LibraryManager.getSavedAlbums(ctx).map { it.albumId })

        release.countDown()
        refreshing.await()
        settleWrites()
        writeSnapshot()

        assertEquals(
            "读取期间的新编辑被一份更老的快照撤销了",
            listOf(77L),
            LibraryManager.getSavedAlbums(ctx).map { it.albumId }
        )
        assertEquals(
            "旧快照连磁盘也一起撤销了",
            listOf(77L),
            SavedAlbumCodec.decode(
                ctx.getSharedPreferences("ncrust_library", 0).getString("saved_albums", null)
            ).map { it.albumId }
        )
    }

    @Test
    fun `late album snapshot cannot resurrect an album removed while reading`() = runBlocking {
        ctx.getSharedPreferences("ncrust_library", 0).edit()
            .putString("saved_albums", SavedAlbumCodec.encode(listOf(album(88)))).commit()
        val readEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val page = """{"code":200,"data":[{"id":88,"name":"fixture-88"}],"hasMore":false}"""
        intercept(RetrofitClient, "plainClient") { request ->
            if (request.url.encodedPath.endsWith("/album/sublist")) {
                readEntered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            answerAlbumRead(page)(request)
        }

        val refreshing = async(Dispatchers.Default) { LibraryManager.refreshFromCloud(ctx) }
        assertTrue(readEntered.await(4, TimeUnit.SECONDS))
        LibraryManager.removeAlbum(ctx, 88)
        assertEquals(
            "取消收藏必须立即生效",
            emptyList<Long>(),
            LibraryManager.getSavedAlbums(ctx).map { it.albumId },
        )

        release.countDown()
        refreshing.await()
        settleWrites()
        writeSnapshot()

        assertTrue(
            "取消收藏被读取中的旧快照复活了",
            LibraryManager.getSavedAlbums(ctx).isEmpty()
        )
        assertTrue(
            SavedAlbumCodec.decode(
                ctx.getSharedPreferences("ncrust_library", 0).getString("saved_albums", null)
            ).isEmpty()
        )
    }
}
