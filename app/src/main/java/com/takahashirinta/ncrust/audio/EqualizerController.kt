/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.audio

import android.content.Context
import android.media.audiofx.Equalizer
import android.util.Log

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

    @Volatile
    private var equalizer: Equalizer? = null

    @Volatile
    private var sessionId: Int = 0

    /** 当前是否已连上（供设置页显示"等待播放"）。 */
    val isAttached: Boolean get() = equalizer != null

    /** 已连上的频段数（未连上时 0）。 */
    val bandCount: Int get() = equalizer?.numberOfBands?.toInt() ?: 0

    /**
     * 挂到某个 audio session 上。
     *
     * [enabled] = false 或 [gainsMb] 为空时**直接释放**，一个 effect 都不留 —— 这样
     * "关掉均衡器"不残留任何 DSP 开销。
     */
    @Synchronized
    fun attach(context: Context, session: Int, enabled: Boolean, gainsMb: IntArray) {
        if (!enabled || session == 0) {
            release()
            return
        }
        if (sessionId == session && equalizer != null) {
            applyGains(gainsMb)
            return
        }
        release()
        runCatching {
            val eq = Equalizer(0, session)
            equalizer = eq
            sessionId = session
            eq.enabled = true
            Log.i(TAG, "attached session=$session bands=${eq.numberOfBands}")
            applyGains(gainsMb)
        }.onFailure {
            Log.w(TAG, "attach failed session=$session: ${it.javaClass.simpleName} ${it.message}")
            release()
        }
    }

    /** 写入增益（长度不足的频段保持系统默认 0mB）。 */
    @Synchronized
    fun applyGains(gainsMb: IntArray) {
        val eq = equalizer ?: return
        val bands = eq.numberOfBands.toInt()
        if (bands <= 0) return
        val range = runCatching { eq.bandLevelRange }.getOrNull()
        val min = range?.get(0)?.toInt() ?: GAIN_MIN_MB
        val max = range?.get(1)?.toInt() ?: GAIN_MAX_MB
        for (band in 0 until bands) {
            val raw = gainsMb.getOrElse(band) { 0 }
            val value = raw.coerceIn(min, max).toShort()
            runCatching { eq.setBandLevel(band.toShort(), value) }
                .onFailure { Log.w(TAG, "setBandLevel($band) failed: ${it.message}") }
        }
    }

    /** 开关切换：关掉立刻释放（不残留 effect）。 */
    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean, gainsMb: IntArray) {
        if (!enabled) release() else attach(context, sessionId, true, gainsMb)
    }

    @Synchronized
    fun release() {
        runCatching { equalizer?.enabled = false }
        runCatching { equalizer?.release() }
        equalizer = null
        sessionId = 0
    }
}
