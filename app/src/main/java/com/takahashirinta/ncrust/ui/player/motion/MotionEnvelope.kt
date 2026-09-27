/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.motion

import com.takahashirinta.ncrust.ui.player.waveform.WaveformEffectsState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * v2.9.0 · A/B/C：**界面动效的每帧包络**（纯逻辑，JVM 可直测）。
 *
 * ## 它解决什么问题
 *
 * 背景呼吸（A）、节拍脉冲（B）、歌词律动（B）、粒子与光晕（C）都需要同一个东西：
 * 「现在有多响、刚刚是不是一下重拍」。数据源只有 v2.8.0 已有的那个每回调一个 RMS
 * （`WaveformStore` 的环形缓冲），所以这里把它加工成三个**每帧只推进一步**的量：
 *
 * | 输出 | 语义 | 用途 |
 * |---|---|---|
 * | [level] | 平滑后的响度（0..1，起音快 / 回落慢） | 背景呼吸幅度、节拍脉冲幅度 |
 * | [pulse] | 重拍脉冲（1 = 刚触发，指数衰减到 0） | 封面浮动、播放键脉冲、歌词行缩放 |
 * | 强拍标记 | 比普通节拍更高的门槛 | 光晕扩散（C 档，任务书 §6.2 要求「强节拍」） |
 *
 * ## 为什么不用 `WaveformRing.breatheScale()`（v2.8.0 已经有一个呼吸相位）
 *
 * 那个是**正弦相位**（`BREATHE_PERIOD_MS = 3200ms` 的自由振荡），与音量无关 ——
 * 它的语义是「一直在轻轻呼吸」，挂在**波形条自己的 alpha** 上。
 * 任务书 §4.2 要的是「随 RMS 明暗/缩放波动」，即**音量驱动**。两者是两件事，
 * 硬改成同一个会把 v2.8.0 的波形呼吸一起改掉（那是回归）。
 *
 * ## 为什么另起一个节拍检测器（而不复用 `WaveformEffectsState` 的）
 *
 * 那个检测器与**涟漪/粒子池**是同一个对象（`spawnRipple()` 就被它直接调用），
 * 而它的生命周期挂在哪条波形挂在哪 —— 竖屏手机根本不挂波形（`PlayerLayout.visualizerSlot`
 * 在「手机竖屏」这一格恒为 false）。界面动效在竖屏**必须**有节拍数据，所以这里需要
 * 一个独立的检测器。两者的**判据参数逐字相同**（直接引用
 * [WaveformEffectsState] 的常量，不复制数值）：同一段音频在两条链路上给出的
 * onset 时刻因此是一致的，不会出现「波形跳了但背景没跳」。
 *
 * ## 有界性（铁律 4）
 *
 * 全部状态都是定长标量，无数组增长；数值一律 `coerceIn` + `isFinite` 防御
 * （NaN 一旦进入缩放因子会让整层背景消失，且**不会**抛异常 —— 最难查的那种）。
 */
class MotionEnvelope {

    private var level = 0f
    private var baseline = 0f
    private var cooldownMs = 0f
    private var pulse = 0f

    /** 平滑响度（0..1），已 clamp。 */
    fun level(): Float = level

    /** 重拍脉冲（0..1）。 */
    fun pulse(): Float = pulse

    /**
     * 每帧推进一步。
     *
     * @param newestBar 最新的**未平滑**柱值（`WaveformStore` 的 targets 尾元素）。
     *   与 `WaveformEffectsState` 同一个口径：用未平滑值，否则起音会被 τ=22ms 的平滑抹圆。
     * @param active 播放中且未在缓冲。false 时 [level]/[pulse] 单调回落到 0，
     *   并清空基线 —— 暂停期间基线若继续衰减到 0，起播第一根柱必然被判成 onset（误触发）。
     * @return 画面是否需要重绘（还有动画没收敛）。
     */
    fun update(newestBar: Float, dtMs: Float, active: Boolean): Boolean {
        val dt = dtMs.coerceIn(0f, 200f)
        var changed = false

        // 脉冲衰减：与刷新率无关的指数形式（同 WaveformRing 的时间常数写法）。
        if (pulse > 0f) {
            val next = pulse * exp(-dt / PULSE_TAU_MS)
            pulse = if (next < PULSE_EPSILON) 0f else next
            changed = true
        }

        if (!active) {
            if (level != 0f) {
                val next = level * exp(-dt / RELEASE_TAU_MS)
                level = if (next < LEVEL_EPSILON) 0f else next
            }
            baseline = 0f
            cooldownMs = 0f
            return changed || level != 0f
        }

        val value = if (newestBar.isFinite()) newestBar.coerceIn(0f, 1f) else 0f
        // 起音快 / 回落慢：与 WaveformRing 的 ATTACK/RELEASE 同源手感，
        // 但这一层要的是"音量包络"而不是"柱高"，所以时间常数更大（背景不该跟着每个缓冲块抖）。
        val tau = if (value > level) ATTACK_TAU_MS else RELEASE_TAU_MS
        val k = 1f - exp(-dt / tau)
        if (level != value) {
            val next = level + (value - level) * k
            level = if (next.isFinite()) next.coerceIn(0f, 1f) else value
            changed = true
        }

        val jump = value - baseline
        // 先算 jump 再更新基线：否则一次强 onset 会立刻把基线抬起来、把自己判掉。
        baseline += (value - baseline) * (1f - exp(-dt / WaveformEffectsState.BASELINE_TAU_MS))
        if (!baseline.isFinite()) baseline = 0f
        if (cooldownMs > 0f) cooldownMs = (cooldownMs - dt).coerceAtLeast(0f)

        val onset = value >= WaveformEffectsState.ONSET_MIN_RMS &&
            jump >= WaveformEffectsState.ONSET_ABS_MIN &&
            jump >= baseline * WaveformEffectsState.ONSET_REL_FACTOR &&
            cooldownMs <= 0f
        if (onset) {
            cooldownMs = WaveformEffectsState.ONSET_COOLDOWN_MS
            pulse = 1f
            // 强拍：绝对增量达到普通门槛的 2.4 倍（0.12）。任务书 §6.2 要求光晕只在
            // **强**节拍触发 —— 每个普通拍都炸一圈光环会变成噪声。
            strongBeat = jump >= STRONG_BEAT_ABS_MIN
            changed = true
        } else {
            strongBeat = false
        }
        return changed
    }

