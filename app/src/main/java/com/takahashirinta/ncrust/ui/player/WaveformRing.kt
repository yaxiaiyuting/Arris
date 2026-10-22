/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.ui.player.waveform.BandBallistics
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerEffects

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
 *  - [bars] = 画面值，由一个**二阶系统（弹簧-阻尼）**推进：起音快（[ATTACK_TAU_MS] 的包络，
 *    跟得上鼓点）、回落慢（[RELEASE_TAU_MS]，像 VU 表一样自然衰减），
 *    并且**有惯性、有轻微过冲**（ζ = 0.75 ⇒ 过冲 2.8%）。
 *
 * **v3.4.5：指数逼近（`k = 1 - exp(-dt/tau)`）已换成弹簧-阻尼的闭式解** ——
 * 指数逼近永远不会越冲、也永远不会回弹，用户读到的就是「小球没有弹起来的感觉」。
 * 闭式解同样是"与刷新率无关"的（而且比时间常数形式更强：对任意 dt **精确**，
 * 不是"误差很小"）。推导与参数依据见 [BandBallistics] 的 KDoc。
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
 * ## ⚠️ v3.6.3：**先读这一节** —— 滚动模型是「波形自己的播放队列」
 *
 * 用户 v3.6.2 的反馈是「一阵子动一下一阵子停止」。根因不是刷新率、也不是插值：
 * 真实音频链路会把**多根柱放进同一次回调**（同一个 `uptimeMillis()`），而 v3.4.7 的模型
 * 有两个隐含前提 —— 窗口一帧最多平移一格、相位按「距最后一根柱的到达时刻」算。
 * 成批到达把两个前提都破了：
 *
 * ```
 * 3 根/300ms（60Hz）：8 帧位移 0.0000 → 一帧 2.00 格 → 0.05 … → 再 8 帧 0.0000
 * 2 根/200ms（60Hz）：3 帧位移 0.0000 → 一帧 1.08 格 → 0.08 …
 * ```
 *
 * 现在的模型（见 [sinceBarMs] / [consumeOne] / [recordBatch]）：
 *
 *  1. **每根柱的间隔按批折算**：`(最新批起点 − 最早批起点) ÷ 中间各批柱数之和`。
 *     3 根/300ms 读出 100ms/根（不是 300ms，也不是 50ms），由 [recordBatch] 发布。
 *  2. **画面按时间铺开**：`sinceBarMs` 每帧只加真实帧间隔；跨过一个格周期才消费**一根**。
 *     一批 3 根会在 3 个格周期里被逐根取走 ⇒ 0.16~0.17 格/帧、0 停顿帧。
 *  3. **重绘门槛看整个窗口**，不看最新一格：一个安静缓冲落在最右格时，左边还有内容在滚动，
 *     旧判据会停止重绘 1~3 帧（另一种「顿住」）。
 *
 * ### 不要退回去的四件事
 *
 *  - 不要把「画面时间」与柱的到达时间戳相减：成批到达时两者不是一一对应的。
 *  - 不要在一帧里消费所有 pending：那正是「一帧冲 N 格、然后等 N 格」。
 *  - 不要用「最新一根的间隔」当分母：它是回调间隔，不是每根柱的时间。
 *  - 不要只看 `targets[barCount-1]` 决定要不要重绘（见 [windowHasSignal]）。
 *
 * 下面两节（v3.3.2 / v3.4.7）保留为历史：它们修的是「一根柱一格」稳态下的分母量化与
 * 跨时钟相减，那些结论仍然成立；只是**相位不再锚在到达时刻**上。
 */
