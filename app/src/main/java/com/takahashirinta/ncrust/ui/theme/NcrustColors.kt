package com.takahashirinta.ncrust.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.takahashirinta.kanesumi.core.theme.MetroColors

/**
 * App-owned palette, shared with Kanesumi through [toMetroColors].
 * Neutral matte surfaces keep text and artwork distinct; pine/sage remains the accent.
 * Container roles describe depth with tone, without requiring shadows or backdrop blur.
 */
@Immutable
data class NcrustColors(
    val primary: Color,
    // B2-C：与 primary 搭配的前景色。默认白色（保持既有观感），由 NcrustTheme 随主题色推导。
    val onPrimary: Color = Color(0xFFFFFFFF),
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    // ── P2 新增：容器层级 + 描边（全部带默认值 = 深色现值，老调用点零变化）────────
    // Lowest      页面级最低层（= background 的深色值）
    // Low         页面与基准容器之间的过渡层
    // （surface） 基准容器：卡片 / 列表背板 / 弹窗默认底，即上面已有的 surface 字段
    // High        抬升一档：弹窗（M3 的 dialog container 就是 surfaceContainerHigh）
    // Highest     抬升两档：浮层 / 菜单 / 下拉 / 输入框 / 图片占位块
    val surfaceContainerLowest: Color = Color(0xFF000000),
    val surfaceContainerLow: Color = Color(0xFF0E0E0E),
    val surfaceContainerHigh: Color = Color(0xFF242424),
    val surfaceContainerHighest: Color = Color(0xFF2E2E2E),
    // 组件描边。outlineVariant = 分隔线 / 弱描边；outline = 控件边界（如未选中的分段选择器）。
    val outline: Color = Color(0xFF4A4A4A),
    val outlineVariant: Color = Color(0xFF2A2A2A),
)

/** Matte charcoal. Preset and cover-based accents remain configurable. */
val DefaultNcrustColors = NcrustColors(
    primary = Color(0xFFB4CEA0), onPrimary = Color(0xFF173C2E),
    background = Color(0xFF141715), surface = Color(0xFF1D221F),
    surfaceVariant = Color(0xFF29312B), onBackground = Color(0xFFF1F3ED),
    onSurface = Color(0xFFF1F3ED), onSurfaceVariant = Color(0xFFB9C1B7),
    surfaceContainerLowest = Color(0xFF141715), surfaceContainerLow = Color(0xFF191D1A),
    surfaceContainerHigh = Color(0xFF252C27), surfaceContainerHighest = Color(0xFF303932),
    outline = Color(0xFF778275), outlineVariant = Color(0xFF374139),
)

val LightNcrustColors = NcrustColors(
    primary = Color(0xFF274B36), onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F5F0), surface = Color(0xFFFFFEFA),
    surfaceVariant = Color(0xFFECEFE5), onBackground = Color(0xFF202A23),
    onSurface = Color(0xFF202A23), onSurfaceVariant = Color(0xFF606A5D),
    surfaceContainerLowest = Color(0xFFF5F5F0), surfaceContainerLow = Color(0xFFF0F1EA),
    surfaceContainerHigh = Color(0xFFEEF0E7), surfaceContainerHighest = Color(0xFFE5E9DD),
    outline = Color(0xFF87917F), outlineVariant = Color(0xFFD9DED2),
)

val LocalNcrustColors = compositionLocalOf { DefaultNcrustColors }

/** Input/menu surfaces use the highest container role; separators use the quiet outline. */
fun NcrustColors.toMetroColors(isDark: Boolean): MetroColors = MetroColors(
    background = background,
    surface = surface,
    surfaceVariant = surfaceContainerHighest,
    primary = primary,
    // B2-C：跟随 NcrustColors 的推导结果 —— 原先硬编码白色会把 NcrustTheme 的
    // onPrimary 覆盖回去，导致 Kanesumi 组件（按钮文字/图标）仍是白字。
    onPrimary = onPrimary,
    onBackground = onBackground,
    onSurface = onSurface,
    onSurfaceVariant = onSurfaceVariant,
    divider = outlineVariant,
    pressTint = if (isDark) Color(0x22FFFFFF) else Color(0x14000000),
)
