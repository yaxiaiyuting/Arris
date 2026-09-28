/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

/**
 * v2.8.0 · P1-A：波形可视化的**分级纯逻辑**（无 Android 依赖 ⇒ 可被 JVM 单测直接断言）。
 *
 * ## 为什么效果分级要抽成纯函数
 *
 * 探针（`docs/verification/v2.8.0/probe-waveform-tier.md` §3.1）的结论是「细粒度 N 个开关
 * = 2^N 个组合，每个组合都要 release + 真机 A/B 才能声称结论」—— 组合爆炸**无法取证**。
 * 所以对外只暴露 3 档（简洁/精致/炫技），对内仍然保留**能力位**（每个效果一个 bit）：
 * 档位 → 能力位是一次纯函数映射，单测可以逐格断言「这一档到底开了哪些效果」，
 * 不需要设备、不需要截图。
 *
 * ## 三条与探针逐条对应的硬约束
 *
 * 1. **默认档不是常量**（本文件只管纯函数，读盘与设备判据在 [VisualizerPrefs]）：
 *    低端机默认简洁档。三条静态判据与仓库既有落点一致 ——
 *    `ActivityManager.isLowRamDevice`（覆盖 ≤1GB）、`MemoryInfo.totalMem`（覆盖 3GB 级老机）、
 *    `SDK_INT < 26`（覆盖 API 24/25），取**并集**。
 * 2. **静态判据只决定起始档**：它是可预期、可单测的；实测帧时间只允许「再降一级」
 *    且每进程最多一次（[downgradedTier]），两者**不得互相覆盖**，否则同一台机器每次冷启动观感不同。
 * 3. **真频谱能力位恒为 false**：本版没有频域数据源（探针 §0 发现 2：全仓 FFT/Goertzel 零命中），
 *    所以 [VisualizerEffects.spectrumColoring] 是一个**永远为 false 的能力位**，
 *    而不是一个「打开了但没效果」的开关 —— 谁将来真的做出频域数据，改的是这一个位。
 */

/** 三档的取值与语义（对外只有这三个数字）。 */
object VisualizerTier {

    /** 简洁：现状的逐像素基线 + 圆角柱 + 峰值保持。低端机默认。 */
    const val SIMPLE = 0

    /** 精致：默认档。简洁 + 渐变流动 + 柱顶光点 + 呼吸。 */
    const val REFINED = 1

    /** 炫技：默认关闭，用户手动开。精致 + 冲击波 / 粒子 / 3D 透视 / 点按交互（各自还有细分开关）。 */
    const val SHOWCASE = 2

    /** 合法取值。越界值一律回落「解析出的默认档」，不写回盘（同 `offline_cache_mb` 的口径）。 */
    val RANGE: IntRange = SIMPLE..SHOWCASE

    /**
     * 低端机判据之一：物理内存 ≤ 3.5 GiB。
     *
     * **口径说明（重要，别照抄 PlaybackService 的数值）**：`PlaybackService.kt:291` 里
     * `LOW_RAM_TOTAL_BYTES = 3_500L * 1024 * 1024 * 1024` 写的是 3.5 **TiB**（3500 × 1024³），
     * 实际效果是「任何真机都命中低内存档」——那是播放缓冲档位的历史实现，不属于本次改动范围，
     * 因此这里**取它想表达的值**（3.5 GiB = 3500 × 1024²）而不是复制它的字面量。
     * 若将来把两处收敛成同一个常量，必须连着把那边的量级一起修，否则本档位会退化成「永远 T0」。
     */
    const val LOW_RAM_TOTAL_BYTES: Long = 3_500L * 1024 * 1024

    /** 低端机判据之二：API < 26（`Build.VERSION_CODES.O`）。*/
    const val LOW_TIER_MAX_SDK_INT: Int = 25

    /**
     * **设备静态判据 → 起始档**（纯函数：三个参数就是三条既有判据，不读任何全局状态）。
     *
     * 与仓库既有的三处判据一一对应：
     *  - `isLowRamDevice`：`ui/player/AudioVisualizer.kt`（帧率档）、`lyric/LyricsDisplayPrefs.kt`（软边降级）；
     *  - `totalMemBytes`：`player/PlaybackService.kt`（缓冲档）、`NcrustApplication.kt`（图片缓存档）；
     *  - `sdkInt`：`ui/player/AudioVisualizer.kt`（帧率档）。
     *
     * 本函数是这三条判据在**波形档位上**的单一落点。刻意不引入第四条判据
     * （探针 §3.2 的告诫：口径越多，越没法解释「为什么这台机器长这样」）。
     *
     * `totalMemBytes <= 0` 视为**未知**（取不到内存信息时不判低端机）：宁可给默认档，
     * 也不要因为一次读取失败把所有设备都锁进简洁档。
     */
    fun defaultTier(isLowRamDevice: Boolean, totalMemBytes: Long, sdkInt: Int): Int {
        val lowMem = totalMemBytes in 1..LOW_RAM_TOTAL_BYTES
        val oldApi = sdkInt in 1..LOW_TIER_MAX_SDK_INT
        return if (isLowRamDevice || lowMem || oldApi) SIMPLE else REFINED
    }

