package com.takahashirinta.ncrust.ui.player.waveform

import kotlin.math.max

/**
 * 波形上**峰值短横**与**小球（圆点）**的纵向几何（纯逻辑，JVM 可单测）。
 *
 * ## 为什么需要它（用户实测）
 *
 * v3.4.5 把圆点改成跟着**小球**（自由落体 + 弹性碰撞的质点）走之后，
 * 圆点终于会在空中离开色带顶边 —— 那是要的效果。但代价是：
 *
 * > 「峰值短横会被圆点遮住」→ 在 `peaks` + `dots` 都开的档位上，
 * > 原来那条粉色虚线变成"一串小球"。
 *
 * 根因是两者**共用同一个 `ballHalf`**：
 *
 * | 元素 | 纵向位置（改前） |
 * |---|---|
 * | 圆点圆心 | `centerY − ballHalf − dotRadius` |
 * | 短横矩形 | `y ∈ [centerY − peakHalf − cap, centerY − peakHalf]`，而 `peakHalf = ballHalf` |
 *
 * 短横的矩形正好落在圆点的半径范围内 ⇒ 被**完全遮住**。
 *
 * ## 修法：短横抬到小球**上方**，间距恒定
 *
 * 用户原话：「**短横感觉错开比较合理**」。
 *
 * 方向选"上方"的理由（不只是审美）：
 * - **语义**：短横记的是"这一格曾经到过的最高点"，小球是"此刻的质点"——
 *   历史峰值在语义上**不低于**当前质点，画在上面不会读错；
 * - **不与色带顶边打架**：往下画会撞进色带填充区，看起来像色带的毛刺。
 *
 * 间距**刻意不随柱高缩放**（固定 `dotRadius + cap` 派生）：若按柱高缩放，
 * 高柱上两者又会重叠 —— 那等于没修。
 *
 * ## 与开关的关系（必须保住）
 *
 * - `peaks` 关掉时短横根本不该画（调用方已有判据），此时圆点位置**与改前逐像素一致**；
 * - `dots` 关掉时只剩短横，其位置也不受本函数影响（本函数只描述"两者同时在场时的错开量"）。
 */
object BandMarkerGeometry {

    /**
     * 短横相对**小球**要额外抬高的量（像素）。
     *
     * 取 **`2·dotRadius + cap`**。推导（屏幕坐标，y 向下，越小越高）：
     *
     * ```
     * 圆点圆心 cy      = centerY − ballHalf − r
     * 圆点上沿         = cy − r            = centerY − ballHalf − 2r
     * 短横下沿         = centerY − peakHalf
     * 不重叠 ⇔ 短横下沿 ≤ 圆点上沿 ⇔ peakHalf ≥ ballHalf + 2r
     * ```
     *
     * 所以"刚好不被遮住"需要 `peakHalf = ballHalf + 2r`，即抬升 **`2r`**（不是 `r` ——
     * 圆点的**上沿**比圆心还高一个半径，只抬 `r` 仍会压住圆点上半部）。
     * 再 `+ cap` 让短横下沿与圆点上沿之间留出**自身厚度**那么大的空隙，
     * 视觉上明确分开，而不是"刚好贴着"（贴着在低分辨率屏上仍会读成一坨）。
     *
     * ⚠️ 我第一版写成 `r + cap`（少算了一个半径），**测试当场抓住了它** ——
     * 半高 0 处 `gap = −1.8`（重叠）。留此记录：这条几何很容易差一个半径。
     *
     * @param dotRadiusPx 圆点半径（像素）
     * @param peakCapPx 短横厚度（像素）
     */
    fun peakLiftPx(dotRadiusPx: Float, peakCapPx: Float): Float {
        val r = if (dotRadiusPx.isFinite() && dotRadiusPx > 0f) dotRadiusPx else 0f
        val c = if (peakCapPx.isFinite() && peakCapPx > 0f) peakCapPx else 0f
        return 2f * r + c
    }

    /**
     * 短横下沿相对**色带顶边**的纵向位置（像素，向上为正的"半高"域）。
     *
     * 调用方把返回值当 `peakHalf` 用：短横画在 `centerY − peakHalf − cap` 处。
     *
     * @param ballHalf 小球当前半高（像素，与 `heights[]` 同量纲）
     * @param dotRadiusPx 圆点半径
     * @param peakCapPx 短横厚度
     * @param separate 是否错开；`false` 时退化成 `ballHalf`（= 改前行为，供 A/B 与测试对照）
     */
    fun peakHalfPx(
        ballHalf: Float,
        dotRadiusPx: Float,
        peakCapPx: Float,
        separate: Boolean = true,
    ): Float {
        val base = if (ballHalf.isFinite()) max(0f, ballHalf) else 0f
        if (!separate) return base
        return base + peakLiftPx(dotRadiusPx, peakCapPx)
    }

    /**
     * 短横与圆点在纵向上是否**确实分开**（判据本身，供测试断言）。
     *
     * 几何（屏幕坐标，y 向下）：
     * - 圆点占据 `[cy − r, cy + r]`，其中 `cy = centerY − ballHalf − r`
     * - 短横占据 `[dashTop, dashTop + cap]`，其中 `dashTop = centerY − peakHalf − cap`
     *
     * 分开 ⇔ `dashTop + cap <= cy − r`（短横下沿不高于圆点上沿）。
     */
    fun isSeparated(
        ballHalf: Float,
        dotRadiusPx: Float,
        peakCapPx: Float,
        separate: Boolean = true,
    ): Boolean {
        // 与 [gapPx] 同一口径：短横下沿必须**不低于**圆点上沿。
        return gapPx(ballHalf, dotRadiusPx, peakCapPx, separate) >= -1e-4f
    }

    /**
     * 短横**下沿**相对圆点**上沿**的空隙（像素）。负数 = 重叠。
     *
     * 单独给出来是为了让测试能断言"有多开"，而不只是"有没有重叠" ——
     * 刚好贴住（空隙 0）在低分辨率屏上仍会读成一坨，所以判据应当是**空隙 ≥ 一个正数**。
     */
    fun gapPx(
        ballHalf: Float,
        dotRadiusPx: Float,
        peakCapPx: Float,
        separate: Boolean = true,
    ): Float {
        val r = max(0f, dotRadiusPx)
        val half = peakHalfPx(ballHalf, dotRadiusPx, peakCapPx, separate)
        val base = if (ballHalf.isFinite()) max(0f, ballHalf) else 0f
        // 都表达成"离 centerY 的距离"（越大越高）：
        //   圆点上沿 = ballHalf + 2r
        //   短横下沿 = half
        // 空隙 = 短横下沿高出圆点上沿多少。
        val dotTop = base + 2f * r
        return half - dotTop
    }
}
