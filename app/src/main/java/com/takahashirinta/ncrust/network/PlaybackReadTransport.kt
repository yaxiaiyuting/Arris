/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.network

import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Response

/** Only for the read-only song-URL POST. Never replay writes or an explicitly cancelled call. */
internal suspend fun Call.awaitPlaybackRead(http1: () -> Call): Response {
    try {
        return awaitBufferedResponse()
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        // Avoid depending on OkHttp internal exception classes (also renamed by R8).
        val reset = e.message in setOf("stream was reset: REFUSED_STREAM", "stream was reset: CANCEL")
        // OkHttp's call deadline marks Call cancelled too, while its coroutine is still live.
        // User cancellation is different: ensureActive above throws, or the error is "Canceled".
        val timedOut = e is InterruptedIOException && e.message == "timeout"
        if ((!timedOut && isCanceled()) || (!reset && !timedOut)) throw e
        // A separate HTTP/1.1 pool cannot reacquire the still-active broken h2 connection.
        // Exactly one fallback; its failure is handed back to the existing bounded retry gate.
        return http1().awaitBufferedResponse()
    }
}
