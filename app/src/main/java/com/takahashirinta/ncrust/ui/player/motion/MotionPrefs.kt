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
import com.takahashirinta.ncrust.ui.player.WaveformStore

/**
 * v2.9.0：统一「动效强度」的**全部落盘键**与读写入口（`ncrust_settings`）。
 * v3.0.0：新增五个「每个动效独立开关」的键，并**删除自动降级机制**。
 * v3.2.0：新增「界面律动总闸」+ 三个律动类细粒度开关，简洁档收窄为静态档（水位 4 → 5）。
 *
 * ## 键名是持久化契约（唯一字面量落点）
 *
 * 本对象是下列键名在**全仓库唯一**的字面量落点，改名 = 静默重置所有用户的选择。
 * 单测 `MotionPrefsTest` 逐字钉住它们。
 *
 * | 键 | 类型 | 默认 | 语义 |
 * |---|---|---|---|
 * | `motion_tier` | Int 0..2 | **不写盘**，按设备判据解析 | 动效强度（波形 + 界面动效共用一个档位） |
 * | `ui_motion_enabled` | Bool | `true` | 「界面动效」总闸（关掉 = A/B/C 三层全不挂载，波形不受影响） |
 * | `motion_shockwave` | Bool | `true` | v3.0.0：瞬态冲击波 |
 * | `motion_halo` | Bool | `true` | v3.0.0：瞬态光晕 |
 * | `motion_particles` | Bool | `true` | v3.0.0：中高频粒子 |
 * | `motion_wave_bands` | Bool | `true` | v3.0.0：多频段波形调制 |
 * | `motion_breathing` | Bool | `true` | v3.0.0：背景随 RMS 呼吸（**律动类**） |
 * | `motion_rhythm_enabled` | Bool | `true` | **v3.2.0：界面律动总闸**（律动类的那一层闸，不等于总闸） |
 * | `motion_cover_float` | Bool | `true` | v3.2.0：封面随节拍浮动（律动类） |
 * | `motion_lyric_pulse` | Bool | `true` | v3.2.0：歌词当前行随节拍缩放（律动类） |
 * | `motion_bar_pulse` | Bool | `true` | v3.2.0：控制条随节拍脉冲（律动类） |
 * | `motion_degrade_level` | Int 0..3 | `0` | **历史值，不再参与渲染**（见 [MotionDegrade]） |
 * | `motion_degrade_log` | String | `""` | 同上；迁移会往里补说明 |
 * | `motion_version` | Int | 缺 key 视为 `0` | 迁移水位（`0` = v2.9.0 或更早的盘） |
 *
 * ## `motion_rhythm_enabled` 与 `ui_motion_enabled` 不是一回事（**别混**）
 *
 * - `ui_motion_enabled`（总闸）：关掉 ⇒ `MotionEffects.anyUiMotion` 为 false ⇒
 *   背景层与帧时钟**整个不挂载**（背景回纯色、零帧循环）。波形档位完全不受影响。
 * - `motion_rhythm_enabled`（律动闸）：关掉 ⇒ **只**掐掉「驱动量来自 `MotionEnvelope`
 *   的节拍 / 强拍 / 响度」那一类（背景呼吸 / 封面浮动 / 歌词律动 / 控制条脉冲 / 封面 3D）。
 *   冲击波、光晕、粒子、视差、背景级波形、背景模糊、封面阴影**一概不受影响**。
 *
 * 语义公式（`MotionEffects.of` 里逐项可见，单测逐项断言）：
 *
 * ```
 * 有效 = 档位允许 AND ui_motion_enabled AND (律动类 ? motion_rhythm_enabled : true) AND 逐项开关
 * ```
 *
 * 律动类的归类判据是**可执行的**：渲染路径里是否读 `MotionClock.pulse()` / `MotionClock.level()`
 * —— 见 `docs/verification/v3.2.0/probe-ui-jitter.md` §5 的逐项表。
 *
 * ## v3.2.0 的迁移（水位 4 → 5）
 *
 * 与 v3.0.0 同一手法：**不搬运任何键的值**，只做两件事：
 *
 *  1. 把水位写到 5；
 *  2. **如果这张盘升级之后实际渲染在简洁档**（显式选过简洁，或由设备判据解析成简洁），
 *     往 `motion_degrade_log` 追加一条说明 —— 简洁档在这一版收窄成**静态档**
 *     （背景呼吸与封面随节拍浮动不再渲染），那台设备上的用户会看到画面**变安静**，
 *     这是**唯一**能解释「为什么升级后动效变少了」的东西。其余档位的盘不写，
 *     避免给所有人塞一条噪音日志。
 *
 * 四个新开关**不需要搬运**：缺 key 的解析结果就是 `true`（[MotionSwitches.ALL_ON]），
 * 与新装一致；刻意不把默认值写回盘，理由与 `motion_tier` 的「缺 key ≠ 选了默认档」同源
 * —— 一旦写回，「用户没动过这一项」这个信息就永久丢了。
 *
 * ## 幂等与有界
 *
 * - [migrate] 只在 `motion_version < CURRENT` 时动作，写盘一次；重复调用返回 `false`
 *   且**盘逐键不变**；
 * - 迁移**不删除、不修改任何既有键**（`visualizer_*` 与 `motion_degrade_*` 一律原样留着，
 *   回滚安装不会丢用户数据）；
 * - 迁移失败（异常）不阻断读取：读取路径本来就带 `runCatching` 兜底。
 */
