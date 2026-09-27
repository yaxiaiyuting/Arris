/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.motion

/**
 * v3.0.0：**音频特征 → 动效参数的绑定层**（纯逻辑，JVM 可直测）。
 *
 * ## 它解决什么问题
 *
 * 「动效必须基于真实音频特征，不得用伪随机或固定周期伪装」（铁律 25）这句话
 * 落到代码上就是：**每一个随音乐变化的量，都必须能追到某一个音频特征**。
 * 这一层是那条追溯链的中间一环 ——
 *
 * ```
 * PCM ──(AudioFeatureExtractor，音频线程，一次遍历)──▶ rms/low/mid/high/质心/瞬态
 *        │
 *        ▼  WaveformStore（volatile 标量发布）
 *   MotionBindings  ◀── 本文件：把特征变成「这一帧要画什么」
 *        │
 *        ├─▶ MotionEnvelope   ：响度包络 + 节拍脉冲（背景呼吸 / 歌词律动 / 播放键脉冲）
 *        └─▶ MotionBackdropState：冲击波 / 光晕 / 粒子（定长池）
 * ```
 *
 * ## 绑定表（与任务书 §4.2 逐条对应）
 *
 * | 动效 | 音频特征 | 判据 |
 * |---|---|---|
 * | 冲击波 | **瞬态** | 音频线程判定出的瞬态触发一次；强度 = 低频/全带增量的较大者 |
 * | 光晕 | **瞬态** | 同上（与冲击波同一个触发源、不同的视觉表现与圈数） |
 * | 粒子 | **中频 + 高频能量** | 超过门槛后**生成速率与能量正相关**（不是固定周期） |
 * | 波形频带调制 | 低频 / 中频 / 高频 | 逐柱着色看「那一刻哪个频带占主导」；能量条看当下三个频带 |
 * | 背景呼吸 | **全带 RMS** | 响度包络（起音快 / 回落慢） |
 *
 * ## 两条工程约束
 *
 * 1. **零分配**：[update] 只写标量字段，没有数组、没有装箱、没有 lambda；
 *    瞬态计数用「比较前后差值」而不是队列。
 * 2. **有界**：一帧最多补 [MAX_TRANSIENTS_PER_FRAME] 次瞬态、粒子生成速率有硬上限、
 *    累加器有 clamp —— 长卡顿之后不会一次性炸出几百个粒子。
 *
 * ## 特征不可用时会发生什么（降级，不是失效）
 *
 * `available == false`（音频线程的提取器不可用，只有 RMS + 低频）时：
 *  - 瞬态**不再由本层产生**，而是让 `MotionEnvelope` / `WaveformBackdrop` 各自回落到
 *    v2.9.0 的内置判据（低频通道的突变 + 冷却）—— 用户看到的是「动效退回 v2.9.0」，不是「动效没了」；
 *  - 中高频与质心一律视为 0 ⇒ 粒子不生成（**这是如实表达「测不到」**，
 *    而不是拿一个假数字去驱动粒子）。
 */
class MotionBindings {

    // ------------------------------------------------------------------
    // 本帧的特征快照（原地更新；draw 阶段与消费方直接读）
    // ------------------------------------------------------------------

    /** 全带 RMS（0..1）。 */
    var rms: Float = 0f
        private set

    /** 低频带能量（0..1，< 150 Hz）。 */
    var low: Float = 0f
        private set

    /** 中频带能量（0..1，150 Hz .. 2 kHz）。 */
    var mid: Float = 0f
        private set

    /** 高频带能量（0..1，> 2 kHz）。 */
    var high: Float = 0f
        private set

    /** 频谱质心的三频带近似（0..1）。 */
    var centroid: Float = 0f
        private set

    /** 中高频的平均能量（0..1）—— 粒子的驱动量。 */
    var midHigh: Float = 0f
        private set

    /** 高频占比（0..1）—— 逐柱着色的辅助量。 */
    var brightness: Float = 0f
        private set

