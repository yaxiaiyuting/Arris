/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0「设置界面二级菜单重构」阶段 1：设置项的**依赖门控**（纯函数，JVM 可单测）。
 */

package com.takahashirinta.ncrust.ui.settings

import com.takahashirinta.ncrust.lyric.LyricsWordAnimationMode

/**
 * 门控原因。UI 用它决定「不挂载这一行」还是「挂载但置灰 + 给提示」——
 * 本文件只给判定，不含任何文案（文案在 `ui/i18n` 目录，由 i18n 任务维护）。
 */
enum class GatingReason {
    /** 无门控。 */
    NONE,

    /**
     * `lyrics_ttml_first` 硬依赖 `lyrics_ttml_enabled`（probe-settings-inventory §4.2）。
     * `ttmlEnabled == false` 时 TTML 根本不进回退链（`LyricSourceChain.kt:69-70`），
     * 这一项的值不参与任何判定 ⇒ **整行不挂载**（现状 `UserScreen.kt:598` 的 `if` 就是这个语义，
     * 注意 AGENTS.md 触摸陷阱 #1：隐藏必须走「不挂载」，不能只改 alpha）。
     */
    TTML_SOURCE_DISABLED,

    /**
     * `lyrics_sweep_quality` 软依赖 `lyrics_word_animation`（§4.1）：
     * 模式是 [LyricsWordAnimationMode.HARD_CUT] / [LyricsWordAnimationMode.OFF] 时，
     * 软边参数在渲染路径上完全用不到（`NcrustLyricsPanel.kt:677/704/750`，
     * `LyricsView.kt:497-499`）⇒ 行**仍然可见**，但**不可用**（应置灰 + 提示先开渐变扫过）。
     */
    SWEEP_ANIMATION_INACTIVE,

    /**
     * v2.8.0 新增：C 档（炫技）细分开关依赖 `visualizer_showcase` 总开关关闭 ⇒ 不可用。
     */
    SHOWCASE_DISABLED,

    /**
     * v2.8.0 新增：`visualizer_tier != 2`（T2 炫技档）⇒ C 档效果根本不参与绘制，不可用。
     */
    TIER_NOT_SHOWCASE_CAPABLE,

    /**
     * v3.2.0 新增：「界面动效」总闸（`ui_motion_enabled`）关着 ⇒ 所有的动效子开关
     * 改了也不会有任何画面变化（`MotionEffects.anyUiMotion == false`，A/B/C 三层全不挂载）。
     */
    UI_MOTION_DISABLED,

    /**
     * v3.2.0 新增：「界面律动」总闸（`motion_rhythm_enabled`）关着 ⇒ **律动类**的
     * 细粒度开关（背景呼吸 / 封面浮动 / 歌词律动 / 控制条脉冲）此刻不参与渲染。
     *
     * 注意它**不**影响冲击波 / 光晕 / 粒子 / 多频段波形 —— 那几项不是律动类，
     * 关律动闸不会碰它们（`MotionEffects.of` 里逐项可读）。
     */
    RHYTHM_DISABLED,

    /**
     * v3.2.0 新增：动效强度是**简洁档** ⇒ 这一项在简洁档不参与渲染。
     *
     * 简洁档自 v3.2.0 起是**静态档**（P0-B：用户选了简洁界面还在抖），
     * 全部逐帧的界面动效都收窄到精致档及以上 ⇒ 简洁档下这些开关是死开关，
     * 必须置灰而不是让用户点了没反应（`visualizer_showcase` 那条门控的同一条理由）。
     */
    TIER_BASIC_ONLY,
}

/** 一个条目的可见性 / 可用性判定结果。 */
data class SettingsAvailability(
    /** 是否应该挂载这一行（false = 整行不挂载，**不是** alpha 隐藏）。 */
    val visible: Boolean,
    /** 是否可交互 / 改了是否有效（false = 应置灰并给出 [reason] 对应的提示）。 */
    val enabled: Boolean,
    val reason: GatingReason,
) {
    companion object {
        /** 无门控：可见且可用。 */
        val FREE: SettingsAvailability = SettingsAvailability(true, true, GatingReason.NONE)

        /** 硬依赖不满足：整行不挂载。 */
        fun hidden(reason: GatingReason): SettingsAvailability =
            SettingsAvailability(visible = false, enabled = false, reason = reason)

        /** 软依赖不满足：可见但不可用。 */
        fun disabled(reason: GatingReason): SettingsAvailability =
            SettingsAvailability(visible = true, enabled = false, reason = reason)
    }
}

