/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v3.2.2：**波形曲线的控制点计算**（纯逻辑，无 Android 依赖 ⇒ JVM 直测）。
 *
 * 离散柱子改成连续曲线，缺的不是 `Path.cubicTo`，而是**切线怎么取**。
 * 探针 §2.5 拿 5 首真实歌曲（生产提取器 + 生产环形缓冲产出的 19055 帧柱高序列、
 * 8 250 815 个采样点）量了四种取法：
 *
 * | 取法 | 出现越界的帧 | 最大过冲 | 负高度采样点 |
 * |---|---|---|---|
 * | Catmull-Rom（均匀参数化） | 14112 / 19055 | 0.02273 | **4066** |
 * | 单调三次 · 朴素（中心差分 + 圆条件） | 13277 / 19055 | 0.01309 | 0 |
 * | **单调三次 · PCHIP（保号切线 + 圆条件）** | **0 / 19055** | **0.00000** | **0** |
 * | 贝塞尔 + 控制点限幅 | 0 / 19055 | 0.00000 | 0 |
 *
 * ⇒ Catmull-Rom **会画出负高度**（铁律 31 明令禁止：曲线穿进自己的镜像里，
 * 看起来像波形"翻"了一下），朴素单调三次**仍会过冲** 1.3%。
 * 只有把**保号**与**圆条件**两条一起用上，才能得到「曲线永远落在相邻两点之间」这条硬性质。
 *
 * ## 为什么 PCHIP 而不是同样 0 过冲的「控制点限幅」
 *
 * 两者都能把曲线关进区间，成本也一样（探针实测 904ns vs 853ns / 27 段）。
 * 选 PCHIP 的理由是**形状**：限幅只夹控制点，单调性没有保证 ——
 * 在一段真实上升沿里，被夹过的贝塞尔仍可能先"鼓"再落（局部极值），
 * 而 PCHIP 的保单调性保证「数据单调的区间上曲线单调」。波形是给人看强弱走向的，
 * 走向不能被插值改掉。
 *
 * ## 两条约束（缺一不可，写在这里防止下一个人只抄一半）
 *
 *  1. **保号**：`m_i · Δ_i ≤ 0` ⇒ `m_i = 0`。
 *     只做第 2 条是不够的 —— 相邻两段斜率反号且前一段更陡时，中心差分给出的 `m_i`
 *     与 `Δ_i` 反号，此时 `|m_i| ≤ 3|Δ_i|` 根本不把控制点关在 `[y_i, y_{i+1}]` 内。
 *  2. **圆条件**：`a² + b² ≤ 9`（`a = m_i/Δ_i`、`b = m_{i+1}/Δ_i`），否则按 `3/√s` 等比缩小。
 *
 * ## 零分配
 *
 * [computeTangents] 把切线写进调用方**复用**的数组（渲染层 `remember` 一个），
 * 自己不做任何分配。[controlY1] / [controlY2] 是 inline 的标量运算，供 `Path.cubicTo` 直接用。
 */
object WaveformCurve {

    /**
     * 一次能处理的最大点数（柱数）。
     *
     * 64 的依据：当前窗口是 28 柱（`WaveformStore.BAR_COUNT`），64 给了 2 倍余量，
     * 同时把渲染层复用数组的长度钉死在 64 —— 曲线化不引入"点数随设备变化"的新自由度。
     */
    const val MAX_POINTS = 64

