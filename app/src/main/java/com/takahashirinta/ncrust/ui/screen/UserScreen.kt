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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.account.SessionMonitor
import com.takahashirinta.ncrust.account.SessionState
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.components.SessionExpiredBanner
import com.takahashirinta.ncrust.ui.components.SettingsCardDivider
import com.takahashirinta.ncrust.ui.components.UpdateAvailableBanner
import com.takahashirinta.ncrust.ui.components.SettingsGroupCard
import com.takahashirinta.ncrust.ui.components.settingsGroupIcon
import com.takahashirinta.ncrust.ui.components.settingsGroupSubtitle
import com.takahashirinta.ncrust.ui.components.settingsGroupTitle
import com.takahashirinta.ncrust.ui.components.vipStatusText
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.settings.SettingsGroup
import com.takahashirinta.ncrust.ui.settings.SettingsRenderPlan
import com.takahashirinta.ncrust.update.UpdateService
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
    // 会话失效横幅点击 → 走现有登录流程（窄屏网页登录 / 宽屏扫码）。
    onRelogin: () -> Unit = {},
    // v3.4.13：用户页顶部「发现新版本」横幅 → 更新页。
    onOpenUpdate: () -> Unit = {},
) {
    val strings = LocalStrings.current
    // 会话/VIP 状态：会员文案只在这里显示（接口失败显示为未知，绝不显示「未开通」）。
    // Arris 的 UserScreen 是设置一级页，账号详情在 SettingsAccountSection；这里先给
    // 一级页一个全局可见的状态行，账号二级页的展示由主 agent 按同一份 vipStatusText 接线。
    val sessionState by SessionMonitor.state.collectAsState()
    val vipText = vipStatusText(strings, sessionState)
    // 更新状态在本组件自己的作用域订阅，不牵动 MainScreen 的重组。
    val updateState by UpdateService.state.collectAsState()

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

            // v3.4.13：发现新版本时，在页头下加一行「发现新版本 X · 查看」。
            // 不弹窗、不发通知 —— 用户自己打开设置页才看得到；「稍后」过的版本由
            // UpdateState.shouldNotify 过滤。
            if (updateState.shouldNotify) {
                item(key = "user.update.banner") {
                    UpdateAvailableBanner(
                        version = updateState.available?.version?.toString().orEmpty(),
                        onOpen = onOpenUpdate
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (sessionState is SessionState.Expired) {
                item(key = "user.session_expired") {
                    SessionExpiredBanner(onRelogin = onRelogin)
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (vipText != null) {
                item(key = "user.vip_status") {
                    MetroText(
                        vipText,
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
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
