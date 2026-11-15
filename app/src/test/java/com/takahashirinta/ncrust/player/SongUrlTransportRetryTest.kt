package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.network.PlaybackNetworkRoute
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SongUrlTransportRetryTest {
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private val song = SongItem(987654321, "fixture", null, null, 200000)
    private fun intercept(answer: (Int) -> String, block: suspend (AtomicInteger, () -> Long) -> Unit) = runBlocking {
        val delegate = field(RetrofitClient, "plainClient\$delegate").get(RetrofitClient) as Lazy<*>
        val original = delegate.value
        val value = delegate.javaClass.getDeclaredField("_value").apply { isAccessible = true }
        val cooldown = field(SongUrlFetcher, "transportCooldownUntil")
        val count = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(answer(count.incrementAndGet()).toResponseBody()).build()
        }.build()
        SongUrlFetcher.onNetworkRouteChanged(); value.set(delegate, client)
        SourceRouter.register(NeteaseSourceProvider)
        try { block(count) { cooldown.getLong(SongUrlFetcher) } }
        finally {
            value.set(delegate, original); SongUrlFetcher.onNetworkRouteChanged()
            client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun `second IOException remains NETWORK and subsequent attempt is cooled down`() =
        intercept({ throw IOException("fixture offline") }) { count, cooldown ->
            val result = SourceRouter.resolveUrlOutcome(song, "standard")
            assertEquals(ResolveFailureKind.NETWORK, result.failure?.kind)
            assertTrue(cooldown() > System.nanoTime() / 1_000_000)
            assertEquals(2, count.get())
            assertEquals(
                ResolveFailureKind.NETWORK,
                SongUrlFetcher.fetchOutcome(song.id, "standard").failure?.kind,
            )
            assertEquals(2, count.get())
        }

    @Test fun `successful transport retry returns a playable result without cooldown`() =
        intercept({ attempt -> if (attempt == 1) throw IOException("fixture reset") else
            """{"data":[{"id":987654321,"code":200,"url":"https://fixture.invalid/audio.mp3","level":"standard","br":128000,"type":"mp3"}]}"""
        }) { count, cooldown ->
            assertNotNull(SongUrlFetcher.fetch(song.id, "standard"))
            assertEquals(2, count.get())
            assertEquals(0L, cooldown())
        }

    @Test fun `business rejection after transport retry keeps its own classification`() =
        intercept({ attempt -> if (attempt == 1) throw IOException("fixture reset") else
            """{"data":[{"id":987654321,"code":404,"url":null,"level":"standard"}]}"""
        }) { count, cooldown ->
            val result = SourceRouter.resolveUrlOutcome(song, "standard")
            assertEquals(ResolveFailureKind.UNKNOWN, result.failure?.kind)
            assertEquals(0L, cooldown())
            assertEquals(2, count.get())
        }

    @Test fun `canceling active HTTP does not retry or enter cooldown`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        try {
            intercept({ entered.complete(Unit); release.await(); throw IOException("fixture canceled") }) { count, cooldown ->
                val job = launch { SongUrlFetcher.fetch(song.id, "standard") }
                withTimeout(2000) { entered.await() }
                job.cancel()
                withTimeout(2000) { job.join() }
                assertTrue(job.isCancelled)
                assertEquals(1, count.get())
                assertEquals(0L, cooldown())
                release.countDown()
            }
        } finally { release.countDown() }
    }

    @Test fun lateFailureFromThePreviousRouteDoesNotCoolDownTheNewRoute() = runBlocking {
        val oldRetryStarted = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val body = """{"data":[{"id":987654321,"code":200,"url":"https://fixture.invalid/audio.mp3","level":"standard","br":128000,"type":"mp3"}]}"""
        try {
            intercept({ attempt ->
                when (attempt) {
                    1 -> throw IOException("fixture old route")
                    2 -> {
                        oldRetryStarted.complete(Unit)
                        release.await()
                        throw IOException("fixture old route retry")
                    }
                    else -> body
                }
            }) { count, _ ->
                val old = async { SongUrlFetcher.fetchOutcome(song.id, "standard") }
                try {
                    withTimeout(5_000L) { oldRetryStarted.await() }
                    SongUrlFetcher.onNetworkRouteChanged()
                    release.countDown()
                    assertEquals(ResolveFailureKind.NETWORK, old.await().failure?.kind)
                    assertNotNull(SongUrlFetcher.fetch(song.id, "standard"))
                    assertEquals(3, count.get())
                } finally {
                    release.countDown()
                }
            }
        } finally {
            release.countDown()
        }
    }

    @Test fun reconnectResetsTransportBeforeTheSuccessorTriesToResolve() = runBlocking {
        val offline = PlaybackNetworkRoute(null, false)
        val first = PlaybackNetworkRoute(1L, true)
        val second = PlaybackNetworkRoute(2L, true)
        val body = """{"data":[{"id":987654321,"code":200,"url":"https://fixture.invalid/audio.mp3","level":"standard","br":128000,"type":"mp3"}]}"""
        val refreshing = CompletableDeferred<Unit>()
        val found = CompletableDeferred<SongUrlResult?>()
        intercept({ if (it <= 2) throw IOException("fixture old route") else body }) { count, cooldown ->
            assertEquals(ResolveFailureKind.NETWORK, SongUrlFetcher.fetchOutcome(song.id, "standard").failure?.kind)
            assertTrue(cooldown() > System.nanoTime() / 1_000_000)
            val gate = PlaybackRecoveryGate(offline)
            val routes = MutableStateFlow(offline)
            coroutineScope {
                var preload: Job? = null
                fun requestSuccessor() {
                    preload?.cancel()
                    preload = launch {
                        gate.awaitRouteRefresh()
                        found.complete(SongUrlFetcher.fetch(song.id, "standard"))
                    }
                }
                val watch = launch(start = CoroutineStart.UNDISPATCHED) {
                    routes.collectLatest { route ->
                        gate.updateRoute(route, onChange = { changed ->
                            if (changed) requestSuccessor()
                        }, refresh = {
                            if (route == first) {
                                refreshing.complete(Unit)
                                awaitCancellation()
                            }
                            SongUrlFetcher.onNetworkRouteChanged()
                        }, onReady = {})
                    }
                }
                try {
                    routes.value = first
                    withTimeout(3_000L) { refreshing.await() }
                    assertFalse("No successor should see the old cooldown", found.isCompleted)
                    routes.value = second
                    assertNotNull(withTimeout(2_000L) { found.await() })
                    assertEquals(3, count.get())
                } finally {
                    watch.cancelAndJoin()
                    preload?.cancelAndJoin()
                }
            }
        }
    }
}