    /**
     * 计算 [count] 个点（等距，间距 [dx]）的保单调切线，写进 [out]。
     *
     * @param y 点的高度（**振幅域**，即 `sqrt(柱值)`；非负）。
     * @param count 有效点数（`2..min(y.size, out.size, MAX_POINTS)`）。
     * @param dx 相邻点的 x 间距（必须 > 0，否则原样返回全 0 切线 —— 退化成折线，
     *   而不是除以 0 得到 NaN 让整条路径消失）。
     */
    fun computeTangents(y: FloatArray, count: Int, dx: Float, out: FloatArray) {
        val n = minOf(count, y.size, out.size, MAX_POINTS)
        if (n <= 0) return
        for (i in 0 until n) out[i] = 0f
        if (n < 2 || !dx.isFinite() || dx <= 0f) return

        // Δ_i = (y_{i+1} − y_i) / dx。原地复用 out 的前 n-1 格存斜率会与后面的切线写入打架，
        // 所以斜率单独算一遍（不分配：只读 y 的相邻两格）。
        // 内部点：加权调和平均（PCHIP 原式，h 全相等 ⇒ 2·Δl·Δr/(Δl+Δr)）。
        for (i in 1 until n - 1) {
            val dl = (y[i] - y[i - 1]) / dx
            val dr = (y[i + 1] - y[i]) / dx
            out[i] = if (dl * dr <= 0f) {
                0f
            } else {
                val w = 3f * dx
                (w + w) / (w / dl + w / dr)
            }
        }
        // 端点：单侧差分，且不得与相邻段斜率反号（否则端段会冲出区间）。
        val d0 = (y[1] - y[0]) / dx
        val d1 = if (n > 2) (y[2] - y[1]) / dx else d0
        out[0] = endpointTangent(d0, d1)
        val dLast = (y[n - 1] - y[n - 2]) / dx
        val dPrev = if (n > 2) (y[n - 2] - y[n - 3]) / dx else dLast
        out[n - 1] = endpointTangent(dLast, dPrev)

        // 条件 1：保号（内部点已由调和平均保证；端点的单侧差分可能反向，这里兜底）。
        for (i in 0 until n - 1) {
            val d = (y[i + 1] - y[i]) / dx
            if (d == 0f) {
                out[i] = 0f
                out[i + 1] = 0f
            } else {
                if (out[i] * d < 0f) out[i] = 0f
                if (out[i + 1] * d < 0f) out[i + 1] = 0f
            }
        }
        // 条件 2：圆条件（把两个控制点同时缩回盒子里）。
        for (i in 0 until n - 1) {
            val d = (y[i + 1] - y[i]) / dx
            if (d == 0f) continue
            val a = out[i] / d
            val b = out[i + 1] / d
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                out[i] = t * a * d
                out[i + 1] = t * b * d
            }
        }
    }

    /** 端点切线：单侧差分，且不得与相邻段斜率反号。 */
    private fun endpointTangent(d0: Float, d1: Float): Float =
        if (d0 * d1 <= 0f) 0f else if (abs(d1) > abs(d0)) 3f * d0 else d0

    /**
     * 段 `i → i+1` 的第一个控制点 y：`y_i + m_i·dx/3`。
     *
     * inline + 纯标量：渲染层每段调一次，不需要数组也不需要对象。
     */
    fun controlY1(y0: Float, m0: Float, dx: Float): Float = y0 + m0 * dx / 3f

    /** 段 `i → i+1` 的第二个控制点 y：`y_{i+1} − m_{i+1}·dx/3`。 */
    fun controlY2(y1: Float, m1: Float, dx: Float): Float = y1 - m1 * dx / 3f

    /**
     * 三次贝塞尔在参数 `t ∈ [0,1]` 处的取值（**单测 / 探针用**）。
     *
     * 生产路径不会调用它：渲染层交给 GPU 的 `cubicTo`。它存在的唯一目的是让
     * 「不过冲、无负高度」这条性质可以被 JVM 断言，而不是靠肉眼。
     */
    fun sampleAt(y0: Float, y1: Float, m0: Float, m1: Float, dx: Float, t: Float): Float {
        val c1 = controlY1(y0, m0, dx)
        val c2 = controlY2(y1, m1, dx)
        val u = 1f - t
        return u * u * u * y0 + 3f * u * u * t * c1 + 3f * u * t * t * c2 + t * t * t * y1
    }

    /**
     * 段 `i → i+1` 上曲线的取值范围是否被两个端点夹住（**可执行的不过冲判据**）。
     *
     * 单测直接用它扫 `t`。返回 `false` 表示出现越界（过冲 / 下冲 / 负高度）。
     */
    fun staysWithinEndpoints(
        y0: Float,
        y1: Float,
        m0: Float,
        m1: Float,
        dx: Float,
        steps: Int = 32,
        epsilon: Float = 1e-5f,
    ): Boolean {
        val lo = minOf(y0, y1) - epsilon
        val hi = maxOf(y0, y1) + epsilon
        for (s in 0..steps) {
            val v = sampleAt(y0, y1, m0, m1, dx, s.toFloat() / steps)
            if (v < lo || v > hi) return false
        }
        return true
    }
}
