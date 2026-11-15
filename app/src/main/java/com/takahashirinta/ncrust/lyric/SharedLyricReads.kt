/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.lyric

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Overlapping readers share one request. The last departing reader cancels unfinished work. */
internal class SharedLyricReads<K, V> {
    private class Request<V>(val result: Deferred<V>, var readers: Int = 0)

    private val requests = HashMap<K, Request<V>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun read(key: K, load: suspend () -> V): V {
        val request = synchronized(requests) {
            requests.getOrPut(key) { Request(scope.async(start = CoroutineStart.LAZY) { load() }) }
                .also { it.readers++ }
        }
        try {
            return request.result.await()
        } finally {
            val last = synchronized(requests) {
                (--request.readers == 0).also { if (it) requests.remove(key) }
            }
            if (last) request.result.cancel()
        }
    }

    @VisibleForTesting
    fun readerCount(key: K): Int = synchronized(requests) { requests[key]?.readers ?: 0 }
}
