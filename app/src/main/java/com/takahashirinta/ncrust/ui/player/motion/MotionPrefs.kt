/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.motion

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerTier

/**
 * v2.9.0：统一「动效强度」的**全部落盘键**与读写入口（`ncrust_settings`）。
 *
 * ## 键名是持久化契约（唯一字面量落点）
 *
 * 本对象是下列键名在**全仓库唯一**的字面量落点，改名 = 静默重置所有用户的选择。
 * 单测 `MotionPrefsTest` 逐字钉住它们。
 *
 * | 键 | 类型 | 默认 | 语义 |
 * |---|---|---|---|
 * | `motion_tier` | Int 0..2 | **不写盘**，按设备判据解析 | 动效强度（波形 + 界面动效共用一个档位） |
 * | `ui_motion_enabled` | Bool | `true` | 「界面动效」总开关（关掉 = A/B/C 全关，波形不受影响） |
 * | `motion_degrade_level` | Int 0..3 | `0` | 自动降级水位（见 [MotionDegrade]） |
 * | `motion_version` | Int | 缺 key 视为 `0` | 迁移水位（`0` = v2.8.0 或更早的盘） |
 *
 * ## 迁移：v2.8.0 的 8 个键 → v2.9.0 的 4 个键（幂等、有水位、有单测）
 *
 * v2.8.0 引入了 `visualizer_tier` / `visualizer_showcase` / `visualizer_shockwave` /
 * `visualizer_particles` / `visualizer_perspective` / `visualizer_drag` /
 * `visualizer_auto_downgraded` / `visualizer_tier_version` 八个键。v2.9.0 里它们**全部保留**
 * （`SettingsRegistry` 里标 `isInternal` + `isLegacyV280`，见 `legacyPrefKeysArePreservedExactly`
 * 的双向等值断言），但**不再驱动渲染**。搬运规则只有两条，都是「保住用户实际看到的东西」：
 *
 * 1. **档位取 v2.8.0 的「有效档」而不是盘上的原始值**：
 *    v2.8.0 的 C 档要求 `tier == 2` **且** `visualizer_showcase == true`；而 `showcase`
 *    的默认是 `false`。也就是说「选了炫技但没开炫技效果」的老盘，在 v2.8.0 下**渲染出来
 *    就是精致档**（`WaveformStrings.visualizerShowcaseDescription` 原文：「关闭后炫技档与
 *    精致档外观一致」）。v2.9.0 把细分开关合并进档位之后，照抄 `2` 会让这些用户**突然**
 *    多出冲击波/粒子/3D —— 那是语义变化。所以搬运的是**有效档**：
 *    `legacyTier == 2 && !legacyShowcase` ⇒ 搬到 `1`。
 * 2. **已经自动降级过的设备，从「第一级界面动效降级」起跑**：
 *    v2.8.0 的 `visualizer_auto_downgraded == true` 是「这台机器的帧时间撑不住」的实测证据。
 *    它的降级结果**已经写在 `visualizer_tier` 里**（v2.8.0 就是这么实现的），所以档位照搬即可；
 *    但新版本**不应该**让这台已知吃力的设备再从头跑一遍完整动效。因此把水位预置成
 *    [MotionDegrade.UI_ADVANCED_OFF]（砍 B 档界面动效）—— 既不重复扣波形，也不假装没发生过。
 *
 * **没有选过档位的盘不写 `motion_tier`**：v2.8.0 的「缺 key ≠ 选了默认档」这条纪律
 * （[VisualizerTier.defaultTier] 的解析默认值 + 换机后不该锁死在老默认上）原样保留。
 *
 * ## 幂等与有界
 *
 * - [migrate] 只在 `motion_version < CURRENT` 时动作，写盘一次；重复调用返回 `false` 且**盘逐键不变**；
 * - 迁移**不删除、不修改任何既有键**（`visualizer_*` 一律原样留着，回滚安装不会丢用户数据）；
 * - 迁移失败（异常）不阻断读取：读取路径本来就带 `runCatching` 兜底。
 */
object MotionPrefs {

    const val PREFS = "ncrust_settings"

    // ---- 落盘键（唯一字面量落点）----
    const val KEY_TIER = "motion_tier"
    const val KEY_UI_MOTION = "ui_motion_enabled"
    const val KEY_DEGRADE_LEVEL = "motion_degrade_level"
    const val KEY_VERSION = "motion_version"

