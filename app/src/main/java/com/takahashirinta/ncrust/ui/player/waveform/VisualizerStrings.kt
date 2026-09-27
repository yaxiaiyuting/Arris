/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

/**
 * v2.8.0 · P1-A：波形分级设置项的**文案常量**与 i18n 属性名清单。
 *
 * ## 这个文件为什么存在
 *
 * 本版有两条并行的任务线：波形分级（本任务）与设置页/i18n（另一条）。
 * 为了避免两边同时改 `ui/i18n/` 打架，本任务**不写任何 locale 文件**，
 * 只在这里把「需要哪些字符串、中文与英文各写什么」固定成常量：
 *
 *  - [Property] 里的常量 = 要加进 `Strings.SettingsStrings` 的**属性名**（逐字，别改名 ——
 *    设置页消费方按这些名字取文案）；
 *  - [Zh] / [En] 里的常量 = 建议文案（中文为基准，英文给参考翻译）；
 *  - 其余 6 个 locale（zh-TW / ja-JP / ja-MY / ko-KP / de-DE / ru-RU）由 i18n 任务补齐。
 *
 * 设置项与键的对应关系（键名见 [VisualizerPrefs]）：
 *
 * | 属性名 | 键 | 控件 |
 * |---|---|---|
 * | `visualizerTierLabel` / `…Description` + `…Simple/Refined/Showcase` | `visualizer_tier` | 三选一 |
 * | `visualizerShowcaseLabel` / `…Description` | `visualizer_showcase` | 开关 |
 * | `visualizerShockwaveLabel` / `…Description` | `visualizer_shockwave` | 开关 |
 * | `visualizerParticlesLabel` / `…Description` | `visualizer_particles` | 开关 |
 * | `visualizerPerspectiveLabel` / `…Description` | `visualizer_perspective` | 开关 |
 * | `visualizerDragLabel` / `…Description` | `visualizer_drag` | 开关 |
 * | `visualizerNotSpectrumHint` | —（说明文字） | 纯文本 |
 *
 * ## 文案上的三条硬要求（都不是措辞偏好，是事实约束）
 *
 * 1. **`visualizerDragLabel` 必须如实写成「点按切换着色」，不能写「拖拽」**：
 *    本版没有实现拖拽（理由是风险，见 `AudioVisualizerBars` 的 KDoc），实际行为是
 *    点一下波形条在「渐变流动」与「按时序着色」之间切换。文案写「拖拽」就是骗用户。
 * 2. **`visualizerNotSpectrumHint` 必须写明不是频谱**：本版没有频域数据源，
 *    亮度表达的是**时间新旧**，不是低/中/高频。不写清楚，用户一定会把它读成频谱。
 * 3. **低端机默认档要说出来**：用户看到的「已选：简洁」可能是系统按设备判据解析出来的默认值，
 *    不是他自己选的（[VisualizerPrefs.hasExplicitTier]）。说明里写清「低端设备默认简洁」，
 *    用户才不会以为设置被改过。
 */
object VisualizerStrings {

    /** 要加进 `Strings` / `SettingsStrings` 的属性名（唯一字面量落点）。 */
    object Property {
        const val TIER_LABEL = "visualizerTierLabel"
        const val TIER_DESCRIPTION = "visualizerTierDescription"
        const val TIER_SIMPLE = "visualizerTierSimple"
        const val TIER_REFINED = "visualizerTierRefined"
        const val TIER_SHOWCASE = "visualizerTierShowcase"
        const val SHOWCASE_LABEL = "visualizerShowcaseLabel"
        const val SHOWCASE_DESCRIPTION = "visualizerShowcaseDescription"
        const val SHOCKWAVE_LABEL = "visualizerShockwaveLabel"
        const val SHOCKWAVE_DESCRIPTION = "visualizerShockwaveDescription"
        const val PARTICLES_LABEL = "visualizerParticlesLabel"
        const val PARTICLES_DESCRIPTION = "visualizerParticlesDescription"
        const val PERSPECTIVE_LABEL = "visualizerPerspectiveLabel"
        const val PERSPECTIVE_DESCRIPTION = "visualizerPerspectiveDescription"
        const val DRAG_LABEL = "visualizerDragLabel"
        const val DRAG_DESCRIPTION = "visualizerDragDescription"
        const val NOT_SPECTRUM_HINT = "visualizerNotSpectrumHint"

