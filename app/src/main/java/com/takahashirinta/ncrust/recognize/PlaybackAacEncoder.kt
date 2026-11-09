/* Arris · Copyright (c) 2026 yaxiaiyuting, GPLv3. */
package com.takahashirinta.ncrust.recognize

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.ByteOrder

/** The existing recognition provider accepts an MP4 container with AAC audio. */
internal object PlaybackAacEncoder {
    suspend fun encode(context: Context, samples: ShortArray, sampleRate: Int): RecogInput.Encoded =
        withContext(Dispatchers.IO) {
            withTimeout(6_000L) {
                require(samples.isNotEmpty() && sampleRate > 0)
                val file = File.createTempFile("recog_playback_", ".m4a", context.cacheDir)
                var codec: MediaCodec? = null
                var muxer: MediaMuxer? = null
                var muxing = false
                try {
                    val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    codec = encoder
                    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                    }
                    encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoder.start()

                    val writer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxer = writer
                    val info = MediaCodec.BufferInfo()
                    var cursor = 0
                    var inputEnded = false
                    var outputEnded = false
                    var track = -1
                    while (!outputEnded) {
                        ensureActive()
                        if (!inputEnded) {
                            val inputIndex = encoder.dequeueInputBuffer(5_000)
                            if (inputIndex >= 0) {
                                val buffer = checkNotNull(encoder.getInputBuffer(inputIndex))
                                buffer.clear()
                                val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val count = minOf(shorts.remaining(), samples.size - cursor)
                                check(count > 0 || cursor == samples.size)
                                shorts.put(samples, cursor, count)
                                inputEnded = count == 0
                                encoder.queueInputBuffer(inputIndex, 0, count * Short.SIZE_BYTES,
                                    cursor * 1_000_000L / sampleRate,
                                    if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                                cursor += count
                            }
                        }
                        val outputIndex = encoder.dequeueOutputBuffer(info, 5_000)
                        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            track = writer.addTrack(encoder.outputFormat)
                            writer.start()
                            muxing = true
                        } else if (outputIndex >= 0) {
                            try {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    val buffer = checkNotNull(encoder.getOutputBuffer(outputIndex))
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    writer.writeSampleData(track, buffer, info)
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally {
                                encoder.releaseOutputBuffer(outputIndex, false)
                            }
                        }
                    }
                    ensureActive()
                    check(muxing)
                    writer.stop()
                    muxing = false
                    val bytes = file.readBytes()
                    check(bytes.isNotEmpty())
                    RecogInput.Encoded(bytes, "audio/mp4", samples.size * 1_000L / sampleRate)
                } finally {
                    if (muxing) runCatching { muxer?.stop() }
                    runCatching { muxer?.release() }
                    runCatching { codec?.release() }
                    file.delete()
                }
            }
        }
}

/** Ignore a short impulse or digital silence before creating an uploadable clip. */
internal fun hasPlaybackSignal(samples: ShortArray, sampleRate: Int): Boolean {
    val requiredSamples = (sampleRate / 10).coerceAtLeast(1)
    var audibleSamples = 0
    for (sample in samples) {
        if (sample >= 32 || sample <= -32) {
            audibleSamples++
            if (audibleSamples >= requiredSamples) return true
        }
    }
    return false
}
