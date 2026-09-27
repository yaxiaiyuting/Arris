/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.1 · P0：**可视化 tee 不得改变音频输出，也不得因为声道数而抛异常。**
 *
 * ## 这一版修的是什么（真机实测的完整因果，别再退回旧写法）
 *
 * v1.8.0 引入可视化时用的是 media3 自带的 `WaveformAudioBufferSink(barsPerSecond, 1, …)`，
 * 第二个参数 `1` 表示「把输入混成单声道」（当时的注释还写着"media3 内部走默认的
 * ChannelMixingMatrix"——**那是一条没有验证过的平台假设**）。而 media3 1.5.0 的
 * `ChannelMixingMatrix.createMixingCoefficients(int, int)` 只实现了三种矩阵：
 *
 *   | 输入 → 输出 | 结果 |
 *   |---|---|
 *   | N → N | 单位矩阵 |
 *   | 1 → 2 | `{1, 1}` |
 *   | 2 → 1 | `{0.5, 0.5}` |
 *   | **其它（含 6 → 1）** | `UnsupportedOperationException: Default channel mixing coefficients for 6->1 are not yet implemented.` |
 *
 * 而 QQ 音乐的「臻品音质 / 臻品全景声」档（本应用统一档位表里的 `dolby` / `jyeffect`）
 * 实测回的**就是 6 声道 FLAC**（`Q001…flac` = FLAC 44100Hz **6ch** 16bit，
 * 见 `docs/verification/v2.2.1/p0-quality-loop/PROBE.md`），于是：
 *
 *   1. `TeeAudioProcessor.flush()` → `WaveformAudioBufferSink.flush()` → `ChannelMixingMatrix.create(6, 1)` **抛异常**；
 *   2. 异常把 AudioSink 打进不可恢复状态（logcat：`Disable failed` / `Reset failed`）；
 *   3. **同一个 player 实例之后任何档位都播不出来**（连 128k mp3 也报同一个错）；
 *   4. `PlayerViewModel.handlePlaybackError` 沿 8 档阶梯一路重试 → 每次 `play()` 抢一次音频焦点；
 *   5. 最低档仍失败 → 跳歌 → 下一首一样失败 → 无限跳歌。
 *
 * 所以本文件的契约有三条，缺一不可：
 *
 *  - **不混音**：输出声道数 = 输入声道数（`onConfigure` 里保持 format 不变），
 *    因此**永远不会**构造 ChannelMixingMatrix，任何声道数都能播；
 *  - **不抛异常**：RMS 计算对未知编码直接跳过（返回 0），绝不把音频线程上的异常抛给播放器；
 *  - **不改变输出格式**：`TeeAudioProcessor` 只旁路读 PCM，本 sink 的输出只是旁路数据。
 *
 * 立体声/单声道的听感、以及可视化柱高语义与旧版**一致**（RMS 定义相同，
 * 只是旧版在 6 声道时直接崩，新版对全部声道一起求 RMS）。
 *
 * ## v2.8.0 · P1-A：补上 v2.2.1 留下的那条裸路
 *
 * 探针（`docs/verification/v2.8.0/probe-waveform.md` §4）指出：v2.2.1 只把 **RMS 计算**
 * 收进了 `runCatching`，而 `WaveformStore.onBar(rms)` 在它**外面**裸奔 —— 那是唯一还能把
 * 播放打挂的路径（media3 的 `TeeAudioProcessor.queueInput` / `DefaultAudioSink.handleBuffer`
 * 对 sink 回调都不兜异常，字节码已核实）。本版把「RMS + 回调」收进**同一个**隔离边界，
 * 并做到三件事：
 *
 *  1. **绝不向上抛**：`catch (Throwable)` 吞掉，失败只让这一根柱消失（不补 0：补 0 会在
 *     画面上画出一个假的静音凹陷，丢一根柱只是少一个采样点）；
 *  2. **开关前置**：`WaveformStore.enabled` 是进程内 `@Volatile` 镜像（音频线程**不读盘**），
 *     判断挪到 RMS **之前** —— 用户关掉可视化之后，音频线程只剩一次 volatile 读，
 *     不再为一份没人看的 RMS 遍历整块 PCM（探针 §5「关掉开关 = 零开销不成立」）；
 *  3. **每缓冲零堆分配**：`PcmRms.of` 改成绝对下标读字节，不再 `duplicate()` +
 *     `asShortBuffer()`（见 [PcmRms] 的 KDoc）。顺带连上游游标都不用保护了 ——
 *     绝对读不改 position/limit/order，比 duplicate 更安全。
 *
 * 失败次数记在 [droppedBarCount]（一个 volatile long 自增，**不拼字符串、不打日志**：
 * 音频线程上任何分配都是爆音）。它只用于诊断与单测，不参与任何逻辑。
 *
 * ## v3.0.0：旁路数据从「一个 RMS」扩成「一组音频特征」
 *
 * 任务书 §4.1 要求瞬态 / 三频段能量 / 频谱质心近似。**这一版仍然不是 FFT**
 * （决定性依据见 [AudioFeatureExtractor] 的档头：没有分帧、没有窗函数、没有频域变换、
 * 没有 PCM 环；只有两个一阶低通，与 v2.9.0 已有的低频通道**共用同一次逐样本遍历**）。
 *
 * 三条结构上的契约（顺序本身就是契约）：
 *
 *  1. **一次遍历算全部**：`AudioFeatureExtractor.process` 里同时产出
 *     RMS / 低频 / 中频 / 高频 / 质心近似 / 瞬态，**不是**「每加一个特征多扫一遍样本」；
 *  2. **两个开关都关 = 零开销**：可视化开关与「界面动效需要音频特征」开关**都**为 false 时，
 *     本回调只剩两次 volatile 读，连一次样本遍历都不跑（v2.8.0 的纪律保住了）。
 *     v2.9.0 只有可视化一个开关，于是「关掉可视化」会把界面动效的节拍数据一起掐掉 ——
 *     那是缺陷不是特性，v3.0.0 把两个需求分开；
 *  3. **失败即降级，不即失效**：`process` 返回 false（采样率未知 / 编码不支持）或它自己抛异常时，
 *     回落到 `PcmRms.analyze`（RMS-only，v2.9.0 的行为），界面动效那一侧由
 *     `MotionEnvelope` 的**内置判据**兜底 —— 用户看到的是「动效退化成 v2.9.0」，
 *     而不是「动效全没了」或「播放断了」。
 */

