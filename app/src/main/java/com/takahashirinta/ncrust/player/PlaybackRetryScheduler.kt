/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Main-thread owned, single pending retry. A retry must still own the original play intent. */
internal class PlaybackRetryScheduler<T>(
    private val scope: CoroutineScope,
    private val isCurrent: (T) -> Boolean,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private var pending: Job? = null
    private var epoch = 0L
    val hasPending: Boolean get() = pending?.isActive == true

    fun cancel() {
        epoch++
        pending?.cancel()
        pending = null
    }

    fun schedule(owner: T, delayMs: Long, retry: (T) -> Unit): Boolean {
        if (hasPending) return false
        val ticket = epoch
        val job = scope.launch(start = CoroutineStart.LAZY) {
            wait(delayMs.coerceAtLeast(0))
            currentCoroutineContext().ensureActive()
            if (ticket == epoch && isCurrent(owner)) {
                // playSong invalidates retries too; never cancel our own dispatch halfway through.
                pending = null
                retry(owner)
            }
        }
        pending = job
        job.start()
        return true
    }
}
