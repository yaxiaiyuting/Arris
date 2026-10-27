/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 审查修复回归：H21（QQ 歌单丢掉响应完整性）。
 *
 * 两个层次都钉住：
 * ① 纯判定 `QqPlaylistRepository.detailIsComplete`（声明的总数 vs 实际拿到的条数）；
 * ② 真实 `QqPlaylistRepository.loadDetail` 走一次本地拦截的 HTTP：
 *    「声明 total=100 却不给 songlist」必须降级成 TRUNCATED、缓存 complete=false，
 *    并且上层 `LocalPlaylistRepository.sync` 只能得到 Partial —— 原来这一串会一路被当成
 *    「完整空表」，TTL 直接推进 10 分钟。
 *
 * 网络由进程内固定替身代替（OkHttp 拦截器直接返回构造好的 JSON），不连接任何真实服务。
 */

package com.takahashirinta.ncrust.qq

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.local.LocalPartialCause
import com.takahashirinta.ncrust.local.LocalPlaylistRepository
import com.takahashirinta.ncrust.local.LocalPlaylistStore
import com.takahashirinta.ncrust.local.LocalSyncOutcome
import com.takahashirinta.ncrust.playlist.PlaylistCacheCodec
import com.takahashirinta.ncrust.playlist.PlaylistDegradation
import com.takahashirinta.ncrust.playlist.PlaylistHardFailure
import com.takahashirinta.ncrust.playlist.PlaylistResult
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QqPlaylistCompletenessTest {

    private class FixtureContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, SharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            synchronized(stores) { stores.getOrPut(name) { MemoryPrefs() } }
    }

    private val key = PlaylistKey(MusicSource.QQMUSIC, "55", "10001")

    private fun field(owner: Any, name: String) =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    /**
     * 把 `QqClient` 的 OkHttpClient 换成"按 module 直接回 JSON"的替身。
     * 只替换 lazy 的 `_value`，用完还原 —— 这是仓库里既有的探针手法。
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

    /** 详情响应：`songlist` 为 null 表示"服务端根本没给数组"。 */
    private fun detailResponse(declaredTotal: Int, hasMore: Boolean, songlist: String?): String =
        """{"req":{"code":0,"data":{
            "total_song_num":$declaredTotal,
            "hasmore":${if (hasMore) 1 else 0},
            "dirinfo":{"id":55,"dirid":1,"songnum":$declaredTotal}
            ${if (songlist != null) ",\"songlist\":$songlist" else ""}
        }}}"""

    // ------------------------------------------------------- 纯判定（H21） ----

    @Test
    fun completenessComparesDeclaredTotalWithCollectedCount() {
        assertTrue("没声明总数 ⇒ 只能按 hasMore 判", QqPlaylistRepository.detailIsComplete(0, 0, false))
        assertTrue("权威空表（声明 0 首，拿到 0 首）", QqPlaylistRepository.detailIsComplete(0, 0, false))
        assertTrue("条数对得上", QqPlaylistRepository.detailIsComplete(2, 2, false))
        assertTrue("还有下一页就不算完整", !QqPlaylistRepository.detailIsComplete(2, 2, true))
        assertFalse(
            "声明 100 首却一首都没拿到 = 结构异常，不是空歌单",
            QqPlaylistRepository.detailIsComplete(100, 0, false),
        )
        assertFalse(
            "少了一首也是没取全",
            QqPlaylistRepository.detailIsComplete(2, 1, false),
        )
    }

    // --------------------------------------------------- 真实取数（H21） ----

    /**
     * H21 的原始探针：`code=0`、声明 `total_song_num=100`、**没有 `songlist`**。
     *
     * 修复前：`hasMore=false` ⇒ `complete=true`，零曲目被缓存成"新鲜又完整"，上层无从判断。
     */
    @Test
    fun missingSongArrayIsNotAFreshCompleteEmptyPlaylist() = runBlocking {
        intercepted({ request ->
            if (request.optString("module") == QqRequests.USER_INFO_MODULE) {
                """{"req":{"code":0,"data":{"info":{"nick":"fixture"}}}}"""
            } else {
                detailResponse(declaredTotal = 100, hasMore = false, songlist = null)
            }
        }) { context ->
            login(context)
            try {
                val result = QqPlaylistRepository.loadDetail(key, 1L, forceRefresh = true)
                assertTrue("夹具响应是合法的 code=0，拿到 $result", result is PlaylistResult.Data)
                val outcome = (result as PlaylistResult.Data).outcome
                assertTrue(outcome.data.songs.isEmpty())
                assertEquals(
                    "缺数组必须降级成 TRUNCATED，不能报「完整」",
                    PlaylistDegradation.TRUNCATED,
                    outcome.degradation,
                )
                val cached = QqPlaylistStore.readDetail(context, key, System.currentTimeMillis())
                    as PlaylistCacheCodec.DetailRead.Ok
                assertFalse("不完整的结果不得缓存成 complete=true", cached.complete)
                assertEquals(100, cached.total)

                // 上层：TTL 不被推进，同步结果只能是 Partial。
                val playlist = LocalPlaylistStore.createPlaylist(context, key, "fixture", 1)!!
                val synced = LocalPlaylistRepository.sync(context, playlist, force = false, now = 2000)
                assertTrue("上层必须能判出'没取全'，拿到 $synced", synced is LocalSyncOutcome.Partial)
                assertEquals(LocalPartialCause.TRUNCATED, (synced as LocalSyncOutcome.Partial).cause)
                assertEquals(
                    "不完整同步不推进 lastSyncedAt",
                    0L,
                    LocalPlaylistStore.readPlaylists(context).single().lastSyncedAt,
                )
            } finally {
                QqAuthStore.clear(context)
            }
        }
    }

    /** 反向对照：条数与声明一致时仍然是完整成功（别把一切都判成不完整）。 */
    @Test
    fun aCompleteResponseStillReportsCompleteAndAdvancesTheTtl() = runBlocking {
        intercepted({ request ->
            if (request.optString("module") == QqRequests.USER_INFO_MODULE) {
                """{"req":{"code":0,"data":{"info":{"nick":"fixture"}}}}"""
            } else {
                detailResponse(
                    declaredTotal = 2,
                    hasMore = false,
                    songlist = """
                        [{"id":101,"mid":"fixture-mid101","name":"A","singer":[]},
                         {"id":202,"mid":"fixture-mid202","name":"B","singer":[]}]
                    """.trimIndent(),
                )
            }
        }) { context ->
            login(context)
            try {
                val result = QqPlaylistRepository.loadDetail(key, 1L, forceRefresh = true)
                val outcome = (result as PlaylistResult.Data).outcome
                assertNull("取全了就没有降级", outcome.degradation)
                assertEquals(2, outcome.data.songs.size)
                val cached = QqPlaylistStore.readDetail(context, key, System.currentTimeMillis())
                    as PlaylistCacheCodec.DetailRead.Ok
                assertTrue(cached.complete)

                val playlist = LocalPlaylistStore.createPlaylist(context, key, "fixture", 1)!!
                val synced = LocalPlaylistRepository.sync(context, playlist, force = false, now = 2000)
                assertTrue("完整读取应当是 Success，拿到 $synced", synced is LocalSyncOutcome.Success)
                assertEquals(2, (synced as LocalSyncOutcome.Success).added)
                assertEquals(
                    "完整同步推进 lastSyncedAt",
                    2000L,
                    LocalPlaylistStore.readPlaylists(context).single().lastSyncedAt,
                )
            } finally {
                QqAuthStore.clear(context)
            }
        }
    }

    /** 部分结果不得覆盖「已知完整」的缓存。 */
    @Test
    fun anIncompleteFetchDoesNotOverwriteACompleteCache() = runBlocking {
        intercepted({ request ->
            if (request.optString("module") == QqRequests.USER_INFO_MODULE) {
                """{"req":{"code":0,"data":{"info":{"nick":"fixture"}}}}"""
            } else {
                detailResponse(declaredTotal = 2, hasMore = false, songlist = null)
            }
        }) { context ->
            login(context)
            try {
                // 先放一份「完整」缓存（savedAt 很旧，因此过期，forceRefresh 会真的去取）。
                val songs = listOf(
                    com.takahashirinta.ncrust.network.SongItem(
                        id = 1L, name = "A", artists = null, album = null, duration = 1000L,
                        source = "qqmusic", sourceId = "fixture-mid1", mediaId = "fixture-media1",
                    ),
                )
                QqPlaylistStore.writeDetail(
                    context = context,
                    key = key,
                    songs = songs,
                    savedAt = 1L,
                    complete = true,
                    total = 1,
                )

                val result = QqPlaylistRepository.loadDetail(key, 1L, forceRefresh = true)
                assertEquals(
                    PlaylistDegradation.TRUNCATED,
                    (result as PlaylistResult.Data).outcome.degradation,
                )
                val cached = QqPlaylistStore.readDetail(context, key, System.currentTimeMillis())
                    as PlaylistCacheCodec.DetailRead.Ok
                assertTrue("完整缓存不得被这次不完整结果降级", cached.complete)
                assertEquals(1, cached.songs.size)
            } finally {
                QqAuthStore.clear(context)
            }
        }
    }

    /** 第一页就失败时仍然是硬失败（不因为本次改动被误判成"不完整"）。 */
    @Test
    fun aFirstPageFailureStaysAHardFailure() = runBlocking {
        intercepted({ request ->
            if (request.optString("module") == QqRequests.USER_INFO_MODULE) {
                """{"req":{"code":0,"data":{"info":{"nick":"fixture"}}}}"""
            } else {
                """{"req":{"code":80050,"data":{}}}"""
            }
        }) { context ->
            login(context)
            try {
                val result = QqPlaylistRepository.loadDetail(key, 1L, forceRefresh = true)
                assertTrue("拿到 $result", result is PlaylistResult.Failed)
                assertTrue(
                    (result as PlaylistResult.Failed).reason is PlaylistHardFailure.Other,
                )
            } finally {
                QqAuthStore.clear(context)
            }
        }
    }
}