    /** 越界值归一化：合法的原样返回，非法的回落到 [fallback]（[fallback] 自身非法时用精致档）。 */
    fun sanitize(raw: Int, fallback: Int): Int =
        if (raw in RANGE) raw else if (fallback in RANGE) fallback else REFINED

    /**
     * v3.0.0：**`downgradedTier` 已删除**（连同 `VisualizerPrefs.applyAutoDowngrade`
     * 与整个自动降级机制）。渲染只看用户选的档位 —— 理由见
     * `ui/player/motion/MotionDegrade` 的 KDoc：判据不知道是谁把帧顶起来的，
     * 而它却会改写用户的选择，于是「为什么这台机器少一层特效」在代码里找不到答案。
     */
}

/**
 * 一档渲染到底开哪些效果（**不可变值对象**：只在设置变化时构造一次，帧路径里只读）。
 *
 * 每个字段都对应探针 §1 表格里的一行，字段名后的注释写清「落在哪、成本量级、为什么这么定」。
 */
class VisualizerEffects(
    /** 当前档位（[VisualizerTier] 的取值之一）。 */
    val tier: Int,
    /** 是否已经发生过一次自动降级（只用于诊断显示，不再影响渲染决策）。 */
    val autoDowngraded: Boolean,
    /** A：镜像对称（以中线上下对称绘制）。**现状本来就满足**（柱以 half 为中心画），增量 0。 */
    val mirror: Boolean,
    /** A：圆角柱（`drawRoundRect`；间距是现状已有的 `gap = width × 0.28 / n`）。 */
    val rounded: Boolean,
    /** A：峰值保持（独立数组，不用每帧分配）。 */
    val peaks: Boolean,
    /** A：按时序着色（越旧越淡的 alpha 阶梯）—— **这不是频谱**，见 [spectrumColoring]。 */
    val timeOrderedTint: Boolean,
    /** B：渐变流动（缓存 1 个 Brush + TileMode.Repeated + 每帧只改平移相位）。 */
    val flow: Boolean,
    /** B：柱顶光点（纯色小圆，不加径向光晕：28 个 shader/帧 是探针明确不建议的路线）。 */
    val dots: Boolean,
    /** B：呼吸（整体亮度正弦调制；只在有信号时参与重绘判据，见 `WaveformRing`）。 */
    val breathe: Boolean,
    /** C：冲击波（RMS 相对基线的突变 + 冷却时间触发，涟漪数/寿命/冷却全部有界）。 */
    val shockwave: Boolean,
    /** C：粒子（定长池 + SoA 数组，零分配）。 */
    val particles: Boolean,
    /** C：3D 透视（父层 `graphicsLayer` 恒定倾角；新增一个 render layer，代价见文档）。 */
    val perspective: Boolean,
    /** C：可视化条上的点按交互（**本版是「拖拽交互」的有界降级**，理由见 [VisualizerPrefs] 的 KDoc）。 */
    val tapInteraction: Boolean,
    /**
     * **真频谱着色的能力位，本版恒为 false。**
     *
     * 探针 §1「多频段分色」的结论：当前数据链路只有一个 RMS 标量，真频段要么在音频线程倍增计算
     * （与 v2.2.1 P0 同级风险），要么新增 PCM 环 + UI 侧 FFT（重做数据层）。本版两者都不做，
     * 因此**不提供任何「按频段分色」的视觉**：与其画一个会被读成频谱的假东西，
     * 不如把「按新旧着色」如实命名成 [timeOrderedTint]。这个位留着是为了让「将来做真频谱」
     * 有一个单点落点，而不是散落的 if。
     */
    val spectrumColoring: Boolean = false,
    /**
     * v3.0.0：**多频段能量对波形的调制档**（0 关 / 1 主导频带着色 / 2 着色 + 三频带能量条）。
     *
     * v3.2.2：**与档位解耦** —— 它由 `motion_wave_bands` 一个开关决定（默认开），
     * 三档都生效；只有 [MODE_WAVE_BAND_LANES] 那一档还要求炫技档。
     *
     * ## 它不是频谱（写在这里，防止下一个人误读）
     *
     * 曲线/柱子的横轴仍然是**时间**（左旧右新）：颜色表达的是"**这一刻**哪个频带占主导"，
     * 不是"这一段频率是多少"；能量条只显示三个频带各自的**当前能量**，
     * 没有频率轴、没有分帧、没有窗函数。[spectrumColoring] 仍然恒为 `false`。
     *
     * 数据源是 `AudioFeatureExtractor` 的**两个一阶低通**（150 Hz / 2 kHz），
     * 频带之间泄漏很大 —— 它足以支撑「画面上看得出来哪一带在动」，
     * **不足以**支撑任何「这是频谱」的读法。
     */
    val waveBandMode: Int = MODE_WAVE_BAND_OFF,
) {
    /**
     * 互斥规则（**API 层二选一，不是优先级问题**）：同一笔绘制要么 `color=` 要么 `brush=`。
     * 渐变流动占用了整条色带 ⇒ 按时序着色与（假想的）真频谱着色都必须关掉。
     * 反过来，一旦真有了频段数据，也应该由它取代渐变流动，而不是叠在一起。
     */
    val colorChannelMode: Int
        get() = when {
            flow -> MODE_FLOW
            spectrumColoring -> MODE_SPECTRUM
            else -> MODE_TIME_TINT
        }

    /** C 档是否有任何一项开着（用于决定要不要挂 `clipToBounds` 与 render layer）。 */
    val anyShowcase: Boolean get() = shockwave || particles || perspective

    /** v3.0.0：多频段调制是否开着（逐柱着色）。 */
    val waveBandOn: Boolean get() = waveBandMode != MODE_WAVE_BAND_OFF

    /** v3.0.0：是否还要画三频带能量条。 */
    val waveBandLanes: Boolean get() = waveBandMode == MODE_WAVE_BAND_LANES

    companion object {
        const val MODE_TIME_TINT = 0
        const val MODE_FLOW = 1
        const val MODE_SPECTRUM = 2

        /** v3.0.0：多频段调制关闭。 */
        const val MODE_WAVE_BAND_OFF = 0

        /** v3.0.0：逐柱按「明亮度占比」着色（时间轴不变）。 */
        const val MODE_WAVE_BAND_TINT = 1

        /** v3.0.0：逐柱着色 **+** 三频带能量条（炫技档）。 */
        const val MODE_WAVE_BAND_LANES = 2

        /**
         * 与档位无关的**基线效果集**（只有 A 档效果）。
         *
         * 单例常量，不是每次构造：`WaveformRing.pump` 的老重载（无 effects 参数）与
         * 单测都复用它 —— 那里如果写成默认参数 `effects = of(...)`，每次调用都会分配一个对象，
         * 而 `pump` 是**每帧**调用的。
         */
        val BASELINE: VisualizerEffects = of(
            tier = VisualizerTier.SIMPLE,
            showcase = false,
            shockwave = false,
            particles = false,
            perspective = false,
            tapInteraction = false,
        )

        /**
         * 档位 + C 档细分开关 → 效果位。**唯一的映射落点**。
         *
         * 规则（逐条对应任务书 §B 与探针 §3.1）：
         *  - 三档**都**包含 A 档效果（圆角柱 / 峰值保持 / 镜像对称 / 缓动衰减——缓动在 `WaveformRing` 里，与本表无关）；
         *  - `tier >= REFINED` 才开 B 档（渐变流动 / 柱顶光点 / 呼吸）；
         *  - `tier == SHOWCASE` **且** `showcase == true` 才逐项看 C 档细分开关；
         *  - `timeOrderedTint` 与 `flow` 互斥（[colorChannelMode] 是这条规则的唯一读法）。
         */
        fun of(
            tier: Int,
            showcase: Boolean,
            shockwave: Boolean,
            particles: Boolean,
            perspective: Boolean,
            tapInteraction: Boolean,
            autoDowngraded: Boolean = false,
            /**
             * v3.0.0：多频段调制档（见 [VisualizerEffects.waveBandMode]）。
             *
             * 它**不是** C 档细分开关：任务书的档位表把「波形特别触发」放在**精致**档
             * （`tier >= REFINED` 即可），所以门槛单独写，不走 `showcaseOn`。
             * 默认 `OFF` ⇒ 老调用点（含 `BASELINE` 与 v2.8.0 的单测）行为一字不变。
             */
            waveBandMode: Int = MODE_WAVE_BAND_OFF,
        ): VisualizerEffects {
            val t = VisualizerTier.sanitize(tier, VisualizerTier.REFINED)
            val refined = t >= VisualizerTier.REFINED
            val showcaseOn = t >= VisualizerTier.SHOWCASE && showcase
            val flow = refined
            return VisualizerEffects(
                tier = t,
                autoDowngraded = autoDowngraded,
                mirror = true,
                rounded = true,
                peaks = true,
                timeOrderedTint = !flow,
                flow = flow,
                dots = refined,
                breathe = refined,
                shockwave = showcaseOn && shockwave,
                particles = showcaseOn && particles,
                perspective = showcaseOn && perspective,
                tapInteraction = showcaseOn && tapInteraction,
                // v3.2.2：`waveBandMode` **不再按档位收窄**（v3.0.0 起这里写的是
                // `if (refined) waveBandMode else OFF`）。理由见 `MotionEffects.of` 的注释：
                // 频带着色是**波形自己的**属性，由它自己的开关（`motion_wave_bands`）管；
                // 按档位收窄的后果是「低端机默认档 = 简洁档 ⇒ 默认体验是一条单色曲线」，
                // 而那正是用户报的问题（「没有做出左中右分别代表低中高频率的感觉」）。
                // 档位仍然决定**别的**东西：渐变流动 / 光点 / 呼吸 / 冲击波 / 粒子 / 频带能量条。
                waveBandMode = waveBandMode,
            )
        }
    }
}