    // ---- 默认值 ----
    /** 「界面动效」总开关默认**开**（任务书铁律 22：A 档默认开，但保留总开关）。 */
    const val DEFAULT_UI_MOTION = true

    /** 降级水位默认 0（未降级）。 */
    const val DEFAULT_DEGRADE_LEVEL = MotionDegrade.NONE

    /** 迁移水位的「未迁移」值：缺 key 或 v2.8.0 及更早的盘。 */
    const val VERSION_PRE_V290 = 0

    /** 当前水位。加语义 ⇒ +1 并在 [migrate] 里补一段搬运逻辑。 */
    const val CURRENT_VERSION = 1

    // ------------------------------------------------------------------
    // 纯读（键缺失 / 类型不符都不抛异常 —— 坏数据最坏只是回落默认值）
    // ------------------------------------------------------------------

    /**
     * 用户是否**显式选择过**档位（键存在且类型正确）。
     * 这是「没选过（用设备判据解析）」与「选了精致」的唯一判据，与 v2.8.0 的口径逐字一致。
     */
    fun hasExplicitTier(prefs: SharedPreferences): Boolean = intOrNull(prefs, KEY_TIER) != null

    /**
     * 实际生效的档位：显式选择优先，否则用调用方解析出来的设备默认档。
     * **越界值同样回落该默认档**（不是回落常量 1 —— 低端机上常量 1 会把用户顶到精致档）。
     */
    fun readTier(prefs: SharedPreferences, deviceDefault: Int): Int {
        val fallback = MotionIntensity.sanitize(deviceDefault, MotionIntensity.REFINED)
        val raw = intOrNull(prefs, KEY_TIER) ?: return fallback
        return MotionIntensity.sanitize(raw, fallback)
    }

    fun readUiMotionEnabled(prefs: SharedPreferences): Boolean =
        bool(prefs, KEY_UI_MOTION, DEFAULT_UI_MOTION)

    fun readDegradeLevel(prefs: SharedPreferences): Int =
        MotionDegrade.sanitize(intOrNull(prefs, KEY_DEGRADE_LEVEL) ?: DEFAULT_DEGRADE_LEVEL)

    /** 缺 key 视为「未迁移」（[VERSION_PRE_V290]），不是「已迁移到 0」—— 两义性必须分清。 */
    fun readVersion(prefs: SharedPreferences): Int =
        intOrNull(prefs, KEY_VERSION) ?: VERSION_PRE_V290

    /** 一次读全：设置变化/降级时只重组一次（帧路径里不再读任何 state）。 */
    fun readEffects(
        prefs: SharedPreferences,
        deviceDefault: Int,
        showcaseEnabled: Boolean = true,
    ): MotionEffects = MotionEffects.of(
        tier = readTier(prefs, deviceDefault),
        uiMotionEnabled = readUiMotionEnabled(prefs),
        degradeLevel = readDegradeLevel(prefs),
        waveformShowcaseEnabled = showcaseEnabled,
    )

    // ------------------------------------------------------------------
    // 纯写
    // ------------------------------------------------------------------

    /**
     * 用户显式改档位。**同时把降级水位清零**（任务书 §8.2：「用户手动改档位时重置降级状态」）——
     * 否则用户在「已经降过级」的机器上永远看不到自己点的那一档，设置页会像坏了一样。
     */
    fun writeTier(prefs: SharedPreferences, tier: Int) {
        safeEdit(prefs) {
            it.putInt(KEY_TIER, MotionIntensity.sanitize(tier, MotionIntensity.REFINED))
            it.putInt(KEY_DEGRADE_LEVEL, MotionDegrade.NONE)
        }
    }

    /**
     * 写「界面动效」总开关。**不顺带重置降级水位**：总开关是用户的稳定偏好，
     * 降级水位是设备实测的结论，两者的生命周期不同（重置只发生在改档位那一刻）。
     */
    fun writeUiMotionEnabled(prefs: SharedPreferences, enabled: Boolean) {
        safeEdit(prefs) { it.putBoolean(KEY_UI_MOTION, enabled) }
    }

    fun writeDegradeLevel(prefs: SharedPreferences, level: Int) {
        safeEdit(prefs) { it.putInt(KEY_DEGRADE_LEVEL, MotionDegrade.sanitize(level)) }
    }

    // ------------------------------------------------------------------
    // 迁移（纯逻辑 + 幂等水位）
    // ------------------------------------------------------------------

