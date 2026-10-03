/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects
import kotlin.math.abs
import kotlin.math.exp

/**
 * v1.8.0 · T3：音频可视化的**无锁单写者环形缓冲 + 柱状滚动窗口 + 时间常数平滑**（纯逻辑，可 JVM 单测）。
 *
 * 线程模型是本文件存在的全部理由：
 *
 *  - **写**只有一条线程 —— ExoPlayer 的 playback 线程（`ExoPlayer:Playback`，
 *    THREAD_PRIORITY_AUDIO）。[push] 里只做一次数组写 + 一次 volatile 自增，
 *    **零分配、零锁、零 IO**：音频线程上任何一次 GC 停顿都是爆音。
 *  - **读**是 UI 线程按帧率调用的 [pump]：把"上次之后新到的柱"搬进滚动窗口。
 *  - 两侧唯一的同步是 `writeIndex` 的 volatile 语义（单写者 ⇒ 读到的一定是某个一致前缀）。
 *    溢出时**丢最旧的**而不是等 —— 可视化允许丢帧，绝不允许回压到音频线程。
 *
 * ## v1.8.1：为什么加了平滑
 *
 * 音频数据只有 30 柱/秒，直接把这 30 个值画成柱高，画面就是"每 33ms 硬跳一次"，
 * 观感上明显是一跳一跳的（用户反馈"帧率太低"。**根因不是帧率而是阶跃**）。
 * 现在拆成两层：
 *
 *  - [targets] = 最新数据（阶跃）；
 *  - [bars] = 画面值，用**时间常数**指数逼近 targets：起音快（[ATTACK_TAU_MS]，跟得上鼓点）、
 *    回落慢（[RELEASE_TAU_MS]，像 VU 表一样自然衰减）。
 *
 * 时间常数形式（`k = 1 - exp(-dt/tau)`）而不是固定系数，是为了**与刷新率无关**：
 * 60fps 与 30fps 下同一条曲线，低端机降帧率不会让观感变形。
 *
 * ## v2.8.0 · P1-A：峰值保持 + 两个动画相位（仍然零分配）
 *
 * A 档的**峰值保持**（[peaks]）与 B 档的**渐变流动相位** / **呼吸相位**都放在这一层，
 * 理由有两个，都是踩过的坑：
 *
 *  1. **可单测**：这些量都是"每帧推进一步"的纯状态，放 draw lambda 里就只能靠肉眼；
 *  2. **必须并进收敛判据**：探针 §1 明确警告过「峰值只活在 draw 里 ⇒ bars 一收敛就
 *     `pump` 返回 false ⇒ 峰值冻在画面上永不落下」（v1.8.1 被单测抓到过同形状的缺陷）。
 *     所以 [pump] 的返回值把「峰值还在落 / 呼吸还在走」也算进去，`WaveformRingTierTest`
 *     有专门用例钉住「峰值最终归零且之后不再要求重绘」。
 *
 * 两个相位的**空转边界**：相位只在 `active`（播放中且未缓冲）**且画面最新一根柱还有内容**
 * （`bars[barCount-1] > [ANIMATION_MIN_SIGNAL]`）时前进，而且只有真的前进了才要求重绘 ——
 * 否则一首歌的静音段会让 60fps 白烧在一条什么都没有的波形上，与 `AudioVisualizer.kt`
 * 的「不空转」契约冲突。渐变流动没有额外的门槛：它和呼吸共用同一个「有信号」条件
 * （静音时既没有色带要流、也不该呼吸）。
 */
