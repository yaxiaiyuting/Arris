/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0「设置界面二级菜单重构」阶段 3：二级页渲染计划的单测（纯 JVM）。
 */

package com.takahashirinta.ncrust.settings

import com.takahashirinta.ncrust.ui.i18n.StringsSnapshot
import com.takahashirinta.ncrust.ui.i18n.zhCN
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerStrings
import com.takahashirinta.ncrust.ui.settings.SettingsEntryType
import com.takahashirinta.ncrust.ui.settings.SettingsRegistry
import com.takahashirinta.ncrust.ui.settings.SettingsRenderPlan
import com.takahashirinta.ncrust.ui.settings.SettingsRowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「每个设置项都能从一级页 → 二级页到达」的**机械证明**。
 *
 * 这是本版最大风险的防线（结构探针 §5 风险 1：迁移期丢项 / 同一个 key 挂两处）：
 *
 *  - [everyNonInternalEntryIsMappedExactlyOnce] —— 双向等值：非 internal 条目**全集**
 *    == 二级页渲染 ∪ 库页承载，且两边不相交 ⇒ 既不丢项、也不重复；
 *  - [everyPlannedRowStaysInItsOwnGroupAndInRegistryOrder] —— 计划列表就是
 *    registry 在该组的声明顺序（二级页的 `LazyColumn` 按它铺，所以顺序也是同一条真相）；
 *  - [gatedRowsAreVisibleByDefaultAndOnlyTtmlFirstCanDisappear] —— 默认取值下
 *    「计划」=「实际挂载」；唯一会整行消失的是 `lyrics_ttml_first`（硬依赖，既有语义）。
 */
class SettingsRenderPlanTest {

    /** 键全部缺失 ⇒ 门控一律走 registry 里声明的期望默认值。 */
    private fun bare(): (String) -> Any? = { _: String -> null }

    // ── 1. 恰好一次（不丢项 / 不重复）────────────────────────────────────────────────

    @Test
    fun `everyNonInternalEntryIsMappedExactlyOnce`() {
        val planned = SettingsRegistry.groups().flatMap { SettingsRenderPlan.plannedRowsOf(it.id) }
        val plannedIds = planned.map { it.id }

        assertEquals("同一个条目不允许被两张二级页渲染：$plannedIds", plannedIds.size, plannedIds.toSet().size)
        assertEquals(
            "非 internal 条目全集必须 == 二级页渲染 ∪ 库页承载（双向等值）",
            SettingsRenderPlan.plannedEntryIds(),
            plannedIds.toSet() + SettingsRenderPlan.HOSTED_ELSEWHERE,
        )
        assertTrue(
            "库页承载的 4 项不得同时出现在二级页",
            (plannedIds.toSet() intersect SettingsRenderPlan.HOSTED_ELSEWHERE).isEmpty(),
        )
    }

    @Test
    fun `everyPlannedRowStaysInItsOwnGroupAndInRegistryOrder`() {
        SettingsRegistry.groups().forEach { group ->
            val expected = SettingsRegistry.entriesOf(group.id)
                .filter { SettingsRenderPlan.isRenderedOnGroupPage(it) }
            assertEquals(
                "分组 ${group.id} 的渲染计划必须 = registry 声明顺序过滤后",
                expected.map { it.id },
                SettingsRenderPlan.plannedRowsOf(group.id).map { it.id },
            )
            SettingsRenderPlan.plannedRowsOf(group.id).forEach { entry ->
                assertSame("条目 ${entry.id} 跑到别的分组去了", group, entry.group)
            }
        }
    }

    @Test
    fun `plannedRowsAreNeverInternalAndAlwaysCarryATitle`() {
        SettingsRenderPlan.allPlannedRows().forEach { entry ->
            assertTrue("内部项不得进入渲染计划：${entry.id}", !entry.isInternal)
        }
        // 文案来源有两处（都必须在测试里留痕）：
        //  ① registry 的 titleKey（绝大多数既有项）；
        //  ② `Strings.waveform.*`（v2.8.0 的 6 个波形项 —— 它们的文案由并行的 i18n 任务
        //     落在 `WaveformStrings`，registry 里 titleKey 仍为 null，
        //     `SettingsRegistryTest.titleLessEntriesAreExactlyTheDocumentedOnes` 就是这么钉的）。
        // 所以这里断言「没有文案的渲染行**恰好**是那 6 个」，而不是「全都有文案」。
        val titleLess = SettingsRenderPlan.allPlannedRows()
            .filter { it.titleKey.isNullOrBlank() }
            .map { it.id }
            .toSet()
        assertEquals(
            "二级页渲染但没有 titleKey 的项必须恰好是这 6 个波形项（其余任何一条都要补 titleKey）",
            setOf(
                "visualizer_tier",
                "visualizer_showcase",
                "visualizer_shockwave",
                "visualizer_particles",
                "visualizer_perspective",
                "visualizer_drag",
            ),
            titleLess,
        )
        // 全量 = 各组之和（不丢一条）
        assertEquals(
            SettingsRenderPlan.allPlannedRows().size,
            SettingsRegistry.groups().sumOf { SettingsRenderPlan.plannedRowsOf(it.id).size },
        )
    }