    /** 本帧**新发生**的瞬态次数（0..[MAX_TRANSIENTS_PER_FRAME]）。 */
    var transients: Int = 0
        private set

    /** 最近一次瞬态的相对强度（0..1）。 */
    var strength: Float = 0f
        private set

    /** 特征链路是否可用（false ⇒ 消费方回落到内置判据）。 */
    var available: Boolean = false
        private set

    /** 上一次看到的瞬态累计计数；`-1` = 还没接上外部信号（只对齐、不补触发）。 */
    private var lastTransientCount: Long = NO_COUNT

    /**
     * 每帧推进一步。**由 `MotionClock.frame` 在音频线程的发布之后调用恰好一次。**
     *
     * @param transientCount 音频线程的瞬态**累计**计数（单调递增）。
     */
    fun update(
        rms: Float,
        low: Float,
        mid: Float,
        high: Float,
        centroid: Float,
        available: Boolean,
        transientCount: Long,
        transientStrength: Float,
    ) {
        this.available = available
        this.rms = finite01(rms)
        this.low = finite01(low)
        this.mid = finite01(mid)
        this.high = finite01(high)
        this.centroid = finite01(centroid)
        this.midHigh = ((this.mid + this.high) * 0.5f).coerceIn(0f, 1f)
        val denom = this.low + this.mid + this.high
        this.brightness = if (denom > 1e-6f) (this.high / denom).coerceIn(0f, 1f) else 0f
        this.strength = finite01(transientStrength)

        if (!available) {
            transients = 0
            lastTransientCount = NO_COUNT
            return
        }
        val previous = lastTransientCount
        lastTransientCount = transientCount
        transients = if (previous < 0L) {
            // 首次接上外部信号：只对齐，不补触发（否则起播那一帧会把「从进程启动以来」
            // 的全部计数一次性炸出来）。
            0
        } else {
            (transientCount - previous).coerceIn(0L, MAX_TRANSIENTS_PER_FRAME.toLong()).toInt()
        }
    }

    /** 换歌 / 停止。 */
    fun reset() {
        rms = 0f
        low = 0f
        mid = 0f
        high = 0f
        centroid = 0f
        midHigh = 0f
        brightness = 0f
        transients = 0
        strength = 0f
        available = false
        lastTransientCount = NO_COUNT
    }

    /** 单测 / 诊断。 */
    internal fun lastCount(): Long = lastTransientCount

