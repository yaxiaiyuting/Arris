/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.0.0：**无 FFT 的音频特征提取层**（瞬态 / 三频段能量 / 频谱质心近似）。
 *
 * ## 这不是 FFT 的翻案 —— 一句话讲清它做了什么、没做什么
 *
 * v2.8.0 与 v2.9.0 两次写下「真 FFT / 多频段频谱不做」，理由是数据通路只有
 * 「整块 PCM → 单标量 RMS」，真频段要么把逐样本计算搬上音频线程（与 v2.2.1 的 P0 同级风险），
 * 要么新增 PCM 环 + UI 侧 FFT（等于重做数据层）。
 *
 * 本类**两条都不越**：
 *
 *  - 瞬态判定每 10ms 结算一次能量，不复制 PCM，不做窗函数或频域变换；
 *  - 只有 **两个一阶（单极点）低通**（截止 150 Hz 与 2 kHz），每样本各一次乘加；
 *    它们与 v2.9.0 已有的那条低频通道**共用同一次逐样本遍历**（不是第二遍扫描）；
 *  - 三频段由「低通之差」得到（[`LP(2k) − LP(150)`] = 中频带，[`x − LP(2k)`] = 高频带），
 *    这是最朴素的一阶滤波器组，**不是滤波器组频谱**：一阶只有 −6 dB/oct，
 *    频带之间泄漏很大，**底鼓与贝斯仍然不可区分**（v2.9.0 的诚实边界一条都没变）。
 *
 * 换句话说：它给出的是**三个带宽很宽的包络**，而不是频谱。任何「按频段分色画一排柱子」
 * 的视觉都不该由它背书 —— 画面上的柱子仍然是**时间轴**的（见 `WaveformRing` 的 `mixRing`）。
 *
 * ## 单次遍历算了什么（逐样本）
 *
 * | 量 | 递推 / 累加 | 每样本代价 |
 * |---|---|---|
 * | 全带 RMS | `sumFull += v·v` | 1 乘 1 加 |
 * | 低频带 `low` | `sLow += aLow·(v − sLow)`，累加 `sLow²` | 2 乘 1 加 1 减 |
 * | 中频带 `mid` | `sMid += aMid·(v − sMid)`，累加 `(sMid − sLow)²` | 2 乘 1 加 2 减 |
 * | 高频带 `high` | 累加 `(v − sMid)²` | 1 乘 2 减 |
 *
 * 合计约 **6 乘 10 加减**／样本，对比 v2.9.0 的 3 乘 2 加减。
 * ⚠️ 早先的注释写「每样本多三次乘加」是**低估**：真实的增量是 **+9 flop/样本**
 * （44.1kHz 立体声 ≈ 1.5 Mflop/s；6 声道 ≈ 4.5 Mflop/s）。风险不在吞吐
 * （4096 帧立体声的纯算术只占 93ms 缓冲预算的百分之几），而在**分配、阻塞与异常** ——
 * 那三样才是本类与调用方逐条守住的。
 *
 * ## v3.0.0 · 交错 PCM 的**每声道**滤波状态（对 v2.9.0 的一个真缺陷的修正）
 *
 * 一阶低通的状态是「按**样本**递推」的，而交错 PCM 的相邻样本属于**不同声道**。
 * v2.9.0 只有一个状态标量，于是每个声道的状态每帧被推进 `channelCount` 次 ——
 * **有效截止频率被乘以声道数**：标称 150 Hz 在单声道是 150 Hz、立体声是 300 Hz、
 * 6 声道（QQ 臻品档的实测布局）是 900 Hz。它不崩、不报错，只是「低频带」悄悄变成了
 * 「中低频带」—— v2.9.0 KDoc 里那句「人声不在这一带」在立体声下并不成立。
 *
 * 修法是**每个声道一个状态**（[filterState] 的第二维）。每样本的成本**不变**
 * （仍然是每个样本一次乘加），只是状态槽位从 1 个变成 `min(channelCount, 8)` 个。
 * 回归由 `AudioFeatureExtractorTest.stereoLowPassCutoffIsNotMultipliedByChannelCount` 钉住：
 * 立体声 150 Hz 正弦在 `low` 上的响应必须与单声道一致；修复前它会明显偏大。
 *
 * ## 零分配 / 零 GC（铁律 27）
 *
 * - 全部状态是**构造时分配一次**的 `DoubleArray`（滤波状态）+ 标量字段；
 * - [process] 里没有装箱、没有 lambda、没有字符串、没有集合、没有 IO、没有锁；
 * - 输出是**对象自身的字段**（不是返回值里的新对象）—— 单线程内读写，
 *   跨线程发布由 `TransparentWaveformSink` 用「预分配标量 + volatile 写」完成。
 *
 * ## 异常隔离（铁律 4）
 *
 * 本类**不自己吞异常**：它是一段纯算术，抛异常的唯一现实来源是上游给了畸形的 buffer。
 * 调用方（`TransparentWaveformSink.handleBuffer`）把「本类调用 + 三个回调」
 * 收在**同一个** try 里，失败只丢这一根柱（v2.8.0 的隔离边界原样保留）。
 * 另有一条**降级路径**：[process] 返回 `false` 时调用方回落到
 * `PcmRms.analyze`（RMS-only，v2.9.0 的行为）。
 */

