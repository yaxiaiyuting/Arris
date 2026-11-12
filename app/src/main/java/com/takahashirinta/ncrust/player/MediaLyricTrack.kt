/*
 * Copyright (c) 2026 yaxiaiyuting.
 * Arris fork contribution, licensed under GPLv3; see LICENSE.
 */

package com.takahashirinta.ncrust.player

import android.content.SharedPreferences
import com.takahashirinta.ncrust.lyric.LrcLine
import kotlin.math.abs

/** Preferences apply only to system media lyrics, independently of in-app translation visibility. */
data class MediaLyricOptions(val bilingual: Boolean = true, val translationFirst: Boolean = false) {
    companion object {
        const val KEY_BILINGUAL = "media_bilingual_lyrics"
        const val KEY_PRIMARY = "media_lyric_primary"
        fun read(prefs: SharedPreferences) = MediaLyricOptions(
            runCatching { prefs.getBoolean(KEY_BILINGUAL, true) }.getOrDefault(true),
            runCatching { prefs.getInt(KEY_PRIMARY, 0) == 1 }.getOrDefault(false),
        )
    }
}

/** One immutable publication prevents original and translation from belonging to different sentences. */
data class MediaLyricFrame(
    val original: String? = null,
    val translation: String? = null,
    val options: MediaLyricOptions = MediaLyricOptions(),
) {
    // Include the secondary text and preferences: late translations/order changes must refresh too.
    val notificationKey: String? get() = original?.takeIf { it.isNotBlank() }?.let {
        "${it.length}:$it${translation?.length ?: -1}:${translation.orEmpty()}:${options.bilingual}:${options.translationFirst}"
    }
}

/** Align once when either track changes, then find the current sentence with a binary search. */
class MediaLyricTrack private constructor(
    private val main: List<LrcLine>,
    private val translations: Array<String?>,
) {
    fun at(positionMs: Long, enabled: Boolean, options: MediaLyricOptions): MediaLyricFrame {
        if (!enabled || positionMs < 0L) return MediaLyricFrame(options = options)
        var lo = 0
        var hi = main.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (main[mid].timeMs <= positionMs) lo = mid + 1 else hi = mid
        }
        val index = lo - 1
        return MediaLyricFrame(main.getOrNull(index)?.text, translations.getOrNull(index), options)
    }

    companion object {
        fun from(main: List<LrcLine>, translated: List<LrcLine>, toleranceMs: Long = 1500L): MediaLyricTrack {
            val sorted = main.sortedBy { it.timeMs }
            if (sorted.isEmpty() || translated.isEmpty()) return MediaLyricTrack(sorted, emptyArray())
            val matched = arrayOfNulls<String>(sorted.size)
            val bestDelta = LongArray(sorted.size)
            // 同一时刻的多行共用译文。采样会显示该时刻的最后一行。
            val lastAtTime = IntArray(sorted.size) { it }
            for (index in sorted.lastIndex - 1 downTo 0) {
                if (sorted[index].timeMs == sorted[index + 1].timeMs) {
                    lastAtTime[index] = lastAtTime[index + 1]
                }
            }
            for (sub in translated) {
                if (sub.text.isBlank()) continue
                var lo = 0
                var hi = sorted.size
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (sorted[mid].timeMs < sub.timeMs) lo = mid + 1 else hi = mid
                }
                val nearest = when {
                    lo == 0 -> 0
                    lo == sorted.size -> sorted.lastIndex
                    abs(sorted[lo - 1].timeMs - sub.timeMs) <= abs(sorted[lo].timeMs - sub.timeMs) -> lo - 1
                    else -> lo
                }
                val owner = lastAtTime[nearest]
                val delta = abs(sorted[owner].timeMs - sub.timeMs)
                if (delta > toleranceMs) continue
                if (matched[owner] == null || delta < bestDelta[owner]) {
                    matched[owner] = sub.text
                    bestDelta[owner] = delta
                }
            }
            return MediaLyricTrack(sorted, matched)
        }
    }
}
