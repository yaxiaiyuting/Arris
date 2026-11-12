/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.audio

import android.content.Context
import android.content.SharedPreferences

/**
 * 均衡器的持久化（4.0.0）。
 *
 * 存"每段增益 + 预设名"，**不存设备预设索引** —— 各厂商的 preset 索引与频段数不同
 * （真机见过 5 段与 10 段），存索引换机必然错位。
 */
object EqualizerPrefs {

    private const val PREFS = "ncrust_settings"
    private const val KEY_ENABLED = "eq_enabled"
    private const val KEY_GAINS_PREFIX = "eq_gain_"
    private const val KEY_PRESET = "eq_preset"
    private const val KEY_IMPORTED_PRESETS = "eq_imported_presets"

    internal val frequenciesHz = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /** 预设：名称 → 增益（mB）。曲线按"低频 +、中频平、高频微升"的常见听感给。 */
    private val builtInPresets = listOf(
        EqualizerPreset("平直", listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
        EqualizerPreset("流行", listOf(300, 200, 0, -100, -100, 0, 200, 400, 500, 500)),
        EqualizerPreset("摇滚", listOf(500, 350, 200, 0, -150, -100, 200, 400, 500, 550)),
        EqualizerPreset("人声", listOf(-150, -100, 0, 200, 400, 450, 300, 0, -100, -150)),
        EqualizerPreset("低音", listOf(700, 600, 450, 250, 0, -100, -150, -150, -100, 0)),
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun presetName(context: Context): String? = prefs(context).getString(KEY_PRESET, null)

    /** 读每段增益（mB）。没有存过就返回预设第一条（平直）。 */
    fun gains(context: Context): IntArray {
        val p = prefs(context)
        return IntArray(EqualizerController.MAX_BANDS) { index ->
            p.getInt(KEY_GAINS_PREFIX + index, 0)
        }
    }

    fun setGain(context: Context, band: Int, gainMb: Int) {
        prefs(context).edit()
            .putInt(KEY_GAINS_PREFIX + band, gainMb.coerceIn(EqualizerController.GAIN_MIN_MB, EqualizerController.GAIN_MAX_MB))
            .remove(KEY_PRESET)
            .apply()
    }

    internal fun presets(context: Context): List<EqualizerPreset> = builtInPresets + importedPresets(prefs(context))

    private fun importedPresets(prefs: SharedPreferences): List<EqualizerPreset> =
        prefs.getString(KEY_IMPORTED_PRESETS, null)?.let(EqualizerPresetFile::decodeStored).orEmpty()

    internal fun applyPreset(context: Context, preset: EqualizerPreset, enable: Boolean = false) {
        val editor = prefs(context).edit().putPreset(preset)
        if (enable) editor.putBoolean(KEY_ENABLED, true)
        editor.apply()
    }

    internal fun importPreset(context: Context, incoming: EqualizerPreset): EqualizerPreset? {
        val p = prefs(context)
        val imported = importedPresets(p)
        val existing = builtInPresets + imported
        val name = EqualizerPresetFile.nameForImport(incoming, existing)
        val preset = incoming.copy(name = name)
        val alreadyStored = existing.any { it.name == name }
        if (!alreadyStored && imported.size >= EqualizerPresetFile.MAX_PRESETS) return null
        val editor = p.edit().putPreset(preset)
        if (!alreadyStored) {
            editor.putString(KEY_IMPORTED_PRESETS, EqualizerPresetFile.encodeStored(imported + preset))
        }
        editor.apply()
        return preset
    }

    private fun SharedPreferences.Editor.putPreset(preset: EqualizerPreset): SharedPreferences.Editor {
        preset.gainsMb.forEachIndexed { index, gain -> putInt(KEY_GAINS_PREFIX + index, gain) }
        return putString(KEY_PRESET, preset.name)
    }
}
