/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.lyric.LrcWord
import com.takahashirinta.ncrust.lyric.LyricsSweepConfig
import com.takahashirinta.ncrust.lyric.LyricsWordAnimationMode
import com.takahashirinta.ncrust.ui.player.KaraokeControls
import com.takahashirinta.ncrust.ui.player.KaraokeCue
import com.takahashirinta.ncrust.ui.player.KaraokeScreen
import com.takahashirinta.ncrust.ui.player.NcrustLyricLine
import com.takahashirinta.ncrust.ui.player.PlayerCardCallbacks
import com.takahashirinta.ncrust.ui.LocalHeightClass
import com.takahashirinta.ncrust.ui.heightClassFor
import com.takahashirinta.ncrust.ui.theme.ArrisMetroTypography
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors
import com.takahashirinta.ncrust.ui.theme.NcrustTheme
import com.takahashirinta.ncrust.ui.theme.themeColorPresets
import com.takahashirinta.ncrust.ui.theme.toMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * 网站上「播放器」那一组配图的离线渲染器。
 *
 * ## 为什么不截图手机
 *
 * 站点配图以前是用真机/模拟器截图再裁的：状态栏、通知、电量、系统手势条全在画面里，
 * 主题一换就得重来一遍，而且没法保证两次出图一致。这里改成**离线渲染真组件** ——
 * 同一个 `KaraokeScreen`（就是 App 里那个），灌一份固定的歌词数据，只在测试进程里画一帧。
 *
 * 产物落在 `Android/data/<pkg>/files/ui-review/` 下的 PNG，再由 `tools/site-shots.sh` 转成 630 宽 webp 放进 `relay/public/assets/`。因为渲染时 `decorFitsSystemWindows = false`，
 * 画面里没有状态栏，也不需要事后裁。
 */
class ArrisKaraokeReviewTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 一句四拍的样例，逐字时间轴让高亮扫得起来。 */
    private fun line(atMs: Long, text: String, translation: String = ""): NcrustLyricLine {
        val per = 420L
        var charStart = 0
        val words = text.chunked(2).mapIndexed { index, piece ->
            LrcWord(
                startMs = atMs + index * per,
                durationMs = per,
                text = piece,
                charStart = charStart,
                charEndExclusive = (charStart + piece.length).also { charStart = it },
            )
        }
        return NcrustLyricLine(
            timestampMillis = atMs,
            text = text,
            translation = translation,
            words = words,
            endMs = atMs + words.size * per,
        )
    }

    private val lines = listOf(
        line(1_000, "波がさらう 足あとを", "海浪带走脚印"),
        line(9_000, "遠い夏の においがした", "闻到了那个遥远夏天的味道"),
        line(17_000, "ありがとう 君がいたから", "谢谢你 因为有你在"),
        line(25_000, "この歌を 歌えるよ", "我才能唱出这首歌"),
        line(33_000, "また会える 日まで", "直到重逢的那一天"),
    )

    private val translations = mapOf(
        1_000L to LrcLine(timeMs = 1_000, text = "海浪带走脚印"),
        9_000L to LrcLine(timeMs = 9_000, text = "闻到了那个遥远夏天的味道"),
        17_000L to LrcLine(timeMs = 17_000, text = "谢谢你 因为有你在"),
        25_000L to LrcLine(timeMs = 25_000, text = "我才能唱出这首歌"),
        33_000L to LrcLine(timeMs = 33_000, text = "直到重逢的那一天"),
    )

    private fun show(dark: Boolean, content: @Composable () -> Unit) {
        compose.activityRule.scenario.onActivity {
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        compose.setContent {
            val density = LocalDensity.current
            val configuration = LocalConfiguration.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1f),
                LocalHeightClass provides heightClassFor(configuration.screenHeightDp.toFloat()),
            ) {
                NcrustTheme(primaryColor = themeColorPresets[0].color, isDark = dark) {
                    MetroTheme(LocalNcrustColors.current.toMetroColors(dark), ArrisMetroTypography) {
                        // 站点配图要的是"应用自己的画面"，不含系统栏 —— 所以在这里让位，
                        // 出图后由 tools/site-shots.sh 把上下两条系统栏区域裁掉。
                        Box(
                            Modifier.fillMaxSize()
                                .background(LocalNcrustColors.current.background)
                                .systemBarsPadding(),
                        ) { content() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(target.getExternalFilesDir(null), "ui-review/$name.png")
        out.parentFile?.mkdirs()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try { out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    /** 跟唱页：当前句大字 + 下一句提前出现 + 逐字扫亮。 */
    private fun karaoke(dark: Boolean) {
        val position = MutableStateFlow(19_400L)
        show(dark) {
            val controls = remember {
                KaraokeControls(
                    embedded = false,
                    title = "ありがとう",
                    artist = "大橋卓弥",
                    cover = null,
                    position = position,
                    duration = MutableStateFlow(299_000L),
                    progress = MutableStateFlow(0.35f),
                    buffering = MutableStateFlow(false),
                    transport = PlayerCardCallbacks(onPlayPause = {}, onDismiss = {}),
                    previousEnabled = true,
                    exit = {},
                    translatedPrimary = false,
                    toggleLanguage = {},
                )
            }
            KaraokeScreen(
                controls = controls,
                lines = lines,
                translations = translations,
                cue = KaraokeCue(current = 2, next = 3),
                position = { position.value },
                onSeek = {},
                playing = true,
                loading = false,
                wordsEnabled = true,
                animationMode = LyricsWordAnimationMode.GRADIENT_SWEEP,
                sweep = LyricsSweepConfig.DEFAULT,
                fontScale = 1f,
                interactive = true,
            )
        }
        screenshot("karaoke-${if (dark) "dark" else "light"}")
    }

    @Test fun karaokeLight() = karaoke(false)

    @Test fun karaokeDark() = karaoke(true)
}
