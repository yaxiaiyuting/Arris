/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.takahashirinta.ncrust.ui.player.WaveformStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 播放链上的只读音频旁路。直接分析交错 PCM，避免依赖六声道转单声道的混音矩阵。
 *
 * 波形柱和界面律动独立订阅。格式检查和开关判断都在 PCM 遍历之前。
 */
@UnstableApi
class TransparentWaveformSink(
    private val enabled: () -> Boolean = { WaveformStore.enabled },
    private val featuresEnabled: () -> Boolean = { WaveformStore.motionFeaturesEnabled },
    private val onBar: (Double, Double, Double, Double) -> Unit = WaveformStore::onBar,
    private val onFeatures: (Double, Double, Double, Double, Double, Boolean) -> Unit =
        WaveformStore::onFeatures,
    private val onTransient: (Int, Float) -> Unit = WaveformStore::onTransient,
    private val extractor: AudioFeatureExtractor = AudioFeatureExtractor(),
    private val fallbackAnalyze: (ByteBuffer, Int, Int, Int, Array<DoubleArray>) -> Long = PcmRms::analyze,
    private val isVisible: () -> Boolean = { true },
) : TeeAudioProcessor.AudioBufferSink {

    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var supportedEncoding = false
    private var sampleRateHz = 0
    private var analysisSuspended = false

    // 回退路径的低通历史按声道保存，音频线程复用预分配的状态。
    private val bassFilterState = Array(PcmRms.MAX_CHANNELS) { DoubleArray(2) }

    /** 旁路分析或发布失败的次数。音频线程只增加计数。 */
    @Volatile
    var droppedBarCount: Long = 0L
        private set

    /** 更换格式时清除两条分析路径的历史。 */
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.channelCount = channelCount.coerceAtLeast(0)
        this.encoding = encoding
        supportedEncoding = PcmRms.bytesPerSample(encoding) != 0
        this.sampleRateHz = sampleRateHz.coerceAtLeast(0)
        for (channel in bassFilterState.indices) {
            bassFilterState[channel][0] = 0.0
            bassFilterState[channel][1] = 0.0
        }
        extractor.configure(this.sampleRateHz, this.channelCount)
        analysisSuspended = false
    }

    /**
     * 特征不可用时回退到全带和低频 RMS。分析或发布抛出异常时跳过该次发布的剩余步骤，
     * 防止 Media3 的旁路回调中断播放。补写零值会画出虚假的静音。
     */
    override fun handleBuffer(buffer: ByteBuffer) {
        if (!supportedEncoding || channelCount == 0) return
        if (!isVisible()) {
            analysisSuspended = true
            return
        }
        val wantBars = enabled()
        val wantFeatures = featuresEnabled()
        if (!wantBars && !wantFeatures) return
        try {
            if (analysisSuspended) flush(sampleRateHz, channelCount, encoding)
            if (extractor.process(buffer, encoding)) {
                if (wantBars) {
                    onBar(
                        extractor.barRms.toDouble(),
                        extractor.barLow.toDouble(),
                        extractor.barMid.toDouble(),
                        extractor.barHigh.toDouble(),
                    )
                }
                if (wantFeatures) {
                    onFeatures(
                        extractor.rms.toDouble(),
                        extractor.low.toDouble(),
                        extractor.mid.toDouble(),
                        extractor.high.toDouble(),
                        extractor.spectralCentroid.toDouble(),
                        true,
                    )
                    val count = extractor.transientCount
                    if (count > 0) onTransient(count, extractor.transientStrength)
                }
            } else {
                val packed = fallbackAnalyze(
                    buffer,
                    encoding,
                    sampleRateHz,
                    channelCount,
                    bassFilterState,
                )
                val full = PcmRms.fullOf(packed).toDouble()
                val bass = PcmRms.bassOf(packed).toDouble()
                if (wantBars) onBar(full, bass, 0.0, 0.0)
                if (wantFeatures) {
                    // 由消费方使用 RMS 包络继续驱动律动。
                    onFeatures(full, bass, 0.0, 0.0, 0.0, false)
                }
            }
        } catch (_: Throwable) {
            droppedBarCount += 1L
        }
    }
}

/**
 * 全带与低频 RMS 的回退实现。按本机字节序用绝对下标读取，保持上游缓冲的视图状态。
 */
