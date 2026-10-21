package com.takahashirinta.ncrust.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-local pacing. Wall-clock adjustments cannot lengthen this bounded interval. */
internal class MonotonicWriteGate(
    private val now: () -> Long,
    private val sleep: suspend (Long) -> Unit,
    private val intervalMs: Long = 2_000L,
) {
    private val mutex = Mutex()
    private var lastCompleted: Long? = null
    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val time=now()
        val wait=lastCompleted?.let { previous ->
            if(time<previous)0L else (intervalMs-(time-previous)).coerceIn(0L,intervalMs)
        } ?: 0L
        if(wait>0)sleep(wait)
        try { block() } finally { lastCompleted=now() }
    }
}
