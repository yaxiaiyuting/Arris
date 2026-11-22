/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v4.2.0：「后台播放保护」的厂商识别与深链候选表。
 */

package com.takahashirinta.ncrust.power

import com.takahashirinta.ncrust.ui.i18n.LanguagePreset
import com.takahashirinta.ncrust.ui.i18n.languagePresets
import com.takahashirinta.ncrust.ui.i18n.zhCN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖三件事，对应任务书的三条硬要求：
 *
 * 1. **厂商字符串 → 档位**：映射是纯函数（三个 Build 字段进、[OemProfile] 出），
 *    所以真机上不可能穷举的机型组合可以在这里逐条钉住；
 * 2. **档位 → 候选深链**：每一档候选数量、包名/类名形状、去重都由数据表断言；
 * 3. **Other 走兜底**：未识别机型不给任何深链，直接落到系统兜底页。
 *
 * 这里**不构造 Intent**：`app/build.gradle.kts` 的单测依赖只有 junit / json（无 Robolectric），
 * 在 JVM 上 `Intent()` 会抛 "not mocked"。Intent 的翻译与逐个尝试在
 * `power/OemBackgroundActions.kt`，靠真机验证（见报告「未验证部分」）。
 */
class OemBackgroundGuideTest {

    // ── 1. 厂商字符串 → 档位 ────────────────────────────────────────────────────

    @Test
    fun `厂商字符串映射到对应档位`() {
        data class Case(val manufacturer: String?, val brand: String?, val display: String?, val expect: OemProfile)

        val cases = listOf(
            // ColorOS 系：BRAND 里**没有** "oppo" 的三个子品牌必须单列，否则会掉进 OTHER。
            Case("OPPO", "OPPO", "CPH2451", OemProfile.COLOR_OS),
            Case("OnePlus", "OnePlus", "CPH2581", OemProfile.COLOR_OS),
            Case("realme", "realme", "RMX3820", OemProfile.COLOR_OS),
            Case("oplus", "oplus", "PJD110", OemProfile.COLOR_OS),
            // 刷机场景：品牌字段被改写，DISPLAY 里仍留着 ROM 名。
            Case("Google", "Pixel", "ColorOS 14.0.0", OemProfile.COLOR_OS),

            // HyperOS / MIUI
            Case("Xiaomi", "Redmi", "OS1.0.1.0.UMOMIXM", OemProfile.MIUI_HYPER_OS),
            Case("Xiaomi", "POCO", "UKQ1.230804.001", OemProfile.MIUI_HYPER_OS),
            Case("Xiaomi", "Xiaomi", "HyperOS 1.0", OemProfile.MIUI_HYPER_OS),
            Case("Xiaomi", "Mi", "V14.0.6.0.TKHMIXM", OemProfile.MIUI_HYPER_OS),

            // OriginOS / FuntouchOS
            Case("vivo", "vivo", "PD2309", OemProfile.ORIGIN_OS),
            Case("vivo", "iQOO", "PD2309", OemProfile.ORIGIN_OS),
            Case("vivo", "vivo", "Funtouch OS 14", OemProfile.ORIGIN_OS),

            // HarmonyOS / EMUI / MagicOS
            Case("HUAWEI", "HUAWEI", "ALN-AL00", OemProfile.HARMONY_EMUI),
            Case("HONOR", "HONOR", "BVL-N49", OemProfile.HARMONY_EMUI),
            Case("HONOR", "HONOR", "MagicOS 8.0", OemProfile.HARMONY_EMUI),

            // Flyme
            Case("Meizu", "meizu", "M2391", OemProfile.FLYME),

            // One UI
            Case("samsung", "samsung", "UP1A.231005.007", OemProfile.ONE_UI),

            // 未适配：Pixel / 摩托 / 类原生。存在 DISPLAY 但没有任何已知 token。
            Case("Google", "google", "AP1A.240305.019.A1", OemProfile.OTHER),
            Case("motorola", "moto", "U1TRS34.8-12-5", OemProfile.OTHER),
        )

        cases.forEach { case ->
            assertEquals(
                "manufacturer=${case.manufacturer} brand=${case.brand} display=${case.display}",
                case.expect,
                OemBackgroundGuide.profileOf(case.manufacturer, case.brand, case.display),
            )
        }
    }

