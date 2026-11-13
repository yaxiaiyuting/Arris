package com.takahashirinta.ncrust.ui.screen

import android.os.SystemClock
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import com.takahashirinta.ncrust.warmup.AppWarmup
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        // Let the local snapshot fill the first screen, with a bounded wait if storage is slow.
        val started = SystemClock.elapsedRealtime()
        withTimeoutOrNull(LOCAL_RESTORE_BUDGET_MS) { AppWarmup.ready.first { it } }
        delay((MIN_DWELL_MS - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0L))
        alpha.animateTo(0f, animationSpec = tween(260, easing = FastOutSlowInEasing))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalMetroColors.current.background)
            .graphicsLayer { this.alpha = alpha.value }
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MetroText(
                "Arris",
                color = Color(0xFF1DB954),
                style = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
            )
            Spacer(Modifier.height(8.dp))
            MetroText(
                "A Re-defined Music Player",
                color = LocalMetroColors.current.onBackground,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
            )
        }

        MetroText(
            "Artwork by Project Arcturius",
            color = LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.5f),
            style = TextStyle(fontSize = 12.sp, textAlign = TextAlign.Center),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        )
    }
}

// splash 最短驻留：warmup 早于此完成时仍要撑到这个时间，避免视觉一闪
private const val MIN_DWELL_MS = 350L
private const val LOCAL_RESTORE_BUDGET_MS = 3_000L
