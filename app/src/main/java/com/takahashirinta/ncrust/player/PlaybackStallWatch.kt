/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

/** Reports once per gap in playback progress. While buffering, downloaded media counts as progress. */
internal class PlaybackStallWatch(private val timeoutMs: Long) {
    private var progressAtMs: Long? = null
    private var positionMs = 0L
    private var bufferedMs: Long? = null
    private var reported = false

    fun reset() {
        progressAtMs = null
        reported = false
    }

    fun observe(nowMs: Long, positionMs: Long, bufferedMs: Long?): Boolean {
        val previousAtMs = progressAtMs
        val previousBuffer = this.bufferedMs
        val advanced = positionMs > this.positionMs ||
            (bufferedMs != null && previousBuffer != null && bufferedMs > previousBuffer)
        this.positionMs = positionMs
        this.bufferedMs = bufferedMs
        if (previousAtMs == null || advanced) {
            progressAtMs = nowMs
            reported = false
            return false
        }
        if (reported || nowMs - previousAtMs < timeoutMs) return false
        reported = true
        return true
    }
}