object MotionPrefs {

    const val PREFS = "ncrust_settings"

    // ---- 落盘键（唯一字面量落点）----
    const val KEY_TIER = "motion_tier"
    const val KEY_UI_MOTION = "ui_motion_enabled"
    const val KEY_DEGRADE_LEVEL = "motion_degrade_level"
    const val KEY_DEGRADE_LOG = "motion_degrade_log"
    const val KEY_VERSION = "motion_version"

    // ---- v3.0.0：每个新动效的独立开关（铁律 26）----
    const val KEY_SHOCKWAVE = "motion_shockwave"
    const val KEY_HALO = "motion_halo"
    const val KEY_PARTICLES = "motion_particles"
    const val KEY_WAVE_BANDS = "motion_wave_bands"
    const val KEY_BREATHING = "motion_breathing"

    // ---- v3.2.0：界面律动（节拍驱动）那一层的闸 ----
    const val KEY_RHYTHM = "motion_rhythm_enabled"
    const val KEY_COVER_FLOAT = "motion_cover_float"
    const val KEY_LYRIC_PULSE = "motion_lyric_pulse"
    const val KEY_BAR_PULSE = "motion_bar_pulse"

    // ---- 默认值 ----
    /** 「界面动效」总开关默认**开**（任务书铁律 22：A 档默认开，但保留总开关）。 */
    const val DEFAULT_UI_MOTION = true

    /**
     * 全部独立开关的默认值：**全开**。
     *
     * 为什么默认开而不是默认关：它们的存在是为了让用户**能关掉**某一样，
     * 而不是让用户去发现某一样。默认关会让「升级之后动效没变化」成为默认体验，
     * 而档位表里明明写着精致档包含冲击波/光晕/粒子 —— 那是撒谎。
     *
     * v3.2.0 的四个新键沿用同一条理由：缺 key = 与上一版观感一致（律动在精致/炫技档
     * 本来就开着）；至于**简洁档**本来就不该有它们，那是**档位**的职责，不是开关的。
     */
    const val DEFAULT_SWITCH = true

    /** 降级水位的默认值（历史键，不再参与渲染）。 */
    const val DEFAULT_DEGRADE_LEVEL = MotionDegrade.NONE

    /** 降级日志的默认值（空串 = 从未降级过）。 */
    const val DEFAULT_DEGRADE_LOG = ""

    /** 日志里保留的最后几条（每条一行，越新越靠后）。 */
    const val DEGRADE_LOG_KEEP = 5

    /** 日志总长上限（字符）。截断只从**头部**丢最旧的整行 —— 绝不把最新一条切一半。 */
    const val DEGRADE_LOG_MAX_CHARS = 700

    /** 迁移水位的「未迁移」值：缺 key 或 v2.9.0 及更早的盘。 */
    const val VERSION_PRE_V290 = 0

    /** v2.9.0 落下的水位（= 那时把 v2.8.0 的盘搬完的值）。 */
    const val VERSION_V290 = 3