        /** 全部属性名（顺序 = 设置页里的呈现顺序）。i18n 任务可按它逐条核对。 */
        val ALL: List<String> = listOf(
            TIER_LABEL, TIER_DESCRIPTION, TIER_SIMPLE, TIER_REFINED, TIER_SHOWCASE,
            SHOWCASE_LABEL, SHOWCASE_DESCRIPTION,
            SHOCKWAVE_LABEL, SHOCKWAVE_DESCRIPTION,
            PARTICLES_LABEL, PARTICLES_DESCRIPTION,
            PERSPECTIVE_LABEL, PERSPECTIVE_DESCRIPTION,
            DRAG_LABEL, DRAG_DESCRIPTION,
            NOT_SPECTRUM_HINT,
        )
    }

    /** 简体中文（基准文案）。 */
    object Zh {
        const val TIER_LABEL = "波形效果档位"
        const val TIER_DESCRIPTION =
            "简洁：圆角柱 + 峰值保持；精致：再加渐变流动、柱顶光点与呼吸；炫技：再加冲击波、粒子与 3D 透视。低端设备（低内存 / 3 GB 级 / Android 7.x）默认简洁档。"
        const val TIER_SIMPLE = "简洁"
        const val TIER_REFINED = "精致"
        const val TIER_SHOWCASE = "炫技"
        const val SHOWCASE_LABEL = "炫技效果"
        const val SHOWCASE_DESCRIPTION = "仅「炫技」档生效；关闭后炫技档与精致档外观一致。"
        const val SHOCKWAVE_LABEL = "冲击波"
        const val SHOCKWAVE_DESCRIPTION = "强节拍时从中心扩散涟漪。节拍来自响度相对基线的突变，慢歌或动态压缩强的曲目可能不触发。"
        const val PARTICLES_LABEL = "粒子"
        const val PARTICLES_DESCRIPTION = "节拍时迸出粒子。粒子数量固定有上限，低端设备上开销随绘制笔数线性增加。"
        const val PERSPECTIVE_LABEL = "3D 透视"
        const val PERSPECTIVE_DESCRIPTION = "给波形条加一个固定倾角。会新增一层渲染层，是炫技档里合成开销最高的一项。"
        const val DRAG_LABEL = "点按切换着色"
        const val DRAG_DESCRIPTION = "点一下波形条，在「渐变流动」与「按时序着色」之间切换。本版未实现拖拽（手势与播放器冲突风险未在真机验证）。"
        const val NOT_SPECTRUM_HINT = "波形亮度表示时间新旧，不是频谱：本版没有频域数据，不区分低/中/高频。"
    }

    /** English（参考翻译；另外 6 个 locale 由 i18n 任务补齐）。 */
    object En {
        const val TIER_LABEL = "Waveform effects"
        const val TIER_DESCRIPTION =
            "Simple: rounded bars with peak hold. Refined: adds a flowing gradient, top dots and breathing. Showcase: adds shockwave, particles and 3D perspective. Low-end devices (low RAM / 3 GB class / Android 7.x) start at Simple."
        const val TIER_SIMPLE = "Simple"
        const val TIER_REFINED = "Refined"
        const val TIER_SHOWCASE = "Showcase"
        const val SHOWCASE_LABEL = "Showcase effects"
        const val SHOWCASE_DESCRIPTION = "Only applies to the Showcase tier; when off, Showcase looks identical to Refined."
        const val SHOCKWAVE_LABEL = "Shockwave"
        const val SHOCKWAVE_DESCRIPTION = "Ripples out from the centre on strong beats. Beats are detected from loudness jumps over a baseline, so slow or heavily compressed tracks may never trigger it."
        const val PARTICLES_LABEL = "Particles"
        const val PARTICLES_DESCRIPTION = "Particles burst out on beats. The pool is fixed and capped; draw calls grow linearly on low-end GPUs."
        const val PERSPECTIVE_LABEL = "3D perspective"
        const val PERSPECTIVE_DESCRIPTION = "Tilts the waveform strip. Adds one render layer — the most expensive compositing item in the Showcase tier."
        const val DRAG_LABEL = "Tap to switch colouring"
        const val DRAG_DESCRIPTION = "Tap the strip to switch between the flowing gradient and time-ordered tinting. Dragging is not implemented in this version (gesture conflicts were not verified on a device)."
        const val NOT_SPECTRUM_HINT = "Brightness shows recency, not frequency: this version has no frequency data and does not separate lows/mids/highs."
    }
}
