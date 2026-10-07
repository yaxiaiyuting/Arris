/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.4 · P1：**显式处理设备刷新率**（铁律 33）。
 */

package com.takahashirinta.ncrust.ui.player.motion

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlin.math.ceil

/**
 * v3.2.4 · P1：**帧节拍与设备刷新率**（纯逻辑，JVM 直测）。
 *
 * ## 为什么需要这个对象（它不是抽象，是一次真机缺陷的产物）
 *
 * v3.2.3 及以前，波形/界面动效的帧循环用的是一个**写死的重绘预算**：
 * `visualizerFrameIntervalMs(context)` = 16ms（非低内存设备）或 33ms（低内存设备），
 * 而循环的判据是 `now - 上次推进 >= 预算`，`now` 只能取到**整数个 vsync**。
 * 于是同一个 16ms 在不同面板上语义完全不同（实测与源码算术见
 * `docs/verification/v3.2.4/probe-waveform-jitter-hfr.md` §0）：
 *
 * | 面板 | vsync | `ceil(16ms/vsync)` | 名义帧间隔 | 距 16ms 余量 | 结果 |
 * |---|---|---|---|---|---|
 * | 60Hz | 16.667ms | 1 | 16.667ms | +0.667ms | **每帧都推进**，无量化 ⇒ 平滑 |
 * | 90Hz | 11.111ms | 2 | 22.222ms | +6.222ms | 推进率只有面板的 **50%** |
 * | 120Hz | 8.333ms | 2 | 16.667ms | **+0.667ms** | 一抖就变成 3 个 vsync（**25ms**）⇒ **±49% 的位移跳变 = 用户报的「抖」** |
 * | 144Hz | 6.944ms | 3 | 20.833ms | +4.833ms | 推进率只有面板的 **33%** |
 *
 * 判据换成**帧步长**（每 N 帧推进一次）而不是**时间阈值**，就把这件事变成精确的：
 *
 * - 非低内存设备：**N = 1**，即 `withFrameNanos` 回调一次就推进一次 ——
 *   节拍器交给 vsync 自己（它本来就与面板同源），60/90/120/144Hz 全部**恰好等于面板刷新率**；
 * - 低内存设备：`N = ceil(33ms / 帧间隔)` —— 60Hz 上 N=2（33.3ms）、
 *   120Hz 上 N=4（33.3ms）、90Hz 上 N=3（33.3ms），**始终约 30fps**，与 v1.8.1 的
 *   「低端机降帧率」契约一致，而且不受 frame pacing 抖动影响（数帧不数时间）。
 *
 * ## 为什么不是「预算 = 1/刷新率」
 *
 * 那等于把阈值的余量压到 **0**：`now - last >= 16.666ms` 里的 `now - last` 恰好是
 * `16666666ns` 量级，一次舍入或一次轻微抖动就会漏掉一帧、并把下一帧的位移翻倍 ——
 * 正是本版要修的那个形状。**数帧**没有这个问题。
 *
 * ## 刷新率本身从哪来
 *
 * [rememberDisplayRefreshRate]：组合期读 `View.display.refreshRate`，并用
 * `DisplayManager.DisplayListener` 跟随**运行时变化**（面板在 60/120Hz 之间切换、
 * LTPO 动态变频、外接屏）——刷新率是**运行时行为**，不是编译期常量（铁律 33）。
 * 取不到时回落 [FALLBACK_HZ] 并在 [sanitizeHz] 里夹到 [MIN_HZ]..[MAX_HZ]。
 */
object DisplayRefresh {

    /** 取不到刷新率时的回落值。取 60 是因为它对所有设备都是一个安全的节拍。 */
    const val FALLBACK_HZ = 60f

    /** 合法的刷新率区间；区间外一律当取不到（不猜）。 */
    const val MIN_HZ = 20f
    const val MAX_HZ = 240f

    /**
     * 低内存设备的重绘间隔上限（v1.8.1 的 30fps 契约）。
     *
     * 只对 `ActivityManager.isLowRamDevice` 生效 —— 「系统版本老」不再是判据
     * （v3.2.3 已经因为用户实测把它删掉了：S6 被 33ms 钉在 30fps，而它实测跑得动 ~50fps）。
     */
    const val LOW_TIER_BUDGET_MS = 33f

