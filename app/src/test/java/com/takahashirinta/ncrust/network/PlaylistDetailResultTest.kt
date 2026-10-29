/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 审查修复回归：H10（`PlaylistApi.getPlaylistDetail` 把完整性丢在函数内部）。
 *
 * 三层都钉住：
 * ① 纯判定 `PlaylistApi.detailCompleteness`（声明总数 / 拿到条数 / 失败批次数）；
 * ② 真实 `PlaylistApi.getPlaylistDetailResult` 走一次本地拦截的 HTTP：
 *    「歌单确实只有 5 首」「服务端说 20 首只给 5 首」「第二批详情失败」三种形状必须能被区分；
 * ③ 上游接线：`LocalPlaylistRepository.sync` 对不完整只回 `Partial(TRUNCATED)`、
 *    **不推进 lastSyncedAt**；对完整才回 `Success`。
 *
 * 网络由进程内固定替身代替（OkHttp 拦截器直接返回构造好的 JSON），不连接任何真实服务。
 */

package com.takahashirinta.ncrust.network

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.local.LocalPartialCause
import com.takahashirinta.ncrust.local.LocalPlaylistRepository
import com.takahashirinta.ncrust.local.LocalPlaylistStore
import com.takahashirinta.ncrust.local.LocalSyncOutcome
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaylistDetailResultTest {

    private class FixtureContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, SharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            synchronized(stores) { stores.getOrPut(name) { MemoryPrefs() } }
    }

    private lateinit var ctx: FixtureContext
    private val restorers = mutableListOf<() -> Unit>()

    private fun field(owner: Any, name: String) =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Before
    fun setup() {
        ctx = FixtureContext()
        field(CookieManager, "app").set(CookieManager, null)
        CookieManager.init(ctx)
    }

    @After
    fun cleanup() {
        restorers.asReversed().forEach { it() }
        field(CookieManager, "app").set(CookieManager, null)
    }

    /**
     * 把 `RetrofitClient.plainClient` 换成"按路径直接回 JSON"的替身。
     * 只替换 lazy 的 `_value`，用完还原 —— 与仓库里既有的探针手法一致。
     */
    private fun intercept(answer: (Request) -> String) {
        val delegate = field(RetrofitClient, "plainClient\$delegate").get(RetrofitClient) as Lazy<*>
        val original = delegate.value
        val valueField = delegate.javaClass.getDeclaredField("_value").apply { isAccessible = true }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("fixture")
                .body(answer(request).toResponseBody())
                .build()
        }.build()
        valueField.set(delegate, client)
        restorers += {
            valueField.set(delegate, original)
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun songJson(id: Long): JSONObject = JSONObject()
        .put("id", id)
        .put("name", "s$id")
        .put("dt", 1000L)
        .put("ar", JSONArray().put(JSONObject().put("id", 1L).put("name", "artist")))
        .put("al", JSONObject().put("id", 2L).put("name", "album"))

    /**
     * `v6/playlist/detail` 的最小形状。
     *
     * 真实响应里 `tracks` 只带前 ~20 首完整对象、`trackIds` 是**完整**顺序表；
     * 夹具把两者拆开传，正是为了单独控制这几种截断形状。
     */
    private fun detailJson(declaredCount: Int, trackIds: List<Long>, tracks: List<Long>): String {
        val ids = JSONArray()
        trackIds.forEach { ids.put(JSONObject().put("id", it)) }
        val trackArray = JSONArray()
        tracks.forEach { trackArray.put(songJson(it)) }
        return JSONObject()
            .put("code", 200)
            .put(
                "playlist",
                JSONObject()
                    .put("trackCount", declaredCount)
                    .put("trackIds", ids)
                    .put("tracks", trackArray),
            )
            .toString()
    }

    private fun songsJson(ids: List<Long>): String {
        val arr = JSONArray()
        ids.forEach { arr.put(songJson(it)) }
        return JSONObject().put("code", 200).put("songs", arr).toString()
    }

    /** 只为 `v3/song/detail` 准备固定答案（请求体是 eapi 加密的，夹具不需要读懂它）。 */
    private fun route(detail: String, songDetail: String = """{"code":200,"songs":[]}"""): (Request) -> String =
        { request ->
            when {
                request.url.encodedPath.endsWith("/playlist/detail") -> detail
                request.url.encodedPath.endsWith("/song/detail") -> songDetail
                else -> """{"code":200}"""
            }
        }

    // ------------------------------------------------- 纯判定（H10） ----

    @Test
    fun completenessDistinguishesTheThreeCases() {
        assertTrue("歌单确实只有 5 首 = 完整", PlaylistApi.detailCompleteness(5, 5, 0))
        assertTrue("服务端没声明总数、也没失败批次 = 只能判完整", PlaylistApi.detailCompleteness(0, 0, 0))
        assertFalse(
            "服务端说 20 首、只拿到 5 首 = 截断",
            PlaylistApi.detailCompleteness(20, 5, 0),
        )
        assertFalse(
            "有一批详情请求失败 = 不完整（哪怕条数看起来对得上）",
            PlaylistApi.detailCompleteness(20, 20, 1),
        )
        assertFalse("少一首也不算完整", PlaylistApi.detailCompleteness(5, 4, 0))
    }

    // --------------------------------------------- 真实取数（H10） ----

    /** 反向对照：真的只有 5 首时必须是 `complete=true`，别把一切都判成不完整。 */
    @Test
    fun aShortPlaylistWithoutTrackIdsIsComplete() = runBlocking {
        intercept(route(detailJson(declaredCount = 5, trackIds = emptyList(), tracks = listOf(1L, 2L, 3L, 4L, 5L))))
        val result = PlaylistApi.getPlaylistDetailResult(7L)
        assertEquals(5, result.songs.size)
        assertEquals(5, result.declaredCount)
        assertEquals(0, result.failedBatches)
        assertTrue("5 首就是全部，必须报完整", result.complete)
        // 便利包装仍是同一个列表（既有 UI 调用点不改签名）。
        assertEquals(result.songs, PlaylistApi.getPlaylistDetail(7L))
    }

    /** H10 的核心形状：服务端声明 20 首，`trackIds` 却只给了 5 首。 */
    @Test
    fun declaredTwentyButOnlyFiveDeliveredIsIncomplete() = runBlocking {
        intercept(route(detailJson(declaredCount = 20, trackIds = listOf(1L, 2L, 3L, 4L, 5L), tracks = listOf(1L, 2L, 3L, 4L, 5L))))
        val result = PlaylistApi.getPlaylistDetailResult(7L)
        assertEquals(5, result.songs.size)
        assertEquals(20, result.declaredCount)
        assertEquals("没有批次可失败（trackIds 自己就短）", 0, result.failedBatches)
        assertFalse("声明 20 只给 5 = 截断", result.complete)
    }

    /** 第二批详情请求失败：少了一截，必须能被数出来。 */
    @Test
    fun aFailedDetailBatchIsCountedNotSwallowed() = runBlocking {
        // tracks 只带第 1 首，另外两首要靠 v3/song/detail 补 —— 而它只回了 1 首。
        intercept(
            route(
                detail = detailJson(declaredCount = 3, trackIds = listOf(1L, 2L, 3L), tracks = listOf(1L)),
                songDetail = songsJson(listOf(2L)),
            )
        )
        val result = PlaylistApi.getPlaylistDetailResult(7L)
        assertEquals(listOf(1L, 2L), result.songs.map { it.id })
        assertEquals(3, result.declaredCount)
        assertEquals("补详情只回来一半 = 这一批没成功", 1, result.failedBatches)
        assertFalse(result.complete)
    }

    // ------------------------------------------ 上游接线（H10） ----

    /** 完整读取 ⇒ 本地同步是 Success，且推进 lastSyncedAt。 */
    @Test
    fun aCompleteNeteaseReadAdvancesTheTtl() = runBlocking {
        intercept(route(detailJson(declaredCount = 2, trackIds = emptyList(), tracks = listOf(11L, 22L))))
        val key = PlaylistKey(MusicSource.NETEASE, "7", "10001")
        val playlist = LocalPlaylistStore.createPlaylist(ctx, key, "fixture", 1L)!!
        val outcome = LocalPlaylistRepository.sync(ctx, playlist, force = false, now = 2000L)
        assertTrue("完整读取应当是 Success，拿到 $outcome", outcome is LocalSyncOutcome.Success)
        assertEquals(2, (outcome as LocalSyncOutcome.Success).added)
        assertEquals(2000L, LocalPlaylistStore.readPlaylists(ctx).single().lastSyncedAt)
    }

    /**
     * 不完整读取 ⇒ `Partial(TRUNCATED)`，**不推进 lastSyncedAt**（TTL 之后还能再补取），
     * 但仍然把拿到的那一部分交给合并（只加不减，不丢用户数据）。
     */
    @Test
    fun anIncompleteNeteaseReadIsPartialAndKeepsTheTtl() = runBlocking {
        intercept(route(detailJson(declaredCount = 20, trackIds = listOf(1L, 2L, 3L, 4L, 5L), tracks = listOf(1L, 2L, 3L, 4L, 5L))))
        val key = PlaylistKey(MusicSource.NETEASE, "7", "10001")
        val playlist = LocalPlaylistStore.createPlaylist(ctx, key, "fixture", 1L)!!

        val songs = LocalPlaylistRepository.loadRemoteSongs(key, 0L, forceRefresh = false)
        assertNotNull("不完整不是失败：能用的那一段照样要交出来", songs)
        assertEquals(5, songs!!.size)

        val outcome = LocalPlaylistRepository.sync(ctx, playlist, force = false, now = 2000L)
        assertTrue("不完整只能是 Partial，拿到 $outcome", outcome is LocalSyncOutcome.Partial)
        val partial = outcome as LocalSyncOutcome.Partial
        assertEquals(LocalPartialCause.TRUNCATED, partial.cause)
        assertEquals(5, partial.added)
        assertEquals(
            "不完整同步不推进 lastSyncedAt",
            0L,
            LocalPlaylistStore.readPlaylists(ctx).single().lastSyncedAt,
        )
    }

    /**
     * 回归：详情接口也必须把 `originCoverType` 带出来。
     *
     * 症状是「搜索里有『原唱』角标，收藏库/歌单详情里同一首歌没有」——
     * 搜索走 `SearchResponse` 的映射，详情走 `parseSongTrack`，而后者长期只挑
     * id/name/ar/al/dt，把版本字段丢了。
     */
    @Test
    fun songDetailCarriesOriginCoverTag() = runBlocking {
        val detail = JSONObject()
            .put("id", 11L)
            .put("name", "s11")
            .put("dt", 1000L)
            .put("ar", JSONArray().put(JSONObject().put("id", 1L).put("name", "artist")))
            .put("al", JSONObject().put("id", 2L).put("name", "album"))
            .put("originCoverType", 1)
        // 歌单详情只给 trackIds（不带 tracks），歌曲对象由 `v3/song/detail` 补齐 ——
        // 这正是收藏库读取时走的路径。
        val playlist = detailJson(declaredCount = 1, trackIds = listOf(11L), tracks = emptyList())
        val songDetail = JSONObject().put("code", 200).put("songs", JSONArray().put(detail)).toString()
        intercept(route(playlist, songDetail))
        val result = PlaylistApi.getPlaylistDetailResult(7L)
        assertEquals(1, result.songs.size)
        assertEquals("详情必须带 originCoverType", 1, result.songs.first().originCoverType)
    }

    @Test fun `user playlist paging crosses the first hundred including hidden special rows`() = kotlinx.coroutines.runBlocking {
        var requests = 0
        val offsets = mutableListOf<Int>()
        intercept { request ->
            val hex = (request.body as okhttp3.FormBody).value(0)
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val plain = com.takahashirinta.ncrust.network.crypto.EapiCrypto.decryptResponse(java.util.Base64.getEncoder().encodeToString(bytes))
            val payload = JSONObject(plain.split("-36cd479b6b5-")[1])
            offsets += payload.getInt("offset")
            requests++
            if (requests == 1) {
                val rows = JSONArray().put(JSONObject().put("id",999).put("specialType",5))
                repeat(99) { n -> rows.put(JSONObject().put("id",n+1).put("name","p$n")) }
                JSONObject().put("code",200).put("more",true).put("playlist",rows).toString()
            } else {
                JSONObject().put("code",200).put("more",false)
                    .put("playlist",JSONArray().put(JSONObject().put("id",1000).put("name","last"))).toString()
            }
        }
        val all = PlaylistApi.getAllUserPlaylists(123)
        assertEquals(listOf(0,100), offsets)
        assertEquals(2, requests);assertEquals(100,all.size)
        assertEquals(1000L,all.last().id);assertFalse(all.any { it.specialType != 0 })
    }
}
