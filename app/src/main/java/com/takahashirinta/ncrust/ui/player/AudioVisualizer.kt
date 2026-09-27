/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.player.AudioFeatureExtractor
import com.takahashirinta.ncrust.ui.player.motion.MotionBindings
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerPrefs
import com.takahashirinta.ncrust.ui.player.waveform.WaveformEffectsState
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import kotlinx.coroutines.delay
import kotlin.math.sqrt

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
     * v2.8.0 · P1-A：C 档（冲击波 / 粒子）的有界状态机。UI 线程独占，定长 SoA 数组，零分配。
     * A/B 档不用它（关掉时 `pump` 连 `update` 都不调用）。
     */
    private val showcaseState = WaveformEffectsState()

    /**
     * v2.8.0 · P1-A：点按交互翻转的着色模式（**进程内、不落盘**）。
     *
     * 语义：`false` = 按档位默认（精致/炫技档渐变流动、简洁档按时序着色），
     * `true` = 取反。点按是给"炫技"档用户的一个即时对比手段；
     * 落盘会多出第 9 个设置键、并且要处理"档位改了这个键还算不算数"，超出本版范围。
     */
    @Volatile
    private var coloringInverted: Boolean = false

    /**
     * 开关的**音频线程侧镜像**。volatile：关掉后 render 回调里只剩一次读。
     * 之所以不动态拆掉 AudioProcessor：ExoPlayer 建好之后改不了 audio sink，
     * 为了一个开关重建播放器会打断播放 —— 而这里省下的开销本来就是纳秒级。
     */
    @Volatile
    var enabled: Boolean = VisualizerSetting.DEFAULT_ENABLED

    /**
     * v3.0.0：**界面动效是否也需要音频特征**（音频线程侧镜像，volatile，不读盘）。
     *
     * 为什么必须与 [enabled] 分开：v2.9.0 只有可视化一个开关，于是「关掉音频可视化」
     * 会把界面动效的节拍数据一起掐掉 —— 背景呼吸、节拍脉冲、粒子全部静默失效，
     * 而设置页里那个开关一个字都没提这件事。v3.0.0 的动效绑定建立在真实音频特征上，
     * 更需要这条数据通路独立于「画不画波形」。
     *
     * 两个开关都关时 `handleBuffer` 仍然只剩两次 volatile 读 ——
     * 「关掉开关 = 零开销」这条纪律保住了，只是从「一个开关」变成「两个都关」。
     */
    @Volatile
    var motionFeaturesEnabled: Boolean = false

    // ------------------------------------------------------------------
    // v3.0.0：音频特征快照（音频线程**单写者**，UI 线程读）
    //
    // 为什么是一个个 volatile 标量而不是一个数组/对象：
    //   · 数组跨界共享会出现撕裂读（写者写一半、读者读一半），而 volatile 数组引用
    //     只能保证引用可见、保证不了元素；
    //   · 每个标量各自 volatile ⇒ 每个值要么是「上一个缓冲的」要么是「这一个缓冲的」，
    //     不会出现半个值。跨字段最多差一个缓冲（实测缓冲粒度 ~10–50ms），
    //     而所有消费方的时间常数都是 90–260ms 量级 ⇒ 不可见。
    //   · 发布顺序固定：先写各分量、**最后**写 [featAvailable] —— 读到 available=true
    //     的读者至少能看到这一轮的全部分量（volatile 的顺序一致性）。
    // ------------------------------------------------------------------

    @Volatile
    private var featRms = 0f

    @Volatile
    private var featLow = 0f

    @Volatile
    private var featMid = 0f

    @Volatile
    private var featHigh = 0f

    @Volatile
    private var featCentroid = 0f

    /** 本次特征是否**真的**由 [AudioFeatureExtractor] 算出（false = 降级到 RMS-only）。**最后写**。 */
    @Volatile
    private var featAvailable = false

    /** 瞬态**累计计数**（单调递增）。消费方比较前后差值 ⇒ 一帧里发生多次也不会漏。 */
    @Volatile
    private var featTransientCount = 0L

    /** 最近一次瞬态的强度（0..1）。 */
    @Volatile
    private var featTransientStrength = 0f

    private val generationState = mutableIntStateOf(0)

    /** 只应在 draw 阶段读（在组合阶段读会变成每帧重组）。 */
    val generation: Int get() = generationState.intValue

    /**
     * **音频线程**调用：零分配、零锁。
     *
     * v2.9.0：多收一个**低频通道**（[bass]）。数据来源与全带 RMS 是**同一次**逐样本遍历
     * （`PcmRms.analyze`，一阶低通），不是第二遍扫描，也不是 FFT。
     * 它的唯一消费方是节拍判据：底鼓/贝斯落在这一带，人声与旋律重音不在 ——
     * 于是「鼓声触发冲击波」成立，而「一句高音也炸一圈涟漪」消失。
     */
    fun onBar(rootMeanSquare: Double, bass: Double, mix: Double) {
        if (enabled) ring.push(rootMeanSquare.toFloat(), bass.toFloat(), mix.toFloat())
    }

    /**
     * v3.0.0：发布一组音频特征（**音频线程**，零分配）。
     *
     * `available=false` 表示这一轮是**降级路径**（特征提取器不可用，只有 RMS + 低频），
     * 消费方据此回落到自己的内置判据 —— 而不是把「没有中高频」误当成「中高频能量为零」。
     * 降级那一路的 `mid/high/centroid` 一律传 0，`rms/low` 仍然是真实值。
     */
    fun onFeatures(
        rms: Double,
        low: Double,
        mid: Double,
        high: Double,
        centroid: Double,
        available: Boolean,
    ) {
        featRms = finite01(rms)
        featLow = finite01(low)
        featMid = finite01(mid)
        featHigh = finite01(high)
        featCentroid = finite01(centroid)
        // **最后写**：读到 available=true 的读者至少能看到这一轮的全部分量。
        featAvailable = available
    }

    /**
     * v3.0.0：发布**一次缓冲里发生的瞬态**（**音频线程**，零分配）。
     *
     * ## 为什么是「个数」而不是「一个 bool」
     *
     * 两个独立的理由，都来自实测：
     *  1. 真机缓冲粒度是 **100ms**（S6 实测 4410 帧 / 11 Hz），一个缓冲里可能落下两次击打；
     *  2. UI 一帧的间隔里可能到达**多个**缓冲。置一个 bool 会让中间那些击打
     *     **永久丢失**（谁后写谁赢）。
     *
     * 计数单调递增，消费方比较前后差值就知道「这一帧发生了几次」。
     *
     * @param count 本次缓冲判出的瞬态个数（≥1；调用方只在 >0 时调用）。
     * @param strength 其中**最强**那一次的强度（0..1）。
     */
    fun onTransient(count: Int, strength: Float) {
        featTransientStrength = if (strength.isFinite()) strength.coerceIn(0f, 1f) else 0f
        // 有界：单次缓冲的个数上限由子帧数决定（100ms / 10ms = 10），
        // 这里再夹一次，防止调用方传进来一个荒唐的值把计数器顶飞。
        //
        // ⚠️ 这个常数**故意在这里再写一遍**，而不是引用
        // `AudioFeatureExtractor.MAX_TRANSIENTS_PER_BUFFER`：那个类是 `@UnstableApi`
        // （media3 的 opt-in 注解），从 UI 层引用它会把 media3 的 opt-in 需求传染到这里，
        // 而这一层根本不关心 media3。两边相等由单测
        // `TransientPublishBoundTest.上界与提取器的子帧上界一致` 钉住，所以不会分叉。
        val bounded = count.coerceIn(1, MAX_TRANSIENTS_PER_BUFFER)
        // **最后写**计数：读到新计数的读者至少能看到与之配套的强度。
        featTransientCount += bounded.toLong()
    }

    /** 特征是否来自真正的三频段提取器（false = 降级到 RMS-only）。 */
    fun featuresAvailable(): Boolean = featAvailable

    fun featureRms(): Float = featRms

    fun featureLow(): Float = featLow

    fun featureMid(): Float = featMid

    fun featureHigh(): Float = featHigh

    fun featureCentroid(): Float = featCentroid

    /** 瞬态累计计数（单调递增；非瞬态时刻不变）。 */
    fun transientCount(): Long = featTransientCount

    /** 最近一次瞬态的强度（0..1）。 */
    fun transientStrength(): Float = featTransientStrength

    /** 只有全带值的重载（老调用点与单测用）：低频通道取同一个值。 */
    fun onBar(rootMeanSquare: Double) = onBar(rootMeanSquare, rootMeanSquare, 0.0)

    /** 全带 + 低频的重载（v2.9.0 的调用点与单测用）：明亮度占比取 0（= 全部低沉）。 */
    fun onBar(rootMeanSquare: Double, bass: Double) = onBar(rootMeanSquare, bass, 0.0)

    /**
     * UI 线程按帧率调用；有新数据（或平滑/峰值/呼吸/C 档特效尚未收敛）时才让画面失效。
     * @param dtMs 距上一帧的毫秒数（平滑系数由它算，见 [WaveformRing.pump]）。
     * @param effects 当前档位的能力位（组合期读一次后捕获，帧路径里不再读任何 state）。
     */
    fun pump(active: Boolean, dtMs: Float, effects: VisualizerEffects) {
        var changed = ring.pump(active, dtMs, effects)
        // v2.9.0：低频通道跟着泵一次（判据用最新一根，所以只在这里读一次）。
        // C 档：只有真的开着才推进（关掉时连函数都不进 ⇒ 零成本）。
        if (effects.shockwave || effects.particles) {
            // v3.0.0：特征链路可用时，瞬态由**音频线程**判定（每缓冲一次判定，
            // 不会像"只读最新一根柱"那样漏掉同一帧里的多次击打）；
            // 不可用时传哨兵值，让 `WaveformEffectsState` 走它自己的内置判据。
            val transientCount = if (featAvailable) featTransientCount else -1L
            if (showcaseState.update(
                    ring.newestTarget(),
                    ring.newestBass(),
                    dtMs,
                    active,
                    effects,
                    transientCount,
                )
            ) {
                changed = true
            }
        }
        if (changed) generationState.intValue++
    }

    /** UI 线程：把滚动窗口与峰值拷进复用数组（两个数组都是 `remember` 的，零分配）。 */
    fun snapshot(bars: FloatArray, peaks: FloatArray) = ring.copyInto(bars, peaks)

    /** v3.0.0：连带**明亮度占比**一起拷（逐柱着色用；三个数组都是 `remember` 的，零分配）。 */
    fun snapshot(bars: FloatArray, peaks: FloatArray, mixes: FloatArray) =
        ring.copyInto(bars, peaks, mixes)

    /**
     * v2.9.0：最新的**未平滑**柱值（0..1）。
     *
     * 界面动效的响度包络（`ui/player/motion/MotionEnvelope.kt`）与 C 档节拍判据共用它。
     * 与 `WaveformEffectsState.update` 同一个口径：**必须用未平滑值** ——
     * 用画面值（`bars`）会把起音按 τ=22ms 抹圆，节拍判据直接失效。
     *
     * 只在 `pump` 之后读（`pump` 负责把新柱搬进 targets；搬之前读到的是上一帧的值）。
     */
    fun newestBar(): Float = ring.newestTarget()

    /**
     * v2.9.0：最新的**低频（鼓 / 贝斯）**能量（0..1），同样是未平滑值。
     *
     * 节拍判据改用它（见 `WaveformEffectsState.update` 与 `MotionEnvelope.update`）——
     * 全带 RMS 对"一句高音"与"一下底鼓"给出同样的响应，那是「乱触发」的根源。
     */
    fun newestBass(): Float = ring.newestBass()

    /**
     * v3.0.0：最新的**明亮度占比**（0..1；0 = 能量全在低频带，1 = 全在中高频带）。
     *
     * 波形逐柱着色（`AudioVisualizerBars`）与三频带能量条共用它。逐柱那一份读的是
     * `snapshot(bars, peaks, mixes)` 里的历史窗口（每根柱子各自的占比），
     * 这个函数给的是**最新一根**的值（能量条用）。
     */
    fun newestMix(): Float = ring.newestMix()

    /** B 档：呼吸亮度倍率。draw 阶段直接读（不是 Compose state ⇒ 不触发重组）。 */
    fun breatheScale(): Float = ring.breatheScale()

    /** B 档：渐变流动相位（0..1）。draw 阶段直接读。 */
    fun flowPhase01(): Float = ring.flowPhase01()

    /** C 档：涟漪 / 粒子的只读访问（draw 阶段直接读）。 */
    val showcase: WaveformEffectsState get() = showcaseState

    /** C 档点按交互：翻转「渐变流动 ↔ 按时序着色」，并让画面立刻失效一次。 */
    fun toggleColoringMode() {
        coloringInverted = !coloringInverted
        generationState.intValue++
    }

    /**
     * draw 阶段读：这一帧到底用不用渐变流动。
     *
     * 是**普通字段读**而不是 Compose state —— 点按时手动 +1 一次 `generation` 就够触发重绘，
     * 不需要为此重组整棵播放器子树。
     */
    fun isColoringInverted(): Boolean = coloringInverted

    /** 单测 / 调试用。 */
    internal fun resetForTest() {
        ring.clear()
        showcaseState.clear()
        coloringInverted = false
        enabled = VisualizerSetting.DEFAULT_ENABLED
        motionFeaturesEnabled = false
        featRms = 0f
        featLow = 0f
        featMid = 0f
        featHigh = 0f
        featCentroid = 0f
        featAvailable = false
        featTransientCount = 0L
        featTransientStrength = 0f
    }

    /** 0..1 的有限值防御：NaN / Inf 一律归零（NaN 进了 `Color` 不会抛异常，只会让画面静默消失）。 */
    private fun finite01(value: Double): Float =
        if (value.isFinite()) value.coerceIn(0.0, 1.0).toFloat() else 0f
}

