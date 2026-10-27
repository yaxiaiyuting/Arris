/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 审查修复回归：H21（QQ 歌单翻页游标用了「映射后条数」）。
 *
 * 现场：`QqPlaylistApi` 里 `begin += page.tracks.size` 用的是**映射后**条数。只要有一行
 * 解析不出（缺 `songmid`）就被跳过，下一页的 offset 于是与上一页重叠（原探针 A、A、B）。
 * 另外 `songlist` 数组**缺失**时被当成"空页/完整"。
 *
 * 本文件钉住三件事：
 * ① 解析层：`rawCount` 记的是**原始条目数**（含被丢弃的行），`hasSonglist` 记数组在不在；
 * ② 分页层（真实拦截 HTTP）：游标按原始条目数前进 ⇒ 请求 begin 是 0、3，不再是 0、2；
 *    整页都解析不出时也照样前进（旧实现会因为 `tracks.isEmpty()` 提前收摊）；
 * ③ 完整性判据：`detailIsComplete` 收到 `hasSonglist=false` 一律判不完整。
 */

package com.takahashirinta.ncrust.qq

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import java.util.Collections
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqPlaylistCursorTest {

    private class FixtureContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, SharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            synchronized(stores) { stores.getOrPut(name) { MemoryPrefs() } }
    }

    private val key = PlaylistKey(MusicSource.QQMUSIC, "55", "10001")

    private fun field(owner: Any, name: String) =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    /** 一个原始条目。`mid == null` ⇒ 缺 songmid，`QqSongMapper` 会把它丢掉。 */
    private fun entry(mid: String?, index: Int): JSONObject = JSONObject().apply {
        put("id", 100 + index)
        put("name", "s$index")
        put("interval", 100)
        if (mid != null) {
            put("mid", mid)
            put("file", JSONObject().put("media_mid", mid))
        }
    }

    /** 服务端按 `song_begin` / `song_num` 切同一个数组 —— 与真实接口的语义一致。 */
    private fun pageResponse(list: List<JSONObject>, begin: Int, num: Int): String {
        val slice = JSONArray()
        val end = minOf(begin + num, list.size)
        for (i in begin until end) slice.put(list[i])
        return envelope(
            JSONObject()
                .put("total_song_num", list.size)
                .put("hasmore", if (end < list.size) 1 else 0)
                .put("dirinfo", JSONObject().put("id", 55L).put("dirid", 1L).put("songnum", list.size))
                .put("songlist", slice),
        )
    }

    /** 结构异常：`dirinfo` 有、**`songlist` 整个缺失**，但 `hasmore` 还在说"有下一页"。 */
    private fun missingSonglistResponse(total: Int, hasMore: Boolean): String = envelope(
        JSONObject()
            .put("total_song_num", total)
            .put("hasmore", if (hasMore) 1 else 0)
            .put("dirinfo", JSONObject().put("id", 55L).put("dirid", 1L).put("songnum", total)),
    )

    private fun envelope(data: JSONObject): String =
        JSONObject().put("req", JSONObject().put("code", 0).put("data", data)).toString()

    /**
     * 把 `QqClient` 的 OkHttpClient 换成"按 module 直接回 JSON"的替身。
     * 只替换 lazy 的 `_value`，用完还原 —— 与 `QqPlaylistCompletenessTest` 同一手法。
     */
    private suspend fun <T> intercepted(
        respond: (JSONObject) -> String,
        block: suspend (FixtureContext) -> T,
    ): T {
        val context = FixtureContext()
        val clientApp = field(QqClient, "appContext")
        val repoApp = field(QqPlaylistRepository, "appContext")
        val oldClientApp = clientApp.get(QqClient)
        val oldRepoApp = repoApp.get(QqPlaylistRepository)
        val delegate = field(QqClient, "http\$delegate").get(QqClient) as Lazy<*>
        val lazyValue = field(delegate, "_value")
        val oldHttp = lazyValue.get(delegate)
        val fake = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body?.writeTo(buffer)
            val request = JSONObject(buffer.readUtf8()).getJSONObject("req")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("fixture")
                .body(respond(request).toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        QqClient.init(context)
        QqPlaylistRepository.init(context)
        lazyValue.set(delegate, fake)
        try {
            return block(context)
        } finally {
            lazyValue.set(delegate, oldHttp)
            clientApp.set(QqClient, oldClientApp)
            repoApp.set(QqPlaylistRepository, oldRepoApp)
            fake.dispatcher.executorService.shutdown()
            fake.connectionPool.evictAll()
        }
    }

    private fun login(context: FixtureContext) {
        QqAuthStore.replaceCookie(context, "uin=10001; qqmusic_key=fixture-key")
    }

    // ------------------------------------------------------- 解析层 ----

    /** `rawCount` 必须把解析不出的行也数进去，`hasSonglist` 说数组在不在。 */
    @Test
    fun parserKeepsRawCountAndFlagsTheMissingArray() {
        val withHole = JSONObject(
            """{"code":0,"total_song_num":2,"hasmore":0,"dirinfo":{"id":1,"dirid":2},
                "songlist":[{"id":1,"name":"没有 mid"},{"id":2,"mid":"m2","file":{"media_mid":"m2"}}]}""",
        )
        val page = (QqPlaylistParser.parseDetailPage(withHole, key, 0) as QqPlaylistResult.Ok).value
        assertEquals("映射后只剩 1 首", 1, page.tracks.size)
        assertEquals("原始条目仍是 2 条（游标要按它前进）", 2, page.rawCount)
        assertTrue(page.hasSonglist)

        val missing = JSONObject("""{"code":0,"dirinfo":{"id":1,"dirid":2}}""")
        val noArray = (QqPlaylistParser.parseDetailPage(missing, key, 0) as QqPlaylistResult.Ok).value
        assertEquals(0, noArray.rawCount)
        assertFalse("缺 songlist 必须被记成结构异常", noArray.hasSonglist)
    }

    /** 缺数组是"判不了"，不是"完整空表"；三参调用点语义不变。 */
    @Test
    fun detailIsCompleteTreatsAMissingSonglistAsIncomplete() {
        assertTrue("没给 hasSonglist 时按原有语义判", QqPlaylistRepository.detailIsComplete(2, 2, false))
        assertTrue( QqPlaylistRepository.detailIsComplete(2, 2, false, hasSonglist = true))
        assertFalse(
            "缺 songlist ⇒ 一律不完整（哪怕服务端说 0 首、hasmore=0）",
            QqPlaylistRepository.detailIsComplete(0, 0, false, hasSonglist = false),
        )
        assertFalse(
            "条数对得上也不能救回缺数组的响应",
            QqPlaylistRepository.detailIsComplete(2, 2, false, hasSonglist = false),
        )
    }

    // ------------------------------------------------------- 分页层 ----

    /**
     * H21 的原始探针：第 2 个原始条目缺 mid。
     *
     * 修复前：第 1 页 mapped=2 ⇒ `begin += 2` ⇒ 第 2 页从 2 开始，把上一页的第 3 条
     * （C）又拿了一遍 ⇒ 序列变成 A、C、C、D…（探针描述为 A、A、B）。
     * 修复后：`begin += rawCount(3)` ⇒ 请求 begin 是 0、3，序列不重叠。
     */
    @Test
    fun theCursorAdvancesByRawCountSoPagesNeverOverlap() = runBlocking {
        val entries = listOf(
            entry("m0", 0),
            entry(null, 1), // ← 缺 songmid，会被 QqSongMapper 丢掉
            entry("m2", 2),
            entry("m3", 3),
            entry("m4", 4),
            entry("m5", 5),
        )
        val begins = Collections.synchronizedList(mutableListOf<Int>())
        intercepted({ request ->
            val param = request.optJSONObject("param")!!
            val begin = param.optInt("song_begin")
            begins += begin
            pageResponse(entries, begin, param.optInt("song_num"))
        }) { context ->
            login(context)
            try {
                val result = QqPlaylistApi.fetchAllPages(key, disstid = 55L, dirId = 1L, pageSize = 3)
                val page = (result as QqPlaylistResult.Ok).value
                assertEquals("请求的 begin 必须是 0、3（不是 0、2）", listOf(0, 3), begins.toList())
                assertEquals(
                    "不允许出现重复曲目（旧实现会在第 2 页重复 m2）",
                    listOf("m0", "m2", "m3", "m4", "m5"),
                    page.songs.map { it.sourceId },
                )
                assertEquals(page.songs.map { it.sourceId }.distinct().size, page.songs.size)
                assertEquals("映射后 5 首", 5, page.tracks.size)
                assertEquals("原始条目 6 条", 6, page.rawCount)
                assertTrue(page.hasSonglist)
                assertFalse("已经翻到末页", page.hasMore)
                assertTrue(
                    "原始条目数对得上声明总数 ⇒ 取全了（不再因为丢弃行而永远不完整）",
                    QqPlaylistRepository.detailIsComplete(
                        page.total, page.rawCount, page.hasMore, page.hasSonglist,
                    ),
                )
            } finally {
                QqAuthStore.clear(context)
            }
        }
    }

    /** 整页都解析不出时也必须前进：旧实现用 `tracks.isEmpty()` 收摊，会少拿后面所有页。 */
    @Test
    fun aFullyUnmappablePageStillAdvancesTheCursor() = runBlocking {
        val entries = listOf(
            entry(null, 0),
            entry(null, 1),
            entry(null, 2),
            entry("m3", 3),
            entry("m4", 4),
            entry("m5", 5),
        )
        val begins = Collections.synchronizedList(mutableListOf<Int>())
        intercepted({ request ->
            val param = request.optJSONObject("param")!!
            val begin = param.optInt("song_begin")
            begins += begin
            pageResponse(entries, begin, param.optInt("song_num"))
        }) { context ->
            login(context)
            try {
                val result = QqPlaylistApi.fetchAllPages(key, disstid = 55L, dirId = 1L, pageSize = 3)
                val page = (result as QqPlaylistResult.Ok).value
                assertEquals(listOf(0, 3), begins.toList())
                assertEquals(listOf("m3", "m4", "m5"), page.songs.map { it.sourceId })
                assertEquals(6, page.rawCount)
                assertFalse(page.hasMore)
            } finally {
                QqAuthStore.clear(context)
            }
        }
    }

    /**
     * 缺 `songlist` + `hasmore=1`：既不能前进（会死循环在同一个 offset），
     * 也不能当成"空页/完整"。必须只发一次请求，并把 `hasSonglist=false` 带出去。
     */
    @Test
    fun aMissingSonglistStopsPagingAndStaysIncomplete() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Int>())
        intercepted({ request ->
            requests += request.optJSONObject("param")!!.optInt("song_begin")
            missingSonglistResponse(total = 6, hasMore = true)
        }) { context ->
            login(context)
            try {
                val result = QqPlaylistApi.fetchAllPages(key, disstid = 55L, dirId = 1L, pageSize = 3)
                val page = (result as QqPlaylistResult.Ok).value
                assertEquals("只能问一次：没有原始条目可前进", 1, requests.size)
                assertTrue(page.songs.isEmpty())
                assertEquals(0, page.rawCount)
                assertFalse("缺数组必须被记成结构异常", page.hasSonglist)
                assertTrue("服务端还说有下一页 ⇒ 上层看到的仍是「没拉全」", page.hasMore)
                assertFalse(
                    "缺数组 ⇒ 不完整，上层据此报 TRUNCATED",
                    QqPlaylistRepository.detailIsComplete(
                        page.total, page.rawCount, page.hasMore, page.hasSonglist,
                    ),
                )
            } finally {
                QqAuthStore.clear(context)
            }
        }
    }
}
