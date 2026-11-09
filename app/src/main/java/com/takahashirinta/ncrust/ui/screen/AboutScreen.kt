/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - 「关于」页身份信息：开发者 / 许可证 / 项目地址改为「原作者 + fork」并列展示。
 *     原作者署名、开发人员名单与致谢一律保留，fork 信息为追加而非替换。
 */

package com.takahashirinta.ncrust.ui.screen

import com.takahashirinta.ncrust.ui.components.arrisAccentInk

import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.BuildConfig
import com.takahashirinta.ncrust.R
import com.takahashirinta.ncrust.ui.ResponsiveContent
import io.github.takahashirinta.kanesumi.anim.sokuou.MetroDefault
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import com.takahashirinta.ncrust.ui.components.TopScrimIconButton
import com.takahashirinta.ncrust.ui.components.arrisSurface
import com.takahashirinta.ncrust.ui.components.rememberManualUpdateCheck
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.update.UpdateService

// 版本号动态读取 BuildConfig.VERSION_NAME（对应 build.gradle.kts 的 versionName），
// 避免发版时忘记同步。
private val VERSION = "v${BuildConfig.VERSION_NAME}"

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenUpdate: () -> Unit = {},
    // 彩蛋「推荐一首」的播放入口（由调用方接到播放器）
    onPlaySong: (com.takahashirinta.ncrust.network.SongItem) -> Unit = {},
) {
    val s = LocalStrings.current
    val updateStrings = LocalStrings.current.update
    val accent = arrisAccentInk()
    // 关于页的手动检查与用户页横幅共用同一份逻辑；检查中禁止重复点。
    val updateState by UpdateService.state.collectAsState()
    val manualUpdateCheck = rememberManualUpdateCheck(onFound = onOpenUpdate)
    // v3.8.0 · 实验室开合状态。**必须放在最外层**：LabScreen 自己带 verticalScroll，
    // 若塞进关于页那个可滚动 Column 里就是滚动套滚动，Compose 会抛
    // IllegalStateException: Vertically scrollable component was measured with an
    // infinity maximum height constraints（真机第一次点开就闪退，logcat 已抓到）。
    var showLab by remember { mutableStateOf(false) }
    // 4.0.11：反馈弹层。诊断信息在这里就地采集（版本/机型/系统/语言主题/登录布尔），
    // 用户按下"发送"前能看到**将要发送的原文**。
    var showFeedback by remember { mutableStateOf(false) }
    BackHandler(enabled = !showLab) { onBack() }
    // Groove 无边框：不再套 M3 TopAppBar Surface，返回箭头浮在内容上方共用 TopScrimIconButton。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalMetroColors.current.background)
    ) {
        ResponsiveContent(maxWidth = 720.dp) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // 顶部预留 status bar + 80dp 让首元素落在 scrim 之下、返回箭头不会盖住 logo。
                    .padding(horizontal = 20.dp)
                    .statusBarsPadding()
                    .padding(top = 72.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 5 段级联：0/40/80/120/160ms 起，每段 200ms 淡入 + 8dp 微滑上。
                // stagger 窗 160ms（不超 200ms 约束），首元素 0ms 保证不"不跟手"。
                CascadeBlock(delayMillis = 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher),
                            contentDescription = "Arris",
                            modifier = Modifier.size(88.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.height(16.dp))
                        MetroText("Arris", color = LocalMetroColors.current.onBackground, style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp))
                        Spacer(Modifier.height(4.dp))
                        MetroText(VERSION, color = LocalMetroColors.current.onSurfaceVariant, style = TextStyle(fontSize = 12.sp, letterSpacing = 0.8.sp))
                        Spacer(Modifier.height(6.dp))
                        MetroText(s.aboutAppSubtitle, color = LocalMetroColors.current.onSurfaceVariant, style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp))
                        Spacer(Modifier.height(28.dp))
                    }
                }

                CascadeBlock(delayMillis = 40) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).arrisSurface().padding(16.dp)) {
                        AboutSection(s.aboutSectionProject.uppercase(), accent)
                        com.takahashirinta.ncrust.celebration.VersionEggRow(s.aboutVersion, VERSION, onPlaySong = onPlaySong)
                        // v3.4.13：手动检查更新（不受自动检查 24 小时窗口限制）。
                        // v3.8.0 · 临时诊断面：长按版本号会和生日彩蛋抢手势，改成可见入口。
                        // 功能上线后这一行与 LabScreen 一起删除。
                        AboutActionRow(
                            label = s.aboutLab,
                            value = "",
                            enabled = true,
                            onClick = { showLab = true },
                        )
                        AboutActionRow(
                            label = updateStrings.checkNow,
                            value = if (updateState.checking) updateStrings.checking else "",
                            enabled = !updateState.checking,
                            onClick = manualUpdateCheck
                        )
                        // 4.0.11：发送错误日志 / 提建议。走自有中转 → Gitee 仓库开 issue
                        // （客户端不放任何令牌，理由见 FeedbackClient 的 KDoc）。
                        AboutActionRow(
                            label = "发送反馈",
                            value = "错误日志 / 建议",
                            enabled = true,
                            onClick = { showFeedback = true },
                        )
                        AboutRow(s.aboutDeveloperOriginal, "Takahashi_Rinta")
                        AboutRow(s.aboutDeveloperFork, "yaxiaiyuting")
                        AboutRow(s.aboutLicense, s.aboutLicenseGplWithMit, stacked = true)
                        AboutRow(s.aboutRepositoryFork, "github.com/yaxiaiyuting/Arris", stacked = true)
                        AboutRow(s.aboutRepositoryOriginal, "github.com/GuitaristRin/Ncrust", stacked = true)
                    }
                }

                CascadeBlock(delayMillis = 80) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).arrisSurface().padding(16.dp)) {
                        AboutSection(s.aboutSectionTechStack.uppercase(), accent)
                        AboutRow(s.aboutLangLabel, "Kotlin")
                        AboutRow(s.aboutUIFrameworkLabel, "Jetpack Compose")
                        AboutRow(s.aboutDesignSystemLabel, "Kanesumi")
                        AboutRow(s.aboutAudioEngineLabel, "Media3 ExoPlayer")
                        AboutRow(s.aboutNetworkLabel, "Retrofit + OkHttp")
                        AboutRow(s.aboutImageLabel, "Coil")
                    }
                }

                CascadeBlock(delayMillis = 120) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).arrisSurface().padding(16.dp)) {
                        AboutSection(s.aboutSectionTeam.uppercase(), accent)
                        AboutRow(s.aboutRoleDev, "Takahashi_Rinta")
                        AboutRow(s.aboutRoleTester, "白给小子")
                        AboutRow(s.aboutRoleForkMaintainer, "yaxiaiyuting")
                    }
                }

                CascadeBlock(delayMillis = 160) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp).arrisSurface().padding(16.dp)) {
                        AboutSection(s.aboutSectionCredits.uppercase(), accent)
                        // 4.0.2：名单重写 —— 去掉那些"列了但其实没发挥作用的"，
                        // 换成真参考过/借鉴过/在上游继续往前走了的东西。
                        // 角色名用字面量，不占 8 套语言的键位。
                        AboutRow("歌词与播放器参考", "NeriPlayer 音理 (GPL-3.0)", stacked = true)
                        AboutRow("播放引擎", "AndroidX Media3 / ExoPlayer", stacked = true)
                        AboutRow("接口逻辑参考", "Suxiaoqinx/Netease_url (MIT)", stacked = true)
                        AboutRow("设计参考", "Apple Music for Android", stacked = true)
                        AboutRow("Tux", "Larry Ewing / The GIMP · Arris", stacked = true)
                        // 4.0.2：致谢不是只写给项目名的 —— 也写给真正在用它的每一个人。
                        AboutRow("致谢", "quit")
                        AboutRow("还有", "每一位屏幕前的使用者")
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }

        TopScrimIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = s.back,
            onClick = onBack,
            overArtwork = false
        )
        if (showFeedback) {
        com.takahashirinta.ncrust.ui.components.FeedbackDialog(
            language = com.takahashirinta.ncrust.ui.i18n.currentLanguageCode(),
            // 主题：跟随系统时记 system(+实际明暗)，否则记实际取值 —— 够定位"深色下才复现"这类问题
            themeMode = run {
                val mode = com.takahashirinta.ncrust.ui.theme.getSavedThemeMode(LocalContext.current)
                when (mode) {
                    com.takahashirinta.ncrust.ui.theme.ThemeMode.SYSTEM ->
                        if (androidx.compose.foundation.isSystemInDarkTheme()) "system-dark" else "system-light"
                    com.takahashirinta.ncrust.ui.theme.ThemeMode.DARK -> "dark"
                    com.takahashirinta.ncrust.ui.theme.ThemeMode.LIGHT -> "light"
                }
            },
            // 音质档：直接读设置里的 Wi-Fi 档位（缺省 -1 = 未知），不做单位换算
            qualityIndex = LocalContext.current
                .getSharedPreferences("ncrust_settings", android.content.Context.MODE_PRIVATE)
                .getInt(com.takahashirinta.ncrust.player.QualityLadder.KEY_WIFI, -1),
            loggedInNcm = com.takahashirinta.ncrust.auth.CookieManager.isLoggedIn(LocalContext.current),
            loggedInQq = com.takahashirinta.ncrust.qq.QqAuthStore.isLoggedIn(LocalContext.current),
            onDismiss = { showFeedback = false },
        )
    }
    if (showLab) {
            LabScreen(onClose = { showLab = false })
        }
    }
}