package com.takahashirinta.ncrust.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.takahashirinta.ncrust.ui.player.WaveformStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 声道数无关的波形旁路 sink。
 *
 * 实现 [TeeAudioProcessor.AudioBufferSink]（**不是** AudioProcessor）：它只是 tee 的下游消费者，
 * 读一份 PCM 副本算 RMS，然后把结果丢给 [WaveformStore.onBar] —— 不返回任何数据给播放链，
 * 因此不可能改变输出格式，也不可能参与声道矩阵。
 *
 * ## 三个构造参数是**单测注入点**，不是配置项
 *
 * 生产路径（`VisualizerRenderersFactory`）永远用默认值；默认值是三个**无捕获**的函数引用，
 * 在对象方法引用的情况下 Kotlin 编译成单例，连构造时都不分配。留着这三条缝的理由是
 * 「异常隔离」与「开关前置」这两条契约**只能在单测里被证伪**：
 * 真机上没有办法让 `WaveformStore.onBar` 抛异常，也没有办法观测 RMS 有没有跑。
 *
 * @param enabled 开关的进程内镜像（**不读盘**：音频线程上禁止 IO）。默认读 [WaveformStore.enabled]。
 * @param onBar 柱值消费端。默认 [WaveformStore.onBar]。
 * @param analyze PCM 分析（全带 RMS + 低频能量，打包成一个 `Long`，见 [PcmRms.pack]）。
 *   默认走内置的一次遍历实现（[PcmRms.analyze]）。传 `null` 之外的值可以替换它 ——
 *   单测用它来**证伪异常隔离**（让分析抛异常，断言播放链不受影响）。
 */
