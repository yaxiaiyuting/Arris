/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.probe

import androidx.compose.ui.graphics.toArgb
import com.takahashirinta.ncrust.ui.theme.color.Cam16
import com.takahashirinta.ncrust.ui.theme.color.CoverPalette
import com.takahashirinta.ncrust.ui.theme.color.Hct
import com.takahashirinta.ncrust.ui.theme.color.TonalPalette
import com.takahashirinta.ncrust.ui.theme.processAccentColor
import org.junit.Test
import java.io.File

/**
 * v3.2.2 探针 · §2.4 **主导频段三色（HCT primary / secondary / tertiary）的可区分性实测**。
 *
 * ## 判据（写在测之前，两档）
 *
 * CAM16-UCS 的 ΔE：**1~2 是"刚能分辨"（JND）**，本用例要的是"在 OLED 黑底上一条
 * 2~3dp 细带上**一眼**读得出换色了"，所以：
 *  - **必需**：任意两色 ΔE ≥ **5**（≈ JND 的 3 倍，明显不同）；
 *  - **理想**：ΔE ≥ **10**（完全不同的颜色）；
 *  - 另加：每色对它所在主题底色的 WCAG 对比度 ≥ **3.0**（非文本图形的下沿）。
 *
 * ## 种子必须是**真实的**主题色
 *
 * 主题色有三个来源：预设（6 个）、封面（经 `processAccentColor` 压饱和度 / 锚亮度）、
 * 系统强调色。本探针把封面种子**先过一遍 `processAccentColor`**（与 App 的路径一致），
 * 而不是拿裸封面色去算 —— 否则会得出"深色主题色在黑底上对比度 1.12"这种
 * 真实产品里不会发生的结论（那是第一轮探针的教训，baseline 表里有它的原始数据）。
 *
 * 结果写 `docs/verification/v3.2.2/probe/color-roles-measurements.md`。
 */
class V322RolesProbe {

    /** 6 个预设主题色（`ThemeManager.themeColorPresets`，与源码逐字一致）。 */
    private val presets = listOf(
        "预设·云杉" to 0xFF1DB954.toInt(),
        "预设·钴蓝" to 0xFF3B82F6.toInt(),
        "预设·绯红" to 0xFFEF4444.toInt(),
        "预设·琥珀" to 0xFFF59E0B.toInt(),
        "预设·堇紫" to 0xFF8B5CF6.toInt(),
        "预设·素白" to 0xFFFFFFFF.toInt(),
    )

    /** 封面可能抽出来的原始色（覆盖各色相 / 彩度 / 明度）。 */
    private val coverRaw = listOf(
        "封面·砖红" to 0xFFB03A2E.toInt(),
        "封面·湖蓝" to 0xFF2E86AB.toInt(),
        "封面·芥黄" to 0xFFF2C14E.toInt(),
        "封面·暗紫" to 0xFF6B4E9B.toInt(),
        "封面·墨绿" to 0xFF1F6F5C.toInt(),
        "封面·藕粉" to 0xFFD96C9A.toInt(),
        "封面·深灰" to 0xFF444444.toInt(),
        "封面·米白" to 0xFFE8E4DA.toInt(),
    )

    /**
     * 候选配色：[secHueShift], [secChroma], [terHueShift], [terChroma]。
     *
     * primary 一律 = 主题色本身（保住"波形就是主题色"的既有识别度）。
     * tone 与主题色相同（夹到可读区间）—— 三个颜色只在**色相与彩度**上分家，
     * 亮度不变，所以"哪一段更亮"这个既有读法不被伪造。
     */
    private class Variant(
        val secHueShift: Double,
        val secChroma: Double,
        val terHueShift: Double,
        val terChroma: Double,
    ) {
        val label: String
            get() = "dh2=%+.0f/C2=%.0f  dh3=%+.0f/C3=%.0f".format(
                secHueShift,
                secChroma,
                terHueShift,
                terChroma,
            )
    }

