/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import kotlinx.coroutines.*

/** UI scope owns commits. A user intent takes its epoch before any network suspension. */
internal class PendingPlayRequests(
    private val scope: CoroutineScope,
    private val begin: () -> Int,
    private val current: (Int) -> Boolean,
) {
    private var job: Job? = null
    fun <T> launch(load: suspend () -> T, commit: (T) -> Unit): Job {
        val epoch = begin()
        job?.cancel()
        return scope.launch {
            val result = try { load() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.w("PendingPlayRequests", "playlist load failed", e); return@launch }
            ensureActive()
            if (current(epoch)) commit(result)
        }.also { job = it }
    }
}
