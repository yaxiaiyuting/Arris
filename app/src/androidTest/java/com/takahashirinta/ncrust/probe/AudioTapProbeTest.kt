/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.0.0 · 探针：**音频旁路（TeeAudioProcessor → AudioBufferSink）的缓冲粒度实测**。
 *
 * ## 为什么必须实测而不是推断
 *
 * `TeeAudioProcessor.queueInput` 逐字是「把上游交过来的 ByteBuffer 原样转给 sink」
 * （1.5.0 字节码：`remaining()` → `handleBuffer(createReadOnlyByteBuffer(buf))` → `replaceOutputBuffer(n)`），
 * **它自己不切块、不缓冲**。所以 sink 每次收到的帧数 = 上游（解码器 / DefaultAudioSink）交过来的块大小，
 * 而那是**运行时行为**，代码里没有任何常量能回答「一次回调多少帧、每秒回调多少次」。
 *
 * 这个数字直接决定 v3.0.0 的瞬态检测器能不能用「每缓冲一次判定」：
 *  - 若一次回调 4096 帧（44.1kHz 约 93ms）⇒ 每秒只有 ~11 次判定，快鼓点必然漏；
 *  - 若一次回调 1024 帧（约 23ms）⇒ 每秒 ~43 次，够用。
 * 两者都是真实可能，**不许猜**。
 *
 * ## 它怎么测（不碰生产代码）
 *
 * 自建一个 44.1kHz 立体声 16bit 的 WAV（正弦 + 周期性低频脉冲串）写进 cache 目录，
 * 用挂了同一个 [TeeAudioProcessor] 的 ExoPlayer 播放它，在 sink 里
 * **用预分配的 IntArray 直方图统计帧数**（零分配、不加锁），播完后一次性 dump。
 *
 * 这是**探针**，不是单测：它不参与 `testDebugUnitTest`，只在
 * `connectedAndroidTest` 里跑（需要设备）。
 */

