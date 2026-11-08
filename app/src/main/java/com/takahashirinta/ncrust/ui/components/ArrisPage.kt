/* Arris app visual system. Copyright (c) 2026 yaxiaiyuting, GPLv3. */
package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.R
import com.takahashirinta.ncrust.ui.theme.AppShapes
import com.takahashirinta.ncrust.ui.theme.AppMotion
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors
import com.takahashirinta.ncrust.ui.theme.contrastRatio
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.*

/** Accent used as small foreground ink must remain readable on every surface role. */
@Composable
internal fun arrisAccentInk(): Color {
    val c = LocalNcrustColors.current
    return if (listOf(c.background, c.surface, c.surfaceContainerHighest).all {
        contrastRatio(c.primary.toArgb(), it.toArgb()) >= 4.5
    }) c.primary else c.onBackground
}

/** A thin, translucent matte surface. No backdrop blur, shadow or continuous redraw. */
@Composable
internal fun Modifier.arrisSurface(
    shape: Shape = AppShapes.large,
    emphasized: Boolean = false,
): Modifier {
    val c = LocalMetroColors.current
    val base = lerp(c.surface, c.primary, if (emphasized) .045f else .008f)
    val edge = c.onBackground.copy(alpha = if (emphasized) .13f else .075f)
    return clip(shape)
        .background(Brush.verticalGradient(listOf(base.copy(alpha = .96f), base.copy(alpha = .82f))))
        .border(.7.dp, Brush.verticalGradient(listOf(edge, edge.copy(alpha = .025f))), shape)
}

/** Keep content behind the navigation legible as depth, without exposing readable ghost text. */
@Composable
internal fun Modifier.arrisNavigationSurface(): Modifier {
    val c = LocalMetroColors.current
    return drawWithCache {
        val wash = Brush.verticalGradient(listOf(c.surface.copy(alpha = .96f), c.surface))
        onDrawBehind {
            drawRect(wash)
            drawLine(c.onBackground.copy(alpha = .06f), Offset.Zero, Offset(size.width, 0f), .7.dp.toPx())
        }
    }
}

/** Selection is a bounded alpha transition, read only in the drawing layer. */
@Composable
private fun ArrisSelection(selected: Boolean, modifier: Modifier = Modifier) {
    val c = LocalMetroColors.current
    val alpha = remember { Animatable(if (selected) 1f else 0f) }
    LaunchedEffect(selected) { alpha.animateTo(if (selected) 1f else 0f, AppMotion.effects) }
    Box(modifier.graphicsLayer { this.alpha = alpha.value }
        .background(c.onBackground.copy(alpha = .065f), AppShapes.medium)) {
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
            .size(12.dp, 2.dp).background(c.onBackground.copy(alpha = .65f), AppShapes.full))
    }
}

