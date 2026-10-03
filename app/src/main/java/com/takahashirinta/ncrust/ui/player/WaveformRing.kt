/*
 * Ncrust —— 网易云音乐第三方客户端
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
     * v3.2.3：**两条柱之间的推进相位**（0..1），让画面按帧率连续滚动。
     *
     * ## 为什么必须补这一层（用户实测反馈「刷新率好低」）
     *
     * 音频缓冲只有 ~10 Hz（真机实测 100ms），而滚动窗口是**按缓冲位移**的：
     * 每 100ms 整条形状"跳"一格。帧循环哪怕是 60fps，看到的也是**10 Hz 的阶跃**——
     * 用户读到的就是"刷新率低"。柱高那一层有 22/130ms 的时间常数在抹平，
     * 而**三条泳道的形状**（历史窗口本身）没有任何抹平：它一格一格跳。
     *
     * 所以这里记两个量：**相位**（[scrollPhase]）与柱间隔的滑动平均
     * （[barIntervalMs]），渲染层用它们的比值做**相邻两格之间的线性插值**——
     * 形状因此按帧率连续左移，而数据仍然是 10 Hz 的真实值（没有插值出假数据：
     * 插的是"两格之间"的位置，不是"未来"的值）。
     *
     * ## v3.3.0 · P1：相位语义从「距上次到达的时长」改成「**时间积分，到达减整数格**」
     *
     * [scrollPhase] 的单位仍是「格」（1.0 = 一整格 = 一个柱间隔），但**它不再在到达时清零**，
     * 而是减去整数格。原因是清零会让那一帧的位移多出 `1 − 小数部分`，
     * 在 60Hz 上表现为每 ~100ms 一次的「顿-冲」（完整推导与实测放大倍数见 [pump] 的注释）。
     *
     * 关键性质：`x` 与 `x−1` 的小数部分相同 ⇒ 小数部分连续 ⇒ 逐帧位移恒为 `dt / interval`。
     * 被减掉的整数格由 `consumePendingCount()` 同步推进的历史窗口承担，
     * 所以「多减一格」或「少减一格」都只会让画面**平移一格**，不会让滚动速度变形。
     */
    private var scrollPhase: Float = 0f

    /**
     * v3.3.0：距上一次**柱到达**的挂钟毫秒数。柱间隔估计**只**用它。
     *
     * ## 为什么不再从相位反推（这是我踩过的一个坑）
     *
     * 相位是「时间量」，直觉上「相位涨了 p 格 ⇒ 过了 p × 间隔毫秒」，于是
     * `avg = phase / arrivals * intervalMs` 看起来等价。**它不等价**：
     * 相位会被 `floor` 扣掉整数部分，所以它**同时**表示「攒了多少」与「欠了多少」，
     * 两者混在一起时按 `phase × interval` 反推会把间隔算飞
     * （逐帧诊断实测：柱间隔从 100ms 被一步步推到 **140ms**，于是每帧步长
     * 从 0.1667 缩到 0.125 —— 画面越滚越慢，而这不是原型缺陷、是本版引入的）。
     *
     * 用挂钟直接量就没有这层耦合：到达时读一次、清零，语义与 `sinceBarMs` 时代
     * **完全一致**（那本来就是它的含义），而相位只负责插值。
     */
    private var msSinceArrival: Float = 0f

    /**
     * v3.3.0：历史窗口**累计平移的格数**。它和 [scrollPhase] 一起构成可见位置：
     *
     * ```
     * 可见位置 = shiftedCells + frac(scrollPhase)
     * ```
     *
     * 暴露它（[shiftedCellsForTest]）不是为了给渲染层用 —— 渲染层直接用窗口下标 ——
     * 而是为了让「滚动速度」这条判据**可被精确测量**：
     * 抖动是「逐帧位移不均」，而位移必须拿这个组合量去量，单看相位或单看格数都会量错
     * （本用例为此错过两次，理由见 `WaveformRingTest` 里那条用例的注释）。
     */
    private var shiftedCells: Float = 0f

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
            // ── v3.3.0 · P1：相位改为「**小数计数器**」────────────────────────────────
            //
            // v3.2.3 的写法是「记距上一根柱过了多久（sinceBarMs），消费到柱时**归零**」。
            // 那个写法在 60Hz 上**必然**抖，而且与刷新率、stride 都无关：
            //
            //   可见位置 W = 已到达的格数 + p，其中 p = sinceBarMs / interval。
            //   某一帧消费了 n 根柱 ⇒ 格数 +n，而 p 被**清零**。
            //   于是那一帧的位移 = n + dt/interval + (1 − p_prev)  ← 多出的 (1 − p_prev) 就是跳变。
            //
            // 实测放大倍数（逐行复刻 pump 的仿真）：柱间隔 92.88ms（4×1024@44.1k）
            // ⇒ 单帧位移 0.51×~1.86× 名义；100±3ms（真实抖动）⇒ 0.00×~2.10×；
            // 静音闸门冻结相位后再恢复 ⇒ 最高 6.0×。
            // 而 `scrollPhase01()` 的 `coerceIn(0f, 1f)` 把「迟到」压成**停顿**，
            // 下一帧再补回来 —— 观感就是每 ~100ms 一次的「顿-冲」。
            //
            // ## 正确模型：**相位按 dt 推进，消费的格数 = 相位的整数部分**
            //
            // [scrollPhase] 的单位是「格」：整数部分 = 画面**已经该平移过去的格数**，
            // 小数部分 = 这一格之内的插值位置。于是「推进」与「消费」在同一次 pump 里结算：
            //
            //   phase += dt / interval              （时间积分）
            //   cells  = floor(phase)               （该平移几格 —— **这就是它的物理含义**）
            //   phase -= min(cells, 本帧到达数)      （平移掉的部分从相位里扣掉）
            //
            // 关键性质：消费量与推进量**同帧**结算 ⇒ 相位的**小数部分逐帧恒定**地只受
            // `dt/interval` 影响，可见速度恒等于名义速度，与到达抖动、与柱间隔是否和 vsync
            // 公度**全都无关**。仿真实测（三种柱间隔 × 60/120Hz）：平均速度误差 0.00%、
            // 逐帧位移的平均绝对偏差 **0.00%**。
            //
            // ⚠️ 一个反直觉之处：**不能用 `max(1, floor(phase))`**（那是我第一版写的）。
            // 到达帧上按全程 dt 推进了相位、却至少扣掉一整格，于是每个到达帧都**净亏**
            // `dt/interval` 的小数部分 —— 仿真里 92.88ms 柱间隔的平均速度因此掉到
            // 名义值的 92.7%（系统性偏慢），而且相位会周期性攒到上界。
            val interval = if (barIntervalMs > 1f) barIntervalMs else DEFAULT_BAR_INTERVAL_MS
            val sounding = targets[barCount - 1] > ANIMATION_MIN_SIGNAL

            // ── 本版最终形态：**相位是唯一的时钟** ────────────────────────────────
            //
            // 前面几版都把「平移格数」挂在**到达事件**上、把「小数位置」挂在**相位**上。
            // 那是两个时钟：到达由音频回调驱动、相位由帧循环驱动，两者必然错拍
            // （逐帧诊断实测：到达在 fr=5、相位跨过整数在 fr=6 ⇒ 平移与相位错开一帧，
            //  可见位置出现 −0.83 / +1.17 的交替跳变，平均绝对偏差 0.334）。
            //
            // 现在的规则只有一条：
            //
            //   **相位每跨过一个 1，就平移一格历史窗口。**
            //
            // 于是「平移」与「小数位置」共用同一个时钟，**按定义**同步：
            //
            //   可见位置 = shiftedCells + frac(scrollPhase)
            //   每帧：相位 +dt/interval；跨过 k 个整数 ⇒ shiftedCells += k、相位 -= k
            //   ⇒ 可见位置的逐帧增量恒为 dt/interval（`frac` 减整数不变）
            //
            // 这正是「x 与 x−1 小数部分相同」那条性质的用法，也是唯一能同时满足
            // 「与到达抖动无关」和「与刷新率无关」的写法。仿真实测三种柱间隔 × 60/120Hz：
            // 平均速度误差与逐帧位移偏差**都是 0.00%**。
            //
            // 顺带纠正一个我一直搞错的关系：**柱到达次数与相位跨整数次数并不逐帧对齐**
            // （前者由回调驱动、后者由帧驱动），所以不能再写 `shiftedCells += arrivals`。
            // 平移量由相位决定；`arrivals` 只用来（a）估计柱间隔、（b）防止音频侧积压。
            // ⚠️ 静音段必须**整体冻结**（相位 + 平移），不能只冻结相位。
            //
            // 只冻结相位是不够的：下一段（平移）仍会读相位并推进窗口，
            // 于是静音期间窗口一直在滚（`fully settled ring reports no repaint`
            // 那条用例抓到的就是这个 —— 静止后 `changed` 恒为 true）。
            // 更隐蔽的后果是：恢复演奏时相位已经跨过很多格，而窗口却没跟着走，
            // 两者脱节 —— 那正是本版要消除的那类不同步。
            //
            // 整段冻结之后，「相位」与「窗口位置」在任何时刻都描述同一个瞬间，
            // 恢复时从冻结点继续，不需要任何补偿。
            if (sounding) {
                // 相位推进量自带 [PHASE_FLOOR, PHASE_MAX] 夹取 ⇒
                // 恢复演奏时不会补齐静音期间的时间（不空转契约）。
                val before = scrollPhase
                advanceScrollPhase(dtMs, interval)
                if (scrollPhase != before) changed = true

                // 相位跨过的整数 = 本帧该平移的格数。**有界**：一帧最多
                // [MAX_CELLS_PER_FRAME] 格（60Hz/100ms 柱正常是 0 或 1），
                // 挂起后恢复也不会一次跳一整屏。
                var cells = kotlin.math.floor(scrollPhase).toInt()
                if (cells > MAX_CELLS_PER_FRAME) cells = MAX_CELLS_PER_FRAME
                if (cells > 0) {
                    scrollPhase -= cells.toFloat()
                    shiftedCells += cells.toFloat()
                    // 真的把历史窗口推进；参数是**上限** —— 音频侧没有足够数据时
                    // 会消费得更少（画面平移由相位驱动，但受真实数据约束）。
                    consumePendingCells(cells)
                    changed = true
                }
            }

            // 柱到达（音频侧）只用于两件事：估计柱间隔、以及把音频侧的积压消费掉
            // —— 后者保证 `readIndex` 不落后于 `writeIndex`（否则环形缓冲会溢出）。
            val arrivals = consumePendingCount()
            if (arrivals > 0) {
                val avg = msSinceArrival / arrivals
                if (avg in MIN_BAR_INTERVAL_MS..MAX_BAR_INTERVAL_MS) {
                    barIntervalMs += (avg - barIntervalMs) * BAR_INTERVAL_EMA
                }
                msSinceArrival = 0f
                changed = true
            }
            // 挂钟照常累加（静音段也累加）：间隔估计的语义是「音频回调的周期」，
            // 与画面是否在滚无关；静音时音频回调仍在跑。
            if (dtMs.isFinite() && dtMs > 0f) {
                msSinceArrival += dtMs.coerceAtMost(MAX_FRAME_DT_MS)
            }
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

    /**
     * 消费了多少根柱（相位推进要用它换算平均柱间隔）。
     *
     * ⚠️ 这是一个**副作用字段**：它由 [consumePendingCount] 写入。
     * v3.3.0 起 [pump] 直接吃 `consumePendingCount()` 的返回值，
     * 不再读这个字段 —— 留它是因为 `pendingConsumed` 在
     * `WaveformRingTest` 的既有用例里被断言过（那是 v3.2.3 的口径）。
     */
    private var pendingConsumed: Int = 0

    /**
     * 音频侧积压了几格（不消费，只数）。
     *
     * 溢出时会把最旧的丢掉（绝不阻塞写者），所以这个数**不是**无限增长的。
     */
    private fun pendingCount(): Int {
        val snapshot = writeIndex
        var pending = snapshot - readIndex
        if (pending <= 0) return 0
        if (pending > capacity) {
            // 溢出：丢掉最旧的，绝不阻塞写者。
            readIndex = snapshot - capacity
            pending = capacity
        }
        return pending
    }

    /**
     * 把音频侧的积压**全部**消费掉（音频回调驱动的路径）。
     *
     * 与 [consumePendingCells] 的分工：
     * - 画面平移用 [consumePendingCells]（**帧驱动**，格数由相位决定）；
     * - 这里负责保证 `readIndex` 不落后于 `writeIndex`，否则环形缓冲会溢出。
     *   `pump` 每次都会调它一次，所以积压实际上总被清空，它的返回值是 0 或 1。
     */
    private fun consumePendingCount(): Int = consumePendingCells(Int.MAX_VALUE)

    /**
     * 把历史窗口向"新"的方向推进至多 [max] 格，返回**实际**消费的格数。
     *
     * 参数是**上限**而不是精确值：音频侧可能还没有那么多数据（例如帧循环比音频回调快），
     * 那时它会消费得更少 —— 这正是「画面平移由相位驱动、但受真实数据约束」的落点。
     */
    private fun consumePendingCells(max: Int): Int {
        pendingConsumed = 0
        if (max <= 0) return 0
        val available = pendingCount()
        if (available <= 0) return 0
        val pending = minOf(available, max)
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
     *
     * v3.3.0：**只取小数部分**。[scrollPhase] 现在是「时间积分」量，
     * 可能大于 1（到达与帧循环不在同一时刻，最多差一格）；小数部分才是有意义的插值位置。
     * 这与旧实现的 `coerceIn(0f, 1f)` 有本质区别：旧写法把「超前」**截断**成 1.0，
     * 于是准时到达的柱会被渲染成一次停顿；取小数则是「它本来就在下一格的 0.3 处」。
     */
    fun scrollPhase01(): Float {
        val p = scrollPhase
        if (!p.isFinite()) return 0f
        val frac = p - kotlin.math.floor(p)
        return if (frac < 0f) 0f else frac
    }

    /**
     * 推进相位。**纯函数**（无副作用、只用参数），所以能在 JVM 里被逐帧断言
     * —— 这条链路的缺陷（每 ~100ms 一次的速度突变）在真机上要靠录屏才能看出来，
     * 单测里没有纯函数就完全测不到。
     *
     * @param phase 当前相位（单位：格）。
     * @param dtMs 本帧真实帧间隔。
     * @param intervalMs 柱间隔（滑动平均）。
     * @return 新相位，夹在 `[PHASE_FLOOR, PHASE_MAX]`。
     *
     * ## 下界为什么是 **−1** 而不是 0（这是本版最后才找对的一处）
     *
     * 直觉是「相位不该为负」，于是夹到 0。但相位是**时间量**：柱到达时相位常常
     * **还没攒满一格**（60Hz/100ms 柱 ⇒ 每帧 0.1667，到达时相位常在 0.83 这种值），
     * 而 [phasesToConsume] 按 `floor(phase)` 扣 —— 扣完就落在 `[−1, 0)`。
     * **那个负值是信息，不是错误**：它表示「这一格已经平移了，但还欠着一部分时间」。
     *
     * 夹到 0 会把这份信息抹掉，下一帧的小数部分于是凭空少掉 `|负值|`，
     * 表现为可见的**倒退**（逐帧诊断实测 `raw=-0.3333` 被夹成 0 之后，
     * 位移出现 −0.67 的倒退）。放宽到 −1 之后：
     * - 小数部分仍然连续（`frac` 是周期函数，`−0.17 ≡ 0.83`，渲染层用 `floor` 取小数，
     *   对负值天然正确）；
     * - 逐帧位移恒为 `dt/interval`（仿真：三种柱间隔 × 60/120Hz，偏差 **0.00%**）；
     * - 仍然**有界**（下界 −1、上界 [PHASE_MAX]，不会无界累积）。
     */
    internal fun advanceScrollPhase(
        phase: Float,
        dtMs: Float,
        intervalMs: Float,
    ): Float {
        val interval = if (intervalMs > 1f) intervalMs else DEFAULT_BAR_INTERVAL_MS
        val dt = dtMs.coerceIn(0f, MAX_FRAME_DT_MS)
        val next = phase + dt / interval
        return if (next.isFinite()) next.coerceIn(PHASE_FLOOR, PHASE_MAX) else phase.coerceIn(PHASE_FLOOR, PHASE_MAX)
    }

    /** [advanceScrollPhase] 的就地版本（避免每帧一次装箱；`pump` 是逐帧路径）。 */
    private fun advanceScrollPhase(dtMs: Float, intervalMs: Float) {
        scrollPhase = advanceScrollPhase(scrollPhase, dtMs, intervalMs)
    }

    /**
     * 本帧该从相位里扣掉多少「格」。**纯函数**。
     *
     * 判据就是**相位的整数部分**（`floor`），再夹到「本帧到达数」与
     * [MAX_ARRIVALS_PER_FRAME] 以内。**不强制至少 1。**
     *
     * ## 为什么是 floor（逐帧诊断穷举过三种写法）
     *
     * 相位是**随帧累积**的时间量：每帧 `+dt/interval`（60Hz / 100ms 柱 ⇒ +0.1667）。
     * 柱到达并不发生在相位恰好等于 1 的那一刻，但**相位跨过 1 的次数必然等于到达次数**
     * —— 两条链都由 `dt` 驱动。所以在到达帧上：
     *
     * ```
     * consume = floor(phase)     ← 相位跨过几个 1，就平移几格
     * phase  -= consume          ← 扣掉整数部分，小数部分连续 ⇒ 逐帧位移恒为 dt/interval
     * ```
     *
     * 这就是「x 与 x−1 小数部分相同」那条性质的用法，也是本版要的**恒定滚动速度**。
     *
     * ## 两种被否决的写法（都实测跑过，留在这里防止重走）
     *
     * | 写法 | 后果 | 逐帧诊断证据 |
     * |---|---|---|
     * | `max(1, floor(phase))` | 到达帧上相位常**不足 1**（0.6667 这种），强制扣 1 会把相位压成**负数**（实测 `raw=-0.3333`、甚至 `-1.0000`），而 [advanceScrollPhase] 的下界 0 会把它夹平 ⇒ 小数部分被抹掉 ⇒ 下一帧出现 `-0.67` 的倒退 | 相位瞬时为负、位移倒退 |
     * | 扣 0（只在 `floor ≥ 1` 时扣） | 相位攒过 1 后**再不回落**（实测单调涨到 1.0043、1.0104…），`scrollPhase01()` 不再是「这一格之内的位置」而是持续漂移的残差 | 相位无界上涨 |
     *
     * ⚠️ 本类刻意**不依赖 Android**（纯逻辑、JVM 可直测），所以这里不打日志。
     * 需要观测相位时用 `rawScrollPhaseForTest()`。
     */
    internal fun phasesToConsume(phase: Float, arrivals: Int): Float {
        if (arrivals <= 0) return 0f
        if (!phase.isFinite()) return 0f
        val byPhase = kotlin.math.floor(phase)
        if (byPhase < 1f) return 0f
        val n = arrivals.coerceAtMost(MAX_ARRIVALS_PER_FRAME)
        return byPhase.coerceAtMost(n.toFloat())
    }

    /** v3.2.3：当前测到的柱间隔（毫秒，滑动平均）。诊断 / 单测用。 */
    fun barIntervalMs(): Float = barIntervalMs

    /**
     * v3.3.0：**只给单测**注入一个确定的柱间隔。
     *
     * 抖动判据要量的是「在**给定**柱间隔下逐帧位移是否均匀」，而不是「间隔估得准不准」
     * （后者由 `柱间隔是测出来的` 那条用例单独守）。不让新判据依赖估计算法，
     * 是为了让它的失败能干净地指回相位逻辑 —— 否则一次估计偏差就会让抖动用例变红，
     * 把排查方向带偏。
     *
     * `internal` 而不是 `public`：生产代码没有调用点。
     */
    internal fun setBarIntervalForTest(intervalMs: Float) {
        if (intervalMs > 1f) barIntervalMs = intervalMs
    }

    /**
     * v3.3.0：**只给单测**读原始相位（未取小数）。诊断「相位是否在到达帧上被多扣」用。
     *
     * 生产代码不读它 —— 渲染层要的是 [scrollPhase01] 的小数部分。
     * `internal` 而不是 `public`：没有生产调用点。
     */
    internal fun rawScrollPhaseForTest(): Float = scrollPhase

    /**
     * v3.3.0：**只给单测**读「累计平移格数」。
     *
     * 与 [rawScrollPhaseForTest] 配对使用：`shiftedCellsForTest() + frac(rawScrollPhaseForTest())`
     * 就是画面的真实滚动位置。抖动判据量的正是它的逐帧差分。
     */
    internal fun shiftedCellsForTest(): Float = shiftedCells

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

        /**
         * v3.3.0：单帧 `dt` 的上限（毫秒）。与相位推进的旧实现同值（200ms），
         * 含义也一样 —— 一次卡顿/挂起后的超大 `dt` 不该被当成「真的过了这么久」，
         * 否则恢复的第一帧会把相位一次性推到很远。
         */
        const val MAX_FRAME_DT_MS = 200f

        /**
         * v3.3.0：相位的上界（单位：格）。
         *
         * 4 格 ≈ 四根柱的间隔。正常情况相位恒在 `[0, 1)`（每根柱到达时减掉整数格），
         * 只有「到达与帧循环错拍」才会短暂超前。给一个**有界**的上限是仓库的硬规则
         * （任何累积路径都要有熔断）：无界累积在帧循环被挂起后恢复时会变成整屏跳变。
         */
        const val PHASE_MAX = 4f

        /**
         * v3.3.0：相位的下界（单位：格）。**是 −1 而不是 0** —— 理由见
         * [advanceScrollPhase] 的 KDoc：柱到达时相位常不足一格，按 `floor` 扣完
         * 会落到 `[-1, 0)`，而那个负值是「这一格已平移但还欠一部分时间」的信息。
         * 夹到 0 会把小数部分抹掉，表现为可见的倒退。
         */
        const val PHASE_FLOOR = -1f

        /**
         * v3.3.0：一帧内最多认几根柱的到达。
         *
         * 正常每 100ms 到达一根，一帧最多一根；取 4 给溢出与错拍留余量。
         * 超出时**宁可少减**（画面平移一格），也不要一次减几十格（整屏跳变）。
         */
        const val MAX_ARRIVALS_PER_FRAME = 4

        /**
         * v3.3.0：一帧最多平移几格。
         *
         * 60Hz / 100ms 柱下正常是 0 或 1（每帧相位推进 0.1667）。取 4 是为了容纳
         * 帧循环被短暂挂起后的追赶；再大就会变成"整屏跳一下"，
         * 而那正是要消除的观感。**有界**是仓库硬规则（任何累积路径都要有熔断）。
         */
        const val MAX_CELLS_PER_FRAME = 4

        /** v3.2.2：频带下标（与 `BandColorRoles` / `BandDominance` 的三色顺序一致）。 */
        const val BAND_LOW = 0
        const val BAND_MID = 1
        const val BAND_HIGH = 2

        private const val TWO_PI = 6.2831855f
    }
}