@UnstableApi
internal object PcmRms {
    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    /**
     * 一次遍历计算全带 RMS 和 150 Hz 低通 RMS。采样率未知时低频读数回退为全带值。
     * 调用方在 state[声道][0] 保留各声道的低通历史。
     */
    fun analyze(
        view: ByteBuffer,
        encoding: Int,
        sampleRateHz: Int,
        channelCount: Int,
        state: Array<DoubleArray>,
    ): Long {
        if (state.isEmpty()) {
            val full = of(view, encoding).toFloat()
            return pack(full, full)
        }
        val coefficient = lowPassCoefficient(sampleRateHz)
        val channels = channelCount.coerceIn(1, minOf(MAX_CHANNELS, state.size))
        var sumFull = 0.0
        var sumBass = 0.0
        var channel = 0
        val count = forEachSample(view, encoding) { sample ->
            val slot = state[channel]
            var low = if (slot.isNotEmpty() && slot[0].isFinite()) slot[0] else 0.0
            sumFull += sample * sample
            low += coefficient * (sample - low)
            sumBass += low * low
            slot[0] = if (low.isFinite()) low else 0.0
            channel++
            if (channel == channels) channel = 0
        }
        if (count == 0) return pack(0f, 0f)

        val full = sqrt(sumFull / count)
        val bass = sqrt(sumBass / count)
        return pack(
            if (bass.isFinite()) bass.toFloat() else 0f,
            if (full.isFinite()) full.toFloat() else 0f,
        )
    }

    /**
     * 滤波状态的声道上限。与 `AudioFeatureExtractor.MAX_CHANNELS` 同值（8）：
     * 本应用实测的最高声道数是 QQ 臻品档的 6 声道 FLAC，8 留了两个余量槽位。
     */
    const val MAX_CHANNELS = 8

    /**
     * 低通系数。截止 [BASS_CUTOFF_HZ]（底鼓基频 50–100Hz、贝斯 40–200Hz 的公共带）。
     *
     * 采样率 ≤0（`flush` 还没被调用过）时返回 **1.0** —— 系数 1 时 `lp = x`，
     * 即"低频值 = 全带值"。这是刻意的退化：宁可让低频通道等价于总响度
     * （行为与 v2.8.0 的节拍判据一致），也不要拿一个猜出来的采样率去滤波。
     */
    fun lowPassCoefficient(sampleRateHz: Int): Double {
        if (sampleRateHz <= 0) return 1.0
        val a = 1.0 - exp(-2.0 * PI * BASS_CUTOFF_HZ / sampleRateHz)
        return a.coerceIn(0.0, 1.0)
    }

    /** 把 (低频, 全带) 两个 Float 打进一个 Long：音频线程上零分配地返回两个标量。 */
    fun pack(bass: Float, full: Float): Long =
        (bass.toRawBits().toLong() shl 32) or (full.toRawBits().toLong() and 0xFFFF_FFFFL)

    fun bassOf(packed: Long): Float = Float.fromBits((packed ushr 32).toInt())

    fun fullOf(packed: Long): Float = Float.fromBits(packed.toInt())

    /**
     * 低频截止（Hz）。**150** 的依据：底鼓的基频能量集中在 50–100Hz、贝斯 40–200Hz，
     * 而人声基频 85–1100Hz、旋律重音更高 —— 150Hz 落在两者之间，
     * 既保住了鼓组的下盘，又把大部分人声排除在外。
     *
     * 这不是"分频段可视化"：它只有一个通道，没有第二、第三个频段可比。
     */
    const val BASS_CUTOFF_HZ = 150.0

    fun of(view: ByteBuffer, encoding: Int): Double {
        var sum = 0.0
        val count = forEachSample(view, encoding) { sum += it * it }
        return if (count == 0) 0.0 else sqrt(sum / count)
    }

    private inline fun forEachSample(view: ByteBuffer, encoding: Int, consume: (Double) -> Unit): Int {
        val bytes = bytesPerSample(encoding)
        if (bytes == 0) return 0
        var index = view.position()
        val count = view.remaining() / bytes
        val end = index + count * bytes
        val swapBytes = view.order() != ByteOrder.nativeOrder()
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                while (index < end) {
                    val raw = view.getShort(index)
                    val sample = if (swapBytes) java.lang.Short.reverseBytes(raw) else raw
                    consume(sample / 32768.0)
                    index += bytes
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                while (index < end) {
                    val raw = view.getInt(index)
                    val bits = if (swapBytes) Integer.reverseBytes(raw) else raw
                    consume(Float.fromBits(bits).toDouble())
                    index += bytes
                }
            }
        }
        return count
    }
}
