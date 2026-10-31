package com.takahashirinta.ncrust.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.takahashirinta.kanesumi.core.theme.MetroColors

/**
 * Ncrust 自有配色板。替代 MD3 的 ColorScheme。
 *
 * 设计：深色为 OLED 纯黑背景；浅色为米色偏白底。两套共享同一组强调色。
 * 所有颜色硬编码，不依赖 MD3 的 tonalElevation 调色逻辑 —— 层级做进颜色，不加阴影。
 *
 * P2 · 容器层级：`surfaceContainer*` 沿用 M3 的语义命名（只作取值参照系，不引入
 * material3 依赖），取值是本设计系统自己的低亮度阶梯。方向 = 「与该模式的页面底色
 * 对比逐级增强」：
 *  - 深色 OLED 底 #000000 逐级更亮：#0E0E0E → #1A1A1A → #242424 → #2E2E2E；
 *  - 浅色纸底 #F6F2E9 逐级更暖更深：#FBF7EE → #FFFDF8 → #F2EBDE → #EDE6D8。
 * 两套都只有颜色差，不产生任何阴影 —— 无 elevation 的视觉识别不变。
 * （v2.5.0 · B 起圆角由 `AppShapes` 统一施加；旧正典是「直角、无圆角」，
 * 颜色板本身仍然只描述颜色、不携带任何形状。）
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

/**
 * 默认配色：云杉绿主题 + OLED 纯黑。
 * onSurfaceVariant 用中性灰 #B3B3B3（Spotify 风），与 MD3 紫调 onSurfaceVariant 区分。
 *
 * 层级：页面 #000000 → 过渡 #0E0E0E → 基准容器 #1A1A1A → 弹窗 #242424 → 浮层 #2E2E2E。
 * 全部相对 #000000 单调变亮，OLED 黑底不变灰。
 * WCAG 对比度（最亮的一档 #2E2E2E 上）：onSurface #FFFFFF 13.6:1、onSurfaceVariant
 * #B3B3B3 6.5:1 —— 每档都 ≥4.5:1，抬升不回退可读性。
 */
/** Website palette: pine, warm paper and sage. Dark surfaces retain readable separation. */
val DefaultNcrustColors = NcrustColors(
    primary = Color(0xFFB4CEA0), onPrimary = Color(0xFF173C2E),
    background = Color(0xFF101A15), surface = Color(0xFF19271F),
    surfaceVariant = Color(0xFF26372B), onBackground = Color(0xFFF4F6EF),
    onSurface = Color(0xFFF4F6EF), onSurfaceVariant = Color(0xFFB2BFB1),
    surfaceContainerLowest = Color(0xFF101A15), surfaceContainerLow = Color(0xFF15221A),
    surfaceContainerHigh = Color(0xFF223229), surfaceContainerHighest = Color(0xFF2C4032),
    outline = Color(0xFF6B806E), outlineVariant = Color(0xFF344A3B),
)

val LightNcrustColors = NcrustColors(
    primary = Color(0xFF274B36), onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F7F2), surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8EDDF), onBackground = Color(0xFF173C2E),
    onSurface = Color(0xFF173C2E), onSurfaceVariant = Color(0xFF5B6958),
    surfaceContainerLowest = Color(0xFFF6F7F2), surfaceContainerLow = Color(0xFFF0F3E9),
    surfaceContainerHigh = Color(0xFFEEF1E7), surfaceContainerHighest = Color(0xFFE3EAD9),
    outline = Color(0xFF85957A), outlineVariant = Color(0xFFD5DECC),
)

val LocalNcrustColors = compositionLocalOf { DefaultNcrustColors }

/**
 * 由 Ncrust 调色板派生 Kanesumi MetroColors，补齐 MetroColors 独有字段
 * （divider / onPrimary / pressTint），保证两套主题源视觉一致。
 *
 * P2：MetroColors 只有 3 个色槽，容器层级按用途映射 ——
 *  - `surface`        ← 基准容器（卡片 / 列表 / 弹窗默认底），深色仍是 #1A1A1A：既有调用点零变化。
 *  - `surfaceVariant` ← surfaceContainerHighest（菜单 / 下拉 / 输入框 / 图片占位块这类
 *                       「浮在基准容器之上」的面）。**这是「深色下 surface == surfaceVariant
 *                       == #1A1A1A，层级差为 0」的修复点**：深色 #1A1A1A → #2E2E2E；
 *                       浅色沿用原值 #EDE6D8（浅色既有层级本来就是可见的，不动）。
 *  - `divider`        ← outlineVariant（深色 #2A2A2A / 浅色 #E2DACB，与改动前逐字节一致）。
 * 需要比基准容器更高一档的弹窗调用点，读 `LocalNcrustColors.current.surfaceContainerHigh`
 * （见 UserScreen 的 ClearCacheConfirmDialog / AccountDialog、BackgroundActivityDialog）。
 */
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