    /** 本帧是否触发了一次**强**节拍（只在触发的这一帧为 true，消费方自己转成持续状态）。 */
    var strongBeat: Boolean = false
        private set

    /** 单测 / 诊断：当前基线。 */
    fun baselineValue(): Float = baseline

    /** 换歌 / 停止时清空。 */
    fun clear() {
        level = 0f
        baseline = 0f
        cooldownMs = 0f
        pulse = 0f
        strongBeat = false
    }

    companion object {
        /** 响度包络起音时间常数（毫秒）。比柱子的 22ms 慢 —— 背景不该跟着一个缓冲块跳。 */
        const val ATTACK_TAU_MS = 90f

        /** 响度包络回落时间常数（毫秒）。 */
        const val RELEASE_TAU_MS = 260f

        /** 重拍脉冲的衰减时间常数（毫秒）：350ms 衰减到 1/e，肉眼刚好"弹一下"。 */
        const val PULSE_TAU_MS = 350f

        /** 强拍门槛（相对基线的绝对增量）。普通门槛是 `ONSET_ABS_MIN = 0.05`，这里取 2.4 倍。 */
        const val STRONG_BEAT_ABS_MIN = 0.12f

        /** 脉冲吸附阈值：小于它直接归零，避免"永远差一点、永远要重绘"。 */
        const val PULSE_EPSILON = 0.004f

        /** 响度吸附阈值（同上）。 */
        const val LEVEL_EPSILON = 0.002f
    }
}

/**
 * v2.9.0 · C 档：**背景层的粒子与光晕**（纯逻辑，定长 SoA，零分配）。
 *
 * 与 `WaveformEffectsState` 的关系：那个画在**波形条内部**（归一化坐标相对 28 根柱子），
 * 这个画在**整屏背景层**（归一化坐标相对屏幕）。两者的池是独立的 —— 波形条在竖屏
 * 根本不挂载，而背景层在竖屏**必须**有粒子。
 *
 * ## 有界性（铁律 4，逐条给依据）
 *
 * | 量 | 上限 | 依据 |
 * |---|---|---|
 * | 光晕 | [HALO_CAPACITY] = 2 | 强拍门槛（0.12 增量）本身就比普通拍稀有；寿命 900ms 内最多重叠 2 圈 |
 * | 粒子 | [PARTICLE_CAPACITY] = 32 | 任务书 §6.1 明确「如 16~32 个」；寿命 900ms + 冷却 180ms ⇒ 稳态 ≤ 25，取 32 是硬上界且让池满路径可达 |
 * | 每次强拍粒子 | [PARTICLES_PER_BEAT] = 6 | 一次重拍一小簇；6 × 5 拍写满一轮池 |
 *
 * 池满时**覆盖最旧的**（游标轮转），绝不扩容、绝不等待 —— 与 v2.8.0 的取舍一致。
 */
