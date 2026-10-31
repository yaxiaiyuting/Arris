package com.takahashirinta.ncrust.network

import java.io.IOException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

/** Actual async calls answered only by local interceptors, with no sockets or credentials. */
class PlaybackReadTransportTest {
    private val request = Request.Builder().url("https://fixture.invalid/eapi/song/enhance/player/url/v1")
        .post(FormBody.Builder().add("params", "fixture").build()).build()

    private fun client(failure: String? = null) = OkHttpClient.Builder().addInterceptor { chain ->
        if (failure != null) throw IOException(failure)
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
            .message("local fixture").body("{\"code\":200}".toResponseBody()).build()
    }.build()

    @Test fun peerResetsUseOneFallbackWithTheOriginalReadRequest() = runBlocking {
        for (code in listOf("REFUSED_STREAM", "CANCEL")) {
            val primary = client("stream was reset: $code"); val fallback = client(); var count = 0
            try {
                primary.newCall(request).awaitPlaybackRead { count++; fallback.newCall(request) }.use {
                    assertEquals(200, it.code)
                    assertSame(request, it.request)
                    assertEquals("{\"code\":200}", it.body!!.string())
                }
                assertEquals(1, count)
            } finally {
                primary.dispatcher.executorService.shutdown(); fallback.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun ordinaryFailuresAreNotProtocolFallbacks() = runBlocking {
        for (reason in listOf("timeout", "Unable to resolve host", "Canceled", "connection reset")) {
            val primary = client(reason); var count = 0
            try {
                val failure = runCatching { primary.newCall(request).awaitPlaybackRead { count++; error("unexpected fallback") } }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertEquals(0, count)
            } finally { primary.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun failedFallbackDoesNotLoop() = runBlocking {
        val primary = client("stream was reset: REFUSED_STREAM")
        val fallback = client("stream was reset: CANCEL"); var count = 0
        try {
            assertTrue(runCatching { primary.newCall(request).awaitPlaybackRead { count++; fallback.newCall(request) } }.exceptionOrNull() is IOException)
            assertEquals(1, count)
        } finally {
            primary.dispatcher.executorService.shutdown(); fallback.dispatcher.executorService.shutdown()
        }
    }

    @Test fun successfulPrimaryDoesNotReplay() = runBlocking {
        val primary = client()
        try { primary.newCall(request).awaitPlaybackRead { error("unexpected fallback") }.close() }
        finally { primary.dispatcher.executorService.shutdown() }
    }

    @Test fun explicitCancellationNeverFallsBack() = runBlocking {
        val primary = client("stream was reset: CANCEL"); val call = primary.newCall(request)
        var count = 0
        try {
            call.cancel()
            runCatching { call.awaitPlaybackRead { count++; error("unexpected fallback") } }
            assertEquals(0, count)
        } finally { primary.dispatcher.executorService.shutdown() }
    }

    @Test fun callDeadlineCanRecoverEvenWhenOkHttpMarksTheCallCancelled() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val primary = OkHttpClient.Builder().callTimeout(20, java.util.concurrent.TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                entered.countDown(); release.await()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("late local fixture").body("old".toResponseBody()).build()
            }.build()
        val fallback = client(); val call = primary.newCall(request); var count = 0
        val unblock = launch(Dispatchers.IO) {
            entered.await(2, java.util.concurrent.TimeUnit.SECONDS)
            delay(100); release.countDown()
        }
        try {
            withTimeout(2000) {
                call.awaitPlaybackRead { count++; fallback.newCall(request) }.use { assertEquals(200, it.code) }
            }
            assertTrue(call.isCanceled())
            assertEquals(1, count)
        } finally {
            release.countDown(); unblock.cancelAndJoin()
            primary.dispatcher.executorService.shutdown(); fallback.dispatcher.executorService.shutdown()
        }
    }

    @Test fun cancellingTheCoroutineDoesNotTriggerFreshPlaybackRead() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val primary = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown(); release.await()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("local").body("old".toResponseBody()).build()
        }.build()
        var count = 0
        try {
            val pending = async { primary.newCall(request).awaitPlaybackRead { count++; error("unexpected fallback") } }
            yield()
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, java.util.concurrent.TimeUnit.SECONDS) })
            pending.cancelAndJoin()
            assertEquals(0, count)
        } finally { release.countDown(); primary.dispatcher.executorService.shutdown() }
    }
}
