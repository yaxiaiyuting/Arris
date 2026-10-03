/*
 * Ncrust —— ncm 第三方客户端
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.player.AudioFeatureExtractor
import com.takahashirinta.ncrust.ui.player.motion.DisplayRefresh
import com.takahashirinta.ncrust.ui.player.motion.MotionBindings
import com.takahashirinta.ncrust.ui.player.waveform.BandColorRoles
import com.takahashirinta.ncrust.ui.player.waveform.BandDominance
import com.takahashirinta.ncrust.ui.player.waveform.BandLanes
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerPrefs
import com.takahashirinta.ncrust.ui.player.waveform.WaveformCurve
import com.takahashirinta.ncrust.ui.player.waveform.WaveformEffectsState
import com.takahashirinta.ncrust.ui.player.waveform.isolateFrame
import com.takahashirinta.ncrust.ui.theme.relativeLuminance
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

    /**
     * v3.2.2：**主导频段判定 + 颜色过渡**（纯逻辑，见 `BandDominance`）。
     *
     * 它放在这里而不是渲染组件里，理由与环形缓冲、C 档状态机完全相同：
     * 它必须并进 [pump] 的「要不要重绘」判据 —— 颜色过渡是一段 80ms 的逐帧动画，
     * 不并入判据就会出现「颜色走到一半停住」（v2.8.0 的峰值保持踩过同形状的坑）。
     */
    private val band = BandDominance()

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
    fun onBar(rootMeanSquare: Double, low: Double, mid: Double, high: Double) {
        if (enabled) {
            ring.push(rootMeanSquare.toFloat(), low.toFloat(), mid.toFloat(), high.toFloat())
        }
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

    /** 只有全带值的重载（老调用点与单测用）：低频带取同一个值，中/高频段无数据。 */
    fun onBar(rootMeanSquare: Double) = onBar(rootMeanSquare, rootMeanSquare, 0.0, 0.0)

    /** 全带 + 低频的重载（v2.9.0 的调用点与单测用）：中/高频段无数据。 */
    fun onBar(rootMeanSquare: Double, bass: Double) = onBar(rootMeanSquare, bass, 0.0, 0.0)

    /**
     * UI 线程按帧率调用；有新数据（或平滑/峰值/呼吸/C 档特效尚未收敛）时才让画面失效。
     * @param dtMs 距上一帧的毫秒数（平滑系数由它算，见 [WaveformRing.pump]）。
     * @param effects 当前档位的能力位（组合期读一次后捕获，帧路径里不再读任何 state）。
     */
    fun pump(active: Boolean, dtMs: Float, effects: VisualizerEffects) {
        var changed = ring.pump(active, dtMs, effects)
        // v3.2.2：主导频段推进。**门槛是 `effects.waveBandOn`**：
        // 关掉（用户关了多频段开关）时音频线程根本不发布特征值，
        // 此时若还去读 `featureLow()` 会把「上一首残留的」或初值 0 当数据用。
        // 这一步零分配、只写标量；返回值并入 changed ⇒ 80ms 的颜色过渡不会被冻住。
        if (band.update(
                low = if (effects.waveBandOn) featureLow() else 0f,
                mid = if (effects.waveBandOn) featureMid() else 0f,
                high = if (effects.waveBandOn) featureHigh() else 0f,
                available = effects.waveBandOn && featAvailable,
                dtMs = dtMs,
            ) && !changed
        ) {
            changed = true
        }
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

    /**
     * v3.2.2：把**三条频带**的历史窗口拷进调用方复用的三个数组（三条泳道用；零分配）。
     *
     * 调用前必须确认 [featuresAvailable] —— 降级路径下中/高频段没有数据，
     * 画出来会是两条贴着中线的直线，那是"编造"而不是"没测到"。
     */
    fun snapshotBands(low: FloatArray, mid: FloatArray, high: FloatArray) =
        ring.copyBandsInto(low, mid, high)

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
     * v3.2.2：本帧的三个频段颜色权重（低 / 中 / 高，和为 1）。
     *
     * @return 权重是否可用。**false 是"测不到"的如实表达**：特征链路不可用（降级路径）
     *   或还没得出结论时，调用方必须退回单一 RMS 颜色 ——
     *   而不是把「没有中高频」画成「中高频能量为零」（那正是降级路径 `mix=0.0` 的既有缺口）。
     */
    fun snapshotBand(out: FloatArray): Boolean = band.weights(out)

    /** v3.2.2：主导频段判定是否可信（诊断 / 单测）。 */
    fun bandReliable(): Boolean = band.isReliable()

    /** v3.2.2：当前主导频段（诊断 / 单测）。 */
    fun dominantBand(): Int = band.dominant

    /**
     * v3.2.3：两条柱之间的推进相位（0..1）。渲染层用它把历史窗口**连续左移**一格 ——
     * 音频缓冲只有 ~10 Hz，没有这一层时三条泳道的形状是"每 100ms 跳一格"（用户反馈"刷新率低"）。
     */
    fun scrollPhase01(): Float = ring.scrollPhase01()

    /**
     * v3.2.2：曲线绘制**丢帧计数**（只增不减，UI 线程单写者）。
     *
     * 渲染侧一帧的失败由 [isolateFrame] 吞掉（丢这一帧、不向上抛）。计数留在这里是因为
     * 「静默降级」和「正常」在画面上长得一样 —— 真出问题时必须有个地方能问
     * "到底丢了多少帧"。**不在这里打日志**：失败可能连续发生，每帧一条日志本身就是雪崩。
     */
    @Volatile
    private var curveDroppedFrames: Int = 0

    fun noteCurveFailure() {
        curveDroppedFrames += 1
    }

    /** 诊断：曲线绘制累计丢帧数。 */
    fun droppedCurveFrames(): Int = curveDroppedFrames

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
        band.reset()
        curveDroppedFrames = 0
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
 *
 * ## ⚠️ v3.2.4 · P1：这个函数**不再有生产调用点**，判据已换成「帧步长 + 真实刷新率」
 *
 * 它的问题是把「重绘预算」写死成**时间阈值**，而阈值只能落在整数个 vsync 上：
 * 16ms 在 60Hz 面板上是「每帧」（无量化、平滑），在 120Hz 面板上是「每两帧、且余量只有
 * 0.667ms」（一抖就变三帧 ⇒ 位移 ±49% ⇒ 用户报的「抖」）。根因、仿真与四档对照见
 * `docs/verification/v3.2.4/probe-waveform-jitter-hfr.md`。
 *
 * 现在生产路径走 [visualizerFrameStride]（数帧，不数时间）+ [DisplayRefresh]（读真实刷新率）。
 * 本函数**保留**是为了让「低端机 33ms / 现代机 16ms」这个历史口径有一个可读的落点，
 * 并由 `DisplayRefreshTest` 钉住它与新判据的一致性（低内存 ⇒ 33ms 档，否则 ⇒ 每帧）。
 */
fun visualizerFrameIntervalMs(context: Context): Long {
    val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    // v3.2.3：**`SDK_INT < 26` 这条判据被删掉了**（原来是 `SDK_INT < O || isLowRamDevice`）。
    //
    // 为什么改（用户实测反馈「刷新率好低」）：S6（Android 7.0）因此被钉在 **33ms = 30fps**，
    // 而真机 release 实测它在三条泳道下的帧时间是 **p50 19ms / p90 26ms**
    // （docs/verification/v3.2.2/probe-perf-tier.md）—— 也就是说设备**本来跑得动 ~50fps**，
    // 30fps 上限纯粹是判据自己加的。v1.8.1 当年选 33ms 的理由是"S6 是流水线式掉帧、
    // 帧间隔量不出真实压力"，那个理由针对的是**自适应降级**（v3.0.0 已删除），
    // 不构成"静态判据也按 SDK 一刀切"的依据。
    //
    // 保留的是 `isLowRamDevice`：那一条是**真的内存不够**（≤1GB 级），
    // 与"系统版本老"是两件事 —— 一台 2015 年的 3GB 旗舰和一台 1GB 的入门机不该同一条判据。
    val lowTier = activityManager?.isLowRamDevice == true
    return if (lowTier) VISUALIZER_FRAME_INTERVAL_SLOW_MS else VISUALIZER_FRAME_INTERVAL_FAST_MS
}

/**
 * v3.2.4 · P1：当前设备上可视化的**帧步长** —— 每几帧推进一次（铁律 33）。
 *
 * - 非低内存设备：**1**（`withFrameNanos` 回调一次推进一次 ⇒ 恰好等于面板刷新率）；
 * - 低内存设备：`ceil(33ms / 帧间隔)` ⇒ 60Hz 上 2、90Hz 上 3、120Hz 上 4，**始终约 30fps**。
 *
 * 判据只吃 `isLowRamDevice` 与**读出来的刷新率**，不看系统版本
 * （v3.2.3 已实测推翻了「Android 7 就该 30fps」这条假设）。
 *
 * @param refreshRateHz 当前显示的刷新率（一般来自 `rememberDisplayRefreshRate()`）。
 */
fun visualizerFrameStride(context: Context, refreshRateHz: Float): Int {
    val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    val lowTier = activityManager?.isLowRamDevice == true
    return DisplayRefresh.strideFor(refreshRateHz, lowTier)
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
 * | 简洁 | 圆角柱 + 峰值保持 + **频带着色** + 越旧越淡（水平 alpha 渐变） | +≤28 笔（峰值只在高出曲线 1.5dp 时才画） |
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
    // v3.2.2：三条频带的历史窗口（低 / 中 / 高）。**降级路径下不读它们** ——
    // 中/高频没有数据时画出来是两条贴着中线的直线，那是编造而不是"没测到"。
    val lowWindow = remember(barCount) { FloatArray(barCount) }
    val midWindow = remember(barCount) { FloatArray(barCount) }
    val highWindow = remember(barCount) { FloatArray(barCount) }
    // v3.2.2：曲线几何的复用缓冲（**跨帧复用 = 帧路径零分配**，见 WaveformCurve 的 KDoc）。
    //  三频带模式下点序列是三条泳道**拼起来**的，所以缓冲要 3 倍长。
    val pointCount = barCount * BandLanes.LANE_COUNT
    val heights = remember(pointCount) { FloatArray(pointCount) }
    val tangents = remember(pointCount) { FloatArray(pointCount) }
    val path = remember { Path() }
    val bandWeights = remember { FloatArray(3) }
    val laneEmphasis = remember { FloatArray(BandLanes.LANE_COUNT) }
    val palette = remember { IntArray(3) }
    // 分级：**组合期读一次**（改设置时才会重组一次）。
    // draw 只用这个捕获值 —— 帧路径里零 state 读（除了下面 draw 里的 generation）。
    val effects = VisualizerPrefs.effects.value
    // v3.2.2：HCT 三角色（低 / 中 / 高）。**只在主题色或明暗变化时算一次** ——
    // 两次 CAM16 求解绝不能进帧路径（见 BandColorRoles 的调用纪律）。
    val background = LocalMetroColors.current.background
    val isDarkTheme = remember(background) { relativeLuminance(background.toArgb()) < 0.5 }
    val paletteReady = remember(barColor, isDarkTheme) {
        BandColorRoles.paletteFor(barColor.toArgb(), isDarkTheme, palette)
    }
    val brushCache = remember { RibbonBrushCache() }
    // v3.0.0：明亮端的颜色（高频占主导的柱子用它）。`Color` 是 value class ⇒ 这个 lerp 不分配。
    val density = LocalDensity.current
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
        WaveformStore.snapshot(bars, peaks)
        // v3.2.2：三频带泳道模式 = 用户开着多频段着色 **且** 特征链路真的可用 **且**
        // 三角色算出来了。任一不满足 ⇒ 退回 v3.2.1 的单条曲线（如实降级，见铁律 28）。
        val threeLane = effects.waveBandOn && WaveformStore.featuresAvailable() && paletteReady
        if (threeLane) {
            WaveformStore.snapshotBands(lowWindow, midWindow, highWindow)
        }
        val n = bars.size
        // v3.2.2：曲线至少要有两个点才谈得上"相邻两点连接"。
        if (n < 2 || size.width <= 0f || size.height <= 0f) return@Canvas
        val minBar = 1.dp.toPx()
        // 呼吸：只在 B 档读；不是 Compose state ⇒ 不触发重组，只是一次字段读。
        val breath = if (effects.breathe) WaveformStore.breatheScale() else 1f
        // 点按交互翻转着色模式：普通字段读 + 点按时手动失效一次（见 WaveformStore.toggleColoringMode）。
        val flow = effects.flow != WaveformStore.isColoringInverted()
        // C 档：涟漪画在曲线**下面**（背景层），粒子画在曲线**上面**（前景层）。
        if (effects.shockwave) {
            drawShockwaveRipples(WaveformStore.showcase, barColor, rippleStroke, breath)
        }
        // ── v3.2.2：颜色与几何 ────────────────────────────────────────────────
        // 主导频段权重来自 `BandDominance`（平滑 300ms + 滞回 20% + 静音闸门 + 100ms 过渡），
        // 在 `pump` 里推进。它的用途在三条泳道布局下变成**泳道强调**：
        // 主导的那条拉到满亮度、另外两条压到 [LANE_DIM] —— 这是"这一刻谁在主导"的读法，
        // 而**不是**把整条曲线换成一个颜色（那样会把"左低中中右高"的空间语义抹掉）。
        val bandReliable = paletteReady && effects.waveBandOn && WaveformStore.snapshotBand(bandWeights)
        val heightPx = size.height
        // ── 三频带泳道（用户要的形态：左低 / 中中 / 右高，各自滚动、无缝拼接）──
        if (threeLane) {
            // v3.2.3：**亚格插值**（连续滚动）。音频缓冲 ~10 Hz ⇒ 历史窗口每 100ms 整条跳一格；
            // 帧循环再快，看到的也是 10 Hz 的阶跃。这里用 `scrollPhase01()` 在**相邻两格之间**
            // 线性取值，形状因此按帧率连续左移。插的是"两格之间"，不是"未来" —— 没有假数据。
            val phase = WaveformStore.scrollPhase01()
            for (lane in 0 until BandLanes.LANE_COUNT) {
                val window = when (lane) {
                    BandLanes.LANE_LOW -> lowWindow
                    BandLanes.LANE_MID -> midWindow
                    else -> highWindow
                }
                val base = lane * n
                for (i in 0 until n) {
                    val a = window[i]
                    // 最后一格没有"下一格"可插（新柱还没到）—— 保持原值，等同"最新点先站住"。
                    val b = if (i < n - 1) window[i + 1] else window[n - 1]
                    val v = a + (b - a) * phase
                    val half = BandLanes.amplitude(v, lane) * heightPx * 0.5f
                    heights[base + i] = if (half < minBar * 0.5f) minBar * 0.5f else half
                }
            }
            val total = n * BandLanes.LANE_COUNT
            // **点距在整条拼接序列上恒定** —— 这是"无缝"的几何含义：
            // 接缝两侧的点距与段内完全一样，曲线在接缝处不会"折一下"。
            // 三段各自的高度只由**自己那个频带**决定，不做跨频带的插值平均，
            // 所以"低音 / 中音 / 高音"的差距不会被抹平（用户明确要求）。
            val step = size.width / (total - 1)
            WaveformCurve.computeTangents(heights, total, step, tangents)
            for (lane in 0 until BandLanes.LANE_COUNT) {
                laneEmphasis[lane] = if (bandReliable) {
                    LANE_DIM + (1f - LANE_DIM) * bandWeights[BandLanes.LANE_ROLE[lane]]
                } else {
                    1f
                }
            }
            val laneBrush = brushCache.lanes(size.width, palette, laneEmphasis)
            // v3.2.2（铁律 28）：一帧的绘制整段隔离 —— 失败丢这一帧，不向上抛、不重试。
            isolateFrame(onFailure = { WaveformStore.noteCurveFailure() }) {
                drawWaveformCurve(
                    heights, peaks, total, tangents, path, barColor, laneBrush, effects,
                    xShift = 0f, step = step, firstX = 0f, heightPx = heightPx,
                    minBar = minBar, dotRadiusPx = dotRadiusPx, peakCapPx = peakCapPx,
                    breath = breath, markerStride = LANE_MARKER_STRIDE,
                )
                // 渐变流动（精致档）：**叠一层**同路径的移动 alpha 波，而不是把色带推到泳道上 ——
                // 泳道色带按横轴固定在"低/中/高"三段上，推着走会与那个空间语义打架。
                if (flow) {
                    val phasePx = WaveformStore.flowPhase01() * size.width * FLOW_TILE_FRACTION
                    val sheen = brushCache.flow(size.width, palette[BandColorRoles.MID])
                    if (sheen != null) {
                        translate(left = -phasePx) {
                            drawCurveSheen(
                                heights, total, tangents, path, sheen, effects,
                                xShift = phasePx, step = step, heightPx = heightPx,
                                breath = breath,
                            )
                        }
                    }
                }
            }
        } else {
            // ── 降级 / 单条曲线（v3.2.1 的形态）：特征不可用、或用户关掉了多频段着色 ──
            val ribbonColor = Color(
                BandColorRoles.resolve(palette, bandWeights, bandReliable, barColor.toArgb())
            )
            for (i in 0 until n) {
                heights[i] = sqrt(bars[i].coerceIn(0f, 1f)) * heightPx
                if (heights[i] < minBar) heights[i] = minBar
                heights[i] *= 0.5f
            }
            val step = size.width / (n - 1)
            WaveformCurve.computeTangents(heights, n, step, tangents)
            val flowBrush = if (flow) brushCache.flow(size.width, ribbonColor.toArgb()) else null
            val ageBrush = if (!flow && effects.timeOrderedTint) {
                brushCache.age(size.width, ribbonColor.toArgb())
            } else {
                null
            }
            isolateFrame(onFailure = { WaveformStore.noteCurveFailure() }) {
                if (flowBrush != null) {
                    val phasePx = WaveformStore.flowPhase01() * size.width * FLOW_TILE_FRACTION
                    translate(left = -phasePx) {
                        drawWaveformCurve(
                            heights, peaks, n, tangents, path, ribbonColor, flowBrush, effects,
                            xShift = phasePx, step = step, firstX = 0f, heightPx = heightPx,
                            minBar = minBar, dotRadiusPx = dotRadiusPx, peakCapPx = peakCapPx,
                            breath = breath,
                        )
                    }
                } else {
                    drawWaveformCurve(
                        heights, peaks, n, tangents, path, ribbonColor, ageBrush, effects,
                        xShift = 0f, step = step, firstX = 0f, heightPx = heightPx,
                        minBar = minBar, dotRadiusPx = dotRadiusPx, peakCapPx = peakCapPx,
                        breath = breath,
                    )
                }
            }
        }
        if (effects.particles) {
            // 粒子用**中频泳道**的颜色（= 主题色）：它与 v3.2.1 的粒子同色，
            // 又在三频带模式下有明确归属（中频段就是主题色那条）。
            drawParticles(WaveformStore.showcase, Color(palette[BandColorRoles.MID]), particleRadiusPx, breath)
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

// ---- v3.0.0：多频段调制（逐柱着色 + 三频带能量条）的三组数字 ----

/**
 * v3.2.2：简洁档「越旧越淡」的左端不透明度。
 *
 * v2.8.0 的逐柱 alpha 阶梯（`0.30 + 0.70 × (i+1)/n`）在连续曲线上没有对应物 ——
 * 曲线上没有"每根柱子各自的 alpha"。这里把它换成一条**水平 alpha 渐变**
 * （左端 0.30 → 右端 1.0），读法不变：左边是历史、右边是此刻。
 */
private const val TIME_TINT_MIN_ALPHA = 0.30f

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
 * v3.2.2：非主导泳道的亮度系数（主导泳道为 1）。
 *
 * 0.78 的依据：再低（<0.6）会让两条泳道看起来像"没画"，再高（>0.9）就分不出谁在主导。
 * 强调量的过渡由 `BandDominance` 的 100ms 负责（铁律 30：不得硬切）。
 */
private const val LANE_DIM = 0.78f

/**
 * v3.2.2：三频带模式下光点 / 峰值标记的**采样步长**。
 *
 * 三频带模式有 84 个点，逐点画光点就是 84 个圆 + 84 个峰值条 ——
 * 一半是纯浪费：曲线本身已经把形状说清楚了，点太密反而糊成一条线。
 * 2 表示隔一个点画一个（42 个），与 v3.2.1 的 28 个同一量级。
 */
private const val LANE_MARKER_STRIDE = 2

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
 * v3.2.2：一帧的**连续曲线**绘制（顶层私有函数，不是 draw lambda 里的局部函数）。
 *
 * 为什么抽出来：需要它的有两个调用点（流动 / 不流动），而局部函数或 lambda 会带来
 * 捕获与分配的不确定性。这里全部参数都是基本类型或**调用方复用**的对象
 * （`heights` / `tangents` / `path` 都是 `remember` 出来的）⇒ 调用本身**零分配**。
 *
 * ## 几何：一条镜像丝带
 *
 * 每个采样点的**半厚** `h_i` 由柱值决定（`h_i = max(1dp, sqrt(柱值) × H) / 2`，
 * 与 v2.8.0 的柱高逐值同源），顶部包络是 `centerY − h_i`、底部是 `centerY + h_i`，
 * 两条包络各用 27 段 `cubicTo` 连起来再 `close()` ⇒ **一笔填充**画出整条带子。
 * 相邻段共享端点与切线 ⇒ C1 连续，肉眼看不到"接缝"。
 *
 * 曲线用 [WaveformCurve] 的**保单调（PCHIP）切线**：探针 §2.5 在 19055 帧真实波形上
 * 量过，Catmull-Rom 会在 74% 的帧里冲出数据范围、其中 224 帧**画出负高度**
 * （曲线穿进自己的镜像里）。PCHIP 的过冲与负高度都是 **0**（铁律 31）。
 *
 * ## 颜色：一条带子一个颜色（不再是逐根柱子一个颜色）
 *
 * 颜色由 `BandDominance` 给出的**三频段权重**混合而成（见 `AudioVisualizerBars`），
 * 所以「这一刻是哪个频带主导」是**整条曲线**的属性，而不是某几根柱子的属性。
 * 这是 v3.2.2 相对 v3.0.0 逐柱着色的**有意替换**：逐柱着色每根柱子都在变，
 * 而主导频段是平滑 + 滞回之后的结论（探针实测 0.103 次/秒），读起来是"颜色跟着音乐走"，
 * 不是"颜色在抖"。既有的**渐变流动**没有丢：它现在作用在整条带子上。
 *
 * `effects.rounded`（圆角柱）在曲线上**没有几何可作用**（连续的带子没有角），
 * 这一位保留是为了 API 稳定与档位表可读，曲线路径不再读它。
 *
 * @param xShift 只有流动模式非 0：画布已整体左移 `xShift`，这里给每个 x 补回去。
 * @param brush 非 null 时用 `brush=`（渐变流动 / 时序淡出），否则用 `color=` ——
 *   两者是 API 层的二选一，[VisualizerEffects.colorChannelMode] 是这条规则的唯一读法。
 */
private fun DrawScope.drawWaveformCurve(
    heights: FloatArray,
    peaks: FloatArray,
    count: Int,
    tangents: FloatArray,
    path: Path,
    color: Color,
    brush: Brush?,
    effects: VisualizerEffects,
    xShift: Float,
    step: Float,
    firstX: Float,
    heightPx: Float,
    minBar: Float,
    dotRadiusPx: Float,
    peakCapPx: Float,
    breath: Float,
    /** v3.2.2：光点 / 峰值标记的采样步长（三频带模式用 2，单带模式用 1）。 */
    markerStride: Int = 1,
) {
    val n = minOf(count, heights.size, tangents.size)
    if (n < 2 || step <= 0f || heightPx <= 0f) return
    val centerY = heightPx / 2f
    val third = step / 3f
    // 顶部包络（左→右），再底部包络（右→左）闭合。**只读写复用的 path**。
    path.reset()
    path.moveTo(firstX + xShift, centerY - heights[0])
    for (i in 0 until n - 1) {
        val xa = firstX + i * step + xShift
        val xb = xa + step
        val c1 = centerY - WaveformCurve.controlY1(heights[i], tangents[i], step)
        val c2 = centerY - WaveformCurve.controlY2(heights[i + 1], tangents[i + 1], step)
        path.cubicTo(xa + third, c1, xb - third, c2, xb, centerY - heights[i + 1])
    }
    val lastX = firstX + (n - 1) * step + xShift
    path.lineTo(lastX, centerY + heights[n - 1])
    for (i in n - 2 downTo 0) {
        val xa = firstX + i * step + xShift
        val xb = xa + step
        val c2 = centerY + WaveformCurve.controlY2(heights[i + 1], tangents[i + 1], step)
        val c1 = centerY + WaveformCurve.controlY1(heights[i], tangents[i], step)
        path.cubicTo(xb - third, c2, xa + third, c1, xa, centerY + heights[i])
    }
    path.close()
    if (brush != null) {
        drawPath(path = path, brush = brush, alpha = breath)
    } else {
        drawPath(path = path, color = color, alpha = breath)
    }
    // 峰值保持：与 v2.8.0 **逐值同构**的短横条（同一个 sqrt 映射，否则峰值会看起来比曲线还矮），
    // 只在明显高于曲线时才画。位置跟着曲线走（峰值的半厚 → y）。
    val dotThreshold = minBar * DOT_MIN_BAR_MULTIPLE
    val peakVisibleDelta = peakCapPx / 2f
    val stride = if (markerStride < 1) 1 else markerStride
    val markerWidth = (step * 0.6f * stride).coerceAtLeast(1f)
    var i = 0
    while (i < n) {
        val x = firstX + i * step + xShift
        if (effects.peaks) {
            val peakHalf = sqrt(peaks[i].coerceIn(0f, 1f)) * heightPx * 0.5f
            if (peakHalf > heights[i] + peakVisibleDelta) {
                val topLeft = Offset(x - markerWidth / 2f, centerY - peakHalf - peakCapPx)
                val size = Size(markerWidth, peakCapPx)
                // v3.2.2：三频带模式下峰值标记**跟着它所在泳道的颜色**（同一个渐变笔刷在
                // 那个 x 处采样出来的就是那一段的颜色）—— 否则会变成一条横穿三条泳道的
                // 主题色虚线，看起来像渲染错误。
                if (brush != null) {
                    drawRect(brush = brush, topLeft = topLeft, size = size, alpha = 0.9f * breath)
                } else {
                    drawRect(color = color, topLeft = topLeft, size = size, alpha = 0.9f * breath)
                }
            }
        }
        if (effects.dots && heights[i] * 2f > dotThreshold) {
            drawCircle(
                color = color,
                radius = dotRadiusPx,
                center = Offset(x, centerY - heights[i] - dotRadiusPx),
                alpha = breath,
            )
        }
        i += stride
    }
}

/**
 * v3.2.2：**渐变流动的叠加层**（只在三频带模式 + 精致档用）。
 *
 * 三频带模式下，沿着横轴的色带被**泳道**占用了（左低 / 中中 / 右高）——
 * v2.8.0 的"整条色带推着走"会把三个频段的色区在横轴上搬来搬去，与那个空间语义直接打架。
 * 所以流动改成**叠一层**：同一条路径再填一次，笔刷是一条移动的 alpha 波（色相不变），
 * 观感仍然是"有东西在流"，而泳道的颜色归属不动。
 *
 * 成本：**多一次 `drawPath`**（与 v3.2.1 的 28 次带 shader 绘制相比仍然少得多）。
 */
private fun DrawScope.drawCurveSheen(
    heights: FloatArray,
    count: Int,
    tangents: FloatArray,
    path: Path,
    brush: Brush,
    effects: VisualizerEffects,
    xShift: Float,
    step: Float,
    heightPx: Float,
    breath: Float,
) {
    val n = minOf(count, heights.size, tangents.size)
    if (n < 2 || step <= 0f || heightPx <= 0f) return
    val centerY = heightPx / 2f
    val third = step / 3f
    path.reset()
    path.moveTo(xShift, centerY - heights[0])
    for (i in 0 until n - 1) {
        val xa = i * step + xShift
        val xb = xa + step
        val c1 = centerY - WaveformCurve.controlY1(heights[i], tangents[i], step)
        val c2 = centerY - WaveformCurve.controlY2(heights[i + 1], tangents[i + 1], step)
        path.cubicTo(xa + third, c1, xb - third, c2, xb, centerY - heights[i + 1])
    }
    path.lineTo((n - 1) * step + xShift, centerY + heights[n - 1])
    for (i in n - 2 downTo 0) {
        val xa = i * step + xShift
        val xb = xa + step
        val c2 = centerY + WaveformCurve.controlY2(heights[i + 1], tangents[i + 1], step)
        val c1 = centerY + WaveformCurve.controlY1(heights[i], tangents[i], step)
        path.cubicTo(xb - third, c2, xa + third, c1, xa, centerY + heights[i])
    }
    path.close()
    drawPath(path = path, brush = brush, alpha = SHEEN_ALPHA * breath)
}

/** 渐变流动叠加层的整体不透明度。0.45 看得出在流，又不会把泳道颜色洗白。 */
private const val SHEEN_ALPHA = 0.45f

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
 * v3.2.2：曲线用的 **Brush 缓存**（渐变流动 / 时序淡出各一份）。
 *
 * ## 为什么键里多了**颜色**
 *
 * v2.8.0 的 `FlowBrushCache` 只在**尺寸变化**时重建，因为那时整条波形只有一个颜色
 * （`LocalMetroColors.current.primary`）。v3.2.2 的颜色由主导频段驱动，切换时有一段
 * 80ms 的过渡 —— 颜色**逐帧在变**。若按"颜色变了就重建"，过渡期间每帧都会 new 一个
 * `LinearGradient`（1 个 Brush + 1 个 colors List + 1 个 native SkShader），
 * 直接违反零分配（`AudioVisualizerBars` 的契约第 1 条）。
 *
 * 所以键是 **颜色量化到每通道 5 bit**：过渡的 80ms 里最多重建 2~6 次（每次切换约
 * 0.1 次/秒 ⇒ 每秒不到一次），**稳定态零重建**。量化带来的色阶差
 * （每通道 8 级）在一条 alpha 渐变的色带上不可见。
 *
 * ## 两个 Brush 的语义
 *
 *  - [flow]：B 档（精致起）的「渐变流动」。三个 stop 是**同一个颜色**的三个 alpha，
 *    两端一样暗 ⇒ `TileMode.Repeated` 的接缝不可见，流动只需改一个平移量。
 *  - [age]：简洁档的「越旧越淡」—— 把 v2.8.0 的逐柱 alpha 阶梯换成一条水平 alpha 渐变
 *    （连续曲线上没有"每根柱子各自的 alpha"这回事了，但"左边是历史"这个读法要留住）。
 */
private class RibbonBrushCache {

    private var cachedWidth = Float.NaN
    private var cachedKey = Int.MIN_VALUE
    private var cachedFlow: Brush? = null
    private var cachedAge: Brush? = null
    private var cachedLaneKey = Int.MIN_VALUE
    private var cachedLanes: Brush? = null

    /** 颜色量化键：每通道 5 bit（alpha 不参与 —— 它由 `drawPath` 的 `alpha=` 单独乘）。 */
    private fun keyOf(argb: Int): Int =
        (((argb shr 19) and 0x1F) shl 10) or (((argb shr 11) and 0x1F) shl 5) or ((argb shr 3) and 0x1F)

    private fun invalidateIfNeeded(widthPx: Float, argb: Int) {
        val width = if (widthPx > 0f) widthPx else 1f
        val key = keyOf(argb)
        if (cachedWidth == width && cachedKey == key) return
        cachedWidth = width
        cachedKey = key
        cachedFlow = null
        cachedAge = null
    }

    /**
     * v3.2.2：**三条泳道的横向渐变**（左低 / 中中 / 右高，接缝处渐变过渡）。
     *
     * 渐变的 stop 按**宽度比例**固定在 `1/3`、`2/3` 两侧各 ±[BandLanes.SEAM_FRACTION]：
     *  - 每个频段在自己的那一段里是**纯色**（读得出"这一段是低频"）；
     *  - 两段之间留出过渡带做颜色混合（"中间连接用渐变色"）。
     *
     * 缓存键 = 宽度 + 三个量化色 + 三个量化强调量。强调量由主导频段决定（约 0.1 次/秒切换
     * + 100ms 过渡），量化到 1/16 ⇒ 稳定态零重建、过渡期最多几次重建（与 `flow` 同一套口径）。
     */
    fun lanes(widthPx: Float, palette: IntArray, emphasis: FloatArray): Brush {
        val width = if (widthPx > 0f) widthPx else 1f
        var key = 17
        for (i in 0 until minOf(3, palette.size)) key = key * 31 + keyOf(palette[i])
        for (i in 0 until minOf(3, emphasis.size)) {
            key = key * 31 + ((emphasis[i].coerceIn(0f, 1f) * 16f).toInt())
        }
        if (cachedWidth == width && cachedLaneKey == key) {
            cachedLanes?.let { return it }
        }
        cachedWidth = width
        cachedLaneKey = key
        val seam = BandLanes.SEAM_FRACTION
        val oneThird = 1f / 3f
        val twoThirds = 2f / 3f
        fun lane(i: Int): Color {
            val argb = if (i < palette.size) palette[i] else palette[0]
            val a = if (i < emphasis.size) emphasis[i].coerceIn(0f, 1f) else 1f
            return Color(argb).copy(alpha = a)
        }
        val c0 = lane(BandLanes.LANE_LOW)
        val c1 = lane(BandLanes.LANE_MID)
        val c2 = lane(BandLanes.LANE_HIGH)
        val brush = Brush.horizontalGradient(
            0.00f to c0,
            (oneThird - seam).coerceAtLeast(0f) to c0,
            (oneThird + seam).coerceAtMost(1f) to c1,
            (twoThirds - seam).coerceAtLeast(0f) to c1,
            (twoThirds + seam).coerceAtMost(1f) to c2,
            1.00f to c2,
            startX = 0f,
            endX = width,
        )
        cachedLanes = brush
        return brush
    }

    /** 渐变流动的色带（`TileMode.Repeated` + 两端同暗）。同时用作三频带模式的叠加流光。 */
    fun flow(widthPx: Float, argb: Int): Brush {
        invalidateIfNeeded(widthPx, argb)
        cachedFlow?.let { return it }
        val color = Color(argb)
        val tile = (cachedWidth * FLOW_TILE_FRACTION).coerceAtLeast(1f)
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
        cachedFlow = brush
        return brush
    }

    /** 时序淡出（左旧右新）：一条水平 alpha 渐变。 */
    fun age(widthPx: Float, argb: Int): Brush {
        invalidateIfNeeded(widthPx, argb)
        cachedAge?.let { return it }
        val color = Color(argb)
        val brush = Brush.horizontalGradient(
            colors = listOf(
                color.copy(alpha = TIME_TINT_MIN_ALPHA),
                color.copy(alpha = 1f),
            ),
            startX = 0f,
            endX = cachedWidth,
        )
        cachedAge = brush
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