/**
 * v3.0.0：一次缓冲最多能被发布的瞬态个数。
 *
 * 与 `AudioFeatureExtractor.MAX_TRANSIENTS_PER_BUFFER` **必须相等**（单测钉住方向与数值）。
 * 这里再声明一次的理由见 `WaveformStore.onTransient` 的注释：不把 media3 的 `@UnstableApi`
 * opt-in 需求传染到 UI 层。
 */
internal const val MAX_TRANSIENTS_PER_BUFFER = 64

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
 *
 * ## v2.8.0 · P1-A：分级渲染（A/B 档，C 档见 WaveformEffectsState）
 *
 * | 档 | 这一档画什么 | 每帧增量（相对现状 28 笔） |
 * |---|---|---|
 * | 简洁 | 圆角柱（`drawRoundRect`）+ 峰值保持（每柱一笔细横条）+ 按时序着色（现状的 alpha 阶梯）+ 间距（现状已有） | +≤28 笔（峰值只在高于柱顶 1.5dp 时才画） |
 * | 精致 | 简洁 + 渐变流动（**缓存 1 个 Brush + TileMode.Repeated + 每帧只改平移相位**）+ 柱顶光点（纯色小圆）+ 呼吸（只乘 alpha） | +≤28 笔（光点） |
 *
 * 三条「零分配 / 零重组」的实现要点，改这里之前先读：
 *
 *  1. **渐变流动不重建 Brush**：探针点名的 `Brush.infiniteLinearGradient` 在 Compose 里
 *     **不存在**；而「每帧 new 一个 LinearGradient」= 每帧 1 个对象 + 1 个 colors List +
 *     1 个 native SkShader，直接违反零分配。这里用 `FlowBrushCache`：只在**尺寸变化**
 *     （旋转 / 进出大屏 / 分栏）时重建，之后每帧只做一次 `translate`（inline，不分配）。
 *  2. **平移只作用于色带，不作用于柱子**：画布整体 `translate(-phase)`，每根柱再在自己的
 *     x 上 `+phase` 抵消 —— 柱子停在原地、Brush 的采样坐标在动，这就是「流动」。
 *  3. **呼吸只改 alpha**：改 Brush 参数会迫使 shader 每帧重建（回到第 1 条的高成本路线），
 *     所以呼吸只乘在 `alpha` 上（探针 §2「渐变流动 × 呼吸」那一格的 ✅ 分支）。
 *
 * ## v2.8.0 · P1-A：C 档（炫技，默认关）与**拖拽交互的降级说明**
 *
 * C 档四项（冲击波 / 粒子 / 3D 透视 / 交互）各自独立开关，且都要求 `tier == 炫技` 且
 * `visualizer_showcase == true`（映射的唯一落点是 `VisualizerEffects.of`）。
 * 每帧增量：涟漪 ≤3 笔描边圆 + 粒子 ≤24 笔实心圆 + 透视多一层 render layer。
 *
 * **「拖拽交互」在本版降级为「点按切换着色模式」，理由如下（不是偷懒，是风险与边界）**：
 *
 *  1. **挂载点不在本任务的改动范围**：可视化条是 `PlayerCard.kt` 里的 `AudioVisualizerSlot`
 *     调用的（大屏左栏 / 平板横屏两栏），手势归属判定按仓库范式（`PlayerDragSnap.kt`）
 *     应当与它同层，而本任务允许改的文件不含 `PlayerCard.kt`。
 *  2. **附近已有命中面**：大屏左栏里，可视化条正下方就是 `clickable { onSongInfoClick() }`
 *     的歌名/歌手区，卡片根部还有两个 `pointerInput`（整卡拖拽 / 展开态吞事件）。
 *     整卡拖拽在 `bigScreenActive` 时被显式停用（`PlayerCard.kt` 的 `if (bigScreenActive) return@pointerInput`），
 *     但"某一形态下刚好没冲突"不等于**手势分解（slop / 方向认领）**是对的。
 *  3. **无设备 ⇒ 无法取证**：AGENTS.md 的触摸陷阱合集与 v1.7.0 · P0 都说明这类判定
 *     必须真机 A/B 才能声称结论；本环境没有设备，写一个"看起来对"的拖拽检测器是拿用户的
 *     播放器手势做赌注。
 *
 * 所以本版：**开关默认关 ⇒ 默认零新增命中面**；打开后是一个 `detectTapGestures`（点一下
 * 在「渐变流动 ↔ 按时序着色」之间切换），不消费任何 MOVE 事件、不与拖拽竞争方向。
 * 文案也如实写成「点按切换着色」而不是「拖拽」（见 `VisualizerStrings`）。
 * 真要做拖拽，先补一份「手势归属」纯函数 + 单测（照 `PlayerDragSnap.kt`），再上真机 A/B。
 */
