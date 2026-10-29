package com.takahashirinta.ncrust.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

/** All requests are answered by an application interceptor; no socket or real account. */
class BufferedHttpCallTest {
    @Test fun cancellationRemainsAttachedAfterHeadersWhileBodyIsStalled() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = object : ResponseBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = -1L
                override fun source(): BufferedSource = object : Source {
                    override fun timeout() = Timeout.NONE
                    override fun close() { closed.set(true) }
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        entered.countDown(); release.await(); return -1L
                    }
                }.buffer()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("local fixture").body(body).build()
        }.build()
        val call = client.newCall(Request.Builder().url("https://fixture.invalid/body").build())
        try {
            val job = async { call.awaitBufferedResponse().use { it.body?.string() } }
            yield()
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            assertFalse("Headers alone must not complete the await", job.isCompleted)
            job.cancel()
            withTimeout(1000) { job.join() }
            assertTrue(call.isCanceled())
            // A new song can resolve while the obsolete response is still stalled.
            val newClient = OkHttpClient.Builder().addInterceptor { c ->
                Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("local").body("new song".toResponseBody()).build()
            }.build()
            try {
                val new = newClient.newCall(Request.Builder().url("https://fixture.invalid/new").build())
                assertEquals("new song", withTimeout(1000) { new.awaitBufferedResponse().use { it.body!!.string() } })
            } finally { newClient.dispatcher.executorService.shutdown() }
        } finally {
            release.countDown()
            client.dispatcher.executorService.shutdown()
            client.dispatcher.executorService.awaitTermination(2, TimeUnit.SECONDS)
        }
        assertTrue(closed.get())
    }
    @Test fun interceptorCancellationIsNotConvertedToABusinessOrNetworkFailure() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { throw CancellationException("local cancel") }.build()
        try {
            val failure = runCatching {
                client.newCall(Request.Builder().url("https://fixture.invalid/cancel").build())
                    .awaitBufferedResponse()
            }.exceptionOrNull()
            assertTrue("actual $failure", failure is CancellationException)
        } finally { client.dispatcher.executorService.shutdown() }
    }

}
