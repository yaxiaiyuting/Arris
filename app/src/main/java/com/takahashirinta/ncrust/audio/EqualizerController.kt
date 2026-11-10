/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 均衡器（4.0.0）。
 *
 * ## 接在哪
 *
 * 用系统 `AudioEffect.Equalizer` 挂在 **ExoPlayer 的 audio session** 上：ExoPlayer 每次
 * 新建 AudioTrack 都会给一个新的 session id，所以 [attach] 要在
 * `Player.Listener.onAudioSessionIdChanged` 里调用（切歌、切音轨、重连音频设备都会触发）。
 *
 * ## 为什么默认关
 *
 * 均衡器是**不可见副作用**：一旦 `enabled` 就会改写整条输出链路。关着的时候这里连
 * `Equalizer` 都不创建（不是创建后设 0dB），保证 4.0.0 的默认听感与 3.8.18 逐字节相同。
 *
 * ## 预设
 *
 * 只存**用户自己的增益**（每个频段一个 dB 值）+ 一个预设名，不依赖设备内置预设 ——
 * 各厂商的 preset 索引与频段数完全不同（真机上见过 5 段与 10 段两种），存索引必然错位。
 */
object EqualizerController {

    private const val TAG = "Equalizer"

    /** 频段数上限：真机最多见过 10 段（ExoPlayer + 系统 EQ）。 */
    const val MAX_BANDS = 10

    /** 增益范围（毫贝）。±1200mB = ±12dB，覆盖系统 EQ 常见上限。 */
    const val GAIN_MIN_MB = -1200
    const val GAIN_MAX_MB = 1200

    private var equalizer: Equalizer? = null
    private var sessionId: Int = 0
    private var deviceCentersHz = DoubleArray(0)
    private val _activeBandCount = MutableStateFlow(0)
    val activeBandCount = _activeBandCount.asStateFlow()

    /**
     * 挂到某个 audio session 上。
     *
     * 关闭时释放 effect，保留会话标识，方便播放过程中重新开启。
     */
    @Synchronized
    fun attach(session: Int, enabled: Boolean, gainsMb: IntArray) {
        if (sessionId != session) {
            releaseEffect()
            sessionId = session
        }
        setEnabled(enabled, gainsMb)
    }

    @Synchronized
    fun setEnabled(enabled: Boolean, gainsMb: IntArray) {
        if (!enabled || sessionId == 0) {
            releaseEffect()
            return
        }
        if (equalizer != null) {
            applyGains(gainsMb)
            return
        }
        runCatching {
            val eq = Equalizer(0, sessionId)
            equalizer = eq
            deviceCentersHz = DoubleArray(eq.numberOfBands.toInt()) { band ->
                runCatching { eq.getCenterFreq(band.toShort()) / 1000.0 }.getOrDefault(0.0)
            }
            check(eq.setEnabled(true) == AudioEffect.SUCCESS) { "Device refused to enable equalizer" }
            applyGains(gainsMb)
            _activeBandCount.value = deviceCentersHz.size
            Log.i(TAG, "attached session=$sessionId bands=${deviceCentersHz.size}")
        }.onFailure {
            Log.w(TAG, "attach failed session=$sessionId: ${it.javaClass.simpleName} ${it.message}")
            releaseEffect()
        }
    }

    /** 写入增益（长度不足的频段保持系统默认 0mB）。 */
    @Synchronized
    fun applyGains(gainsMb: IntArray) {
        val eq = equalizer ?: return
        val range = runCatching { eq.bandLevelRange }.getOrNull()
        val min = range?.get(0)?.toInt() ?: GAIN_MIN_MB
        val max = range?.get(1)?.toInt() ?: GAIN_MAX_MB
        for (band in deviceCentersHz.indices) {
            val centerHz = deviceCentersHz[band]
            val raw = if (centerHz > 0.0) gainAtFrequency(gainsMb, centerHz) else gainsMb.getOrElse(band) { 0 }
            val value = raw.coerceIn(min, max).toShort()
            runCatching { eq.setBandLevel(band.toShort(), value) }
                .onFailure { Log.w(TAG, "setBandLevel($band) failed: ${it.message}") }
        }
    }

    @Synchronized
    fun release() {
        releaseEffect()
        sessionId = 0
    }

    private fun releaseEffect() {
        val eq = equalizer ?: return
        runCatching { eq.enabled = false }
        runCatching { eq.release() }
        equalizer = null
        deviceCentersHz = DoubleArray(0)
        _activeBandCount.value = 0
    }

    /** Match the saved ten-band curve to the device's native band centers. */
    internal fun gainAtFrequency(gainsMb: IntArray, centerHz: Double): Int {
        val centers = EqualizerPrefs.frequenciesHz
        val upper = centers.indexOfFirst { it >= centerHz }
        if (upper <= 0) return gainsMb.getOrElse(if (upper == 0) 0 else centers.lastIndex) { 0 }
        val lowerHz = centers[upper - 1].toDouble()
        val fraction = ln(centerHz / lowerHz) / ln(centers[upper] / lowerHz)
        val a = gainsMb.getOrElse(upper - 1) { 0 }
        val b = gainsMb.getOrElse(upper) { 0 }
        return (a + (b - a) * fraction).roundToInt()
    }
}
