package com.takahashirinta.ncrust.ui

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.People
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.takahashirinta.ncrust.R
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.ui.components.*
import com.takahashirinta.ncrust.ui.player.PlayerCard
import com.takahashirinta.ncrust.ui.player.PlayerCardCallbacks
import com.takahashirinta.ncrust.ui.player.motion.MotionPrefs
import com.takahashirinta.ncrust.ui.screen.RecognizeScreen
import com.takahashirinta.ncrust.ui.screen.TogetherScreen
import com.takahashirinta.ncrust.ui.screen.UserScreen
import com.takahashirinta.ncrust.ui.theme.*
import io.github.takahashirinta.kanesumi.core.theme.MetroTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Offline component review on a fresh emulator. No account, recognition submission, or room creation. */
class ArrisVisualReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val song get() = SongItem(id = -383L, name = "喜欢的音乐，留在身边", artists = listOf(ArtistItem(name = "Arris · 本地视觉样例")),
        album = AlbumItem(name = "界面验证", picUrl = "android.resource://com.takahashirinta.ncrust/${R.drawable.tux_listener}"), duration = 208000L)

    private fun show(dark: Boolean = false, scale: Float = 1f, content: @Composable () -> Unit) {
        compose.activityRule.scenario.onActivity {
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
        compose.setContent {
            val density = LocalDensity.current
            val configuration = LocalConfiguration.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale),
                LocalHeightClass provides heightClassFor(configuration.screenHeightDp.toFloat())) {
                NcrustTheme(primaryColor = themeColorPresets[0].color, isDark = dark) {
                    MetroTheme(LocalNcrustColors.current.toMetroColors(dark), ArrisMetroTypography) {
                        Box(Modifier.fillMaxSize().background(LocalNcrustColors.current.background)) { content() }
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
    @Test fun recognitionIdleAndLoginHint() {
        show { RecognizeScreen {} }
        compose.onNodeWithText("开始识别").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("请先登录网易云音乐，再开始识别。").assertExists()
        screenshot("recognition-light")
    }
    @Test fun recognitionDarkLargeFont() {
        show(dark = true, scale = 1.5f) { RecognizeScreen {} }
        compose.onNodeWithText("听歌识曲").assertIsDisplayed()
        compose.onNodeWithText("开始识别").performScrollTo().assertIsDisplayed()
        screenshot("recognition-dark-large-font")
    }
    @Test fun togetherTransportAndAddress() {
        show { TogetherScreen {} }
        compose.onNodeWithText("局域网", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("房间邀请或地址").performTextInput("192.168.1.8:4567")
        compose.onNodeWithText("192.168.1.8:4567").assertExists()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        screenshot("together-lan-input")
        compose.onNodeWithText("P2P", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("房间邀请或地址").performTextClearance()
        compose.onNodeWithContentDescription("房间邀请或地址").performTextInput("203.0.113.7:4567#123456")
        compose.onNodeWithText("203.0.113.7:4567#123456").assertExists()
    }
    @Test fun togetherDark() {
        show(dark = true) { TogetherScreen {} }
        compose.onNodeWithText("一起听").assertIsDisplayed()
        screenshot("together-dark")
    }
    @Test fun settingsLight() {
        show { UserScreen() }
        compose.onNodeWithText(com.takahashirinta.ncrust.ui.i18n.zhCN.tabUser).assertIsDisplayed()
        screenshot("settings-light")
    }
    @Test fun libraryComponents() {
        show {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 20.dp)) {
                Column(Modifier.padding(start = 16.dp)) { ArrisHomeIdentity() }
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ArrisShortcut("听歌识曲", "留住偶然的旋律", Icons.Default.Mic, Modifier.weight(1f)) {}
                    ArrisShortcut("一起听", "分享同一个时刻", Icons.Default.People, Modifier.weight(1f)) {}
                }
                SongCard(song = song, onClick = {})
            }
        }
        compose.onNodeWithText("听歌识曲").assertIsDisplayed()
        screenshot("shared-components-light")
    }
    private fun player(dark: Boolean, scale: Float) {
        MotionPrefs.setUiMotionEnabled(compose.activity, false)
        show(dark, scale) {
            val density = LocalDensity.current
            BoxWithConstraints {
                PlayerCard(song = song, isPlaying = false, screenHeightPx = with(density) { maxHeight.toPx() },
                    progress = remember { Animatable(1f) }, totalDragDistancePx = with(density) { maxHeight.toPx() },
                    playbackQueue = listOf(song), currentQueueIndex = 0, playMode = 0,
                    callbacks = PlayerCardCallbacks(onPlayPause = {}, onDismiss = {}))
            }
        }
        val cover = compose.onNodeWithTag("player.cover", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("player.cover.title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Cover ${cover.bottom} crosses title ${title.top}", cover.bottom <= title.top)
        screenshot("player-${if (dark) "dark" else "light"}-scale-$scale")
    }
    @Test fun playerCoverAndTitleLight() = player(false, 1f)
    @Test fun playerCoverAndTitleLargeFont() = player(true, 1.5f)
}