    private fun rolesFor(v: Variant, accent: Int, isDark: Boolean): IntArray {
        val hct = Hct.fromInt(accent)
        val tone = hct.tone.coerceIn(if (isDark) 55.0 else 25.0, if (isDark) 88.0 else 62.0).toInt()
        return intArrayOf(
            accent,
            TonalPalette.fromHueAndChroma(hct.hue + v.secHueShift, v.secChroma).tone(tone),
            TonalPalette.fromHueAndChroma(hct.hue + v.terHueShift, v.terChroma).tone(tone),
        )
    }

    private fun deltaE(a: Int, b: Int): Double = Cam16.fromInt(a).distance(Cam16.fromInt(b))

    private fun contrast(fg: Int, bg: Int): Double {
        fun lum(c: Int): Double {
            fun ch(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) +
                0.0722 * ch(c and 0xFF)
        }
        val a = lum(fg)
        val b = lum(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun hex(c: Int): String = "#%06X".format(c and 0xFFFFFF)

    private class Row(
        val label: String,
        val seedName: String,
        val isDark: Boolean,
        val accent: Int,
        val roles: IntArray,
        val minDe: Double,
        val minContrast: Double,
    )

    @Test
    fun hctRoleSeparation() {
        val outDir = ProbeCorpus.outputDir()
        val md = StringBuilder()
        md.append("# 探针 §2.4 主导频段三色（HCT 三角色）可区分性实测\n\n")
        md.append("判据：任意两色 **CAM16-UCS ΔE ≥ 5**（必需）/ **≥ 10**（理想）；")
        md.append("每色对它所在主题底色的 **WCAG 对比度 ≥ 3.0**。\n\n")
        md.append("实现：仓库自带的 `ui/theme/color/`（`Hct` / `TonalPalette` / `Cam16`，")
        md.append("material-color-utilities 派生）。\n\n")
        md.append("primary 固定 = **主题色本身**（保住既有识别度）；secondary / tertiary 由主题色的 ")
        md.append("HCT hue 旋转 + 指定彩度得到，**tone 与主题色相同**（只换色相与彩度，不换亮度）。\n\n")

        // 真实种子集：预设 + 封面色经 processAccentColor（与 App 同一条路径）。
        data class Seed(val name: String, val argb: Int, val viaCover: Boolean)

        val seeds = ArrayList<Seed>()
        for ((n, c) in presets) seeds += Seed(n, c, false)
        for (isDark in listOf(true, false)) {
            for ((n, c) in coverRaw) {
                val processed = processAccentColor(c, isDark)
                if (processed != null) {
                    seeds += Seed("$n(${if (isDark) "深" else "浅"}处理)", processed.toArgb(), true)
                }
            }
        }

        // 变体：色相偏移 × 彩度
        val variants = ArrayList<Variant>()
        for (secHue in listOf(-60.0, -120.0)) {
            for (secChroma in listOf(24.0, 48.0)) {
                for (terHue in listOf(60.0, 120.0)) {
                    for (terChroma in listOf(24.0, 48.0)) {
                        variants += Variant(secHue, secChroma, terHue, terChroma)
                    }
                }
            }
        }

        // baseline：Material 经典 scheme（a1/a2/a3），用于说明"为什么不能直接用"
        val baselineLabel = "V1 Material scheme（a1/a2/a3）"
        val baseline = ArrayList<Row>()
        for (s in seeds) {
            for (isDark in listOf(true, false)) {
                val p = CoverPalette.fromSeed(s.argb, isDark)
                val roles = intArrayOf(p.primary, p.secondary, p.tertiary)
                val bg = if (isDark) 0xFF000000.toInt() else 0xFFF6F2E9.toInt()
                baseline += Row(
                    baselineLabel, s.name, isDark, s.argb, roles,
                    minOf(deltaE(roles[0], roles[1]), deltaE(roles[1], roles[2]), deltaE(roles[0], roles[2])),
                    roles.minOf { contrast(it, bg) },
                )
            }
        }

        val rows = ArrayList<Row>()
        for (v in variants) {
            for (s in seeds) {
                for (isDark in listOf(true, false)) {
                    val roles = rolesFor(v, s.argb, isDark)
                    val bg = if (isDark) 0xFF000000.toInt() else 0xFFF6F2E9.toInt()
                    rows += Row(
                        v.label, s.name, isDark, s.argb, roles,
                        minOf(
                            deltaE(roles[0], roles[1]),
                            deltaE(roles[1], roles[2]),
                            deltaE(roles[0], roles[2]),
                        ),
                        roles.minOf { contrast(it, bg) },
                    )
                }
            }
        }

        md.append("## 变体排名（按最小 ΔE 降序）\n\n")
        md.append("| 变体 | 最小 ΔE | 达标(≥5) | 理想(≥10) | 最小对比度 |\n|---|---|---|---|---|\n")
        val ranked = (variants.map { it.label } + listOf(baselineLabel)).distinct()
            .map { label ->
                val sub = if (label == baselineLabel) baseline else rows.filter { it.label == label }
                label to sub
            }
            .sortedByDescending { (_, sub) -> sub.minOf { it.minDe } }
        for ((label, sub) in ranked) {
            md.append("| ").append(label)
                .append(" | ").append("%.1f".format(sub.minOf { it.minDe }))
                .append(" | ").append(sub.count { it.minDe >= 5.0 && it.minContrast >= 3.0 })
                .append(" / ").append(sub.size)
                .append(" | ").append(sub.count { it.minDe >= 10.0 && it.minContrast >= 3.0 })
                .append(" / ").append(sub.size)
                .append(" | ").append("%.2f".format(sub.minOf { it.minContrast }))
                .append(" |\n")
        }

        val best = ranked.first()
        md.append("\n## 最优变体逐样本（").append(best.first).append("）\n\n")
        md.append("| 种子 | 模式 | 主题色 | 低(primary) | 中(secondary) | 高(tertiary) | 最小 ΔE | 最小对比度 |\n")
        md.append("|---|---|---|---|---|---|---|---|\n")
        for (r in best.second.sortedWith(compareBy({ it.seedName }, { it.isDark }))) {
            md.append("| ").append(r.seedName)
                .append(" | ").append(if (r.isDark) "深" else "浅")
                .append(" | ").append(hex(r.accent))
                .append(" | ").append(hex(r.roles[0]))
                .append(" | ").append(hex(r.roles[1]))
                .append(" | ").append(hex(r.roles[2]))
                .append(" | ").append("%.1f".format(r.minDe))
                .append(" | ").append("%.2f".format(r.minContrast))
                .append(" |\n")
        }

        md.append("\n## baseline：Material 经典 scheme 为什么不能直接用\n\n")
        md.append("| 种子 | 模式 | 低(primary) | 中(secondary) | 高(tertiary) | 最小 ΔE | 最小对比度 |\n")
        md.append("|---|---|---|---|---|---|---|\n")
        for (r in baseline.sortedWith(compareBy({ it.seedName }, { it.isDark }))) {
            md.append("| ").append(r.seedName)
                .append(" | ").append(if (r.isDark) "深" else "浅")
                .append(" | ").append(hex(r.roles[0]))
                .append(" | ").append(hex(r.roles[1]))
                .append(" | ").append(hex(r.roles[2]))
                .append(" | ").append("%.1f".format(r.minDe))
                .append(" | ").append("%.2f".format(r.minContrast))
                .append(" |\n")
        }

        md.append("\n## 种子集（封面色已过 processAccentColor）\n\n| 名称 | ARGB | 来源 |\n|---|---|---|\n")
        for (s in seeds.distinctBy { it.name + it.argb }) {
            md.append("| ").append(s.name).append(" | ").append(hex(s.argb))
                .append(" | ").append(if (s.viaCover) "封面(已处理)" else "预设").append(" |\n")
        }

        File(outDir, "color-roles-measurements.md").writeText(md.toString())
        println(md)
    }
}
