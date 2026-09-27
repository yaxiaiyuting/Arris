/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerPrefs
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import kotlinx.coroutines.delay

/**
 * v1.8.0 · T3：「音频可视化」开关的**唯一读写入口**（与 RotationSetting 同一套写法）。
 *
 * 默认**开**：可视化只出现在横屏大屏模式，不影响竖屏日常使用；关掉时
 * [AudioVisualizerBars] 整个不挂载（连帧时钟都不跑），所以关掉等于这个功能不存在。
 *
 * v2.8.0 · P1-A：本对象同时是分级设置的**进程内初始化入口**
 * （[read] 里调用 `VisualizerPrefs.ensureLoaded`，见那里的注释）。
 */
object VisualizerSetting {

    private const val PREFS = "ncrust_settings"
    private const val KEY = "audio_visualizer"

    const val DEFAULT_ENABLED = true

    private val stateHolder = mutableStateOf(DEFAULT_ENABLED)
    private var loadedFromDisk = false

    val state: MutableState<Boolean> get() = stateHolder

    fun read(context: Context): Boolean {
        if (!loadedFromDisk) {
            val enabled = prefs(context).getBoolean(KEY, DEFAULT_ENABLED)
            stateHolder.value = enabled
            WaveformStore.enabled = enabled
            loadedFromDisk = true
        }
        // v2.8.0 · P1-A：分级（档位 / C 档细分 / 自动降级标记）与总开关共用同一个初始化入口。
        // 放在这里而不是 NcrustApplication：调用点 MainActivity.onCreate 与 PlaybackService
        // 都已经在读总开关，多一个 Application 改动只会多一处「谁先谁后」的不确定性。
        // ensureLoaded 自身幂等，重复调用只是多一次布尔判断。
        VisualizerPrefs.ensureLoaded(context)
        return stateHolder.value
    }

