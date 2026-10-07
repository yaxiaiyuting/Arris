/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
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
 * v3.0.0：输入换成真实音频特征（RMS 包络 + 音频线程判定的瞬态）。
 *
 * ## 它解决什么问题
 *
 * 背景呼吸（A）、节拍脉冲（B）、歌词律动（B）、粒子与光晕（C）都需要同一个东西：
 * 「现在有多响、刚刚是不是一下重拍」。v2.9.0 的数据源是每回调一个全带 RMS + 低频通道；
 * v3.0.0 换成 `MotionBindings`（音频线程一次遍历算出的 RMS / 三频段 / 质心 / 瞬态），
 * 于是这里只剩两件事：
 *
 * | 输出 | 语义 | 用途 |
 * |---|---|---|
 * | [level] | 平滑后的响度（0..1，起音快 / 回落慢） | 背景呼吸幅度、节拍脉冲幅度 |
 * | [pulse] | 重拍脉冲（1 = 刚触发，指数衰减到 0） | 封面浮动、播放键脉冲、歌词行缩放 |
 * | [strongBeat] | 本帧的瞬态是否达到「强拍」门槛 | 光晕圈数 / 冲击波幅度 |
 *
 * ## 两条路径，靠 [MotionBindings.available] 切换（不是靠 try/catch）
 *
 * - **特征链路可用**：瞬态由音频线程判定（`AudioFeatureExtractor` 每缓冲一次，
 *   不漏缓冲），本层只做「把计数变成脉冲」的衰减动画；
 * - **不可用**：回落 v2.9.0 的内置判据（低频通道相对基线的突变 + 冷却）。
 *   判据参数**逐字引用** [WaveformEffectsState] 的常量，两条路径给出的 onset 时刻
 *   因此是一致的，不会出现「波形跳了但背景没跳」。
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
    private var strongHold = false

    /** 平滑响度（0..1），已 clamp。 */
    fun level(): Float = level

    /** 重拍脉冲（0..1）。 */
    fun pulse(): Float = pulse

    /** 本帧的瞬态是否达到「强拍」门槛。 */
    var strongBeat: Boolean = false
        private set

    /**
     * 每帧推进一步（v2.9.0 的老重载：只有全带 RMS，走内置判据）。
     *
     * @param newestBar 最新的**未平滑**柱值。与 `WaveformEffectsState` 同一个口径：
     *   用未平滑值，否则起音会被 τ=22ms 的平滑抹圆。
     * @param active 播放中且未在缓冲。false 时 [level]/[pulse] 单调回落到 0，
     *   并清空基线 —— 暂停期间基线若继续衰减到 0，起播第一根柱必然被判成 onset（误触发）。
     * @return 画面是否需要重绘（还有动画没收敛）。
     */
    fun update(newestBar: Float, dtMs: Float, active: Boolean): Boolean =
        update(newestBar, newestBar, dtMs, active, 0, 0f, externalAvailable = false)

    /** v2.9.0 的老重载：全带 + 低频，走内置判据。 */
    fun update(newestBar: Float, newestBass: Float, dtMs: Float, active: Boolean): Boolean =
        update(newestBar, newestBass, dtMs, active, 0, 0f, externalAvailable = false)

    /**
     * v3.0.0：**特征驱动**的重载。
     *
     * @param newestBar 全带 RMS（未平滑）：用于**响度包络**（背景呼吸的幅度）。
     * @param newestBass 低频能量（未平滑）：**只在** [externalAvailable] 为 false 时
     *   用作内置节拍判据的输入（特征链路可用时瞬态由音频线程给出）。
     * @param transients 本帧新发生的瞬态次数（来自 [MotionBindings.transients]）。
     * @param strength 最近一次瞬态的强度（0..1）。
     * @param externalAvailable 音频特征链路是否可用（false ⇒ 走内置判据）。
     */
    fun update(
        newestBar: Float,
        newestBass: Float,
        dtMs: Float,
        active: Boolean,
        transients: Int,
        strength: Float,
        externalAvailable: Boolean,
    ): Boolean {
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
            strongBeat = false
            strongHold = false
            return changed || level != 0f
        }

        // 响度包络跟全带 RMS（背景呼吸要的是"整体多响"，不是"有没有鼓"）。
        val levelValue = if (newestBar.isFinite()) newestBar.coerceIn(0f, 1f) else 0f
        // 起音快 / 回落慢：与 WaveformRing 的 ATTACK/RELEASE 同源手感，
        // 但这一层要的是"音量包络"而不是"柱高"，所以时间常数更大（背景不该跟着每个缓冲块抖）。
        val tau = if (levelValue > level) ATTACK_TAU_MS else RELEASE_TAU_MS
        val k = 1f - exp(-dt / tau)
        if (level != levelValue) {
            val next = level + (levelValue - level) * k
            // 吸附：无限逼近永远"不等于"目标，不吸附就会永远要求重绘 ——
            // 表现是「音乐放着、画面已经全黑，帧时钟还在 60fps 空转」。
            // 这**不是**理论风险：v2.9.0 的实现在这条分支上没有吸附，
            // `MotionBindingsTest.静音输入下所有随时间变化的量都收敛到零` 把它抓了出来。
            level = when {
                !next.isFinite() -> levelValue
                kotlin.math.abs(next - levelValue) < LEVEL_EPSILON -> levelValue
                else -> next.coerceIn(0f, 1f)
            }
            changed = true
        }

        if (externalAvailable) {
            // ---- 特征链路：瞬态由音频线程判定 ----
            baseline = 0f
            cooldownMs = 0f
            val fired = transients > 0
            if (fired) {
                // 一帧里发生多次时脉冲不叠加（叠加会超过 1 然后被 clamp，反而看不出"更强"）；
                // 强度交给 strongBeat 表达。
                pulse = 1f
                changed = true
            }
            val nextStrong = fired && strength >= STRONG_BEAT_STRENGTH
            if (nextStrong != strongHold) {
                strongHold = nextStrong
                changed = true
            }
            strongBeat = nextStrong
            return changed
        }

        // ---- 内置判据（v2.9.0 的行为，特征链路不可用时走这条）----
        strongHold = false
        val onsetSource = if (newestBass.isFinite() && newestBass >= 0f) newestBass else newestBar
        val value = if (onsetSource.isFinite()) onsetSource.coerceIn(0f, 1f) else 0f
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
            // 强拍：绝对增量达到普通门槛的 2.4 倍（0.12），与 v2.9.0 逐字一致。
            strongBeat = jump >= STRONG_BEAT_ABS_MIN
            changed = true
        } else {
            strongBeat = false
        }
        return changed
    }

    /** 单测 / 诊断：当前基线（只有内置判据路径会更新它）。 */
    fun baselineValue(): Float = baseline

    /** 换歌 / 停止时清空。 */
    fun clear() {
        level = 0f
        baseline = 0f
        cooldownMs = 0f
        pulse = 0f
        strongBeat = false
        strongHold = false
    }

    companion object {
        /** 响度包络起音时间常数（毫秒）。比柱子的 22ms 慢 —— 背景不该跟着一个缓冲块跳。 */
        const val ATTACK_TAU_MS = 90f

        /** 响度包络回落时间常数（毫秒）。 */
        const val RELEASE_TAU_MS = 260f

        /** 重拍脉冲的衰减时间常数（毫秒）：350ms 衰减到 1/e，肉眼刚好"弹一下"。 */
        const val PULSE_TAU_MS = 350f

        /** 内置判据的强拍门槛（相对基线的绝对增量）。普通门槛是 `ONSET_ABS_MIN = 0.05`，这里 2.4 倍。 */
        const val STRONG_BEAT_ABS_MIN = 0.12f

        /**
         * v3.0.0：特征链路下的强拍门槛（瞬态强度 0..1）。
         *
         * 0.35 的依据：瞬态强度是「增量 ÷ 0.20」的 clamp（见
         * `AudioFeatureExtractor.ONSET_STRENGTH_FULL`），0.35 对应增量 0.07 ——
         * 落在内置判据的强拍门槛（0.12）**之下**是有意的：音频线程的判据在
         * **每一次回调**上都判定，捕获到的增量比 UI 帧上看到的更锐利（帧上那根柱
         * 已经被「取最新一根」平滑过），所以同一个击打在两条链路上的数值不可直接比。
         * 取 0.35 是让「强拍」在两条链路上**同样稀有**（真机听感校准的起点，可由单测钉住，
         * 但数值本身仍需真机复核 —— 见 `docs/verification/v3.0.0/probe/probe-motion-binding.md`）。
         */
        const val STRONG_BEAT_STRENGTH = 0.35f

        /** 脉冲吸附阈值：小于它直接归零，避免"永远差一点、永远要重绘"。 */
        const val PULSE_EPSILON = 0.004f

        /** 响度吸附阈值（同上）。 */
        const val LEVEL_EPSILON = 0.002f
    }
}

