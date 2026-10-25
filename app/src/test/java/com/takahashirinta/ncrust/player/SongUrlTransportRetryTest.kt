package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SongUrlTransportRetryTest {
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private val song = SongItem(987654321, "fixture", null, null, 200000)
    private fun intercept(answer: (Int) -> String, block: suspend (AtomicInteger, AtomicLong) -> Unit) = runBlocking {
        val delegate = field(RetrofitClient, "plainClient\$delegate").get(RetrofitClient) as Lazy<*>
        val original = delegate.value
        val value = delegate.javaClass.getDeclaredField("_value").apply { isAccessible = true }
        val cooldown = field(SongUrlFetcher, "transportCooldownUntil").get(SongUrlFetcher) as AtomicLong
        val failures = field(SongUrlFetcher, "transportFailures").get(SongUrlFetcher) as MutableSet<*>
        val count = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(answer(count.incrementAndGet()).toResponseBody()).build()
        }.build()
        cooldown.set(0); failures.clear(); value.set(delegate, client)
        SourceRouter.register(NeteaseSourceProvider)
        try { block(count, cooldown) }
        finally {
            value.set(delegate, original); cooldown.set(0); failures.clear()
            client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun `second IOException remains NETWORK and subsequent attempt is cooled down`() =
        intercept({ throw IOException("fixture offline") }) { count, cooldown ->
            val result = SourceRouter.resolveUrlOutcome(song, "standard")
            assertEquals(ResolveFailureKind.NETWORK, result.failure?.kind)
            assertTrue(SongUrlFetcher.lastAttemptFailedOnTransport(song.id))
            assertTrue(cooldown.get() > System.nanoTime() / 1_000_000)
            assertEquals(2, count.get())
            assertNull(SongUrlFetcher.fetch(song.id, "standard"))
            assertEquals(2, count.get())
        }

    @Test fun `successful transport retry returns a playable result without cooldown`() =
        intercept({ attempt -> if (attempt == 1) throw IOException("fixture reset") else
            """{"data":[{"id":987654321,"code":200,"url":"https://fixture.invalid/audio.mp3","level":"standard","br":128000,"type":"mp3"}]}"""
        }) { count, cooldown ->
            assertNotNull(SongUrlFetcher.fetch(song.id, "standard"))
            assertEquals(2, count.get())
            assertEquals(0L, cooldown.get())
        }

    @Test fun `business rejection after transport retry keeps its own classification`() =
        intercept({ attempt -> if (attempt == 1) throw IOException("fixture reset") else
            """{"data":[{"id":987654321,"code":404,"url":null,"level":"standard"}]}"""
        }) { count, cooldown ->
            val result = SourceRouter.resolveUrlOutcome(song, "standard")
            assertEquals(ResolveFailureKind.UNKNOWN, result.failure?.kind)
            assertFalse(SongUrlFetcher.lastAttemptFailedOnTransport(song.id))
            assertEquals(0L, cooldown.get())
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
                assertEquals(0L, cooldown.get())
                release.countDown()
            }
        } finally { release.countDown() }
    }
}
