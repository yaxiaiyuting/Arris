package com.takahashirinta.ncrust.celebration

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

data class CelebrationOptions(val month: Int, val day: Int, val enabled: Boolean, val animated: Boolean, val shown: Set<String>)

object CelebrationPrefs {
    const val MONTH = "birthday_month"
    const val DAY = "birthday_day"
    const val ENABLED = "birthday_celebration_enabled"
    const val ANIMATED = "easter_egg_animation_enabled"
    const val SHOWN = "birthday_shown_years"
    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("ncrust_settings", Context.MODE_PRIVATE)
    fun read(context: Context): CelebrationOptions {
        val p = prefs(context)
        fun int(key: String) = runCatching { p.getInt(key, 0) }.getOrDefault(0)
        fun bool(key: String, fallback: Boolean) = runCatching { p.getBoolean(key, fallback) }.getOrDefault(fallback)
        val month = int(MONTH); val day = int(DAY)
        return CelebrationOptions(month, day, bool(ENABLED, false) && BirthdayRule.valid(month, day), bool(ANIMATED, true),
            runCatching { p.getStringSet(SHOWN, emptySet())!!.toSet() }.getOrDefault(emptySet()))
    }
    fun setDate(context: Context, month: Int, day: Int) {
        require(BirthdayRule.valid(month, day))
        prefs(context).edit().putInt(MONTH, month).putInt(DAY, day).putBoolean(ENABLED, true).apply()
    }
    fun clearDate(context: Context) { prefs(context).edit().remove(MONTH).remove(DAY).putBoolean(ENABLED, false).apply() }
    fun setEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(ENABLED, enabled).apply() }
    fun setAnimated(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(ANIMATED, enabled).apply() }
    fun markShown(context: Context, year: Int) {
        val shown = read(context).shown + year.toString()
        prefs(context).edit().putStringSet(SHOWN, shown.sortedBy { it.toIntOrNull() ?: 0 }.takeLast(32).toSet()).apply()
    }
}

@Composable
fun rememberCelebrationOptions(context: Context): CelebrationOptions {
    val p = remember(context) { CelebrationPrefs.prefs(context) }
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(p) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick++ }
        p.registerOnSharedPreferenceChangeListener(listener)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(context, tick) { CelebrationPrefs.read(context) }
}
