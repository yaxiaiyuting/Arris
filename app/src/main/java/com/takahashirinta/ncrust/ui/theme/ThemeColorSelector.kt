package com.takahashirinta.ncrust.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/** Named, accessible swatches with a fixed 48dp touch target and quiet selection frame. */
@Composable
fun ThemeColorSelector(
    selectedIndex: Int,
    presets: List<ThemeColorPreset>,
    onSelect: (Int) -> Unit
) {
    val colorNames = LocalStrings.current.themeColorNames
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            presets.forEachIndexed { index, preset ->
                val isSelected = index == selectedIndex
                val name = colorNames.getOrElse(index) { preset.label }
                Box(
                    Modifier.size(48.dp).clip(AppShapes.medium)
                        .background(if (isSelected) LocalMetroColors.current.onBackground.copy(alpha = .065f) else Color.Transparent)
                        .selectable(isSelected, role = Role.RadioButton, onClick = { onSelect(index) })
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(30.dp).clip(AppShapes.full).background(preset.color)
                        .border(.7.dp, LocalMetroColors.current.onBackground.copy(alpha = .18f), AppShapes.full),
                        contentAlignment = Alignment.Center) {
                        if (isSelected) MetroIcon(Icons.Default.Check, null, tint = onAccentColor(preset.color), sizeDp = 18.dp)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        MetroText(
            text = colorNames.getOrElse(selectedIndex) {
                presets.getOrElse(selectedIndex) { presets[0] }.label
            },
            color = LocalMetroColors.current.onBackground,
            style = TextStyle(fontSize = 14.sp)
        )
    }
}
