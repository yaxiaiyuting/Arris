/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import com.takahashirinta.ncrust.ui.player.WaveformStore
import com.takahashirinta.ncrust.ui.player.visualizerFrameStride
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/** 波形与播放器背景共用的帧状态。绘制阶段按各自的代数读出更新。 */
object MotionClock {

    private val generationState = mutableIntStateOf(0)

    /** 只应在 draw / `graphicsLayer` 块里读（在组合阶段读会变成每帧重组）。 */
    val generation: Int get() = generationState.intValue

    private val breathGenerationState = mutableIntStateOf(0)
    internal val breathGeneration: Int get() = breathGenerationState.intValue
    internal val breathing = BreathingMotion()

    internal fun suspendBreathing() {
        breathing.reset()
        breathGenerationState.intValue++
    }

    private val envelope = MotionEnvelope()
    private val backdrop = MotionBackdropState()

    /** v3.0.0：本帧的音频特征快照（绑定层的输入，也是渲染侧的只读来源）。 */
    private val featureBindings = MotionBindings()

    /** 平滑响度（0..1）。draw 阶段直接读。 */
    fun level(): Float = envelope.level()

    /** 重拍脉冲（0..1）。draw 阶段直接读。 */
    fun pulse(): Float = envelope.pulse() * WaveformStore.displayGain

    /** v3.0.0：本帧的音频特征（逐柱着色 / 三频带能量条 / 呼吸都用它）。draw 阶段直接读。 */
    fun bindings(): MotionBindings = featureBindings

    /** 冲击波 / 光晕 / 粒子的只读访问（draw 阶段直接读）。 */
    val backstage: MotionBackdropState get() = backdrop

    /**
     * 推进一次波形和界面动效。nowMs 来自帧时钟，与音频到达时刻使用同一单调时间基准。
     *
     * @return 是否仍有变化或需要继续推进的收尾状态。
     */
    fun frame(
        active: Boolean,
        dtMs: Float,
        waveform: VisualizerEffects,
        motion: MotionEffects,
        nowMs: Long = 0L,
        holdWaveform: Boolean = false,
        animateBreathing: Boolean = false,
    ): Boolean {
        val waveformMoving = WaveformStore.pump(active, dtMs, waveform, nowMs, holdHistory = holdWaveform)
        val gain = WaveformStore.displayGain
        val audible = active && gain > 0f
        // 特征快照：优先用音频线程发布的真值；链路不可用时它对消费方呈现 available=false。
        featureBindings.update(
            rms = WaveformStore.featureRms() * gain,
            low = WaveformStore.featureLow() * gain,
            mid = WaveformStore.featureMid() * gain,
            high = WaveformStore.featureHigh() * gain,
            centroid = WaveformStore.featureCentroid(),
            available = WaveformStore.featuresAvailable(),
            transientCount = WaveformStore.transientCount(),
            transientStrength = WaveformStore.transientStrength() * gain,
        )
        // 响度包络的来源：特征链路可用时直接用它的 RMS（这样「关掉可视化但开着界面动效」
        // 也仍然有呼吸）；不可用时回落波形环形缓冲（v2.9.0 的行为）。
        val levelSource = if (featureBindings.available) {
            featureBindings.rms
        } else {
            WaveformStore.newestBar() * gain
        }
        var changed = envelope.update(
            newestBar = levelSource,
            newestBass = WaveformStore.newestBass() * gain,
            dtMs = dtMs,
            active = audible,
            transients = featureBindings.transients,
            strength = featureBindings.strength,
            externalAvailable = featureBindings.available,
        )
        if (motion.haloBloom || motion.particles || motion.shockwave) {
            if (backdrop.update(featureBindings, motion, dtMs, audible)) changed = true
        }
        val breathingChanged = animateBreathing && breathing.advance(
            dtMs, audible, featureBindings.available, featureBindings.low, featureBindings.mid, featureBindings.high,
        )
        if (breathingChanged) breathGenerationState.intValue++
        if (changed) generationState.intValue++
        // 背景呼吸以 30 Hz 更新。两次绘制之间也可能还有幅度正在回落。
        return waveformMoving || changed || breathingChanged || (animateBreathing && breathing.hasEnergy)
    }

