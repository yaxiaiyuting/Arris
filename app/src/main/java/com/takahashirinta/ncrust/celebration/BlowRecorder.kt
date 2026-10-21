package com.takahashirinta.ncrust.celebration

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import kotlin.math.abs

enum class BlowResult { DETECTED, TIMEOUT, UNAVAILABLE }

/** Foreground UI owns cancellation. No file, upload, audio-focus request, SCO or output-route change. */
object BlowRecorder {
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun listen(context: Context, onArmed: () -> Unit = {}): BlowResult = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@withContext BlowResult.UNAVAILABLE
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return@withContext BlowResult.UNAVAILABLE
        var record: AudioRecord? = null
        try {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val source=if(manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)=="true")MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
            record = AudioRecord(source, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
            if (record.state != AudioRecord.STATE_INITIALIZED) return@withContext BlowResult.UNAVAILABLE
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let { record.setPreferredDevice(it) }
            val samples = ShortArray(320)
            val gate = BlowGate()
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext BlowResult.UNAVAILABLE
            val start = SystemClock.elapsedRealtime()
            var armed = false
            while (currentCoroutineContext().isActive && SystemClock.elapsedRealtime() - start < CelebrationTiming.MIC_WINDOW) {
                val count = record.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                if (count < 0) return@withContext BlowResult.UNAVAILABLE
                if (count < 2) { delay(10); continue }
                var power = 0.0; var difference = 0.0; var crossings = 0; var peak = 0.0
                for (i in 0 until count) {
                    val value = samples[i] / 32768.0
                    power += value * value
                    peak=maxOf(peak,abs(value))
                    if (i > 0) {
                        val prior = samples[i - 1] / 32768.0
                        difference += (value - prior) * (value - prior)
                        if ((value > 0) != (prior > 0)) crossings++
                    }
                }
                val rms=sqrt(power/count)
                if (gate.sample(rms, difference / maxOf(power, .000001), crossings.toDouble() / count, count * 1000 / 16000,peak/maxOf(rms,.000001))) return@withContext BlowResult.DETECTED
                if (!armed && gate.ready) { armed = true; withContext(Dispatchers.Main) { onArmed() } }
                delay(5)
            }
            currentCoroutineContext().ensureActive()
            BlowResult.TIMEOUT
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
        } catch (_: Exception) { BlowResult.UNAVAILABLE
        } finally {
            record?.let { r -> runCatching { r.stop() }; runCatching { r.release() } }
        }
    }
}
