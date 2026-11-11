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
 * 波形的单写者缓冲和滚动画面。音频线程写入四个频带值与到达时间，最后发布写入位置。
 * UI 线程按帧消费，溢出时跳过最旧的样本，音频线程无需等待。
 *
 * 可见历史按音频柱的间隔移动，亚格位移由渲染层处理。每格的柱高、小球和峰值短横
 * 各自推进，缺样、缓冲和恢复时沿用同一套窗口。帧间复用所有数组。
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

    /** v3.6.9：设置横向滚动倍速（0.25..4；非有限值忽略）。 */
    fun setScrollSpeedFactor(factor: Float) {
        if (factor.isFinite()) scrollSpeedFactor = factor.coerceIn(0.25f, 4f)
    }

    /** 测试 / 诊断：当前滚动倍速。 */
    fun scrollSpeedFactor(): Float = scrollSpeedFactor


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
    private var timestampedInput = false

    /**
     * 柱间隔的滑动平均（毫秒）。硬件缓冲粒度是运行时行为，所以只能测、不能假设。
     *
     * v3.3.2：它就是相位斜坡的**分母**，而且只在**到达处**测量（见 [arrivalMs]）。
     * 初值 [DEFAULT_BAR_INTERVAL_MS] 在第一次测到真实间隔之前充当分母 —— 若真值是 96ms，
     * 头几百毫秒的相位会略微偏慢（0.96 倍速），EMA（α=0.15，约 7 根柱收敛）随后把它拉正。
     */
    private var barIntervalMs: Float = DEFAULT_BAR_INTERVAL_MS

    /**
     * v3.6.9：横向滚动倍速（1 = 跟随音频数据）。
     *
     * 只影响 **UI 消费节拍**，不改变音频线程写入；>1 时数据会更快被取完，
     * [pump] 的缺样分支改为重复最后一列（hold），避免空白格。
     */
    @Volatile
    private var scrollSpeedFactor: Float = 1f

    private val fullWindow = ColumnWindow(barCount, scrollDisplay = false)
    private val bandWindows = Array(3) { ColumnWindow(barCount, scrollDisplay = true) }
    private var bassTarget = 0f

    private val springAttack = BandBallistics.SpringStep()
    private val springRelease = BandBallistics.SpringStep()
    private val ballStep = BandBallistics.BallStep()
    private val springOut = FloatArray(2)
    private val ballOut = FloatArray(3)
    private var preparedStepSeconds = Float.NaN

    private class ColumnWindow(size: Int, private val scrollDisplay: Boolean) {
        val targets = FloatArray(size)
        val heights = FloatArray(size)
        val heightVelocity = FloatArray(size)
        val balls = FloatArray(size)
        val ballVelocity = FloatArray(size)
        val ballResting = BooleanArray(size)
        val dash = FloatArray(size)

        fun clear() {
            targets.fill(0f)
            heights.fill(0f)
            heightVelocity.fill(0f)
            balls.fill(0f)
            ballVelocity.fill(0f)
            ballResting.fill(false)
            dash.fill(0f)
        }

        fun append(value: Float, blank: Boolean, hold: Boolean) {
            targets.copyInto(targets, startIndex = 1)
            // 泳道的画面状态随历史移动。单条曲线的平滑状态按屏幕坐标保留。
            if (scrollDisplay) {
                heights.copyInto(heights, startIndex = 1)
                heightVelocity.copyInto(heightVelocity, startIndex = 1)
                balls.copyInto(balls, startIndex = 1)
                ballVelocity.copyInto(ballVelocity, startIndex = 1)
                ballResting.copyInto(ballResting, startIndex = 1)
                dash.copyInto(dash, startIndex = 1)
            }
            if (hold) return

            val last = targets.lastIndex
            targets[last] = value
            if (blank) heights[last] = 0f
            if (scrollDisplay || blank) {
                heightVelocity[last] = 0f
                balls[last] = 0f
                ballVelocity[last] = 0f
                ballResting[last] = true
                dash[last] = 0f
            }
        }

        fun hasUnsettledColumns(peaks: Boolean): Boolean {
            for (index in targets.indices) {
                val height = heights[index]
                if (height != targets[index] || heightVelocity[index] != 0f) return true
                if (peaks && (
                    !ballResting[index] || balls[index] != height ||
                        ballVelocity[index] != 0f || dash[index] != balls[index]
                    )
                ) return true
            }
            return false
        }
    }

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
        timestampedInput = false
        fullWindow.clear()
        for (window in bandWindows) window.clear()
        bassTarget = 0f
        flowPhase = 0f
        breathePhase = 0f
    }

    /** UI 线程按帧率调用（效果全关的基线路径，老调用点与单测沿用这个重载）。 */
    fun pump(active: Boolean, dtMs: Float): Boolean = pump(active, dtMs, VisualizerEffects.BASELINE)

    /** 小球在某帧可能只改变静止标记，画面不变但仍要继续推进。 */
    internal fun hasUnsettledColumns(peaks: Boolean): Boolean =
        fullWindow.hasUnsettledColumns(peaks) || bandWindows.any { it.hasUnsettledColumns(peaks) }

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
    fun pump(active: Boolean, dtMs: Float, effects: VisualizerEffects, nowMs: Long = 0L, holdHistory: Boolean = false): Boolean {
        // Buffering is a temporary hold, not silence. Keep the measured window and its
        // ballistics intact, and re-anchor the frame clock when real playback resumes.
        if (!active && holdHistory) {
            lastPumpNowMs = 0L
            return false
        }
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
            var dtForPhase = if (rawDelta >= 0L) {
                rawDelta.coerceIn(0L, MAX_FRAME_DELTA_MS).toFloat()
            } else {
                dtMs.coerceIn(0f, MAX_FRAME_DELTA_MS.toFloat())
            }
            if (nowMs > 0L) lastPumpNowMs = nowMs
            // A stall must not replace a visible screen in one frame. Discard obsolete
            // pending samples, keep the measured history, and resume from the newest
            // pending column on the normal grid. Only the initial mount primes a window.
            if (windowPrimed && (rawDelta > RESYNC_AFTER_MS || writeIndex - readIndex > barCount)) {
                val latest = writeIndex
                if (latest - readIndex > 1) readIndex = latest - 1
                sinceBarMs = 0f
                dtForPhase = 0f // The stall predates the new anchor; never replay its elapsed time.
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
            if (!primedThisFrame) sinceBarMs += dtForPhase * scrollSpeedFactor
            val interval = phaseIntervalMs()
            var consumed = false
            if (windowPrimed && sinceBarMs >= interval) {
                // 跨过一格：**只消费一根**。一批 N 根柱会在 N 个格周期里被逐根取走，
                // 画面因此匀速 —— 而不是把 N 格位移堆在同一帧里（那正是「一阵子动一下」）。
                consumed = consumeOne()
                val canAdvanceGap = timestampedInput && nowMs > 0L
                if (!consumed && canAdvanceGap) {
                    // 数据短暂迟到不等于播放暂停。历史仍按时间滚动。
                    val visibleHistory = windowHasSignal()
                    if (scrollSpeedFactor > 1.001f && visibleHistory) {
                        // v3.6.9：倍速 > 1 时数据会先被取完；重复最后一列保持滚动，
                        // 而不是插空列（空列会让「加速」看起来像掉帧/断层）。
                        appendColumn(0f, 0f, 0f, 0f, blank = false, hold = true)
                    } else {
                        // 1× 及以下保持「缺失位置留空」的诚实语义，不重复编造峰值。
                        appendColumn(0f, 0f, 0f, 0f, blank = true)
                    }
                    consumed = visibleHistory
                }
                if (consumed || canAdvanceGap) {
                    sinceBarMs -= interval
                    // 保留既有低帧率边界；整窗已空时也不积攒等待时间或持续重绘。
                    if (sinceBarMs > interval) sinceBarMs = interval
                } else sinceBarMs = interval // 没有到达时间的旧宿主不推断数据缺失。
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
        } else if (fullWindow.targets.any { it != 0f }) {
            fullWindow.targets.fill(0f)
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
        if (active && fullWindow.heights.last() > ANIMATION_MIN_SIGNAL) {
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
        if (fullWindow.targets.any { it > ANIMATION_MIN_SIGNAL }) return true
        for (window in bandWindows) {
            if (window.targets.any { it > ANIMATION_MIN_SIGNAL }) return true
        }
        return false
    }

    /** 每个格周期最多消费一根柱，避免同批到达的数据在同一帧跳过多格。 */
    private fun consumeOne(): Boolean {
        val snapshot = writeIndex
        val pending = snapshot - readIndex
        if (pending <= 0) return false
        if (pending > capacity) {
            // 溢出：丢掉最旧的，绝不阻塞写者。
            readIndex = snapshot - capacity
        }
        // 注意：读到的是"某一时刻的值"，写者可能刚好覆盖同一格（只在溢出时发生）。
        // 单根柱失真在视觉上不可见，也不影响后续帧 —— 因此这里不用锁。
        val slot = readIndex % capacity
        if (arriveStampMs[slot] > 0L) timestampedInput = true
        appendColumn(ring[slot], lowRing[slot], midRing[slot], highRing[slot], blank = false)
        readIndex += 1
        return true
    }

    private fun appendColumn(
        value: Float,
        low: Float,
        mid: Float,
        high: Float,
        blank: Boolean,
        hold: Boolean = false,
    ) {
        fullWindow.append(normalized(value), blank, hold)
        val lowValue = normalized(low)
        if (!hold) bassTarget = lowValue
        bandWindows[BAND_LOW].append(lowValue, blank, hold)
        bandWindows[BAND_MID].append(normalized(mid), blank, hold)
        bandWindows[BAND_HIGH].append(normalized(high), blank, hold)
        shiftedCells++
    }

    private fun normalized(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    private fun approach(dt: Float, effects: VisualizerEffects): Boolean {
        val dtSec = dt / 1000f
        if (dtSec != preparedStepSeconds) {
            springAttack.prepare(OMEGA_ATTACK, BandBallistics.DAMPING_RATIO, dtSec)
            springRelease.prepare(OMEGA_RELEASE, BandBallistics.DAMPING_RATIO, dtSec)
            preparedStepSeconds = dtSec
        }

        val peaksEnabled = effects.peaks
        var changed = advanceWindow(fullWindow, dtSec, peaksEnabled)
        for (window in bandWindows) {
            if (advanceWindow(window, dtSec, peaksEnabled)) changed = true
        }
        return changed
    }

    private fun advanceWindow(window: ColumnWindow, dtSec: Float, peaksEnabled: Boolean): Boolean {
        var changed = false
        for (index in window.heights.indices) {
            if (advanceHeight(window, index)) changed = true
            if (peaksEnabled && advancePeak(window, index, dtSec)) changed = true
        }
        return changed
    }

    private fun advanceHeight(window: ColumnWindow, index: Int): Boolean {
        val height = window.heights[index]
        val velocity = window.heightVelocity[index]
        val target = window.targets[index]
        if (height == target && velocity == 0f) return false

        val stepper = if (target >= height) springAttack else springRelease
        val moved = stepper.step(height, velocity, target, springOut)
        val next = springOut[0].coerceIn(0f, 1f)
        window.heights[index] = next
        window.heightVelocity[index] = springOut[1]
        return moved || next != height
    }

    private fun advancePeak(window: ColumnWindow, index: Int, dtSec: Float): Boolean {
        val ground = window.heights[index]
        val groundVelocity = window.heightVelocity[index]
        val ball = window.balls[index]
        val velocity = window.ballVelocity[index]
        val resting = window.ballResting[index]
        val dash = window.dash[index]
        if (resting && ball == ground && velocity == 0f && groundVelocity == 0f && dash == ball) {
            return false
        }

        val moved = ballStep.step(ball, velocity, resting, ground, groundVelocity, dtSec, ballOut)
        val nextBall = ballOut[0].coerceIn(ground, 1f)
        window.balls[index] = nextBall
        window.ballVelocity[index] = ballOut[1]
        window.ballResting[index] = ballOut[2] != 0f
        val nextDash = BandBallistics.holdStep(nextBall, dash, dtSec)
        window.dash[index] = nextDash
        return moved || nextDash != dash
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

    /** 把全带画面拷进调用方复用的数组。 */
    fun copyInto(destination: FloatArray) = copyWindow(fullWindow.heights, destination)

    fun copyInto(barsDestination: FloatArray, peaksDestination: FloatArray) {
        copyInto(barsDestination)
        copyWindow(fullWindow.balls, peaksDestination)
    }

    fun copyInto(barsDestination: FloatArray, peaksDestination: FloatArray, dashDestination: FloatArray) {
        copyInto(barsDestination, peaksDestination)
        copyWindow(fullWindow.dash, dashDestination)
    }

    /** 频带快照使用平滑后的柱高，与小球、短横共享相同的格子下标。 */
    fun copyBandsInto(lowDestination: FloatArray, midDestination: FloatArray, highDestination: FloatArray) {
        copyWindow(bandWindows[BAND_LOW].heights, lowDestination)
        copyWindow(bandWindows[BAND_MID].heights, midDestination)
        copyWindow(bandWindows[BAND_HIGH].heights, highDestination)
    }

    fun copyBandPeaksInto(lowDestination: FloatArray, midDestination: FloatArray, highDestination: FloatArray) {
        copyWindow(bandWindows[BAND_LOW].balls, lowDestination)
        copyWindow(bandWindows[BAND_MID].balls, midDestination)
        copyWindow(bandWindows[BAND_HIGH].balls, highDestination)
    }

    fun copyBandDashInto(lowDestination: FloatArray, midDestination: FloatArray, highDestination: FloatArray) {
        copyWindow(bandWindows[BAND_LOW].dash, lowDestination)
        copyWindow(bandWindows[BAND_MID].dash, midDestination)
        copyWindow(bandWindows[BAND_HIGH].dash, highDestination)
    }

    private fun copyWindow(source: FloatArray, destination: FloatArray) {
        source.copyInto(destination, endIndex = minOf(source.size, destination.size))
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
     * 用未平滑值而不是 柱高：起音时间常数 22ms 会把 onset 抹圆（探针 §1 明确要求用 targets）。
     */
    fun newestTarget(): Float = fullWindow.targets.last()

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

    fun bandAt(band: Int, index: Int): Float =
        if (hasBandCell(band, index)) bandWindows[band].targets[index] else 0f

    fun bandBarAt(band: Int, index: Int): Float =
        if (hasBandCell(band, index)) bandWindows[band].heights[index] else 0f

    fun barAt(index: Int): Float = fullWindow.heights[index]

    fun targetAt(index: Int): Float = fullWindow.targets[index]

    fun peakAt(index: Int): Float = fullWindow.balls[index]

    fun peakVelAt(index: Int): Float =
        if (index in 0 until barCount) fullWindow.ballVelocity[index] else 0f

    fun peakRestingAt(index: Int): Boolean =
        index in 0 until barCount && fullWindow.ballResting[index]

    fun bandPeakAt(band: Int, index: Int): Float =
        if (hasBandCell(band, index)) bandWindows[band].balls[index] else 0f

    fun bandDashAt(band: Int, index: Int): Float =
        if (hasBandCell(band, index)) bandWindows[band].dash[index] else 0f

    private fun hasBandCell(band: Int, index: Int): Boolean =
        band in bandWindows.indices && index in 0 until barCount

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
