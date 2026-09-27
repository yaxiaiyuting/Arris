/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * v2.8.0 · P1-A：波形分级相关的**全部落盘键**与读写入口（`ncrust_settings`）。
 *
 * ## 为什么键名必须集中声明在这里
 *
 * 任务书铁律：字段名显式声明在专门的 prefs 读写对象里，不散落。本对象是这些键的
 * **唯一字面量落点**（设置页只拿本对象的 `KEY_*` 常量与 [effects]，不自己拼字符串）。
 * 单测里有一条「键名是持久化契约」的用例把 8 个名字逐字钉住 —— 改名等于静默重置所有
 * 用户的选择（v2.3.0「落盘 key 名断言」的同一做法）。
 *
 * ## 三个刻意的设计决定（都别顺手改掉）
 *
 * ### 1. 缺 key ≠ 选了默认档：默认值是**解析出来的**
 *
 * `visualizer_tier` 的默认值不是常量 `1`，而是根据设备判据解析出来的
 * （[VisualizerTier.defaultTier]：低端机 → 简洁档，其余 → 精致档）。
 * 实现上用 **key 是否存在**区分「用户没选过」与「用户选的就是 1」：
 *
 *  - [readTierOrNull] 返回 `null` 表示**没选过** ⇒ 用解析默认；
 *  - 返回 0/1/2 表示用户显式选过 ⇒ **以用户值为准**（哪怕它比解析默认低/高）。
 *
 * 读盘时**绝不把解析默认写回**：一旦写回，「没选过」这个信息就永久丢了，
 * 用户以后换了内存更大的设备也永远停在老默认上（v1.9.3「缺失 vs 空」两义性的同一教训）。
 *
 * ### 2. 迁移是幂等水位，且不碰任何既有键
 *
 * 范式照 `player/QualityLadder.kt:53-63`：读版本号 → 够了就直接返回 → 否则做事 + 落水位。
 * v1（本版）**没有需要搬运的键**（波形分级是首次引入），且 `DEFAULT_TIER_VERSION` 与
 * `CURRENT_TIER_VERSION` 都是 1 ⇒ 缺 key 时迁移是严格 no-op。这不是空实现：
 * 下一个人要给档位加语义时，入口已经接好线、已经被单测覆盖（缺 key 不写盘、低水位补水位、
 * 跑两次结果一致、既有键一个不动）。
 *
 * ### 3. 自动降级是有界的，并且**会改写 `visualizer_tier`**
 *
 * [applyAutoDowngrade] 只降一级、只发生一次（标记落盘 `visualizer_auto_downgraded = true`）、
 * 之后**永不自动恢复**。它**故意**把降级后的档位写回 `visualizer_tier`：设置页显示的必须是
 * 「实际在渲染的那一档」，否则用户看到的是「炫技」、画面上是「精致」——那是撒谎。
 * 用户之后仍可手动改回高档（[writeTier]），改回后不会再被自动降级（标记已置位）。
 */

/** 键名 / 默认值 / 纯读写（不依赖 Android 运行时，JVM 单测直接可用内存版 SharedPreferences）。 */
object VisualizerPrefs {

    const val PREFS = "ncrust_settings"

    // ---- 落盘键（唯一字面量落点）----
    const val KEY_TIER = "visualizer_tier"
    const val KEY_SHOWCASE = "visualizer_showcase"
    const val KEY_SHOCKWAVE = "visualizer_shockwave"
    const val KEY_PARTICLES = "visualizer_particles"
    const val KEY_PERSPECTIVE = "visualizer_perspective"
    const val KEY_DRAG = "visualizer_drag"
    const val KEY_AUTO_DOWNGRADED = "visualizer_auto_downgraded"
    const val KEY_TIER_VERSION = "visualizer_tier_version"

    // ---- 默认值 ----
    const val DEFAULT_SHOWCASE = false
    const val DEFAULT_AUTO_DOWNGRADED = false