/**
 * ⚠️ **v3.3.1 回退了 v3.3.0 对滚动的改动。改这个类之前请先读完这一段。**
 *
 * ## 发生了什么
 *
 * 用户反馈「60 帧设备上还是抖动」。v3.2.4 的帧闸门改动在 60Hz 上等于没改
 * （非低内存设备 stride 恒为 1），所以抖动来自 v3.2.3 引入的**亚格插值**：
 * 它把相位（`sinceBarMs`）在**每根柱到达时清零**，那一帧的位移因此多出 `1 − 相位`
 * （最高 2.1× 名义速度），观感是每 ~100ms 一次的「顿-冲」。
 *
 * ## v3.3.0 试了什么、为什么失败
 *
 * 我把「窗口平移」与「小数位置」合并到一个相位上（相位每跨过整数就平移一格，
 * 见当时的 `scrollPhase` / `shiftedCells`）。逐帧仿真确实得到过
 * 「步长恒为 0.1667、偏差 4.4e-6」。
 *
 * **但用户在真机上实测：仍然抖动，而且会「抽搐」。**
 *
 * 复盘出的两条教训（都是我的错，写在这里防止重犯）：
 *
 * 1. **判据的测量口径错了三次。** 我先后用「只量相位的差分」「把平移格数硬编码成 +1」
 *    「`shiftedCells + frac(phase)`」三种口径去量「可见位移」，每一种都能自圆其说地
 *    给出「已修好」的数字 —— 而渲染层真正用的是
 *    `W[i] + (W[i+1] − W[i]) × scrollPhase01()`，
 *    它对应的量是「窗口下标 + 小数」，不是我在测试里另建的那套账。
 *    **结论：这个组件的判据必须在真机上看画面，仿真绿灯不算数。**
 * 2. **我把「帧时间抖动」与「相位量化」混成了一个缺陷。** 真机 `dt` 在 15~20ms 之间跳是
 *    常态（GC / 组合 / 调度），那是**帧时间**的抖动；而 v3.2.3 的缺陷是**相位量化**。
 *    两者都会表现为「波形抖」，但修法相反：前者要平滑速率，后者要去掉清零。
 *    我在两个假设之间来回改了 6 次，每次都以为找到了根因。
 *
 * ## 现在的状态（v3.3.1 起）
 *
 * 本文件已**逐字节回退到 v3.2.4**（用户实测「能用」的那一版）——
 * 也就是说 v3.2.3 的相位清零**回来了**，抖动仍在，但它至少是**已知的、可用的**行为。
 * 本版不再尝试修它：没有可靠自验手段的改动，风险大于收益。
 *
 * ## 如果将来还要修
 *
 * 至少要做到这三条，否则不要动：
 * - **先在真机上录屏量化**（帧间隔 + 可见位移两条序列），不要只做 JVM 仿真；
 * - 判据必须**直接读渲染层消费的那个量**（`scrollPhase01()` 与窗口下标），
 *   不要在测试里另建一套「可见位置」的定义；
 * - 90 / 120Hz 面板与 60Hz 面板**分别**验证 —— 本环境只有 60Hz 模拟器。
 */