    /** v3.0.0 落下的水位（= 自动降级被删除、五个独立开关落地的那一版）。 */
    const val VERSION_V300 = 4

    /** 当前水位。加语义 ⇒ +1 并在 [migrate] 里补一段搬运逻辑。 */
    const val CURRENT_VERSION = 5

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
     *
     * v3.0.0：这是渲染的**唯一**档位来源（自动降级已删除，没有任何后台改写路径）。
     */
    fun readTier(prefs: SharedPreferences, deviceDefault: Int): Int {
        val fallback = MotionIntensity.sanitize(deviceDefault, MotionIntensity.REFINED)
        val raw = intOrNull(prefs, KEY_TIER) ?: return fallback
        return MotionIntensity.sanitize(raw, fallback)
    }

    fun readUiMotionEnabled(prefs: SharedPreferences): Boolean =
        bool(prefs, KEY_UI_MOTION, DEFAULT_UI_MOTION)

    /** v2.9.0 的历史水位（**不参与渲染**；只为诊断与迁移说明而读）。 */
    fun readDegradeLevel(prefs: SharedPreferences): Int =
        MotionDegrade.sanitize(intOrNull(prefs, KEY_DEGRADE_LEVEL) ?: DEFAULT_DEGRADE_LEVEL)

    /** 降级日志（历史键）。空串 = 从未降级过。 */
    fun readDegradeLog(prefs: SharedPreferences): String =
        runCatching { prefs.getString(KEY_DEGRADE_LOG, DEFAULT_DEGRADE_LOG) }
            .getOrNull()
            .orEmpty()

    /** 缺 key 视为「未迁移」（[VERSION_PRE_V290]），不是「已迁移到 0」—— 两义性必须分清。 */
    fun readVersion(prefs: SharedPreferences): Int =
        intOrNull(prefs, KEY_VERSION) ?: VERSION_PRE_V290

    /** v3.0.0：一次读全独立开关（缺 key = 开）。v3.2.0：加入律动闸与三个律动类细粒度开关。 */
    fun readSwitches(prefs: SharedPreferences): MotionSwitches = MotionSwitches(
        shockwave = bool(prefs, KEY_SHOCKWAVE, DEFAULT_SWITCH),
        halo = bool(prefs, KEY_HALO, DEFAULT_SWITCH),
        particles = bool(prefs, KEY_PARTICLES, DEFAULT_SWITCH),
        waveBands = bool(prefs, KEY_WAVE_BANDS, DEFAULT_SWITCH),
        breathing = bool(prefs, KEY_BREATHING, DEFAULT_SWITCH),
        rhythm = bool(prefs, KEY_RHYTHM, DEFAULT_SWITCH),
        coverFloat = bool(prefs, KEY_COVER_FLOAT, DEFAULT_SWITCH),
        lyricPulse = bool(prefs, KEY_LYRIC_PULSE, DEFAULT_SWITCH),
        barPulse = bool(prefs, KEY_BAR_PULSE, DEFAULT_SWITCH),
    )

    /** 一次读全：设置变化时只重组一次（帧路径里不再读任何 state）。 */
    fun readEffects(
        prefs: SharedPreferences,
        deviceDefault: Int,
        showcaseEnabled: Boolean = true,
    ): MotionEffects = MotionEffects.of(
        tier = readTier(prefs, deviceDefault),
        uiMotionEnabled = readUiMotionEnabled(prefs),
        waveformShowcaseEnabled = showcaseEnabled,
        switches = readSwitches(prefs),
    )

    // ------------------------------------------------------------------
    // 纯写
    // ------------------------------------------------------------------

    /**
     * 用户显式改档位。
     *
     * v3.0.0：**不再重置任何降级水位** —— 那个机制已经整个删除（见 [MotionDegrade]）。
     * 档位现在是渲染的唯一输入，用户点了哪一档，画面上就是哪一档。
     */
    fun writeTier(prefs: SharedPreferences, tier: Int) {
        safeEdit(prefs) {
            it.putInt(KEY_TIER, MotionIntensity.sanitize(tier, MotionIntensity.REFINED))
        }
    }

    /** 写「界面动效」总开关。 */
    fun writeUiMotionEnabled(prefs: SharedPreferences, enabled: Boolean) {
        safeEdit(prefs) { it.putBoolean(KEY_UI_MOTION, enabled) }
    }

