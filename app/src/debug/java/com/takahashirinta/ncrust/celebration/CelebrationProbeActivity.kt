/* Copyright (c) 2026 yaxiaiyuting. This Arris fork modification is licensed under GPLv3. */
package com.takahashirinta.ncrust.celebration

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.takahashirinta.ncrust.ui.theme.NcrustTheme
import io.github.takahashirinta.kanesumi.core.theme.MetroTheme

/** Debug-only entry for repeatable rendering/layout checks, without login or first-run dialogs. */
class CelebrationProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val kind = if (intent.getStringExtra("kind") == "LISTENER") CelebrationKind.LISTENER else CelebrationKind.BIRTHDAY
        val fontScale = intent.getFloatExtra("fontScale", 1f).let { if (it.isFinite()) it.coerceIn(1f, 2f) else 1f }
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NcrustTheme { MetroTheme { CelebrationOverlay(kind, onClose = { finish() }) } }
            }
        }
    }
}