    /** 本版波形分级的迁移水位（首次引入 = 1）。 */
    const val DEFAULT_TIER_VERSION = 1

    /** 当前水位。加语义 ⇒ 把它 +1 并在 [migrate] 里补一段搬运逻辑（照 QualityLadder 的范式）。 */
    const val CURRENT_TIER_VERSION = 1

    // ------------------------------------------------------------------
    // 纯读（键缺失 / 类型不符都不抛异常 —— 坏数据最坏只是回落默认值）
    // ------------------------------------------------------------------

    /**
     * 用户是否**显式选择过**档位（键存在且类型正确）。这是「没选过」与「选了 1」的唯一判据。
     *
     * 探针 §3.3 明确要求：缺 key **不得**被解读成「用户选了最低档」。
     */
    fun hasExplicitTier(prefs: SharedPreferences): Boolean = intOrNull(prefs, KEY_TIER) != null

    /**
     * 实际生效的档位：显式选择优先，否则用调用方解析出来的设备默认档。
     * **越界值同样回落该默认档**（不是回落常量 1 —— 低端机上常量 1 会把用户顶到精致档）。
     */
    fun readTier(prefs: SharedPreferences, deviceDefault: Int): Int {
        val fallback = VisualizerTier.sanitize(deviceDefault, VisualizerTier.REFINED)
        val raw = intOrNull(prefs, KEY_TIER) ?: return fallback
        return VisualizerTier.sanitize(raw, fallback)
    }

    fun readShowcase(prefs: SharedPreferences): Boolean = bool(prefs, KEY_SHOWCASE, DEFAULT_SHOWCASE)

    fun readShockwave(prefs: SharedPreferences): Boolean = bool(prefs, KEY_SHOCKWAVE, false)

    fun readParticles(prefs: SharedPreferences): Boolean = bool(prefs, KEY_PARTICLES, false)

    fun readPerspective(prefs: SharedPreferences): Boolean = bool(prefs, KEY_PERSPECTIVE, false)

    /** `visualizer_drag`：C 档细分开关。本版语义是「点按切换着色模式」，见 [VisualizerEffects.tapInteraction]。 */
    fun readTapInteraction(prefs: SharedPreferences): Boolean = bool(prefs, KEY_DRAG, false)

    fun readAutoDowngraded(prefs: SharedPreferences): Boolean =
        bool(prefs, KEY_AUTO_DOWNGRADED, DEFAULT_AUTO_DOWNGRADED)

    fun readTierVersion(prefs: SharedPreferences): Int =
        intOrNull(prefs, KEY_TIER_VERSION) ?: DEFAULT_TIER_VERSION

    /** 一次读全（组合期只读一次盘/一次内存快照；避免 8 次分散的 state 读）。 */
    fun readEffects(prefs: SharedPreferences, deviceDefault: Int): VisualizerEffects =
        VisualizerEffects.of(
            tier = readTier(prefs, deviceDefault),
            showcase = readShowcase(prefs),
            shockwave = readShockwave(prefs),
            particles = readParticles(prefs),
            perspective = readPerspective(prefs),
            tapInteraction = readTapInteraction(prefs),
            autoDowngraded = readAutoDowngraded(prefs),
        )

    // ------------------------------------------------------------------
    // 纯写
    // ------------------------------------------------------------------

    fun writeTier(prefs: SharedPreferences, tier: Int) {
        safeEdit(prefs) { it.putInt(KEY_TIER, VisualizerTier.sanitize(tier, VisualizerTier.REFINED)) }
    }

    fun writeShowcase(prefs: SharedPreferences, enabled: Boolean) {
        safeEdit(prefs) { it.putBoolean(KEY_SHOWCASE, enabled) }
    }

    fun writeShockwave(prefs: SharedPreferences, enabled: Boolean) {
        safeEdit(prefs) { it.putBoolean(KEY_SHOCKWAVE, enabled) }
    }