    /** v3.0.0：写某一个独立开关（键名由调用方从本对象的 `KEY_*` 常量取，不拼字符串）。 */
    fun writeSwitch(prefs: SharedPreferences, key: String, enabled: Boolean) {
        safeEdit(prefs) { it.putBoolean(key, enabled) }
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
     * ⚠️ v3.0.0 起这个映射**只写历史键、不参与渲染**：留着它是为了让
     * 「这台设备在 v2.8.0 曾经吃力过」这个事实在盘上不丢，供诊断与迁移说明使用。
     */
    fun migratedDegradeLevel(legacyAutoDowngraded: Boolean): Int =
        if (legacyAutoDowngraded) MotionDegrade.UI_ADVANCED_OFF else MotionDegrade.NONE

    /**
     * 幂等迁移：**不删不改任何既有键**（`visualizer_*` 一律原样）。
     *
     * - v3.0.0（水位 3 → 4）：不搬运任何键的值，只补一条「自动降级已移除」的说明
     *   —— 且**只对真的被降级过的盘补**（那些用户会看到画面多出动效，需要一个解释）。
     * - v3.2.0（水位 4 → 5）：同样不搬运任何键的值，只补一条「简洁档已收窄为静态档」的说明
     *   —— 且**只对实际渲染在简洁档的盘补**（显式选过简洁，或设备判据解析成简洁）。
     *
     * @return 本次是否真的写了盘（单测用；生产路径不关心）。
     */
    fun migrate(prefs: SharedPreferences, deviceDefault: Int): Boolean {
        val version = readVersion(prefs)
        if (version >= CURRENT_VERSION) return false
        val legacyTier = intOrNull(prefs, LegacyKeys.KEY_TIER)
        val legacyShowcase = bool(prefs, LegacyKeys.KEY_SHOWCASE, false)
        val legacyAutoDowngraded = bool(prefs, LegacyKeys.KEY_AUTO_DOWNGRADED, false)

        val tier = migratedTier(legacyTier, legacyShowcase, deviceDefault)
        val legacyLevel = readDegradeLevel(prefs)
        // 只有「盘上真的带着一次降级」才写说明：v2.8.0 的标记或 v2.9.0 的水位都算。
        val wasDegraded = legacyAutoDowngraded || legacyLevel > MotionDegrade.NONE
        // v3.2.0：这张盘**显式选过**的档位（含从 v2.8.0 搬过来的那一份）。
        // 刻意**不**把「设备判据解析出来的简洁档」算进来 —— 那是全新安装的正常结果，
        // 给他写一条"你的简洁档变安静了"是无中生有的噪音（迁移说明只在盘上真的有变化时写）。
        val explicitTier = tier
            ?: intOrNull(prefs, KEY_TIER)?.let { MotionIntensity.sanitize(it, deviceDefault) }
        val simpleDisk = explicitTier == MotionIntensity.SIMPLE

        var log = readDegradeLog(prefs)
        if (wasDegraded) {
            log = appendDegradeLog(
                log,
                degradeLogEntry(
                    level = 0,
                    tierBefore = explicitTier ?: deviceDefault,
                    uiMotionOn = readUiMotionEnabled(prefs),
                    reason = "v3-auto-degrade-removed(was ${MotionDegrade.describeLegacyLevel(legacyLevel)})",
                ),
            )
        }
        // 幂等由水位保证（这里每个盘只会走到一次）⇒ 不需要再判「是不是 v3.0.0 的盘」。
        if (simpleDisk) {
            log = appendDegradeLog(
                log,
                degradeLogEntry(
                    level = 0,
                    tierBefore = MotionIntensity.SIMPLE,
                    uiMotionOn = readUiMotionEnabled(prefs),
                    reason = "v3.2-simple-tier-is-static-now(no-breathing,no-cover-float)",
                ),
            )
        }

        safeEdit(prefs) {
            if (tier != null) it.putInt(KEY_TIER, tier)
            val level = migratedDegradeLevel(legacyAutoDowngraded)
            // 只在**盘上还没有** v2.9.0 水位时补写它（v2.9.0 的盘已经有了，别覆盖真实历史）。
            if (level != MotionDegrade.NONE && intOrNull(prefs, KEY_DEGRADE_LEVEL) == null) {
                it.putInt(KEY_DEGRADE_LEVEL, level)
            }
            if (log.isNotEmpty()) it.putString(KEY_DEGRADE_LOG, log)
            it.putInt(KEY_VERSION, CURRENT_VERSION)
        }
        return true
    }

    // ------------------------------------------------------------------
    // 进程内镜像（组合期读它；音频线程**不读这里**）
    // ------------------------------------------------------------------

    private val effectsStateHolder = mutableStateOf(
        MotionEffects.of(
            tier = MotionIntensity.REFINED,
            uiMotionEnabled = DEFAULT_UI_MOTION,
        )
    )

    private var loadedFromDisk = false

    /** 组合期读这一个状态：设置变化时只重组一次。 */
    val effects: State<MotionEffects> get() = effectsStateHolder

    /** 当前渲染档位。 */
    val currentTier: Int get() = effectsStateHolder.value.tier

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
        syncAudioFeatureDemand()
        loadedFromDisk = true
    }