/**
 * ## ⚠️ v3.3.2：滚动抖动的**真实机制**（历史模型；生产代码已改为上面的播放队列）
 *
 * 前六次尝试全部失败，根因是**判据口径**与**归因**都错了。这一节记录的是
 * 用「与渲染层同源的口径」（`位移 = Δ窗口平移格 + ΔscrollPhase01()`）量出来的事实，
 * 不是推理。所有数字都可由 `WaveformScrollJitterDiagTest` 复现。
 *
 * ### 症状与量到的形状
 *
 * 用户：「波形抖动还是老问题」/「新的播放条也还在抖动，这次甚至会抽搐」。
 * 60Hz 面板、柱间隔 100ms 时，逐帧可见位移长这样（单位 = 格，名义步长 0.1667）：
 *
 * ```
 * 0.1667 ×5 帧 → 0.0000 → 0.1391 → 0.0000 → 1.1399 → 0.4903 …
 * ```
 *
 * 也就是**每 ~100ms 一轮**：先停住 1~4 帧（位移 0.0000），再一次性冲出 1.1 格（名义的 660%）。
 * 这就是「顿-冲」，也就是用户说的「抽搐」。
 *
 * ### 机制（两个缺陷叠加，**都不是**「到达时清零」本身）
 *
 * 1. **相位斜坡的分母被 vsync 量化，永远收敛不到真值 —— 这是主因。**
 *    分母（柱间隔）原本在 **UI 消费处**倒推：`sinceBarMs / 消费根数`。而 UI 消费只能发生在
 *    帧上，于是一根真实 100ms 的柱被量成 **100 / 116.7 / 133.3ms** 三者之一，
 *    滑动平均因此**永远收敛不到真值** —— 同口径实测 20 秒里从 100ms 一路爬到 **155ms**
 *    且没有停下来的意思。分母偏大 ⇒ 相位按 0.65 倍速爬 ⇒ **爬满一格之前下一根柱就到了**。
 * 2. **于是相位撞上 `scrollPhase01()` 的 `coerceIn(0, 1)` 顶棚，停在 1.0 上不动**，
 *    连续几帧位移 `0.0000`；下一根柱到达时相位被折回，位移一次性冲出 1.1 格。
 *    旧代码在到达时把 `sinceBarMs` **清零**（而不是减整数）又放大了这一下。
 *
 * `barIntervalMs` 就是那个爬飞的分母 —— 它是本缺陷的**指纹**：
 * 只要它在真机上单调爬升，画面就一定在顿-冲。
 *
 * ### 修法（v3.3.2 的三条 + v3.4.7 修正的第 2 条）
 *
 * | # | 改动 | 为什么必须 |
 * |---|---|---|
 * | 1 | 分母改在 **push（音频线程）处**测：每根柱带一个 `arrivalAtMs` | 那是音频缓冲**自己的节拍**，与 vsync 无关 ⇒ 没有量化，收敛到真值（实测钉在 100.00ms） |
 * | 2 | 消费时把相位**锚到那根柱的到达时刻** | 帧时间轴与到达时间轴之间那一帧的相位误差被一次性算掉，不会逐轮累积。⚠️ **v3.3.2 的实现是错的**（`frameClockMs − clockOffsetMs − pendingArrivalMs`：跨时钟相减），见下一节 |
 * | 3 | 折回**小数余量**而不是清零；且推进**不再受信号门槛控制** | 清零会丢掉「这一帧多出来的位移」；门槛挂在原始柱高上，真实音乐里单根安静缓冲就会让相位冻住 5 帧、再冲出 1 格 |
 *
 * 信号门槛（[ANIMATION_MIN_SIGNAL]）**保留**，但只管「要不要为这一帧排重绘」——
 * v1.8.1 的「不空转」契约与暂停即停帧契约一行没改。
 *
 * ### ⚠️⚠️ v3.4.7：第 2 条**当时是错的**，这才是「改完照抖」的根因
 *
 * 上面那一版的锚点式子是 `frameClockMs − clockOffsetMs − pendingArrivalMs`，其中
 * `frameClockMs` 是**本类自己按 `dt` 累加**的帧时钟，而且逐帧 `+= dtForPhase.toLong()`。
 * 于是它拿**两个不同的时钟**相减：一个自攒的（每帧被截断 0.33~0.67ms ⇒ **4%/秒**的系统性
 * 偏慢），一个是音频线程的 `SystemClock.uptimeMillis()`。差值单调跑负 ⇒ 相位被钉在 0 或
 * 顶棚上跳。真机探针量到的指纹是 `sinceBarMs` 只在 `{0.00, 41.18}` 两个值之间跳、
 * 相位每 5 帧走 `0.206 × 5`、第 6 帧被覆盖回 0 —— **每格丢一帧的位移**。
 *
 * 当时还有一条**反向的**错误归因把它盖住了：把 `.toLong()` 改成四舍五入会让两条判据变红，
 * 于是它被当成「有意的补偿」写进了注释。真相是：**向下取整与「分母偏大」这两处偏差
 * 在互相抵消一部分**，谁单独改都会让另一处的偏差露出来。两处一起改（分母在到达处测、
 * 帧时钟换成真实时间戳）才是同一个模型。
 *
 * 现在：**本类不再攒任何时钟**。[pump] 收的 `nowMs` 就是帧循环那一帧的时间戳
 * （`withFrameNanos` 的 `frameTimeNanos / 1e6`，与 `SystemClock.uptimeMillis()` 同一个
 * 单调时钟基准），相位 = `nowMs − 最后一根被消费的柱的到达时刻`，两个量同源、都不累加。
 *
 * **判据也一起改了**：到达帧上相位本来就是「这一帧比那根柱晚了多少」这个**小正数**，
 * 所以「到达帧位移 = 名义步长」是可判定的（旧模型在这一帧量到 `0.0000`）。
 * 这一条由 `WaveformScrollJitterRegressionTest` 与真机探针 `WaveformVsyncProbeTest` 同时钉住。
 *
 * ### 改前 / 改后（同一台仿真、同一口径、时间相邻）
 *
 * | 场景 | 改前抖动率 | 改后抖动率 | 改前顿帧 | 改后顿帧 |
 * |---|---|---|---|---|
 * | 理想帧 + 理想柱 | 6.09% | 6.09% | 2 | 2 |
 * | 仅柱与 vsync 错开 | 0.00% | 0.00% | 0 | 0 |
 * | 仅帧时间抖动 | 14.34% | 14.34% | 2 | 2 |
 * | 帧抖 + 柱抖 | 22.20% | 22.20% | 22 | 22 |
 * | **真实音乐幅度（每 20 柱一根安静缓冲）** | **50.00%** | **0.00%** | **45** | **0** |
 * | **真实音乐幅度（每 8 柱一根安静缓冲）** | **79.87%** | **0.00%** | **114** | **0** |
 *
 * 读法：**「帧时间抖动」那一列的贡献是零**（改前改后完全相同）——它本身不是缺陷，
 * 位移本来就该正比于 `dt`（匀速运动在帧时间上就是不等步长的）。
 * 抖动全部来自上面那两条被修掉的缺陷。
 *
 * ### 教训（写给下一个改这里的人）
 *
 * - **判据必须直接读渲染层消费的那两个量**（[scrollPhase01] 与窗口平移格数）。
 *   前三次失败都是因为在测试里另建了「可见位置」的定义：只量相位差分 /
 *   把平移硬编码成 +1 / `shiftedCells + frac(phase)`。仿真因此可以一路绿灯而真机照抖。
 * - **`[barIntervalMs]` 是诊断指纹**：它会爬，就说明分母又被量化了。
 * - 帧时间抖动（真机 `dt` 在 15~20ms 跳）**不是**缺陷，不要去平滑它 ——
 *   平滑帧时间等于篡改速度，只会把匀速运动变成忽快忽慢。
 * - **两个时钟相减之前，先问它们是不是同一个时钟。** 「看起来都是 uptime 量级」不够：
 *   只要其中一个是自己攒的，它就和对方不是同一个时钟（v3.4.7 的根因）。
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
     * v3.3.2 / v3.6.3：与 [ring] 逐槽对齐的**到达时刻**（调用方的时间基准，毫秒；0 = 没给）。
     *
     * ## 为什么柱间隔必须在 push 这一侧测
     *
     * 旧写法在 **UI 消费处**倒推间隔（`sinceBarMs / 消费根数`），而消费只能发生在帧上：
     * 一根真实 100ms 的柱会被量成 100 / 116.7 / 133.3ms 三者之一（60Hz），
     * 滑动平均因此**永远收敛不到真值** —— 真机同口径实测从 100ms 一路爬到 155ms 且不停。
     * 在 push 处测的是**音频缓冲自己的节拍**，与 vsync 无关。
     *
     * v3.6.3 又补了一层：真实链路会把多根柱放进**同一次回调**（同一个时间戳）。
     * 按「相邻两根」测会得到 `0, 0, 300ms`，把每根 100ms 读成 300ms ⇒ 画面一顿一冲。
     * 现在按批折算（见 [batchStarts] / [batchSizes]），并把结果发布给 [publishedGapMs]。
     *
     * ## 代价（音频线程）
     *
     * 两次数组写（stamp、gap 不需要逐槽了，只发布标量）与两次 volatile 写，
     * **没有分配、没有锁、没有系统调用** —— 与既有的四个 float 写同级。
     * 时间戳由调用方传入（`SystemClock.uptimeMillis()`），所以本类仍然不依赖 Android。
     */
    private val arriveStampMs = LongArray(capacity)

    /**
     * v3.6.3：**已经按每根柱折算过**的到达间隔（毫秒），音频线程写、UI 线程读。
     *
     * 为什么要一个单独的发布通道，而不是只在消费柱时更新：
     * [barIntervalMs] 是画面时间的节拍。如果它只在「消费了一根」时更新，一个偏小的估计
     * 会让画面消费得比音频到达快 ⇒ 很快没有柱可消费 ⇒ 帧停在格边界 ⇒ 估计没机会被修正。
     * 现在音频线程每测到一个新样本就发布一次（值 + 自增序号），UI 每帧最多吸收一个新样本，
     * 与消费节奏无关。
     */
    @Volatile
    private var publishedGapMs: Float = 0f

    /** 与 [publishedGapMs] 配套的序号：**最后写**，读者看到新序号时值一定已写好。 */
    @Volatile
    private var publishedGapSeq: Long = 0L

    /** UI 线程已经吸收到的 [publishedGapSeq]（避免同一个样本被平滑多次）。 */
    private var appliedGapSeq: Long = 0L

    /** 已吸收的间隔样本数（前几个直接采纳，见 [absorbPublishedGap]）。 */
    private var gapSamples: Int = 0

    /**
     * v3.6.3：最近 [GAP_BATCHES] 个**到达批次**的起点（音频线程独占读写）。
     *
     * 为什么要按「批」而不是按「根」记：真实链路会把多根柱放进同一次回调（同一个时间戳）。
     * 按相邻两根测会得到 `0, 0, 300ms`；只按上一批柱数折算，又会在批次大小变化时失真。
     * 记下最近 8 批的「起点 + 柱数」，就能算
     * `(最新批起点 − 最早批起点) ÷ 中间各批柱数之和` —— 这是**每根柱真正占用的时间**，
     * 对「每批 1 根」「每批 3 根」「批大小变化」都成立。
     */
    private val batchStarts = LongArray(GAP_BATCHES)

    /** 与 [batchStarts] 对齐：这一批里有几根柱。 */
    private val batchSizes = IntArray(GAP_BATCHES)

    /** [batchStarts] 里有效批次数（≤ [GAP_BATCHES]）。 */
    private var batchCount: Int = 0

    /** 下一批写入 [batchStarts] 的下标（环）。 */
    private var batchNext: Int = 0

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
     * v3.4.7：相位斜坡**实际使用的分母**（毫秒）—— 诊断 / 单测 / 真机探针用（生产渲染路径零调用）。
     *
     * v3.6.3 起分母只有一个来源（[barIntervalMs]，由 [recordBatch] 按批折算后平滑）。
     * 探针仍必须读**被判据实际消费的那个量**：中间还有 `isFinite` / `> 1f` 的回落，
     * 读发布值或读默认值都可能把「分母不对」读成「相位不对」。
     */
    internal fun phaseIntervalMsForTest(): Float = phaseIntervalMs()

    /**
     * v3.2.3 / v3.6.3：**画面滚动累计量**（毫秒，区间 `[0, 柱间隔]`）。
     *
     * ## 为什么不是「这一帧比最后一根柱晚了多久」
     *
     * v3.4.7 把相位改成 `nowMs − 最后一根被消费的柱的到达时刻`，解决了「自攒帧时钟跨时钟相减」
     * 的漂移。但它仍有一个前提：**一根柱必须在一格时间内只到达一根**。一旦音频链路把多根柱
     * 放进同一次回调（或 UI 一帧里消费了一批），这个前提就破了：
     *
     *  - 窗口在一帧里平移 N 格（N = 这一批的柱数），而相位只按「距最后一根」算 ⇒ 画面一次冲 N 格；
     *  - 下一批要等 N×柱间隔才到 ⇒ 相位先撞顶棚、画面**停住**，再重复上面那一步。
     *
     * 这就是用户在 v3.6.2 报的「一阵子动一下一阵子停止」。同一份仿真里，3 根柱 / 300ms 的批
     * 在 60Hz 上量到 **8 帧位移 0.00 + 一帧 2.00 格**；按时间铺开后是 0.16~0.17 格/帧。
     *
     * ## 现在的模型：波形自己的**播放队列**（playout）
     *
     * [sinceBarMs] 是**画面时间**，每帧只按真实帧间隔推进（[pump] 的 `nowMs` 差，或回落 `dtMs`）。
     * 它跨过一格（[phaseIntervalMs]）时才**消费一根柱**、窗口平移一格、再减去一格：
     *
     * ```
     * sinceBarMs += 本帧真实经过的时间
     * if (sinceBarMs >= 间隔 && 还有待消费的柱) { 消费一根; 窗口左移一格; sinceBarMs -= 间隔 }
     * else if (sinceBarMs >= 间隔) sinceBarMs = 间隔   // 没有数据：停在格边界等
     * ```
     *
     * 两个后果，都是想要的：
     *
     *  1. **成批到达被摊开**：一批 3 根柱会在 3 个格周期里各消费一根，画面匀速；
     *  2. **早到/晚到不再改变位移**：数据早到就在环里等，晚到就让画面在格边界停一下
     *     （那是「真的没有下一格」的诚实表达，而不是跳一下补回来）。
     *
     * ⚠️ 这**不是**把 v3.4.7 的修复退回去：这里只有一根本类自己攒的**时间量**，它从不与
     * 音频线程的到达时间戳相减；时间量用 Float 累加，也没有 `.toLong()` 的逐帧截断。
     * 到达时间戳现在只用来测「一根柱代表多久」（[batchStarts]），不参与相位计算。
     */
    private var sinceBarMs: Float = 0f

    /**
     * v3.6.3：上一帧的时间戳（毫秒，与柱到达同一时钟；0 = 还没收到帧时钟）。
     *
     * 只用来算「这一帧真实经过了多久」。它每帧被**覆盖**而不是累加，所以不会漂。
     * 传 0（单测 / 老调用点）时回落到调用方给的 `dtMs`。
     */
    private var lastPumpNowMs: Long = 0L

    /** v3.6.3：首帧是否已经用环里的历史填过窗口（见 [pump] 的说明）。 */
    private var windowPrimed: Boolean = false

    /**
     * 柱间隔的滑动平均（毫秒）。硬件缓冲粒度是运行时行为，所以只能测、不能假设。
     *
     * v3.3.2：它就是相位斜坡的**分母**，而且只在**到达处**测量（见 [arrivalMs]）。
     * 初值 [DEFAULT_BAR_INTERVAL_MS] 在第一次测到真实间隔之前充当分母 —— 若真值是 96ms，
     * 头几百毫秒的相位会略微偏慢（0.96 倍速），EMA（α=0.15，约 7 根柱收敛）随后把它拉正。
     */
    private var barIntervalMs: Float = DEFAULT_BAR_INTERVAL_MS

    /** 最新数据（阶跃）。UI 线程原地更新，绝不重新分配。 */
    private val targets = FloatArray(barCount)

    /** v2.9.0：与 [targets] 对齐的**低频**最新值（节拍判据用）。 */
    private var bassTarget = 0f

    /** v3.2.2：与 [targets] 对齐的三条**频带历史窗口**（三条泳道的画面值来源）。 */
    private val lowTargets = FloatArray(barCount)
    private val midTargets = FloatArray(barCount)
    private val highTargets = FloatArray(barCount)

    /**
     * v3.4.4：三条泳道的**画面值**窗口 —— 就是 [lowTargets] / [midTargets] / [highTargets]
     * 经过 [ATTACK_TAU_MS] / [RELEASE_TAU_MS] 弹道平滑之后的结果。
     *
     * ## 为什么必须有这一层（这是「小球和横线没有弹起来的物理感觉」的唯一根因）
     *
     * v3.2.2 引入三泳道时，几何取值直接从**原始**窗口 `*Targets[i]` 算：
     * `BandScroll.fillLane` 读的是 `lowTargets/midTargets/highTargets`，
     * 而 `AudioVisualizer` 的三泳道分支画的就是它。于是 [bars] 那一层的
     * 起音 22ms / 回落 130ms **整条被绕过了** —— 三泳道的画面值与音频缓冲**逐格相等**：
     *
     *  - 一根新柱到达 ⇒ 该格高度**瞬间**跳到新值（没有起音，也没有回落尾巴）；
     *  - 柱间隔 82.6ms（真机 PCM 缓冲实测）⇒ 画面是一条 **12.1Hz 的阶梯**。
     *
     * 用户读到的就是「小球（`heights[i]` 画在色带顶边上的圆点）与横线（色带顶边包络）
     * 没有真实的弹起来的物理感觉」，而且是「一口一口拼上去 / 吃回去」——
     * 因为每一格都是**瞬时**赋值：涨是硬跳、落也是硬跳。
     *
     * 单条曲线那条老路径一直是有弹道的（`heights[i] = sqrt(bars[i]) * …`，`bars` 带 22/130ms），
     * 所以这是三泳道引入的**回归**，不是新需求。
     *
     * ## 为什么是「先同步平移、再平滑」而不是「直接对旧值做平滑」
     *
     * 平移与平滑**必须分开**，否则会把 v3.4.1 修掉的时域模糊又请回来：
     * [shiftBandIn] 把**两个数组一起**平移一格（画面值跟着原始窗口走，形状是刚性平移），
     * [approach] 只在这个已经平移过的坐标系里做逐格弹道。
     * 两步都对「格」是平移不变的 ⇒ 整条轨迹 == 未平移轨迹的刚性平移，
     * 不会跨格混合（v3.4.1 的「峰尖度波动 90%」正是跨格混合造成的）。
     *
     * 代价：3 × `barCount` 个 float 与每帧 3 × `barCount` 次乘加，**零分配**。
     */
    private val lowBars = FloatArray(barCount)
    private val midBars = FloatArray(barCount)
    private val highBars = FloatArray(barCount)

    /** 画面上的柱子高度（0..1）= 平滑后的值。UI 线程原地更新。 */
    private val bars = FloatArray(barCount)

    /**
     * v3.4.5：柱高的**速度**（归一化高度/秒）—— 二阶系统（弹簧-阻尼）的第二个状态量。
     *
     * 它同时是小球的**地面速度**（柱顶在往上顶的时候会把小球踢起来），
     * 所以必须与 [bars] 一起推进、一起进重绘判据。
     */
    private val barVel = FloatArray(barCount)

    /** A 档：峰值/小球（0..1）。独立数组，每帧原地更新 —— 绝不每帧分配。 */
    private val peaks = FloatArray(barCount)

    /** v3.4.5：小球的速度（归一化高度/秒，向上为正）。 */
    private val peakVel = FloatArray(barCount)

    /** v3.4.5：小球是否静止在柱顶上（静止态不施加重力，这是"停得住"的原因）。 */
    private val peakRest = BooleanArray(barCount)

    /** v3.4.5：柱高二阶系统的精确解步进器（起音 / 回落各一个，构造期建好，帧路径零分配）。 */
    private val springAttack = BandBallistics.SpringStep()
    private val springRelease = BandBallistics.SpringStep()

    /** v3.4.5：小球步进器与两个复用的暂存区（帧路径零分配）。 */
    private val ballStep = BandBallistics.BallStep()
    private val springOut = FloatArray(2)
    private val ballOut = FloatArray(3)

    /** v3.4.5：三条泳道柱高的速度（小球的"地面速度"）。 */
    private val lowBarVel = FloatArray(barCount)
    private val midBarVel = FloatArray(barCount)
    private val highBarVel = FloatArray(barCount)

    /** v3.4.5：三条泳道各自的小球（位置 / 速度 / 静止标志）。 */
    /**
     * v3.4.6：**峰值短横的保持值**（归一化高度，`[0,1]`）。
     *
     * 与 [peaks] / [lowPeaks] 等（小球位置）是**两份独立状态**：小球是受重力的质点，
     * 短横是"这一格曾经到过的最高点"（见 [BandBallistics.holdStep]）。
     * 在它之前短横只是"小球位置 + 常量"，两者永远同步 —— 用户实测两次指出这一点。
     */
    private val dash = FloatArray(barCount)

    private val lowDash = FloatArray(barCount)
    private val midDash = FloatArray(barCount)
    private val highDash = FloatArray(barCount)

    private val lowPeaks = FloatArray(barCount)
    private val midPeaks = FloatArray(barCount)
    private val highPeaks = FloatArray(barCount)
    private val lowPeakVel = FloatArray(barCount)
    private val midPeakVel = FloatArray(barCount)
    private val highPeakVel = FloatArray(barCount)
    private val lowPeakRest = BooleanArray(barCount)
    private val midPeakRest = BooleanArray(barCount)
    private val highPeakRest = BooleanArray(barCount)

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
    fun push(value: Float, low: Float = value, mid: Float = 0f, high: Float = 0f, arrivalAtMs: Long = 0L) {
        // NaN / Inf 防御：环形缓冲的边界读在和写者赛跑时理论上可能读到半个值
        // （见 pump 的注释）。宁可画成静音，也不要让 NaN 传染给整块画布。
        val slot = writeIndex % capacity
        ring[slot] = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        lowRing[slot] = if (low.isFinite()) low.coerceIn(0f, 1f) else 0f
        midRing[slot] = if (mid.isFinite()) mid.coerceIn(0f, 1f) else 0f
        highRing[slot] = if (high.isFinite()) high.coerceIn(0f, 1f) else 0f
        // v3.3.2 / v3.6.3：柱间隔**在这里**测（见 [batchStarts] 的 KDoc）—— 音频缓冲自己的节拍。
        // 槽位与 writeIndex 一次自增绑定，非 volatile；但 UI 侧永远读 `[0, snapshot)`，
        // 而 snapshot 是**自增之后**才发布的，所以这一槽对读者一定已经写完。
        if (arrivalAtMs > 0L) recordBatch(arrivalAtMs)
        arriveStampMs[slot] = arrivalAtMs
        writeIndex += 1
    }

    /**
     * v3.6.3：记一个到达批次，并把「每根柱占多久」发布给 UI（音频线程，零分配）。
     *
     * 同一批的判定是 `arrival − 本批起点 ≤ [BATCH_WINDOW_MS]`：同一次回调里的多次 push
     * 时间戳相同（或差 1~2ms），它们属于同一批；真实的柱间隔最小 20ms，不会被误并。
     *
     * 每根柱的间隔 = `(最新批起点 − 最早批起点) ÷ 中间各批柱数之和`。
     * 这个式子对「每批 1 根」「每批 3 根」「批次大小变化」都成立 —— 见 [batchStarts]。
     */
    private fun recordBatch(arrivalAtMs: Long) {
        val last = (batchNext - 1 + GAP_BATCHES) % GAP_BATCHES
        if (batchCount > 0 && arrivalAtMs - batchStarts[last] <= BATCH_WINDOW_MS) {
            // 同一批：只加柱数。最新批自己的柱数不进分母（它还没走完），所以不重新发布。
            batchSizes[last] += 1
            return
        }
        batchStarts[batchNext] = arrivalAtMs
        batchSizes[batchNext] = 1
        batchNext = (batchNext + 1) % GAP_BATCHES
        if (batchCount < GAP_BATCHES) batchCount += 1
        if (batchCount < 2) return
        val newest = (batchNext - 1 + GAP_BATCHES) % GAP_BATCHES
        val oldest = (batchNext - batchCount + GAP_BATCHES) % GAP_BATCHES
        var slots = 0
        var i = oldest
        while (i != newest) {
            slots += batchSizes[i]
            i = (i + 1) % GAP_BATCHES
        }
        if (slots <= 0) return
        val span = batchStarts[newest] - batchStarts[oldest]
        if (span <= 0L) return
        val perBar = span.toFloat() / slots
        if (perBar.isFinite() && perBar in MIN_BAR_INTERVAL_MS..MAX_BAR_INTERVAL_MS) {
            // 先写值、**最后**写序号：UI 看到新序号时一定读得到配套的值。
            publishedGapMs = perBar
            publishedGapSeq += 1
        }
    }

    /** UI 线程：清空（换歌 / 停止时用）。 */
    fun clear() {
        readIndex = writeIndex
        // v3.6.3：画面时间与帧时钟一起归零。不清的话，上一首留下的相位会让新歌开头
        // 先平移一段空窗口（换歌观感会变成「先空滑一格」）。柱间隔**不清**：缓冲粒度是
        // 设备/编码器的属性，换歌不会变，留着可以少一次重新收敛。
        sinceBarMs = 0f
        lastPumpNowMs = 0L
        windowPrimed = false
        bars.fill(0f)
        barVel.fill(0f)
        targets.fill(0f)
        bassTarget = 0f
        lowTargets.fill(0f)
        midTargets.fill(0f)
        highTargets.fill(0f)
        lowBars.fill(0f)
        midBars.fill(0f)
        highBars.fill(0f)
        lowBarVel.fill(0f)
        midBarVel.fill(0f)
        highBarVel.fill(0f)
        peaks.fill(0f)
        peakVel.fill(0f)
        peakRest.fill(false)
        lowPeaks.fill(0f)
        midPeaks.fill(0f)
        highPeaks.fill(0f)
        dash.fill(0f)
        lowDash.fill(0f)
        midDash.fill(0f)
        highDash.fill(0f)
        lowPeakVel.fill(0f)
        midPeakVel.fill(0f)
        highPeakVel.fill(0f)
        lowPeakRest.fill(false)
        midPeakRest.fill(false)
        highPeakRest.fill(false)
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
     * @param nowMs **这一帧自己的时间戳**（毫秒），必须与 [push] 的 `arrivalAtMs` **同一个时钟**
     *   （生产里两边都是 uptime 单调时钟：帧侧是 `withFrameNanos` 的 `frameTimeNanos / 1e6`，
     *   柱侧是音频线程的 `SystemClock.uptimeMillis()`）。传 0 = 没有帧时钟，回落旧的累加口径。
     * @return 画面是否需要重绘。**完全静止（没有新数据、已收敛、峰值已落、相位已停）时
     *   返回 false** —— 这是"暂停后不再白烧 GPU"的保证。
     */
    fun pump(active: Boolean, dtMs: Float, effects: VisualizerEffects, nowMs: Long = 0L): Boolean {
        var changed = false
        if (active) {
            // v3.3.2：`sinceBarMs` 是时间量，与信号无关。它现在**每帧由两个真实时间戳相减**
            // ★ v3.6.3：画面时间只由**真实帧间隔**推进。
            //
            //   `nowMs` 是帧循环自己的时间戳（`withFrameNanos / 1e6`，与音频线程的
            //   `uptimeMillis()` 同一个单调时钟基准）。这里只取它与**上一帧**的差 ——
            //   两个同源时间戳相减，不存在跨时钟标定；`lastPumpNowMs` 每帧被覆盖、不累加，
            //   所以也不会像 v3.3.2 那样逐帧截断出 4%/秒的漂移。
            //
            //   没有帧时钟（老调用点 / 单测）时回落到调用方给的 `dtMs`：它同样是真实帧间隔。
            val rawDelta = if (nowMs > 0L && lastPumpNowMs > 0L) nowMs - lastPumpNowMs else -1L
            val dtForPhase = if (rawDelta >= 0L) {
                rawDelta.coerceIn(0L, MAX_FRAME_DELTA_MS).toFloat()
            } else {
                dtMs.coerceIn(0f, MAX_FRAME_DELTA_MS.toFloat())
            }
            if (nowMs > 0L) lastPumpNowMs = nowMs
            // v3.6.3：长时间卡顿 / 后台回来 / 旋转后重新挂载时，环里可能积了几十根柱。
            // 按「一格一根」播完要好几秒，最新一格会一直落后于耳朵（旧实现是一帧全消费，
            // 不落后但会一次冲很多格）。这里改成**重建**：丢掉一屏以外的积压，用最新的一屏
            // 重新填满窗口 —— 视觉上是一次「追上现在」，之后继续匀速。
            if (rawDelta > RESYNC_AFTER_MS || writeIndex - readIndex > barCount) {
                windowPrimed = false
            }
            // v3.6.3：吸收音频线程发布的最新「每根柱间隔」。**放在消费之前**、且与这一帧
            // 有没有柱可消费无关 —— 否则一个偏小的估计会让画面先消费过快、随后没柱可消费，
            // 估计就永远停在错的值上（帧停在格边界，却再也没机会被修正）。
            absorbPublishedGap()
            // v3.6.3：**首帧把已有的历史一次填满**。挂载时环里可能已经积了半秒到几秒的柱
            // （用户先播放、后展开播放器），按「一格一根」慢慢填会先看到一段空窗口。
            // 只填一次；之后全部走时间节拍，成批到达不会再变成一次大跳。
            var primedThisFrame = false
            if (!windowPrimed) {
                // 只保留最新的一屏；更老的柱是「已经滚过去的历史」，再播只会让画面滞后。
                val backlog = writeIndex - readIndex
                if (backlog > barCount) readIndex = writeIndex - barCount
                var filled = 0
                while (filled < barCount && consumeOne()) filled++
                // 只有真的填到东西才算「首帧已就绪」：挂载早于起播时第一帧没有数据，
                // 不能把这次机会浪费掉 —— 起播后的第一帧才应该一次填满已有历史。
                if (filled > 0) {
                    windowPrimed = true
                    sinceBarMs = 0f
                    primedThisFrame = true
                    changed = true
                }
            }
            // 首帧**不推进**画面时间：这一帧就是「第 0 格」。否则画面时钟会比第一根柱的
            // 到达时刻提前一个 dt，之后每个格边界都早到几毫秒 —— 数据还没到，相位先撞顶棚，
            // 于是每隔一格停一帧（实测 60Hz / 100ms 柱上 5.8% 的帧停在 1.0）。
            if (!primedThisFrame) sinceBarMs += dtForPhase
            val interval = phaseIntervalMs()
            var consumed = false
            if (sinceBarMs >= interval) {
                // 跨过一格：**只消费一根**。一批 N 根柱会在 N 个格周期里被逐根取走，
                // 画面因此匀速 —— 而不是把 N 格位移堆在同一帧里（那正是「一阵子动一下」）。
                consumed = consumeOne()
                if (consumed) {
                    sinceBarMs -= interval
                    // 一帧的 dt 大于一个格周期时（长卡顿 / 低帧率），最多保留一格余量：
                    // 多出来的时间直接丢掉，宁可慢一点，也不要把好几格位移压进同一帧。
                    if (sinceBarMs > interval) sinceBarMs = interval
                } else {
                    // 没有下一根柱：停在格边界等。这是「真的没有数据」的诚实表达。
                    // 下一根柱到达后会在这里被消费，相位从 0 继续 —— 位移不跳变。
                    sinceBarMs = interval
                }
            }
            // 信号门槛**保留**在它该在的地方 —— 「要不要为这一帧排重绘」：静音段既不
            // 排帧也不白烧 GPU，v1.8.1 的「不空转」契约与暂停即停帧契约都没有变。
            //
            // ⚠️ v3.6.3 修掉一个隐蔽的「顿住」：旧判据只看**最新一根**柱
            // （`targets[barCount - 1]`）。音乐里一个安静缓冲落到最右格时，整条还在滚动、
            // 左边还有内容的波形会**停止重绘** 1~3 帧，下一根有声柱到达时才一次补上 ——
            // 那也是一顿一停。现在只要**可见窗口里还有任何内容**就继续重绘。
            if (consumed || (sinceBarMs < interval && windowHasSignal())) {
                changed = true
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
     * v3.6.3：把音频线程发布的「每根柱间隔」吸收进 [barIntervalMs]（UI 线程，每帧最多一次）。
     *
     * 发布通道用「值 + 自增序号」而不是直接读 [publishedGapMs]：同一个数值可能连续出现
     * （稳定 100ms 的流每批都是 100.0），只比值会把后续样本全丢掉，估计就卡在旧的错误上。
     */
    private fun absorbPublishedGap() {
        val seq = publishedGapSeq
        if (seq == appliedGapSeq) return
        appliedGapSeq = seq
        val sample = publishedGapMs
        if (!sample.isFinite() || sample !in MIN_BAR_INTERVAL_MS..MAX_BAR_INTERVAL_MS) return
        if (gapSamples < 3) {
            // 前 3 个样本直接采纳：默认值 100ms 只是猜测（实测有 82.6ms 的机型）。
            // 若按 EMA 慢跑，头几秒画面会比音频慢、积压一直涨，最后触发一次重建跳变。
            // 首样本可能带启动抖动，所以夹到更窄的 [50, 200]；真实的大缓冲随后会被 EMA 修正。
            barIntervalMs = sample.coerceIn(50f, 200f)
            gapSamples += 1
            return
        }
        barIntervalMs += (sample - barIntervalMs) * BAR_INTERVAL_EMA
        gapSamples += 1
    }

    /**
     * 可见窗口里是否还有内容（含三条频带）。
     *
     * 重绘门槛用**整个窗口**而不是最新一格：波形是滚动的时间轴，最右格恰好是一个安静
     * 缓冲时，左边还有大半个屏幕的内容在移动 —— 只看最右格会让画面停止重绘（v3.6.3 修）。
     * 全窗口都静音时返回 false，帧就真的停下来（v1.8.1 的「不空转」契约）。
     */
    private fun windowHasSignal(): Boolean {
        for (i in 0 until barCount) {
            if (targets[i] > ANIMATION_MIN_SIGNAL) return true
        }
        if (lowTargets.any { it > ANIMATION_MIN_SIGNAL }) return true
        if (midTargets.any { it > ANIMATION_MIN_SIGNAL }) return true
        return highTargets.any { it > ANIMATION_MIN_SIGNAL }
    }

    /**
     * v3.6.3：消费**恰好一根**柱 —— 画面每跨过一个格周期调用一次。
     *
     * 旧实现 [consumePending] 会把这一帧里所有新到的柱一次搬进窗口：一批 N 根 ⇒ 窗口
     * 平移 N 格，相位只按最后一根算 ⇒ 画面一次冲 N 格、接着等下一批。现在由调用方按
     * 时间节拍一根一根取，批与批之间的抖动被**摊平**成匀速滚动。
     *
     * @return 是否真的消费了一根（false = 环里没有可消费的柱）。
     */
    private fun consumeOne(): Boolean {
        val snapshot = writeIndex
        var pending = snapshot - readIndex
        if (pending <= 0) return false
        if (pending > capacity) {
            // 溢出：丢掉最旧的，绝不阻塞写者。
            readIndex = snapshot - capacity
        }
        // 注意：读到的是"某一时刻的值"，写者可能刚好覆盖同一格（只在溢出时发生）。
        // 单根柱失真在视觉上不可见，也不影响后续帧 —— 因此这里不用锁。
        val slot = readIndex % capacity
        shiftIn(ring[slot])
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
        shiftBandIn(lowTargets, lowBars, lowPeaks, lowPeakVel, lowPeakRest, lowValue)
        shiftBandIn(midTargets, midBars, midPeaks, midPeakVel, midPeakRest, midValue)
        shiftBandIn(highTargets, highBars, highPeaks, highPeakVel, highPeakRest, highValue)
        readIndex += 1
        // 平移格数与该柱**同时**记账：渲染层的「窗口下标」因此成为测试可以直接读到的事实，
        // 而不是测试自己数出来的东西（v3.3.0 三次口径错误的根源）。
        shiftedCells += 1
        return true
    }

    /**
     * 把一条频带值搬进它自己的滚动窗口（与 [shiftIn] 同构）。
     *
     * v3.4.4：[display]（画面值窗口）**跟着一起平移** —— 见 [lowBars] 的 KDoc。
     * 两个数组的平移必须逐格同构，否则画面值会与原始窗口错位一格（形状被抹平）。
     *
     * @return 这一格是否真的改变了窗口内容（全等值时不必重绘）。
     */
    private fun shiftBandIn(
        window: FloatArray,
        display: FloatArray,
        ball: FloatArray,
        ballVel: FloatArray,
        ballRest: BooleanArray,
        value: Float,
    ): Boolean {
        var changed = false
        for (i in 0 until barCount - 1) {
            if (window[i] != window[i + 1]) {
                window[i] = window[i + 1]
                changed = true
            }
            // 画面值窗口同步平移：不平移就会把「上一格的弹道」留在原地 —— 那是时域模糊。
            display[i] = display[i + 1]
            // ⚠️ v3.4.5：**小球的状态必须跟着一起平移**。不平移的话，小球会"粘在下标上"，
            // 每一根新柱到达时它的地面都会被换成邻居的值 —— 而 `coerceIn(ground, 1f)`
            // 会立刻把小球夹到新地面上 ⇒ 小球永远离不了地、永远弹不起来。
            // 单条曲线那条路径**不**平移（那里的 `bars` 本身就不平移，是屏幕坐标系的平滑），
            // 小球与它同坐标系，所以是对的。
            ball[i] = ball[i + 1]
            ballVel[i] = ballVel[i + 1]
            ballRest[i] = ballRest[i + 1]
            // v3.4.6：短横的保持值同样必须平移 —— 它记的是"这一格"的历史峰值，
            // 不平移就会变成"记在横轴某个位置上"，柱子滚过去之后短横留在原地。
            dash[i] = dash[i + 1]
        }
        if (window[barCount - 1] != value) {
            window[barCount - 1] = value
            changed = true
        }
        // 最右一格进入的是**原始**值；它自己的起音由 [approach] 在随后的帧上做，
        // 所以这里不做任何赋初值 —— 一进来就赋值等于没有起音。
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
     * 让画面值朝数据值走一步。
     *
     * ## v3.4.5：指数逼近 → **二阶系统（弹簧-阻尼）的精确解**
     *
     * 旧写法是 `current += (target-current) * (1-exp(-dt/tau))` —— 指数**渐近**，
     * 永不越冲、永不回弹，而且 `SETTLE_EPSILON` 的收敛截断让它接近目标时**直接吸附**。
     * 用户读到的就是「小球没有弹起来的感觉」。现在换成
     * `x'' = ω²(target−x) − 2ζω·x'` 的闭式解（推导、参数依据、上界与静止判据
     * 全部写在 [BandBallistics] 的 KDoc 里）。
     *
     * ## 重绘判据刻意不用"位移是否大于某个 epsilon"
     *
     * 回落尾段每帧位移会越来越小，用位移阈值会让 [pump] 在柱子还停在 ~1.5% 的时候
     * 就报"不用重绘" —— 柱子冻在非零值上（v1.8.1 实现时被单测抓到的真实缺陷）。
     * 现在的判据是"这一格还没到位"（步进器返回 false == 已经精确吸附到目标且速度归零），
     * 一旦吸附就精确相等、下一帧自然停。
     *
     * v2.8.0 起峰值也并进这个返回值：小球在空中（或还在反弹）期间必须继续重绘，
     * 落到柱顶静止之后不再要求重绘。
     *
     * @return 有柱子还没到位、或小球还在动（需要重绘）时为 true。
     */
    private fun approach(dt: Float, effects: VisualizerEffects): Boolean {
        val dtSec = dt / 1000f
        // 三个超越函数每帧只算一次，四条窗口（28 × 4 格）共用。
        springAttack.prepare(OMEGA_ATTACK, BandBallistics.DAMPING_RATIO, dtSec)
        springRelease.prepare(OMEGA_RELEASE, BandBallistics.DAMPING_RATIO, dtSec)
        var changed = false
        for (i in bars.indices) {
            if (stepSpring(bars, barVel, i, targets[i])) changed = true
            if (effects.peaks) {
                if (stepBall(peaks, peakVel, peakRest, i, bars[i], barVel[i], dtSec)) changed = true
                // v3.4.6：短横 = 小球位置的历史最大值（带缓慢回落）。
                // 它必须**每帧都算**（即使小球没动 —— 回落本身也是运动），所以这里
                // 不能像小球那样只在 changed 时才更新。
                val next = BandBallistics.holdStep(peaks[i], dash[i], dtSec)
                if (next != dash[i]) {
                    dash[i] = next
                    changed = true
                }
            }
        }
        // v3.4.4：三条泳道的画面值走**同一套弹道**。
        // 不加这一层的后果见 [lowBars] 的 KDoc：12.1Hz 的瞬时阶梯 = 没有弹起感。
        // 这三个循环必须也参与返回值：静音段里 `hadSignal` 为 false 时不排帧，
        // 若弹道不计入 changed，回落尾巴会被冻在画面上（v1.8.1 踩过的同形状缺陷）。
        if (approachBand(lowTargets, lowBars, lowBarVel)) changed = true
        if (approachBand(midTargets, midBars, midBarVel)) changed = true
        if (approachBand(highTargets, highBars, highBarVel)) changed = true
        // v3.4.5：小球在**它自己那条泳道的柱顶**上弹（柱顶就是地面）。
        // 改前 peaks 是**单条曲线**的峰值，却画在三泳道的几何上（左中右三段的横轴），
        // 于是「地面」与「小球所在的柱子」根本不是同一个量。现在逐泳道各一份。
        if (effects.peaks) {
            if (stepBallBand(lowBars, lowBarVel, lowPeaks, lowPeakVel, lowPeakRest, lowDash, dtSec)) changed = true
            if (stepBallBand(midBars, midBarVel, midPeaks, midPeakVel, midPeakRest, midDash, dtSec)) changed = true
            if (stepBallBand(highBars, highBarVel, highPeaks, highPeakVel, highPeakRest, highDash, dtSec)) changed = true
        }
        return changed
    }

    /**
     * 一格柱高的弹簧-阻尼推进（升 / 降用各自的 ω，与旧的起音 / 回落时间常数同源）。
     *
     * @return 这一格是否还在动
     */
    private fun stepSpring(values: FloatArray, vels: FloatArray, index: Int, target: Float): Boolean {
        val x = values[index]
        val v = vels[index]
        val stepper = if (target >= x) springAttack else springRelease
        val moved = stepper.step(x, v, target, springOut)
        // 硬契约：柱高绝不越出 [0,1]（过冲 2.8% 只会在目标贴到 1.0 时被这里夹住）。
        values[index] = springOut[0].coerceIn(0f, 1f)
        vels[index] = springOut[1]
        return moved || values[index] != x
    }

    /** 一条频带画面窗口的弹道推进（与 [bars] 的逐格规则逐字同构）。 */
    private fun approachBand(
        window: FloatArray,
        display: FloatArray,
        displayVel: FloatArray,
    ): Boolean {
        var changed = false
        for (i in display.indices) {
            if (stepSpring(display, displayVel, i, window[i])) changed = true
        }
        return changed
    }

    /** 一格小球的推进（地面 = 同一格柱高的画面值，地面速度 = 该格柱高的速度）。 */
    private fun stepBall(
        values: FloatArray,
        vels: FloatArray,
        rests: BooleanArray,
        index: Int,
        ground: Float,
        groundV: Float,
        dtSec: Float,
    ): Boolean {
        val moved = ballStep.step(
            y = values[index],
            v = vels[index],
            resting = rests[index],
            ground = ground,
            groundV = groundV,
            dtSec = dtSec,
            out = ballOut,
        )
        // 硬契约：小球绝不低于当前柱高、也绝不越出画幅。
        values[index] = ballOut[0].coerceIn(ground, 1f)
        vels[index] = ballOut[1]
        rests[index] = ballOut[2] != 0f
        return moved
    }

    /** 一条泳道的小球推进。 */
    private fun stepBallBand(
        ground: FloatArray,
        groundVel: FloatArray,
        ball: FloatArray,
        ballVel: FloatArray,
        ballRest: BooleanArray,
        dash: FloatArray,
        dtSec: Float,
    ): Boolean {
        var changed = false
        for (i in ball.indices) {
            if (stepBall(ball, ballVel, ballRest, i, ground[i], groundVel[i], dtSec)) changed = true
            // 短横每帧都推进（回落也是运动），见 [BandBallistics.holdStep]。
            val next = BandBallistics.holdStep(ball[i], dash[i], dtSec)
            if (next != dash[i]) {
                dash[i] = next
                changed = true
            }
        }
        return changed
    }

    /** 相位推进：只在 [periodMs] 内循环，用取模而不是累加 —— 长跑不会丢精度。 */
    private fun advancePhase(phase: Float, dt: Float, periodMs: Float): Float {
        if (periodMs <= 0f) return 0f
        var next = phase + dt / periodMs
        if (next >= 1f) next -= (next.toInt()).toFloat()
        return if (next.isFinite()) next else 0f
    }

    /**
     * 相位斜坡的**分母**（毫秒）：一根柱代表多久。
     *
     * v3.6.3：只认 [barIntervalMs]（在 [consumeOne] 里按「每根柱」平滑过的到达间隔）。
     * 旧实现优先用「最新一根柱的间隔」—— 成批到达时那是一次回调的间隔（例如 300ms），
     * 会让画面按 3 倍慢速爬、然后撞顶棚。现在不再有「最新一根」这个概念。
     */
    private fun phaseIntervalMs(): Float = when {
        barIntervalMs.isFinite() && barIntervalMs > 1f -> barIntervalMs
        else -> DEFAULT_BAR_INTERVAL_MS
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
     * v3.4.6：滚动窗口 + 小球 + **峰值短横的保持值**一起拷出（渲染路径用，零分配）。
     *
     * 短横与小球是两份独立状态：小球是受重力的质点，短横是"这一格曾经到过的最高点"
     * （见 [BandBallistics.holdStep]）。渲染层必须同时拿到两者，否则又退回
     * "短横 = 小球 + 常量"那种同步移动。
     */
    fun copyInto(
        barsDestination: FloatArray,
        peaksDestination: FloatArray,
        dashDestination: FloatArray,
    ) {
        copyInto(barsDestination)
        val n = minOf(peaksDestination.size, barCount)
        for (i in 0 until n) peaksDestination[i] = peaks[i]
        val m = minOf(dashDestination.size, barCount)
        for (i in 0 until m) dashDestination[i] = dash[i]
    }

    /**
     * v3.2.2：把**三条频带**的滚动窗口拷进调用方复用的三个数组（零分配）。
     *
     * v3.4.4：拷出的是**画面值**（[lowBars] 等，带 22/130ms 弹道），不是原始窗口 ——
     * 渲染层画的就是它。原始窗口仍由 [bandAt] 读（诊断与回归测试用），
     * 两者的差就是这一版修掉的「没有弹起感」。
     *
     * 三个数组长度都按 `barCount` 截断 —— 调用方传大数组也不会越界。
     */
    fun copyBandsInto(lowDestination: FloatArray, midDestination: FloatArray, highDestination: FloatArray) {
        copyBand(lowBars, lowDestination)
        copyBand(midBars, midDestination)
        copyBand(highBars, highDestination)
    }

    /**
     * v3.4.5：把**三条泳道各自的小球位置**拷进调用方复用的三个数组（零分配）。
     *
     * 与 [copyBandsInto] 逐格对齐：`lowPeaks[i]` 就是站在 `lowBars[i]` 这根柱子顶上的那个小球。
     * 渲染层用它把小球映射成像素高度（与柱高走**同一套** sqrt / 泳道增益），
     * 所以「小球绝不低于柱顶」在画面上也是逐像素成立的。
     *
     * 与 [copyBandsInto] 一样按 `barCount` 截断 —— 调用方传大数组也不会越界。
     */
    fun copyBandPeaksInto(lowDestination: FloatArray, midDestination: FloatArray, highDestination: FloatArray) {
        copyBand(lowPeaks, lowDestination)
        copyBand(midPeaks, midDestination)
        copyBand(highPeaks, highDestination)
    }

    /**
     * v3.4.6：把**三条泳道各自的峰值短横保持值**拷进调用方复用的三个数组（零分配）。
     *
     * 与 [copyBandPeaksInto] 逐格对齐，语义不同：那一份是"此刻的质点"（小球），
     * 这一份是"这一格曾经到过的最高点"（短横）。两者的高低关系由
     * [BandBallistics.holdStep] 的不变量保证（保持值恒 ≥ 小球）。
     */
    fun copyBandDashInto(lowDestination: FloatArray, midDestination: FloatArray, highDestination: FloatArray) {
        copyBand(lowDash, lowDestination)
        copyBand(midDash, midDestination)
        copyBand(highDash, highDestination)
    }

    private fun copyBand(source: FloatArray, destination: FloatArray) {
        val n = minOf(destination.size, barCount)
        for (i in 0 until n) destination[i] = source[i]
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
     * v3.2.3 / v3.6.3：画面时间在一格内的进度（0..1）。渲染层用它把历史窗口**连续左移**
     * 一格（v3.4.1 起是几何平移，不是对取值插值），而不是每 100ms 跳一格。
     * **不产生新数据**：移的是位置，不是未来那一格的值。
     */
    fun scrollPhase01(): Float {
        val p = sinceBarMs / phaseIntervalMs()
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

    /** 单测用：某一条频带**画面值**窗口里的第 `index` 格（v3.4.4，带 22/130ms 弹道）。 */
    fun bandBarAt(band: Int, index: Int): Float {
        if (index !in 0 until barCount) return 0f
        return when (band) {
            BAND_LOW -> lowBars[index]
            BAND_MID -> midBars[index]
            BAND_HIGH -> highBars[index]
            else -> 0f
        }
    }

    /** 单测用：画面值（平滑后）。 */
    fun barAt(index: Int): Float = bars[index]

    /** 单测用：数据值（未平滑）。 */
    fun targetAt(index: Int): Float = targets[index]

    /** 单测用：峰值 / 小球的位置（0..1，**绝不低于同一格的柱高**）。 */
    fun peakAt(index: Int): Float = peaks[index]

    /** 单测用：小球的速度（归一化高度/秒，向上为正）。 */
    fun peakVelAt(index: Int): Float = if (index in peakVel.indices) peakVel[index] else 0f

    /** 单测用：小球是否静止在柱顶上。 */
    fun peakRestingAt(index: Int): Boolean = index in peakRest.indices && peakRest[index]

    /** 单测用：某一条泳道**小球**的位置（0..1）。 */
    fun bandPeakAt(band: Int, index: Int): Float {
        if (index !in 0 until barCount) return 0f
        return when (band) {
            BAND_LOW -> lowPeaks[index]
            BAND_MID -> midPeaks[index]
            BAND_HIGH -> highPeaks[index]
            else -> 0f
        }
    }

    /** 单测用：某一条泳道**小球**的速度（归一化高度/秒）。 */
    fun bandPeakVelAt(band: Int, index: Int): Float {
        if (index !in 0 until barCount) return 0f
        return when (band) {
            BAND_LOW -> lowPeakVel[index]
            BAND_MID -> midPeakVel[index]
            BAND_HIGH -> highPeakVel[index]
            else -> 0f
        }
    }

    /** 单测用：某一条泳道柱高的速度（归一化高度/秒）—— 也就是小球的"地面速度"。 */
    fun bandBarVelAt(band: Int, index: Int): Float {
        if (index !in 0 until barCount) return 0f
        return when (band) {
            BAND_LOW -> lowBarVel[index]
            BAND_MID -> midBarVel[index]
            BAND_HIGH -> highBarVel[index]
            else -> 0f
        }
    }

    /** 单测用：还没被 UI 消费的柱数。 */
    val pendingCount: Int get() = writeIndex - readIndex

    companion object {
        /**
         * 起音**包络**时间常数（毫秒）：跟得上鼓点，又不至于把 30Hz 的阶跃原样画出来。
         *
         * v3.4.5 换成二阶系统之后它仍然是"起音多快"的唯一来源 ——
         * `ω = 1/(ζ·τ)`（[BandBallistics.omegaFor]），ζ 由 [BandBallistics.DAMPING_RATIO] 固定。
         */
        const val ATTACK_TAU_MS = BandBallistics.ATTACK_TAU_MS

        /** 回落**包络**时间常数（毫秒）：VU 表式的自然衰减节奏没有变。 */
        const val RELEASE_TAU_MS = BandBallistics.RELEASE_TAU_MS

        /** 收敛判据：低于它就吸附到目标值，避免"永远差一点点"导致无限重绘。 */
        const val SETTLE_EPSILON = BandBallistics.SETTLE_EPSILON

        /** v3.4.5：起音方向的自然频率（rad/s），由包络时间常数反解。 */
        val OMEGA_ATTACK = BandBallistics.omegaFor(ATTACK_TAU_MS)

        /** v3.4.5：回落方向的自然频率（rad/s），比起音慢 5.9 倍。 */
        val OMEGA_RELEASE = BandBallistics.omegaFor(RELEASE_TAU_MS)

        /**
         * v3.4.5：**峰值保持时长与匀速下落速度已被物理模型取代，不再存在。**
         *
         * 旧常量是 `PEAK_HOLD_MS = 420f`（保持 420ms）与 `PEAK_FALL_PER_SECOND = 0.9f`
         * （之后匀速下落、落到柱高即停）。匀速 = 零加速度，落到柱高就停 = 永不反弹，
         * 用户读到的正是「小球没有弹起来的感觉」。现在小球的运动由
         * [BandBallistics.GRAVITY] / [BandBallistics.RESTITUTION] 决定，
         * "保持"变成了弹道飞行时间本身（自然结果，不再是计时器）。
         */
        /** 渐变流动一个完整周期的时长（毫秒）。2.4s 是一眼能看出"在流动"又不至于晃眼的下限附近。 */
        const val FLOW_PERIOD_MS = 2400f

        /** 呼吸一个完整周期的时长（毫秒）。3.2s ≈ 平静呼吸，比渐变慢一档避免两个动画打架。 */
        const val BREATHE_PERIOD_MS = 3200f

        /** 呼吸深度：亮度在 [1-DEPTH, 1] 之间摆动。0.16 在 OLED 黑底上可辨、又不至于像闪烁。 */
        const val BREATHE_DEPTH = 0.16f

        /**
         * 动画相位的空转门槛：**画面**最新一根柱低于它就不为「滚动」排重绘
         * （渐变流动 / 呼吸两个动画相位同此门槛）。
         *
         * ⚠️ v3.3.2：它**只管要不要重绘**，不再管 `sinceBarMs` 推不推进。
         * 旧写法让门槛同时管住「时间积分」，于是真实音乐里单根缓冲低于 0.02
         * （间奏 / 换气 / 混音空档，**未平滑的原始柱高**意义上的常态）会让相位冻住、
         * 画面原地停 5 帧再一次性冲出去 —— 量到的形状是「0,0,0,0,0,1.0」。
         * 判据是「画面值还有没有内容」，不是「时间走不走」。
         *
         * 0.02 远低于音乐 RMS 的常见区间（0.05~0.3，见 AudioVisualizer 的 sqrt 映射注释），
         * 但高于"全零后的残余"，所以静音段与暂停态都不会白烧帧。
         */
        const val ANIMATION_MIN_SIGNAL = 0.02f

        /** v3.2.3：柱间隔的初值（毫秒）。真机实测 S6 是 100ms（11 Hz），这里只是起始猜测。 */
        const val DEFAULT_BAR_INTERVAL_MS = 100f

        /** 柱间隔样本的合法区间：超出就丢弃（卡顿 / 暂停恢复不该污染滑动平均）。 */
        const val MIN_BAR_INTERVAL_MS = 20f
        const val MAX_BAR_INTERVAL_MS = 500f

        /**
         * v3.6.3：同一批柱的判定窗口（毫秒）。
         *
         * 音频链路会把多根柱放进同一次回调，时间戳通常**完全相同**；少数实现会差 1~2ms。
         * 10ms 足够把这些算作一批，又远小于真实柱间隔（最小 20ms），不会把两根正常的柱
         * 误并成一批（那会把间隔读成一半，画面变成 2 倍速）。
         */
        const val BATCH_WINDOW_MS = 10L

        /**
         * v3.6.3：参与「每根柱间隔」平均的批次数。
         *
         * 8 批在 100ms/根的流上是 0.8s 的窗口：既能抹掉单次回调的抖动，又能在换编码器 /
         * seek 之后 1 秒内跟上新节奏。**不按根数**取窗口 —— 每批几根是运行时才知道的。
         */
        const val GAP_BATCHES = 8

        /**
         * v3.6.3：单帧允许推进的**画面时间**上限（毫秒）。
         *
         * 长卡顿（后台切回 / GC / 首帧）后的 `dt` 可能有几百毫秒。把它整段算进滚动会让画面
         * 一次跨好几格（再被一格上限截断），看起来就是「猛跳一下」。截到 200ms 等于
         * 「卡顿期间画面慢一点」，与 `dtMs` 一贯的 200ms 上限一致。
         */
        const val MAX_FRAME_DELTA_MS = 200L

        /**
         * v3.6.3：超过这么久没有推进过帧，就认为 UI 刚经历了一次长卡顿 / 后台 / 重新挂载，
         * 下一帧直接用环里最新的一屏**重建**窗口，而不是把积压的柱一格一格慢慢播。
         *
         * 250ms ≈ 15 帧：正常 vsync 抖动远低于它；超过它时用户已经能看到画面停住了，
         * 此时「追上现在」比「保住中间那几格连续」更重要。
         */
        const val RESYNC_AFTER_MS = 250L

        /**
         * v3.3.2：把「距上一根柱过了多久」**折回**到一格之内（纯函数，JVM 单测直测）。
         *
         * 窗口在消费 `barsConsumed` 根柱时正好平移了同样多的格数 ⇒ 相位的整数部分
         * 已经由窗口承担，小数部分留给渲染层插值。所以到达时该做的是
         * `elapsed -= floor(elapsed / interval) × interval`，**不是**把 `elapsed` 清零。
         *
         * 清零只在「一根柱一帧、且恰好走到格尾」的稳态下与减整数数值相同；
         * 一帧里到达多根柱（卡顿后追帧）时，清零会把那一帧本该有的余量丢掉，
         * 于是位移少一截、下一帧再补回来 —— 又是一次「顿-冲」。
         *
         * @return 折回后的余量（毫秒），落在 `[0, interval)`；输入非有限时返回 0。
         */
        internal fun foldScrollPhase(elapsedMs: Float, intervalMs: Float, barsConsumed: Int): Float {
            if (!elapsedMs.isFinite()) return 0f
            val interval = if (intervalMs.isFinite() && intervalMs > 1f) {
                intervalMs
            } else {
                DEFAULT_BAR_INTERVAL_MS
            }
            val cells = maxOf(0, barsConsumed).toFloat()
            var remaining = elapsedMs - cells * interval
            // 巨额 dt（暂停恢复 / 长卡顿）防御：折到一格之内，顺带避开大数浮点精度损失。
            if (remaining >= interval) remaining %= interval
            if (remaining < 0f) remaining += cells * interval
            return if (remaining.isFinite() && remaining >= 0f) remaining else 0f
        }

        /** 柱间隔滑动平均的更新系数（每根柱一次）。 */
        const val BAR_INTERVAL_EMA = 0.15f

        /** v3.2.2：频带下标（与 `BandColorRoles` / `BandDominance` 的三色顺序一致）。 */
        const val BAND_LOW = 0
        const val BAND_MID = 1
        const val BAND_HIGH = 2

        private const val TWO_PI = 6.2831855f
    }
}
