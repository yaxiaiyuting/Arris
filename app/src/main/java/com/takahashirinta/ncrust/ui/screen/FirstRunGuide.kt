/*
 * Copyright (c) 2026 yaxiaiyuting.
 * Arris fork contribution, licensed under GPLv3. See LICENSE.
 *
 * 从主线 feat/4.0.0（8293c64）回移：可跳过的首次设置引导。
 *
 * ## 与主线的差异（LTS 适配）
 *
 * 主线用的是 4.0.0 的 `ArrisFullScreenDialog` / `SettingsRow` / `Modifier.arrisSurface`，
 * 这三个在 LTS 线不存在。这里改用 LTS 既有的等价物，行为不变：
 *  - 全屏层 → `androidx.compose.ui.window.Dialog(usePlatformDefaultWidth = false)`
 *    + `LocalNcrustColors.background`（与 `OfflineCacheOverlay` 同一套写法）；
 *  - 设置入口行 → `SettingsGroupCard`（一级页那张卡片，图标 / 标题 / 副标题 / 箭头）；
 *  - 卡片容器 → 不再套一层 surface：LTS 的卡片自己带 `MetroColors.surface` 底色。
 */

package com.takahashirinta.ncrust.ui.screen

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.takahashirinta.ncrust.R
import com.takahashirinta.ncrust.ui.components.ArrisButton
import com.takahashirinta.ncrust.ui.components.SettingsGroupCard
import com.takahashirinta.ncrust.ui.components.settingsGroupIcon
import com.takahashirinta.ncrust.ui.components.settingsGroupTitle
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.i18n.currentLanguageCode
import com.takahashirinta.ncrust.ui.i18n.firstRunStringsForCode
import com.takahashirinta.ncrust.ui.settings.SettingsGroup
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText

private const val PREFS_SETUP = "arris_setup"
private const val GUIDE_COMPLETED = "guide_completed"
private val guideGroups = listOf(SettingsGroup.ACCOUNT, SettingsGroup.PLAYBACK, SettingsGroup.APPEARANCE)

/**
 * 这一台设备**是否需要**首次引导。
 *
 * 判据是 `firstInstallTime == lastUpdateTime`：只有「新装、从未更新过」的盘才算首次安装，
 * 升级安装一律跳过（老用户不该被一个设置向导拦在门口）。
 *
 * 判定结果会**立刻落盘**（`guide_completed = !pending`）：即使引导没做完就关掉 App、
 * 期间又装了一次更新，这张盘已经不再满足 `firstInstall == lastUpdate`，
 * 靠落盘的那一位才能让「没做完的引导」继续出现 —— 否则它会静默消失。
 */
@Suppress("DEPRECATION")
internal fun firstRunGuidePending(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_SETUP, Context.MODE_PRIVATE)
    if (prefs.contains(GUIDE_COMPLETED)) return !prefs.getBoolean(GUIDE_COMPLETED, false)
    val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
    val pending = pkg.firstInstallTime == pkg.lastUpdateTime
    prefs.edit().putBoolean(GUIDE_COMPLETED, !pending).apply()
    return pending
}

/** 用户点了「开始听歌」或「稍后设置」：两条路的终点是同一位，都算完成。 */
internal fun completeFirstRunGuide(context: Context) {
    context.getSharedPreferences(PREFS_SETUP, Context.MODE_PRIVATE)
        .edit().putBoolean(GUIDE_COMPLETED, true).apply()
}

@Composable
internal fun FirstRunGuide(onOpen: (SettingsGroup) -> Unit, onFinish: () -> Unit) {
    val strings = LocalStrings.current
    val copy = firstRunStringsForCode(currentLanguageCode())
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    Dialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalNcrustColors.current.background)
                .statusBarsPadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(painterResource(R.drawable.tux_listener), null, Modifier.size(72.dp))
                    MetroText(copy.title, color = colors.onBackground, style = typography.headlineMedium)
                    MetroText(copy.summary, color = colors.onSurfaceVariant, style = typography.bodyMedium)
                }
                // 三个设置入口复用一级页的卡片：图标取自 registry，标题走同一份 settingsGroupTitle，
                // 所以引导里看到的「账号 / 播放 / 外观」与设置页完全一致。
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    guideGroups.forEach { group ->
                        SettingsGroupCard(
                            icon = settingsGroupIcon(group.iconName),
                            title = settingsGroupTitle(strings, group),
                            subtitle = when (group) {
                                SettingsGroup.ACCOUNT -> copy.accountHint
                                SettingsGroup.PLAYBACK -> copy.playbackHint
                                else -> copy.appearanceHint
                            },
                            onClick = { onOpen(group) },
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArrisButton(copy.start, Modifier.fillMaxWidth(), onClick = onFinish)
                    ArrisButton(copy.skip, Modifier.fillMaxWidth(), primary = false, onClick = onFinish)
                }
            }
        }
    }
}
