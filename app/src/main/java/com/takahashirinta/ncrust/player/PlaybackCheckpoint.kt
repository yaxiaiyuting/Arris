/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import kotlin.math.abs

/** A new media item starts its own checkpoint interval at its actual starting position. */
internal class PlaybackCheckpoint {
    private var previousMs = 0L

    fun reset(positionMs: Long) {
        previousMs = positionMs
    }

    fun record(positionMs: Long, explicit: Boolean = false): Boolean {
        if (positionMs < 0L || !explicit && abs(positionMs - previousMs) < 5_000L) return false
        previousMs = positionMs
        return true
    }
}
