package com.takahashirinta.ncrust.ui.player.waveform

/**
 * 三泳道波形的**横向滚动取值**（纯逻辑，JVM 可单测）。
 *
 * ## 它存在的理由：一个被误解了很久的缺陷
 *
 * 用户实测报告：「波形条会**尖端变平滑再变回去**」，并给了两张截图。这条与
 * 横向「顿-冲」（v3.3.2 修的分母量化）**是两个独立缺陷** —— 这一条是**形状**问题。
 *
 * ### 旧写法（[Mode.VALUE_LERP]）为什么错
 *
 * ```kotlin
 * val v = window[i] + (window[i + 1] - window[i]) * phase
 * ```
 *
 * 这看起来像「在相邻两格之间插值得到平滑滚动」，但它在数学上是
 * **把两个相邻时刻的信号线性混合** —— 也就是**时域模糊**，不是平移。
 *
 * 实测（同一段带尖峰的幅度、扫 12 个相位）：峰尖度在
 * **0.0034 ~ 0.0339** 之间摆动，即 **90% 的波动**，周期正好是柱间隔（约 100ms）
 * —— 那就是用户看到的「尖端变圆、再变尖」。同一把尺子下
 * [Mode.POSITION_SHIFT] 的波动是 **0%**。
 *
 * 根因是「相位」被用错了地方：相位描述的是**位置**，却被拿去插**取值**。
 *
 * ### 新写法（[Mode.POSITION_SHIFT]）为什么对
 *
 * 取值**直接取整格**（形状完全保真，不做任何时域混合），把亚格相位交给
 * **几何平移** —— 每条泳道的 x 整体左移 `phase × step`，并把 x 范围多延伸一格。
 *
 * 两个好处：
 * 1. **形状恒定**：相位变化时没有一个 `heights[]` 元素会变，变的只有绘制位置；
 * 2. **平滑滑动保留**：每帧位移仍是 `dt / 间隔 × step`（60Hz/100ms 柱 ≈ 0.167 格），
 *    不是退回 10Hz 的整格跳变（那条路实测更抖：抖动率 57.8% → 166.9%、跳变 100px）。
 *
 * 注意**不需要**多采一格历史：x 范围延伸一格即可，取值下标仍是 `0..count-1`
 * （最右那格被画到可见区之外，正好补上左移露出的缺口）。
 *
 * ## 与渲染层的关系
 *
 * `AudioVisualizer` 的三泳道分支消费本对象：`heights` 写进复用的数组，
 * `xShiftPx` 传给 `drawWaveformCurve` 的 `xShift` 参数（那个参数本来就是
 * 「画布已整体左移，这里给每个 x 补回去」的语义 —— 渐变色流动用的就是它）。
 */
object BandScroll {

    /** 取值方式。保留两种是为了让 A/B 与回归测试能在**同一把尺子**下对比。 */
    enum class Mode {
        /**
         * 旧写法：对**值**做亚格插值。**已知会产生尖端变形（尖度波动 90%）**，
         * 保留它只为回归测试与排查，生产路径不再使用。
         */
        VALUE_LERP,

        /** 新写法：取值保真 + 几何平移（生产路径）。 */
        POSITION_SHIFT,
    }

    /** 生产路径使用的模式。改这一行就等于切换整条链的行为。 */
    val PRODUCTION = Mode.POSITION_SHIFT

    /**
     * 把一条泳道的窗口值写进 [out] 的 `[base, base + count)`，并返回该泳道的**几何左移量**（像素）。
     *
     * @param window 该频带的历史窗口（长度 ≥ `count`）
     * @param out 复用的高度数组
     * @param base `out` 里的起始下标（三泳道拼接）
     * @param count 该泳道的格数
     * @param phase 亚格相位（0..1）
     * @param lane 泳道下标（交给 [BandLanes.amplitude] 做显示增益）
     * @param heightPx 绘制区高度
     * @param minBar 最小可见高度（像素）
     * @param step 一格对应的像素宽度
     * @param mode 取值方式
     * @return 该泳道应施加的 x 位移（像素，负值 = 左移）
     */
    fun fillLane(
        window: FloatArray,
        out: FloatArray,
        base: Int,
        count: Int,
        phase: Float,
        lane: Int,
        heightPx: Float,
        minBar: Float,
        step: Float,
        mode: Mode = PRODUCTION,
    ): Float {
        if (count <= 0) return 0f
        val ph = if (phase.isFinite()) phase.coerceIn(0f, 1f) else 0f
        for (i in 0 until count) {
            if (base + i >= out.size) break
            val v = when (mode) {
                // 旧写法：时域混合两个相邻时刻 —— 形状因此随相位变形。
                Mode.VALUE_LERP -> {
                    val a = window.getOrElse(i) { 0f }
                    // 最后一格没有"下一格"可插（新柱还没到）—— 保持原值。
                    val b = if (i < count - 1) window.getOrElse(i + 1) { a } else a
                    a + (b - a) * ph
                }
                // 新写法：取值原样，亚格位移交给几何。
                Mode.POSITION_SHIFT -> window.getOrElse(i) { 0f }
            }
            val half = BandLanes.amplitude(v, lane) * heightPx * 0.5f
            out[base + i] = if (half < minBar * 0.5f) minBar * 0.5f else half
        }
        return when (mode) {
            Mode.VALUE_LERP -> 0f
            // 左移一个亚格；x 范围多延伸一格由调用方负责（见类 KDoc）。
            Mode.POSITION_SHIFT -> -ph * step
        }
    }

    /**
     * [Mode.POSITION_SHIFT] 下绘制时需要多覆盖的格数。
     *
     * 左移 `phase` 格后，最右侧会露出至多一格的缺口 —— 把 x 范围延伸一格即可补上，
     * **不需要多采历史**（最右格画到可见区外）。返回 1 是这条契约的唯一来源，
     * 免得调用方各自写一个魔法数。
     */
    const val EXTRA_POINTS = 1
}