@Composable
fun AudioVisualizerBars(
    modifier: Modifier = Modifier,
    barCount: Int = WaveformStore.BAR_COUNT,
) {
    val barColor = LocalMetroColors.current.primary
    val bars = remember(barCount) { FloatArray(barCount) }
    val peaks = remember(barCount) { FloatArray(barCount) }
    // v3.0.0：逐柱的「明亮度占比」窗口（多频段调制用；关掉时根本不快照它）。
    val mixes = remember(barCount) { FloatArray(barCount) }
    // 分级：**组合期读一次**（改设置时才会重组一次）。
    // draw 只用这个捕获值 —— 帧路径里零 state 读（除了下面 draw 里的 generation）。
    val effects = VisualizerPrefs.effects.value
    val flowBrushCache = remember(barColor) { FlowBrushCache(barColor) }
    // v3.0.0：明亮端的颜色（高频占主导的柱子用它）。`Color` 是 value class ⇒ 这个 lerp 不分配。
    val brightColor = remember(barColor) { lerp(barColor, Color.White, BAND_TINT_WHITE_MIX) }
    val density = LocalDensity.current
    val cornerRadiusPx = with(density) { BAR_CORNER_RADIUS_DP.dp.toPx() }
    val dotRadiusPx = with(density) { BAR_DOT_RADIUS_DP.dp.toPx() }
    val peakCapPx = with(density) { BAR_PEAK_CAP_DP.dp.toPx() }
    val rippleStroke = remember(density) { Stroke(width = with(density) { RIPPLE_STROKE_DP.dp.toPx() }) }
    val particleRadiusPx = with(density) { PARTICLE_RADIUS_DP.dp.toPx() }
    val perspectiveModifier = remember(effects.perspective, density) {
        if (effects.perspective) Modifier.visualizerPerspective(density.density) else Modifier
    }
    // C 档：涟漪/粒子会画到条带之外（粒子有重力、涟漪半径超过条带高），必须裁剪。
    // 只在 C 档挂 clip：A/B 档保持"视觉零变化"（现状不裁剪也没有越界内容）。
    val clipModifier = remember(effects.anyShowcase) {
        if (effects.anyShowcase) Modifier.clipToBounds() else Modifier
    }
    // C 档的点按交互（「拖拽交互」的有界降级，理由见 AudioVisualizerBars 的 KDoc）。
    // **默认关 ⇒ 默认零新增命中面**：开关关掉时这里返回 Modifier，不挂任何 pointerInput。
    val tapModifier = remember(effects.tapInteraction) {
        if (effects.tapInteraction) {
            Modifier.pointerInput(Unit) {
                detectTapGestures { WaveformStore.toggleColoringMode() }
            }
        } else {
            Modifier
        }
    }
    // v2.9.0：**帧循环搬到了 `ui/player/motion/MotionClock.kt`**。
    //
    // 为什么必须搬：界面动效（背景呼吸 / 节拍脉冲 / 粒子 / 光晕）在**竖屏手机**也要跑，
    // 而竖屏手机上本组件根本不挂载（`PlayerLayout.visualizerSlot` 在那一格恒为 false）。
    // 两处各起一条循环会让同一个 `WaveformStore.pump` 每帧被调用两次 ——
    // `consumePending` 幂等，但**动画相位**（渐变流动 / 呼吸）会每帧前进两次，
    // 流动速度直接翻倍，而这个 bug 只在横屏/平板上出现，竖屏测不出来。
    //
    // 本组件的契约因此是：**只读**（draw 阶段读 `WaveformStore.generation` 与快照），
    // 推进由 `MotionFrameClock`（在 `PlayerCard` 里挂载恰好一次）负责。
    // v2.8.0 的「不空转 / 零分配 / 零重组」三条契约一字未改，只是执行者换了地方。

    Canvas(modifier.then(clipModifier).then(perspectiveModifier).then(tapModifier)) {
        // 在 **draw 阶段**读状态：只让这块画布失效重绘，不触发任何重组。
        WaveformStore.generation
        // v3.0.0：多频段调制开着时**多拷一个数组**（零分配：数组是 remember 出来的）；
        // 关掉时连拷都不拷（「关掉开关 = 零开销」在快照这一层也成立）。
        if (effects.waveBandOn) {
            WaveformStore.snapshot(bars, peaks, mixes)
        } else {
            WaveformStore.snapshot(bars, peaks)
        }
        val n = bars.size
        if (n == 0 || size.width <= 0f || size.height <= 0f) return@Canvas
        val gap = size.width * 0.28f / n
        val barWidth = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val minBar = 1.dp.toPx()
        // 呼吸：只在 B 档读；不是 Compose state ⇒ 不触发重组，只是一次字段读。
        val breath = if (effects.breathe) WaveformStore.breatheScale() else 1f
        // 点按交互翻转着色模式：普通字段读 + 点按时手动失效一次（见 WaveformStore.toggleColoringMode）。
        val flow = effects.flow != WaveformStore.isColoringInverted()
        // C 档：涟漪画在柱子**下面**（背景层），粒子画在柱子**上面**（前景层）。
        if (effects.shockwave) {
            drawShockwaveRipples(WaveformStore.showcase, barColor, rippleStroke, breath)
        }
        val flowBrush = if (flow) flowBrushCache.obtain(size.width) else null
        val barMixes = if (effects.waveBandOn) mixes else null
        if (flowBrush != null) {
            val phasePx = WaveformStore.flowPhase01() * size.width * FLOW_TILE_FRACTION
            // 画布整体左移 phase，每根柱再右移 phase 抵消：柱子不动、色带在流。
            translate(left = -phasePx) {
                drawWaveformBars(
                    bars, peaks, barColor, brightColor, flowBrush, effects, barMixes,
                    xShift = phasePx, barWidth = barWidth, gap = gap, minBar = minBar,
                    cornerRadiusPx = cornerRadiusPx, dotRadiusPx = dotRadiusPx,
                    peakCapPx = peakCapPx, breath = breath,
                )
            }
        } else {
            drawWaveformBars(
                bars, peaks, barColor, brightColor, null, effects, barMixes,
                xShift = 0f, barWidth = barWidth, gap = gap, minBar = minBar,
                cornerRadiusPx = cornerRadiusPx, dotRadiusPx = dotRadiusPx,
                peakCapPx = peakCapPx, breath = breath,
            )
        }
        if (effects.particles) {
            drawParticles(WaveformStore.showcase, barColor, particleRadiusPx, breath)
        }
        // v3.0.0：炫技档的三频带能量条（3 笔 drawRect，画在柱子之上）。
        // 它显示的是**当下**三个频带各自的能量，不是频谱（横轴没有频率含义）。
        if (effects.waveBandLanes) {
            drawBandLanes(
                low = WaveformStore.featureLow(),
                mid = WaveformStore.featureMid(),
                high = WaveformStore.featureHigh(),
                color = barColor,
                laneHeightPx = with(density) { BAND_LANE_HEIGHT_DP.dp.toPx() },
                gapPx = with(density) { BAND_LANE_GAP_DP.dp.toPx() },
                alpha = breath,
            )
        }
    }
}

