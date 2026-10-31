/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.network

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Keep cancellation attached through the JSON body, not just until headers arrive. */
internal suspend fun Call.awaitBufferedResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            // OkHttp wraps an interceptor CancellationException in an IOException and
            // preserves it as a cause/suppressed exception. Keep it as cancellation.
            val cancellation = (listOfNotNull(e.cause) + e.suppressed)
                .filterIsInstance<kotlinx.coroutines.CancellationException>().firstOrNull()
            if (cont.isActive) cont.resumeWith(Result.failure(cancellation ?: e))
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val buffered = response.use {
                    val body = it.body
                    it.newBuilder().body(body?.bytes()?.toResponseBody(body.contentType())).build()
                }
                // The returned body owns only memory; the socket is already closed/released.
                if (cont.isActive) cont.resumeWith(Result.success(buffered)) else buffered.close()
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }
        }
    })
}
