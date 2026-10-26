package com.takahashirinta.ncrust.search

import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.SongUrlResult
import com.takahashirinta.ncrust.source.*
import com.takahashirinta.ncrust.ui.viewmodel.SearchViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.internal.MainDispatcherFactory
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/** Isolated audit JVM only; the service registration is removed after the probe. */
@OptIn(InternalCoroutinesApi::class)
class AuditMainDispatcherFactory : MainDispatcherFactory {
    override val loadPriority = Int.MAX_VALUE
    override fun hintOnError() = "audit main dispatcher"
    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher = AuditMain
}
private object AuditMain : MainCoroutineDispatcher() {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "arris-audit-main").apply { isDaemon = true } }
    override val immediate: MainCoroutineDispatcher get() = this
    override fun dispatch(context: CoroutineContext, block: Runnable) { executor.execute(block) }
}

/** Characterizes partial success and duplicate accumulation in the real SearchViewModel. */
class SearchPartialPageProbe {
    @Test fun partialFailureAdvancesSharedCursorAndDoubleCountsSuccessfulSource() = runBlocking {
        val field = RetrofitClient.javaClass.getDeclaredField("restClient\$delegate").apply { isAccessible = true }
        val lazy = field.get(RetrofitClient) as Lazy<*>
        val original = lazy.value
        val value = lazy.javaClass.getDeclaredField("_value").apply { isAccessible = true }
        val offsets = mutableListOf<Int>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
            val offset = Regex("(?:^|&)offset=(\\d+)").find(buffer.readUtf8())!!.groupValues[1].toInt()
            synchronized(offsets) { offsets += offset }
            val rows = JSONArray()
            repeat(30) { rows.put(JSONObject().put("id", 1000 + offset + it).put("name", "fixture")) }
            val json = JSONObject().put("result", JSONObject().put("songs", rows)).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (offset == 30) 500 else 200).message("fixture").body(json.toResponseBody()).build()
        }.build()
        value.set(lazy, client)
        val oldQq = SourceRouter.provider(MusicSource.QQMUSIC)
        SourceRouter.register(object : MusicSourceProvider {
            override val source = MusicSource.QQMUSIC
            override val isLoggedIn = true
            override suspend fun searchSongs(keyword: String, limit: Int, page: Int, totalOut: MutableMap<String, Int>?): List<SongItem> {
                delay(150)
                return (0 until limit).map { i -> SongItem(SourceIds.qqId(page * 1000L + i, "fixture-$page-$i"),
                    "fixture", null, null, 200000, source = "qqmusic", sourceId = "fixture-$page-$i") }
            }
            override suspend fun resolveUrl(song: SongItem, level: String): SongUrlResult? = null
            override suspend fun songDetail(song: SongItem): SongItem = song
        })
        val vm = SearchViewModel()
        suspend fun settled(minimum: Int) = withTimeout(5000) {
            while (vm.songs.value.size < minimum || vm.isLoading.value || vm.isLoadingMore.value) delay(10)
        }
        try {
            withContext(Dispatchers.Main) { vm.onQueryChanged("fixture") }
            settled(60)
            withContext(Dispatchers.Main) { vm.loadMore() }; settled(90)
            withContext(Dispatchers.Main) { vm.loadMore() }; settled(150)
            assertEquals(listOf(0, 30, 60), offsets.toList())
            val netease = vm.songs.value.filter { it.musicSource == MusicSource.NETEASE }
            assertEquals(60, netease.size)
            assertFalse(netease.any { it.id == 1030L })
            assertTrue(netease.any { it.id == 1060L })
            assertEquals(90, vm.sourceCounts.value!!.neteaseCount)
            println("AUDIT search partial failure: Netease page 2 requested once then skipped; source count=90 for 60 unique Netease rows")
        } finally {
            vm.viewModelScope.cancel(); value.set(lazy, original); client.dispatcher.executorService.shutdown()
            val providers = SourceRouter.javaClass.getDeclaredField("providers").apply { isAccessible = true }
                .get(SourceRouter) as MutableMap<MusicSource, MusicSourceProvider>
            if (oldQq == null) providers.remove(MusicSource.QQMUSIC) else SourceRouter.register(oldQq)
        }
    }
}
