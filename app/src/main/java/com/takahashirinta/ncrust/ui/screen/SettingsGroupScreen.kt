/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0「设置界面二级菜单重构」阶段 3：二级页骨架（路由 `settings/{group}` 的落点）。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.settingsGroupSubtitle
import com.takahashirinta.ncrust.ui.components.settingsGroupTitle
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.settings.SettingsGroup
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 设置二级页（一级分组卡片点进来的详情页）。
 *
 * ## 为什么是导航页而不是「设置页内部状态 + BackHandler」（结构探针 §4.2 方案①）
 *
 *  - 返回栈正确性交给导航库：系统返回、手势返回、进程重建后恢复都对；
 *  - 转场复用 `pageTransitionEnabled`（`NavGraph.kt` 的四个 lambda 是
 *    `AppMotion.pageTransitionSpec()` 的**唯一调用点**，再开一处就破坏了这个唯一性）；
 *  - 骨架直接用 [DetailScaffold]：返回箭头 / opaque 背景 / `BottomOverlayInsetDp` 都是现成的。
 *
 * ## 骨架期（阶段 3）与内容期（阶段 4）的边界
 *
 * 阶段 3 只落**骨架 + 路由**，一级页仍是旧的平铺 `UserScreen` ⇒ 产物行为不变、可单独 revert。
 * 阶段 4 才把各分组的内容铺进来，并**同一个提交里**删掉 `UserScreen` 对应的旧 item 块
 * （禁止「先加后删」的双轨期：同一个 key 挂在两处 = 改一处另一处不刷新）。
 *
 * ⚠️ 本页在 `NavHost` 里组合，所以**不能**在这里调 `viewModel()`：`LocalViewModelStoreOwner`
 * 在 NavHost 目的地里是 NavBackStackEntry，`viewModel()` 会造出**第二个** `PlayerViewModel`
 * （Activity 作用域那个才是播放器在用的），歌词/音质开关就会写到一个没人听的实例上。
 * 所以 PlayerViewModel 由 `MainScreen` 显式传进来。
 */
@Composable
fun SettingsGroupScreen(
    group: SettingsGroup,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current

    DetailScaffold(
        title = settingsGroupTitle(strings, group),
        onBack = onBack,
        header = { SettingsGroupHeader(group) },
    ) {
        // 阶段 4 在此按 `SettingsRenderPlan.rowsOf(group.id, read)` 铺各分组的行。
    }
}

/**
 * 二级页页头：大标题 + 副标题。
 *
 * `statusBarsPadding() + top = 56.dp`：顶部 48dp 是返回箭头的命中区
 * （`DetailScaffold` 的 `TopScrimIconButton` 浮在内容之上），留够才不会被箭头压住标题。
 */
@Composable
private fun SettingsGroupHeader(group: SettingsGroup) {
    val strings = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 8.dp)
    ) {
        MetroText(
            settingsGroupTitle(strings, group),
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.pageHeading,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        MetroText(
            settingsGroupSubtitle(strings, group),
            color = LocalMetroColors.current.onSurfaceVariant,
            style = LocalMetroTypography.current.caption,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