/**
 * 设置项的依赖门控（**纯函数**）。
 *
 * ## 语义边界（写在这里，也写在单测里）
 *
 * 本文件**只影响「可见性 / 可用性」**：
 * - 不改变任何落盘 key（门控不重命名、不新增、不删除 key）；
 * - 不改变任何默认值（键缺失时用的是 `SettingsRegistry` 里声明的期望默认值 —— 与既有
 *   `read*` 的默认值一致，见 `SettingsRegistryTest` / `SettingsDefaultsTest`）；
 * - **没有任何写盘能力**：输入是一个只读的 `(key) -> Any?` 查询函数，
 *   门控既不构造 `SharedPreferences`，也不 import 任何 Android 类型。
 *
 * ## 为什么用「查询函数」而不是 `SharedPreferences`
 *
 * 单测（与未来的 UI 预览）用一张 map 就能跑；而且签名上就没给写入的机会。
 * 真实调用点由 UI 层传 `{ key -> prefs.getAll()[key] }` 或逐键 `read*`。
 *
 * ## 未覆盖的依赖（如实声明，见 probe-settings-inventory §4.3 / §4.7 / §4.8）
 *
 * - `wifi_quality` / `mobile_quality` 依赖**设备 FLAC 解码能力**（`SongUrlFetcher.deviceSupportsFlac`）：
 *   这是平台能力不是 prefs 值，塞进本文件会破坏「纯函数」；
 * - `custom_bg_enabled` 依赖**背景文件是否存在**（`BackgroundImageManager.isActive`）：同上，需要 File/Context；
 * - `accent_source == SYSTEM` 依赖 **API 31+**（`AccentSource.kt:30-31`）：同上。
 *
 * 这三条由 UI 层单独处理（它们本来就只在 UI 里判断，且从 v1.x 起就是既有行为）。
 */
object SettingsVisibility {

    /** 需要「T2 炫技档」才可用的档位值（probe-waveform-tier.md §3.1：T2 炫技）。 */
    const val TIER_SHOWCASE: Int = 2

    /** `visualizer_tier` 的默认档（probe-waveform-tier.md §3.3：默认 T1 精致）。 */
    const val TIER_DEFAULT: Int = 1

    /** `visualizer_tier` 的合法取值（非法值一律回落 [TIER_DEFAULT]，同 `offline_cache_mb` 的口径）。 */
    val TIER_RANGE: IntRange = 0..TIER_SHOWCASE

    /** v3.2.0：精致档的取值（简洁档是 0；所有逐帧的界面动效都要 ≥ 这一档）。 */
    const val TIER_REFINED: Int = 1

    /**
     * v3.2.0：**动效的九个独立开关**（v3.0.0 五项 + v3.2.0 四项）。
     *
     * 它们的门控是复合的：先看「界面动效」总闸，再看（律动类的）「界面律动」总闸，
     * 最后看档位。三层都过了才可用。
     */
    val MOTION_SWITCH_IDS: Set<String> = linkedSetOf(
        "motion_shockwave",
        "motion_halo",
        "motion_particles",
        "motion_wave_bands",
        "motion_breathing",
        "motion_rhythm_enabled",
        "motion_cover_float",
        "motion_lyric_pulse",
        "motion_bar_pulse",
    )

    /**
     * v3.2.0：**律动类**的细粒度开关（不含律动闸自己）。
     *
     * 归类判据是可执行的：渲染路径里是否读 `MotionClock.pulse()` / `MotionClock.level()`
     * —— 逐项表见 `docs/verification/v3.2.0/probe-ui-jitter.md` §5。
     * 「封面 3D 旋转」也是律动类，由一个能力位（不是开关）表达，因此不在这个集合里。
     */
    val RHYTHM_DETAIL_IDS: Set<String> = linkedSetOf(
        "motion_breathing",
        "motion_cover_float",
        "motion_lyric_pulse",
        "motion_bar_pulse",
    )

