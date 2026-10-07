/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.4 · D：音频可视化条挂载判据的 A-B 矩阵单测（纯逻辑）。
 */

package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PlayerLayout.visualizerSlot` 的六格 A/B 矩阵 + 高度算式。
 *
 * ## 为什么这组用例必须存在
 *
 * 这个 bug 的形状是「**某一格**没有挂载」，而 bug 报告只会描述那一格。
 * 修好它之后，回归的方式同样是「某一格悄悄变了」—— 例如有人把
 * `isLargeScreen` 换成 `isWidePlayer`（手机横屏也会长出波浪条），
 * 或者把 `orientationLandscape` 去掉（平板竖屏也长出来）。
 * 六个组合逐个钉住，才能让「不回归」变成可执行断言。
 *
 * 设备取值（真机实测，见 `docs/verification/v2.5.4/probe-waveform-tablet.md`）：
 *  - 手机 S6：`smallestScreenWidthDp = 360`（竖屏 360×640dp）
 *  - 手机 PCL110 横屏：`screenWidthDp = 800`，但 `smallestScreenWidthDp` 仍 < 600
 *  - 平板 WGR-W09：`smallestScreenWidthDp = 800`（竖屏 800×1280dp / 横屏 1280×768dp）
 */
class PlayerLayoutVisualizerTest {

    private fun slot(
        enabled: Boolean = true,
        bigScreenActive: Boolean = false,
        isWidePlayer: Boolean = false,
        isLargeScreen: Boolean = false,
        orientationLandscape: Boolean = false,
    ) = PlayerLayout.visualizerSlot(enabled, bigScreenActive, isWidePlayer, isLargeScreen, orientationLandscape)

    // ---------------------------------------------------------- 六格矩阵 ----

    @Test
    fun `手机竖屏不挂载（v1_8_0 起就没有，不回归）`() {
        assertFalse(slot(bigScreenActive = false, isWidePlayer = false, isLargeScreen = false, orientationLandscape = false))
    }

    @Test
    fun `手机横屏进大屏模式时挂载（走 bigScreenActive，不回归）`() {
        assertTrue(slot(bigScreenActive = true, isWidePlayer = true, isLargeScreen = false, orientationLandscape = true))
    }

    @Test
    fun `手机横屏不进大屏模式时不挂载（与 v1_8_0 一致）`() {
        // 关键回归点：这里 isWidePlayer = true（横屏窗口宽 800dp），
        // 若把 isLargeScreen 误写成 isWidePlayer，这一格会变绿 ⇒ 手机横屏多出一条波浪条。
        assertFalse(slot(bigScreenActive = false, isWidePlayer = true, isLargeScreen = false, orientationLandscape = true))
    }

    @Test
    fun `平板竖屏不挂载（与 v1_8_0 一致）`() {
        // 关键回归点：若把 orientationLandscape 从判据里去掉，这一格会变绿。
        assertFalse(slot(bigScreenActive = false, isWidePlayer = true, isLargeScreen = true, orientationLandscape = false))
    }

    @Test
    fun `平板横屏挂载（本版修的就是这一格）`() {
        assertTrue(slot(bigScreenActive = false, isWidePlayer = true, isLargeScreen = true, orientationLandscape = true))
    }

    @Test
    fun `平板横屏进大屏模式仍然挂载（走 bigScreenActive）`() {
        assertTrue(slot(bigScreenActive = true, isWidePlayer = true, isLargeScreen = true, orientationLandscape = true))
    }

    // ------------------------------------------------------------ 开关 ----

    @Test
    fun `用户关掉开关时任何形态都不挂载`() {
        for (big in listOf(false, true)) {
            for (wide in listOf(false, true)) {
                for (large in listOf(false, true)) {
                    for (land in listOf(false, true)) {
                        assertFalse(
                            "关掉开关后不该有任何挂载组合：big=$big wide=$wide large=$large land=$land",
                            slot(enabled = false, bigScreenActive = big, isWidePlayer = wide, isLargeScreen = large, orientationLandscape = land)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `挂载组合的精确集合（大屏模式短路 + 平板横屏那一格）`() {
        // `bigScreenActive` 在生产里蕴含两件事：
        //  ① `orientationLandscape == true`（`isBigScreenActive = requested && orientationLandscape`）；
        //  ② `isWidePlayer == true`（横屏窗口宽必然 >= 600dp）。
        // 判据里它是**短路**的那个分支（`bigScreenActive || (...)`），
        // 所以它一旦为真，`isWidePlayer` / `isLargeScreen` 取什么都不影响结果。
        //
        // 枚举按「物理可达」过滤：big=true 时只取 land=true 且 wide=true。
        val mounted = sortedSetOf<String>()
        for (big in listOf(false, true)) {
            for (wide in listOf(false, true)) {
                for (large in listOf(false, true)) {
                    for (land in listOf(false, true)) {
                        if (big && (!land || !wide)) continue // 不可达
                        if (slot(bigScreenActive = big, isWidePlayer = wide, isLargeScreen = large, orientationLandscape = land)) {
                            mounted.add("big=$big,wide=$wide,large=$large,land=$land")
                        }
                    }
                }
            }
        }
        assertEquals(
            "可达组合里该挂载的恰好这 5 个（大屏模式 2 个 + 平板横屏 1 个 + 大屏模式在平板上的 2 个）",
            sortedSetOf(
                "big=true,wide=true,large=false,land=true",
                "big=true,wide=true,large=true,land=true",
                "big=false,wide=true,large=true,land=true",
            ).let { expected ->
                // 大屏模式那一支是短路的：large 取两个值都挂载，所以两两展开成 4 条。
                expected + setOf(
                    "big=true,wide=true,large=false,land=true",
                    "big=true,wide=true,large=true,land=true",
                )
            },
            mounted,
        )
    }

    @Test
    fun `大屏模式为真时其余三个入参都不影响结果（短路语义）`() {
        for (wide in listOf(false, true)) {
            for (large in listOf(false, true)) {
                for (land in listOf(false, true)) {
                    assertTrue(
                        "大屏模式一旦生效就该挂载：wide=$wide large=$large land=$land",
                        slot(bigScreenActive = true, isWidePlayer = wide, isLargeScreen = large, orientationLandscape = land)
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ 高度 ----

    @Test
    fun `手机横屏拿到约 40dp_平板横屏夹到 56dp`() {
        // PCL110 横屏可用高 363dp、WGR-W09 横屏 768dp（都是实测值）。
        assertEquals(39.93f, PlayerLayout.visualizerHeightDp(363f), 0.01f)
        assertEquals(56f, PlayerLayout.visualizerHeightDp(768f), 0.0f)
        // S6 竖屏 640dp 高，但它在竖屏不挂载；算式本身仍应给出 56dp 上界。
        assertEquals(56f, PlayerLayout.visualizerHeightDp(640f), 0.0f)
    }

    @Test
    fun `极矮与极高的窗口都被夹住，不会压掉封面或溢出`() {
        assertEquals(PlayerLayout.VISUALIZER_MIN_HEIGHT_DP, PlayerLayout.visualizerHeightDp(0f), 0.0f)
        assertEquals(PlayerLayout.VISUALIZER_MIN_HEIGHT_DP, PlayerLayout.visualizerHeightDp(100f), 0.0f)
        assertEquals(PlayerLayout.VISUALIZER_MAX_HEIGHT_DP, PlayerLayout.visualizerHeightDp(100000f), 0.0f)
    }

    @Test
    fun `宽屏断点与平板断点取值相同但语义不同（不许互相替换）`() {
        assertEquals(600, PlayerLayout.WIDE_BREAKPOINT_DP)
        assertEquals(600, PlayerLayout.LARGE_SCREEN_BREAKPOINT_DP)
    }

    // ================================================================
    // v2.5.5 · E：平板的大屏幕模式入口（⤢）
    // ================================================================

    /**
     * ★ **平板 + 不在大屏** 的两格由无变有（本版新增）。
     *
     * v2.5.4 的探针发现平板上根本没有 ⤢ 入口（它只存在于竖屏控制条变体里，
     * 而平板 `screenWidthDp` 恒 >= 600 ⇒ 走宽屏横向控件条），但没有在同一个提交里改。
     */
    @Test
    fun `平板在两个方向上都有大屏模式入口`() {
        assertTrue(PlayerLayout.bigScreenEntrySlot(isLargeScreen = true, bigScreenActive = false))
    }

    /**
     * ★ **手机横屏一格都不变**（v2.5.4 规则 4：挂载条件必须在全部目标形态上 A/B）。
     *
     * 手机横屏的 `screenWidthDp` 也 >= 600，但它**不是** `isLargeScreen`
     * （`smallestScreenWidthDp` = 360）。拿宽屏谓词当「平板」就会在这里多出一个按钮。
     */
    @Test
    fun `手机横屏不新增大屏模式入口`() {
        assertFalse(PlayerLayout.bigScreenEntrySlot(isLargeScreen = false, bigScreenActive = false))
    }

    /** 已经在横屏大屏里时不出入口 —— 出口在控制条的 `trailing` 槽位，两者不能同时出现。 */
    @Test
    fun `大屏模式生效时不挂载入口`() {
        assertFalse(PlayerLayout.bigScreenEntrySlot(isLargeScreen = true, bigScreenActive = true))
        assertFalse(PlayerLayout.bigScreenEntrySlot(isLargeScreen = false, bigScreenActive = true))
    }

    /** 手机竖屏的 ⤢ 在**另一条**（竖屏）控件里，这里不重复挂第二个。 */
    @Test
    fun `手机竖屏由竖屏控件条承载 这里不挂`() {
        assertFalse(PlayerLayout.bigScreenEntrySlot(isLargeScreen = false, bigScreenActive = false))
    }

    /**
     * 四格矩阵逐格钉住（与 KDoc 里的 A/B 表逐行对应）。
     *
     * 表里只有第一行由「无」变「有」；其余三行与 v2.5.4 逐格相同。
     */
    @Test
    fun `大屏入口的四格矩阵`() {
        val table = listOf(
            // isLargeScreen, bigScreenActive, expected
            Triple(true, false, true),    // 平板·不在大屏  ← 本版新增
            Triple(false, false, false),  // 手机（两种方向）·不在大屏
            Triple(true, true, false),    // 平板·大屏中（出口在 trailing）
            Triple(false, true, false),   // 手机·大屏中（出口在 trailing）
        )
        table.forEach { (large, big, expected) ->
            assertEquals(
                "isLargeScreen=$large bigScreenActive=$big 的挂载判定不符",
                expected,
                PlayerLayout.bigScreenEntrySlot(large, big),
            )
        }
    }

    /**
     * 判据**只依赖两个谓词**：等于「平板 且 不在大屏」。
     *
     * 这一条防的是有人把 `isLargeScreen` 换成 `screenWidthDp >= 600`
     * （那会让手机横屏也长出按钮），与 `visualizerSlot` 的那条误替换用例同源。
     */
    @Test
    fun `大屏入口的判据是平板且不在大屏`() {
        for (large in listOf(true, false)) {
            for (big in listOf(true, false)) {
                assertEquals(large && !big, PlayerLayout.bigScreenEntrySlot(large, big))
            }
        }
    }
}