/** 简洁档的按时序着色：越靠左（越旧）越淡。**这不是频谱**，见 [VisualizerEffects.spectrumColoring]。 */
private const val TIME_TINT_MIN_ALPHA = 0.30f
private const val TIME_TINT_ALPHA_SPAN = 0.70f

// ---- v3.0.0：多频段调制（逐柱着色 + 三频带能量条）的三组数字 ----

/**
 * 明亮端颜色 = 主题色与白色的混合比例。
 *
 * 0.45 的依据：太浅（>0.6）会在深色主题下丢失主题色、看起来像"所有柱子都变白了"；
 * 太深（<0.3）则与低沉端分不出来。0.45 在 OLED 黑底上"一眼能看出这批柱子在发光"。
 */
private const val BAND_TINT_WHITE_MIX = 0.45f

/**
 * 占比高于它才用「流动 Brush」画这根柱子（低于它用实心低沉色）。
 *
 * 0.5 是刻意的中点：音乐的中高频占比长期在 0.3~0.6（见 `MotionBindings.tintMix` 的说明），
 * 以 0.5 为界能让两种画法在同一帧里同时出现 —— 那正是"低频段柱子 / 高频段柱子"
 * 这句话在视觉上的样子。
 */
private const val BAND_TINT_BRUSH_THRESHOLD = 0.5f

