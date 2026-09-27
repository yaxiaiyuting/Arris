/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import com.takahashirinta.ncrust.ui.player.WaveformStore
import com.takahashirinta.ncrust.ui.player.visualizerFrameIntervalMs
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerPrefs
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerTier
import kotlinx.coroutines.delay

/**
 * v2.9.0：**动效的唯一帧时钟**（波形 + 界面动效共用一条循环）。
 *
 * ## 为什么把帧循环从 `AudioVisualizerBars` 搬到这里
 *
 * v2.8.0 的帧循环长在波形组件内部。v2.9.0 的界面动效（背景呼吸、节拍脉冲、粒子、光晕）
 * 在**竖屏手机**也必须跑，而竖屏手机上波形**根本不挂载**
 * （`PlayerLayout.visualizerSlot` 在「手机竖屏」那一格恒为 false）。如果两处各起一条循环，
 * 就会出现两个问题：
 *
 *  1. **同一个 `WaveformStore.pump` 被调用两次**：`consumePending` 是幂等的（第二次没有新柱），
 *     但**动画相位**（渐变流动 / 呼吸）会每帧前进两次 —— 流动速度直接翻倍，
 *     而且这个 bug 只在「波形与界面动效同时开」的设备上出现（横屏/平板），
 *     竖屏测不出来；
 *  2. 两条循环各自持有 `LongArray` 帧时钟，暂停/恢复时的 `dt` 与节流各自为政，
 *     包络与柱高会**错帧**。
 *
 * 所以帧循环**只留一条**，在这里；`AudioVisualizerBars` 退化成纯读取方
 * （它的 `Canvas` 读 `WaveformStore.generation`，本对象负责让那个 generation 动起来）。
 *
 * ## 一条循环干四件事（顺序不能反）
 *
 * 1. `WaveformStore.pump(...)` —— v2.8.0 的波形推进（含 C 档涟漪/粒子），**一行没改**；
 * 2. `MotionBindings.update(...)` —— 把音频线程发布的特征收成「这一帧的输入」；
 * 3. `MotionEnvelope.update(...)` —— 响度包络与瞬态脉冲；
 * 4. `MotionBackdropState.update(...)` —— 瞬态 → 冲击波 / 光晕；中高频能量 → 粒子。
 *
 * ## v3.0.0：**帧时间监控与自动降级已整个删除**
 *
 * v2.8.0 起这里挂过 `VisualizerFrameMonitor`，用实测帧时间触发一次自动降级。
 * v3.0.0 把它删掉了 —— 理由见 [MotionDegrade] 的 KDoc（一句话：
 * **判据不知道是谁把帧顶起来的，而它却会改写用户选的档位，于是渲染结果不再可推导**）。
 *
 * 「关掉开关 = 零开销」这条纪律**没有变**，只是判据从「一个开关」变成「两个都不需要」：
 * 界面动效不需要帧时钟**且**波形也没挂载时，`LaunchedEffect` 直接 return，一行都不跑。
 *
 * @param activeProvider 播放中且未在缓冲。**用 lambda 而不是布尔参数**：这个值只在帧循环里读，
 *   传布尔会让 `PlayerCard` 订阅 `isPlaying`/`isBuffering` 而整树重组（AGENTS.md「GPU 零重组」）。
 * @param enabled 调用方的挂载判据（展开态 + 有歌）。
 */
object MotionClock {

    private val generationState = mutableIntStateOf(0)

    /** 只应在 draw / `graphicsLayer` 块里读（在组合阶段读会变成每帧重组）。 */
    val generation: Int get() = generationState.intValue

    private val envelope = MotionEnvelope()
    private val backdrop = MotionBackdropState()

    /** v3.0.0：本帧的音频特征快照（绑定层的输入，也是渲染侧的只读来源）。 */
    private val featureBindings = MotionBindings()

    /** 平滑响度（0..1）。draw 阶段直接读。 */
    fun level(): Float = envelope.level()

    /** 重拍脉冲（0..1）。draw 阶段直接读。 */
    fun pulse(): Float = envelope.pulse()

    /** v3.0.0：本帧的音频特征（逐柱着色 / 三频带能量条 / 呼吸都用它）。draw 阶段直接读。 */
    fun bindings(): MotionBindings = featureBindings

    /** 冲击波 / 光晕 / 粒子的只读访问（draw 阶段直接读）。 */
    val backstage: MotionBackdropState get() = backdrop