class WaveformRing(
    /** 环形缓冲容量（柱数）。UI 掉帧时最多积压这么多，再多就丢最旧的。 */
    private val capacity: Int,
    /** 滚动窗口长度 = 画面上的柱子数。 */
    val barCount: Int,
) {
    init {
        require(capacity > 0) { "capacity must be > 0" }
        require(barCount > 0) { "barCount must be > 0" }
    }

    private val ring = FloatArray(capacity)

    /**
     * v2.9.0 / v3.2.2：与 [ring] **逐槽对齐**的三个频带环形缓冲（低 / 中 / 高）。
     *
     * 为什么是平行的三个环而不是"再算一遍"：四个值来自**同一次**音频线程遍历
     * （`AudioFeatureExtractor.process` 里的两个一阶低通），到 UI 侧也必须逐槽对齐 ——
     * 否则三个泳道画的是不同时刻的曲线，接缝处的时间轴会错开。
     *
     * v3.2.2 之前这里只有低频一个环（v2.9.0 的节拍判据用）+ 一个「明亮度占比」环
     * （v3.0.0 的逐柱 tint 用）。本版要画**三条各自滚动的频带泳道**（左低 / 中中 / 右高），
     * 于是中频与高频也需要各自的**历史窗口** —— 占比是把三者揉成一个数，
     * 揉完就回不去了（`(mid+high)/(low+mid+high)` 无法反解出 mid 与 high）。
     * 音频线程的代价是**传两个已经算好的 float**，没有任何新增逐样本计算（铁律 29）。
     */
    private val lowRing = FloatArray(capacity)
    private val midRing = FloatArray(capacity)
    private val highRing = FloatArray(capacity)

    /**
     * 写入游标 = 累计写入的柱数（不是下标）。
     *
     * volatile：音频线程写、UI 线程读。**单写者**，所以 UI 侧先快照它、再读
     * `[0, snapshot)` 区间，读到的一定是连续且已经写完整的值。
     */
    @Volatile
    private var writeIndex: Int = 0

    /** UI 侧已消费到的位置。只有 UI 线程读写。 */
    private var readIndex: Int = 0

    /**
     * 滚动窗口自起播以来**累计平移了多少格**（= 被消费的柱数）。
     *
     * 存在的唯一理由是**给判据提供与渲染层完全同源的位移量**：渲染层真正消费的是
     * 「窗口下标 + [scrollPhase01]」（`W[i] + (W[i+1] − W[i]) × phase`），
     * 所以「这一帧可见位移」只可能是 `Δ本值 + ΔscrollPhase01()`。
     * 测试若另建一套「可见位置」的定义（v3.3.0 踩过三次：只量相位差分、把平移硬编码 +1、
     * `shiftedCells + frac(phase)`），量出来的数就不再是画面上的那个东西。
     *
     * 生产代码**不读它**：`private` + `internal` 访问器 = 同一模块内可见，JVM 单测可直接调。
     */
    private var shiftedCells: Long = 0

    /** 单测 / 诊断：累计平移格数（见 [shiftedCells] 的注释，生产路径零调用）。 */
    internal fun shiftedCellsForTest(): Long = shiftedCells

    /**
     * v3.2.3：**两条柱之间的推进相位**（0..1），让画面按帧率连续滚动。
     *
     * ## 为什么必须补这一层（用户实测反馈「刷新率好低」）
     *
     * 音频缓冲只有 ~10 Hz（真机实测 100ms），而滚动窗口是**按缓冲位移**的：
     * 每 100ms 整条形状"跳"一格。帧循环哪怕是 60fps，看到的也是**10 Hz 的阶跃**——
     * 用户读到的就是"刷新率低"。柱高那一层有 22/130ms 的时间常数在抹平，
     * 而**三条泳道的形状**（历史窗口本身）没有任何抹平：它一格一格跳。
     *
     * 所以这里记两个量：距上一根柱过了多久（[sinceBarMs]）与柱间隔的滑动平均
     * （[barIntervalMs]），渲染层用它们的比值做**相邻两格之间的线性插值**——
     * 形状因此按帧率连续左移，而数据仍然是 10 Hz 的真实值（没有插值出假数据：
     * 插的是"两格之间"的位置，不是"未来"的值）。
     */
    private var sinceBarMs: Float = 0f

    /** 柱间隔的滑动平均（毫秒）。硬件缓冲粒度是运行时行为，所以只能测、不能假设。 */
    private var barIntervalMs: Float = DEFAULT_BAR_INTERVAL_MS

    /** 最新数据（阶跃）。UI 线程原地更新，绝不重新分配。 */
    private val targets = FloatArray(barCount)

    /** v2.9.0：与 [targets] 对齐的**低频**最新值（节拍判据用）。 */
    private var bassTarget = 0f

    /** v3.2.2：与 [targets] 对齐的三条**频带历史窗口**（三条泳道的画面值来源）。 */
    private val lowTargets = FloatArray(barCount)
    private val midTargets = FloatArray(barCount)
    private val highTargets = FloatArray(barCount)

    /** 画面上的柱子高度（0..1）= 平滑后的值。UI 线程原地更新。 */
    private val bars = FloatArray(barCount)

    /** A 档：峰值（0..1）。独立数组，每帧原地更新 —— 绝不每帧分配。 */
    private val peaks = FloatArray(barCount)

    /** 每个柱的峰值保持剩余时间（毫秒）。到 0 之后峰值才按 [PEAK_FALL_PER_SECOND] 下落。 */
    private val peakHoldMs = FloatArray(barCount)

    /** B 档：渐变流动相位（0..1 循环）。 */
    private var flowPhase = 0f

    /** B 档：呼吸相位（0..1 循环）。 */
    private var breathePhase = 0f

    /**
     * 音频线程：推入一根柱（全带 RMS + 低频能量，都是 0..1）。
     *
     * 两个值**共用同一个 writeIndex**：单写者语义不变，UI 侧快照一次游标就能拿到
     * 对齐的一对值。
     *
     * v3.0.0：多收一个 [mix]（明亮度占比，0..1）。它同样共用 `writeIndex`，
     * 所以「柱高、低频、明亮度」三者永远来自**同一个音频缓冲**，不会错帧。
     * 老调用点（只给 RMS，或给 RMS + 低频）继续可用：默认 `bass = value`、`mix = 0`。
     */
    fun push(value: Float, low: Float = value, mid: Float = 0f, high: Float = 0f) {
        // NaN / Inf 防御：环形缓冲的边界读在和写者赛跑时理论上可能读到半个值
        // （见 pump 的注释）。宁可画成静音，也不要让 NaN 传染给整块画布。
        val slot = writeIndex % capacity
        ring[slot] = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        lowRing[slot] = if (low.isFinite()) low.coerceIn(0f, 1f) else 0f
        midRing[slot] = if (mid.isFinite()) mid.coerceIn(0f, 1f) else 0f
        highRing[slot] = if (high.isFinite()) high.coerceIn(0f, 1f) else 0f
        writeIndex += 1
    }

    /** UI 线程：清空（换歌 / 停止时用）。 */
    fun clear() {
        readIndex = writeIndex
        bars.fill(0f)
        targets.fill(0f)
        bassTarget = 0f
        lowTargets.fill(0f)
        midTargets.fill(0f)
        highTargets.fill(0f)
        peaks.fill(0f)
        peakHoldMs.fill(0f)
        flowPhase = 0f
        breathePhase = 0f
    }

    /** UI 线程按帧率调用（效果全关的基线路径，老调用点与单测沿用这个重载）。 */
    fun pump(active: Boolean, dtMs: Float): Boolean = pump(active, dtMs, VisualizerEffects.BASELINE)

    /**
     * UI 线程按帧率调用。
     *
     * @param active 播放中（且不在缓冲）时为 true：消费新柱并把画面值逼近数据值；
     *   false 时把**数据值**归零，画面值按 [RELEASE_TAU_MS] 衰减到 0
     *   —— 暂停/缓冲瞬间清零会"闪一下"，衰减看起来像余震自然消失。
     * @param dtMs 距上一帧的毫秒数。平滑系数由它算出来，所以 30fps 与 60fps 观感一致。
     * @param effects 当前档位的能力位：只决定「峰值保持 / 相位动不动」，渲染细节不在这里。
     * @return 画面是否需要重绘。**完全静止（没有新数据、已收敛、峰值已落、相位已停）时
     *   返回 false** —— 这是"暂停后不再白烧 GPU"的保证。
     */
    fun pump(active: Boolean, dtMs: Float, effects: VisualizerEffects): Boolean {
        var changed = false
        if (active) {
            // v3.2.3：先结算相位（用**上一帧**的间隔），再消费新柱 —— 消费到柱时相位归零。
            val dtForPhase = dtMs.coerceIn(0f, 200f)
            val interval = if (barIntervalMs > 1f) barIntervalMs else DEFAULT_BAR_INTERVAL_MS
            // 空转门槛与动画相位同一条（`ANIMATION_MIN_SIGNAL`）：**画面最后一格还有内容**时
            // 才推进相位、才为"滚动"排帧。静音段 / 放完 / 暂停都既不排帧也不推进
            // —— v1.8.1 的「不空转」契约原样保住（相位冻在那里，下一根柱到达时归零）。
            if (targets[barCount - 1] > ANIMATION_MIN_SIGNAL) {
                if (sinceBarMs < interval) changed = true
                sinceBarMs += dtForPhase
            }
            val consumed = consumePending()
            if (consumed) {
                // 柱间隔的滑动平均：只用落在合理区间的样本，避免一次卡顿把间隔带飞。
                val avg = sinceBarMs / pendingConsumed.coerceAtLeast(1)
                if (avg in MIN_BAR_INTERVAL_MS..MAX_BAR_INTERVAL_MS) {
                    barIntervalMs += (avg - barIntervalMs) * BAR_INTERVAL_EMA
                }
                sinceBarMs = 0f
            }
            if (consumed) changed = true
        } else if (targets.any { it != 0f }) {
            targets.fill(0f)
            changed = true
        }
        val dt = dtMs.coerceIn(0f, 200f)
        val animated = approach(dt, effects)
        // 相位只在「播放中 **且** 画面最新一根柱还有内容」时前进，并且只有真的前进了才要求重绘。
        // 这一条就是探针 §2「呼吸 / 粒子 / 涟漪 × 暂停即停帧契约」要求的**显式停止条件**：
        // 静音段与暂停态都不会为动画排帧（`pump` 返回 false ⇒ generation 不增 ⇒ 不失效）。
        //
        // 判据用**画面值**（bars 的最后一根）而不是原始数据（targets）：
        //  - 画面值带 130ms 回落时间常数 ⇒ 单个安静缓冲块（33ms）不会让流动一顿一顿；
        //  - 它归零就等于"画面上已经什么都没有了"，此时继续排帧纯属白烧（暂停/放完/长期静音）。
        var phaseAdvanced = false
        if (active && bars[barCount - 1] > ANIMATION_MIN_SIGNAL) {
            if (effects.flow) {
                flowPhase = advancePhase(flowPhase, dt, FLOW_PERIOD_MS)
                phaseAdvanced = true
            }
            if (effects.breathe) {
                breathePhase = advancePhase(breathePhase, dt, BREATHE_PERIOD_MS)
                phaseAdvanced = true
            }
        }
        return animated || changed || phaseAdvanced
    }

    /** 把环形缓冲里 UI 还没消费的柱搬进 [targets]。 */
    private fun consumePending(): Boolean = consumePendingCount() > 0

    /** v3.2.3：消费了多少根柱（相位推进要用它换算平均柱间隔）。 */
    private var pendingConsumed: Int = 0

    private fun consumePendingCount(): Int {
        pendingConsumed = 0
        val snapshot = writeIndex
        var pending = snapshot - readIndex
        if (pending <= 0) return 0
        if (pending > capacity) {
            // 溢出：丢掉最旧的，绝不阻塞写者。
            readIndex = snapshot - capacity
            pending = capacity
        }
        var changed = false
        repeat(pending) {
            // 注意：读到的是"某一时刻的值"，写者可能刚好覆盖同一格（只在溢出时发生）。
            // 单根柱失真在视觉上不可见，也不影响后续帧 —— 因此这里不用锁。
            val slot = readIndex % capacity
            if (shiftIn(ring[slot])) changed = true
            // v2.9.0：低频通道额外保留"最新一根"给**节拍判据**用（`newestBass`）：
            // 它要的是"当下这一刻"的低频能量，不是历史窗口。
            val low = lowRing[slot]
            val lowValue = if (low.isFinite()) low.coerceIn(0f, 1f) else 0f
            bassTarget = lowValue
            val mid = midRing[slot]
            val midValue = if (mid.isFinite()) mid.coerceIn(0f, 1f) else 0f
            val high = highRing[slot]
            val highValue = if (high.isFinite()) high.coerceIn(0f, 1f) else 0f
            // v3.2.2：三条频带**都要**进滚动窗口 —— 三条泳道画的就是它们各自的历史。
            // 它们的位移**计入重绘判据**（与 v3.0.0 的"占比不进判据"不同）：
            // 占比只影响颜色，而频带值直接决定**几何**（泳道高度），不重绘就会画出旧的形状。
            if (shiftBandIn(lowTargets, lowValue)) changed = true
            if (shiftBandIn(midTargets, midValue)) changed = true
            if (shiftBandIn(highTargets, highValue)) changed = true
            readIndex += 1
            // 平移格数与该柱**同时**记账：渲染层的「窗口下标」因此成为测试可以直接读到的事实，
            // 而不是测试自己数出来的东西（v3.3.0 三次口径错误的根源）。
            shiftedCells += 1
        }
        pendingConsumed = pending
        return pending
    }

    /**
     * 把一条频带值搬进它自己的滚动窗口（与 [shiftIn] 同构）。
     *
     * @return 这一格是否真的改变了窗口内容（全等值时不必重绘）。
     */
    private fun shiftBandIn(window: FloatArray, value: Float): Boolean {
        var changed = false
        for (i in 0 until barCount - 1) {
            if (window[i] != window[i + 1]) {
                window[i] = window[i + 1]
                changed = true
            }
        }
        if (window[barCount - 1] != value) {
            window[barCount - 1] = value
            changed = true
        }
        return changed
    }

    /** @return 这一根新柱是否真的改变了窗口内容（全等值时不必重绘）。 */
    private fun shiftIn(value: Float): Boolean {
        var changed = false
        for (i in 0 until barCount - 1) {
            if (targets[i] != targets[i + 1]) {
                targets[i] = targets[i + 1]
                changed = true
            }
        }
        val last = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        if (targets[barCount - 1] != last) {
            targets[barCount - 1] = last
            changed = true
        }
        return changed
    }

    /**
     * 让画面值朝数据值走一步（时间常数形式，与刷新率无关）。
     *
     * **重绘判据刻意不用"位移是否大于某个 epsilon"**：回落尾段每帧位移会越来越小，
     * 用位移阈值会让 [pump] 在柱子还停在 ~1.5% 的时候就报"不用重绘" —— 柱子冻在
     * 非零值上（v1.8.1 实现时被单测抓到的真实缺陷）。现在的判据是"这一根还没到位"
     * （`current != target`），配合下面的收敛截断：只要没到位就继续重绘，一旦吸附到
     * 目标值就精确相等、下一帧自然停。
     *
     * v2.8.0 起峰值保持也并进这个返回值：峰值下落期间必须继续重绘，落到位之后精确相等。
     *
     * @return 有柱子还没到位、或峰值还在动（需要重绘）时为 true。
     */
    private fun approach(dt: Float, effects: VisualizerEffects): Boolean {
        val kAttack = 1f - exp(-dt / ATTACK_TAU_MS)
        val kRelease = 1f - exp(-dt / RELEASE_TAU_MS)
        var changed = false
        for (i in bars.indices) {
            val target = targets[i]
            val current = bars[i]
            if (current != target) {
                val k = if (target > current) kAttack else kRelease
                val next = current + (target - current) * k
                // 收敛截断：无限逼近永远不等于目标，不截断就会永远"需要重绘"。
                bars[i] = if (abs(next - target) < SETTLE_EPSILON) target else next
                changed = true
            }
            if (effects.peaks && advancePeak(i, dt, bars[i])) changed = true
        }
        return changed
    }

    /**
     * 峰值保持：`max(柱, 上一帧峰值)`，到达新峰值时重置保持计时；保持期结束后按
     * [PEAK_FALL_PER_SECOND] 匀速下落，但**绝不低于当前柱高**（否则光点会插进柱子里）。
     *
     * @return 峰值是否变化（需要重绘）。
     */
    private fun advancePeak(index: Int, dt: Float, bar: Float): Boolean {
        val previous = peaks[index]
        val next: Float
        if (bar >= previous) {
            next = bar
            peakHoldMs[index] = PEAK_HOLD_MS
        } else if (peakHoldMs[index] > 0f) {
            peakHoldMs[index] = (peakHoldMs[index] - dt).coerceAtLeast(0f)
            next = previous
        } else {
            var fallen = previous - PEAK_FALL_PER_SECOND * dt / 1000f
            if (fallen < bar) fallen = bar
            if (fallen < 0f) fallen = 0f
            next = fallen
        }
        if (next == previous) return false
        peaks[index] = next
        return true
    }

    /** 相位推进：只在 [periodMs] 内循环，用取模而不是累加 —— 长跑不会丢精度。 */
    private fun advancePhase(phase: Float, dt: Float, periodMs: Float): Float {
        if (periodMs <= 0f) return 0f
        var next = phase + dt / periodMs
        if (next >= 1f) next -= (next.toInt()).toFloat()
        return if (next.isFinite()) next else 0f
    }

    /** 把滚动窗口拷进调用方**复用**的数组（避免每帧分配）。 */
    fun copyInto(destination: FloatArray) {
        val n = minOf(destination.size, barCount)
        for (i in 0 until n) destination[i] = bars[i]
    }

    /** 把滚动窗口与峰值一起拷进调用方复用的两个数组（渲染路径用，零分配）。 */
    fun copyInto(barsDestination: FloatArray, peaksDestination: FloatArray) {
        copyInto(barsDestination)
        val n = minOf(peaksDestination.size, barCount)
        for (i in 0 until n) peaksDestination[i] = peaks[i]
    }

    /**
     * v3.2.2：把**三条频带**的滚动窗口拷进调用方复用的三个数组（零分配）。
     *
     * 三个数组长度都按 `barCount` 截断 —— 调用方传大数组也不会越界。
     */
    fun copyBandsInto(lowDestination: FloatArray, midDestination: FloatArray, highDestination: FloatArray) {
        val n = minOf(lowDestination.size, barCount)
        for (i in 0 until n) lowDestination[i] = lowTargets[i]
        val m = minOf(midDestination.size, barCount)
        for (i in 0 until m) midDestination[i] = midTargets[i]
        val h = minOf(highDestination.size, barCount)
        for (i in 0 until h) highDestination[i] = highTargets[i]
    }

    /** B 档：呼吸亮度倍率（[1-BREATHE_DEPTH] .. 1）。draw 阶段直接读，不触发重组。 */
    fun breatheScale(): Float {
        if (!breathePhase.isFinite()) return 1f
        // 用 cos 而不是 sin：相位 0（起播那一刻）就是**满亮度** —— 呼吸的第一步不该先暗一下。
        // 0.5 + 0.5·cos(2π·phase) ∈ [0,1]，再映射到 [1-DEPTH, 1]。
        val wave = 0.5f + 0.5f * kotlin.math.cos(TWO_PI * breathePhase)
        return 1f - BREATHE_DEPTH + BREATHE_DEPTH * wave
    }

    /** B 档：渐变流动相位（0..1 循环）。 */
    fun flowPhase01(): Float = if (flowPhase.isFinite()) flowPhase else 0f

    /**
     * 最新一根柱的**未平滑**数据值（C 档节拍检测的输入）。
     *
     * 用未平滑值而不是 [bars]：起音时间常数 22ms 会把 onset 抹圆（探针 §1 明确要求用 targets）。
     */
    fun newestTarget(): Float = targets[barCount - 1]

    /**
     * v3.2.3：两条柱之间的推进相位（0..1）。渲染层用它把历史窗口**连续左移**一格，
     * 而不是每 100ms 跳一格。**不产生新数据**：插的是相邻两格之间的位置。
     */
    fun scrollPhase01(): Float {
        val interval = if (barIntervalMs > 1f) barIntervalMs else DEFAULT_BAR_INTERVAL_MS
        val p = sinceBarMs / interval
        return if (p.isFinite()) p.coerceIn(0f, 1f) else 0f
    }

    /** v3.2.3：当前测到的柱间隔（毫秒，滑动平均）。诊断 / 单测用。 */
    fun barIntervalMs(): Float = barIntervalMs

    /** v2.9.0：最新的低频能量（与 [newestTarget] 同一时刻）。 */
    fun newestBass(): Float = bassTarget

    /** 单测用：某一条频带窗口里的第 `index` 格。 */
    fun bandAt(band: Int, index: Int): Float {
        if (index !in 0 until barCount) return 0f
        return when (band) {
            BAND_LOW -> lowTargets[index]
            BAND_MID -> midTargets[index]
            BAND_HIGH -> highTargets[index]
            else -> 0f
        }
    }

    /** 单测用：画面值（平滑后）。 */
    fun barAt(index: Int): Float = bars[index]

    /** 单测用：数据值（未平滑）。 */
    fun targetAt(index: Int): Float = targets[index]

    /** 单测用：峰值。 */
    fun peakAt(index: Int): Float = peaks[index]

    /** 单测用：还没被 UI 消费的柱数。 */
    val pendingCount: Int get() = writeIndex - readIndex

    companion object {
        /** 起音时间常数（毫秒）：跟得上鼓点，又不至于把 30Hz 的阶跃原样画出来。 */
        const val ATTACK_TAU_MS = 22f

        /** 回落时间常数（毫秒）：VU 表式的自然衰减。 */
        const val RELEASE_TAU_MS = 130f

        /** 收敛判据：低于它就吸附到目标值，避免"永远差一点点"导致无限重绘。 */
        const val SETTLE_EPSILON = 0.004f

        /**
         * 峰值保持时长（毫秒）。取 420ms 的依据：常见流行乐的鼓点间隔在 300~600ms，
         * 保持 420ms 能让峰值在**同一小节内**读得出"刚才有多响"，又不会跨到下一拍
         * 而看起来像"卡住了"。
         */
        const val PEAK_HOLD_MS = 420f

        /**
         * 峰值下落速度（每秒，单位是 0..1 幅度）。0.9/s 意味着从满幅落到 0 最多 1.1 秒，
         * 与回落时间常数（130ms）相比明显更慢 —— 这正是"峰值比柱子掉得慢"的观感来源。
         */
        const val PEAK_FALL_PER_SECOND = 0.9f

        /** 渐变流动一个完整周期的时长（毫秒）。2.4s 是一眼能看出"在流动"又不至于晃眼的下限附近。 */
        const val FLOW_PERIOD_MS = 2400f

        /** 呼吸一个完整周期的时长（毫秒）。3.2s ≈ 平静呼吸，比渐变慢一档避免两个动画打架。 */
        const val BREATHE_PERIOD_MS = 3200f

        /** 呼吸深度：亮度在 [1-DEPTH, 1] 之间摆动。0.16 在 OLED 黑底上可辨、又不至于像闪烁。 */
        const val BREATHE_DEPTH = 0.16f

        /**
         * 动画相位的空转门槛：**画面**最新一根柱低于它就不推进相位（也就不会为它排帧）。
         * 0.02 远低于音乐 RMS 的常见区间（0.05~0.3，见 AudioVisualizer 的 sqrt 映射注释），
         * 但高于"全零后的残余"，所以静音段与暂停态都不会白烧帧。
         */
        const val ANIMATION_MIN_SIGNAL = 0.02f

        /** v3.2.3：柱间隔的初值（毫秒）。真机实测 S6 是 100ms（11 Hz），这里只是起始猜测。 */
        const val DEFAULT_BAR_INTERVAL_MS = 100f

        /** 柱间隔样本的合法区间：超出就丢弃（卡顿 / 暂停恢复不该污染滑动平均）。 */
        const val MIN_BAR_INTERVAL_MS = 20f
        const val MAX_BAR_INTERVAL_MS = 500f

        /** 柱间隔滑动平均的更新系数（每根柱一次）。 */
        const val BAR_INTERVAL_EMA = 0.15f

        /** v3.2.2：频带下标（与 `BandColorRoles` / `BandDominance` 的三色顺序一致）。 */
        const val BAND_LOW = 0
        const val BAND_MID = 1
        const val BAND_HIGH = 2

        private const val TWO_PI = 6.2831855f
    }
}