    fun write(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY, enabled).apply()
        loadedFromDisk = true
        stateHolder.value = enabled
        // 音频线程侧只读这一个 volatile 布尔：关掉之后 render 循环里每次回调
        // 只剩一次 volatile 读，等于零开销（不会去动 AudioProcessor 链，
        // 因为重建 ExoPlayer 的代价远大于这点开销）。
        WaveformStore.enabled = enabled
    }

    internal fun resetForTest() {
        stateHolder.value = DEFAULT_ENABLED
        loadedFromDisk = false
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * v1.8.0 · T3：音频柱状数据的**进程内单例**（音频线程写 / UI 线程读的唯一交汇点）。
 *
 * 数据来源：[com.takahashirinta.ncrust.player.VisualizerRenderersFactory] 注入的
 * `TeeAudioProcessor` + [com.takahashirinta.ncrust.player.TransparentWaveformSink]
 * （**不需要任何权限**，也绕开了 `android.media.audiofx.Visualizer` 那条要 RECORD_AUDIO 的路）。
 *
 * v2.8.0 · P1-A：这里原先写的是「media3 官方 API `WaveformAudioBufferSink`」—— 那句话从
 * v2.2.1 起就过期了（它正是 6→1 混音抛异常、把 AudioSink 打挂的那个类，已被上文的 tee 替换）。
 *
 * 为什么 [generation] 用 Compose 状态而不是回调：柱状图必须在 **draw 阶段**读取数据，
 * 用状态失效只触发重绘、不触发重组（与播放器卡片的零重组原则一致）。
 */
object WaveformStore {

    /** 画面上的柱子数。28 根在 PCL110（左栏 352dp 宽）上单根约 9dp，看得清又不糊。 */
    const val BAR_COUNT = 28

    /**
     * 环形缓冲容量（柱数）。
     *
     * **真实柱率 = `handleBuffer` 回调率**（`TransparentWaveformSink.handleBuffer` 每回调推一根柱），
     * 由解码器/渲染器送进 sink 的缓冲粒度决定，**代码不保证任何固定值**（探针 §7 #1 明确未确认）。
     * 所以这里不写「积压 N 秒」这种换个音频格式就不成立的换算：本容量只承诺一件事 ——
     * UI 卡顿时最多积压 256 根柱，再多就丢最旧的（绝不回压音频线程）。
     *
     * v2.8.0 · P1-A：删掉 `BARS_PER_SECOND = 30`（v1.8.1 引入）。它是 v2.2.1 换掉的
     * media3 `WaveformAudioBufferSink(barsPerSecond, …)` 的消费者参数，**全仓已无任何引用**，
     * 「30 柱/秒」和由它推出的「最多积压 8.5 秒」都是过期结论（探针 §0 发现 1）。
     * 保留一个没人读、却让注释继续撒谎的常量，比删掉它更危险。
     */
    private const val CAPACITY = 256

    private val ring = WaveformRing(capacity = CAPACITY, barCount = BAR_COUNT)

    /**
     * 开关的**音频线程侧镜像**。volatile：关掉后 render 回调里只剩一次读。
     * 之所以不动态拆掉 AudioProcessor：ExoPlayer 建好之后改不了 audio sink，
     * 为了一个开关重建播放器会打断播放 —— 而这里省下的开销本来就是纳秒级。
     */
    @Volatile
    var enabled: Boolean = VisualizerSetting.DEFAULT_ENABLED

    private val generationState = mutableIntStateOf(0)

    /** 只应在 draw 阶段读（在组合阶段读会变成每帧重组）。 */
    val generation: Int get() = generationState.intValue

    /** **音频线程**调用：零分配、零锁。 */
    fun onBar(rootMeanSquare: Double) {
        if (enabled) ring.push(rootMeanSquare.toFloat())
    }

    /**
     * UI 线程按帧率调用；有新数据（或平滑尚未收敛）时才让画面失效。
     * @param dtMs 距上一帧的毫秒数（平滑系数由它算，见 [WaveformRing.pump]）。
     */
    fun pump(active: Boolean, dtMs: Float) {
        if (ring.pump(active, dtMs)) generationState.intValue++
    }

    /** UI 线程：把滚动窗口拷进复用数组。 */
    fun snapshot(destination: FloatArray) = ring.copyInto(destination)

    /** 单测 / 调试用。 */
    internal fun resetForTest() {
        ring.clear()
        enabled = VisualizerSetting.DEFAULT_ENABLED
    }
}

/** 现代设备的可视权重绘间隔：16ms ≈ 60fps。 */
const val VISUALIZER_FRAME_INTERVAL_FAST_MS = 16L

/** 低端设备的可视权重绘间隔：33ms ≈ 30fps（数据照旧按回调率到达，只是平滑步长更大）。 */
const val VISUALIZER_FRAME_INTERVAL_SLOW_MS = 33L

/**
 * v1.8.1：可视化的重绘上限间隔。
 *
 * - 现代设备（API 26+ 且非低内存）：**16ms ≈ 60fps**
 * - API < 26 或 `isLowRamDevice`：**33ms ≈ 30fps**
 *
 * 为什么按"设备档位"而不是按"实测帧间隔"自动降级：S6 是流水线式掉帧（帧回调仍 60Hz、
 * 每帧延迟 ~19ms），帧间隔量不出真实压力 —— v1.6.0 已经踩过这条并回退（见 AGENTS.md
 * 的 D2 节）。设备档位是静态、可预期、可单测的判据。
 *
 * 注意：平滑是**时间常数**形式（`1 - exp(-dt/tau)`），所以 30fps 与 60fps 画出来的是
 * 同一条运动曲线，降帧率只损失顺滑度、不改变幅度与节奏。
 */
fun visualizerFrameIntervalMs(context: Context): Long {
    val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val lowTier = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        activityManager?.isLowRamDevice == true
    return if (lowTier) VISUALIZER_FRAME_INTERVAL_SLOW_MS else VISUALIZER_FRAME_INTERVAL_FAST_MS
}

/**
 * v1.8.0 · T3（v1.8.1 提帧）：横屏大屏模式左栏的**音频可视化条**（封面下、歌名/作者上）。
 *
 * 性能契约（S6 基线是硬指标，写在这里防止被"顺手优化"掉）：
 *
 *  - **不重组**：[WaveformStore.generation] 只在 [Canvas] 的 draw lambda 里读，
 *    失效范围是"重绘这一块画布"；柱高数组是 `remember` 出来的 `FloatArray`，逐帧原地更新，
 *    **零分配**。
 *  - **不空转**：暂停 / 缓冲时走 `delay` 而不是帧时钟；数据归零且平滑收敛之后
 *    [WaveformStore.pump] 返回 false，画面不再失效（不会出现"暂停了还在 60fps 重绘"）。
 *  - **不拖音频线程**：音频线程每回调只做一次 RMS 遍历 + 一次数组写；关掉开关时
 *    连 RMS 都不算（开关判断在 `TransparentWaveformSink.handleBuffer` 里前置到 RMS 之前）。
 *
 * @param activeProvider 播放中且未在缓冲。用 lambda 而不是布尔参数：这个值只在
 *   帧循环里读，传布尔会让 PlayerCard 订阅 isPlaying/isBuffering 而整树重组
 *   （AGENTS.md「GPU 零重组」）。
 */
@Composable
fun AudioVisualizerBars(
    activeProvider: () -> Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = WaveformStore.BAR_COUNT,
) {
    val context = LocalContext.current
    val barColor = LocalMetroColors.current.primary
    val currentActive = rememberUpdatedState(activeProvider)
    val bars = remember(barCount) { FloatArray(barCount) }
    // 设备档位只算一次（isLowRamDevice 不会变）。
    val frameIntervalMs = remember(context) { visualizerFrameIntervalMs(context) }

    LaunchedEffect(barCount, frameIntervalMs) {
        val budgetNs = frameIntervalMs * 1_000_000L
        var lastFrameNs = 0L
        var lastPumpNs = 0L
        while (true) {
            if (currentActive.value()) {
                withFrameNanos { now ->
                    if (lastFrameNs == 0L || now - lastFrameNs >= budgetNs) {
                        val dtMs = if (lastPumpNs == 0L) frameIntervalMs.toFloat()
                        else ((now - lastPumpNs) / 1_000_000f).coerceIn(1f, 100f)
                        lastFrameNs = now
                        lastPumpNs = now
                        WaveformStore.pump(active = true, dtMs = dtMs)
                    }
                }
            } else {
                // 暂停 / 缓冲：用 delay 而不是帧时钟 —— 归零过程不需要跟着刷新率走。
                delay(frameIntervalMs)
                lastFrameNs = 0L
                lastPumpNs = 0L
                WaveformStore.pump(active = false, dtMs = frameIntervalMs.toFloat())
            }
        }
    }

    Canvas(modifier) {
        // 在 **draw 阶段**读状态：只让这块画布失效重绘，不触发任何重组。
        WaveformStore.generation
        WaveformStore.snapshot(bars)
        val n = bars.size
        if (n == 0 || size.width <= 0f || size.height <= 0f) return@Canvas
        val gap = size.width * 0.28f / n
        val barWidth = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val half = size.height / 2f
        val minBar = 1.dp.toPx()
        for (i in 0 until n) {
            // 幅度用**平方根**映射到高度，而不是线性。
            // 音乐（尤其母带压缩过的流行乐）的 RMS 通常落在 0.05~0.3，线性映射只能画出
            // 带宽 5%~30% 的一排小方块，肉眼像"没在动"；sqrt 把 0.09→0.3、0.25→0.5，
            // 既保留相对强弱，又让整条带子用得上高度。一次 sqrt/柱/帧（28 次）可忽略。
            val amplitude = kotlin.math.sqrt(bars[i].coerceIn(0f, 1f))
            val height = (amplitude * size.height).coerceAtLeast(minBar)
            // 越靠左（越旧）越淡 —— 不用渐变对象，一次 alpha 计算换来"余韵"观感。
            val alpha = 0.30f + 0.70f * (i + 1).toFloat() / n
            drawRect(
                color = barColor.copy(alpha = alpha),
                topLeft = androidx.compose.ui.geometry.Offset(
                    x = i * (barWidth + gap),
                    y = half - height / 2f,
                ),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
            )
        }
    }
}
