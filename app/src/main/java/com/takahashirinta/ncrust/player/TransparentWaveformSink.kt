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
 */

package com.takahashirinta.ncrust.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.takahashirinta.ncrust.ui.player.WaveformStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 * @param rootMeanSquare RMS 计算。默认 [PcmRms.of]。
 */
@UnstableApi
class TransparentWaveformSink(
    private val enabled: () -> Boolean = { WaveformStore.enabled },
    private val onBar: (Double) -> Unit = WaveformStore::onBar,
    private val rootMeanSquare: (ByteBuffer, Int) -> Double = PcmRms::of,
) : TeeAudioProcessor.AudioBufferSink {

    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0

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
    }

    /**
     * **音频线程**。零分配、零锁、零异常（任务书对可视化的性能契约）。
     *
     * 三件事按顺序发生，顺序本身就是契约：
     *  1. 未知编码（24bit / 32bit 整数等）与声道数 0 直接返回：宁可没有可视化，也不能让播放失败；
     *  2. **开关前置**：关掉可视化 ⇒ 连 RMS 都不算（用户关掉开关后本回调只剩一次 volatile 读）；
     *  3. 「RMS + 回调」在**同一个** try 里 ⇒ 任何 Throwable 都在这里终结，绝不向上抛
     *     （media3 的 tee 与 AudioSink 都不兜异常，抛出去就是 v2.2.1 的级联形状）。
     *
     * 失败时的行为是**丢弃这一根柱**，不是补一个 0：补 0 会在画面上画出一个假的静音凹陷。
     */
    override fun handleBuffer(buffer: ByteBuffer) {
        val bps = bytesPerSample
        if (bps == 0 || channelCount == 0) return
        if (!enabled()) return
        try {
            onBar(rootMeanSquare(buffer, encoding))
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