    companion object {

        /** 还没接上外部瞬态信号的哨兵（与 `WaveformEffectsState.NO_EXTERNAL_TRANSIENT` 同值同义）。 */
        const val NO_COUNT = -1L

        /**
         * 一帧最多补触发几次瞬态。
         *
         * 有界的理由（铁律 4）：正常帧里到达的缓冲数是 0~3；一次长卡顿可能攒下十几次，
         * 补满只会让画面「炸一下」，不补又会让卡顿期间的鼓点永久丢失。取 4：
         * 覆盖正常帧，同时把异常帧的代价钉死。
         */
        const val MAX_TRANSIENTS_PER_FRAME = 4

        // ---- 粒子：中高频能量 → 生成速率 ----

        /**
         * 粒子启动门槛（中高频平均能量）。
         *
         * 0.06 的依据：母带压缩过的流行乐 RMS 常落在 0.05~0.3（`AudioVisualizer` 的
         * sqrt 映射注释里有同一组实测区间），而中高频**只是其中一部分**，所以它的绝对值
         * 系统性低于全带 RMS。0.06 是「明显有弦乐/人声/镲片」的下沿，低于它生成的粒子
         * 会变成「安静段落也在飘」——那正是铁律 25 要禁止的形状。
         */
        const val PARTICLE_THRESHOLD = 0.06f

        /** 低密度档（精致）的粒子生成速率上限（个/秒）。 */
        const val PARTICLE_RATE_LOW = 10f

        /** 高密度档（炫技）的粒子生成速率上限（个/秒）。 */
        const val PARTICLE_RATE_HIGH = 26f

        /**
         * 中高频能量 → 粒子生成速率（个/秒）。**纯函数**。
         *
         * 低于门槛恒为 0（不是「很小的速率」——那会让静音段也在生成）；
         * 高于门槛后**线性正相关**，到 [PARTICLE_RATE_LOW] / [PARTICLE_RATE_HIGH] 封顶。
         * 这是「生成速率与能量正相关」那条要求的全部内容。
         */
        fun particleRateHz(midHigh: Float, density: Int): Float {
            val value = if (midHigh.isFinite()) midHigh.coerceIn(0f, 1f) else 0f
            if (value <= PARTICLE_THRESHOLD) return 0f
            val max = if (density >= MotionEffects.DENSITY_HIGH) PARTICLE_RATE_HIGH else PARTICLE_RATE_LOW
            // 门槛处从 0 连续起步（不跳变），到能量 ~0.4 时达到上限。
            val span = (PARTICLE_FULL_ENERGY - PARTICLE_THRESHOLD).coerceAtLeast(1e-3f)
            val t = ((value - PARTICLE_THRESHOLD) / span).coerceIn(0f, 1f)
            return max * t
        }

        /** 粒子速率达到上限所需的中高频能量。0.4 覆盖「副歌」量级，之后不再加速。 */
        const val PARTICLE_FULL_ENERGY = 0.4f

        // ---- 光晕 / 冲击波：瞬态强度 → 视觉参数 ----

        /**
         * 瞬态强度 → 光晕/冲击波的**幅度倍率**（[MIN_SCALE] .. 1）。
         *
         * 弱击打画小圈、强击打画大圈 —— 这是「让冲击波的大小跟随击打力度」的那条线，
         * 也是它区别于「固定周期的装饰动画」的关键。
         */
        fun strengthScale(strength: Float): Float {
            val s = if (strength.isFinite()) strength.coerceIn(0f, 1f) else 0f
            return MIN_SCALE + (1f - MIN_SCALE) * s
        }

        /** 最弱击打的幅度倍率。0.55 仍然明显可见，不会让弱拍"没有反应"。 */
        const val MIN_SCALE = 0.55f

        /**
         * 炫技档「多圈」的第 `index` 圈相对第 0 圈的**起始延迟**（0..1 的进度偏移）。
         *
         * 用「进度偏移」而不是时间延迟：池里的进度是 0..1，直接给第二圈一个负偏移
         * （= 出生即已经扩散了一点）就能得到「两圈错开」的观感，不需要第二个计时器。
         */
        fun ringStartOffset(index: Int): Float = if (index <= 0) 0f else RING_STAGGER * index

        /** 每一圈之间的进度错开量。0.22 → 两圈在寿命内明显分离，又都看得见。 */
        const val RING_STAGGER = 0.22f

        // ---- 逐柱着色 ----

        /**
         * 「明亮度占比」→ 逐柱着色的混合系数（0..1）。
         *
         * 直接用原始 `mix` 会让大部分柱子的颜色挤在中间（音乐的中高频占比长期在
         * 0.3~0.6 徘徊），画面上看不出差别。这里做一次**对比度拉伸**：
         * 以 [TINT_CENTER] 为中心放大 [TINT_GAIN] 倍，两端 clamp。
         */
        fun tintMix(mix: Float): Float {
            val m = if (mix.isFinite()) mix.coerceIn(0f, 1f) else 0f
            val stretched = (m - TINT_CENTER) * TINT_GAIN + 0.5f
            return stretched.coerceIn(0f, 1f)
        }

        /** 拉伸中心。0.5 表示「中高频占一半」是视觉上的中间色。 */
        const val TINT_CENTER = 0.5f

        /** 拉伸增益。1.8 让 0.3/0.7 分别落到接近两端，但不至于一有风吹草动就饱和。 */
        const val TINT_GAIN = 1.8f

        private fun finite01(value: Float): Float =
            if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    }
}
