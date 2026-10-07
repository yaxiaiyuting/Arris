/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

/**
 * v3.2.2：**三条频带泳道**的几何与显示增益（纯逻辑，JVM 直测）。
 *
 * ## 布局（用户要的形态）
 *
 * 把波形区**按横轴等分成三块**，各自画自己那条时间曲线：
 *
 * ```
 *  高 ┤      ╭╮            ╭╮           ╭╮
 *     │  ╭──╯╰─╮      ╭──╯╰──╮    ╭──╯╰──╮
 *  低 ┤╭╯       ╰────╯        ╰──╯        ╰╮
 *     └──────────────┴──────────────┴───────
 *       低频（鼓/贝斯）  中频（人声/吉他）  高频（镲片/齿音）
 * ```
 *
 *  - **三块各自滚动**：每一块画的是**它自己那个频带**的历史窗口（各 `barCount` 个点），
 *    三者按同一时刻对齐（同一个环形缓冲的同一批槽位）；
 *  - **无缝拼接**：三段点**拼成一条连续的点序列**（不是三条独立路径），
 *    所以接缝处由同一套 [WaveformCurve] 保单调切线连接 —— 曲线连续、没有断口；
 *  - **中间连接用渐变色**：整条带子用一条水平渐变 Brush 上色，
 *    在两个接缝处各留一段 [SEAM_FRACTION] 宽的过渡带做颜色混合（见渲染层）；
 *  - **不许把差距抹平**：接缝只是"连起来"，**不做跨频带的插值平均** ——
 *    左块的高度只由低频决定，右块只由高频决定。三段各自保留自己的形状，
 *    拼起来仍然读得出"左低音、中中音、右高音"。
 *
 * ## 显示增益（**这是显示层的标定，不是数据**）
 *
 * 三个频带的绝对量级差得很远。探针 §2.3 的 12197 个缓冲实测（5 首真实歌曲）：
 *
 * | 量 | p50 | p75 | p95 | max |
 * |---|---|---|---|---|
 * | 全带 rms | 0.147 | 0.288 | 0.432 | 0.599 |
 * | 低频 low | 0.081 | 0.180 | 0.344 | 0.483 |
 * | 中频 mid | 0.102 | 0.168 | 0.256 | 0.344 |
 * | 高频 high | **0.035** | 0.076 | **0.137** | 0.228 |
 *
 * 高频段的 p95 只有低频段的 **40%**。按同一把尺子画，高频泳道会是一条几乎贴着中线的
 * 细线 —— 用户要的「凸显出高音/中音/低音的差距」就反而**看不见**了。
 * 所以每条泳道有一个**显示增益**，把各自的 p95 标定到接近满高（0.9 的振幅域）：
 *
 * | 泳道 | 增益 | 依据（p95 × 增益 ≈ 0.9） |
 * |---|---|---|
 * | 低频 | 2.6 | `0.344 × 2.6 = 0.894` |
 * | 中频 | 3.5 | `0.256 × 3.5 = 0.896` |
 * | 高频 | 6.6 | `0.137 × 6.6 = 0.904` |
 *
 * 映射是**线性 + 增益**，不是 `sqrt`。这一点是照着真机截图改的：`sqrt` 会把
 * 0.081（p50）抬到 0.28、把 0.344（p95）压到 0.59 —— 动态范围被压成 **2 倍**，
 * 画面上就是三条"差不多粗的板子"，安静与响亮的差别看不出来。
 * 线性 + 增益下 0.081→0.21、0.344→0.89，动态范围是 **4 倍**，鼓点与间奏分得开。
 * （v2.8.0 用 `sqrt` 的理由是"绝对值太小、用不满高度"——增益就是为了解决同一件事
 * 而**不**牺牲动态范围的工具。）
 *
 * **增益只作用在显示高度上**，不改音频数据、不改颜色、不进任何统计口径；
 * 三条曲线的"形状差异"（低频是鼓点的脉冲、高频是镲片的碎抖）因此才读得出来。
 */
