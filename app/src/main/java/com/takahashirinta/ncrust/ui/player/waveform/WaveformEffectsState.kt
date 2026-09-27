/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * v2.8.0 · P1-A：C 档（炫技）的**有界**状态机 —— 冲击波涟漪 + 粒子。
 *
 * 纯逻辑（无 Android / 无 Compose 依赖）⇒ 可 JVM 直测；渲染在 `AudioVisualizer.kt`。
 *
 * ## 为什么全部用定长 SoA 数组
 *
 * 探针 §1「粒子」一栏：禁止 `List<Particle>` / data class，否则每帧都在分配。
 * 这里每个粒子 5 个 `FloatArray` 槽位（x / y / vx / vy / life），涟漪只要一个进度数组，
 * **构造时分配一次，之后每帧原地更新，零分配、零锁**。
 *
 * ## 节拍判据：相对基线的突变 + 冷却（不是凭空捏造）
 *
 * 数据源只有「每回调一个 RMS」。所以判据是**响度**层面的：
 *
 *  - 维护一条慢速基线（[BASELINE_TAU_MS] 的指数均值）；
 *  - 当「本根柱 − 基线」同时超过绝对门槛（[ONSET_ABS_MIN]）、相对门槛
 *    （基线的 [ONSET_REL_FACTOR] 倍）与最低响度（[ONSET_MIN_RMS]）时判为一次 onset；
 *  - 冷却 [ONSET_COOLDOWN_MS] 内不重复触发（一次瞬态只出一个涟漪）。
 *
 * **诚实边界（写进 i18n 与交付说明，不许含糊）**：
 *  - 时间分辨率受限于柱率（= `handleBuffer` 回调率，代码不保证固定值，通常是几十 Hz 量级）
 *    ⇒ 快节奏的连续鼓点可能并成一次；
 *  - RMS 是**响度**不是低频能量 ⇒ 底鼓 / 军鼓 / 人声重音**不可区分**；
 *  - 母带压缩强的曲目动态小 ⇒ 相对门槛可能一直不达标（表现为"不触发"，而不是"乱触发"）。
 *
 * ## 有界性（铁律 4：失败处理必须有界）
 *
 *  | 量 | 上限 | 依据 |
 * |---|---|---|
 * | 同时存在的涟漪 | [RIPPLE_CAPACITY] = 3 | 冷却 180ms + 寿命 700ms ⇒ 稳态最多 4 个，取 3 保证不超出 |
 * | 同时存在的粒子 | [PARTICLE_CAPACITY] = 16 | 最快节拍流（冷却 180ms、寿命 650ms）下同时存活 ≤ 20，取 16 既是硬上界、又让"池满覆盖最旧"路径可达 |
 * | 每次触发的粒子 | [PARTICLES_PER_BEAT] = 5 | 一次节拍一小簇；与池容量一起决定"最多 4 拍就写满一轮" |
 * | 涟漪寿命 | [RIPPLE_LIFE_MS] = 700 | 一眼能看完的扩散，且与心跳节奏同量级 |
 * | 粒子寿命 | [PARTICLE_LIFE_MS] = 650 | 同上；超过 1 秒会显得"飘" |
 *
 * 池满时的策略是**覆盖最旧的**（游标轮转），不是丢弃新的：覆盖的视觉表现是"最老的涟漪
 * 提前消失"，比"新节拍没反应"更可接受。绝不扩容、绝不等待。
 */