    /**
     * v2.8.0 新增的 **C 档细分开关**（4 项）。它们的门控是复合的：
     * 先看档位（必须 T2），再看 `visualizer_showcase` 总开关。
     */
    val SHOWCASE_DETAIL_IDS: Set<String> = linkedSetOf(
        "visualizer_shockwave",
        "visualizer_particles",
        "visualizer_perspective",
        "visualizer_drag",
    )

    /** `visualizer_tier` 的非法值归一化：**只读、不写回**（与 `LyricsSweepQuality.normalize` 同一口径）。 */
    fun normalizeTier(raw: Int): Int = if (raw in TIER_RANGE) raw else TIER_DEFAULT

    /**
     * 读当前波形档位（已归一化）。
     *
     * [read] 返回 null（键不存在）或类型不符时，用 registry 里 `visualizer_tier` 的期望默认值。
     */
    fun visualizerTier(read: (String) -> Any?): Int = normalizeTier(intValue("visualizer_tier", read))

    /**
     * v3.2.0：读当前**统一动效强度**档位（`motion_tier`，已归一化；缺 key 时用 registry 声明的
     * 精致档）。
     *
     * ⚠️ 与 [visualizerTier] 不是同一个键：`visualizer_tier` 是 v2.8.0 的迁移源，
     * 已经不再参与渲染。这里的缺省口径刻意与 `SettingsRegistry` 里 `motion_tier` 的
     * `default = MotionIntensity.REFINED` 一致 —— 于是「没选过档位」的盘在设置页上
     * 等价于精致档；**真实 UI 传入的是解析后的值**（`MotionPrefs.readTier(prefs,
     * deviceDefaultTier(context))`），所以低端机上会正确置灰。
     * 这是本文件「只做纯函数、不碰平台能力」这条边界的又一处：设备判据在 UI 层。
     */
    fun motionTier(read: (String) -> Any?): Int =
        normalizeTier(intValue("motion_tier", read))

    /**
     * 门控判定（按 id）。未知 id 一律返回 [SettingsAvailability.FREE] —— **不抛异常**
     * （与 `AccentSource.kt:36` 的 `getOrDefault(PRESET)` 同一条纪律：坏输入最坏只是多显示一行，
     * 不该让设置页崩）。
     */
    fun availabilityOf(entryId: String, read: (String) -> Any?): SettingsAvailability {
        val entry = SettingsRegistry.entryById(entryId) ?: return SettingsAvailability.FREE
        return availabilityOf(entry, read)
    }

    /** 门控判定（按条目）。 */
    fun availabilityOf(entry: SettingsEntry, read: (String) -> Any?): SettingsAvailability =
        when (entry.id) {
            // 硬依赖：TTML 总开关关 → 整行不挂载（唯一一处在现状里已经正确门控的依赖，原样保留）。
            "lyrics_ttml_first" ->
                if (boolValue("lyrics_ttml_enabled", read)) {
                    SettingsAvailability.FREE
                } else {
                    SettingsAvailability.hidden(GatingReason.TTML_SOURCE_DISABLED)
                }

            // 软依赖：逐字动画不是「渐变扫过」→ 改了也看不到变化 ⇒ 可见但不可用。
            // 判据用 LyricsWordAnimationMode.normalize：盘上的越界脏值与真实读路径
            // （LyricsDisplayPrefs.readWordAnimation）一样回落 GRADIENT_SWEEP，门控不能比读路径更严。
            "lyrics_sweep_quality" ->
                if (LyricsWordAnimationMode.normalize(intValue("lyrics_word_animation", read)) ==
                    LyricsWordAnimationMode.GRADIENT_SWEEP
                ) {
                    SettingsAvailability.FREE
                } else {
                    SettingsAvailability.disabled(GatingReason.SWEEP_ANIMATION_INACTIVE)
                }

            // 炫技总开关本身：档位不是 T2 时打开它没有任何效果 ⇒ 可见但不可用（避免死开关）。
            // 这一条是**推断**（任务书只写了「4 个细分开关仅 tier==2 可用」），已在交付说明里标注。
            "visualizer_showcase" ->
                if (visualizerTier(read) == TIER_SHOWCASE) {
                    SettingsAvailability.FREE
                } else {
                    SettingsAvailability.disabled(GatingReason.TIER_NOT_SHOWCASE_CAPABLE)
                }

            in SHOWCASE_DETAIL_IDS -> when {
                visualizerTier(read) != TIER_SHOWCASE ->
                    SettingsAvailability.disabled(GatingReason.TIER_NOT_SHOWCASE_CAPABLE)

                !boolValue("visualizer_showcase", read) ->
                    SettingsAvailability.disabled(GatingReason.SHOWCASE_DISABLED)

                else -> SettingsAvailability.FREE
            }

            // v3.2.0：界面律动总闸本身。它是**策略开关**（关掉就是关掉，任何档位都不许绕过），
            // 所以只受「界面动效」总闸约束，不随档位置灰 —— 用户可以在简洁档先关掉它，
            // 把档位调高之后它仍然生效。
            "motion_rhythm_enabled" ->
                if (boolValue("ui_motion_enabled", read)) {
                    SettingsAvailability.FREE
                } else {
                    SettingsAvailability.disabled(GatingReason.UI_MOTION_DISABLED)
                }

            // v3.2.0：其余八个动效开关：总闸 → 律动闸（只有律动类看它）→ 档位。
            // 简洁档自 v3.2.0 起是静态档（P0-B）⇒ 这一档下它们全是死开关，必须置灰。
            in MOTION_SWITCH_IDS -> when {
                !boolValue("ui_motion_enabled", read) ->
                    SettingsAvailability.disabled(GatingReason.UI_MOTION_DISABLED)

                entry.id in RHYTHM_DETAIL_IDS && !boolValue("motion_rhythm_enabled", read) ->
                    SettingsAvailability.disabled(GatingReason.RHYTHM_DISABLED)

                motionTier(read) < TIER_REFINED ->
                    SettingsAvailability.disabled(GatingReason.TIER_BASIC_ONLY)

                else -> SettingsAvailability.FREE
            }

            else -> SettingsAvailability.FREE
        }

