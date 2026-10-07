/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0「设置界面二级菜单重构」阶段 3：`settings/{group}` 路由的单测（纯 JVM）。
 */

package com.takahashirinta.ncrust.settings

import com.takahashirinta.ncrust.ui.navigation.NavRoutes
import com.takahashirinta.ncrust.ui.settings.SettingsGroup
import com.takahashirinta.ncrust.ui.settings.SettingsGroupRoute
import com.takahashirinta.ncrust.ui.settings.SettingsRegistry
import com.takahashirinta.ncrust.ui.settings.SettingsRenderPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二级页路由：7 个合法分组都解析得回来，未知值一律**安全回落**（不抛异常）。
 *
 * 为什么这条要单独测：结构探针 §4.3-4 —— 进程重建 / 版本升级删分组之后，
 * 系统可能把一条对不上的 `settings/xxx` 路由重新喂回来。那时的正确行为是回落到一级页，
 * 而不是崩在设置页里。
 */
class SettingsGroupRouteTest {

    private fun bare(): (String) -> Any? = { _: String -> null }

    @Test
    fun `allSevenGroupIdsResolveBackToTheirGroup`() {
        assertEquals("一级分组就是这 7 个（probe-settings-structure §2）", 7, SettingsGroup.entries.size)
        SettingsGroup.entries.forEach { group ->
            assertSame("settings/${group.id} 解析不回 ${group.name}", group, SettingsGroupRoute.resolve(group.id))
            assertEquals("settings/${group.id}", SettingsGroupRoute.route(group))
            assertEquals(SettingsGroupRoute.route(group), SettingsGroupRoute.route(group.id))
        }
        // allRoutes() 给出的是**完整路由**（settings/<id>），去掉前缀后必须解析回同一个分组
        assertEquals(7, SettingsGroupRoute.allRoutes().size)
        assertEquals(7, SettingsGroupRoute.allRoutes().toSet().size)
        assertTrue(SettingsGroupRoute.allRoutes().all { it.startsWith("settings/") })
        assertEquals(
            SettingsGroup.entries.map { it.id },
            SettingsGroupRoute.allRoutes()
                .map { it.removePrefix("settings/") }
                .map { requireNotNull(SettingsGroupRoute.resolve(it)).id },
        )
    }

    @Test
    fun `routePatternAndArgNameHaveASingleLiteralSource`() {
        // NavRoutes 直接引用 SettingsGroupRoute.PATTERN，两处不是两份字面量
        assertEquals("settings/{group}", SettingsGroupRoute.PATTERN)
        assertEquals(SettingsGroupRoute.PATTERN, NavRoutes.SETTINGS_GROUP)
        assertEquals("group", SettingsGroupRoute.ARG)
        // 模板与函数拼出来的路径必须是同一个形状（`{group}` 只被替换一次）
        assertTrue(NavRoutes.settingsGroup("lyrics").startsWith("settings/"))
        assertEquals(NavRoutes.settingsGroup("lyrics"), SettingsGroupRoute.route("lyrics"))
    }

    @Test
    fun `unknownGroupIdsFallBackWithoutThrowing`() {
        listOf(
            null,                       // 参数缺失（路由被深链/重建后参数丢了）
            "",                         // 空串
            " ",                        // 空白
            "nope",                     // 不存在的分组
            "ACCOUNT",                  // 大小写不符（刻意不做 lowercase 宽容）
            " account",                 // 带前导空格
            "account/",                 // 多一段
            "accounts",                 // 前缀相同的另一个词
            "settings",                 // 把路由当成了 id
            "{group}",                  // 模板本身被当成实参
        ).forEach { raw ->
            assertNull("非法 group 参数必须回落（而不是抛异常）：'$raw'", SettingsGroupRoute.resolve(raw))
        }
    }

    @Test
    fun `secondaryPageCanListEveryEntryOfItsGroup`() {
        SettingsGroup.entries.forEach { group ->
            val all = SettingsRegistry.entriesOf(group.id)
            assertTrue("分组 ${group.id} 在 registry 里是空的", all.isNotEmpty())
            // 二级页拿得到该组的全部条目（registry 侧，含 internal —— 防丢项的那一半）
            assertEquals(SettingsRegistry.allEntries().filter { it.group == group }.map { it.id }, all.map { it.id })
            // 实际渲染的那些：默认取值下 == 计划渲染列表（门控在默认值下不藏任何一行）
            assertEquals(
                SettingsRenderPlan.plannedRowsOf(group.id).map { it.id },
                SettingsRenderPlan.rowsOf(group.id, bare()).map { it.id },
            )
            assertTrue("分组 ${group.id} 的二级页一行都没有", SettingsRenderPlan.rowsOf(group.id, bare()).isNotEmpty())
        }
        // 未知分组：取到空列表，不抛异常
        assertTrue(SettingsRegistry.entriesOf("no_such_group").isEmpty())
        assertTrue(SettingsRenderPlan.rowsOf("no_such_group", bare()).isEmpty())
    }

    @Test
    fun `everyGroupOwnsAtLeastOneRealPreferenceOrActionRow`() {
        SettingsGroup.entries.forEach { group ->
            val rows = SettingsRenderPlan.plannedRowsOf(group.id)
            assertTrue("分组 ${group.id} 没有可渲染的行", rows.isNotEmpty())
            rows.forEach { entry ->
                assertEquals("${entry.id} 的分组与它所在页面不一致", group.id, entry.group.id)
            }
        }
    }
}