class WaveformEffectsState(
    /** 随机源：构造时建一次（固定种子 ⇒ 同一段音频的粒子分布可复现，便于排查）。 */
    private val random: Random = Random(PARTICLE_RANDOM_SEED),
) {
    // ---- 冲击波涟漪 ----
    /** 每个涟漪的扩散进度（0..1）；**负数 = 这个槽位空着**。 */
    private val rippleProgress = FloatArray(RIPPLE_CAPACITY) { -1f }
    private var rippleCursor = 0

    // ---- 粒子（SoA）----
    /** 归一化坐标（0..1，相对波形条自身），绘制时再乘宽高 ⇒ 分辨率无关。 */
    private val particleX = FloatArray(PARTICLE_CAPACITY)
    private val particleY = FloatArray(PARTICLE_CAPACITY)
    private val particleVx = FloatArray(PARTICLE_CAPACITY)
    private val particleVy = FloatArray(PARTICLE_CAPACITY)
    private val particleLife = FloatArray(PARTICLE_CAPACITY)
    private var particleCursor = 0

    // ---- 节拍检测 ----
    private var baseline = 0f
    private var cooldownMs = 0f

    /**
     * 每帧推进一步。
     *
     * @param newestBar 最新的**未平滑**柱值（`WaveformRing` 的 targets 尾元素）——
     *   探针 §1 明确要求用未平滑值，用平滑后的 bars 会把 onset 抹圆（起音 τ = 22ms）。
     * @param active 播放中且未在缓冲。false 时不产生新节拍，但**已存在的涟漪/粒子会自然演完**
     *   （冻结在半空比让它消失更难看，而且寿命有上界 ⇒ 不会永远重绘）。
     * @param effects 能力位：关掉某项时立刻清空它对应的状态（用户改设置后画面要马上跟上）。
     * @return 是否需要重绘。
     */
    fun update(newestBar: Float, dtMs: Float, active: Boolean, effects: VisualizerEffects): Boolean =
        update(newestBar, newestBar, dtMs, active, effects)

    /**
     * v2.9.0：**节拍判据改用低频（鼓 / 贝斯）通道**。
     *
     * ## 为什么
     *
     * 全带 RMS 对「一句高音」与「一下底鼓」给出的是同一种响应 —— 那是「涟漪乱触发、
     * 真鼓点反而不明显」的根源。低频通道来自 `PcmRms.analyze` 的**一阶低通**
     * （截止 150Hz，与全带 RMS 同一次逐样本遍历，不是 FFT、不是第二遍扫描），
     * 底鼓与贝斯落在这一带、人声与旋律重音不在。
     *
     * ## 诚实边界（不改，也不许含糊）
     *
     *  - 只有一个低频通道，**没有**低/中/高频之分 —— 底鼓与贝斯仍然不可区分；
     *  - 慢歌 / 无鼓的曲目（古典、清唱）低频能量低 ⇒ **触发变少是正确行为**，不是 bug；
     *  - 传入 `newestBass` 为 NaN/负数时回落全带值（老调用点与单测走的也是这条）。
     */
    fun update(
        newestBar: Float,
        newestBass: Float,
        dtMs: Float,
        active: Boolean,
        effects: VisualizerEffects,
    ): Boolean {
        val dt = dtMs.coerceIn(0f, 200f)
        var changed = false

        // 关掉的能力位：立刻清空（只在真的还有残留时才 fill，避免每帧白扫）。
        if (!effects.shockwave && hasRipples()) {
            rippleProgress.fill(-1f)
            changed = true
        }
        if (!effects.particles && hasParticles()) {
            particleLife.fill(0f)
            changed = true
        }

        if (advanceRipples(dt)) changed = true
        if (advanceParticles(dt)) changed = true

        if (!active) {
            // 暂停：基线归零，下次起播重新建立（否则暂停期间基线会衰减到 0，
            // 起播第一根柱必然被判成 onset —— 那是误触发）。
            baseline = 0f
            cooldownMs = 0f
            return changed
        }

        // 低频通道不可用时回落全带值：行为与 v2.8.0 的判据一致。
        val onsetSource = if (newestBass.isFinite() && newestBass >= 0f) newestBass else newestBar
        val value = if (onsetSource.isFinite()) onsetSource.coerceIn(0f, 1f) else 0f
        val jump = value - baseline
        // 先算 jump 再更新基线：否则一次强 onset 会立刻把基线抬起来，把自己判掉。
        baseline += (value - baseline) * (1f - exp(-dt / BASELINE_TAU_MS))
        if (!baseline.isFinite()) baseline = 0f
        if (cooldownMs > 0f) cooldownMs = (cooldownMs - dt).coerceAtLeast(0f)

        val onset = value >= ONSET_MIN_RMS &&
            jump >= ONSET_ABS_MIN &&
            jump >= baseline * ONSET_REL_FACTOR &&
            cooldownMs <= 0f
        if (!onset) return changed

        cooldownMs = ONSET_COOLDOWN_MS
        if (effects.shockwave) {
            spawnRipple()
            changed = true
        }
        if (effects.particles) {
            spawnParticles()
            changed = true
        }
        return changed
    }

    /** 换歌 / 停止时清空（与 `WaveformRing.clear()` 一起调用）。 */
    fun clear() {
        rippleProgress.fill(-1f)
        particleLife.fill(0f)
        rippleCursor = 0
        particleCursor = 0
        baseline = 0f
        cooldownMs = 0f
    }

    // ------------------------------------------------------------------
    // 内部推进
    // ------------------------------------------------------------------

    private fun advanceRipples(dt: Float): Boolean {
        var changed = false
        val step = dt / RIPPLE_LIFE_MS
        for (i in rippleProgress.indices) {
            val progress = rippleProgress[i]
            if (progress < 0f) continue
            val next = progress + step
            if (next >= 1f) {
                rippleProgress[i] = -1f
            } else {
                rippleProgress[i] = next
            }
            changed = true
        }
        return changed
    }

    private fun advanceParticles(dt: Float): Boolean {
        var changed = false
        val dtSeconds = dt / 1000f
        val lifeStep = dt / PARTICLE_LIFE_MS
        for (i in particleLife.indices) {
            val life = particleLife[i]
            if (life <= 0f) continue
            val nextLife = life - lifeStep
            if (nextLife <= 0f) {
                particleLife[i] = 0f
                changed = true
                continue
            }
            particleLife[i] = nextLife
            particleX[i] += particleVx[i] * dtSeconds
            particleY[i] += particleVy[i] * dtSeconds
            particleVy[i] += PARTICLE_GRAVITY * dtSeconds
            changed = true
        }
        return changed
    }

    private fun hasRipples(): Boolean {
        for (i in rippleProgress.indices) if (rippleProgress[i] >= 0f) return true
        return false
    }

    private fun hasParticles(): Boolean {
        for (i in particleLife.indices) if (particleLife[i] > 0f) return true
        return false
    }

    private fun spawnRipple() {
        rippleProgress[rippleCursor] = 0f
        rippleCursor = (rippleCursor + 1) % RIPPLE_CAPACITY
    }

    private fun spawnParticles() {
        var emitted = 0
        while (emitted < PARTICLES_PER_BEAT) {
            val slot = particleCursor
            particleCursor = (particleCursor + 1) % PARTICLE_CAPACITY
            // 上半平面扇形：角度 0.15π..0.85π（π/2 是正上方），避免粒子一出生就贴着边界。
            val angle = PI * (PARTICLE_ANGLE_MIN_FRACTION +
                (PARTICLE_ANGLE_MAX_FRACTION - PARTICLE_ANGLE_MIN_FRACTION) * random.nextFloat())
            val speed = PARTICLE_SPEED_MIN + random.nextFloat() * (PARTICLE_SPEED_MAX - PARTICLE_SPEED_MIN)
            particleX[slot] = 0.5f
            particleY[slot] = 0.5f
            particleVx[slot] = (cos(angle) * speed).toFloat()
            particleVy[slot] = (-sin(angle) * speed).toFloat()
            particleLife[slot] = 1f
            emitted++
        }
    }

    // ------------------------------------------------------------------
    // 渲染侧只读访问（draw 阶段调用，零分配）
    // ------------------------------------------------------------------

    val rippleCapacity: Int get() = RIPPLE_CAPACITY

    val particleCapacity: Int get() = PARTICLE_CAPACITY

    /** 涟漪扩散进度（0..1）；负数 = 该槽位空着（渲染侧直接跳过）。 */
    fun rippleProgressAt(index: Int): Float = rippleProgress[index]

    /** 粒子寿命（1 = 刚出生，0 = 已消失）。 */
    fun particleLifeAt(index: Int): Float = particleLife[index]

    /** 粒子归一化 x（0..1，相对波形条宽度）。 */
    fun particleXAt(index: Int): Float = particleX[index]

    /** 粒子归一化 y（0..1，相对波形条高度）。 */
    fun particleYAt(index: Int): Float = particleY[index]

    /** 单测 / 诊断：当前存活数。 */
    fun aliveRipples(): Int {
        var n = 0
        for (i in rippleProgress.indices) if (rippleProgress[i] >= 0f) n++
        return n
    }

    fun aliveParticles(): Int {
        var n = 0
        for (i in particleLife.indices) if (particleLife[i] > 0f) n++
        return n
    }

    /** 单测 / 诊断：当前基线（节拍判据的参照）。 */
    fun baselineValue(): Float = baseline

    companion object {
        /** 同时存在的涟漪上限。 */
        const val RIPPLE_CAPACITY = 3

        /**
         * 粒子池容量（定长，绝不扩容）。
         *
         * 16 不是随手取的：粒子寿命 650ms、节拍冷却 180ms ⇒ 最快节拍流下同时存活的粒子数
         * 最多 `(650/200 向上取整) × 5 = 20`（实测相邻两次触发最短 200ms，见 [ONSET_COOLDOWN_MS]），
         * 取 16 让**池满路径可达**（可被单测覆盖），同时仍是硬上界。
         * 若以后调大 [PARTICLES_PER_BEAT] 或 [PARTICLE_LIFE_MS]，必须同步复核这个数。
         */
        const val PARTICLE_CAPACITY = 16

        /** 一次节拍迸出的粒子数。 */
        const val PARTICLES_PER_BEAT = 5

        /** 涟漪寿命（毫秒）。 */
        const val RIPPLE_LIFE_MS = 700f

        /** 粒子寿命（毫秒）。 */
        const val PARTICLE_LIFE_MS = 650f

        /** 基线时间常数（毫秒）：约 0.8s 的响度记忆 —— 比一个乐句短、比一拍长。 */
        const val BASELINE_TAU_MS = 800f

        /** 冷却时间（毫秒）：一次瞬态只出一个涟漪；180ms 也把涟漪稳态数压在 4 以内。 */
        const val ONSET_COOLDOWN_MS = 180f

        /** 最低响度门槛：低于它一律不算节拍（避免安静段落被底噪触发）。 */
        const val ONSET_MIN_RMS = 0.12f

        /** 相对基线的绝对增量门槛。 */
        const val ONSET_ABS_MIN = 0.05f

        /** 相对基线的倍数门槛：涨幅要超过基线的 35% 才算"突变"。 */
        const val ONSET_REL_FACTOR = 0.35f

        /** 粒子初速下限（归一化 y 单位/秒）。 */
        const val PARTICLE_SPEED_MIN = 0.9f

        /** 粒子初速上限。 */
        const val PARTICLE_SPEED_MAX = 1.9f

        /** 粒子重力（归一化 y 单位/秒²）。 */
        const val PARTICLE_GRAVITY = 2.4f

        /** 粒子出射角下限（π 的比例）：0.15π ≈ 27°。 */
        const val PARTICLE_ANGLE_MIN_FRACTION = 0.15

        /** 粒子出射角上限：0.85π ≈ 153°。 */
        const val PARTICLE_ANGLE_MAX_FRACTION = 0.85

        /** 固定随机种子：同一段音频每次得到同一套粒子分布（排查"是不是随机看着乱"用）。 */
        const val PARTICLE_RANDOM_SEED = 0x5EED2F
    }
}
