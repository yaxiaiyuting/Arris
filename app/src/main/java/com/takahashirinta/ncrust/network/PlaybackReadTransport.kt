/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.network

import java.io.IOException
import java.io.InterruptedIOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Response

/**
 * Only for the read-only song-URL POST. Never replay writes or an explicitly cancelled call.
 *
 * @param h2First v4.1.10：`false` 表示这个 host 最近用 h2 被对端复位过
 *   （[StreamResetMemo]，5 分钟 TTL），直接走 HTTP/1.1 池 —— 省掉一次必然失败的
 *   连接 + 一次 `RST_STREAM`。默认 `true`，即「没学过就别预判」。
 */
internal suspend fun Call.awaitPlaybackRead(h2First: Boolean = true, http1: () -> Call): Response {
    if (!h2First) {
        // 已学到的坏路径：一次往返就够，不再先撞一次 h2。失败原样抛给上层的有界重试。
        return http1().awaitBufferedResponse()
    }
    try {
        return awaitBufferedResponse()
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        // Avoid depending on OkHttp internal exception classes (also renamed by R8).
        val reset = StreamResetMemo.isStreamReset(e)
        // OkHttp's call deadline marks Call cancelled too, while its coroutine is still live.
        // User cancellation is different: ensureActive above throws, or the error is "Canceled".
        val timedOut = e is InterruptedIOException && e.message == "timeout"
        if ((!timedOut && isCanceled()) || (!reset && !timedOut)) throw e
        // A separate HTTP/1.1 pool cannot reacquire the still-active broken h2 connection.
        // Exactly one fallback; its failure is handed back to the existing bounded retry gate.
        if (reset) StreamResetMemo.record(request().url.host, AddressPreference.nowMs())
        return http1().awaitBufferedResponse()
    }
}