// 关于页专用的级联入场包装：每块内容独立 MutableTransitionState + AnimatedVisibility，
// 通过 delayMillis 拉开时序。首块传 0 即立即出现，符合"首元素 0ms"约束。
@Composable
private fun CascadeBlock(delayMillis: Int, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val slideOffsetPx = with(density) { 8.dp.roundToPx() }
    val visibleState = remember {
        MutableTransitionState(false).apply { targetState = true }
    }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(
            animationSpec = tween(200, delayMillis = delayMillis, easing = MetroDefault)
        ) + slideInVertically(
            animationSpec = tween(200, delayMillis = delayMillis, easing = MetroDefault),
            initialOffsetY = { slideOffsetPx }
        )
    ) { content() }
}

@Composable
private fun AboutSection(title: String, accent: Color) {
    MetroText(
        title,
        color = accent,
        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
    )
}

@Composable
private fun AboutRow(label: String, value: String, stacked: Boolean = false) {
    if (stacked) {
        Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
            MetroText(label, color = LocalMetroColors.current.onSurfaceVariant,
                style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp))
            Spacer(Modifier.height(4.dp))
            MetroText(value, color = LocalMetroColors.current.onBackground,
                style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp))
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroText(
            label,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
            modifier = Modifier.weight(1f)
        )
        MetroText(
            value,
            color = LocalMetroColors.current.onBackground,
            style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.End),
            modifier = Modifier.weight(1.5f)
        )
    }
}

/**
 * 可点的 About 行：右侧值用主题色，整行可点。
 * 与 [AboutRow] 同排版，差别只在「这是一个动作」而不是一条只读信息。
 */
@Composable
private fun AboutActionRow(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroText(
            label,
            color = arrisAccentInk(),
            style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
            modifier = Modifier.weight(1f)
        )
        if (value.isNotEmpty()) {
            MetroText(
                value,
                color = LocalMetroColors.current.onSurfaceVariant,
                style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, textAlign = TextAlign.End),
                modifier = Modifier.weight(1.5f)
            )
        }
    }
}