    fun writeParticles(prefs: SharedPreferences, enabled: Boolean) {
        safeEdit(prefs) { it.putBoolean(KEY_PARTICLES, enabled) }
    }

    fun writePerspective(prefs: SharedPreferences, enabled: Boolean) {
        safeEdit(prefs) { it.putBoolean(KEY_PERSPECTIVE, enabled) }
    }

    fun writeTapInteraction(prefs: SharedPreferences, enabled: Boolean) {
        safeEdit(prefs) { it.putBoolean(KEY_DRAG, enabled) }
    }

    /**
     * 幂等迁移：**不删不改任何既有键**（`audio_visualizer` 等一律原样）。
     *
     * v1 的实际情况（写清楚，别让下一个人以为这里在做别的事）：`DEFAULT_TIER_VERSION` 与
     * `CURRENT_TIER_VERSION` **都是 1**，所以「键缺失」读出来就是当前水位 ⇒ 缺 key 时本函数
     * 是**严格 no-op**（不写盘、不新增键）。理由：波形分级是首次引入，**没有任何需要搬运的键**；
     * 悄悄写一个语义上等于默认值的键，只会让「这个键到底代不代表用户选择过」更难判断。
     *
     * 写盘分支因此只对「水位**低于**当前版本」的盘生效 —— 回滚安装、手工改坏的盘、
     * 以及**将来真的要搬键的那个版本**（那时把 `CURRENT_TIER_VERSION` 加一，在这里补搬运逻辑）。
     * 单测两个方向都覆盖：缺 key ⇒ no-op 且盘逐键不变；水位 0 ⇒ 补到 1 且再次调用不写。
     *
     * @return 本次是否真的写了盘（单测用；生产路径不关心）。
     */
    fun migrate(prefs: SharedPreferences): Boolean {
        if (readTierVersion(prefs) >= CURRENT_TIER_VERSION) return false
        safeEdit(prefs) { it.putInt(KEY_TIER_VERSION, CURRENT_TIER_VERSION) }
        return true
    }

    /**
     * 帧时间实测触发的一次性降级（纯函数，落盘 + 返回新档位；`null` = 不动作）。
     *
     * 三条边界全在 [VisualizerTier.downgradedTier] 里（已降过 / 已在最低档 / 只降一级）。
     * 动作是**两个键一起写**：`visualizer_tier` = 新档、`visualizer_auto_downgraded` = true。
     * 两个都不落盘的话，「每进程最多一次」在冷启动后就会重新发生一次，而用户会看到
     * 设置页的档位在两次启动之间来回跳。
     */
    fun applyAutoDowngrade(
        prefs: SharedPreferences,
        currentTier: Int,
        alreadyDowngraded: Boolean,
    ): Int? {
        val next = VisualizerTier.downgradedTier(currentTier, alreadyDowngraded) ?: return null
        safeEdit(prefs) {
            it.putInt(KEY_TIER, next)
            it.putBoolean(KEY_AUTO_DOWNGRADED, true)
        }
        return next
    }

    // ------------------------------------------------------------------
    // 进程内镜像（组合期读它，避免每次重组都读盘；音频线程**不读这里**）
    // ------------------------------------------------------------------

    private val effectsStateHolder = mutableStateOf(
        VisualizerEffects.of(
            tier = VisualizerTier.REFINED,
            showcase = DEFAULT_SHOWCASE,
            shockwave = false,
            particles = false,
            perspective = false,
            tapInteraction = false,
            autoDowngraded = DEFAULT_AUTO_DOWNGRADED,
        )
    )

    private var loadedFromDisk = false

    /** 组合期读这一个状态：设置变化（含自动降级）时只重组一次，帧路径里不再读任何 state。 */
    val effects: MutableState<VisualizerEffects> get() = effectsStateHolder

