/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.network

import java.io.EOFException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Keep cancellation attached through the JSON body, not just until headers arrive. */
internal suspend fun Call.awaitBufferedResponse(
    bufferBody: (Response) -> Boolean = { true },
): Response = awaitResponseRead { response ->
    val body = response.body?.takeIf { bufferBody(response) }
    val memoryBody = if (body == null) ByteArray(0).toResponseBody() else body.bytes().toResponseBody(body.contentType())
    response.newBuilder().body(memoryBody).build()
}

/** Discard a small warmup response with bounded memory and no text decoding. */
internal suspend fun Call.awaitWarmupResponse() = awaitResponseRead { response ->
    try {
        response.body?.source()?.skip(64L * 1024L)
    } catch (_: EOFException) {
        // A short response was drained and its connection can return to the pool.
    }
    Unit
}

/** The result owns only memory. The socket is already closed or back in the pool. */
private suspend fun <T> Call.awaitResponseRead(read: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            // OkHttp wraps an interceptor CancellationException in an IOException and
            // preserves it as a cause/suppressed exception. Keep it as cancellation.
            val cancellation = e.cause as? CancellationException
                ?: e.suppressed.firstNotNullOfOrNull { it as? CancellationException }
            if (cont.isActive) cont.resumeWith(Result.failure(cancellation ?: e))
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use(read)
                if (cont.isActive) cont.resumeWith(Result.success(result))
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }
        }
    })
}