package com.takahashirinta.ncrust.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 无 FFT 的音频特征提取器（**单线程使用**：谁调用 [process] 谁读结果）。
 *
 * 典型用法（`TransparentWaveformSink`）：
 * ```
 * flush(sampleRate, channels, encoding) → extractor.configure(sampleRate, channels)
 * handleBuffer(buffer)                  → if (extractor.process(buffer, encoding)) { 读字段 }
 * ```
 */
@UnstableApi
open class AudioFeatureExtractor {

    // ------------------------------------------------------------------
    // 输出（**同一线程内**读写；跨线程发布是调用方的事）
    // ------------------------------------------------------------------

    /** 全带 RMS（0..1）。语义与 v2.9.0 的 `PcmRms.fullOf` 逐值一致。 */
    var rms: Float = 0f
        private set

    /** 低频带（< [LOW_CUTOFF_HZ]）能量。**与 v2.9.0 的低频通道逐值一致**（同一条递推）。 */
    var low: Float = 0f
        private set

    /** 中频带（[LOW_CUTOFF_HZ] .. [MID_HIGH_CUTOFF_HZ]）能量。人声 / 吉他基频主要落在这一带。 */
    var mid: Float = 0f
        private set

    /** 高频带（> [MID_HIGH_CUTOFF_HZ]）能量。弦乐泛音 / 镲片 / 齿音主要落在这一带。 */
    var high: Float = 0f
        private set

    /**
     * 频谱质心的**三频带近似**，归一化到 `0..1`（0 = 全在低频，1 = 全在高频）。
     *
     * 真频谱质心 = 幅度加权的平均频率，需要频谱。这里用三个频带的**代表频率**
     * （[CENTROID_F_LOW_HZ] / [CENTROID_F_MID_HZ] / [CENTROID_F_HIGH_HZ]）做幅度加权平均，
     * 再按 `ln` 映射到 `[CENTROID_MIN_HZ, CENTROID_MAX_HZ]`。
     *
     * **诚实边界**：分辨率只有 3 个频带，且三个代表频率是**约定的代表值、不是测量值** ——
     * 它足以支撑「亮一点 / 暗一点」的视觉调制，**不足以**支撑任何数值结论
     * （不要拿它去报「这首歌的质心是 1.2 kHz」）。能量全零时输出 0。
     */
    var spectralCentroid: Float = 0f
        private set

    /**
     * 本次 [process] 判定出的**瞬态个数**（0..[MAX_TRANSIENTS_PER_BUFFER]）。
     *
     * ## 为什么是"个数"而不是"一个 bool"（真机实测逼出来的）
     *
     * 仪器探针实测（S6 / Android 7.0）：`TeeAudioProcessor` 一次回调 **4410 帧 = 100ms**
     * （`docs/verification/v3.0.0/probe/EVIDENCE.md` 的 `PROBE-AUDIO-TAP`）。
     * 一个 100ms 的缓冲里可能发生**不止一次**击打 —— 只报一个 bool 会把它们合并成一次
     * （表现是「快鼓点只跳一半」）。
     *
     * 所以时间基准不是"一个缓冲"，而是缓冲内部的**子帧**（[SUB_FRAME_MS] = 10ms）：
     * 逐样本累加，每 10ms 结算一次判据。窗口跨缓冲连续，同一份 PCM 不随回调边界改变判定。
     */
    var transientCount: Int = 0
        private set

    /** 本次 [process] 里最强的那次瞬态的强度（0..1，已 clamp）。非瞬态时为 0。 */
    var transientStrength: Float = 0f
        private set

    /** 本次 [process] 是否判定出至少一个瞬态（[transientCount] > 0 的便捷读法）。 */
    val transient: Boolean get() = transientCount > 0

    // Retain the short attack for the display while keeping RMS and centroid as buffer averages.
    val barRms: Float get() = withAttack(rms, attackFull)
    val barLow: Float get() = withAttack(low, attackLow)
    val barMid: Float get() = withAttack(mid, attackMid)
    val barHigh: Float get() = withAttack(high, attackHigh)

