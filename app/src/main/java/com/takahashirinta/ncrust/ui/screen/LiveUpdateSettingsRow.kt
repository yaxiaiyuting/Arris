/* Arris · Copyright (c) 2026 yaxiaiyuting, GPLv3. */
package com.takahashirinta.ncrust.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.takahashirinta.ncrust.player.LiveUpdateNotifier
import com.takahashirinta.ncrust.player.LiveUpdatePromotion
import com.takahashirinta.ncrust.ui.components.SettingActionRow
import com.takahashirinta.ncrust.ui.i18n.currentLanguageCode
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText

@Composable
internal fun LiveUpdateSettingsRow() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val promotion by LiveUpdateNotifier.promotion.collectAsState()
    val language = currentLanguageCode()
    val zh = language == "zh-CN"
    val tw = language == "zh-TW"
    val title = LocalStrings.current.liveUpdatesSettings
    val state = when (promotion) {
        LiveUpdatePromotion.UNSUPPORTED -> when {
            zh -> "不支持"
            tw -> "不支援"
            else -> "Unsupported"
        }
        LiveUpdatePromotion.NOT_PROMOTED -> when {
            zh -> "未提升"
            tw -> "未升級"
            else -> "Not promoted"
        }
        LiveUpdatePromotion.PROMOTED -> when {
            zh -> "已提升"
            tw -> "已升級"
            else -> "Promoted"
        }
    }
    DisposableEffect(lifecycle, context) {
        LiveUpdateNotifier.refreshPromotion(context)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) LiveUpdateNotifier.refreshPromotion(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (promotion == LiveUpdatePromotion.UNSUPPORTED) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            MetroText(title, color = LocalMetroColors.current.onBackground,
                style = LocalMetroTypography.current.bodyLarge)
            MetroText(
                if (zh || tw) "$state · 需要 Android 16" else "$state · Requires Android 16",
                color = LocalMetroColors.current.onSurfaceVariant,
                style = LocalMetroTypography.current.bodySmall,
            )
        }
    } else {
        SettingActionRow(label = title, trailing = state, onClick = {
            if (!LiveUpdateNotifier.launchSettings(context)) {
                val message = when {
                    zh -> "找不到系统通知设置"
                    tw -> "找不到系統通知設定"
                    else -> "System notification settings are unavailable"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        })
    }
}