    @Test
    fun `识别不区分大小写且容忍空值`() {
        // 三个字段全部来自 Build，厂商写 "OPPO"/"oppo"/"Oppo" 的都有。
        assertEquals(OemProfile.COLOR_OS, OemBackgroundGuide.profileOf("OpPo", null, null))
        assertEquals(OemProfile.MIUI_HYPER_OS, OemBackgroundGuide.profileOf(null, "XiAoMi", null))
        assertEquals(OemProfile.ONE_UI, OemBackgroundGuide.profileOf(null, null, "SAMSUNG SM-S9210"))
        // 空值 / 空串 / 全空白：都不可能命中任何 token，只能是 OTHER。
        assertEquals(OemProfile.OTHER, OemBackgroundGuide.profileOf(null, null, null))
        assertEquals(OemProfile.OTHER, OemBackgroundGuide.profileOf("", "", ""))
        assertEquals(OemProfile.OTHER, OemBackgroundGuide.profileOf("   ", null, "  "))
    }

    @Test
    fun `识别优先级是先声明先命中`() {
        // 一加刷 ColorOS 的机型：manufacturer=OnePlus、display 里带 ColorOS —— 两处都指向同一档。
        assertEquals(
            OemProfile.COLOR_OS,
            OemBackgroundGuide.profileOf("OnePlus", "OnePlus", "ColorOS 13.1"),
        )
        // 冲突输入（厂商字段互相矛盾）按表顺序取第一个命中：ColorOS 在 MIUI 之前。
        assertEquals(
            OemProfile.COLOR_OS,
            OemBackgroundGuide.profileOf("OPPO", "Xiaomi", "MIUI 14"),
        )
        // 识别 token 必须全是小写 —— 有一个大写就会永远匹配不到（字段已 lowercase）。
        OemBackgroundGuide.DETECTION_TOKENS.forEach { (profile, tokens) ->
            assertTrue("$profile 的 token 列表为空", tokens.isNotEmpty())
            tokens.forEach { token ->
                assertEquals("token 必须小写：$token", token, token.lowercase())
                assertTrue("token 必须非空且无首尾空格：'$token'", token.isNotBlank() && token.trim() == token)
            }
        }
        // 每一档（除 OTHER）都必须有识别 token，否则那一档永远到不了。
        assertEquals(
            OemProfile.entries.toSet() - OemProfile.OTHER,
            OemBackgroundGuide.DETECTION_TOKENS.map { it.first }.toSet(),
        )
    }

    // ── 2. 档位 → 深链候选 ─────────────────────────────────────────────────────

    @Test
    fun `每档深链候选数量与目标形状固定`() {
        // 数量写在这里而不是「至少一条」：候选表是本版唯一的**可机械审阅**的部分，
        // 加减一条候选应当在 diff 里显式可见。
        val expectedCounts = mapOf(
            OemProfile.COLOR_OS to 3,
            OemProfile.MIUI_HYPER_OS to 2,
            OemProfile.ORIGIN_OS to 2,
            OemProfile.HARMONY_EMUI to 2,
            OemProfile.FLYME to 1,
            OemProfile.ONE_UI to 1,
            OemProfile.OTHER to 0,
        )
        assertEquals(
            "LINK_TABLE 必须逐档都有键（含 OTHER 的空表）",
            OemProfile.entries.toSet(),
            OemBackgroundGuide.LINK_TABLE.keys,
        )
        OemProfile.entries.forEach { profile ->
            assertEquals(
                "$profile 的候选深链数量",
                expectedCounts.getValue(profile),
                OemBackgroundGuide.linksOf(profile).size,
            )
        }
    }