    /** 用户显式选择档位（设置页调用）。 */
    fun setTier(context: Context, tier: Int) = update(context) { writeTier(it, tier) }

    /** 用户切换「界面动效」总开关（设置页调用）。 */
    fun setUiMotionEnabled(context: Context, enabled: Boolean) =
        update(context) { writeUiMotionEnabled(it, enabled) }

    /** v3.0.0：用户切换某一个独立开关（设置页调用）。 */
    fun setSwitch(context: Context, key: String, enabled: Boolean) =
        update(context) { writeSwitch(it, key, enabled) }

    /** 设备静态判据解析出的**初始**档（只在用户没选过时生效；设置页回显用同一个函数）。 */
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

    internal fun resetForTest() {
        loadedFromDisk = false
        effectsStateHolder.value = MotionEffects.of(
            tier = MotionIntensity.REFINED,
            uiMotionEnabled = DEFAULT_UI_MOTION,
        )
        WaveformStore.motionFeaturesEnabled = false
    }

    // ------------------------------------------------------------------
    // 日志（历史键的唯一写入路径：迁移说明）
    // ------------------------------------------------------------------

    /** 一条降级记录：`<epochMs> level=<n> tier=<a> ui=<bool> why=<reason>`。 */
    fun degradeLogEntry(level: Int, tierBefore: Int, uiMotionOn: Boolean, reason: String): String =
        "${System.currentTimeMillis()} level=$level tier=$tierBefore ui=$uiMotionOn why=$reason"

    /**
     * 追加一条日志，只保留最后 [DEGRADE_LOG_KEEP] 条、总长不超过 [DEGRADE_LOG_MAX_CHARS]。
     * **从头部丢最旧的整行**，绝不把最新一条切一半（切一半的日志比没有更糟）。
     */
    fun appendDegradeLog(existing: String, entry: String): String {
        val lines = (existing.lines().filter { it.isNotBlank() } + entry.trim())
            .filter { it.isNotEmpty() }
            .takeLast(DEGRADE_LOG_KEEP)
            .toMutableList()
        // 太长就从**头部**丢最旧的整行，直到放得下；只剩一行时不再丢（宁可略超长，
        // 也不能把最新那条切一半 —— 半条日志比没有更糟）。
        while (lines.size > 1 && lines.joinToString("\n").length > DEGRADE_LOG_MAX_CHARS) {
            lines.removeAt(0)
        }
        return lines.joinToString("\n")
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
        syncAudioFeatureDemand()
        loadedFromDisk = true
    }

    /**
     * v3.0.0：把「界面动效是否需要音频特征」写进音频线程侧的进程内镜像。
     *
     * 这是**唯一**的写入点（每次设置变化 / 初始化后走一次），音频线程只读那个
     * `@Volatile` 布尔 —— 音频线程上禁止读 SharedPreferences（那是 IO，v2.8.0 的纪律）。
     */
    private fun syncAudioFeatureDemand() {
        WaveformStore.motionFeaturesEnabled = effectsStateHolder.value.needsAudioFeatures
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
     * v2.8.0 的 3 个字面量在这里再声明一次（**只读**，本对象从不写它们）。
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
