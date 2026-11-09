/* Arris · Copyright (c) 2026 yaxiaiyuting, GPLv3. */
package com.takahashirinta.ncrust.recognize

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.takahashirinta.ncrust.R
import kotlinx.coroutines.*
import java.util.UUID

internal sealed interface PlaybackCaptureResult {
    data class Recorded(val clip: RecogInput.Encoded) : PlaybackCaptureResult
    data class Failed(val reason: RecogFailure) : PlaybackCaptureResult
}

/** Owns a single foreground, audio-only projection session. The screen owns its lifetime. */
internal class PlaybackRecognitionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val request = pending?.takeIf { it.id == intent.getStringExtra(EXTRA_REQUEST_ID) }
        try {
            startForeground(NOTIFICATION_ID, captureNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (_: RuntimeException) {
            request?.result?.complete(PlaybackCaptureResult.Failed(RecogFailure.PERMISSION_DENIED))
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val consent = IntentCompat.getParcelableExtra(intent, EXTRA_CONSENT, Intent::class.java)
        if (request == null || consent == null) {
            request?.result?.complete(PlaybackCaptureResult.Failed(RecogFailure.PERMISSION_DENIED))
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        worker?.cancel()
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                request.result.complete(record(consent))
            } catch (_: ProjectionStopped) {
                request.result.complete(PlaybackCaptureResult.Failed(RecogFailure.PERMISSION_DENIED))
            } catch (_: TimeoutCancellationException) {
                request.result.complete(PlaybackCaptureResult.Failed(RecogFailure.TIMEOUT))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                request.result.complete(PlaybackCaptureResult.Failed(RecogFailure.RECORDER_UNAVAILABLE))
            } finally {
                request.result.complete(PlaybackCaptureResult.Failed(RecogFailure.RECORDER_UNAVAILABLE))
                stopSelfResult(startId)
            }
        }
        worker = task
        request.worker = task
        task.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun captureNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "系统声音识曲", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tux_notification)
            .setContentTitle("正在识别系统声音")
            .setContentText("采集约 10 秒。返回识曲页可停止。")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private suspend fun record(consent: Intent): PlaybackCaptureResult = coroutineScope {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = manager.getMediaProjection(Activity.RESULT_OK, consent)
            ?: return@coroutineScope PlaybackCaptureResult.Failed(RecogFailure.PERMISSION_DENIED)
        val lifetime = coroutineContext.job
        val callback = object : MediaProjection.Callback() {
            override fun onStop() { lifetime.cancel(ProjectionStopped()) }
        }
        val samples = try {
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
            withTimeout(12_500L) { withContext(Dispatchers.IO) { readPlayback(projection) } }
        } finally {
            runCatching { projection.unregisterCallback(callback) }
            runCatching { projection.stop() }
        }
        ensureActive()
        if (!hasPlaybackSignal(samples, SAMPLE_RATE)) {
            PlaybackCaptureResult.Failed(RecogFailure.SILENT_INPUT)
        } else {
            PlaybackCaptureResult.Recorded(PlaybackAacEncoder.encode(applicationContext, samples, SAMPLE_RATE))
        }
    }

    @SuppressLint("MissingPermission") // Both RECORD_AUDIO and projection consent precede this service.
    private suspend fun readPlayback(projection: MediaProjection): ShortArray = coroutineScope {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0)
        val recorder = AudioRecord.Builder().setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum * 2, 8_192))
            .setAudioPlaybackCaptureConfig(config).build()
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            val samples = ShortArray(SAMPLE_RATE * AacRecorder.DEFAULT_DURATION_MS / 1_000)
            var offset = 0
            while (offset < samples.size) {
                ensureActive()
                val count = recorder.read(samples, offset, minOf(4_096, samples.size - offset), AudioRecord.READ_NON_BLOCKING)
                check(count >= 0)
                if (count == 0) delay(8) else offset += count
            }
            samples
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    private class ProjectionStopped : CancellationException()

    private class Request {
        val id: String = UUID.randomUUID().toString()
        val result = CompletableDeferred<PlaybackCaptureResult>()
        var worker: Job? = null
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL = "arris_recognition_capture"
        private const val NOTIFICATION_ID = 9041
        private const val EXTRA_CONSENT = "capture_consent"
        private const val EXTRA_REQUEST_ID = "capture_request_id"

        // Commands and request ownership are serialized on Main. Consent is never persisted or reused.
        private var pending: Request? = null

        suspend fun capture(context: Context, consent: Intent): PlaybackCaptureResult =
            withContext(Dispatchers.Main.immediate) {
                pending?.let {
                    it.worker?.cancel()
                    it.result.cancel()
                }
                val request = Request()
                pending = request
                try {
                    val command = Intent(context, PlaybackRecognitionService::class.java)
                        .putExtra(EXTRA_REQUEST_ID, request.id)
                        .putExtra(EXTRA_CONSENT, consent)
                    ContextCompat.startForegroundService(context, command)
                    withTimeoutOrNull(20_000L) { request.result.await() }
                        ?: PlaybackCaptureResult.Failed(RecogFailure.TIMEOUT)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: RuntimeException) {
                    PlaybackCaptureResult.Failed(RecogFailure.PERMISSION_DENIED)
                } finally {
                    if (pending === request) pending = null
                    request.worker?.cancel()
                }
            }
    }
}
