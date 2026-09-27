/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0「设置界面二级菜单重构」阶段 3：一级页的分组卡片 + registry 图标名 → ImageVector。
 */

package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.ui.i18n.Strings
import com.takahashirinta.ncrust.ui.settings.SettingsGroup
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 一级页的一张分组卡片：图标 + 标题 + 副标题 + 箭头。
 *
 * ## 形状与动效
 *
 *  - **不施加任何 shape**：Kanesumi 正典是「直角、无圆角」，而 `AppShapes` 是圆角 token 的
 *    唯一落点（`AppShapesSingleSourceTest` 强制）—— 它刻意**没有**「方角」这一档，
 *    因为直角就是「不 clip」。这里沿用 `UserScreen.kt` 里既有三种入口行（关于 / 离线缓存管理 /
 *    后台运行）的写法：`background` + `clickable` + `padding`，零 shape。
 *  - 动效走 [appPressScale]（`AppMotion.PRESS_SCALE` + `AppMotion.pressScale`）：
 *    按下回弹在 `PointerEventPass.Initial` 观察、**不消费**事件，所以它不会抢走
 *    `clickable` 的 tap，也不引入任何重组（缩放写在 `graphicsLayer` 里）。
 *  - 颜色一律取自 [LocalMetroColors]（跟随主题 / 封面取色），没有硬编码色值。
 *
 * ## 触控
 *
 * 整卡可点（`fillMaxWidth` + `vertical = 12.dp` + 两行文字 ⇒ 远高于 48dp），
 * 符合 AGENTS.md「Compose 触摸陷阱」第 7 条的命中区要求。
 */
@Composable
internal fun SettingsGroupCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .appPressScale()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetroIcon(
            icon,
            contentDescription = null,
            tint = LocalMetroColors.current.primary,
            sizeDp = 24.dp,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            MetroText(
                title,
                color = LocalMetroColors.current.onBackground,
                style = LocalMetroTypography.current.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            MetroText(
                subtitle,
                color = LocalMetroColors.current.onSurfaceVariant,
                style = LocalMetroTypography.current.caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        MetroIcon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = LocalMetroColors.current.onSurfaceVariant,
            sizeDp = 20.dp,
        )
    }
}

/**
 * 一级页卡片之间的细分隔线（`divider` 色，左 16dp 缩进 —— 与卡片文字对齐）。
 *
 * 直角风格下没有「卡片间距 + 卡片底色」的层次，靠 1px 分隔线表达「这是 7 张卡片」。
 */
@Composable
internal fun SettingsCardDivider() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(LocalMetroColors.current.divider)
    )
}

/**
 * registry 的 `iconName`（**纯字符串**，registry 刻意不 import Compose）→ `Icons.Filled.*`。
 *
 * 未知名字回落 `Icons.Default.Tune`（= 「通用」的图标）：坏数据最坏是多一个通用图标，
 * 不该让一级页崩 —— 与 `AccentSource.getOrDefault(PRESET)` 同一条纪律。
 * `SettingsGroupIconsTest` 反过来断言 7 个真实分组名都不走这条兜底。
 */
internal fun settingsGroupIcon(iconName: String): ImageVector = when (iconName) {
    "AccountCircle" -> Icons.Default.AccountCircle
    "Tune" -> Icons.Default.Tune
    "Palette" -> Icons.Default.Palette
    "PlayCircle" -> Icons.Default.PlayCircle
    "Lyrics" -> Icons.Default.Lyrics
    "Storage" -> Icons.Default.Storage
    "Info" -> Icons.Default.Info
    else -> Icons.Default.Tune
}

/**
 * 分组标题文案。
 *
 * 为什么这里是一张显式 `when` 而不是「按 `SettingsGroup.titleKey` 反射取」：
 * registry 存的是**属性名**（`"settingsGroupAccountTitle"`），而 Compose 侧必须编译期
 * 拿到真实字符串。7 个分组的映射写成穷尽 `when` ⇒ 以后**新增分组不写文案是编译错误**。
 * 「registry 的 titleKey 与 Strings 属性名逐字一致」这件事由
 * `SettingsRegistryTest.everyTitleKeyResolvesToARealStringsAccessorPath` 用 i18n 快照
 * （`StringsSnapshot`）单独钉住，两条防线互相独立。
 */
internal fun settingsGroupTitle(strings: Strings, group: SettingsGroup): String = when (group) {
    SettingsGroup.ACCOUNT -> strings.settings.settingsGroupAccountTitle
    SettingsGroup.GENERAL -> strings.settings.settingsGroupGeneralTitle
    SettingsGroup.APPEARANCE -> strings.settings.settingsGroupAppearanceTitle
    SettingsGroup.PLAYBACK -> strings.settings.settingsGroupPlaybackTitle
    SettingsGroup.LYRICS -> strings.settings.settingsGroupLyricsTitle
    SettingsGroup.STORAGE -> strings.settings.settingsGroupStorageTitle
    SettingsGroup.ABOUT -> strings.settings.settingsGroupAboutTitle
}

/** 分组副标题文案（同 [settingsGroupTitle] 的纪律）。 */
internal fun settingsGroupSubtitle(strings: Strings, group: SettingsGroup): String = when (group) {
    SettingsGroup.ACCOUNT -> strings.settings.settingsGroupAccountSubtitle
    SettingsGroup.GENERAL -> strings.settings.settingsGroupGeneralSubtitle
    SettingsGroup.APPEARANCE -> strings.settings.settingsGroupAppearanceSubtitle
    SettingsGroup.PLAYBACK -> strings.settings.settingsGroupPlaybackSubtitle
    SettingsGroup.LYRICS -> strings.settings.settingsGroupLyricsSubtitle
    SettingsGroup.STORAGE -> strings.settings.settingsGroupStorageSubtitle
    SettingsGroup.ABOUT -> strings.settings.settingsGroupAboutSubtitle
}
