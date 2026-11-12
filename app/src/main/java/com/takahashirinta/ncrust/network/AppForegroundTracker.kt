/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.network

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** The activity reports its visibility on the main thread. IO diagnostics read the last resume. */
internal class ForegroundInterval {
    @Volatile private var resumedAtMs: Long? = null
    private var backgroundAtMs: Long? = null

    fun background(nowMs: Long) {
        if (backgroundAtMs == null) backgroundAtMs = nowMs
    }

    fun foreground(nowMs: Long): Long? {
        val started = backgroundAtMs
        backgroundAtMs = null
        resumedAtMs = nowMs
        return started?.let { (nowMs - it).coerceAtLeast(0L) }
    }

    fun sinceForegroundMs(nowMs: Long): Long =
        resumedAtMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: Long.MAX_VALUE
}

object AppForegroundTracker {
    const val IDLE_CLEANUP_MS = 10_000L

    private val interval = ForegroundInterval()
    private val _events = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    fun onForeground() {
        interval.foreground(SystemClock.elapsedRealtime())?.let(_events::tryEmit)
    }

    fun onBackground() {
        interval.background(SystemClock.elapsedRealtime())
    }

    fun sinceForegroundMs(): Long = interval.sinceForegroundMs(SystemClock.elapsedRealtime())
}