/** 低沉端保留的 alpha 比例（明亮端为 1.0）。0.8 让两种柱子的明暗差别可辨但不夸张。 */
private const val BAND_TINT_ALPHA_MIN = 0.8f

/** 频带能量条的高度（dp）。2dp：在 32~56dp 高的条带里刚好读得出三段。 */
private const val BAND_LANE_HEIGHT_DP = 2f

/** 频带能量条之间的间距（dp）。1dp 足够把三段分开。 */
private const val BAND_LANE_GAP_DP = 1f

/** 频带能量条的最大不透明度。低于柱子（它们不该抢主视觉）。 */
private const val BAND_LANE_ALPHA = 0.65f

/** 渐变流动一个色带占条带宽度的比例（0.55 ⇒ 一条带上能看到约 1.8 个周期）。 */
private const val FLOW_TILE_FRACTION = 0.55f

/** 色带两端与中间的不透明度：两端一样暗 ⇒ `TileMode.Repeated` 的接缝不可见（不然会看到硬边）。 */
private const val FLOW_DIM_ALPHA = 0.55f
private const val FLOW_BRIGHT_ALPHA = 1.00f

/**
 * 圆角半径（dp）。
 *
 * 为什么不从 `ui/theme/AppShapes` 取：`AppShapes` 提供的是 `Shape`（给 `Modifier.clip` 用），
 * 而 `drawRoundRect` 要的是 `CornerRadius`（dp 数值），两者不能互转 —— 本仓库目前只有
 * `RoundedCornerShape` 一个落点，还没有 dp 半径 token。本版不动 `AppShapes.kt`
 * （不在本次改动范围内），所以半径常量在 draw 侧本地声明；
 * **遗留项**：把 `CornerRadius` 半径收敛进 `AppShapes`，并把 `CornerRadius(` 加进
 * `AppShapesSingleSourceTest` 的 forbidden 列表（探针 §1「圆角柱」一栏的建议）。
 */
