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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 从交错 PCM 提取全带 RMS、三频带幅度和瞬态。配置、处理、读取结果均在同一线程。
 *
 * 两个一阶低通分别以 150 Hz 和 2 kHz 为截止频率。低通之差给出中频，原样本减去
 * 2 kHz 低通给出高频。频带之间会泄漏，结果用于视觉反馈。
 */
@UnstableApi
open class AudioFeatureExtractor {

    /** 整个缓冲的 RMS 幅度，归一化到 0..1。 */
    var rms: Float = 0f
        private set

    var low: Float = 0f
        private set

    var mid: Float = 0f
        private set

    var high: Float = 0f
        private set

    /** 三频带幅度加权的质心近似，按对数归一化到 0..1。 */
    var spectralCentroid: Float = 0f
        private set

    /** 本次 [process] 检出的瞬态数。检测窗口跨缓冲连续。 */
    var transientCount: Int = 0
        private set

    /** 本次检测到的最强瞬态的强度，取值 0..1。 */
    var transientStrength: Float = 0f
        private set

    // 柱高保留短促的击打峰值，RMS 和质心仍基于整个缓冲。
    val barRms: Float get() = withAttack(rms, attackFull)
    val barLow: Float get() = withAttack(low, attackLow)
    val barMid: Float get() = withAttack(mid, attackMid)
    val barHigh: Float get() = withAttack(high, attackHigh)

    private fun withAttack(average: Float, attack: Float): Float =
        average + (attack - average).coerceAtLeast(0f) * 0.72f

    /** 是否已配置有效的采样率和声道数。 */
    var available: Boolean = false
        private set

    /**
     * 每声道各保留一组 150 Hz、2 kHz 低通状态。交错声道共用状态会抬高实际截止频率。
     */
    private val filterState = Array(MAX_CHANNELS) { DoubleArray(2) }

    private var lowBaseline = 0.0
    private var fullBaseline = 0.0
    private var presenceBaseline = 0.0
    private var previousPresence = 0.0
    private var peakPresence = 0.0
    private var peakLow = 0.0
    private var peakFull = 0.0
    private var cooldownMs = 0.0

    // 一个分析窗口可以包含上一个缓冲留下的样本。
    private var sumLowSub = 0.0
    private var sumMidSub = 0.0
    private var sumHighSub = 0.0
    private var sumFullSub = 0.0
    private var subSamples = 0
    private var subFrameSamples = 0
    private var subFrameMs = 0.0
    private var baselineK = 0.0
    private var subPeakDecay = 0.0

    private var attackLow = 0f
    private var attackMid = 0f
    private var attackHigh = 0f
    private var attackFull = 0f

    private var filterChannels = 0
    private var lowCoefficient = 1.0
    private var midCoefficient = 1.0

    /** 在音频格式变化时重算滤波与瞬态窗口的系数。 */
    fun configure(sampleRateHz: Int, channelCount: Int) {
        reset()
        available = sampleRateHz > 0 && channelCount > 0
        if (!available) return
        filterChannels = channelCount.coerceAtMost(MAX_CHANNELS)
        lowCoefficient = lowPassCoefficient(LOW_CUTOFF_HZ, sampleRateHz)
        midCoefficient = lowPassCoefficient(MID_HIGH_CUTOFF_HZ, sampleRateHz)
        val frames = (sampleRateHz * SUB_FRAME_MS / 1000.0).toInt().coerceAtLeast(1)
        subFrameSamples = frames * channelCount
        subFrameMs = frames * 1000.0 / sampleRateHz
        baselineK = baselineCoefficient(subFrameMs)
        subPeakDecay = peakMemoryDecay(subFrameMs)
    }