    /**
     * v2.8.0 老盘的**有效档位**推导（纯函数，单测逐格断言）。
     *
     * @param legacyTier `visualizer_tier` 的原始值；`null` = 这个键不存在（用户没选过）。
     * @param legacyShowcase `visualizer_showcase` 的值（缺 key 时读出来就是 `false`，
     *   与 v2.8.0 的 `readShowcase` 默认值一致 —— 这一点很关键，见对象 KDoc 第 1 条）。
     * @param deviceDefault 解析出的设备默认档（`null` 档位时的回落，也是越界值的回落）。
     * @return `null` = **不要写 `motion_tier`**（用户没选过，让新版本继续用解析默认档）。
     */
    fun migratedTier(legacyTier: Int?, legacyShowcase: Boolean, deviceDefault: Int): Int? {
        if (legacyTier == null) return null
        val effective = MotionIntensity.sanitize(legacyTier, deviceDefault)
        // 炫技档但「炫技效果」关着 ⇒ v2.8.0 实际渲染的就是精致档（见对象 KDoc）。
        return if (effective == MotionIntensity.SHOWCASE && !legacyShowcase) {
            MotionIntensity.REFINED
        } else {
            effective
        }
    }

    /**
     * v2.8.0 的 `visualizer_auto_downgraded` → v2.9.0 的起始降级水位（纯函数）。
     *
     * 只映射到 [MotionDegrade.UI_ADVANCED_OFF]：v2.8.0 的那次降级**已经体现在档位里**，
     * 这里再扣一次波形就是重复处罚；但完全不理会又等于把一台实测吃力的设备当新机。
     * 砍掉 B 档界面动效是代价最小、又确实回吐预算的那一格。
     */
    fun migratedDegradeLevel(legacyAutoDowngraded: Boolean): Int =
        if (legacyAutoDowngraded) MotionDegrade.UI_ADVANCED_OFF else MotionDegrade.NONE

    /**
     * 幂等迁移：**不删不改任何既有键**（8 个 `visualizer_*` 一律原样）。
     *
     * @return 本次是否真的写了盘（单测用；生产路径不关心）。
     */
    fun migrate(prefs: SharedPreferences, deviceDefault: Int): Boolean {
        if (readVersion(prefs) >= CURRENT_VERSION) return false
        val legacyTier = intOrNull(prefs, LegacyKeys.KEY_TIER)
        val legacyShowcase = bool(prefs, LegacyKeys.KEY_SHOWCASE, false)
        val legacyAutoDowngraded = bool(prefs, LegacyKeys.KEY_AUTO_DOWNGRADED, false)

        val tier = migratedTier(legacyTier, legacyShowcase, deviceDefault)
        val level = migratedDegradeLevel(legacyAutoDowngraded)
        safeEdit(prefs) {
            if (tier != null) it.putInt(KEY_TIER, tier)
            if (level != MotionDegrade.NONE) it.putInt(KEY_DEGRADE_LEVEL, level)
            it.putInt(KEY_VERSION, CURRENT_VERSION)
        }
        return true
    }

    // ------------------------------------------------------------------
    // 自动降级（有界阶梯）
    // ------------------------------------------------------------------

    /**
     * 帧时间实测触发的**一次**降级。动作是「推进一级水位」，必要时把波形档位一起降下来。
     *
     * 三条边界全在 [MotionDegrade] 里（每进程一次由调用方的 FrameBudgetPolicy 保证 /
     * 到顶即止 / 只推进一级）。到 [MotionDegrade.WAVEFORM_DOWN] 时**同时**把 `motion_tier`
     * 减一：设置页显示的必须是「实际在渲染的那一档」，否则用户看到「炫技」、画面上是「精致」
     * —— 那是撒谎（v2.8.0 的同一条纪律）。
     *
     * @return 降级后的水位；`null` = 不动作（已到顶）。
     */
    fun applyAutoDowngrade(prefs: SharedPreferences): Int? {
        val next = MotionDegrade.next(readDegradeLevel(prefs)) ?: return null
        val currentTier = readTier(prefs, MotionIntensity.REFINED)
        val cutTier = MotionDegrade.cutsWaveformTier(next) && currentTier > MotionIntensity.SIMPLE
        safeEdit(prefs) {
            it.putInt(KEY_DEGRADE_LEVEL, next)
            if (cutTier) it.putInt(KEY_TIER, currentTier - 1)
        }
        return next
    }