private const val BAR_CORNER_RADIUS_DP = 2f

/** 柱顶光点半径（dp）。细到能读出"这是柱顶"，又不会盖住柱子本身。 */
private const val BAR_DOT_RADIUS_DP = 1.2f

/** 峰值横条高度（dp）。 */
private const val BAR_PEAK_CAP_DP = 1.5f

/** 柱高低于 `minBar × 它` 时不画光点：矮柱上的光点会盖掉柱子本身，看起来像噪点。 */
private const val DOT_MIN_BAR_MULTIPLE = 3f

/** 3D 透视的固定倾角（度）。恒定值 ⇒ 不产生逐帧 layer 失效（动态倾角要每帧重算矩阵）。 */
private const val PERSPECTIVE_DEGREES = 9f

/** `cameraDistance = 它 × density`。Compose 默认是 8×density，越大透视越弱；14 是"看得出立体但不夸张"。 */
private const val PERSPECTIVE_CAMERA_DISTANCE_FACTOR = 14f

/**
 * C 档的 3D 透视：**新增一个 render layer**（探针 §1「3D 透视」一栏）。
 *
 * DrawScope 只有 2D 变换（`withTransform/rotate/scale`），没有 `cameraDistance` 语义，
 * 所以透视只能走 `graphicsLayer`。代价与取舍：
 *  - 多一层合成：低端机上这是炫技档里最贵的一项 —— 因此它**默认关**、只在 C 档可见；
 *  - 倾角恒定（不做逐帧动画）⇒ layer 只在挂载/尺寸变化时更新一次，帧路径零成本；
 *  - 与圆角柱不冲突（圆角在 local 空间画好再被父矩阵变换）。
 */
private fun Modifier.visualizerPerspective(density: Float): Modifier = graphicsLayer {
    rotationX = PERSPECTIVE_DEGREES
    cameraDistance = PERSPECTIVE_CAMERA_DISTANCE_FACTOR * density
}

/**
 * 一帧的柱状绘制（**顶层私有函数**，不是 draw lambda 里的局部函数）。
 *
 * 为什么抽出来：需要它的有两个调用点（流动 / 不流动），而局部函数或 lambda 会带来
 * 捕获与分配的不确定性。这里全部参数都是基本类型或已存在的对象 ⇒ 调用本身**零分配**。
 *
 * ## v3.0.0：多频段调制（`mixes != null` 时生效）
 *
 * 每根柱子按**它自己那一刻**的「明亮度占比」决定怎么画：
 *
 *  - 占比低（那一刻能量主要在低频带：鼓 / 贝斯）⇒ **实心低沉色**（`barColor`）；
 *  - 占比高（那一刻能量主要在中高频带：人声 / 弦乐 / 镲片）⇒ **流动明亮色**
 *    （有渐变流动时用同一个 `flowBrush`，否则用 `brightColor`）。
 *
 * 三条性质让这个做法站得住：
 *
 *  1. **横轴仍然是时间**（左旧右新）—— 这不是频谱，`spectrumColoring` 仍恒为 false；
 *  2. **零新增绘制**：每根柱子仍然是**一笔**，只是那一笔的着色来源跟着音频走；
 *  3. **不牺牲渐变流动**：流动色带没有关掉，只是**出现在高频占主导的那些柱子上** ——
 *     音乐一亮，波形就"流动"起来，这比"整条一直在流"更能读出音乐。
 *
 * @param xShift 只有流动模式非 0：画布已整体左移 `xShift`，这里给每根柱补回去。
 * @param flowBrush 非 null 时用 `brush=`（渐变流动），否则用 `color=`（按时序着色）——
 *   两者是 API 层的二选一，[VisualizerEffects.colorChannelMode] 是这条规则的唯一读法。
 * @param brightColor 多频段调制的明亮端颜色（只在没有流动 Brush 时用到）。
 * @param mixes 逐柱明亮度占比（0..1）；`null` = 多频段调制关着，走 v2.8.0 的逐字路径。
 */
