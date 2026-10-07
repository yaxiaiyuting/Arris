/*
 * Ncrust —— ncm 第三方客户端
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
    fun `levelOneCardListMatchesRegistryGroupsExactly`() {
        // 一级页（UserScreen）渲染的就是 cardGroups()，这里断言数量与顺序
        val cards = SettingsRenderPlan.cardGroups()
        assertEquals(SettingsRegistry.groups(), cards)
        assertEquals("一级页必须是 7 张卡片", 7, cards.size)
        assertEquals(
            listOf("account", "general", "appearance", "playback", "lyrics", "storage", "about"),
            cards.map { it.id },
        )
        assertTrue("每个分组都必须有可渲染的行", cards.all { SettingsRenderPlan.plannedRowsOf(it.id).isNotEmpty() })
    }

    @Test
    fun `renderedRowCountPerGroupIsPinnedAndNothingIsLost`() {
        // 逐组钉住行数：任何「顺手多渲染一行 / 少渲染一行」都会在这里变红。
        val counts = SettingsRenderPlan.cardGroups().associate { group ->
            group.id to SettingsRenderPlan.plannedRowsOf(group.id).size
        }
        assertEquals(
            mapOf(
                // v3.2.0：2 → 3（B 站账号块，P1）。三块共用同一个登录框架的不同通道。
                "account" to 3,      // ncm 账号块 + qm 账号块 + B 站账号块
                // v3.1.0：+1（B 站音源开关）⇒ 5。它是**内容源开关**，不属于账号页。
                "general" to 5,      // 语言 / 自动旋转 / 音乐人推荐 / 后台运行 / B 站音源
                // v3.0.0：动效/波形相关的 8 个可见项从「播放与音质」搬到「外观与动效」
                // （它们控制的是画面，不是音质）：音频可视化 + 动效强度 + 界面动效总开关
                // + 五个独立开关 ⇒ 5 + 8 = 13。
                // v3.2.0：+4（界面律动总闸 + 封面浮动 / 歌词律动 / 控制条脉冲）⇒ 17。
                "appearance" to 17,   // 主题模式 / 主题色 / 主题色来源 / 页面切换动效 / 自定义背景
                // v2.9.0：v2.8.0 的 6 个波形项降级为迁移源（不渲染），换成统一动效强度 2 项
                // ⇒ 11 − 6 + 2 = 7。逐项：音质×2 / 无缝 / 禁止熄屏 / 可视化 / 动效强度 / 界面动效。
                // v3.0.0：动效/波形项搬走之后，这里只剩「音质 + 播放行为」四项。
                // v3.4.8：+2（B 站音质上限 / 优先无损 FLAC）⇒ 6。它们调的是**音质**，
                // 与 wifi_quality / mobile_quality 同类，所以在同一页而不是「通用」。
                "playback" to 6,
                // v3.4.8：+1（B 站字幕语言）⇒ 10。它与既有的「歌词翻译」相邻 ——
                // 两者回答同一个问题（要哪个语言的歌词），只是各音源能给的东西不同。
                "lyrics" to 10,       // 翻译 / 逐字 / 渐变质量 / 字号 / 媒体面板 / TTML×2 / 音译 / 动态字号
                "storage" to 3,      // 离线缓存上限 / 清除缓存 / 离线缓存管理
                "about" to 1,        // 关于
            ),
            counts,
        )
        // v2.9.0：35 → 31（减 6 个降级为迁移源的波形项、加 2 个统一动效项）。
        // v3.0.0：31 → 36（加 5 个「每个动效独立开关」，铁律 26）。
        // v3.1.0：36 → 37（B 站音源开关：一个**可见**的新开关，不是内部项）。
        // v3.2.0：37 → 42（界面律动那一层的闸 4 项 + B 站账号块 1 项，都是可见项）。
        // v3.4.8：42 → 45（B 站音质上限 / 优先无损 FLAC / 字幕语言，三条都是可见项）。
        assertEquals(45, counts.values.sum())
        // 72 条 registry 条目 = 26 条内部项（从来不渲染） + 4 条库页承载 + 42 条二级页渲染
        //
        // v2.9.0 的内部项从 16 涨到 25：+7 是 v2.8.0 的波形键（降级为迁移源，含此前漏枚举的
        // visualizer_auto_downgraded），
        // 另 +3 是 v2.9.0 的三个派生键（降级水位 / 降级日志 / 迁移水位）。
        // 条目总数 55 → 61 = 新增的 5 个动效键 + 补枚举的 1 个 v2.8.0 漏项。
        // v3.1.0：66 → 67（bilibili_enabled）。
        // v3.2.0：67 → 72（界面律动那一层的 4 个键 + B 站账号块 1 项）。
        // v3.4.8：72 → 75（三项 B 站专属设置：音质上限 / 优先无损 FLAC / 字幕语言）。
        assertEquals(75, SettingsRegistry.allEntries().size)
        // 内部项仍然是 26（v3.0.0 的 5 个动效键与 v3.1.0 的 B 站开关**都是可见开关**）。
        assertEquals(26, SettingsRegistry.allEntries().count { it.isInternal })
        assertEquals(4, SettingsRenderPlan.HOSTED_ELSEWHERE.size)
        assertEquals(
            "内部项 + 库页承载 + 二级页渲染必须等于全部条目（不丢项）",
            SettingsRegistry.allEntries().size,
            26 + 4 + counts.values.sum(),
        )
    }

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
        // v2.9.0：文案来源**收敛成一处** —— registry 的 `titleKey`。
        // v2.8.0 时那 6 个波形项没有 titleKey（文案由并行的 i18n 任务落在 `WaveformStrings`），
        // 所以当时这条断言写的是「没有文案的渲染行恰好是那 6 个」。v2.9.0 把它们全部降级为
        // 迁移源（不渲染），接替的 `motion_tier` / `ui_motion_enabled` **带** titleKey，
        // 于是"没有文案的渲染行"变成空集 —— 这比白名单更强：**任何**新增的渲染行都必须自带文案。
        val titleLess = SettingsRenderPlan.allPlannedRows()
            .filter { it.titleKey.isNullOrBlank() }
            .map { it.id }
            .toSet()
        assertEquals(
            "二级页渲染的每一行都必须有 titleKey（v2.9.0 起文案来源只有 registry 一处）",
            emptySet<String>(),
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