@UnstableApi
class TransparentWaveformSink(
    private val enabled: () -> Boolean = { WaveformStore.enabled },
    private val featuresEnabled: () -> Boolean = { WaveformStore.motionFeaturesEnabled },
    private val onBar: (Double, Double, Double) -> Unit = WaveformStore::onBar,
    private val onFeatures: (Double, Double, Double, Double, Double, Boolean) -> Unit =
        WaveformStore::onFeatures,
    private val onTransient: (Int, Float) -> Unit = WaveformStore::onTransient,
    private val extractor: AudioFeatureExtractor = AudioFeatureExtractor(),
    private val fallbackAnalyze: (ByteBuffer, Int, Int, Int, Array<DoubleArray>) -> Long = PcmRms::analyze,
) : TeeAudioProcessor.AudioBufferSink {

    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0

    /** v2.9.0：低通需要的采样率（来自 [flush]）。0 = 还不知道 ⇒ 低频通道退化成全带。 */
    private var sampleRateHz = 0

    /**
     * v2.9.0：一阶低通的跨缓冲状态（**降级路径**用；正常路径的状态在 [extractor] 里）。
     *
     * v3.0.0：形状从 `DoubleArray(1)` 改成 `Array(8) { DoubleArray(2) }` —— 与
     * [AudioFeatureExtractor] 同一处修正（交错 PCM 必须**每声道**一个状态，
     * 否则有效截止频率会乘以声道数）。构造时分配一次，之后零分配。
     */
    private val bassFilterState = Array(PcmRms.MAX_CHANNELS) { DoubleArray(2) }

    /**
     * 被隔离边界吞掉的柱数（诊断 + 单测用）。
     *
     * volatile：音频线程自增、UI 线程/单测读。**故意不写日志** —— 音频线程上拼一次字符串
     * 就是一次堆分配。计数本身不参与任何决策。
     */
    @Volatile
    var droppedBarCount: Long = 0L
        private set

    /** 只更新格式，**不做任何可能抛异常的推导**（旧实现在这里建矩阵，就是崩在这）。 */
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.channelCount = if (channelCount > 0) channelCount else 0
        this.encoding = encoding
        this.bytesPerSample = bytesPerSampleOf(encoding)
        // 格式变化（换歌 / 换设备）⇒ 采样率可能变、滤波器状态也不再对应，一并重置。
        this.sampleRateHz = if (sampleRateHz > 0) sampleRateHz else 0
        for (channel in bassFilterState.indices) {
            bassFilterState[channel][0] = 0.0
            bassFilterState[channel][1] = 0.0
        }
        // v3.0.0：特征提取器的系数跟着采样率走；采样率未知时它自己置 available=false，
        // 调用方据此走高一段的降级路径（RMS-only）。
        extractor.configure(this.sampleRateHz, this.channelCount)
    }

    /**
     * **音频线程**。零分配、零锁、零异常（任务书对可视化的性能契约）。
     *
     * 四件事按顺序发生，顺序本身就是契约：
     *  1. 未知编码（24bit / 32bit 整数等）与声道数 0 直接返回：宁可没有可视化，也不能让播放失败；
     *  2. **开关前置**：可视化与「界面动效要特征」**两个**开关都关 ⇒ 连一次样本遍历都不跑
     *     （v2.8.0 的「关掉开关 = 零开销」原样保留，只是从「一个开关」变成「两个都关」）；
     *  3. **一次遍历算全部特征**（[AudioFeatureExtractor.process]），失败回落 RMS-only；
     *  4. 「计算 + 三个回调」在**同一个** try 里 ⇒ 任何 Throwable 都在这里终结，绝不向上抛
     *     （media3 的 tee 与 AudioSink 都不兜异常，抛出去就是 v2.2.1 的级联形状）。
     *
     * 失败时的行为是**丢弃这一根柱**，不是补一个 0：补 0 会在画面上画出一个假的静音凹陷。
     */
    override fun handleBuffer(buffer: ByteBuffer) {
        val bps = bytesPerSample
        if (bps == 0 || channelCount == 0) return
        val wantBars = enabled()
        val wantFeatures = featuresEnabled()
        if (!wantBars && !wantFeatures) return
        try {
            // v3.0.0：**一次遍历同时算全带 / 低频 / 中频 / 高频 / 质心 / 瞬态**（不是多遍）。
            // 多出来的只有每样本两次乘加 —— 与"每加一个特征再走一遍全部样本"差一个数量级。
            if (extractor.process(buffer, encoding)) {
                if (wantBars) {
                    onBar(
                        extractor.rms.toDouble(),
                        extractor.low.toDouble(),
                        AudioFeatureExtractor.bandMix(extractor.low, extractor.mid, extractor.high)
                            .toDouble(),
                    )
                }
                if (wantFeatures) {
                    onFeatures(
                        extractor.rms.toDouble(),
                        extractor.low.toDouble(),
                        extractor.mid.toDouble(),
                        extractor.high.toDouble(),
                        extractor.spectralCentroid.toDouble(),
                        true,
                    )
                    // v3.0.0：一个缓冲里**可能发生多次击打**（真机实测缓冲粒度 100ms，
                    // 见 `AudioFeatureExtractor.transientCount` 的 KDoc）—— 报个数，不是报一个 bool。
                    if (extractor.transientCount > 0) {
                        onTransient(extractor.transientCount, extractor.transientStrength)
                    }
                }
            } else {
                // 降级路径（v2.9.0 的行为）：只有全带 RMS + 低频，没有任何新特征。
                val packed = fallbackAnalyze(
                    buffer,
                    encoding,
                    sampleRateHz,
                    channelCount,
                    bassFilterState,
                )
                val full = PcmRms.fullOf(packed).toDouble()
                val bass = PcmRms.bassOf(packed).toDouble()
                if (wantBars) onBar(full, bass, 0.0)
                if (wantFeatures) {
                    // available = false：消费方据此回落到**自己的**内置判据（v2.9.0 的包络），
                    // 而不是把「没有中高频」误当成「中高频能量为零」。
                    onFeatures(full, bass, 0.0, 0.0, 0.0, false)
                }
            }
        } catch (t: Throwable) {
            // 有意吞掉：这是隔离边界本身。计数不分配、不上锁。
            droppedBarCount += 1L
        }
    }

    private fun bytesPerSampleOf(encoding: Int): Int = PcmRms.bytesPerSample(encoding)
}

