/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences

/**
 * 歌词逐字动画模式（v1.5.1 · A，用户可选）。
 *
 * v1.5.0 只有「开 / 关」两种状态，且开的时候是「逐词硬切」——S6 (Android 7.0) 上真实生效，
 * 但用户反馈两点：① 硬切不够顺滑；② PCL110 (Android 16) 上完全没有逐字效果
 * （根因见 [LyricsDisplayPrefs] 与 TASK.md：v1.5.0 走的是
 * `TextLayoutResult.getPathForRange` + `clipPath`，在 API 36 上拿不到可用的裁剪区域）。
 *
 * 改成三模式后：
 *  - [GRADIENT_SWEEP]：软边横扫（默认）。词内按时间线性推进，边缘 0.5~1 个字宽渐变；
 *  - [HARD_CUT]：v1.5.0 的逐词硬切，向后兼容保留；
 *  - [OFF]：不做逐字，当前行整行高亮（等价于 v1.4.x 的渲染路径）。
 */
object LyricsWordAnimationMode {
    const val GRADIENT_SWEEP = 0
    const val HARD_CUT = 1
    const val OFF = 2

    /** 越界值一律回落默认模式，绝不因为 prefs 被写坏而崩或不显示。 */
    fun normalize(raw: Int): Int = if (raw in GRADIENT_SWEEP..OFF) raw else GRADIENT_SWEEP
}

/**
 * v1.5.2：逐字扫过的绘制质量。
 *
 * [SOFT] 与 [EDGE] 的**光标位置算法完全相同**（都走 SweepTrack），差别只在渐变带的边缘：
 * SOFT 用 `saveLayer` + `DstIn` 磨出软边，EDGE 只用 `clipRect` 画硬边。
 * 所以降到 EDGE 只会失去「柔化」，**不会退回 v1.5.1 那种按词跳变**。
 */
object LyricsSweepQuality {
    /** 按设备能力自动决定（默认）。 */
    const val AUTO = 0

    /** 强制软边（离屏层只覆盖渐变带，代价很小）。 */
    const val SOFT = 1

    /** 强制硬边：完全不分配离屏层，最省。 */
    const val EDGE = 2

    /** 越界值一律回落 AUTO，绝不因为 prefs 被写坏而崩或不显示。 */
    fun normalize(raw: Int): Int = if (raw in AUTO..EDGE) raw else AUTO
}

/**
 * 歌词显示相关的持久化设置（v1.5.1 · A/E，v1.5.2 扩展）。
 *
 * 全部落在 `ncrust_settings`，与其余设置项一致（无 DI、无 Room，读时现取）。
 */
object LyricsDisplayPrefs {

    const val PREFS_NAME = "ncrust_settings"

    /** 逐字动画模式（Int，取值见 [LyricsWordAnimationMode]）。 */
    const val KEY_WORD_ANIMATION = "lyrics_word_animation"

    /** v1.5.0 的布尔开关，仅用于一次性迁移，不再写入。 */
    const val KEY_WORD_BY_WORD_LEGACY = "lyrics_word_by_word"

    /** 歌词字号倍率（Float，0.7~1.5）。 */
    const val KEY_FONT_SCALE = "lyrics_font_scale"

    /** v1.5.2：扫过绘制质量（Int，取值见 [LyricsSweepQuality]）。 */
    const val KEY_SWEEP_QUALITY = "lyrics_sweep_quality"

    /** v1.9.0：AMLL TTML 歌词源总开关。关掉 = 一个 TTML 请求都不发，行为与 v1.8.1 完全一致。 */
    const val KEY_TTML_ENABLED = "lyrics_ttml_enabled"

    /** v1.9.0：TTML 与 ncm 歌词都能用时，是否优先用 TTML。 */
    const val KEY_TTML_FIRST = "lyrics_ttml_first"

    /**
     * v1.9.3：音译显示开关（默认**关**）。渲染规则见 [LyricSubtitleText]。
     *
     * 它只影响「读出来的音译要不要画」，与歌词源、缓存、请求全都无关 ——
     * 打开 / 关闭不会触发任何网络请求，也不需要重取歌词（音译数据一直在 [LrcLine] 那一轨上）。
     */
    const val KEY_ROMANIZATION = "lyrics_romanization"

    /**
     * v2.0.0 · T4：动态字号（**实验性，默认关**）。按每句估算折行数给这一句一个倍率，
     * 见 [DynamicLyricFont]。默认关 = 渲染路径与 v1.9.3 完全一致（逐字节），
     * 也便于用户 A/B 对比后自己决定是否留下。
     */
    const val KEY_DYNAMIC_FONT = "lyrics_dynamic_font"

    /**
     * v1.9.3：音译的基础字号 / 行高（sp）。
     *
     * 比译文（20 / 26）低一档，用来建立「原文 > 译文 > 音译」的层级；A- / A+ 与译文一样
     * 把 fontScale 乘在上面（见 LyricsView 的传参），所以三者的比例恒定。
     */
    const val ROMANIZATION_FONT_SP = 18f
    const val ROMANIZATION_LINE_HEIGHT_SP = 24f