object BandLanes {

    /** 泳道数（低 / 中 / 高）。 */
    const val LANE_COUNT = 3

    /** 低频泳道在拼接点序列里的下标。 */
    const val LANE_LOW = 0

    /** 中频泳道。 */
    const val LANE_MID = 1

    /** 高频泳道。 */
    const val LANE_HIGH = 2

    /**
     * 接缝处**颜色过渡带**占整条宽度的比例（单侧）。
     *
     * 0.05 的依据：过渡带太窄（<2%）在真机上是硬切，看起来像三张图被裁开贴在一起；
     * 太宽（>12%）会把两条泳道的颜色混成第三种颜色，接缝反而变成"主角"。
     * 5% 在 1440px 宽的 S6 上是 72px，看得出渐变又不会喧宾夺主。
     */
    const val SEAM_FRACTION = 0.05f

    /**
     * 三条泳道的**颜色顺序**（与 `BandColorRoles` 的三角色一一对应）：
     * 低频 = secondary、中频 = primary（主题色本身）、高频 = tertiary。
     *
     * 中频给主题色不是随手排的：探针 §2.3 实测中频占 **0.66** 的时间（低 0.33 / 高 0.00），
     * 把主题色给它，画面里最常出现的那条泳道就是主题色。
     */
    val LANE_ROLE = intArrayOf(BandColorRoles.LOW, BandColorRoles.MID, BandColorRoles.HIGH)

    /** 显示增益（低 / 中 / 高），依据见类文档的实测表。 */
    val DISPLAY_GAIN = floatArrayOf(2.6f, 3.5f, 6.6f)

    /**
     * 一条泳道的**显示振幅**（0..1）：线性 × 显示增益，饱和截断。
     *
     * 为什么不是 `sqrt`（v2.8.0 的柱高映射）：见类文档的实测表 ——
     * `sqrt` 把动态范围压到 2 倍，画面上三条泳道会变成"差不多粗的板子"。
     *
     * @param raw 该频带的原始值（0..1，音频线程已 clamp）。
     * @param lane 泳道下标；越界返回 0（不抛异常 —— 这是每帧调用的路径）。
     */
    fun amplitude(raw: Float, lane: Int): Float {
        if (lane !in 0 until LANE_COUNT) return 0f
        val v = if (raw.isFinite()) raw.coerceIn(0f, 1f) else 0f
        return (v * DISPLAY_GAIN[lane]).coerceIn(0f, 1f)
    }

    /**
     * 把所有泳道的窗口拼成**一条连续点序列**的 x 位置（写进 [out]，零分配）。
     *
     * 三段等分整宽，点间距在整条序列上**恒定** —— 这是"无缝"的几何含义：
     * 接缝两边的点距与段内完全一样，曲线因此不会在接缝处"折一下"。
     *
     * @param pointsPerLane 每条泳道的点数（= `WaveformRing.barCount`）。
     * @param width 可用宽度（像素）。
     * @param out 长度 ≥ `pointsPerLane * LANE_COUNT` 的复用数组。
     * @return 拼接后的点数（`out` 里前这么多格有效）。
     */
    fun layoutX(pointsPerLane: Int, width: Float, out: FloatArray): Int {
        if (pointsPerLane < 2 || width <= 0f) return 0
        val total = pointsPerLane * LANE_COUNT
        val n = minOf(total, out.size)
        if (n < 2) return 0
        val step = width / (n - 1)
        for (i in 0 until n) out[i] = i * step
        return n
    }

    /**
     * 拼接点序列里第 `index` 个点属于哪条泳道（诊断 / 单测用）。
     */
    fun laneOf(index: Int, pointsPerLane: Int): Int =
        if (pointsPerLane <= 0) -1 else (index / pointsPerLane).coerceIn(0, LANE_COUNT - 1)
}
