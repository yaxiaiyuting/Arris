package com.takahashirinta.ncrust.ui.player.waveform

/** 三条泳道按格取值，共用同一个水平位移，保留每根柱的形状。 */
object BandScroll {
    fun fillLane(
        window: FloatArray,
        out: FloatArray,
        base: Int,
        count: Int,
        lane: Int,
        heightPx: Float,
        minBar: Float,
    ) {
        val minHalf = minBar * 0.5f
        val length = minOf(count, out.size - base)
        for (i in 0 until length) {
            val half = BandLanes.amplitude(window.getOrElse(i) { 0f }, lane) * heightPx * 0.5f
            out[base + i] = if (half < minHalf) minHalf else half
        }
    }

    fun leftShiftPx(phase: Float, step: Float): Float {
        val fraction = if (phase.isFinite()) phase.coerceIn(0f, 1f) else 0f
        return -fraction * step
    }

    /** 左移后在右侧延伸一格，避免可见区域露空。 */
    const val EXTRA_POINTS = 1
}