    // v1.5.2：扫过参数的高级覆盖项。默认值（见 LyricsSweepConfig）就是定稿值；留这几个键是为了
    // **不重新构建**就能在真机上扫参数——低端机调 fadeEm、浅色主题调 inactiveAlpha、慢歌试 easing。
    // 键不存在 / 值非法时一律回落默认值，所以普通用户永远不会碰到它们。
    const val KEY_SWEEP_FADE_EM = "lyrics_sweep_fade_em"
    const val KEY_SWEEP_INACTIVE_ALPHA = "lyrics_sweep_inactive_alpha"
    const val KEY_SWEEP_EASING = "lyrics_sweep_easing"

    const val FONT_SCALE_MIN = 0.7f
    const val FONT_SCALE_MAX = 1.5f
    const val FONT_SCALE_DEFAULT = 1.0f

    /** 设置页档位（TASK 里要求 5~7 档，这里取 5 档；A-/A+ 按键也走同一张表）。 */
    val FONT_SCALE_STEPS = listOf(0.7f, 0.85f, 1.0f, 1.2f, 1.5f)

    /** 档位文案：纯数字倍率，与语言无关，不进 i18n（8 语言下都是同一串）。 */
    val FONT_SCALE_LABELS = listOf("0.7x", "0.85x", "1.0x", "1.2x", "1.5x")

    /**
     * 读逐字动画模式，并完成 v1.5.0 布尔开关的一次性迁移：
     * 老用户关掉过逐字 → [LyricsWordAnimationMode.OFF]（等价于他原来的选择）；
     * 老用户开着（默认）→ 新的默认 [LyricsWordAnimationMode.GRADIENT_SWEEP]。
     *
     * 迁移结果立刻写回，所以「用户关掉过逐字」这个意图只被消费一次。
     */
    fun readWordAnimation(prefs: SharedPreferences): Int {
        if (prefs.contains(KEY_WORD_ANIMATION)) {
            return LyricsWordAnimationMode.normalize(prefs.getInt(KEY_WORD_ANIMATION, LyricsWordAnimationMode.GRADIENT_SWEEP))
        }
        val migrated = if (prefs.getBoolean(KEY_WORD_BY_WORD_LEGACY, true)) {
            LyricsWordAnimationMode.GRADIENT_SWEEP
        } else {
            LyricsWordAnimationMode.OFF
        }
        prefs.edit().putInt(KEY_WORD_ANIMATION, migrated).apply()
        return migrated
    }

    fun writeWordAnimation(prefs: SharedPreferences, mode: Int) {
        prefs.edit().putInt(KEY_WORD_ANIMATION, LyricsWordAnimationMode.normalize(mode)).apply()
    }

    fun readSweepQuality(prefs: SharedPreferences): Int =
        LyricsSweepQuality.normalize(
            prefs.getInt(KEY_SWEEP_QUALITY, LyricsSweepQuality.AUTO)
        )

    fun writeSweepQuality(prefs: SharedPreferences, quality: Int) {
        prefs.edit().putInt(KEY_SWEEP_QUALITY, LyricsSweepQuality.normalize(quality)).apply()
    }

    /**
     * v1.9.0：读 AMLL TTML 歌词源开关，默认开。
     *
     * 「非法值回落默认」在这里的含义与 [readSweepQuality] 的取整回落是同一件事：
     * SharedPreferences 对**类型不符**的键会抛 ClassCastException（键里是 String 而调
     * getBoolean），脏键不该让播放器崩 —— 一律回落默认值，最坏也只是退回既有 LRC 路径。
     */
    fun readTtmlEnabled(prefs: SharedPreferences): Boolean = readBooleanSafely(prefs, KEY_TTML_ENABLED, true)