    /** 某条目是否应挂载。 */
    fun isVisible(entryId: String, read: (String) -> Any?): Boolean =
        availabilityOf(entryId, read).visible

    /** 某条目是否可交互（改了是否有效）。 */
    fun isEnabled(entryId: String, read: (String) -> Any?): Boolean =
        availabilityOf(entryId, read).enabled

    /**
     * 某分组**应渲染**的条目（渲染顺序，已排除 `isInternal`），但**不评估门控**。
     *
     * 一级页/二级页的静态骨架用它（不需要 prefs 就能画出列表）。
     */
    fun renderableEntriesOf(groupId: String): List<SettingsEntry> =
        SettingsRegistry.entriesOf(groupId).filter { !it.isInternal }

    /**
     * 某分组在当前取值下**实际应挂载**的条目（渲染顺序 = registry 声明顺序）。
     *
     * = [renderableEntriesOf] 再过滤掉 `visible == false` 的条目。
     */
    fun visibleEntriesOf(groupId: String, read: (String) -> Any?): List<SettingsEntry> =
        renderableEntriesOf(groupId).filter { availabilityOf(it, read).visible }

    // ── 私有：按 registry 默认值兜底的只读取值 ─────────────────────────────────────────

    private fun boolValue(entryId: String, read: (String) -> Any?): Boolean {
        val entry = SettingsRegistry.entryById(entryId) ?: return false
        val raw = entry.key?.let(read)
        return raw as? Boolean ?: entry.defaultValue as? Boolean ?: false
    }

    private fun intValue(entryId: String, read: (String) -> Any?): Int {
        val entry = SettingsRegistry.entryById(entryId) ?: return 0
        val raw = entry.key?.let(read)
        return when (raw) {
            is Int -> raw
            is Number -> raw.toInt()
            else -> (entry.defaultValue as? Number)?.toInt() ?: 0
        }
    }

    /** `lyrics_ttml_enabled` 的期望默认值（供 UI / 测试引用，避免散落魔法值）。 */
    val TTML_ENABLED_DEFAULT: Boolean =
        SettingsRegistry.entryById("lyrics_ttml_enabled")?.defaultValue as? Boolean ?: true

    /** 逐字动画的默认模式（= `lyrics_sweep_quality` 软依赖的判据基准）。 */
    const val WORD_ANIMATION_DEFAULT: Int = LyricsWordAnimationMode.GRADIENT_SWEEP
}
