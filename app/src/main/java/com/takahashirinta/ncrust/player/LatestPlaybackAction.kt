/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import kotlinx.coroutines.*

/** Advance the selection immediately; perform only the final request after a tap burst. */
internal class LatestPlaybackAction(
    private val scope: CoroutineScope,
    private val begin: () -> Int,
    private val isCurrent: (Int) -> Boolean,
    private val settleMs: Long = 220L,
) {
    private var job: Job? = null
    val hasPending: Boolean get() = job?.isActive == true
    fun cancel() { job?.cancel(); job = null }
    fun submit(action: () -> Unit) {
        cancel()
        val epoch = begin()
        job = scope.launch {
            delay(settleMs)
            ensureActive()
            if (isCurrent(epoch)) { job = null; action() }
        }
    }
}
