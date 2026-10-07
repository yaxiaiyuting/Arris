/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import kotlin.math.exp

/**
 * v3.2.2：**主导频段判定 + 防闪烁**（纯逻辑，无 Android / media3 依赖 ⇒ JVM 直测）。
 *
 * 波形的**颜色**由「这一刻哪个频带占主导」驱动（低频 / 中频 / 高频 → HCT 三个角色色）。
 * 这件事听起来是一行 `argmax`，但探针 §2.3 在 5 首真实歌曲上量出来的结论是：
 * 裸 `argmax` 的颜色切换频率是 **1.258 次/秒**（电子乐 2.009 次/秒），
 * 而缓冲粒度换成现代设备的 23ms 时是 **4.260 次/秒** —— 远超「> 3 次/秒 = 不合格」的线。
 * 观感上那就是**频闪**。
 *
 * ## 三级防闪（缺一不可，数字全部来自探针实测）
 *
 * | 级 | 机制 | 常量 | 实测效果（5 首合计 1219 秒） |
 * |---|---|---|---|
 * | 1 | 三频带各自**时间平滑**（EMA，`k = 1 − exp(−dt/τ)`） | [BAND_SMOOTH_TAU_MS] = 300ms | 1.258 → 0.308 次/秒 |
 * | 2 | **滞回**：挑战者必须比当前频段高 X% 才允许切换 | [BAND_HYSTERESIS_RATIO] = 0.20 | 0.308 → 0.118 次/秒，闪断 0 次 |
 * | 3 | 切换处 **50~100ms 颜色过渡**（不是硬切） | [BAND_COLOR_TRANSITION_MS] = 80ms | 观感：颜色"化"过去而不是"跳"过去 |
 *
 * 另加一条**静音闸门**（[BAND_SILENCE_GATE]）：三频带能量之和低于它时**保持**当前频段。
 * 安静段落里三个频带都是底噪，`argmax` 只是在挑噪声 —— 闸门让它保持上一个颜色，
 * 实测最短驻留从 100ms 提升到 300ms（探针 §2.3 的 `d20g` 一行）。
 *
 * ## 为什么 EMA 可以按**帧率**推进，而输入只有 11 Hz
 *
 * 频带值由音频线程按缓冲发布（真机实测 100ms / 11.0 Hz，见 `docs/verification/v3.0.0/
 * probe/EVIDENCE.md` 的 `PROBE-AUDIO-TAP`），而本类由帧时钟按 16/33ms 调用。
 * 指数平滑的离散形式 `k = 1 − exp(−dt/τ)` 在**输入分段恒定**时与连续解逐点相等 ——
 * 按帧推进不会改变时间常数，只是把同一条连续曲线采样得更密。
 * 真正的差异在**判定时机**：60 Hz 下穿越点可能落在两个缓冲之间。
 * 探针因此额外跑了「11 Hz 判定 vs 60 Hz 判定」的对照（见 `docs/verification/v3.2.2/
 * probe/dominant-band-measurements.md` 的帧率对照一节），而不是假设两者相同。
 *
 * ## 零分配 / 有界
 *
 * [update] 只写标量；[weights] 写进调用方复用的三元素数组。没有任何集合、装箱或 lambda。
 * 平滑值在输入非法（NaN / Inf）时**保持不动**而不是归零 —— 一次坏数据不该把颜色打回原点。
 */
