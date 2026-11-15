/* Arris · Copyright (c) 2026 yaxiaiyuting, GPLv3. */
package com.takahashirinta.ncrust.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.takahashirinta.ncrust.player.LiveUpdateNotifier
import com.takahashirinta.ncrust.player.LiveUpdateStatus
import com.takahashirinta.ncrust.ui.components.SettingActionRow
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.i18n.currentLanguageCode
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText

@Composable
internal fun LiveUpdateSettingsRow() {
    val context = LocalContext.current
    val status by LiveUpdateNotifier.status.collectAsStateWithLifecycle()
    val language = currentLanguageCode()
    fun localized(simplified: String, traditional: String, english: String): String = when (language) {
        "zh-CN" -> simplified
        "zh-TW" -> traditional
        else -> english
    }
    val title = LocalStrings.current.liveUpdatesSettings
    LaunchedEffect(context) {
        LiveUpdateNotifier.refreshPromotion(context)
    }
    if (status == LiveUpdateStatus.UNSUPPORTED) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            MetroText(title, color = LocalMetroColors.current.onBackground, style = LocalMetroTypography.current.bodyLarge)
            MetroText(
                localized("不支持 · 需要 Android 16", "不支援 · 需要 Android 16", "Unsupported · Requires Android 16"),
                color = LocalMetroColors.current.onSurfaceVariant,
                style = LocalMetroTypography.current.bodySmall,
            )
        }
        return
    }
    val subtitle = when (status) {
        LiveUpdateStatus.UNSUPPORTED -> null
        LiveUpdateStatus.APP_DISABLED ->
            localized("Arris 的实时通知已关闭", "Arris 的即時通知已關閉", "Live updates are disabled in Arris")
        LiveUpdateStatus.NOTIFICATIONS_DISABLED ->
            localized("去系统设置允许 Arris 发送通知", "前往系統設定允許 Arris 傳送通知", "Allow notifications in system settings")
        LiveUpdateStatus.PROMOTION_DISABLED ->
            localized("去系统设置开启 Arris 的实时通知", "前往系統設定開啟 Arris 的即時通知", "Enable live updates in system settings")
        LiveUpdateStatus.CHANNEL_DISABLED ->
            localized("去系统设置启用 Arris 实时通知类别", "前往系統設定啟用 Arris 即時通知類別", "Enable the Arris live updates channel in system settings")
        LiveUpdateStatus.IDLE ->
            localized("当前没有实时通知", "目前沒有即時通知", "No live notification is active")
        LiveUpdateStatus.ACTIVE_NOT_PROMOTED ->
            localized("通知已发送，系统尚未提升", "通知已傳送，系統尚未提升", "The system hasn't promoted the active notification")
        LiveUpdateStatus.PROMOTED -> localized("打开系统通知设置", "開啟系統通知設定", "Open system notification settings")
        LiveUpdateStatus.UNKNOWN -> localized("暂时读不到系统状态", "暫時無法讀取系統狀態", "System status is temporarily unavailable")
    }
    SettingActionRow(
        label = title,
        subtitle = subtitle,
        trailing = if (status == LiveUpdateStatus.PROMOTED) localized("已提升", "已提升", "Promoted")
            else localized("未提升", "未提升", "Not promoted"),
        onClick = {
            if (!LiveUpdateNotifier.launchSettings(context)) {
                val message = localized("找不到系统通知设置", "找不到系統通知設定", "System notification settings are unavailable")
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        },
    )
}