    @Test
    fun `深链候选的包名类名合法且不重复`() {
        OemBackgroundGuide.LINK_TABLE.forEach { (profile, links) ->
            val seen = mutableSetOf<String>()
            links.forEach { link ->
                assertTrue("$profile 的包名不是合法包名：${link.packageName}", link.packageName.count { it == '.' } >= 2)
                assertFalse("$profile 的包名不该有空白：${link.packageName}", link.packageName.any { it.isWhitespace() })
                if (link.className.isNotEmpty()) {
                    assertTrue(
                        "$profile 的类名必须全限定：${link.className}",
                        link.className.startsWith(link.packageName + "."),
                    )
                    assertFalse("$profile 的类名不该有空白：${link.className}", link.className.any { it.isWhitespace() })
                } else {
                    assertTrue("$profile 既没有类名也没有 action", link.action != null)
                }
                // 同一个目标出现两次只会让「依次尝试」白跑一遍。
                val key = link.packageName + "/" + link.className + "#" + link.action
                assertTrue("$profile 的候选重复：$key", seen.add(key))
            }
        }
        // 只有 MIUI 的 HiddenAppsConfigActivity 需要 package_name（其余入口都是列表页）。
        OemBackgroundGuide.LINK_TABLE.forEach { (profile, links) ->
            links.filter { it.extraPackageName != null }.forEach { link ->
                assertEquals(OemProfile.MIUI_HYPER_OS, profile)
                assertEquals("package_name", link.extraPackageName)
            }
        }
    }

    @Test
    fun `Other 不给深链所以只能走系统兜底`() {
        assertTrue(OemBackgroundGuide.linksOf(OemProfile.OTHER).isEmpty())
        // 未知/伪造档位同样按空表处理，绝不 fallback 到别的厂商的包（那是更糟的失败）。
        assertTrue(OemBackgroundGuide.guideOf(OemProfile.OTHER, zhTexts()).links.isEmpty())
    }

    // ── 3. 文案组装（8 语言 × 7 档都是三步） ────────────────────────────────────

    @Test
    fun `八种语言的每档三步说明都是三行`() {
        assertEquals("语言数量不是 8", 8, languagePresets.size)
        assertThreeSteps(languagePresets)
    }

    @Test
    fun `每档的最近任务表述都非空且与其他档不同`() {
        val texts = zhTexts()
        val recents = OemProfile.entries.associateWith {
            OemBackgroundGuide.guideOf(it, texts).recents
        }
        recents.forEach { (profile, line) ->
            assertTrue("$profile 的最近任务表述为空", line.isNotBlank())
        }
        // ColorOS / OriginOS / MIUI 三种手势必须写成三句不同的话 —— 它们是本页最容易被
        // 「统一成一句话」而失去意义的部分。
        val handGestures = listOf(
            recents.getValue(OemProfile.COLOR_OS),
            recents.getValue(OemProfile.ORIGIN_OS),
            recents.getValue(OemProfile.MIUI_HYPER_OS),
        )
        assertEquals("三种手势被写成同一句话", 3, handGestures.toSet().size)
    }

    @Test
    fun `引导对象带上该档的深链`() {
        OemProfile.entries.forEach { profile ->
            val guide = OemBackgroundGuide.guideOf(profile, zhTexts())
            assertEquals(profile, guide.profile)
            assertEquals(OemBackgroundGuide.linksOf(profile), guide.links)
        }
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    /** 中文文案（与 `zhCN` 同一个对象；取 `settings.backgroundGuide` 组）。 */
    private fun zhTexts() = zhCN.settings.backgroundGuide

    /**
     * 8 种语言逐档断言「恰好三步、每步非空、三步互不相同」。
     *
     * 这条是「文案用 `\n` 分行」这个折中方案的代价守卫：分行由代码做，
     * 那么**漏打一个换行**就只剩一步、**多打一个**就多出一步，编译器一个字都不会说。
     */
    private fun assertThreeSteps(presets: List<LanguagePreset>) {
        presets.forEach { preset ->
            val texts = preset.strings.settings.backgroundGuide
            OemProfile.entries.forEach { profile ->
                val guide = OemBackgroundGuide.guideOf(profile, texts)
                assertEquals(
                    "${preset.code} / $profile 的步骤数",
                    3,
                    guide.steps.size,
                )
                guide.steps.forEachIndexed { index, step ->
                    assertTrue(
                        "${preset.code} / $profile 的第 ${index + 1} 步为空",
                        step.isNotBlank(),
                    )
                }
                assertEquals(
                    "${preset.code} / $profile 的三步里有重复文案",
                    3,
                    guide.steps.toSet().size,
                )
            }
        }
    }
}
