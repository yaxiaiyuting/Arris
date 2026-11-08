/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0「设置界面二级菜单重构」阶段 3：一级页的分组卡片 + registry 图标名 → ImageVector。
 */

package com.takahashirinta.ncrust.ui.components

import com.takahashirinta.ncrust.ui.theme.AppShapes
import com.takahashirinta.ncrust.ui.theme.AppMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.ui.i18n.Strings
import com.takahashirinta.ncrust.ui.settings.SettingsGroup
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/** 设置入口共用薄表面与低对比图标底座；按压只在绘制层轻收，不改变整卡命中区。 */
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
            .padding(horizontal = 16.dp)
            .appPressScale(pressedScale = AppMotion.BROWSE_PRESS_SCALE)
            .arrisSurface()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp)
                .background(LocalMetroColors.current.primary.copy(alpha = 0.07f), AppShapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            MetroIcon(icon, contentDescription = null, tint = LocalMetroColors.current.primary, sizeDp = 22.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            MetroText(
                title,
                color = LocalMetroColors.current.onBackground,
                style = LocalMetroTypography.current.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
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
            tint = LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.65f),
            sizeDp = 18.dp,
        )
    }
}

/** 卡片之间保留短而均匀的留白。 */
@Composable
internal fun SettingsCardDivider() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
    )
}


/**
 * 设置分组里的一行（**不自带表面**）。
 *
 * 与 [SettingsGroupCard] 的区别只有一处：它不画卡片，由调用方把所有行放进**同一张**表面里，
 * 行间用 hairline 分隔。参考的是 Linear / Notion 这类"分组列表"的做法 ——
 * 一行一张卡片会让 130% 字号下首屏只剩 5 行，而且"卡片即页面结构"是最容易显得偷懒的排版。
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    val c = LocalMetroColors.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).background(c.primary.copy(alpha = 0.07f), AppShapes.small),
                contentAlignment = Alignment.Center,
            ) {
                MetroIcon(icon, contentDescription = null, tint = c.primary, sizeDp = 20.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                MetroText(
                    title,
                    color = c.onBackground,
                    style = LocalMetroTypography.current.titleMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    MetroText(
                        subtitle,
                        color = c.onSurfaceVariant,
                        style = LocalMetroTypography.current.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MetroIcon(
                Icons.Default.ChevronRight, contentDescription = null,
                tint = c.onSurfaceVariant.copy(alpha = .7f), sizeDp = 18.dp,
            )
        }
        if (showDivider) {
            Box(
                Modifier.fillMaxWidth().padding(start = 66.dp, end = 16.dp).height(.7.dp)
                    .background(c.onBackground.copy(alpha = .07f)),
            )
        }
    }
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