@Composable
internal fun ArrisSegmentedTabs(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 个别项不可用（例如"跟随系统"在系统取色不可用时）。null = 全部可用。 */
    enabled: List<Boolean>? = null,
) {
    val c = LocalMetroColors.current
    Row(modifier.fillMaxWidth().selectableGroup().arrisSurface().padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { index, label ->
            val active = selected == index
            val usable = enabled?.getOrNull(index) ?: true
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(AppShapes.medium)
                .selectable(active, enabled = usable, role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center) {
                ArrisSelection(active, Modifier.matchParentSize())
                MetroText(label,
                    color = when {
                        !usable -> c.onSurfaceVariant.copy(alpha = .45f)
                        active -> c.onBackground
                        else -> c.onSurfaceVariant
                    },
                    style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun ArrisChip(
    text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = LocalMetroColors.current
    // The drawn capsule is inset; the whole 48dp height remains accessible to touch.
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(AppShapes.full)
        .selectable(selected, role = Role.Tab, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().padding(vertical = 6.dp).clip(AppShapes.full)
            .background(if (selected) c.onBackground.copy(alpha = .065f) else Color.Transparent)
            .border(.7.dp, c.onBackground.copy(alpha = if (selected) .20f else .08f), AppShapes.full))
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            MetroIcon(Icons.Default.Check, null, tint = c.onBackground, sizeDp = 14.dp,
                modifier = Modifier.graphicsLayer { alpha = if (selected) 1f else 0f })
            Spacer(Modifier.width(5.dp))
            MetroText(text, color = if (selected) c.onBackground else c.onSurfaceVariant,
            style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun ArrisPageHeader(title: String, subtitle: String, onBack: () -> Unit) {
    val c = LocalMetroColors.current
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MetroIconButtonSurface(Icons.AutoMirrored.Filled.ArrowBack, LocalStrings.current.back, onBack)
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
    Box(Modifier.size(48.dp).appPressScale(pressedScale = AppMotion.ICON_PRESS_SCALE).arrisSurface(AppShapes.full)
        .clickable(role = Role.Button, onClickLabel = label, onClick = action),
        contentAlignment = Alignment.Center) {
        MetroIcon(icon, contentDescription = label, tint = c.onBackground, sizeDp = 22.dp)
    }
}

@Composable
internal fun ArrisButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true,
                         enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalMetroColors.current
    Box(modifier.appPressScale(enabled = enabled, pressedScale = AppMotion.BROWSE_PRESS_SCALE).clip(AppShapes.full)
        .background(if (primary) c.primary.copy(alpha = if (enabled) 1f else 0.45f) else c.surface)
        .border(.7.dp, if (primary) Color.Transparent else c.onBackground.copy(alpha = .10f), AppShapes.full)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 50.dp).padding(horizontal = 18.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
        MetroText(text, color = if (primary) c.onPrimary else c.onBackground,
            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
internal fun ArrisBottomNav(items: List<Pair<ImageVector, String>>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalMetroColors.current
    Row(Modifier.fillMaxSize().selectableGroup().padding(horizontal = 8.dp, vertical = 4.dp)) {
        items.forEachIndexed { index, (icon, label) ->
            val active = selected == index
            Box(Modifier.weight(1f).fillMaxHeight().clip(AppShapes.large)
                .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center) {
                ArrisSelection(active, Modifier.matchParentSize().padding(horizontal = 5.dp, vertical = 2.dp))
                Column(
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                MetroIcon(icon, null, tint = if (active) c.onBackground else c.onSurfaceVariant, sizeDp = 20.dp)
                MetroText(label, color = if (active) c.onBackground else c.onSurfaceVariant,
                    style = TextStyle(fontSize = 10.sp, lineHeight = 16.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun ArrisShortcut(title: String, detail: String, icon: ImageVector, modifier: Modifier, action: () -> Unit) {
    val colors = LocalMetroColors.current
    Column(
        modifier.appPressScale(pressedScale = AppMotion.BROWSE_PRESS_SCALE).arrisSurface()
            .clickable(role = Role.Button, onClick = action)
            .padding(16.dp),
    ) {
        Box(
            Modifier.size(34.dp).background(colors.primary.copy(alpha = 0.11f), AppShapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            MetroIcon(icon, null, tint = arrisAccentInk(), sizeDp = 20.dp)
        }
        Spacer(Modifier.height(14.dp))
        MetroText(title, color = colors.onBackground, style = LocalMetroTypography.current.titleMedium)
        Spacer(Modifier.height(4.dp))
        MetroText(detail, color = colors.onSurfaceVariant, style = LocalMetroTypography.current.caption)
    }
}

@Composable
internal fun ArrisHomeIdentity(title: String, modifier: Modifier = Modifier) {
    val colors = LocalMetroColors.current
    val chinese = com.takahashirinta.ncrust.ui.i18n.currentLanguageCode().startsWith("zh")
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
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
            modifier = Modifier.size(64.dp),
        )
    }
}