    /** 换歌或换设备时清除分析历史和输出，保留当前格式的系数。 */
    fun reset() {
        for (channel in filterState.indices) {
            filterState[channel][0] = 0.0
            filterState[channel][1] = 0.0
        }
        lowBaseline = 0.0
        fullBaseline = 0.0
        presenceBaseline = 0.0
        previousPresence = 0.0
        peakPresence = 0.0
        cooldownMs = 0.0
        rms = 0f
        low = 0f
        mid = 0f
        high = 0f
        spectralCentroid = 0f
        transientCount = 0
        transientStrength = 0f
        peakLow = 0.0
        peakFull = 0.0
        clearSubFrame()
        clearAttacks()
    }

    private fun clearAttacks() {
        attackLow = 0f
        attackMid = 0f
        attackHigh = 0f
        attackFull = 0f
    }

    private fun clearSubFrame() {
        sumLowSub = 0.0
        sumMidSub = 0.0
        sumHighSub = 0.0
        sumFullSub = 0.0
        subSamples = 0
    }

    /**
     * 在音频线程上原位读取 native-endian PCM，保留缓冲区的位置、界限和字节序。
     *
     * @return 是否更新了特征。格式未知、编码不支持或样本不足时由调用方降级。
     */
    open fun process(view: ByteBuffer, encoding: Int): Boolean {
        transientCount = 0
        transientStrength = 0f
        clearAttacks()
        if (!available) return false

        val swapBytes = view.order() != ByteOrder.nativeOrder()
        return when (encoding) {
            C.ENCODING_PCM_16BIT -> processSamples(view, Short.SIZE_BYTES) { index ->
                val raw = view.getShort(index)
                val sample = if (swapBytes) java.lang.Short.reverseBytes(raw) else raw
                sample / 32768.0
            }
            C.ENCODING_PCM_FLOAT -> processSamples(view, Int.SIZE_BYTES) { index ->
                val raw = view.getInt(index)
                val sample = Float.fromBits(if (swapBytes) Integer.reverseBytes(raw) else raw)
                if (sample.isFinite()) sample.toDouble() else 0.0
            }
            else -> false
        }
    }

    // 内联解码，音频线程上没有解码器对象或逐样本的装箱调用。
    private inline fun processSamples(
        view: ByteBuffer,
        bytesPerSample: Int,
        readSample: (Int) -> Double,
    ): Boolean {
        val samples = view.remaining() / bytesPerSample
        if (samples == 0) return false

        val channels = filterChannels
        val lowAlpha = lowCoefficient
        val midAlpha = midCoefficient
        var index = view.position()
        val end = index + samples * bytesPerSample
        var channel = 0
        var sumFull = 0.0
        var sumLow = 0.0
        var sumMid = 0.0
        var sumHigh = 0.0
        while (index < end) {
            val sample = readSample(index)
            val state = filterState[channel]
            var lowPass = state[0]
            var midPass = state[1]
            lowPass += lowAlpha * (sample - lowPass)
            midPass += midAlpha * (sample - midPass)
            val midBand = midPass - lowPass
            val highBand = sample - midPass
            val fullPower = sample * sample
            val lowPower = lowPass * lowPass
            val midPower = midBand * midBand
            val highPower = highBand * highBand
            sumFull += fullPower
            sumLow += lowPower
            sumMid += midPower
            sumHigh += highPower
            sumFullSub += fullPower
            sumLowSub += lowPower
            sumMidSub += midPower
            sumHighSub += highPower
            // 有限的 PCM 输入和 [0, 1] 内的系数让低通状态保持有限。
            state[0] = lowPass
            state[1] = midPass

            index += bytesPerSample
            channel++
            if (channel >= channels) channel = 0
            subSamples++
            if (subSamples >= subFrameSamples) finalizeSubFrame()
        }

        val n = samples.toDouble()
        val full = sqrt(sumFull / n)
        val lowValue = sqrt(sumLow / n)
        val midValue = sqrt(sumMid / n)
        val highValue = sqrt(sumHigh / n)
        rms = finite01(full)
        low = finite01(lowValue)
        mid = finite01(midValue)
        high = finite01(highValue)
        spectralCentroid = centroidOf(lowValue, midValue, highValue)

        return true
    }