private fun DrawScope.drawWaveformBars(
    bars: FloatArray,
    peaks: FloatArray,
    barColor: Color,
    brightColor: Color,
    flowBrush: Brush?,
    effects: VisualizerEffects,
    mixes: FloatArray?,
    xShift: Float,
    barWidth: Float,
    gap: Float,
    minBar: Float,
    cornerRadiusPx: Float,
    dotRadiusPx: Float,
    peakCapPx: Float,
    breath: Float,
) {
    val n = bars.size
    val half = size.height / 2f
    val heightPx = size.height
    val cornerRadius = CornerRadius(cornerRadiusPx)
    val dotThreshold = minBar * DOT_MIN_BAR_MULTIPLE
    val peakVisibleDelta = peakCapPx
    for (i in 0 until n) {
        // 幅度用**平方根**映射到高度，而不是线性。
        // 音乐（尤其母带压缩过的流行乐）的 RMS 通常落在 0.05~0.3，线性映射只能画出
        // 带宽 5%~30% 的一排小方块，肉眼像"没在动"；sqrt 把 0.09→0.3、0.25→0.5，
        // 既保留相对强弱，又让整条带子用得上高度。一次 sqrt/柱/帧（28 次）可忽略。
        val amplitude = sqrt(bars[i].coerceIn(0f, 1f))
        val barHeight = (amplitude * heightPx).coerceAtLeast(minBar)
        val left = i * (barWidth + gap) + xShift
        val top = half - barHeight / 2f
        // 越靠左（越旧）越淡 —— 不用渐变对象，一次 alpha 计算换来"余韵"观感。
        // 流动模式下这条色带让给 Brush（同一笔绘制要么 color 要么 brush），alpha 只剩呼吸。
        val timeAlpha = if (flowBrush == null) {
            (TIME_TINT_MIN_ALPHA + TIME_TINT_ALPHA_SPAN * (i + 1).toFloat() / n) * breath
        } else {
            breath
        }
        // v3.0.0：这一根柱子的频带归属（null = 关着，走原来的单一着色路径）。
        var useBrush = flowBrush != null
        var solidColor = barColor
        var alpha = timeAlpha
        if (mixes != null) {
            val tint = MotionBindings.tintMix(mixes[i])
            useBrush = tint >= BAND_TINT_BRUSH_THRESHOLD && flowBrush != null
            solidColor = lerp(barColor, brightColor, tint)
            // 明亮端稍亮一点（alpha 高 20%）：低沉的柱子更"沉"，明亮的更"跳"。
            alpha = timeAlpha * (BAND_TINT_ALPHA_MIN + (1f - BAND_TINT_ALPHA_MIN) * tint)
        }
        if (effects.rounded) {
            if (useBrush) {
                drawRoundRect(
                    brush = flowBrush!!,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = cornerRadius,
                    alpha = alpha,
                )
            } else {
                drawRoundRect(
                    color = solidColor,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = cornerRadius,
                    alpha = alpha,
                )
            }
        } else if (useBrush) {
            drawRect(brush = flowBrush!!, topLeft = Offset(left, top), size = Size(barWidth, barHeight), alpha = alpha)
        } else {
            drawRect(color = solidColor, topLeft = Offset(left, top), size = Size(barWidth, barHeight), alpha = alpha)
        }
        if (effects.dots && barHeight > dotThreshold) {
            drawCircle(
                color = solidColor,
                radius = dotRadiusPx,
                center = Offset(left + barWidth / 2f, top - dotRadiusPx),
                alpha = breath,
            )
        }
        if (effects.peaks) {
            // 峰值用与柱子**同一个** sqrt 映射，否则两者不可比（峰值会看起来比柱子还矮）。
            val peakHeight = sqrt(peaks[i].coerceIn(0f, 1f)) * heightPx
            if (peakHeight > barHeight + peakVisibleDelta) {
                drawRect(
                    color = solidColor,
                    topLeft = Offset(left, half - peakHeight / 2f - peakCapPx),
                    size = Size(barWidth, peakCapPx),
                    alpha = 0.9f * breath,
                )
            }
        }
    }
}

/**
 * v3.0.0：**三频带能量条**（炫技档；画在波形柱子之上、条带底部）。
 *
 * 三条等长的短横条，**长度**分别与低频 / 中频 / 高频的当前能量成正比。
 * 它的读法是「哪一带在动」，不是频谱 —— 三条之间没有频率轴，只有三个带宽很宽的
 * 一阶滤波器输出（见 `AudioFeatureExtractor`）。
 *
 * 成本：**3 笔 `drawRect`**（低频在最下、高频在最上），零分配。
 */