    /**
     * 推进一步。**只在 [MotionFrameClock] 的循环里调用**（每帧一次）。
     *
     * @return 界面动效这一层是否需要重绘（波形的失效由 `WaveformStore` 自己管）。
     */
    fun frame(
        active: Boolean,
        dtMs: Float,
        waveform: VisualizerEffects,
        motion: MotionEffects,
    ): Boolean {
        WaveformStore.pump(active, dtMs, waveform)
        // 特征快照：优先用音频线程发布的真值；链路不可用时它对消费方呈现 available=false。
        featureBindings.update(
            rms = WaveformStore.featureRms(),
            low = WaveformStore.featureLow(),
            mid = WaveformStore.featureMid(),
            high = WaveformStore.featureHigh(),
            centroid = WaveformStore.featureCentroid(),
            available = WaveformStore.featuresAvailable(),
            transientCount = WaveformStore.transientCount(),
            transientStrength = WaveformStore.transientStrength(),
        )
        // 响度包络的来源：特征链路可用时直接用它的 RMS（这样「关掉可视化但开着界面动效」
        // 也仍然有呼吸）；不可用时回落波形环形缓冲（v2.9.0 的行为）。
        val levelSource = if (featureBindings.available) {
            featureBindings.rms
        } else {
            WaveformStore.newestBar()
        }
        var changed = envelope.update(
            newestBar = levelSource,
            newestBass = WaveformStore.newestBass(),
            dtMs = dtMs,
            active = active,
            transients = featureBindings.transients,
            strength = featureBindings.strength,
            externalAvailable = featureBindings.available,
        )
        if (motion.haloBloom || motion.particles || motion.shockwave) {
            if (backdrop.update(featureBindings, motion, dtMs, active)) changed = true
        }
        if (changed) generationState.intValue++
        return changed
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
    }
}

/**
 * v2.9.0：**挂载帧时钟**（在展开态播放器子树里挂**恰好一次**）。
 *
 * ## 挂载条件由调用方给（[enabled]），内部再判一次「有没有东西要动」
 *
 * 关掉「界面动效」总开关、且波形也没挂载时，本组件**不跑任何循环**
 * （`LaunchedEffect` 的 key 里带 `clockNeeded`，为 false 时直接 return）——
 * 这就是「关掉开关 = 零开销」被代码结构保证，而不是写在注释里（v2.8.0 的纪律）。
 *
 * ## v3.0.0：帧时间监控（与自动降级）已删除
 *
 * v2.8.0 起本组件挂过 `VisualizerFrameMonitor`，v2.9.0 把注册点从波形组件搬到这里。
 * v3.0.0 删掉了它：**渲染只看用户选的档位**。留下的 `FrameBudgetPolicy` /
 * `VisualizerFrameMonitor` 两个类仅供诊断复用，**没有任何生产调用点**（见它们的 KDoc）。
 *
 * @param activeProvider 播放中且未在缓冲。**用 lambda 而不是布尔参数**：这个值只在帧循环里读，
 *   传布尔会让 `PlayerCard` 订阅 `isPlaying`/`isBuffering` 而整树重组（AGENTS.md「GPU 零重组」）。
 * @param enabled 调用方的挂载判据（展开态 + 有歌）。
 */
@Composable
fun MotionFrameClock(
    activeProvider: () -> Boolean,
    enabled: Boolean,
) {
    val context = LocalContext.current
    val currentActive = rememberUpdatedState(activeProvider)
    // 组合期读一次（改设置时才会重组一次）。帧路径里零 state 读。
    val motion = MotionPrefs.effects.value
    val waveform = VisualizerPrefs.effects.value
    val frameIntervalMs = remember(context) { visualizerFrameIntervalMs(context) }

    val clockNeeded = enabled && (
        motion.needsFrameClock || waveform.tier > VisualizerTier.SIMPLE || waveform.anyShowcase
        )

    // ---- 唯一的帧循环 ----
    LaunchedEffect(clockNeeded, frameIntervalMs, motion, waveform) {
        if (!clockNeeded) return@LaunchedEffect
        val budgetNs = frameIntervalMs * 1_000_000L
        // 帧时钟状态放进**循环外**的复用数组，回调也提升到循环外（v2.8.0 的零分配写法）。
        val clock = LongArray(2)
        val onFrame: (Long) -> Unit = { now ->
            if (clock[0] == 0L || now - clock[0] >= budgetNs) {
                val dtMs = if (clock[1] == 0L) frameIntervalMs.toFloat()
                else ((now - clock[1]) / 1_000_000f).coerceIn(1f, 100f)
                clock[0] = now
                clock[1] = now
                MotionClock.frame(active = true, dtMs = dtMs, waveform = waveform, motion = motion)
            }
        }
        while (true) {
            if (currentActive.value()) {
                withFrameNanos(onFrame)
            } else {
                delay(frameIntervalMs)
                clock[0] = 0L
                clock[1] = 0L
                MotionClock.frame(
                    active = false,
                    dtMs = frameIntervalMs.toFloat(),
                    waveform = waveform,
                    motion = motion,
                )
            }
        }
    }
}