    /** 当前渲染档位（帧时间监控判据用）。 */
    val currentTier: Int get() = effectsStateHolder.value.tier

    /**
     * 进程内初始化：读盘 + 迁移 + 解析设备默认档。**幂等**，且与
     * `VisualizerSetting.read(context)` 一起在 `MainActivity.onCreate` 里被调用
     * （在播放器组合之前，避免首帧按默认值渲染）。
     */
    fun ensureLoaded(context: Context) {
        if (loadedFromDisk) return
        val prefs = prefs(context)
        // 迁移失败不阻断读取：它只写一个水位，坏了也不该让可视化整体不可用。
        runCatching { migrate(prefs) }
        effectsStateHolder.value = runCatching { readEffects(prefs, deviceDefaultTier(context)) }
            .getOrElse { effectsStateHolder.value }
        loadedFromDisk = true
    }

    /** 用户显式选择档位（设置页调用）。 */
    fun setTier(context: Context, tier: Int) = update(context) { writeTier(it, tier) }

    fun setShowcase(context: Context, enabled: Boolean) = update(context) { writeShowcase(it, enabled) }

    fun setShockwave(context: Context, enabled: Boolean) = update(context) { writeShockwave(it, enabled) }

    fun setParticles(context: Context, enabled: Boolean) = update(context) { writeParticles(it, enabled) }

    fun setPerspective(context: Context, enabled: Boolean) = update(context) { writePerspective(it, enabled) }

    fun setTapInteraction(context: Context, enabled: Boolean) = update(context) { writeTapInteraction(it, enabled) }

    /** 设备静态判据解析出的默认档（设置页显示「未选择时实际是哪一档」用同一个函数）。 */
    fun deviceDefaultTier(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val totalMem = runCatching {
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            info.totalMem
        }.getOrDefault(0L)
        return VisualizerTier.defaultTier(
            isLowRamDevice = runCatching { am?.isLowRamDevice == true }.getOrDefault(false),
            totalMemBytes = totalMem,
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    /**
     * 帧时间监控发现「持续超标」时调用：落盘 + 刷新进程内状态。
     *
     * @return 降级后的档位；`null` = 不动作（已降过 / 已在最低档）。
     */
    fun applyAutoDowngrade(context: Context): Int? {
        val current = effectsStateHolder.value
        val next = runCatching {
            applyAutoDowngrade(prefs(context), current.tier, current.autoDowngraded)
        }.getOrNull() ?: return null
        refresh(context)
        return next
    }

    internal fun resetForTest() {
        loadedFromDisk = false
        effectsStateHolder.value = VisualizerEffects.of(
            tier = VisualizerTier.REFINED,
            showcase = DEFAULT_SHOWCASE,
            shockwave = false,
            particles = false,
            perspective = false,
            tapInteraction = false,
        )
    }

    private inline fun update(context: Context, write: (SharedPreferences) -> Unit) {
        val prefs = prefs(context)
        runCatching { write(prefs) }
        refresh(context)
    }

    private fun refresh(context: Context) {
        effectsStateHolder.value = runCatching { readEffects(prefs(context), deviceDefaultTier(context)) }
            .getOrElse { effectsStateHolder.value }
        loadedFromDisk = true
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 任何一次 prefs 写都不允许把异常抛给调用方（设置页/帧时间回调都不该因此崩）。 */
    private inline fun safeEdit(prefs: SharedPreferences, block: (SharedPreferences.Editor) -> Unit) {
        runCatching {
            val editor = prefs.edit()
            block(editor)
            editor.apply()
        }
    }

    private fun intOrNull(prefs: SharedPreferences, key: String): Int? =
        runCatching { if (prefs.contains(key)) prefs.getInt(key, 0) else null }.getOrNull()

    private fun bool(prefs: SharedPreferences, key: String, fallback: Boolean): Boolean =
        runCatching { prefs.getBoolean(key, fallback) }.getOrDefault(fallback)
}