/**
 * v3.0.0 · 音频驱动：**背景层的冲击波 / 光晕 / 粒子**（纯逻辑，定长 SoA，零分配）。
 *
 * 与 `WaveformEffectsState` 的关系：那个画在**波形条内部**（归一化坐标相对 28 根柱子），
 * 这个画在**整屏背景层**（归一化坐标相对屏幕）。两者的池是独立的 —— 波形条在竖屏
 * 根本不挂载，而背景层在竖屏**必须**有粒子。
 *
 * ## 与 v2.9.0 的三处差别（都是「绑定到真实音频特征」那条铁律的直接后果）
 *
 * | 效果 | v2.9.0 | v3.0.0 |
 * |---|---|---|
 * | 光晕 | 强拍触发，幅度固定 | **瞬态**触发，幅度随**击打力度**（[MotionBindings.strengthScale]），炫技档**多圈**（错开起始进度） |
 * | 冲击波 | 无 | **瞬态**触发，从画面中心扩散的径向渐晕，寿命独立于光晕 |
 * | 粒子 | 强拍触发一小簇（固定 6 个） | **中高频能量**驱动的连续生成，速率与能量正相关（[MotionBindings.particleRateHz]） |
 *
 * ## 有界性（铁律 4，逐条给依据）
 *
 * | 量 | 上限 | 依据 |
 * |---|---|---|
 * | 光晕 | [HALO_CAPACITY] = 4 | 瞬态冷却 180ms + 寿命 900ms + 炫技档每次 2 圈 ⇒ 稳态最多约 10 个"想要"存活，池按游标覆盖最旧的，**硬上界 4** |
 * | 冲击波 | [SHOCK_CAPACITY] = 2 | 同源触发、寿命 700ms；2 个已经能读出"连着两下" |
 * | 粒子 | [PARTICLE_CAPACITY] = 40 | 高密度档 26 个/秒 × 寿命 900ms ≈ 24 个稳态；40 是硬上界且让"池满覆盖最旧"路径可达 |
 * | 每帧新粒子 | [MAX_PARTICLES_PER_FRAME] = 4 | 一帧最多补 4 个，长卡顿之后不会一次性炸出一屏 |
 *
 * 池满时**覆盖最旧的**（游标轮转），绝不扩容、绝不等待 —— 与 v2.8.0 的取舍一致。
 */
