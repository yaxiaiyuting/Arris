/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui.player.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.palette.graphics.Palette
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

internal data class BackdropEnvironment(val animations: Boolean, val powerSaver: Boolean)

@Composable
internal fun rememberBackdropEnvironment(): BackdropEnvironment {
    val context = LocalContext.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    fun current() = BackdropEnvironment(
        animations = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f,
        powerSaver = power?.isPowerSaveMode == true,
    )
    var state by remember(context) { mutableStateOf(current()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { state = current() }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { state = current() }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        state = current()
        onDispose {
            context.unregisterReceiver(receiver)
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    return state
}

private data class BreathPalette(val a: Color, val b: Color, val c: Color) {
    fun blend(other: BreathPalette, t: Float) = BreathPalette(lerp(a, other.a, t), lerp(b, other.b, t), lerp(c, other.c, t))
}

@Composable
internal fun BreathingBackdrop(
    coverKey: String?,
    bitmap: Bitmap?,
    animate: Boolean,
    canTransition: Boolean,
    fluid: Boolean,
) {
    val theme = LocalMetroColors.current
    val fallback = BreathPalette(theme.primary, lerp(theme.primary, theme.surface, 0.37f),
        lerp(theme.primary, theme.onBackground, 0.22f))
    val extracted by produceState<BreathPalette?>(null, coverKey, bitmap, fallback) {
        value = null
        if (coverKey != null && bitmap != null) {
            value = withContext(Dispatchers.Default) { extractPalette(bitmap, fallback) }
        }
    }
    var target by remember { mutableStateOf(fallback) }
    var previous by remember { mutableStateOf(fallback) }
    val mix = remember { Animatable(1f) }
    // While Coil prepares the next cover, keep the previous colors. Blend only once its replacement is ready.
    LaunchedEffect(extracted, coverKey, fallback, canTransition) {
        val next = extracted ?: if (coverKey == null || bitmap == null) fallback else return@LaunchedEffect
        if (!canTransition) {
            target = next
            previous = next
            mix.snapTo(1f)
        } else if (next != target) {
            previous = previous.blend(target, mix.value)
            mix.snapTo(0f)
            target = next
            mix.animateTo(1f, tween(580))
        }
    }
    val shader = remember(fluid, animate) {
        if (fluid && animate && Build.VERSION.SDK_INT >= 33) {
            runCatching { FluidCanvas33() }.getOrNull()
        } else null
    }
    Box(Modifier.fillMaxSize().drawWithCache {
        val prior = glowLayers(previous)
        val next = glowLayers(target)
        onDrawBehind {
            val signal = if (animate) {
                MotionClock.breathGeneration
                MotionClock.breathing
            } else null
            val phase = signal?.phase ?: 0f
            val spread = signal?.spread ?: 0f
            val shimmer = signal?.shimmer ?: 0f
            val blend = mix.value
            // Blend in the gradient path while switching covers, so only one fluid pass ever runs.
            val drawn = blend == 1f && signal?.fluidTooSlow == false && Build.VERSION.SDK_INT >= 33 &&
                shader?.draw(this, target, phase, spread, shimmer) == true
            if (!drawn) {
                if (blend < 1f) drawGlows(prior, 1f - blend, phase, spread, shimmer)
                drawGlows(next, blend, phase, spread, shimmer)
            }
        }
    })
}

private fun extractPalette(bitmap: Bitmap, fallback: BreathPalette): BreathPalette? = runCatching {
    val readable = if (bitmap.config == Bitmap.Config.HARDWARE)
        bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null else bitmap
    try {
        val palette = Palette.from(readable).resizeBitmapArea(1024).maximumColorCount(12).generate()
        val base = palette.vibrantSwatch ?: palette.dominantSwatch ?: return null
        val alternate = palette.mutedSwatch ?: palette.darkVibrantSwatch ?: palette.lightVibrantSwatch
        val soft = palette.lightMutedSwatch ?: palette.lightVibrantSwatch
        BreathPalette(
            lerp(Color(base.rgb), fallback.a, 0.11f),
            lerp(alternate?.let { Color(it.rgb) } ?: Color(base.rgb), fallback.b, 0.22f),
            lerp(soft?.let { Color(it.rgb) } ?: Color(base.rgb), fallback.c, 0.27f),
        )
    } finally {
        if (readable !== bitmap) readable.recycle()
    }
}.getOrNull()

private data class Glow(val brush: Brush, val center: Offset, val alpha: Float)

private fun androidx.compose.ui.draw.CacheDrawScope.glowLayers(palette: BreathPalette): List<Glow> {
    val extent = maxOf(size.width, size.height, 1f)
    fun light(x: Float, y: Float, reach: Float, color: Color, alpha: Float): Glow {
        val center = Offset(size.width * x, size.height * y)
        return Glow(Brush.radialGradient(
            0f to color, 0.46f to color.copy(alpha = 0.57f), 1f to color.copy(alpha = 0f),
            center = center, radius = extent * reach,
        ), center, alpha)
    }
    return listOf(light(0.24f, 0.33f, 0.56f, palette.a, 0.80f),
        light(0.89f, 0.55f, 0.49f, palette.b, 0.65f),
        light(0.42f, 0.96f, 0.35f, palette.c, 0.48f))
}

private fun DrawScope.drawGlows(glows: List<Glow>, opacity: Float, phase: Float, spread: Float, shimmer: Float) {
    if (opacity <= 0f) return
    for (i in glows.indices) {
        val glow = glows[i]
        val direction = if (i == 1) -1f else 1f
        translate(size.width * direction * 0.072f * sin(phase),
            size.height * 0.051f * sin(phase * direction + i) * sin(phase * 0.5f)) {
            scale(1f + spread * 0.17f, 1f + spread * 0.12f, pivot = glow.center) {
                drawRect(glow.brush, alpha = opacity * glow.alpha * (0.83f + 0.17f * shimmer))
            }
        }
    }
}

@RequiresApi(33)
private class FluidCanvas33 {
    private val shader = RuntimeShader(PROGRAM)
    private val paint = Paint().apply { shader = this@FluidCanvas33.shader }
    private var failed = false
    private var palette: BreathPalette? = null

    fun draw(scope: DrawScope, colors: BreathPalette, phase: Float, spread: Float, shimmer: Float): Boolean {
        val canvas = scope.drawContext.canvas.nativeCanvas
        if (failed || !canvas.isHardwareAccelerated || scope.size.minDimension <= 0f) return false
        return try {
            if (palette != colors) {
                shader.setColorUniform("inkA", colors.a.toArgb())
                shader.setColorUniform("inkB", colors.b.toArgb())
                shader.setColorUniform("inkC", colors.c.toArgb())
                palette = colors
            }
            shader.setFloatUniform("viewport", scope.size.width, scope.size.height)
            shader.setFloatUniform("motion", cos(phase), sin(phase), spread, shimmer)
            canvas.drawRect(0f, 0f, scope.size.width, scope.size.height, paint)
            true
        } catch (_: RuntimeException) {
            failed = true
            false
        }
    }

    private companion object {
        const val PROGRAM = """
            uniform float2 viewport;
            uniform float4 motion;
            layout(color) uniform half4 inkA;
            layout(color) uniform half4 inkB;
            layout(color) uniform half4 inkC;

            half4 main(float2 coord) {
                float2 uv = coord / viewport;
                float2 tide = uv + 0.065 * float2(
                    sin(uv.y * 5.0 + motion.y * 2.0),
                    cos(uv.x * 4.0 + motion.x * 2.0)
                );
                float spread = 1.0 + 0.18 * motion.z;
                float2 a = (tide - float2(0.24 + 0.07 * motion.y, 0.33)) / spread;
                float2 b = (tide - float2(0.89 - 0.06 * motion.y, 0.56 + 0.08 * motion.x)) / spread;
                float2 c = (tide - float2(0.44, 0.96 - 0.05 * motion.x)) / spread;
                float wa = 0.68 * exp(-5.2 * dot(a, a));
                float wb = 0.54 * exp(-7.0 * dot(b, b));
                float wc = 0.33 * exp(-10.0 * dot(c, c));
                float sum = wa + wb + wc;
                float alpha = min(0.84, sum * (0.77 + 0.15 * motion.w));
                half3 color = (inkA.rgb * wa + inkB.rgb * wb + inkC.rgb * wc) / max(sum, 0.001);
                return half4(color * alpha, alpha);
            }
        """
    }
}