    fun writeTtmlEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TTML_ENABLED, enabled).apply()
    }

    /** v1.9.0：读「TTML 优先」开关，默认开。非法值同样回落默认。 */
    fun readTtmlFirst(prefs: SharedPreferences): Boolean = readBooleanSafely(prefs, KEY_TTML_FIRST, true)

    fun writeTtmlFirst(prefs: SharedPreferences, first: Boolean) {
        prefs.edit().putBoolean(KEY_TTML_FIRST, first).apply()
    }

    /**
     * v1.9.3：读「显示音译」开关，**默认关**。
     *
     * 默认关是刻意的：音译轨是 v1.9.2 才进缓存的数据，老用户升级后不该因为「服务端刚好有这份
     * 数据」就凭空多出一行小字。想要的人自己去设置页打开；打开后由 [LyricSubtitleText] 负责
     * 丢掉空白行与「与原文逐字相同」的行，所以打开也不会多出噪音行。
     */
    fun readRomanization(prefs: SharedPreferences): Boolean =
        readBooleanSafely(prefs, KEY_ROMANIZATION, false)

    fun writeRomanization(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ROMANIZATION, enabled).apply()
    }

    /** v2.0.0 · T4：动态字号开关（默认关）。只影响字号，不触发任何请求、不进歌词缓存。 */
    fun readDynamicFont(prefs: SharedPreferences): Boolean =
        readBooleanSafely(prefs, KEY_DYNAMIC_FONT, false)

    fun writeDynamicFont(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_FONT, enabled).apply()
    }

    private fun readBooleanSafely(prefs: SharedPreferences, key: String, def: Boolean): Boolean =
        runCatching { prefs.getBoolean(key, def) }.getOrDefault(def)

    fun readFontScale(prefs: SharedPreferences): Float =
        prefs.getFloat(KEY_FONT_SCALE, FONT_SCALE_DEFAULT).coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)

    fun writeFontScale(prefs: SharedPreferences, scale: Float) {
        prefs.edit().putFloat(KEY_FONT_SCALE, scale.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)).apply()
    }

    /** 找当前档位在 [FONT_SCALE_STEPS] 里的下标；不在表里时取最接近的一档。 */
    fun fontScaleStepIndex(scale: Float): Int {
        var best = 0
        var bestDiff = Float.MAX_VALUE
        FONT_SCALE_STEPS.forEachIndexed { i, v ->
            val d = kotlin.math.abs(v - scale)
            if (d < bestDiff) { bestDiff = d; best = i }
        }
        return best
    }

    /** A- / A+ ：在档位表上移动一格；已经在端点则返回原值（配合调用方的防抖）。 */
    fun steppedFontScale(scale: Float, delta: Int): Float {
        val i = (fontScaleStepIndex(scale) + delta).coerceIn(0, FONT_SCALE_STEPS.size - 1)
        return FONT_SCALE_STEPS[i]
    }
}

/**
 * v1.5.2：把「设备能力 + 用户设置 + 高级覆盖」解析成一份 [LyricsSweepConfig]。
 *
 * 解析只做三件事，全部是纯读，无副作用：
 *  1. 质量档 [LyricsSweepQuality.AUTO] → 问设备（见 [autoSoftEdge]）；
 *  2. 三个高级覆盖键（[LyricsDisplayPrefs.KEY_SWEEP_FADE_EM] / ..._INACTIVE_ALPHA / ..._EASING）
 *     存在且合法时覆盖默认值，否则回落 [LyricsSweepConfig.DEFAULT]；
 *  3. easing 的越界值一律回落 LINEAR。
 *
 * **为什么自动降级选「离屏软边」而不是「整个动画」**：两种画法的光标位置算法完全相同
 * （都走 SweepTrack），差的只是渐变带边缘磨不磨圆。所以降级绝不会退回到 v1.5.1 那种按词跳变，
 * 只是「柔化没了」——这是最划算的一刀。
 */
object LyricsSweepPerf {

    /**
     * 自动决定是否使用离屏软边。
     *
     * 判据只有一条：**低内存设备**（[ActivityManager.isLowRamDevice]）走硬边。
     * v1.5.2 起离屏层只覆盖渐变带（0.65 em × 行高，约整行面积的 1/20），
     * 真机实测（见 TASK.md「v1.5.2 性能」节）在 S6 / API 24 与 PCL110 / API 36 上帧时间都无可见差异，
     * 因此**不**按 API 等级一刀切把老设备降级 —— 老设备也配得上软边。
     * 只有厂商明确标了 low-RAM 的机器才退到硬边。
     */
    fun autoSoftEdge(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return am == null || !am.isLowRamDevice
    }

    fun resolve(context: Context, prefs: SharedPreferences): LyricsSweepConfig {
        val def = LyricsSweepConfig.DEFAULT
        val softEdge = when (LyricsDisplayPrefs.readSweepQuality(prefs)) {
            LyricsSweepQuality.SOFT -> true
            LyricsSweepQuality.EDGE -> false
            else -> autoSoftEdge(context)
        }
        val fadeEm = prefs.getFloat(LyricsDisplayPrefs.KEY_SWEEP_FADE_EM, def.fadeEm)
            .takeIf { it.isFinite() && it > 0f } ?: def.fadeEm
        val inactiveAlpha = prefs.getFloat(LyricsDisplayPrefs.KEY_SWEEP_INACTIVE_ALPHA, def.inactiveAlpha)
            .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: def.inactiveAlpha
        val easing = when (prefs.getInt(LyricsDisplayPrefs.KEY_SWEEP_EASING, def.easing.ordinal)) {
            SweepEasing.SMOOTH.ordinal -> SweepEasing.SMOOTH
            SweepEasing.EASE_OUT.ordinal -> SweepEasing.EASE_OUT
            else -> SweepEasing.LINEAR
        }
        return LyricsSweepConfig(
            fadeEm = fadeEm,
            inactiveAlpha = inactiveAlpha,
            easing = easing,
            softEdge = softEdge,
        )
    }
}