    private fun finalizeSubFrame() {
        val n = subSamples.toDouble()
        val low = finite01(sqrt(sumLowSub / n))
        val mid = finite01(sqrt(sumMidSub / n))
        val high = finite01(sqrt(sumHighSub / n))
        val full = finite01(sqrt(sumFullSub / n))
        clearSubFrame()
        if (updateTransient(low, full, high + 0.35 * mid)) {
            attackLow = maxOf(attackLow, low)
            attackMid = maxOf(attackMid, mid)
            attackHigh = maxOf(attackHigh, high)
            attackFull = maxOf(attackFull, full)
        }
    }

    /** 低频和全频检测鼓点，中高频包络捕捉伴奏中的拍手和军鼓。 */
    private fun updateTransient(low: Float, full: Float, presence: Double): Boolean {
        val lowValue = low.toDouble()
        val fullValue = full.toDouble()
        val lowBase = lowBaseline
        val fullBase = fullBaseline
        val presenceBase = presenceBaseline
        val lowJump = lowValue - lowBase
        val fullJump = fullValue - fullBase
        val presenceJump = presence - presenceBase
        val presenceStep = presence - previousPresence
        val presenceOnset = presence >= 0.025 && presenceJump >= maxOf(0.012, presenceBase * 0.22) &&
            presenceStep >= maxOf(0.012, previousPresence * 0.3) &&
            presence >= peakPresence * subPeakDecay * 1.2
        previousPresence = presence
        peakPresence = maxOf(presence, peakPresence * subPeakDecay)

        // 用更新前的基线和衰减峰值判定，避免当前击打抬高自己的门槛。
        val memory = peakLow * subPeakDecay
        val memoryFull = peakFull * subPeakDecay
        val lowNovel = lowValue >= memory * ONSET_PEAK_RATIO
        val fullNovel = fullValue >= memoryFull * ONSET_PEAK_RATIO

        lowBaseline = lowBase + lowJump * baselineK
        fullBaseline = fullBase + fullJump * baselineK
        presenceBaseline = presenceBase + presenceJump * baselineK
        peakLow = if (memory > lowValue) memory else lowValue
        peakFull = if (memoryFull > fullValue) memoryFull else fullValue

        if (cooldownMs > 0.0) cooldownMs = (cooldownMs - subFrameMs).coerceAtLeast(0.0)
        if (cooldownMs > 0.0) return false

        // 衰减峰值抑制同一次击打的尾部在冷却结束后再次触发。
        val lowOnset = lowNovel && low >= ONSET_MIN_LOW &&
            lowJump >= ONSET_ABS_MIN &&
            lowJump >= lowBase * ONSET_REL_FACTOR
        val fullOnset = fullNovel && fullValue >= ONSET_MIN_RMS &&
            fullJump >= ONSET_ABS_MIN &&
            fullJump >= fullBase * ONSET_REL_FACTOR
        if (!lowOnset && !fullOnset && !presenceOnset) return false

        cooldownMs = ONSET_COOLDOWN_MS.toDouble()
        transientCount++
        val strength = maxOf(
            lowJump / ONSET_STRENGTH_FULL,
            fullJump / ONSET_STRENGTH_FULL,
            if (presenceOnset) presenceJump / 0.10 else 0.0,
        )
        val clamped = if (strength.isFinite()) strength.toFloat().coerceIn(0f, 1f) else 0f
        if (clamped > transientStrength) transientStrength = clamped
        return true
    }