    internal fun alignTransientCounts() {
        val count = if (WaveformStore.featuresAvailable()) WaveformStore.transientCount() else MotionBindings.NO_COUNT
        featureBindings.alignTransientCount(count)
        WaveformStore.showcase.alignTransientCount(count)
    }

    /**
     * 换歌：清空**界面动效这一层**的包络与特效池（基线、脉冲、光晕、粒子）。
     *
     * 刻意**不清** `WaveformStore` 的环形缓冲：那会让新歌开头几帧的波形是空的
     * （上一首的柱子滚出去本来就是既有的"换歌观感"）。本层的状态是自己的，
     * 清它是为了「新歌的第一个强拍不被上一首的基线吃掉」。
     */
    fun clear() {
        envelope.clear()
        backdrop.clear()
        featureBindings.reset()
        generationState.intValue++
    }

    /** 单测 / 诊断。 */
    internal fun resetForTest() {
        envelope.clear()
        backdrop.clear()
        featureBindings.reset()
        generationState.intValue = 0
        breathing.reset()
        breathGenerationState.intValue = 0
    }
}

/** 展开态播放器只挂一次。播放时随屏幕帧推进，暂停或缓冲时只结算残余动效。 */
@Composable
fun MotionFrameClock(
    isPlaying: Boolean,
    isBufferingFlow: StateFlow<Boolean>,
    enabled: Boolean,
    waveformMounted: Boolean,
    animateBreathing: Boolean = false,
) {
    val context = LocalContext.current
    val currentPlaying = rememberUpdatedState(isPlaying)
    // 组合期读一次（改设置 / 换面板刷新率时才会重组一次）。帧路径里零 state 读。
    val motion = MotionPrefs.effects.value
    val waveform = VisualizerPrefs.effects.value
    val refreshHz = rememberDisplayRefreshRate()
    val stride = remember(context, refreshHz) { visualizerFrameStride(context, refreshHz) }
    val frameIntervalMs = remember(refreshHz) { DisplayRefresh.frameIntervalMs(refreshHz) }

    val clockNeeded = enabled && (motion.needsFrameClock || waveformMounted)

    LaunchedEffect(animateBreathing) {
        if (!animateBreathing) MotionClock.suspendBreathing()
    }

    LaunchedEffect(clockNeeded, stride, frameIntervalMs, motion, waveform, animateBreathing, isBufferingFlow) {
        if (!clockNeeded) return@LaunchedEffect
        val inactiveDelayMs = frameIntervalMs.toLong().coerceAtLeast(1L)
        val pacer = FrameStride(stride)
        var lastFrameNanos = 0L
        val onFrame: (Long) -> Unit = { now ->
            if (pacer.shouldAdvance()) {
                val dtMs = if (lastFrameNanos == 0L) frameIntervalMs
                else ((now - lastFrameNanos) / 1_000_000f).coerceIn(1f, 100f)
                lastFrameNanos = now
                MotionClock.frame(
                    active = true,
                    dtMs = dtMs,
                    waveform = waveform,
                    motion = motion,
                    nowMs = now / 1_000_000L,
                    animateBreathing = animateBreathing,
                )
            }
        }

        snapshotFlow { currentPlaying.value }
            .combine(isBufferingFlow) { playing, buffering ->
                when {
                    buffering -> MotionPlayback.Buffering
                    playing -> MotionPlayback.Playing
                    else -> MotionPlayback.Paused
                }
            }
            .runMotionFrames(
                play = { resuming ->
                    if (resuming) MotionClock.alignTransientCounts()
                    lastFrameNanos = 0L
                    pacer.reset()
                    while (true) withFrameNanos(onFrame)
                },
                settle = { holdWaveform ->
                    delay(inactiveDelayMs)
                    MotionClock.frame(
                        active = false,
                        holdWaveform = holdWaveform,
                        animateBreathing = animateBreathing,
                        dtMs = frameIntervalMs,
                        waveform = waveform,
                        motion = motion,
                    )
                },
            )
    }
}
