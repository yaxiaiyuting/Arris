/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.motion

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerFrameMonitor
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
 * ## 一条循环干三件事（顺序不能反）
 *
 * 1. `WaveformStore.pump(...)` —— v2.8.0 的波形推进（含 C 档涟漪/粒子），**一行没改**；
 * 2. `MotionEnvelope.update(...)` —— 读**刚刚 pump 过**的最新柱值算响度包络与重拍；
 * 3. `MotionBackdropState.update(...)` —— 强拍 → 光晕 / 粒子。
 *
 * ## 不空转（沿用 v2.8.0 的契约）
 *
 * 播放中走 `withFrameNanos`（并且只在 `pump` 真的有变化时才失效）；暂停/缓冲时走
 * `delay(frameIntervalMs)` —— 归零与收敛不需要跟着刷新率走。低端机的重绘上限仍是
 * 30fps（`visualizerFrameIntervalMs`），**与 v2.8.0 逐字一致**。
 */
object MotionClock {

    private val generationState = mutableIntStateOf(0)

    /** 只应在 draw / `graphicsLayer` 块里读（在组合阶段读会变成每帧重组）。 */
    val generation: Int get() = generationState.intValue

    private val envelope = MotionEnvelope()
    private val backdrop = MotionBackdropState()

    /** 平滑响度（0..1）。draw 阶段直接读。 */
    fun level(): Float = envelope.level()

    /** 重拍脉冲（0..1）。draw 阶段直接读。 */
    fun pulse(): Float = envelope.pulse()

    /** C 档背景粒子 / 光晕的只读访问（draw 阶段直接读）。 */
    val backstage: MotionBackdropState get() = backdrop

    /**
     * 推进一步。**只在 [MotionFrameClock] 的循环里调用**（每帧一次）。
     *
     * @return 界面动效这一层是否需要重绘（波形的失效由 `WaveformStore` 自己管）。
     */
    fun frame(active: Boolean, dtMs: Float, waveform: VisualizerEffects, motion: MotionEffects): Boolean {
        WaveformStore.pump(active, dtMs, waveform)
        var changed = envelope.update(WaveformStore.newestBar(), dtMs, active)
        if (motion.haloBloom || motion.particles) {
            val backdropChanged = backdrop.update(
                strongBeat = envelope.strongBeat,
                dtMs = dtMs,
                haloEnabled = motion.haloBloom,
                particlesEnabled = motion.particles,
            )
            if (backdropChanged) changed = true
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
        generationState.intValue++
    }

    /** 单测 / 诊断。 */
    internal fun resetForTest() {
        envelope.clear()
        backdrop.clear()
        generationState.intValue = 0
    }
}

/**
 * v2.9.0：**挂载帧时钟 + 帧时间监控**（在展开态播放器子树里挂**恰好一次**）。
 *
 * ## 挂载条件由调用方给（[enabled]），内部再判一次「有没有东西要动」
 *
 * 关掉「界面动效」总开关、且波形也没挂载时，本组件**不跑任何循环**
 * （`LaunchedEffect` 的 key 里带 `clockNeeded`，为 false 时直接返回）——
 * 这就是「关掉开关 = 零开销」被代码结构保证，而不是写在注释里（v2.8.0 的纪律）。
 *
 * ## 帧时间监控为什么也搬到这里
 *
 * v2.8.0 的 `VisualizerFrameMonitor` 注册在波形组件里。v2.9.0 的降级阶梯
 * （[MotionDegrade]）需要覆盖界面动效 —— 竖屏用户开着背景模糊与呼吸时，
 * 波形组件不存在，监控就永远不会注册，「自动降级优先砍界面动效」也就永远不会触发。
 * 所以监控跟着帧时钟走。
 *
 * 注册条件四条，缺一不可：
 *  1. 本组件挂载（展开态播放器在屏幕上）；
 *  2. 宿主 Activity 找得到（预览/测试宿主里找不到就静默不监控）；
 *  3. **确实有东西在动**（波形档位高于简洁档，或界面动效开着）—— 没有的话注册只是白看帧；
 *  4. 降级阶梯还没到顶（到顶之后永不恢复、也永不二次降级）。
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
    // 组合期读一次（改设置或发生一次自动降级时才会重组一次）。帧路径里零 state 读。
    val motion = MotionPrefs.effects.value
    val waveform = VisualizerPrefs.effects.value
    val frameIntervalMs = remember(context) { visualizerFrameIntervalMs(context) }

    val clockNeeded = enabled && (
        motion.needsFrameClock || waveform.tier > VisualizerTier.SIMPLE || waveform.anyShowcase
        )

    // ---- 帧时间监控（一次性自动降级，每进程最多推进一级）----
    val hostActivity = remember(context) { findHostActivity(context) }
    // v2.9.0：多加一条「本进程还没判定过」。`FrameBudgetPolicy` 只保证**每个实例**判定一次，
    // 而这个 `DisposableEffect` 会在播放器收起再展开（或 motion/monitorEnabled 变化）时
    // 重新注册一个新实例 —— S6 实测因此出现过「同一进程水位 0 → 2」。真正的「每进程一级」
    // 由 `MotionPrefs.hasDecidedDegradeThisProcess()` 提供。
    val monitorEnabled = clockNeeded &&
        motion.degradeLevel < MotionDegrade.MAX &&
        !MotionPrefs.hasDecidedDegradeThisProcess() &&
        (waveform.tier > VisualizerTier.SIMPLE || motion.anyUiMotion)
    DisposableEffect(hostActivity, monitorEnabled) {
        val activity = hostActivity
        if (!monitorEnabled || activity == null) {
            onDispose { }
        } else {
            val monitor = VisualizerFrameMonitor.start(activity) { overBudget, windowFrames ->
                // 降级动作本身也在隔离边界里（写 prefs + 刷新状态都可能失败，失败就当没降）。
                // reason 带上判据本身：`motion_degrade_log` 落盘后，这就是"为什么画面变简单了"
                // 的唯一解释来源（真机上缺了它，用户只能报"回退成老 UI 了"）。
                runCatching {
                    MotionPrefs.applyAutoDowngrade(
                        activity,
                        reason = "over-budget $overBudget/$windowFrames",
                        // 轻微超标只砍 B/C；严重超标（≥70%）才允许继续往下推（见 maxLevelFor 的 KDoc）。
                        severe = MotionDegrade.isSevere(overBudget, windowFrames),
                    )
                }
            }
            onDispose { monitor?.stop() }
        }
    }

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

/**
 * 从 `LocalContext` 往上找宿主 Activity。
 *
 * 找不到（Compose 预览、`ComponentActivity` 之外的宿主）返回 `null` —— 调用方据此
 * **静默不监控**，绝不抛异常（v2.8.0 · `AudioVisualizer.kt` 里的同名函数原样搬来，
 * 两处各留一份是为了不让播放器模块反向依赖动效模块）。
 */
internal fun findHostActivity(context: Context): Activity? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
