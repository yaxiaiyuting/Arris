/* Arris app visual system. Copyright (c) 2026 yaxiaiyuting, GPLv3. */
package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.R
import com.takahashirinta.ncrust.ui.theme.AppShapes
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.*

@Composable
internal fun ArrisPageHeader(title: String, eyebrow: String, subtitle: String, onBack: () -> Unit) {
    val c = LocalMetroColors.current
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MetroIconButtonSurface(Icons.AutoMirrored.Filled.ArrowBack, LocalStrings.current.back, onBack)
            Spacer(Modifier.width(12.dp))
            MetroText(eyebrow, color = c.primary,
                style = TextStyle(fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.height(24.dp))
        MetroText(title, color = c.onBackground, style = LocalMetroTypography.current.pageHeading)
        Spacer(Modifier.height(8.dp))
        MetroText(subtitle, color = c.onSurfaceVariant,
            style = TextStyle(fontSize = 14.sp, lineHeight = 22.sp))
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun MetroIconButtonSurface(icon: ImageVector, label: String, action: () -> Unit) {
    val c = LocalMetroColors.current
    Box(Modifier.size(48.dp).clip(AppShapes.full).background(c.surface)
        .border(1.dp, LocalNcrustColors.current.outlineVariant, AppShapes.full)
        .clickable(role = Role.Button, onClickLabel = label, onClick = action),
        contentAlignment = Alignment.Center) {
        MetroIcon(icon, contentDescription = label, tint = c.onBackground, sizeDp = 22.dp)
    }
}

@Composable
internal fun ArrisButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true,
                         enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalMetroColors.current
    Box(modifier.clip(AppShapes.full)
        .background(if (primary) c.primary.copy(alpha = if (enabled) 1f else 0.45f) else c.surface)
        .border(1.dp, if (primary) c.primary else LocalNcrustColors.current.outlineVariant, AppShapes.full)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 50.dp).padding(horizontal = 18.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
        MetroText(text, color = if (primary) c.onPrimary else c.onBackground,
            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
internal fun ArrisBottomNav(items: List<Pair<ImageVector, String>>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalMetroColors.current
    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        items.forEachIndexed { index, (icon, label) ->
            val active = selected == index
            Column(Modifier.weight(1f).fillMaxHeight().clip(AppShapes.large)
                .background(if (active) c.primary.copy(alpha = .12f) else c.surface)
                .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) }),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                MetroIcon(icon, null, tint = if (active) c.primary else c.onSurfaceVariant, sizeDp = 20.dp)
                MetroText(label, color = if (active) c.primary else c.onSurfaceVariant,
                    style = TextStyle(fontSize = 10.sp, lineHeight = 16.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1)
            }
        }
    }
}

@Composable
internal fun ArrisShortcut(title: String, detail: String, icon: ImageVector, modifier: Modifier, action: () -> Unit) {
    val colors = LocalMetroColors.current
    val wash = Brush.verticalGradient(listOf(lerp(colors.surface, colors.primary, 0.075f), colors.surface))
    Column(
        modifier.clip(AppShapes.large)
            .background(wash)
            .border(1.dp, LocalNcrustColors.current.outlineVariant, AppShapes.large)
            .clickable(role = Role.Button, onClick = action)
            .padding(16.dp),
    ) {
        Box(
            Modifier.size(34.dp).background(colors.primary.copy(alpha = 0.11f), AppShapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            MetroIcon(icon, null, tint = colors.primary, sizeDp = 20.dp)
        }
        Spacer(Modifier.height(8.dp))
        MetroText(title, color = colors.onBackground, style = LocalMetroTypography.current.titleMedium)
        MetroText(detail, color = colors.onSurfaceVariant, style = LocalMetroTypography.current.caption)
    }
}

@Composable
internal fun ArrisHomeIdentity(title: String, modifier: Modifier = Modifier) {
    val colors = LocalMetroColors.current
    val chinese = com.takahashirinta.ncrust.ui.i18n.currentLanguageCode().startsWith("zh")
    val light = colors.background.luminance() > 0.5f
    Box(
        modifier.fillMaxWidth()
            .clip(AppShapes.extraLarge)
            .border(1.dp, LocalNcrustColors.current.outlineVariant, AppShapes.extraLarge)
            .drawWithCache {
                val end = if (layoutDirection == LayoutDirection.Ltr) 0.84f else 0.16f
                val center = Offset(size.width * end, size.height * 0.44f)
                val wash = Brush.radialGradient(
                    listOf(
                        lerp(colors.surface, colors.primary, if (light) 0.20f else 0.24f),
                        lerp(colors.surface, colors.background, 0.12f),
                    ),
                    center = center,
                    radius = size.width * 0.74f,
                )
                onDrawBehind {
                    drawRect(wash)
                    drawArrisGrooves(center, size.height * 0.83f, colors.primary)
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 18.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                MetroText(
                    "ARRIS / YOUR MUSIC",
                    color = colors.primary,
                    style = TextStyle(fontSize = 10.sp, letterSpacing = 1.7.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.height(4.dp))
                MetroText(
                    title,
                    color = colors.onBackground,
                    style = LocalMetroTypography.current.pageHeading.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.8).sp,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                MetroText(
                    if (chinese) "喜欢的音乐，都在这里。" else "Your music, all in one place.",
                    color = colors.onSurfaceVariant,
                    style = LocalMetroTypography.current.bodySmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            Image(
                painter = painterResource(R.drawable.tux_listener),
                contentDescription = null,
                modifier = Modifier.size(70.dp),
            )
        }
    }
}
