package com.takahashirinta.ncrust.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.AudioTrack
import java.lang.ref.WeakReference
import kotlin.math.pow
import kotlin.math.sqrt

/** Reads the media stream on the routed track. Sampling happens off the audio and UI threads. */
internal class PlaybackVisualVolume(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Volatile
    private var track: WeakReference<AudioTrack>? = null

    fun observeTrack(audioTrack: AudioTrack) {
        track = WeakReference(audioTrack)
    }

    // AudioTrack routes to outputs. Some OEMs report an unsupported type, handled by the fallback.
    @SuppressLint("WrongConstant")
    fun sample(playerGain: Float): Float {
        val manager = audioManager ?: return visualGain(playerGain, 1, 1, null)
        val stream = AudioManager.STREAM_MUSIC
        return runCatching {
            val index = manager.getStreamVolume(stream)
            if (index == 0 || manager.isStreamMute(stream)) return 0f
            val max = manager.getStreamMaxVolume(stream)
            val routeDb = runCatching route@{
                val device = track?.get()?.routedDevice ?: return@route null
                if (!device.isSink) return@route null
                manager.getStreamVolumeDb(stream, index, device.type) -
                    manager.getStreamVolumeDb(stream, max, device.type)
            }.getOrNull()
            visualGain(playerGain, index, max, routeDb)
        }.getOrElse { visualGain(playerGain, 1, 1, null) }
    }

    companion object {
        /** Compress the visual scale so quiet media still has readable detail. */
        internal fun visualGain(playerGain: Float, index: Int, max: Int, routeDb: Float?): Float {
            if (index <= 0 || !playerGain.isFinite()) return 0f
            val streamGain = when {
                routeDb == Float.NEGATIVE_INFINITY -> 0.0
                routeDb != null && routeDb.isFinite() -> 10.0.pow(routeDb.coerceAtMost(0f) / 20.0)
                else -> (index.toDouble() / max.coerceAtLeast(1)).coerceIn(0.0, 1.0)
            }
            return sqrt(playerGain.coerceIn(0f, 1f) * streamGain).toFloat()
        }
    }
}