private fun DrawScope.drawBandLanes(
    low: Float,
    mid: Float,
    high: Float,
    color: Color,
    laneHeightPx: Float,
    gapPx: Float,
    alpha: Float,
) {
    val total = laneHeightPx * 3f + gapPx * 2f
    if (total >= size.height) return
    // 三条依次画（低频在最下、高频在最上）。**刻意不用数组**：`floatArrayOf(...)` 每帧
    // 都会在堆上分配一个数组，而这个函数是每帧调用的（铁律 27 的零分配要求）。
    var bottom = size.height
    bottom = drawBandLane(low, color, 0f, bottom, laneHeightPx, gapPx, alpha)
    bottom = drawBandLane(mid, color, 0f, bottom, laneHeightPx, gapPx, alpha)
    drawBandLane(high, color, 0f, bottom, laneHeightPx, gapPx, alpha)
}

/** 画一条频带能量条，返回下一条的底边 y（自上而下推进，零分配）。 */
private fun DrawScope.drawBandLane(
    value: Float,
    color: Color,
    left: Float,
    bottom: Float,
    laneHeightPx: Float,
    gapPx: Float,
    alpha: Float,
): Float {
    val top = bottom - laneHeightPx
    val v = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    // 用 sqrt 映射与柱子保持同一套观感（见 drawWaveformBars 的注释）。
    val width = size.width * sqrt(v)
    if (width > 0f) {
        drawRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(width, laneHeightPx),
            alpha = alpha * BAND_LANE_ALPHA,
        )
    }
    return top - gapPx
}

/**
 * 渐变流动的 Brush 缓存：**只在宽度变化时重建**（旋转 / 进出大屏 / 平板分栏）。
 *
 * 关键点是 `TileMode.Repeated` + 两端同色的色带：让一个周期在任意宽度下都能无缝循环，
 * 于是"流动"只需要改一个平移量，不需要每帧构造 shader。
 */
private class FlowBrushCache(private val color: Color) {
    private var cachedWidth = Float.NaN
    private var cached: Brush? = null

    fun obtain(widthPx: Float): Brush {
        val width = if (widthPx > 0f) widthPx else 1f
        val existing = cached
        if (existing != null && cachedWidth == width) return existing
        val tile = (width * FLOW_TILE_FRACTION).coerceAtLeast(1f)
        val colors = listOf(
            color.copy(alpha = FLOW_DIM_ALPHA),
            color.copy(alpha = FLOW_BRIGHT_ALPHA),
            color.copy(alpha = FLOW_DIM_ALPHA),
        )
        val brush = Brush.linearGradient(
            colors = colors,
            start = Offset.Zero,
            end = Offset(tile, 0f),
            tileMode = TileMode.Repeated,
        )
        cachedWidth = width
        cached = brush
        return brush
    }
}

/** 涟漪描边宽度（dp）。细线更像"冲击波"，粗了会像一圈柱子。 */
private const val RIPPLE_STROKE_DP = 1.5f
/** 涟漪最大半径 = 条带长边 × 它。1.1 让涟漪在消失前刚好越过整条带子。 */
private const val RIPPLE_MAX_RADIUS_FRACTION = 1.1f

/** 涟漪最大不透明度（出生时）。0.55 在"看得见"与"不盖住柱子"之间。 */
private const val RIPPLE_MAX_ALPHA = 0.55f

/** 粒子基础半径（dp）。 */
private const val PARTICLE_RADIUS_DP = 1.3f

/**
 * C 档：冲击波涟漪（画在柱子下面）。
 *
 * 形状是**以条带中心为圆心的描边圆**，半径按进度从 0 扩到 `长边 × 1.1`、alpha 线性淡出。
 * 在 32~56dp 高的条带里它看起来是"一圈横向扩散的波"，配合 `clipToBounds` 不会溢出到封面区。
 * 上限 3 个（[WaveformEffectsState.RIPPLE_CAPACITY]），每帧最多 3 笔 `drawCircle`。
 */
private fun DrawScope.drawShockwaveRipples(
    state: WaveformEffectsState,
    barColor: Color,
    stroke: Stroke,
    breath: Float,
) {
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val maxRadius = maxOf(size.width, size.height) * RIPPLE_MAX_RADIUS_FRACTION
    for (i in 0 until state.rippleCapacity) {
        val progress = state.rippleProgressAt(i)
        if (progress < 0f) continue
        val radius = progress * maxRadius
        if (radius < 1f) continue
        drawCircle(
            color = barColor,
            radius = radius,
            center = Offset(centerX, centerY),
            alpha = (1f - progress) * RIPPLE_MAX_ALPHA * breath,
            style = stroke,
        )
    }
}

/**
 * C 档：粒子（画在柱子上面）。
 *
 * 位置在状态里是**归一化坐标**（0..1），这里乘宽高 ⇒ 旋转/分栏后自动跟着变，不需要重算轨迹。
 * 半径与 alpha 都随寿命衰减；池容量固定 24，每帧最多 24 笔 `drawCircle`。
 */
private fun DrawScope.drawParticles(
    state: WaveformEffectsState,
    barColor: Color,
    particleRadiusPx: Float,
    breath: Float,
) {
    for (i in 0 until state.particleCapacity) {
        val life = state.particleLifeAt(i)
        if (life <= 0f) continue
        drawCircle(
            color = barColor,
            radius = particleRadiusPx * (0.4f + 0.6f * life),
            center = Offset(state.particleXAt(i) * size.width, state.particleYAt(i) * size.height),
            alpha = life * breath,
        )
    }
}