class MotionBackdropState(
    private val random: Random = Random(PARTICLE_RANDOM_SEED),
) {
    // ---- 光晕（瞬态驱动，幅度随力度、可多圈）----
    private val haloProgress = FloatArray(HALO_CAPACITY) { -1f }
    private val haloScale = FloatArray(HALO_CAPACITY) { 1f }

    /**
     * 光晕的**出生点**（归一化坐标，相对整屏）。
     *
     * v3.0.0（真机观感修正）：以前所有冲击波/光晕都从**画面正中**扩散，
     * 连着几下鼓点就是同一个位置反复炸圈，看起来像"一个固定的装饰动画"。
     * 现在每次瞬态取一个随机出生点（[SPAWN_X_MIN]..[SPAWN_X_MAX] /
     * [SPAWN_Y_MIN]..[SPAWN_Y_MAX]），同一击的多圈**共用同一个出生点**
     * （否则一次击打会看起来像两件独立的事）。
     */
    private val haloX = FloatArray(HALO_CAPACITY) { 0.5f }
    private val haloY = FloatArray(HALO_CAPACITY) { HALO_CENTER_Y_FRACTION }
    private var haloCursor = 0

    // ---- 冲击波（瞬态驱动，径向渐晕）----
    private val shockProgress = FloatArray(SHOCK_CAPACITY) { -1f }
    private val shockScale = FloatArray(SHOCK_CAPACITY) { 1f }

    /** 冲击波的出生点（与光晕同源、同一次瞬态取同一个点）。 */
    private val shockX = FloatArray(SHOCK_CAPACITY) { 0.5f }
    private val shockY = FloatArray(SHOCK_CAPACITY) { HALO_CENTER_Y_FRACTION }
    private var shockCursor = 0

    // ---- 粒子（中高频能量驱动，连续生成）----
    private val particleX = FloatArray(PARTICLE_CAPACITY)
    private val particleY = FloatArray(PARTICLE_CAPACITY)
    private val particleVx = FloatArray(PARTICLE_CAPACITY)
    private val particleVy = FloatArray(PARTICLE_CAPACITY)
    private val particleLife = FloatArray(PARTICLE_CAPACITY)
    private var particleCursor = 0

    /** 粒子生成累加器（个）。**有界**：只在 `[0, 1)` 区间内推进，绝不无限增长。 */
    private var particleEmitAcc = 0f

    /**
     * 每帧推进一步。
     *
     * @param bindings 本帧的音频特征（瞬态次数 / 强度 / 中高频能量）。
     * @param motion 当前档位的能力位（含炫技档的密度与圈数）。
     * @param active 播放中且未在缓冲。false 时不产生新的特效，但**已存在的会自然演完**
     *   （冻结在半空比让它消失更难看，而且寿命有上界 ⇒ 不会永远重绘）。
     * @return 是否需要重绘。
     */
    fun update(
        bindings: MotionBindings,
        motion: MotionEffects,
        dtMs: Float,
        active: Boolean,
    ): Boolean {
        val dt = dtMs.coerceIn(0f, 200f)
        var changed = false

        // 关掉的能力位：立刻清空（用户改设置后画面要马上跟上，而不是让残留粒子飘完）。
        if (!motion.haloBloom && hasHalos()) {
            haloProgress.fill(-1f)
            changed = true
        }
        if (!motion.shockwave && hasShocks()) {
            shockProgress.fill(-1f)
            changed = true
        }
        if (!motion.particles && hasParticles()) {
            particleLife.fill(0f)
            particleEmitAcc = 0f
            changed = true
        }

        if (advanceHalos(dt)) changed = true
        if (advanceShocks(dt)) changed = true
        if (advanceParticles(dt)) changed = true

        if (!active) {
            // 暂停：累加器归零，恢复播放时不补触发暂停期间"欠下"的粒子。
            particleEmitAcc = 0f
            return changed
        }

        if (bindings.transients > 0) {
            val scale = MotionBindings.strengthScale(bindings.strength)
            // 一次瞬态一个出生点：冲击波与光晕**共用**它（它们是同一件事的两种表现）。
            val spawnX = SPAWN_X_MIN + random.nextFloat() * (SPAWN_X_MAX - SPAWN_X_MIN)
            val spawnY = SPAWN_Y_MIN + random.nextFloat() * (SPAWN_Y_MAX - SPAWN_Y_MIN)
            if (motion.haloBloom) {
                spawnHalos(scale, motion.haloRings, spawnX, spawnY)
                changed = true
            }
            if (motion.shockwave) {
                spawnShock(scale, spawnX, spawnY)
                changed = true
            }
        }

        if (motion.particles) {
            // 生成速率与**中高频能量**正相关；低于门槛恒为 0（安静段落不生成）。
            val rateHz = MotionBindings.particleRateHz(
                midHigh = bindings.midHigh,
                density = motion.particleDensity,
            )
            if (rateHz > 0f) {
                particleEmitAcc += rateHz * dt / 1000f
                var emitted = 0
                while (particleEmitAcc >= 1f && emitted < MAX_PARTICLES_PER_FRAME) {
                    particleEmitAcc -= 1f
                    spawnParticle()
                    emitted++
                }
                // 有界：累加器最多留 1 个"欠账"，避免速率计算异常时无限增长。
                if (particleEmitAcc > 1f) particleEmitAcc = 1f
                if (emitted > 0) changed = true
            } else {
                // 能量掉到门槛以下：把不足一个的零头丢掉，避免"攒到下一次爆发"。
                particleEmitAcc = 0f
            }
        }

        return changed
    }

    /** 换歌 / 停止。 */
    fun clear() {
        haloProgress.fill(-1f)
        shockProgress.fill(-1f)
        particleLife.fill(0f)
        haloCursor = 0
        shockCursor = 0
        particleCursor = 0
        particleEmitAcc = 0f
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

    private fun advanceShocks(dt: Float): Boolean {
        var changed = false
        val step = dt / SHOCK_LIFE_MS
        for (i in shockProgress.indices) {
            val progress = shockProgress[i]
            if (progress < 0f) continue
            val next = progress + step
            shockProgress[i] = if (next >= 1f) -1f else next
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

    private fun hasShocks(): Boolean {
        for (i in shockProgress.indices) if (shockProgress[i] >= 0f) return true
        return false
    }

    private fun hasParticles(): Boolean {
        for (i in particleLife.indices) if (particleLife[i] > 0f) return true
        return false
    }

    /** 一次瞬态扩 [rings] 圈光晕，圈与圈之间错开起始进度（"多圈"的全部实现）。 */
    private fun spawnHalos(scale: Float, rings: Int, x: Float, y: Float) {
        val count = rings.coerceIn(1, HALO_CAPACITY)
        var spawned = 0
        while (spawned < count) {
            val slot = haloCursor
            haloCursor = (haloCursor + 1) % HALO_CAPACITY
            haloProgress[slot] = MotionBindings.ringStartOffset(spawned)
            haloScale[slot] = scale
            haloX[slot] = x
            haloY[slot] = y
            spawned++
        }
    }

    private fun spawnShock(scale: Float, x: Float, y: Float) {
        val slot = shockCursor
        shockCursor = (shockCursor + 1) % SHOCK_CAPACITY
        shockProgress[slot] = 0f
        shockScale[slot] = scale
        shockX[slot] = x
        shockY[slot] = y
    }

    private fun spawnParticle() {
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
    }

    // ---- 渲染侧只读访问（draw 阶段调用，零分配）----

    val haloCapacity: Int get() = HALO_CAPACITY
    val shockCapacity: Int get() = SHOCK_CAPACITY
    val particleCapacity: Int get() = PARTICLE_CAPACITY

    /** 光晕扩散进度（0..1）；负数 = 该槽位空着。 */
    fun haloProgressAt(index: Int): Float = haloProgress[index]

    /** 光晕的幅度倍率（0.55..1，随击打力度）。 */
    fun haloScaleAt(index: Int): Float = haloScale[index]

    /** 光晕出生点的归一化 x（0..1，相对屏幕宽）。 */
    fun haloXAt(index: Int): Float = haloX[index]

    /** 光晕出生点的归一化 y（0..1，相对屏幕高）。 */
    fun haloYAt(index: Int): Float = haloY[index]

    /** 冲击波扩散进度（0..1）；负数 = 该槽位空着。 */
    fun shockProgressAt(index: Int): Float = shockProgress[index]

    /** 冲击波的幅度倍率（0.55..1）。 */
    fun shockScaleAt(index: Int): Float = shockScale[index]

    /** 冲击波出生点的归一化 x（0..1）。 */
    fun shockXAt(index: Int): Float = shockX[index]

    /** 冲击波出生点的归一化 y（0..1）。 */
    fun shockYAt(index: Int): Float = shockY[index]

    fun particleLifeAt(index: Int): Float = particleLife[index]
    fun particleXAt(index: Int): Float = particleX[index]
    fun particleYAt(index: Int): Float = particleY[index]

    /** 单测 / 诊断：当前存活数。 */
    fun aliveHalos(): Int {
        var n = 0
        for (i in haloProgress.indices) if (haloProgress[i] >= 0f) n++
        return n
    }

    fun aliveShocks(): Int {
        var n = 0
        for (i in shockProgress.indices) if (shockProgress[i] >= 0f) n++
        return n
    }

    fun aliveParticles(): Int {
        var n = 0
        for (i in particleLife.indices) if (particleLife[i] > 0f) n++
        return n
    }

    /** 单测 / 诊断：粒子生成累加器。 */
    fun emitAccumulator(): Float = particleEmitAcc

    companion object {
        const val HALO_CAPACITY = 4
        const val SHOCK_CAPACITY = 2
        const val PARTICLE_CAPACITY = 40

        /** 一帧最多生成几个粒子（长卡顿后的有界补发）。 */
        const val MAX_PARTICLES_PER_FRAME = 4

        /** 光晕寿命（毫秒）。 */
        const val HALO_LIFE_MS = 900f

        /** 冲击波寿命（毫秒）。比光晕短：它是"一下"，不是"一圈余韵"。 */
        const val SHOCK_LIFE_MS = 700f

        /** 粒子寿命（毫秒）。 */
        const val PARTICLE_LIFE_MS = 900f

        const val PARTICLE_SPEED_MIN = 0.18f
        const val PARTICLE_SPEED_MAX = 0.55f
        const val PARTICLE_GRAVITY = 0.35f
        const val PARTICLE_ANGLE_MIN_FRACTION = 0.15
        const val PARTICLE_ANGLE_MAX_FRACTION = 0.85

        /** 出生点横向散布（归一化，相对屏幕宽）。 */
        const val PARTICLE_SPAWN_SPREAD = 0.3f

        // ---- v3.0.0：冲击波 / 光晕的**随机出生点**（归一化，相对整屏）----

        /**
         * 出生点的横向范围。
         *
         * 0.18..0.82 而不是 0..1：光晕的最大半径是屏幕短边的 0.7 倍，贴边出生会让
         * 大部分圆弧跑到屏幕外，读起来像"一个缺角"。这个区间让圆心至少离左右边缘
         * 18% 屏宽，圆看起来仍然是"从某处扩散开"，而不是"从边上挤进来"。
         */
        const val SPAWN_X_MIN = 0.18f
        const val SPAWN_X_MAX = 0.82f

        /**
         * 出生点的纵向范围。
         *
         * 0.28..0.72：避开状态栏/标题栏（顶部）与底部控制条 —— 那两个区域被前景内容压着，
         * 圆在那里扩散基本看不见。中段也覆盖了封面与歌词区，观感上"在画面里到处炸"。
         */
        const val SPAWN_Y_MIN = 0.28f
        const val SPAWN_Y_MAX = 0.72f

        /** 默认（无随机时）的纵向中心：与 v2.9.0 的固定位置一致（封面中心偏上）。 */
        const val HALO_CENTER_Y_FRACTION = 0.42f

        /** 固定随机种子：同一段音频每次得到同一套粒子分布（排查"是不是随机看着乱"用）。 */
        const val PARTICLE_RANDOM_SEED = 0x2C0FFEE
    }
}
