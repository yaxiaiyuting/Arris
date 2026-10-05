/*
 * Ncrust —— ncm 第三方客户端
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
import com.takahashirinta.ncrust.ui.player.visualizerFrameStride
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerPrefs
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
     * @param nowMs **这一帧的时间戳**（毫秒），必须与音频线程打在柱上的到达时间戳**同一个时钟**。
     *   v3.4.7 起它一路传到 [WaveformStore.pump] → `WaveformRing.pump`，成为波形滚动相位的分子
     *   （相位 = `nowMs − 最后一根被消费的柱的到达时刻`）。**不要在这里传 `dt` 的累加值** ——
     *   那正是 v3.3.2~v3.4.6 的缺陷（自攒帧时钟被 `.toLong()` 逐帧截断 ⇒ 4%/秒的系统性漂移）。
     *   传 0 = 没有帧时钟（单测 / 老调用点），ring 回落到旧的累加口径。
     * @return 界面动效这一层是否需要重绘（波形的失效由 `WaveformStore` 自己管）。
     */
    fun frame(
        active: Boolean,
        dtMs: Float,
        waveform: VisualizerEffects,
        motion: MotionEffects,
        nowMs: Long = 0L,
    ): Boolean {
        WaveformStore.pump(active, dtMs, waveform, nowMs)
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
 * ## 挂载条件由调用方给（[enabled] + [waveformMounted]），内部再判一次「有没有东西要动」
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
 * ## v3.2.0：[waveformMounted] 把「波形的帧推进」与「界面动效的帧推进」解耦
 *
 * 上一版的判据是 `motion.needsFrameClock || waveform.tier > SIMPLE || waveform.anyShowcase`，
 * 而 v2.9.0/v3.0.0 的**简洁档**恰好有一个逐帧的界面动效（背景呼吸）⇒
 * 简洁档的波形柱一直是**搭着背景呼吸的便车**在推进的。P0-B 把呼吸收窄到精致档之后，
 * 这条便车没有了：不同时把「波形挂载」显式告诉帧时钟，简洁档的波形会**冻住**
 * （`AudioVisualizerBars` 自 v2.9.0 起是纯读取方，自己不再推进）。
 *
 * 现在的关系是干净的：**波形挂载 ⇒ 要帧；界面动效需要 ⇒ 要帧**，两者取或。
 *
 * ## v3.2.4 · P1：节拍从「时间阈值」改成「**帧步长**」，并显式读设备刷新率
 *
 * 上一版的循环是 `if (now - 上次推进 >= 16ms)`。这个判据只能落在**整数个 vsync** 上，
 * 而 16ms 在不同面板上的含义完全不同：60Hz 面板上 1 个 vsync = 16.667ms（**每帧都推进**，
 * 没有量化 ⇒ 平滑），120Hz 面板上 2 个 vsync = 16.667ms（**每两帧推进一次，余量只有
 * 0.667ms**，frame pacing 一抖就变成 3 个 vsync = 25ms）——**同一份代码，60Hz 平滑、
 * 120Hz 抖**，这就是 v3.2.4 的 P1。根因、仿真与判据见
 * `docs/verification/v3.2.4/probe-waveform-jitter-hfr.md`。
 *
 * 现在：
 * - **刷新率是读出来的**（[rememberDisplayRefreshRate]，跟随面板切换与 LTPO 变频）；
 * - **步长是数出来的**（[DisplayRefresh.strideFor]）：非低内存设备 = **1**（每帧都推进 ⇒
 *   与面板同频），低内存设备 = `ceil(33ms / 帧间隔)`（仍然约 30fps，且不受抖动影响）；
 * - `dtMs` 依旧取**真实帧时间戳之差**，所以插值的时间基准与刷新率无关（这一条没改）。
 *
 * @param activeProvider 播放中且未在缓冲。**用 lambda 而不是布尔参数**：这个值只在帧循环里读，
 *   传布尔会让 `PlayerCard` 订阅 `isPlaying`/`isBuffering` 而整树重组（AGENTS.md「GPU 零重组」）。
 * @param enabled 调用方的挂载判据（展开态 + 有歌）。
 * @param waveformMounted 波形组件此刻是否挂载（`PlayerLayout.visualizerSlot ||
 *   motion.fullScreenWaveform`）。它**每一档**都需要帧推进 —— 波形柱跟的是 RMS，
 *   与「这一档有哪些界面动效」是两件事。
 */
@Composable
fun MotionFrameClock(
    activeProvider: () -> Boolean,
    enabled: Boolean,
    waveformMounted: Boolean,
) {
    val context = LocalContext.current
    val currentActive = rememberUpdatedState(activeProvider)
    // 组合期读一次（改设置 / 换面板刷新率时才会重组一次）。帧路径里零 state 读。
    val motion = MotionPrefs.effects.value
    val waveform = VisualizerPrefs.effects.value
    val refreshHz = rememberDisplayRefreshRate()
    val stride = remember(context, refreshHz) { visualizerFrameStride(context, refreshHz) }
    val frameIntervalMs = remember(refreshHz) { DisplayRefresh.frameIntervalMs(refreshHz) }

    val clockNeeded = enabled && (motion.needsFrameClock || waveformMounted)

    // ---- 唯一的帧循环 ----
    LaunchedEffect(clockNeeded, stride, frameIntervalMs, motion, waveform) {
        if (!clockNeeded) return@LaunchedEffect
        // 帧时钟状态放进**循环外**的复用数组，回调也提升到循环外（v2.8.0 的零分配写法）。
        val clock = LongArray(2)
        val pacer = FrameStride(stride)
        val onFrame: (Long) -> Unit = { now ->
            // ★ 数帧，不数时间：stride=1 时每帧都推进，节拍器就是 vsync 本身。
            if (pacer.shouldAdvance()) {
                val dtMs = if (clock[1] == 0L) frameIntervalMs
                else ((now - clock[1]) / 1_000_000f).coerceIn(1f, 100f)
                clock[0] = now
                clock[1] = now
                // ★ v3.4.7：把**这一帧自己的时间戳**原样交给波形（`withFrameNanos` 的
                //   `frameTimeNanos` 与柱到达时间戳的 `SystemClock.uptimeMillis()` 都是
                //   单调时钟、同一个基准）。波形滚动的相位就是它与柱到达时刻之差 ——
                //   以前这里只给 `dtMs`，环自己累加出来的帧时钟被 `.toLong()` 截断，
                //   与到达时间戳不再是同一个时钟（4%/秒的漂移，就是「抽搐」的根因）。
                MotionClock.frame(
                    active = true,
                    dtMs = dtMs,
                    waveform = waveform,
                    motion = motion,
                    nowMs = now / 1_000_000L,
                )
            }
        }
        while (true) {
            if (currentActive.value()) {
                withFrameNanos(onFrame)
            } else {
                delay(frameIntervalMs.toLong().coerceAtLeast(1L))
                clock[0] = 0L
                clock[1] = 0L
                pacer.reset()
                MotionClock.frame(
                    active = false,
                    dtMs = frameIntervalMs,
                    waveform = waveform,
                    motion = motion,
                )
            }
        }
    }
}
