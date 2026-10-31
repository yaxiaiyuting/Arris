package com.takahashirinta.ncrust.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color as ComposeColor
import com.takahashirinta.ncrust.ui.theme.color.CoverPalette

/**
 * 主题色预设条目
 * @param label 显示名称（中文）
 * @param color 主题色值
 */
data class ThemeColorPreset(
    val label: String,
    val color: Color
)

/**
 * 6 种主题色预设，契合设计文档第十三节：
 * Spruce / Cobalt / Crimson / Amber / Violet / Mono
 * 云杉为官网松绿；深色模式使用对应的浅鼠尾草强调色。
 */
val themeColorPresets = listOf(
    ThemeColorPreset("云杉",  Color(0xFF274B36)),
    ThemeColorPreset("钴蓝",  Color(0xFF3B82F6)),
    ThemeColorPreset("绯红",  Color(0xFFEF4444)),
    ThemeColorPreset("琥珀",  Color(0xFFF59E0B)),
    ThemeColorPreset("堇紫",  Color(0xFF8B5CF6)),
    ThemeColorPreset("素白",  Color(0xFFFFFFFF)),
)

/** 主题模式：跟随系统 / 深色 / 浅色。 */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

private const val PREFS_NAME = "ncrust_settings"
private const val KEY_THEME_INDEX = "theme_color_index"
private const val KEY_THEME_MODE = "theme_mode"

/** 读取已保存的主题模式，默认跟随系统。 */
fun getSavedThemeMode(context: Context): ThemeMode {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_THEME_MODE, null)
    return runCatching { ThemeMode.valueOf(raw ?: "") }.getOrDefault(ThemeMode.SYSTEM)
}

fun saveThemeMode(context: Context, mode: ThemeMode) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_THEME_MODE, mode.name)
        .apply()
}

/**
 * 从 SharedPreferences 读取已保存的主题索引（默认 0 = 云杉）
 */
fun getSavedThemeIndex(context: Context): Int {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(KEY_THEME_INDEX, 0)
}

/**
 * 持久化主题索引（0..5）
 */
fun saveThemeIndex(context: Context, index: Int) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putInt(KEY_THEME_INDEX, index)
        .apply()
}

/**
 * 根据索引获取对应的 Color 预设，索引越界时兜底云杉
 */
fun themeColorForIndex(index: Int, isDark: Boolean = false): Color {
    val selected = themeColorPresets.getOrElse(index) { themeColorPresets[0] }.color
    return if (selected == themeColorPresets[0].color && isDark) DefaultNcrustColors.primary else selected
}

@Composable
fun NcrustTheme(
    primaryColor: Color = Color(0xFF274B36),
    isDark: Boolean = true,
    /**
     * v2.5.0 · A（色调）：封面取色的多角色调色板。非 null 时**背景与强调色跟随封面**。
     *
     * 默认 null = 保持既有观感（预设色 + 固定底），所以全部既有调用点零行为变化。
     * 只有用户在设置里选了「主题色来源 = 跟随封面」时 `MainActivity` 才会传它进来。
     *
     * ⚠️ 传进来的这一套必须已经由 `CoverThemeExtractor` 在**后台线程**算好 ——
     * 本函数在组合期求值，这里只做字段搬运，不做任何颜色计算（铁律 3）。
     */
    coverPalette: CoverPalette? = null,
    content: @Composable () -> Unit
) {
    val colors = remember(primaryColor, isDark, coverPalette) {
        val accent = if (primaryColor == themeColorPresets[0].color && isDark)
            DefaultNcrustColors.primary else primaryColor
        val base = (if (isDark) DefaultNcrustColors else LightNcrustColors).copy(
            primary = accent,
            // B2-C：onPrimary 随主题色推导。原先恒为白色，浅色主题色（如琥珀）上
            // 文字/图标对比度不足 —— 这是 4.5:1 要求的前提。
            onPrimary = onAccentColor(accent),
        )
        // v2.5.0：跟随封面时，背景/容器/描边/强调色整组来自封面调色板。
        // 注意 `toNcrustColors` **不覆盖** surfaceContainerHigh/Highest ——
        // 那是菜单与输入框的底色，可读性影响面比"背景好不好看"大（见其 KDoc）。
        if (coverPalette != null) CoverThemeExtractor.toNcrustColors(coverPalette, base) else base
    }
    CompositionLocalProvider(
        LocalNcrustColors provides colors,
        LocalNcrustTypography provides DefaultNcrustTypography,
        content = content
    )
}

/**
 * 由主题色派生的输入框/搜索框底色：深色主题下是极暗的低饱和色，
 * 浅色主题下是极亮(近白)的低饱和色——保证框内文字(onBackground)始终可读。
 */
fun desaturateColor(
    color: ComposeColor,
    saturationFactor: Float = 0.08f,
    darkTheme: Boolean = true
): ComposeColor {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[1] *= saturationFactor
    hsv[2] = if (darkTheme) hsv[2] * 0.25f + 0.02f else 0.93f
    return ComposeColor(android.graphics.Color.HSVToColor(hsv))
}

private fun ComposeColor.toArgb(): Int {
    return android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    )
}