/**
 * 交错 PCM 的 RMS。**纯函数、纯数学、无 Android 依赖**（所以能被 JVM 单测直接断言数值）。
 *
 * 声道数无关：把所有声道一起算一个 RMS。旧实现（media3 的 WaveformAudioBufferSink）
 * 走的是「先混音到 1 声道再算」，而它的混音矩阵对 6→1 直接抛异常 —— 我们不需要那个矩阵，
 * 只要一个数值给柱状图用。
 *
 * ## v2.8.0 · P1-A：零堆分配（探针 §6.1「每 handleBuffer 2 次堆分配」）
 *
 * 旧写法是 `view.duplicate().order(nativeOrder()).asShortBuffer()`：`duplicate()` 一个对象、
 * `asShortBuffer()` 又一个对象，**每个音频缓冲 2 次堆分配**，与 KDoc 里「零分配」的契约不符
 * （音频线程上任何一次 Young GC 都是爆音风险）。现在改成**绝对下标读字节**：
 *
 *  - **零分配**：不建 view、不建包装对象；
 *  - **零副作用**：绝对读不改 position / limit / mark / order，连"用 duplicate 保护上游游标"
 *    这件事都不需要了 —— 比旧写法更安全；
 *  - **逐字节序显式**：旧写法用 `ByteOrder.nativeOrder()` 解释样本；这里同样按 nativeOrder
 *    自己拼 16/32 位整数，**不依赖 buffer 自己声明的 order**（tee 交过来的 buffer 未必声明过
 *    native order，而 `getShort(int)`/`getInt(int)` 是跟着 `order()` 走的 —— 直接用绝对
 *    getShort 会在大端声明 + 小端数据的组合上静默算出错的数）。
 *
 * 代价是每个样本 2 次（16bit）或 4 次（float）`get(index)` 而不是 1 次批量读。
 * 这是刻意的取舍：**每缓冲一次堆分配 > 每样本一次额外访存**（前者是不可控的 GC 停顿，
 * 后者是恒定的纳秒级开销）。数值等价性由 `TransparentWaveformSinkTest` 用
 * 「旧实现参考版」逐样本对齐钉住。
 */