    /** 把任意输入夹成一个可信的刷新率。NaN / Inf / 0 / 负数 / 超界一律回落 [FALLBACK_HZ]。 */
    fun sanitizeHz(hz: Float): Float =
        if (hz.isFinite() && hz >= MIN_HZ && hz <= MAX_HZ) hz else FALLBACK_HZ

    /** 一个刷新周期的毫秒数（保证 > 0）。 */
    fun frameIntervalMs(hz: Float): Float = 1000f / sanitizeHz(hz)

    /**
     * **每几帧推进一次**（帧步长）。
     *
     * - 非低内存设备 ⇒ **1**（每帧都推进 = 与面板同频）；
     * - 低内存设备 ⇒ `ceil(33ms / 帧间隔)`，最小 1。
     */
    fun strideFor(hz: Float, lowTier: Boolean): Int {
        if (!lowTier) return 1
        val stride = ceil(LOW_TIER_BUDGET_MS / frameIntervalMs(hz)).toInt()
        return stride.coerceAtLeast(1)
    }

    /** 帧步长对应的**名义**帧间隔（诊断 / 单测用；真实 dt 一律以帧时间戳为准）。 */
    fun pacedIntervalMs(hz: Float, lowTier: Boolean): Float =
        strideFor(hz, lowTier) * frameIntervalMs(hz)
}

/**
 * v3.2.4 · P1：**数帧**的节流器（纯逻辑，JVM 直测）。
 *
 * 为什么是「数帧」而不是「数时间」：[DisplayRefresh] 的 KDoc 说明了原因 ——
 * 时间阈值必须落在整数个 vsync 上，于是同一个 16ms 在 60Hz 上是「每帧」、
 * 在 120Hz 上是「每两帧且余量 0.667ms」。数帧没有阈值，也就没有量化。
 *
 * `stride <= 1` 时 [shouldAdvance] 恒真（每帧都推进，节拍器就是 vsync 本身）。
 * 非线程安全：它只被**一条**帧循环持有（`MotionFrameClock` 的 `LaunchedEffect`）。
 */
class FrameStride(private val stride: Int) {

    private var seen = 0

    /** 本帧要不要推进。 */
    fun shouldAdvance(): Boolean {
        if (stride <= 1) return true
        seen += 1
        if (seen >= stride) {
            seen = 0
            return true
        }
        return false
    }

    /** 暂停/起播时清掉半程计数（否则恢复后的第一次推进会提前或延后一帧）。 */
    fun reset() {
        seen = 0
    }

    /** 单测 / 诊断。 */
    val effectiveStride: Int get() = stride.coerceAtLeast(1)
}

/**
 * 当前显示的刷新率（Hz），**跟随运行时变化**。
 *
 * 读的是 `View.display.refreshRate`（与 `Choreographer` 的 vsync 同源），
 * 并注册 `DisplayManager.DisplayListener`：面板从 120Hz 切到 60Hz（或反过来）时
 * 状态会更新一次 ⇒ 只有 `MotionFrameClock` 的 `LaunchedEffect` 会重起，
 * **不产生逐帧重组**（刷新率在一次播放里通常只变 0~2 次）。
 *
 * 取不到 `View.display` 时回落到 `DisplayManager.getDisplay(DEFAULT_DISPLAY)`，
 * 再取不到就用 [DisplayRefresh.FALLBACK_HZ]。
 */
@Composable
fun rememberDisplayRefreshRate(): Float {
    val context = LocalContext.current
    val view = LocalView.current
    val fallback = remember(context) { defaultRefreshRate(context) }
    var hz by remember(view) { mutableFloatStateOf(view.display?.refreshRate ?: fallback) }

    DisposableEffect(view, context) {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                val display = view.display ?: return
                if (display.displayId != displayId) return
                val next = DisplayRefresh.sanitizeHz(display.refreshRate)
                if (next != hz) hz = next
            }
        }
        runCatching { manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper())) }
        onDispose { runCatching { manager?.unregisterDisplayListener(listener) } }
    }
    return DisplayRefresh.sanitizeHz(hz)
}

/** 拿不到 `View.display`（组合期视图还没 attach）时的兜底。 */
private fun defaultRefreshRate(context: Context): Float {
    val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    val display = manager?.getDisplay(Display.DEFAULT_DISPLAY)
    return DisplayRefresh.sanitizeHz(display?.refreshRate ?: DisplayRefresh.FALLBACK_HZ)
}