package com.takahashirinta.ncrust.probe

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AudioTapProbeTest {

    /** 预分配的直方图桶：帧数 ≤ 16384 时按 256 帧归档，超出进最后一桶。 */
    private val bucketCount = 64

    private class Tap {
        val histogram = IntArray(64)
        var callbacks = 0
        var totalFrames = 0L
        var emptyCallbacks = 0
        @Volatile var sampleRate = 0
        @Volatile var channelCount = 0
        @Volatile var encoding = C.ENCODING_INVALID
        @Volatile var firstCallbackNanos = 0L
        @Volatile var lastCallbackNanos = 0L
        var bytesPerSample = 2
    }

    private class ProbeSink(private val tap: Tap) : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            tap.sampleRate = sampleRateHz
            tap.channelCount = channelCount
            tap.encoding = encoding
            tap.bytesPerSample = when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_FLOAT -> 4
                else -> 0
            }
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            val remaining = buffer.remaining()
            if (remaining <= 0) {
                tap.emptyCallbacks++
                return
            }
            val bps = tap.bytesPerSample
            val channels = if (tap.channelCount > 0) tap.channelCount else 1
            val frames = if (bps == 0) 0 else remaining / bps / channels
            val bucket = (frames / 256).coerceIn(0, 63)
            tap.histogram[bucket]++
            tap.callbacks++
            tap.totalFrames += frames
            val now = System.nanoTime()
            if (tap.firstCallbackNanos == 0L) tap.firstCallbackNanos = now
            tap.lastCallbackNanos = now
        }
    }

    private fun writeWav(file: File, sampleRate: Int, channels: Int, seconds: Double) {
        val totalFrames = (sampleRate * seconds).toInt()
        val dataBytes = totalFrames * channels * 2
        val out = FileOutputStream(file)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)                       // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * 2) // byte rate
        header.putShort((channels * 2).toShort())// block align
        header.putShort(16)                      // bits
        header.put("data".toByteArray())
        header.putInt(dataBytes)
        out.write(header.array())

        // 内容：220Hz 正弦 + 每 500ms 一次 60Hz 低频脉冲（模拟底鼓）+ 每 250ms 一次 4kHz 高频脉冲（模拟镲片）。
        val pcm = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
        var frame = 0
        while (frame < totalFrames) {
            val t = frame.toDouble() / sampleRate
            var v = 0.18 * sin(2 * PI * 220.0 * t)
            val kickPhase = t % 0.5
            if (kickPhase < 0.06) {
                v += 0.55 * sin(2 * PI * 60.0 * t) * (1.0 - kickPhase / 0.06)
            }
            val hatPhase = t % 0.25
            if (hatPhase < 0.02) {
                v += 0.25 * sin(2 * PI * 4000.0 * t) * (1.0 - hatPhase / 0.02)
            }
            val s = (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
            repeat(channels) { pcm.putShort(s) }
            if (pcm.remaining() < channels * 2) {
                out.write(pcm.array(), 0, pcm.position())
                pcm.clear()
            }
            frame++
        }
        if (pcm.position() > 0) out.write(pcm.array(), 0, pcm.position())
        out.close()
    }

    private fun runProbe(label: String, sampleRate: Int, channels: Int): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File(context.cacheDir, "probe-$label.wav")
        if (!wav.exists() || wav.length() < 1024) {
            writeWav(wav, sampleRate, channels, seconds = 6.0)
        }

        val tap = Tap()
        val sink = ProbeSink(tap)
        val tee = TeeAudioProcessor(sink)
        val factory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(tee))
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }

        val player = ExoPlayer.Builder(context, factory).build()
        val started = System.nanoTime()
        // ExoPlayer 必须在**它自己的应用线程**（这里是 main）上访问 ——
        // 仪器测试默认跑在 `Instr: AndroidJUnitRunner` 线程上，直接调会抛
        // `IllegalStateException: Player is accessed on the wrong thread`。
        val main = InstrumentationRegistry.getInstrumentation()
        try {
            main.runOnMainSync {
                player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(wav)))
                player.prepare()
                player.play()
            }
            // 有界等待：最多 15s，或者已经收集够 4 秒的回调。
            // ⚠️ 这里**只能读 tap 的字段**：`player.isPlaying` 也是「应用线程专属」的访问，
            // 在仪器线程上读会抛同一个 IllegalStateException。
            val deadline = System.nanoTime() + 15_000_000_000L
            while (System.nanoTime() < deadline) {
                val first = tap.firstCallbackNanos
                if (first != 0L && tap.lastCallbackNanos - first > 4_000_000_000L) break
                Thread.sleep(100)
            }
        } finally {
            main.runOnMainSync {
                runCatching { player.stop() }
                runCatching { player.release() }
            }
        }
        val elapsedNanos = started.let { System.nanoTime() - it }

        val spanNanos = (tap.lastCallbackNanos - tap.firstCallbackNanos).coerceAtLeast(1L)
        val rateHz = tap.callbacks.toDouble() / (spanNanos / 1e9)
        val avgFrames = if (tap.callbacks > 0) tap.totalFrames.toDouble() / tap.callbacks else 0.0
        val buckets = buildString {
            for (i in 0 until tap.histogram.size) {
                if (tap.histogram[i] == 0) continue
                val lo = i * 256
                val hi = lo + 255
                append("[$lo..$hi]=${tap.histogram[i]} ")
            }
        }
        val report = buildString {
            append("PROBE-AUDIO-TAP label=$label ")
            append("requested=${sampleRate}Hz/${channels}ch ")
            append("actual=${tap.sampleRate}Hz/${tap.channelCount}ch/enc=${tap.encoding} ")
            append("callbacks=${tap.callbacks} empty=${tap.emptyCallbacks} ")
            append("avgFrames=${"%.1f".format(avgFrames)} ")
            append("avgBufferMs=${"%.2f".format(if (tap.sampleRate > 0) avgFrames / tap.sampleRate * 1000 else 0.0)} ")
            append("callbackRateHz=${"%.1f".format(rateHz)} ")
            append("elapsedS=${"%.1f".format(elapsedNanos / 1e9)} ")
            append("hist={$buckets}")
        }
        Log.i(TAG, report)
        println(report)
        return report
    }

    @Test
    fun probeAudioTapBufferGrain() {
        val reports = mutableListOf<String>()
        reports += runProbe("44100-2ch", 44_100, 2)
        reports += runProbe("48000-2ch", 48_000, 2)
        // 至少要有回调，否则这次探针什么都没测到（不许把「没测到」写成结论）。
        assertTrue(
            "音频旁路一次回调都没有 —— 探针无效：\n" + reports.joinToString("\n"),
            reports.any { !it.contains("callbacks=0 ") },
        )
    }

    /**
     * v3.0.0：**音频线程增量的实测**（任务书 §3.4「新增音频特征提取的 CPU 开销估算」）。
     *
     * 用真实尺寸的缓冲（探针上一条测出来的粒度附近）跑 N 次 `process`，直接量墙钟时间。
     * 判据是**实时倍率**：一次 `process` 覆盖的音频时长 ÷ 它花掉的 CPU 时间。
     * 只要有几十倍余量，音频线程就不可能因为这一层而欠载。
     */
    @Test
    fun probeFeatureExtractorCost() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File(context.cacheDir, "probe-cost.wav")
        if (!wav.exists() || wav.length() < 1024) writeWav(wav, 44_100, 2, seconds = 1.0)

        val frames = 4096
        val channels = 2
        val buffer = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        var seed = 7
        repeat(frames * channels) {
            seed = seed * 1103515245 + 12345
            buffer.putShort((seed shr 12).toShort())
        }
        buffer.flip()

        val extractor = com.takahashirinta.ncrust.player.AudioFeatureExtractor()
        extractor.configure(44_100, channels)
        // 预热（JIT + 首次分支预测）。
        repeat(200) { extractor.process(buffer, C.ENCODING_PCM_16BIT) }
        val iterations = 4_000
        val t0 = System.nanoTime()
        repeat(iterations) { extractor.process(buffer, C.ENCODING_PCM_16BIT) }
        val elapsed = System.nanoTime() - t0
        val perBufferUs = elapsed / 1000.0 / iterations
        val audioMsPerBuffer = frames * 1000.0 / 44_100
        val realtimeFactor = audioMsPerBuffer * 1000.0 / perBufferUs
        val report = "PROBE-FEATURE-COST frames=$frames ch=$channels " +
            "perBufferUs=${"%.2f".format(perBufferUs)} " +
            "audioMsPerBuffer=${"%.2f".format(audioMsPerBuffer)} " +
            "realtimeFactor=${"%.0f".format(realtimeFactor)}x " +
            "iterations=$iterations"
        Log.i(TAG, report)
        println(report)
        assertTrue("特征提取慢到不够实时：$report", realtimeFactor > 20.0)
    }

    private companion object {
        const val TAG = "NcrustAudioTapProbe"
    }
}