    // ------------------------------------------------------------------
    // 进程内镜像（组合期读它；音频线程**不读这里**）
    // ------------------------------------------------------------------

    private val effectsStateHolder = mutableStateOf(
        MotionEffects.of(
            tier = MotionIntensity.REFINED,
            uiMotionEnabled = DEFAULT_UI_MOTION,
            degradeLevel = DEFAULT_DEGRADE_LEVEL,
        )
    )

    private var loadedFromDisk = false

    /** 组合期读这一个状态：设置变化 / 自动降级时只重组一次。 */
    val effects: State<MotionEffects> get() = effectsStateHolder

    /** 当前渲染档位（帧时间监控判据用）。 */
    val currentTier: Int get() = effectsStateHolder.value.tier

    /** 当前降级水位。 */
    val currentDegradeLevel: Int get() = effectsStateHolder.value.degradeLevel

    /**
     * 进程内初始化：读盘 + 迁移 + 解析设备默认档。**幂等**，与
     * `VisualizerSetting.read(context)` 一起在 `MainActivity.onCreate` 里被调用。
     * 放在 `VisualizerPrefs.ensureLoaded` 的转发里（那是 v2.8.0 已经接好的唯一初始化入口）。
     */
    fun ensureLoaded(context: Context) {
        if (loadedFromDisk) return
        val prefs = prefs(context)
        val deviceDefault = deviceDefaultTier(context)
        // 迁移失败不阻断读取：它只写两个键，坏了也不该让动效整体不可用。
        runCatching { migrate(prefs, deviceDefault) }
        effectsStateHolder.value = runCatching { readEffects(prefs, deviceDefault) }
            .getOrElse { effectsStateHolder.value }
        loadedFromDisk = true
    }

    /** 用户显式选择档位（设置页调用）。 */
    fun setTier(context: Context, tier: Int) = update(context) { writeTier(it, tier) }

    /** 用户切换「界面动效」总开关（设置页调用）。 */
    fun setUiMotionEnabled(context: Context, enabled: Boolean) =
        update(context) { writeUiMotionEnabled(it, enabled) }

    /** 设备静态判据解析出的默认档（设置页回显「未选择时实际是哪一档」用同一个函数）。 */
    fun deviceDefaultTier(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val totalMem = runCatching {
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            info.totalMem
        }.getOrDefault(0L)
        return MotionIntensity.defaultFor(
            isLowRamDevice = runCatching { am?.isLowRamDevice == true }.getOrDefault(false),
            totalMemBytes = totalMem,
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    /**
     * 帧时间监控发现「持续超标」时调用：落盘 + 刷新进程内状态。
     *
     * @return 降级后的水位；`null` = 不动作（已到顶）。
     */
    fun applyAutoDowngrade(context: Context): Int? {
        val next = runCatching { applyAutoDowngrade(prefs(context)) }.getOrNull() ?: return null
        refresh(context)
        return next
    }

    internal fun resetForTest() {
        loadedFromDisk = false
        effectsStateHolder.value = MotionEffects.of(
            tier = MotionIntensity.REFINED,
            uiMotionEnabled = DEFAULT_UI_MOTION,
            degradeLevel = DEFAULT_DEGRADE_LEVEL,
        )
    }

    private inline fun update(context: Context, write: (SharedPreferences) -> Unit) {
        val prefs = prefs(context)
        runCatching { write(prefs) }
        refresh(context)
    }

    private fun refresh(context: Context) {
        effectsStateHolder.value = runCatching {
            readEffects(prefs(context), deviceDefaultTier(context))
        }.getOrElse { effectsStateHolder.value }
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

    /**
     * v2.8.0 的 8 个字面量在这里再声明一次（**只读**，本对象从不写它们）。
     *
     * 为什么不直接引用 `VisualizerPrefs.KEY_*`：那会让「迁移读的是哪个键」这件事
     * 依赖另一个可变对象；迁移是一份**历史契约**，历史键名应当与历史键名放在一起。
     * 单测会同时断言两边逐字相等（`MotionPrefsTest.legacyKeyNamesMatchVisualizerPrefs`），
     * 所以分叉不可能悄悄发生。
     */
    object LegacyKeys {
        const val KEY_TIER = "visualizer_tier"
        const val KEY_SHOWCASE = "visualizer_showcase"
        const val KEY_AUTO_DOWNGRADED = "visualizer_auto_downgraded"
    }
}
