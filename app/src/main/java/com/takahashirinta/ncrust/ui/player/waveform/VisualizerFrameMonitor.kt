/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window

/**
 * v2.8.0 · P1-A：帧时间监控（`Window.addOnFrameMetricsAvailableListener`，**API 24+**，正好等于 minSdk）。
 *
 * ## 为什么不用 `withFrameNanos` 的间隔当判据
 *
 * AGENTS.md 记录了 S6 的**流水线式掉帧**：帧回调仍然 60Hz、每帧延迟约 19ms ——
 * 帧**间隔**量不出真实压力，v1.6.0 已经踩过这条并回退。`FrameMetrics.TOTAL_DURATION` 是平台
 * 给出的这一帧的实际时长，是这里唯一可用的现成信号（探针 §4.2 的候选 3）。
 *
 * ## 隔离与有界（铁律 1 / 4）
 *
 *  - 注册、回调、注销**三处都在隔离边界里**：任何一个环节抛异常都不许传播
 *    （回调跑在**主线程**上，抛出去就是崩溃）；
 *  - 判定逻辑在纯函数 [FrameBudgetPolicy] 里（可 JVM 单测），本类只负责"接平台信号 + 有界收尾"；
 *  - 判定成立后**立刻注销**自己：此后不再看任何一帧，也不做第二次判定；
 *  - 只在可视化挂载期间注册（调用方用 `DisposableEffect`），没有常驻监听。
 *
 * ## 只做一次性降级，不参与任何 UI 逻辑
 *
 * 拿到的唯一动作是"通知调用方一次"，由调用方决定降到哪一档
 * （见 `VisualizerPrefs.applyAutoDowngrade`：只降一级、写标记、永不自动恢复）。
 */
internal class VisualizerFrameMonitor private constructor(
    private val targetWindow: Window,
    private val listener: Window.OnFrameMetricsAvailableListener,
) {
    /** 注销监听。**幂等**、**绝不抛**（判定成立与自己 `onDispose` 两条路径都可能调到）。 */
    fun stop() {
        runCatching { targetWindow.removeOnFrameMetricsAvailableListener(listener) }
    }

    /** 监听器本体：**具名类**而不是 lambda —— 触发时要注销自己（lambda 里 `this` 不指向它）。 */
    private class Listener(
        private val window: Window,
        private val onSustainedOverBudget: (overBudgetFrames: Int, windowFrames: Int) -> Unit,
    ) : Window.OnFrameMetricsAvailableListener {

        private val policy = FrameBudgetPolicy()

        override fun onFrameMetricsAvailable(window: Window?, frameMetrics: FrameMetrics?, dropCount: Int) {
            // 整个回调体都在隔离边界里：这里只是"看一眼帧时长"，不值得让主线程崩。
            runCatching {
                val metrics = frameMetrics ?: return
                val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                if (!policy.onFrame(duration)) return
                // 先注销再回调：即使回调抛异常（也被下面吞掉），也不会继续采样。
                runCatching { this.window.removeOnFrameMetricsAvailableListener(this) }
                // v2.9.0：把"窗口内超标多少帧"一并交出去 —— 降级日志必须能写清判据，
                // 否则下一次再有人看到"画面自己变简单了"仍然无从归因。
                onSustainedOverBudget(policy.overBudgetCount(), FrameBudgetPolicy.WINDOW_FRAMES)
            }
        }
    }

    companion object {
        /**
         * 注册监听。任何一步失败都返回 `null`（= 不监控），**绝不抛异常**。
         *
         * @param onSustainedOverBudget 判定「持续超标」时回调一次（在主线程上）。调用方负责
         *   有界降级；本类在回调后立即注销。
         */
        fun start(
            activity: Activity,
            onSustainedOverBudget: (overBudgetFrames: Int, windowFrames: Int) -> Unit,
        ): VisualizerFrameMonitor? {
            val window = runCatching { activity.window }.getOrNull() ?: return null
            val handler = runCatching { Handler(Looper.getMainLooper()) }.getOrNull() ?: return null
            val listener = Listener(window, onSustainedOverBudget)
            return runCatching {
                window.addOnFrameMetricsAvailableListener(listener, handler)
                VisualizerFrameMonitor(window, listener)
            }.getOrNull()
        }
    }
}