    companion object {
        /** 与 RMS 回退路径共用低频截止频率。 */
        const val LOW_CUTOFF_HZ: Double = PcmRms.BASS_CUTOFF_HZ
        const val MID_HIGH_CUTOFF_HZ: Double = 2000.0

        // 质心近似使用的三个代表频率（Hz）。
        const val CENTROID_F_LOW_HZ: Double = 75.0
        const val CENTROID_F_MID_HZ: Double = 1000.0
        const val CENTROID_F_HIGH_HZ: Double = 6000.0

        // 质心对数归一化的频率范围（Hz）。
        const val CENTROID_MIN_HZ: Double = 50.0
        const val CENTROID_MAX_HZ: Double = 16000.0

        const val CENTROID_EPS = 1e-9
        private val centroidLogSpan = ln(CENTROID_MAX_HZ / CENTROID_MIN_HZ)

        // 幅度门槛与 WaveformEffectsState 的 UI 回退判据一致。
        const val ONSET_MIN_LOW = 0.10f
        const val ONSET_MIN_RMS = 0.12f
        const val ONSET_ABS_MIN = 0.05f
        const val ONSET_REL_FACTOR = 0.35f

        /** 冷却短于 UI 回退路径，以保留同一长缓冲中的相邻击打。 */
        const val ONSET_COOLDOWN_MS = 90f

        /** 超出状态槽位的声道轮用已有滤波状态，避免在音频线程分配。 */
        const val MAX_CHANNELS = 8
        const val SUB_FRAME_MS = 10.0

        /** 波形瞬态计数的发布上限。 */
        const val MAX_TRANSIENTS_PER_BUFFER = 64
        const val BASELINE_TAU_MS = 800.0
        const val ONSET_STRENGTH_FULL = 0.20f
        const val PEAK_MEMORY_TAU_MS = 250.0
        const val ONSET_PEAK_RATIO = 1.6

        /** 慢速基线系数。无有效时间步长时保持原基线。 */
        fun baselineCoefficient(dtMs: Double): Double {
            if (!dtMs.isFinite() || dtMs <= 0.0) return 0.0
            val k = 1.0 - exp(-dtMs / BASELINE_TAU_MS)
            return k.coerceIn(0.0, 1.0)
        }

        /** 峰值记忆的衰减系数。无有效时间步长时保持峰值。 */
        fun peakMemoryDecay(dtMs: Double): Double {
            if (!dtMs.isFinite() || dtMs <= 0.0) return 1.0
            val d = exp(-dtMs / PEAK_MEMORY_TAU_MS)
            return if (d.isFinite()) d.coerceIn(0.0, 1.0) else 1.0
        }

        /** 一阶低通系数：`a = 1 − exp(−2π·fc/fs)`，clamp 到 `[0,1]`。 */
        fun lowPassCoefficient(cutoffHz: Double, sampleRateHz: Int): Double {
            if (sampleRateHz <= 0 || cutoffHz <= 0.0) return 1.0
            val a = 1.0 - exp(-2.0 * PI * cutoffHz / sampleRateHz)
            return a.coerceIn(0.0, 1.0)
        }

        /** 计算三频带幅度加权的对数质心。接近静音时返回 0。 */
        fun centroidOf(lowAmplitude: Double, midAmplitude: Double, highAmplitude: Double): Float {
            val l = if (lowAmplitude.isFinite() && lowAmplitude > 0.0) lowAmplitude else 0.0
            val m = if (midAmplitude.isFinite() && midAmplitude > 0.0) midAmplitude else 0.0
            val h = if (highAmplitude.isFinite() && highAmplitude > 0.0) highAmplitude else 0.0
            val denom = l + m + h
            if (denom <= CENTROID_EPS) return 0f
            val weighted = (l * CENTROID_F_LOW_HZ + m * CENTROID_F_MID_HZ + h * CENTROID_F_HIGH_HZ) / denom
            val hz = weighted.coerceIn(CENTROID_MIN_HZ, CENTROID_MAX_HZ)
            val normalized = ln(hz / CENTROID_MIN_HZ) / centroidLogSpan
            return if (normalized.isFinite()) normalized.toFloat().coerceIn(0f, 1f) else 0f
        }

        private fun finite01(value: Double): Float =
            if (value.isFinite()) value.coerceIn(0.0, 1.0).toFloat() else 0f
    }
}