@UnstableApi
internal object PcmRms {
    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    /**
     * v2.9.0：**低频（鼓 / 贝斯）能量**的一阶（单极点）低通。
     *
     * ## 这不是 FFT，也不是频谱（写在最前面，避免下一个人误读）
     *
     * 整个计算只有一条递推：`state += a * (x - state)`，每样本**一次乘加**，
     * 输出是**一个标量**（低频带的 RMS）。没有分帧、没有窗函数、没有频域变换、
     * 也不画任何"按频段分色"的东西。它唯一的用途是把节拍判据从
     * 「整段响度」换成「低频能量」—— 底鼓与贝斯落在这一带，人声与旋律重音不在，
     * 于是「鼓声触发冲击波」成为可能，而「一句高音也炸一圈涟漪」消失。
     *
     * 这一点很重要：v2.8.0 明确写了「真 FFT / 多频段分色不做」（数据通路只有单标量 RMS，
     * 真频段要么在音频线程上倍增计算、要么新增 PCM 环 + UI 侧 FFT）。本函数**两条都不越**：
     * 它在**已有的那一次**逐样本遍历里多算一次乘加，不新增遍历、不新增缓冲、
     * 也不产出任何"看起来像频谱"的视觉。
     *
     * ## 参数
     *
     * @param sampleRateHz 采样率（来自 `AudioBufferSink.flush`）。≤0 时退化成"不做低通"
     *   （低频值 = 全带值），宁可退化也不要拿一个错的截止频率去滤波。
     * @param channelCount 声道数（来自 `AudioBufferSink.flush`）。交错 PCM 的相邻样本属于
     *   不同声道，所以**每个声道各有一个滤波状态** —— 共用一个状态会让有效截止频率
     *   乘以声道数（立体声下 150Hz 变成 300Hz，6 声道变成 900Hz）。上限 [MAX_CHANNELS]。
     * @param state **调用方持有**的二维 `Array<DoubleArray>`（滤波状态：`[声道][0]` 低通）。
     *   用数组而不是返回值，是为了让这个函数在音频线程上**零分配**：状态跨缓冲必须连续，
     *   而"把状态包进返回值"就得每缓冲建一个对象。
     */
    fun analyze(
        view: ByteBuffer,
        encoding: Int,
        sampleRateHz: Int,
        channelCount: Int,
        state: Array<DoubleArray>,
    ): Long {
        val bps = bytesPerSample(encoding)
        if (bps == 0) return pack(0f, 0f)
        if (state.isEmpty()) {
            // 调用方给了长度为 0 的状态数组：不做滤波，低频退化成全带
            // （与「采样率未知」同一口径）。**绝不抛异常** —— 音频线程上抛一次就是 v2.2.1。
            val full = of(view, encoding)
            return pack(full.toFloat(), full.toFloat())
        }
        val base = view.position()
        val count = view.remaining() / bps
        if (count <= 0) return pack(0f, 0f)
        val littleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        // 一阶低通系数：截止 [BASS_CUTOFF_HZ]，a = 1 - exp(-2π fc / fs)。
        val a = lowPassCoefficient(sampleRateHz)
        val channels = channelCount.coerceIn(1, minOf(MAX_CHANNELS, state.size))
        var sumFull = 0.0
        var sumBass = 0.0
        var index = base
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                var i = 0
                var channel = 0
                while (i < count) {
                    val lo = view.get(index).toInt() and 0xFF
                    val hi = view.get(index + 1).toInt()
                    val raw = if (littleEndian) (hi shl 8) or lo else (lo shl 8) or (hi and 0xFF)
                    val v = raw.toShort() / 32768.0
                    val slot = state[channel]
                    var lp = if (slot.isNotEmpty() && slot[0].isFinite()) slot[0] else 0.0
                    sumFull += v * v
                    lp += a * (v - lp)
                    sumBass += lp * lp
                    slot[0] = if (lp.isFinite()) lp else 0.0
                    index += 2
                    i++
                    channel++
                    if (channel >= channels) channel = 0
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                var i = 0
                var channel = 0
                while (i < count) {
                    val b0 = view.get(index).toInt() and 0xFF
                    val b1 = view.get(index + 1).toInt() and 0xFF
                    val b2 = view.get(index + 2).toInt() and 0xFF
                    val b3 = view.get(index + 3).toInt() and 0xFF
                    val bits = if (littleEndian) {
                        (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                    } else {
                        (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
                    }
                    val v = Float.fromBits(bits).toDouble()
                    val slot = state[channel]
                    var lp = if (slot.isNotEmpty() && slot[0].isFinite()) slot[0] else 0.0
                    sumFull += v * v
                    lp += a * (v - lp)
                    sumBass += lp * lp
                    slot[0] = if (lp.isFinite()) lp else 0.0
                    index += 4
                    i++
                    channel++
                    if (channel >= channels) channel = 0
                }
            }
            else -> return pack(0f, 0f)
        }
        val full = sqrt(sumFull / count)
        val bass = sqrt(sumBass / count)
        return pack(
            if (bass.isFinite()) bass.toFloat() else 0f,
            if (full.isFinite()) full.toFloat() else 0f,
        )
    }

    /**
     * 滤波状态的声道上限。与 `AudioFeatureExtractor.MAX_CHANNELS` 同值（8）：
     * 本应用实测的最高声道数是 QQ 臻品档的 6 声道 FLAC，8 留了两个余量槽位。
     */
    const val MAX_CHANNELS = 8

    /**
     * 低通系数。截止 [BASS_CUTOFF_HZ]（底鼓基频 50–100Hz、贝斯 40–200Hz 的公共带）。
     *
     * 采样率 ≤0（`flush` 还没被调用过）时返回 **1.0** —— 系数 1 时 `lp = x`，
     * 即"低频值 = 全带值"。这是刻意的退化：宁可让低频通道等价于总响度
     * （行为与 v2.8.0 的节拍判据一致），也不要拿一个猜出来的采样率去滤波。
     */
    fun lowPassCoefficient(sampleRateHz: Int): Double {
        if (sampleRateHz <= 0) return 1.0
        val a = 1.0 - exp(-2.0 * PI * BASS_CUTOFF_HZ / sampleRateHz)
        return a.coerceIn(0.0, 1.0)
    }

    /** 把 (低频, 全带) 两个 Float 打进一个 Long：音频线程上零分配地返回两个标量。 */
    fun pack(bass: Float, full: Float): Long =
        (bass.toRawBits().toLong() shl 32) or (full.toRawBits().toLong() and 0xFFFF_FFFFL)

    fun bassOf(packed: Long): Float = Float.fromBits((packed ushr 32).toInt())

    fun fullOf(packed: Long): Float = Float.fromBits(packed.toInt())

    /**
     * 低频截止（Hz）。**150** 的依据：底鼓的基频能量集中在 50–100Hz、贝斯 40–200Hz，
     * 而人声基频 85–1100Hz、旋律重音更高 —— 150Hz 落在两者之间，
     * 既保住了鼓组的下盘，又把大部分人声排除在外。
     *
     * 这不是"分频段可视化"：它只有一个通道，没有第二、第三个频段可比。
     */
    const val BASS_CUTOFF_HZ = 150.0

    fun of(view: ByteBuffer, encoding: Int): Double {
        val bps = bytesPerSample(encoding)
        if (bps == 0) return 0.0
        val base = view.position()
        val count = view.remaining() / bps
        if (count <= 0) return 0.0
        val littleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        var sum = 0.0
        var index = base
        // 用 while 而不是 repeat{}：这里要保证「零分配」，不依赖某个高阶函数的内联行为。
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                var i = 0
                while (i < count) {
                    val lo = view.get(index).toInt() and 0xFF
                    val hi = view.get(index + 1).toInt()
                    val raw = if (littleEndian) (hi shl 8) or lo else (lo shl 8) or (hi and 0xFF)
                    val v = raw.toShort() / 32768.0
                    sum += v * v
                    index += 2
                    i++
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                var i = 0
                while (i < count) {
                    val b0 = view.get(index).toInt() and 0xFF
                    val b1 = view.get(index + 1).toInt() and 0xFF
                    val b2 = view.get(index + 2).toInt() and 0xFF
                    val b3 = view.get(index + 3).toInt() and 0xFF
                    val bits = if (littleEndian) {
                        (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                    } else {
                        (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
                    }
                    val v = Float.fromBits(bits).toDouble()
                    sum += v * v
                    index += 4
                    i++
                }
            }
            else -> return 0.0
        }
        return sqrt(sum / count)
    }
}