    /**
     * 6 个波形项的文案路径必须在 i18n 的 `Strings` 上真实可达。
     *
     * 它们不是 registry 的 `titleKey`（registry 刻意不 import i18n），而是 UI 层按 id 取
     * `strings.waveform.*`。用仓库既有的 i18n 快照工具（`StringsSnapshot`）反射采集全部
     * 可达路径 —— 与 `SettingsRegistryTest.everyTitleKeyResolvesToARealStringsAccessorPath`
     * 同一把尺子，只是走的是 `waveform.` 前缀。
     */
    @Test
    fun `theSixWaveformRowsResolveTheirLabelsFromWaveformStrings`() {
        val paths = StringsSnapshot.capture(zhCN).keys
        val missing = VisualizerStrings.Property.ALL.filterNot { paths.contains("waveform.$it") }
        assertTrue("这些波形文案路径在 Strings.waveform 上不存在：$missing", missing.isEmpty())
    }

    @Test
    fun `rowKindOfIsTotalAndInternalEntriesNeverRender`() {
        SettingsRegistry.allEntries().forEach { entry ->
            val kind = SettingsRenderPlan.rowKindOf(entry)
            if (entry.isInternal) {
                assertEquals("内部项必须映射到 INTERNAL：${entry.id}", SettingsRowKind.INTERNAL, kind)
            } else {
                assertTrue("非内部项不得回落 INTERNAL：${entry.id}", kind != SettingsRowKind.INTERNAL)
            }
            // ACTION 行要么是专用块（账号/缓存/离线/关于/后台），要么是 ACTION_JUMP
            if (entry.type == SettingsEntryType.ACTION) {
                assertTrue(
                    "行为行 ${entry.id} 的控件形态不该是 $kind",
                    kind != SettingsRowKind.SWITCH && kind != SettingsRowKind.DROPDOWN,
                )
            }
        }
    }

    @Test
    fun `hostedElsewhereIsExactlyTheFourLibraryDisplayPreferences`() {
        assertEquals(
            setOf(
                "library_playlist_layout",
                "library_section_collapsed_local",
                "library_section_collapsed_netease",
                "library_section_collapsed_qq",
            ),
            SettingsRenderPlan.HOSTED_ELSEWHERE,
        )
        SettingsRenderPlan.HOSTED_ELSEWHERE.forEach { id ->
            assertEquals(SettingsRenderPlan.HOST_LIBRARY, SettingsRenderPlan.hostOf(id))
            assertEquals(
                "库页承载项必须映射到 HOSTED_ELSEWHERE：$id",
                SettingsRowKind.HOSTED_ELSEWHERE,
                SettingsRenderPlan.rowKindOf(SettingsRegistry.entryById(id)!!),
            )
        }
        assertNull(SettingsRenderPlan.hostOf("wifi_quality"))
    }

    // ── 2. 门控：默认取值下「计划」=「实际挂载」──────────────────────────────────────

    @Test
    fun `gatedRowsAreVisibleByDefaultAndOnlyTtmlFirstCanDisappear`() {
        SettingsRegistry.groups().forEach { group ->
            assertEquals(
                "默认取值下不应有任何行被门控藏起来（${group.id}）",
                SettingsRenderPlan.plannedRowsOf(group.id).map { it.id },
                SettingsRenderPlan.rowsOf(group.id, bare()).map { it.id },
            )
        }

        // lyrics_ttml_enabled = false ⇒ 歌词页少且只少 lyrics_ttml_first（既有硬依赖语义）
        val ttmlOff: (String) -> Any? = { key: String ->
            if (key == "lyrics_ttml_enabled") false else null
        }
        assertEquals(
            SettingsRenderPlan.plannedRowsOf("lyrics").map { it.id } - setOf("lyrics_ttml_first"),
            SettingsRenderPlan.rowsOf("lyrics", ttmlOff).map { it.id },
        )
    }

    @Test
    fun `rowsOfNeverReturnsSomethingOutsideItsGroup`() {
        SettingsRegistry.groups().forEach { group ->
            SettingsRenderPlan.rowsOf(group.id, bare()).forEach { entry ->
                assertEquals("${entry.id} 不该出现在 ${group.id} 的二级页", group.id, entry.group.id)
            }
        }
        // 未知分组 = 空列表（而不是抛异常）
        assertTrue(SettingsRenderPlan.rowsOf("no_such_group", bare()).isEmpty())
        assertTrue(SettingsRenderPlan.plannedRowsOf("no_such_group").isEmpty())
    }
}
