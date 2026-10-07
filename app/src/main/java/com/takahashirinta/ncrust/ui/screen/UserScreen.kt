/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0「设置界面二级菜单重构」阶段 4：设置**一级页**。
 *
 * ## 本文件从「一个大 LazyColumn + 8 个 item 块」变成「页头 + 7 张分组卡片」
 *
 * 迁移前这里平铺着全部 39 行设置项（音质 / 播放 / 歌词 / 外观 / 后台运行 / 背景图 /
 * 存储 / 关于），最长的「播放」块里混着 15 项。现在每一项都搬进了它所属分组的二级页
 * （`ui/screen/SettingsGroupScreen.kt` 与 `ui/screen/SettingsAccountSection.kt`），
 * **同一个提交里**删掉了对应的旧 item 块 —— 不留双轨期（同一个 key 挂两处 = 改一处另一处
 * 不刷新，见结构探针 §5 风险 1）。
 *
 * 卡片顺序 = [SettingsRegistry.groups()]（registry 是渲染顺序的唯一真相），
 * 所以这里没有第二份分组清单。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.components.SettingsCardDivider
import com.takahashirinta.ncrust.ui.components.SettingsGroupCard
import com.takahashirinta.ncrust.ui.components.settingsGroupIcon
import com.takahashirinta.ncrust.ui.components.settingsGroupSubtitle
import com.takahashirinta.ncrust.ui.components.settingsGroupTitle
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.settings.SettingsGroup
import com.takahashirinta.ncrust.ui.settings.SettingsRenderPlan
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 设置一级页：Groove 大字页头 + 7 张分组卡片（图标 + 标题 + 副标题 + 箭头）。
 *
 * [onOpenSettingsGroup] 由 `MainScreen` 接上 `navController.navigate(NavRoutes.settingsGroup(id))`
 * —— 一级页不认识导航库，也不认识二级页需要哪些状态（那些提升在 MainScreen / MainActivity）。
 *
 * 滚动容器继续用 [BottomOverlayInsetDp] 作 `contentPadding`：设置页是 tab 屏、在
 * `PlayerCardOverlay` 之下，有歌在播时底部那条「触摸死带」里的行点不动
 * （AGENTS.md「Compose 触摸陷阱」第 2 条）。
 */
@Composable
fun UserScreen(
    onOpenSettingsGroup: (SettingsGroup) -> Unit = {},
) {
    val strings = LocalStrings.current

    // 宽屏设置内容居中限宽（上限 720dp），避免设置行横跨平板。
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxHeight(),
            contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
            flingBehavior = rememberMetroFlingBehavior()
        ) {
            item(key = "user.header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
                ) {
                    MetroText(
                        strings.tabUser,
                        color = LocalMetroColors.current.onBackground,
                        style = LocalMetroTypography.current.pageHeading,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // 分组顺序 = registry 的枚举声明顺序（一级页卡片列表 == SettingsRenderPlan.cardGroups()，
            // 由 SettingsRenderPlanTest 断言）。
            SettingsRenderPlan.cardGroups().forEachIndexed { index, group ->
                item(key = "settings.group.${group.id}") {
                    if (index > 0) SettingsCardDivider()
                    SettingsGroupCard(
                        icon = settingsGroupIcon(group.iconName),
                        title = settingsGroupTitle(strings, group),
                        subtitle = settingsGroupSubtitle(strings, group),
                        onClick = { onOpenSettingsGroup(group) },
                    )
                }
            }
        }
    }
}
