package com.takahashirinta.ncrust.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 主题色来源三选一（B2-C）。形态对齐同页的 ThemeModeSelector：三等分方块 + 选中高亮。
 * 系统取色在 API < 31 上不可用：该项置灰、不可点，并在下方给出说明。
 */
@Composable
fun AccentSourceSelector(
    selected: AccentSource,
    systemEnabled: Boolean,
    labels: Triple<String, String, String>,
    systemHint: String,
    onSelect: (AccentSource) -> Unit,
) {
    val options = listOf(
        AccentSource.PRESET to labels.first,
        AccentSource.COVER to labels.second,
        AccentSource.SYSTEM to labels.third,
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // 与"主题模式""库页分类"共用同一个分段控件：短选项集在应用里只该有一种形态。
        com.takahashirinta.ncrust.ui.components.ArrisSegmentedTabs(
            items = options.map { it.second },
            selected = options.indexOfFirst { it.first == selected }.coerceAtLeast(0),
            onSelect = { onSelect(options[it].first) },
            enabled = options.map { it.first != AccentSource.SYSTEM || systemEnabled },
        )
        if (!systemEnabled) {
            Spacer(Modifier.height(6.dp))
            MetroText(
                systemHint,
            color = LocalMetroColors.current.onSurfaceVariant,
                style = TextStyle(fontSize = 11.sp)
            )
        }
    }
}