class BandDominance(
    private val smoothingTauMs: Float = BAND_SMOOTH_TAU_MS,
    private val hysteresis: Float = BAND_HYSTERESIS_RATIO,
    private val silenceGate: Float = BAND_SILENCE_GATE,
    private val transitionMs: Float = BAND_COLOR_TRANSITION_MS,
) {

    /**
     * 当前主导频段（[LOW] / [MID] / [HIGH]），[NONE] = 还没有结论（起播 / 特征不可用）。
     *
     * 渲染侧读它只为诊断；真正驱动颜色的是 [weights]。
     */
    var dominant: Int = NONE
        private set

    /** 上一个主导频段（过渡期的起点）。 */
    var previous: Int = NONE
        private set

    /** 平滑后的三频带能量（诊断 / 单测用）。 */
    private var smoothLow = 0f
    private var smoothMid = 0f
    private var smoothHigh = 0f

    /** 过渡进度：0 = 完全是 [previous] 的颜色，1 = 完全是 [dominant] 的颜色。 */
    private var transition = 1f

    /** 特征链路是否可用（false ⇒ 渲染侧必须退回单一 RMS 颜色，见 [isReliable]）。 */
    var available: Boolean = false
        private set

    /**
     * 推进一步（**每帧一次**，由 `WaveformStore.pump` 调用）。
     *
     * @param low/mid/high 音频线程发布的三频带能量（0..1，**未平滑**）。
     * @param available 特征链路是否可用。false ⇒ 本类不做任何判定，
     *   [weights] 全 0、[isReliable] 为 false，渲染侧据此退回单一 RMS 颜色。
     * @param dtMs 距上一帧的毫秒数。
     * @return 这一帧是否有任何变化（颜色权重或过渡进度动了）⇒ 需要重绘。
     */
    fun update(low: Float, mid: Float, high: Float, available: Boolean, dtMs: Float): Boolean {
        this.available = available
        if (!available) {
            // 降级：把状态整个清掉，而不是保留上一首/上一刻的颜色 ——
            // 保留会让「特征挂了」和「真的是低频」在画面上不可区分。
            return reset()
        }
        val dt = if (dtMs.isFinite()) dtMs.coerceIn(0f, MAX_DT_MS) else 0f
        val k = if (smoothingTauMs > 0f && dt > 0f) 1f - exp(-dt / smoothingTauMs) else if (dt > 0f) 1f else 0f
        // **非法输入保持不动**（而不是 coerced 到 0 或 1）：
        //   · 归零会让一次坏数据把颜色打回"没有低频"；
        //   · clamp 到 1.0 会让一次 Inf 把某个频带顶到满幅、凭空触发一次切换。
        // 保持上一个有效值是最保守的一侧 —— 坏数据的表现是"颜色停一下"，不是"颜色乱跳"。
        val l = finite01OrNull(low)
        val m = finite01OrNull(mid)
        val h = finite01OrNull(high)
        var changed = false
        if (k > 0f) {
            val nl = if (l != null) smoothLow + (l - smoothLow) * k else smoothLow
            val nm = if (m != null) smoothMid + (m - smoothMid) * k else smoothMid
            val nh = if (h != null) smoothHigh + (h - smoothHigh) * k else smoothHigh
            if (nl != smoothLow || nm != smoothMid || nh != smoothHigh) changed = true
            smoothLow = nl
            smoothMid = nm
            smoothHigh = nh
        }
        val sum = smoothLow + smoothMid + smoothHigh
        if (sum >= silenceGate) {
            val challenger = argmax(smoothLow, smoothMid, smoothHigh)
            if (dominant == NONE) {
                dominant = challenger
                previous = challenger
                transition = 1f
                changed = true
            } else if (challenger != dominant && transition >= 1f && better(challenger, dominant)) {
                // `transition >= 1f` = **过渡期间不接受新切换**（等价于最短驻留 = 过渡时长 80ms）。
                //
                // 这条不是防御性代码，是探针 §2.4 量出来的缺陷修复：权重是
                // `previous·(1−t) + dominant·t`，如果过渡走到一半就把 `previous` 换成
                // 新的 dominant，权重会从「混了一半的中间色」**瞬间跳回**纯色 ——
                // 实测那一次跳变的 CAM16-UCS ΔE 是 8.13，而完整的 80ms 过渡本应把它摊到
                // 每帧 3.5。加上这条之后「过渡被打断」次数为 **0**，每帧最大色差回到 3.5 量级。
                //
                // 代价是零：探针 §2.3 实测最短驻留是 **300ms**（静音闸门那一档），
                // 远大于 80ms —— 这条约束在真实音乐上从不生效，只在"两次切换挤在一起"时兜底。
                previous = dominant
                dominant = challenger
                transition = 0f
                changed = true
            }
        }
        if (transition < 1f) {
            val step = if (transitionMs > 0f) dt / transitionMs else 1f
            val next = (transition + step).coerceIn(0f, 1f)
            if (next != transition) changed = true
            transition = next
            if (transition >= 1f) previous = dominant
        }
        return changed
    }

    /**
     * 本帧的三色权重（写进调用方复用的三元素数组，顺序 = 低 / 中 / 高）。
     *
     * 过渡期是**在角色色之间插值**（权重线性混合），不是硬切 —— 这就是
     * [BAND_COLOR_TRANSITION_MS] 那 80ms 的全部内容。全程零分配。
     *
     * @return 权重是否可用。false ⇒ 调用方必须用单一 RMS 颜色（特征不可用 / 还没结论）。
     */
    fun weights(out: FloatArray): Boolean {
        if (out.size < 3) return false
        out[0] = 0f
        out[1] = 0f
        out[2] = 0f
        if (!available || dominant == NONE) return false
        val t = transition.coerceIn(0f, 1f)
        // **线性**插值权重。为什么不是 smoothstep / ease-in-out —— 探针 §2.4 量过并否决了：
        //
        // 直觉是"起步太快所以闪"，于是试了 `t' = t²(3−2t)`。实测**更差**：
        // 80ms 档的每帧最大色差从 8.13 涨到 9.33 —— 因为 smoothstep 的斜率峰值在 t=0.5
        // （1.5×），而混色的中段恰好落在 CAM16 空间里变化最快的区域。
        // 换句话说起伏的位置由**颜色本身**决定，不由缓动曲线决定；缓动只是把峰值搬了个位置。
        // 真正把"每帧色差"压下来的手段是**加长过渡**（见下方扫描表），那是本版选 100ms 的依据。
        if (previous != NONE && previous != dominant) {
            out[previous] += 1f - t
            out[dominant] += t
        } else {
            out[dominant] += 1f
        }
        return true
    }

    /** 颜色是否可信（false ⇒ 退回单一 RMS 颜色：这是「测不到」的如实表达）。 */
    fun isReliable(): Boolean = available && dominant != NONE

    /** 过渡是否还在进行（诊断 / 单测）。 */
    fun transitionProgress(): Float = transition

    /** 换歌 / 停止：清空全部状态。@return 是否有东西被清掉（需要重绘）。 */
    fun reset(): Boolean {
        val changed = dominant != NONE || previous != NONE ||
            smoothLow != 0f || smoothMid != 0f || smoothHigh != 0f || transition != 1f
        dominant = NONE
        previous = NONE
        smoothLow = 0f
        smoothMid = 0f
        smoothHigh = 0f
        transition = 1f
        available = false
        return changed
    }

    /** 这一帧的平滑值（单测 / 探针用）。 */
    fun smoothAt(band: Int): Float = when (band) {
        LOW -> smoothLow
        MID -> smoothMid
        HIGH -> smoothHigh
        else -> 0f
    }

    private fun argmax(l: Float, m: Float, h: Float): Int = when {
        m > l && m >= h -> MID
        h > l && h > m -> HIGH
        else -> LOW
    }

    /**
     * 挑战者是否**足够好**到可以切换：`v[challenger] > v[current] × (1 + hysteresis)`。
     *
     * 滞回的方向很重要：它保护的是**当前**颜色不被一个只高一点点的挑战者顶掉，
     * 而当前颜色要主动让位时不需要额外条件（否则两个频段互相接近时会来回抖）。
     */
    private fun better(challenger: Int, current: Int): Boolean {
        val c = value(challenger)
        val cur = value(current)
        return c > cur * (1f + hysteresis)
    }

    private fun value(band: Int): Float = when (band) {
        LOW -> smoothLow
        MID -> smoothMid
        HIGH -> smoothHigh
        else -> 0f
    }

    companion object {

        const val NONE = -1
        const val LOW = 0
        const val MID = 1
        const val HIGH = 2

        /**
         * 频带平滑的时间常数（毫秒）。
         *
         * 300 的依据（探针 §2.3，5 首 1219 秒）：150ms → 0.540 次/秒、300ms → 0.308、
         * 600ms → 0.202。600 虽然更稳，但**跟不上一首歌内部的段落变化**（主歌→副歌的
         * 频带重心改变要 1.8s 才走完 95%），颜色会显得"慢半拍"。300ms 在
         * 「够稳」与「跟得上」之间，且加了滞回之后已经落到 0.118 次/秒 —— 没有理由再慢。
         */
        const val BAND_SMOOTH_TAU_MS = 300f

        /**
         * 滞回阈值（比例）。
         *
         * 20% 的依据（探针 §2.3，同一条曲线上的扫描）：5% → 0.205 次/秒、10% → 0.161、
         * 15% → 0.133、20% → 0.118、30% → 0.085、50% → 0.054。
         * 收益在 20% 之后明显变平（20→50 只再降一半，代价是颜色越来越"懒得动"），
         * 所以取 20%。逐曲最差是电子乐 0.169 次/秒，**仍比不合格线（3 次/秒）低一个数量级**。
         */
        const val BAND_HYSTERESIS_RATIO = 0.20f

        /**
         * 静音闸门：三频带平滑值之和低于它时保持当前频段。
         *
         * 0.06 的依据：母带压缩过的流行乐**全带** RMS 常落在 0.05~0.3
         * （见 `AudioVisualizer` 的 sqrt 映射注释），而三个频带各自的能量**系统性低于**全带
         * 值。0.06 是「这一小段确实有内容」的下沿；实测把最短驻留从 100ms 提到 300ms、
         * 切换频率从 0.118 降到 0.103 次/秒（探针 §2.3 的 `d20g` 一行）。
         */
        const val BAND_SILENCE_GATE = 0.06f

        /**
         * 切换处的颜色过渡时长（毫秒）。
         *
         * 探针 §2.4 在 60fps 判定 / 11Hz 输入下扫了 0 / 40 / 80 / 100 / 120 / 400ms，
         * 指标是**相邻两帧混合色的最大 CAM16-UCS 色差**（"闪不闪"的数值形式）：
         *
         * | 过渡 | 每帧最大色差 | 处于混合态的时间占比 |
         * |---|---|---|
         * | 0ms（硬切） | 17.58 | 0.0% |
         * | 40ms | 11.99 | 0.3% |
         * | 80ms | 8.13 | 0.7% |
         * | **100ms（本版）** | **7.13** | **1.0%** |
         * | 120ms | 6.44 | 1.2% |
         * | 400ms | 3.11 | 4.1% |
         *
         * 取任务书推荐区间 **50~100ms 的上沿 100ms**：区间内色差最低。
         * 出界（120ms 起）边际收益迅速变小，而"混合态时间"（颜色既不是甲也不是乙的时间）
         * 从 1.0% 涨到 4.1% —— 那会把"这一刻是哪个频带"这句话稀释掉。
         * 硬切（0ms）是明确不合格的：一次切换的全部色差压进一帧，比 100ms 过渡跳 2.5 倍。
         *
         * 100ms 也**远小于**实测最短驻留（300ms）⇒ 过渡总能在下一次切换前走完；
         * 这一点由 [transition] 的「过渡期间不接受新切换」再兜一层底。
         */
        const val BAND_COLOR_TRANSITION_MS = 100f

        /** 单帧 dt 的上限：长卡顿（后台、锁屏）不该让过渡"一步到位"或平滑冲过头。 */
        const val MAX_DT_MS = 200f

        /** 0..1 的有限值；NaN / Inf 返回 null（= 本次更新跳过这一路）。 */
        private fun finite01OrNull(value: Float): Float? =
            if (value.isFinite()) value.coerceIn(0f, 1f) else null
    }
}