    private fun withAttack(average: Float, attack: Float): Float =
        average + (attack - average).coerceAtLeast(0f) * 0.72f

    /**
     * 本次缓冲覆盖的**时长**（毫秒）。
     *
     * 由「帧数 ÷ 采样率」算出来 —— 这是本类与「每缓冲一次判定」能成立的全部依据：
     * 缓冲粒度是运行时行为（`TeeAudioProcessor.queueInput` 原样透传上游的块，
     * 代码里没有常量能回答它多大），所以时间基准**必须**从样本数反算，
     * 不能假设每缓冲固定多少毫秒。
     */
    var frameMs: Float = 0f
        private set

    /** 本类当前是否可用（采样率已知、编码受支持）。false 时调用方应降级到 RMS-only。 */
    var available: Boolean = false
        private set

    // ------------------------------------------------------------------
    // 内部状态（构造时分配一次，之后零分配）
    // ------------------------------------------------------------------

    /**
     * 滤波状态：`[声道][0]` = 150 Hz 低通、`[声道][1]` = 2 kHz 低通。跨缓冲连续。
     *
     * **每声道一份**（上限 [MAX_CHANNELS]）：交错 PCM 的相邻样本属于不同声道，
     * 共用一个状态会让有效截止频率乘以声道数（见文件头 KDoc 的 v3.0.0 一节）。
     * 构造时分配一次，之后**零分配**（声道数变小只是少用几行，不重新分配）。
     */
    private val filterState = Array(MAX_CHANNELS) { DoubleArray(2) }

    /** 低频、全频和中高频包络的慢速基线。 */
    private val baselineState = DoubleArray(3)
    private var previousPresence = 0.0
    private var peakPresence = 0.0

    /** 冷却剩余（毫秒）。 */
    private var cooldownMs: Double = 0.0

    /**
     * **衰减的峰值记忆**（低频带）。判定"这一下是不是新的一击"的参照。
     *
     * ## 为什么不是"重新武装"或"包络必须在上升"
     *
     * 一次底鼓不是一根脉冲，而是一段**带衰减的包络**（60ms 衰减常数意味着 240ms 内都还有能量）。
     * 只靠冷却窗口的话，同一个鼓点会在冷却刚过的第一帧**再触发一次** ——
     * 慢速基线还没追上那一下的峰值，`值 − 基线` 依然很大。实测「500ms 一次鼓点、8 秒」
     * 的合成轨道会被判出 21~22 次而不是 16 次，多出来的全是同一个鼓点的尾巴。
     *
     * 「等包络落回谷底再重新武装」与「要求包络在上升」两条都试过，都不够稳：
     * 前者的参照值是**固定比例**，遇到衰减慢的曲子会一直在"还没落回"的状态；
     * 后者会被**每缓冲 RMS 的相位起伏**骗到（一个 60Hz 的正弦在 23ms 的窗口里只有 1.4 个周期，
     * 逐缓冲的 RMS 本身就有 ±20% 的抖动）。
     *
     * 现在这条判据是「**当前能量必须显著超过近期峰值的衰减记忆**」：
     * `peak = max(值, peak · e^(−dt/τ))`，判定要求 `值 ≥ peak · [ONSET_PEAK_RATIO]`。
     * 它同时对两件事免疫：衰减尾巴（值低于记忆）与持续的大音量（值与记忆同步）。
     */
    private var peakLow: Double = 0.0

    /** 全带响度的峰值记忆（与 [peakLow] 同构）—— 全带判据同样需要它，见 [updateTransient]。 */
    private var peakFull: Double = 0.0

    // Energy for one continuous analysis window, including samples from preceding buffers.
    private var sumLowSub = 0.0
    private var sumMidSub = 0.0
    private var sumHighSub = 0.0
    private var sumFullSub = 0.0
    private var subSamples = 0
    private var subFrameSamples = 0
    private var subFrameMs = 0.0
    private var baselineK = 0.0
    private var subPeakDecay = 0.0

    private var attackLow = 0f
    private var attackMid = 0f
    private var attackHigh = 0f
    private var attackFull = 0f

    private var sampleRateHz: Int = 0
    private var channelCount: Int = 0

    /** 低通系数（configure 时算一次，之后每样本只用一次乘加）。 */
    private var aLow: Double = 1.0
    private var aMid: Double = 1.0