class MotionBackdropState(
    private val random: Random = Random(PARTICLE_RANDOM_SEED),
) {
    private val haloProgress = FloatArray(HALO_CAPACITY) { -1f }
    private var haloCursor = 0

    private val particleX = FloatArray(PARTICLE_CAPACITY)
    private val particleY = FloatArray(PARTICLE_CAPACITY)
    private val particleVx = FloatArray(PARTICLE_CAPACITY)
    private val particleVy = FloatArray(PARTICLE_CAPACITY)
    private val particleLife = FloatArray(PARTICLE_CAPACITY)
    private var particleCursor = 0

    /**
     * 每帧推进一步。
     *
     * @param strongBeat 本帧是否触发强拍（来自 [MotionEnvelope.strongBeat]）。
     * @param haloEnabled / [particlesEnabled] 能力位：关掉时**立刻**清空对应状态
     *   （用户改设置后画面要马上跟上，而不是让残留粒子飘完）。
     * @return 是否需要重绘。
     */
    fun update(strongBeat: Boolean, dtMs: Float, haloEnabled: Boolean, particlesEnabled: Boolean): Boolean {
        val dt = dtMs.coerceIn(0f, 200f)
        var changed = false
        if (!haloEnabled && hasHalos()) {
            haloProgress.fill(-1f)
            changed = true
        }
        if (!particlesEnabled && hasParticles()) {
            particleLife.fill(0f)
            changed = true
        }
        if (advanceHalos(dt)) changed = true
        if (advanceParticles(dt)) changed = true
        if (strongBeat) {
            if (haloEnabled) {
                haloProgress[haloCursor] = 0f
                haloCursor = (haloCursor + 1) % HALO_CAPACITY
                changed = true
            }
            if (particlesEnabled) {
                spawnParticles()
                changed = true
            }
        }
        return changed
    }

    fun clear() {
        haloProgress.fill(-1f)
        particleLife.fill(0f)
        haloCursor = 0
        particleCursor = 0
    }

    private fun advanceHalos(dt: Float): Boolean {
        var changed = false
        val step = dt / HALO_LIFE_MS
        for (i in haloProgress.indices) {
            val progress = haloProgress[i]
            if (progress < 0f) continue
            val next = progress + step
            haloProgress[i] = if (next >= 1f) -1f else next
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
            val next = life - lifeStep
            if (next <= 0f) {
                particleLife[i] = 0f
            } else {
                particleLife[i] = next
                particleX[i] += particleVx[i] * dtSeconds
                particleY[i] += particleVy[i] * dtSeconds
                particleVy[i] += PARTICLE_GRAVITY * dtSeconds
            }
            changed = true
        }
        return changed
    }

    private fun hasHalos(): Boolean {
        for (i in haloProgress.indices) if (haloProgress[i] >= 0f) return true
        return false
    }

    private fun hasParticles(): Boolean {
        for (i in particleLife.indices) if (particleLife[i] > 0f) return true
        return false
    }

    private fun spawnParticles() {
        var emitted = 0
        while (emitted < PARTICLES_PER_BEAT) {
            val slot = particleCursor
            particleCursor = (particleCursor + 1) % PARTICLE_CAPACITY
            // 从画面中心附近的窄扇区向上/向外抛，避免粒子出生就贴边（与波形粒子同一手法）。
            val angle = PI * (PARTICLE_ANGLE_MIN_FRACTION +
                (PARTICLE_ANGLE_MAX_FRACTION - PARTICLE_ANGLE_MIN_FRACTION) * random.nextFloat())
            val speed = PARTICLE_SPEED_MIN + random.nextFloat() * (PARTICLE_SPEED_MAX - PARTICLE_SPEED_MIN)
            particleX[slot] = 0.5f + (random.nextFloat() - 0.5f) * PARTICLE_SPAWN_SPREAD
            particleY[slot] = 0.5f
            particleVx[slot] = (cos(angle) * speed).toFloat()
            particleVy[slot] = (-sin(angle) * speed).toFloat()
            particleLife[slot] = 1f
            emitted++
        }
    }

    // ---- 渲染侧只读访问（draw 阶段调用，零分配）----

    val haloCapacity: Int get() = HALO_CAPACITY
    val particleCapacity: Int get() = PARTICLE_CAPACITY

    /** 光晕扩散进度（0..1）；负数 = 该槽位空着。 */
    fun haloProgressAt(index: Int): Float = haloProgress[index]

    fun particleLifeAt(index: Int): Float = particleLife[index]
    fun particleXAt(index: Int): Float = particleX[index]
    fun particleYAt(index: Int): Float = particleY[index]

    companion object {
        const val HALO_CAPACITY = 2
        const val PARTICLE_CAPACITY = 32
        const val PARTICLES_PER_BEAT = 6

        /** 光晕寿命（毫秒）。 */
        const val HALO_LIFE_MS = 900f

        /** 粒子寿命（毫秒）。 */
        const val PARTICLE_LIFE_MS = 900f

        const val PARTICLE_SPEED_MIN = 0.18f
        const val PARTICLE_SPEED_MAX = 0.55f
        const val PARTICLE_GRAVITY = 0.35f
        const val PARTICLE_ANGLE_MIN_FRACTION = 0.15
        const val PARTICLE_ANGLE_MAX_FRACTION = 0.85

        /** 出生点横向散布（归一化，相对屏幕宽）。 */
        const val PARTICLE_SPAWN_SPREAD = 0.3f

        /** 固定随机种子：同一段音频每次得到同一套粒子分布（排查"是不是随机看着乱"用）。 */
        const val PARTICLE_RANDOM_SEED = 0x2C0FFEE
    }
}
