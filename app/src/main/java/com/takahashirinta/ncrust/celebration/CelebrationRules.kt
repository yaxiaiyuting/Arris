package com.takahashirinta.ncrust.celebration

/** Calendar inputs are explicit so timezone changes and leap-day policy are testable. */
object BirthdayRule {
    fun valid(month: Int, day: Int): Boolean = month in 1..12 && day in 1..days(month)
    fun days(month: Int): Int = when (month) { 2 -> 29; 4, 6, 9, 11 -> 30; in 1..12 -> 31; else -> 0 }
    fun matches(month: Int, day: Int, year: Int, todayMonth: Int, todayDay: Int): Boolean {
        if (!valid(month, day)) return false
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val observed = if (month == 2 && day == 29 && !leap) 28 else day
        return todayMonth == month && todayDay == observed
    }
    fun eligible(month: Int, day: Int, enabled: Boolean, year: Int, todayMonth: Int, todayDay: Int, shown: Set<String>): Boolean =
        enabled && year.toString() !in shown && matches(month, day, year, todayMonth, todayDay)
}

/** Uses elapsedRealtime, never wall clock. The successful gate remains locked until reset. */
class VersionTapGate {
    var count = 0; private set
    private var first = 0L
    private var last = 0L
    fun tap(now: Long): Boolean {
        if (count == 7) return false
        if (count == 0 || now < last || now - last > 1200 || now - first > 6000) { count = 0; first = now }
        last = now
        count++
        return count == 7
    }
    fun reset() { count = 0; first = 0; last = 0 }
}

/** A sustained broadband puff after a short noise-floor calibration, not a loudness-only trigger. */
class BlowGate {
    private var calibratedMs = 0
    private var floor = .004
    private val calibration = DoubleArray(32)
    private var calibrationCount = 0
    private var sustainedMs = 0
    private var gapMs = 0
    private var detected = false
    val ready: Boolean get() = calibratedMs >= 350
    fun sample(rms: Double, roughness: Double, crossings: Double, durationMs: Int, crestFactor: Double = 3.0): Boolean {
        if (detected || !rms.isFinite() || !roughness.isFinite() || !crossings.isFinite() || !crestFactor.isFinite() || durationMs <= 0) return false
        if (!ready) {
            if (calibrationCount < calibration.size) calibration[calibrationCount++] = rms.coerceAtLeast(0.0)
            calibratedMs += durationMs
            if (ready) {
                calibration.sort(0,calibrationCount)
                // A short startup puff must not become an inflated background-noise baseline.
                floor=calibration[(calibrationCount-1)/5].coerceIn(.0003,.02)
            }
            return false
        }
        val threshold=maxOf(.004, floor*1.45+.0015)
        // Wind often has far more low-frequency energy than the original high-ZCR gate allowed.
        // Crest factor rejects steady tones without requiring a harsh, high-frequency blast.
        val noiseLike=crestFactor>1.65 || rms>.22 && roughness>.05
        val puff=rms>threshold && roughness>.003 && crossings>.001 && noiseLike
        val dt=durationMs.coerceAtMost(40)
        if(puff) {sustainedMs+=dt;gapMs=0} else {
            gapMs+=dt
            if(gapMs>40)sustainedMs=0
            if(rms<floor)floor=floor*.8+rms*.2
        }
        if (sustainedMs >= 100) detected = true
        return detected
    }
}

enum class CelebrationKind { BIRTHDAY, LISTENER }
enum class CelebrationPhase { ARRIVING, PLACING, LIGHTING, WAITING, EXTINGUISHING, BLACKOUT, FIREWORKS, BLESSING, DONE }

object CelebrationTiming {
    const val ARRIVE = 1650L
    const val PLACE = 700L
    const val LIGHT = 1600L
    const val EXTINGUISH = 1100L
    const val BLACKOUT = 1000L
    const val SHOW = 6600L
    const val REVEAL = 2300L
    const val MIC_WINDOW = 8000L
}