    /**
     * 格式变化时调用（media3 的 `AudioBufferSink.flush`）。**不做任何可能抛异常的推导。**
     *
     * 采样率 ≤0 或声道数 ≤0 ⇒ [available] 置 false、调用方降级到 RMS-only。
     * 这是刻意的：宁可退化到 v2.9.0 的行为，也不要拿一个猜出来的采样率去滤波
     * （截止频率错了，低频带会变成别的东西，而画面上看不出来）。
     */
    fun configure(sampleRateHz: Int, channelCount: Int) {
        this.sampleRateHz = if (sampleRateHz > 0) sampleRateHz else 0
        this.channelCount = if (channelCount > 0) channelCount else 0
        reset()
        available = this.sampleRateHz > 0 && this.channelCount > 0
        if (!available) return
        aLow = lowPassCoefficient(LOW_CUTOFF_HZ, this.sampleRateHz)
        aMid = lowPassCoefficient(MID_HIGH_CUTOFF_HZ, this.sampleRateHz)
        val frames = (this.sampleRateHz * SUB_FRAME_MS / 1000.0).toInt().coerceAtLeast(1)
        subFrameSamples = frames * this.channelCount
        subFrameMs = frames * 1000.0 / this.sampleRateHz
        baselineK = baselineCoefficient(subFrameMs)
        subPeakDecay = peakMemoryDecay(subFrameMs)
    }

    /**
     * 换歌 / 换设备：清状态与**输出**，不动系数（系数由 [configure] 管）。
     *
     * 输出也要清：否则新歌开头那几帧，消费方会读到上一首的频段值
     * （`WaveformStore.featuresAvailable()` 仍为 true ⇒ 它会当真数据用）。
     */
    fun reset() {
        for (channel in filterState.indices) {
            filterState[channel][0] = 0.0
            filterState[channel][1] = 0.0
        }
        baselineState.fill(0.0)
        previousPresence = 0.0
        peakPresence = 0.0
        cooldownMs = 0.0
        rms = 0f
        low = 0f
        mid = 0f
        high = 0f
        spectralCentroid = 0f
        transientCount = 0
        transientStrength = 0f
        frameMs = 0f
        peakLow = 0.0
        peakFull = 0.0
        clearSubFrame()
        clearAttacks()
    }

    private fun clearAttacks() {
        attackLow = 0f
        attackMid = 0f
        attackHigh = 0f
        attackFull = 0f
    }

    private fun clearSubFrame() {
        sumLowSub = 0.0
        sumMidSub = 0.0
        sumHighSub = 0.0
        sumFullSub = 0.0
        subSamples = 0
    }

