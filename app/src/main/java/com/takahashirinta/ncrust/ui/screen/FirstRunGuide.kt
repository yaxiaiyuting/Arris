/*
 * Copyright (c) 2026 yaxiaiyuting.
 * Arris fork contribution, licensed under GPLv3. See LICENSE.
 */

package com.takahashirinta.ncrust.ui.screen

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.R
import com.takahashirinta.ncrust.ui.components.ArrisButton
import com.takahashirinta.ncrust.ui.components.ArrisFullScreenDialog
import com.takahashirinta.ncrust.ui.components.SettingsRow
import com.takahashirinta.ncrust.ui.components.arrisSurface
import com.takahashirinta.ncrust.ui.components.settingsGroupIcon
import com.takahashirinta.ncrust.ui.components.settingsGroupTitle
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.i18n.currentLanguageCode
import com.takahashirinta.ncrust.ui.i18n.firstRunStringsForCode
import com.takahashirinta.ncrust.ui.settings.SettingsGroup
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText

private const val PREFS_SETUP = "arris_setup"
private const val GUIDE_COMPLETED = "guide_completed"
private val guideGroups = listOf(SettingsGroup.ACCOUNT, SettingsGroup.PLAYBACK, SettingsGroup.APPEARANCE)

@Suppress("DEPRECATION")
internal fun firstRunGuidePending(context: Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_SETUP, Context.MODE_PRIVATE)
    if (prefs.contains(GUIDE_COMPLETED)) return !prefs.getBoolean(GUIDE_COMPLETED, false)
    // Persist eligibility so an unfinished guide can resume even if the app updates before completion.
    val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
    val pending = pkg.firstInstallTime == pkg.lastUpdateTime
    prefs.edit().putBoolean(GUIDE_COMPLETED, !pending).apply()
    return pending
}

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
    ArrisFullScreenDialog(onDismiss = onFinish, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(painterResource(R.drawable.tux_listener), null, Modifier.size(72.dp))
                MetroText(copy.title, color = colors.onBackground, style = typography.headlineMedium)
                MetroText(copy.summary, color = colors.onSurfaceVariant, style = typography.bodyMedium)
            }
            Column(Modifier.fillMaxWidth().arrisSurface()) {
                guideGroups.forEachIndexed { index, group ->
                    SettingsRow(
                        icon = settingsGroupIcon(group.iconName),
                        title = settingsGroupTitle(strings, group),
                        subtitle = when (group) {
                            SettingsGroup.ACCOUNT -> copy.accountHint
                            SettingsGroup.PLAYBACK -> copy.playbackHint
                            else -> copy.appearanceHint
                        },
                        onClick = { onOpen(group) },
                        showDivider = index != guideGroups.lastIndex,
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
