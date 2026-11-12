/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui.player.motion

import kotlin.math.abs
import kotlin.math.exp

/** Samples the existing player clock at no more than 30 Hz. This owns no loop or audio input. */
internal class BreathingMotion {
    var phase = 0f
        private set
    var spread = 0f
        private set
    var shimmer = 0f
        private set
    var fluidTooSlow = false
        private set
    private var sinceDraw = 0f
    private var slowFrames = 0
    val hasEnergy: Boolean get() = spread != 0f || shimmer != 0f

    fun advance(dtMs: Float, active: Boolean, available: Boolean, low: Float, mid: Float, high: Float): Boolean {
        val live = active && available
        slowFrames = if (!live) 0 else if (dtMs > 54f) slowFrames + 1 else (slowFrames - 1).coerceAtLeast(0)
        val fellBack = slowFrames >= 8 && !fluidTooSlow
        if (fellBack) fluidTooSlow = true
        if (!live && !hasEnergy) {
            sinceDraw = 0f
            return false
        }
        sinceDraw += dtMs
        if (sinceDraw < 1000f / 30f && !fellBack) return false
        val step = sinceDraw.coerceAtMost(120f)
        sinceDraw = 0f
        if (live) phase = (phase + step * 0.00023f) % 6.2831855f
        spread = approach(spread, if (live) low.coerceIn(0f, 1f) else 0f, step)
        shimmer = approach(shimmer, if (live) (mid * 0.72f + high * 0.28f).coerceIn(0f, 1f) else 0f, step)
        return true
    }

    fun reset() {
        phase = 0f
        spread = 0f
        shimmer = 0f
        sinceDraw = 0f
        slowFrames = 0
        fluidTooSlow = false
    }

    private fun approach(previous: Float, target: Float, dtMs: Float): Float {
        val tau = if (target > previous) 190f else 520f
        val next = target + (previous - target) * exp(-dtMs / tau)
        return if (abs(next - target) < 0.002f) target else next
    }
}