    /**
     * **音频线程**：一次遍历算出全部特征。零分配、零锁、零 IO。
     *
     * @return `true` = 特征已更新（可以读字段）；`false` = 本次无法计算
     *   （编码不支持 / 缓冲为空 / 采样率未知），调用方应降级到 RMS-only。
     *
     * `open`：唯一目的是让单测能**证伪异常隔离**（让这里抛异常，断言播放链不受影响）——
     * 真机上没有办法让这一层自己抛。生产路径没有子类，虚调用点只有这一个。
     */
    open fun process(view: ByteBuffer, encoding: Int): Boolean {
        transientCount = 0
        transientStrength = 0f
        clearAttacks()
        if (!available) return false
        val bps = bytesPerSample(encoding)
        if (bps == 0) return false
        val base = view.position()
        val samples = view.remaining() / bps
        if (samples <= 0) return false

        val littleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
        val kl = aLow
        val km = aMid
        // 声道数决定「交错样本里的第几个」——状态按声道分开取（见 filterState 的 KDoc）。
        val channels = channelCount.coerceIn(1, MAX_CHANNELS)
        var sumFull = 0.0
        var sumLow = 0.0
        var sumMid = 0.0
        var sumHigh = 0.0
        var index = base
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                var i = 0
                var channel = 0
                while (i < samples) {
                    val lo = view.get(index).toInt() and 0xFF
                    val hi = view.get(index + 1).toInt()
                    val raw = if (littleEndian) (hi shl 8) or lo else (lo shl 8) or (hi and 0xFF)
                    val v = raw.toShort() / 32768.0
                    val state = filterState[channel]
                    var sLow = state[0]
                    var sMid = state[1]
                    sLow += kl * (v - sLow)
                    sMid += km * (v - sMid)
                    val m = sMid - sLow
                    val h = v - sMid
                    val fullPower = v * v
                    val lowPower = sLow * sLow
                    val midPower = m * m
                    val highPower = h * h
                    sumFull += fullPower
                    sumLow += lowPower
                    sumMid += midPower
                    sumHigh += highPower
                    sumFullSub += fullPower
                    sumLowSub += lowPower
                    sumMidSub += midPower
                    sumHighSub += highPower
                    state[0] = if (sLow.isFinite()) sLow else 0.0
                    state[1] = if (sMid.isFinite()) sMid else 0.0
                    index += 2
                    i++
                    channel++
                    if (channel >= channels) channel = 0
                    subSamples++
                    if (subSamples >= subFrameSamples) finalizeSubFrame()
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                var i = 0
                var channel = 0
                while (i < samples) {
                    val b0 = view.get(index).toInt() and 0xFF
                    val b1 = view.get(index + 1).toInt() and 0xFF
                    val b2 = view.get(index + 2).toInt() and 0xFF
                    val b3 = view.get(index + 3).toInt() and 0xFF
                    val bits = if (littleEndian) {
                        (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                    } else {
                        (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
                    }
                    val sample = Float.fromBits(bits)
                    val v = if (sample.isFinite()) sample.toDouble() else 0.0
                    val state = filterState[channel]
                    var sLow = state[0]
                    var sMid = state[1]
                    sLow += kl * (v - sLow)
                    sMid += km * (v - sMid)
                    val m = sMid - sLow
                    val h = v - sMid
                    val fullPower = v * v
                    val lowPower = sLow * sLow
                    val midPower = m * m
                    val highPower = h * h
                    sumFull += fullPower
                    sumLow += lowPower
                    sumMid += midPower
                    sumHigh += highPower
                    sumFullSub += fullPower
                    sumLowSub += lowPower
                    sumMidSub += midPower
                    sumHighSub += highPower
                    state[0] = if (sLow.isFinite()) sLow else 0.0
                    state[1] = if (sMid.isFinite()) sMid else 0.0
                    index += 4
                    i++
                    channel++
                    if (channel >= channels) channel = 0
                    subSamples++
                    if (subSamples >= subFrameSamples) finalizeSubFrame()
                }
            }
            else -> return false
        }

        val n = samples.toDouble()
        val full = sqrt(sumFull / n)
        val lowValue = sqrt(sumLow / n)
        val midValue = sqrt(sumMid / n)
        val highValue = sqrt(sumHigh / n)
        rms = finite01(full)
        low = finite01(lowValue)
        mid = finite01(midValue)
        high = finite01(highValue)
        spectralCentroid = centroidOf(lowValue, midValue, highValue)

        // 本次缓冲覆盖的时长：**(样本数 ÷ 声道数) ÷ 采样率**。
        val frames = samples / channelCount
        frameMs = if (sampleRateHz > 0) (frames * 1000.0 / sampleRateHz).toFloat() else 0f

        return true
    }

    private fun finalizeSubFrame() {
        val n = subSamples.toDouble()
        val low = finite01(sqrt(sumLowSub / n))
        val mid = finite01(sqrt(sumMidSub / n))
        val high = finite01(sqrt(sumHighSub / n))
        val full = finite01(sqrt(sumFullSub / n))
        clearSubFrame()
        if (updateTransient(low, full, high + 0.35 * mid)) {
            attackLow = maxOf(attackLow, low)
            attackMid = maxOf(attackMid, mid)
            attackHigh = maxOf(attackHigh, high)
            attackFull = maxOf(attackFull, full)
        }
    }

    /**
     * Low/full energy retains the kick path. The presence envelope catches short claps and snare
     * attacks that cut through a sustained accompaniment with very little change in full RMS.
     */
    private fun updateTransient(low: Float, full: Float, presence: Double): Boolean {
        val lowValue = low.toDouble()
        val fullValue = full.toDouble()
        val lowBase = baselineState[0]
        val fullBase = baselineState[1]
        val presenceBase = baselineState[2]
        val lowJump = lowValue - lowBase
        val fullJump = fullValue - fullBase
        val presenceJump = presence - presenceBase
        val presenceStep = presence - previousPresence
        val presenceOnset = presence >= 0.025 && presenceJump >= maxOf(0.012, presenceBase * 0.22) &&
            presenceStep >= maxOf(0.012, previousPresence * 0.3) &&
            presence >= peakPresence * subPeakDecay * 1.2
        previousPresence = presence
        peakPresence = maxOf(presence, peakPresence * subPeakDecay)

        // 判定必须用**上一步的**峰值记忆（含本次就把它抬起来 = 自己把自己判掉）。
        val memory = peakLow * subPeakDecay
        val memoryFull = peakFull * subPeakDecay
        val lowNovel = lowValue >= memory * ONSET_PEAK_RATIO
        val fullNovel = fullValue >= memoryFull * ONSET_PEAK_RATIO

        // 先算 jump 再更新基线：否则一次强瞬态会立刻把基线抬起来、把自己判掉。
        baselineState[0] = lowBase + (lowValue - lowBase) * baselineK
        baselineState[1] = fullBase + (fullValue - fullBase) * baselineK
        baselineState[2] = presenceBase + presenceJump * baselineK
        peakLow = if (memory > lowValue) memory else lowValue
        peakFull = if (memoryFull > fullValue) memoryFull else fullValue

        if (cooldownMs > 0.0) cooldownMs = (cooldownMs - subFrameMs).coerceAtLeast(0.0)
        if (cooldownMs > 0.0) return false

        // The peak memory stops the tail of a single hit from re-triggering after cooldown.
        val lowOnset = lowNovel && low >= ONSET_MIN_LOW &&
            lowJump >= ONSET_ABS_MIN &&
            lowJump >= lowBase * ONSET_REL_FACTOR
        val fullOnset = fullNovel && fullValue >= ONSET_MIN_RMS &&
            fullJump >= ONSET_ABS_MIN &&
            fullJump >= fullBase * ONSET_REL_FACTOR
        if (!lowOnset && !fullOnset && !presenceOnset) return false

        cooldownMs = ONSET_COOLDOWN_MS.toDouble()
        transientCount++
        val strength = maxOf(
            lowJump / ONSET_STRENGTH_FULL,
            fullJump / ONSET_STRENGTH_FULL,
            if (presenceOnset) presenceJump / 0.10 else 0.0,
        )
        val clamped = if (strength.isFinite()) strength.toFloat().coerceIn(0f, 1f) else 0f
        // 一次缓冲里可能有多次击打：**取最强的那一次**驱动视觉幅度
        // （逐个播完是不可能的 —— 视觉上"最响的那一下决定这一帧有多大"才是对的）。
        if (clamped > transientStrength) transientStrength = clamped
        return true
    }

    /** 单测 / 诊断：低频带慢速基线。 */
    internal fun lowBaseline(): Float = baselineState[0].toFloat()

    /** 单测 / 诊断：全带慢速基线。 */
    internal fun fullBaseline(): Float = baselineState[1].toFloat()

    companion object {

        /**
         * 低频带截止（Hz）。**直接引用 [PcmRms.BASS_CUTOFF_HZ]**（不是复制数值）：
         * 低频带与 v2.9.0 的低频节拍通道必须是**同一条递推**，否则「柱状图的低频」
         * 与「节拍判据的低频」会分叉，而画面上看不出来。
         */
        const val LOW_CUTOFF_HZ: Double = PcmRms.BASS_CUTOFF_HZ

        /**
         * 中/高频带的分界（Hz）。
         *
         * 2000 的依据：人声基频 85–1100 Hz、吉他基频 82–1000 Hz 都落在它之下，
         * 而弦乐泛音、镲片（能量集中在 3–10 kHz）、齿音落在它之上 —— 这是任务书
         * §4.1「中频＝人声/吉他、高频＝弦乐/镲片」那句话的**频率依据**，
         * 不是随手取的一个数。一阶滤波器的 −6 dB/oct 意味着这条边界**很宽**
         * （150 Hz 以下与 2 kHz 以上各自只衰减 6 dB/oct），所以它是「重心」不是「分界」。
         */
        const val MID_HIGH_CUTOFF_HZ: Double = 2000.0

        /** 质心加权用的三个频带代表频率（Hz）。**约定值，不是测量值** —— 见 [spectralCentroid]。 */
        const val CENTROID_F_LOW_HZ: Double = 75.0
        const val CENTROID_F_MID_HZ: Double = 1000.0
        const val CENTROID_F_HIGH_HZ: Double = 6000.0

        /** 质心归一化的下界 / 上界（Hz）。ln 映射，与听感的对数性一致。 */
        const val CENTROID_MIN_HZ: Double = 50.0
        const val CENTROID_MAX_HZ: Double = 16000.0

        /** 质心分母的保护值：三个频带都近似为零时不参与加权（输出 0）。 */
        const val CENTROID_EPS = 1e-9

        // ---- 瞬态判据（与 `WaveformEffectsState` 的 onset 常量逐值一致，由单测钉住）----

        /** 低频带最低能量门槛：低于它一律不算瞬态（避免安静段落被底噪触发）。 */
        const val ONSET_MIN_LOW = 0.10f

        /** 全带最低响度门槛。与 `WaveformEffectsState.ONSET_MIN_RMS` 同值。 */
        const val ONSET_MIN_RMS = 0.12f

        /** 相对慢速基线的**绝对**增量门槛。与 `WaveformEffectsState.ONSET_ABS_MIN` 同值。 */
        const val ONSET_ABS_MIN = 0.05f

        /** 相对慢速基线的**倍数**门槛：涨幅要超过基线的 35%。与 `ONSET_REL_FACTOR` 同值。 */
        const val ONSET_REL_FACTOR = 0.35f

        /**
         * 冷却（毫秒）：一次击打只出一个瞬态。
         *
         * **90 而不是 v2.9.0 的 180**：180ms 把触发率上限钉在 5.6 次/秒，而 120 BPM 的
         * 八分音符是 16 次/秒、180 BPM 的四分音符是 12 次/秒 —— 快节奏曲目会把相邻击打
         * **合并成一次**（表现为「鼓点应该跳但没跳」）。90ms 的上限是 11 次/秒，
         * 覆盖到 180 BPM 的八分音符；文献里听感可分辨的下界是 30ms 量级，
         * 取 90 是「不漏拍」与「不把一次击打判成两次」之间的折中。
         *
         * 与 `WaveformEffectsState.ONSET_COOLDOWN_MS`（180）**不再同值**：那是 UI 帧循环上
         * 的回落判据，它的时间分辨率本来就受帧率限制（16~33ms），放宽冷却只会让
         * 「一帧内多次触发」变成噪声。两条路径的差异由
         * `AudioFeatureExtractorTest.transientCooldownIsNotSlowerThanTheUiFallback` 钉住方向。
         */
        const val ONSET_COOLDOWN_MS = 90f

        /**
         * 滤波状态的声道上限。
         *
         * 8 的依据：本应用的实测最高声道数是 QQ 臻品档的 **6 声道 FLAC**
         * （`docs/verification/v2.2.1/p0-quality-loop/PROBE.md`）；8 留了两个余量槽位，
         * 同时把状态内存钉死在 `8 × 2 × 8B = 128B`。超过 8 声道的输入只是**不区分**多出来的声道
         * （它们与第 1 声道共用状态），而不会分配、不会抛异常。
         */
        const val MAX_CHANNELS = 8

        /**
         * 典型缓冲时长（毫秒）。只用于给 [baselineK] 一个非零初值；真实值每次 process 现算。
         *
         * ⚠️ 真机实测的缓冲粒度是 **100ms**（S6 / Android 7.0，见 `PROBE-AUDIO-TAP`），
         * 不是这里写的 21ms。这个常量只影响「process 从没被调用过时的初值」，
         * 所以留着 21 不影响任何实际行为 —— 但它**不能**被当成"缓冲就是 21ms"的依据。
         */
        const val TYPICAL_FRAME_MS = 21.0

        /**
         * 瞬态判据的**子帧**长度（毫秒）。
         *
         * 10 的依据：真机缓冲粒度是 100ms（11 Hz），而 120 BPM 的八分音符间隔是 250ms、
         * 180 BPM 的八分音符是 167ms —— 一个 100ms 的缓冲里**可能落下两次击打**。
         * 10ms 的分辨率（约 100 Hz）足以把常见节奏的相邻击打分到不同子帧里，
         * 同时每缓冲只多 9 次结算（10 次 sqrt + 判据），代价可忽略。
         */
        const val SUB_FRAME_MS = 10.0

        /** 一个缓冲里最多可能结算出的子帧数（= 缓冲时长 ÷ 子帧长度）。**有界性用**。 */
        const val MAX_TRANSIENTS_PER_BUFFER = 64

        /** 慢速基线的时间常数（毫秒）。与 `WaveformEffectsState.BASELINE_TAU_MS` 同值（0.8s）。 */
        const val BASELINE_TAU_MS = 800.0

        /**
         * 慢速基线的每缓冲系数（**纯函数**）：`k = 1 − exp(−dt/τ)`。
         *
         * dt ≤ 0 或非法时返回 0（= 基线不动），而不是 1（= 基线直接被这一次的值取代）。
         */
        fun baselineCoefficient(dtMs: Double): Double {
            if (!dtMs.isFinite() || dtMs <= 0.0) return 0.0
            val k = 1.0 - exp(-dtMs / BASELINE_TAU_MS)
            return k.coerceIn(0.0, 1.0)
        }

        /** 强度归一化：增量达到它即视为满强度。0.20 ≈ 普通门槛的 4 倍。 */
        const val ONSET_STRENGTH_FULL = 0.20f

        /**
         * 峰值记忆的衰减时间常数（毫秒）。
         *
         * 250 的依据：常见流行乐的击打间隔 170ms（180 BPM 八分）~ 500ms。
         * 取 250ms ⇒ 一个 500ms 的间隔之后记忆只剩 `e^-2 = 13.5%`（新的一击必然远超它），
         * 而 170ms 之后剩 50%（新的一击仍要高出 60% 才算数，见 [ONSET_PEAK_RATIO]）。
         */
        const val PEAK_MEMORY_TAU_MS = 250.0

        /**
         * 峰值记忆的衰减系数（纯函数）：`e^(−dt/τ)`。
         *
         * dt ≤ 0 或非法时返回 1（= 记忆不衰减 ⇒ 判据变成"必须比上一次更响"，
         * 是保守的一侧，不会因为坏的 dt 而狂触发）。
         */
        fun peakMemoryDecay(dtMs: Double): Double {
            if (!dtMs.isFinite() || dtMs <= 0.0) return 1.0
            val d = exp(-dtMs / PEAK_MEMORY_TAU_MS)
            return if (d.isFinite()) d.coerceIn(0.0, 1.0) else 1.0
        }

        /**
         * 判定"这是一次新的击打"所需的**相对峰值记忆的倍数**。
         *
         * 1.6 的依据（三个场景一起定）：
         *  - **衰减尾巴**（同一击之后 90ms）：记忆还有 70%，而尾巴只剩 22% ⇒ 比值 0.32 ≪ 1.6，正确地不触发；
         *  - **180 BPM 的八分音符**（170ms 间隔）：记忆剩 51%，新的一击 ≈ 1.0 ⇒ 比值 ≈ 1.96 > 1.6，正确地触发；
         *  - **持续的大音量段落**：值与记忆同步（比值 ≈ 1）⇒ 不触发（那不是"一击"）。
         *
         * 1.6 同时给了逐缓冲 RMS 相位起伏（±20%）足够的安全余量。
         */
        const val ONSET_PEAK_RATIO = 1.6

        /** 一阶低通系数：`a = 1 − exp(−2π·fc/fs)`，clamp 到 `[0,1]`。 */
        fun lowPassCoefficient(cutoffHz: Double, sampleRateHz: Int): Double {
            if (sampleRateHz <= 0 || cutoffHz <= 0.0) return 1.0
            val a = 1.0 - exp(-2.0 * PI * cutoffHz / sampleRateHz)
            return a.coerceIn(0.0, 1.0)
        }

        /**
         * 三频带幅度 → 「明亮度占比」`mix ∈ [0,1]`（纯函数）。
         *
         * `mix = (mid + high) / (low + mid + high)`：0 = 能量全在低频带（鼓 / 贝斯），
         * 1 = 全在中高频带（人声 / 弦乐 / 镲片）。它是**波形逐柱着色**的驱动量
         * （时间轴仍然是时间轴，**不是**频谱）与「三频带能量条」的共同输入。
         *
         * 三个幅度都近似为零时返回 0（而不是 NaN —— NaN 一旦进入 `Color` 会让整条波形
         * 变成透明/黑色，且**不会**抛异常）。
         */
        fun bandMix(low: Float, mid: Float, high: Float): Float {
            val l = if (low.isFinite() && low > 0f) low.toDouble() else 0.0
            val m = if (mid.isFinite() && mid > 0f) mid.toDouble() else 0.0
            val h = if (high.isFinite() && high > 0f) high.toDouble() else 0.0
            val denom = l + m + h
            if (denom <= CENTROID_EPS) return 0f
            val mix = (m + h) / denom
            return if (mix.isFinite()) mix.toFloat().coerceIn(0f, 1f) else 0f
        }

        /** 编码 → 每样本字节数；不支持的编码返回 0（调用方据此降级）。 */
        fun bytesPerSample(encoding: Int): Int = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> 0
        }

        /**
         * 三频带幅度 → 归一化质心（0..1）。
         *
         * 纯函数（单测直接断言）：幅度加权平均频率，再按 `ln` 映射到
         * `[CENTROID_MIN_HZ, CENTROID_MAX_HZ]`。三个幅度都近似为零时返回 0
         * （而不是 NaN —— NaN 一旦进入缩放因子会让整层背景消失，且**不会**抛异常）。
         */
        fun centroidOf(lowAmplitude: Double, midAmplitude: Double, highAmplitude: Double): Float {
            val l = if (lowAmplitude.isFinite() && lowAmplitude > 0.0) lowAmplitude else 0.0
            val m = if (midAmplitude.isFinite() && midAmplitude > 0.0) midAmplitude else 0.0
            val h = if (highAmplitude.isFinite() && highAmplitude > 0.0) highAmplitude else 0.0
            val denom = l + m + h
            if (denom <= CENTROID_EPS) return 0f
            val weighted = (l * CENTROID_F_LOW_HZ + m * CENTROID_F_MID_HZ + h * CENTROID_F_HIGH_HZ) / denom
            val hz = weighted.coerceIn(CENTROID_MIN_HZ, CENTROID_MAX_HZ)
            val span = ln(CENTROID_MAX_HZ / CENTROID_MIN_HZ)
            if (span <= 0.0) return 0f
            val normalized = ln(hz / CENTROID_MIN_HZ) / span
            return if (normalized.isFinite()) normalized.toFloat().coerceIn(0f, 1f) else 0f
        }

        private fun finite01(value: Double): Float =
            if (value.isFinite()) value.coerceIn(0.0, 1.0).toFloat() else 0f
    }
}
