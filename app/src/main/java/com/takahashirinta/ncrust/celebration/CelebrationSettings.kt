/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 *
 * 修改说明：
 *   - v3.9.0：生日设置界面彻底重构。
 *     · 日期选择弹窗升级为温润优雅的生日卡片
 *     · 采用现代胶囊药丸按钮 (CelebrationPillButton)
 *     · 遵循全局设计系统容器规范 (AppShapes.extraLarge)
 */

package com.takahashirinta.ncrust.celebration

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.takahashirinta.ncrust.ui.components.MetroDropdownRow
import com.takahashirinta.ncrust.ui.components.SettingActionRow
import com.takahashirinta.ncrust.ui.components.SettingSwitchRow
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.AppShapes
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun CelebrationSettingsRow(id: String) {
    val context = LocalContext.current
    val s = LocalStrings.current.celebration
    val options = rememberCelebrationOptions(context)
    var picker by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }

    when (id) {
        "action.birthday_date" -> {
            val date = if (BirthdayRule.valid(options.month, options.day)) {
                val cal = Calendar.getInstance().apply {
                    set(2000, options.month - 1, options.day)
                }
                SimpleDateFormat(
                    android.text.format.DateFormat.getBestDateTimePattern(
                        Locale.getDefault(), "MMMMd"
                    ), Locale.getDefault()
                ).format(cal.time)
            } else s.notSet
            SettingActionRow(label = s.dateTitle, trailing = date, onClick = { picker = true })
            MetroText(
                s.dateHint,
                color = LocalMetroColors.current.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        CelebrationPrefs.ENABLED -> SettingSwitchRow(
            title = s.enabledTitle,
            description = s.enabledHint,
            checked = options.enabled,
            onCheckedChange = {
                if (it && !BirthdayRule.valid(options.month, options.day)) picker = true
                else CelebrationPrefs.setEnabled(context, it)
            }
        )

        CelebrationPrefs.ANIMATED -> SettingSwitchRow(
            title = s.animationTitle,
            description = s.animationHint,
            checked = options.animated,
            onCheckedChange = { CelebrationPrefs.setAnimated(context, it) }
        )

        "action.birthday_preview" -> SettingActionRow(
            label = s.previewTitle,
            trailing = "",
            onClick = { preview = true }
        )
    }

    if (picker) BirthdayPicker(onClose = { picker = false })
    if (preview) CelebrationOverlay(CelebrationKind.BIRTHDAY, onClose = { preview = false })
}

@Composable
private fun BirthdayPicker(onClose: () -> Unit) {
    val context = LocalContext.current
    val s = LocalStrings.current.celebration
    val colors = LocalNcrustColors.current
    val metro = LocalMetroColors.current
    val original = remember { CelebrationPrefs.read(context) }
    var month by remember {
        mutableIntStateOf(
            if (BirthdayRule.valid(original.month, original.day)) original.month
            else Calendar.getInstance().get(Calendar.MONTH) + 1
        )
    }
    var day by remember {
        mutableIntStateOf(
            if (BirthdayRule.valid(original.month, original.day)) original.day
            else 1
        )
    }

    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(AppShapes.extraLarge)
                .background(colors.surfaceContainerHigh, AppShapes.extraLarge)
                .border(1.dp, colors.outlineVariant, AppShapes.extraLarge)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶部小徽章
            Box(
                Modifier
                    .size(46.dp)
                    .clip(AppShapes.full)
                    .background(metro.primary.copy(alpha = 0.12f), AppShapes.full),
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    Icons.Default.Cake,
                    contentDescription = null,
                    tint = metro.primary,
                    sizeDp = 22.dp
                )
            }

            Spacer(Modifier.height(14.dp))

            MetroText(
                text = s.dateTitle,
                color = metro.onBackground,
                style = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            )
            Spacer(Modifier.height(6.dp))
            MetroText(
                text = s.dateHint,
                color = metro.onSurfaceVariant,
                style = TextStyle(
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            )

            Spacer(Modifier.height(20.dp))

            // 下拉选择区
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.large)
                    .background(colors.surfaceContainerLow, AppShapes.large)
                    .padding(8.dp)
            ) {
                MetroDropdownRow(
                    label = s.monthLabel,
                    selectedIndex = month - 1,
                    options = (1..12).map { it.toString() },
                    onSelect = {
                        month = it + 1
                        day = day.coerceAtMost(BirthdayRule.days(month))
                    }
                )
                MetroDropdownRow(
                    label = s.dayLabel,
                    selectedIndex = day - 1,
                    options = (1..BirthdayRule.days(month)).map { it.toString() },
                    onSelect = { day = it + 1 }
                )
            }

            Spacer(Modifier.height(24.dp))

            // 操作按钮
            CelebrationPillButton(
                text = s.save,
                onClick = {
                    CelebrationPrefs.setDate(context, month, day)
                    onClose()
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (BirthdayRule.valid(original.month, original.day)) {
                Spacer(Modifier.height(10.dp))
                CelebrationPillButton(
                    text = s.clear,
                    onClick = {
                        CelebrationPrefs.clearDate(context)
                        onClose()
                    },
                    primary = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(10.dp))
            CelebrationPillButton(
                text = s.cancel,
                onClick = onClose,
                primary = false,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
