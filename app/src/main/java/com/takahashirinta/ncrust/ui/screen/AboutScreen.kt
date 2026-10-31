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
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import com.takahashirinta.ncrust.ui.components.TopScrimIconButton
import com.takahashirinta.ncrust.ui.components.rememberManualUpdateCheck
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.update.UpdateService

// 版本号动态读取 BuildConfig.VERSION_NAME（对应 build.gradle.kts 的 versionName），
// 避免发版时忘记同步。
private val VERSION = "v${BuildConfig.VERSION_NAME}"

@Composable
fun AboutScreen(onBack: () -> Unit, onOpenUpdate: () -> Unit = {}) {
    val s = LocalStrings.current
    val updateStrings = LocalStrings.current.update
    val accent = LocalMetroColors.current.primary
    // 关于页的手动检查与用户页横幅共用同一份逻辑；检查中禁止重复点。
    val updateState by UpdateService.state.collectAsState()
    val manualUpdateCheck = rememberManualUpdateCheck(onFound = onOpenUpdate)
    // v3.8.0 · 实验室开合状态。**必须放在最外层**：LabScreen 自己带 verticalScroll，
    // 若塞进关于页那个可滚动 Column 里就是滚动套滚动，Compose 会抛
    // IllegalStateException: Vertically scrollable component was measured with an
    // infinity maximum height constraints（真机第一次点开就闪退，logcat 已抓到）。
    var showLab by remember { mutableStateOf(false) }
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
                    .padding(horizontal = 24.dp)
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
                            modifier = Modifier.size(80.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.height(16.dp))
                        MetroText("Arris", color = accent, style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold))
                        MetroText(VERSION, color = accent.copy(alpha = 0.72f), style = TextStyle(fontSize = 14.sp))
                        Spacer(Modifier.height(6.dp))
                        MetroText(s.aboutAppSubtitle, color = LocalMetroColors.current.onSurfaceVariant, style = TextStyle(fontSize = 13.sp))
                        Spacer(Modifier.height(28.dp))
                        MetroDivider(color = LocalMetroColors.current.divider)
                        Spacer(Modifier.height(20.dp))
                    }
                }

                CascadeBlock(delayMillis = 40) {
                    Column {
                        AboutSection(s.aboutSectionProject.uppercase(), accent)
                        com.takahashirinta.ncrust.celebration.VersionEggRow(s.aboutVersion, VERSION)
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
                        AboutRow(s.aboutDeveloperOriginal, "Takahashi_Rinta")
                        AboutRow(s.aboutDeveloperFork, "yaxiaiyuting")
                        AboutRow(s.aboutLicense, s.aboutLicenseGplWithMit)
                        AboutRow(s.aboutRepositoryFork, "github.com/yaxiaiyuting/Arris")
                        AboutRow(s.aboutRepositoryOriginal, "github.com/GuitaristRin/Ncrust")
                        Spacer(Modifier.height(20.dp))
                    }
                }

                CascadeBlock(delayMillis = 80) {
                    Column {
                        AboutSection(s.aboutSectionTechStack.uppercase(), accent)
                        AboutRow(s.aboutLangLabel, "Kotlin")
                        AboutRow(s.aboutUIFrameworkLabel, "Jetpack Compose")
                        AboutRow(s.aboutDesignSystemLabel, "Kanesumi")
                        AboutRow(s.aboutAudioEngineLabel, "Media3 ExoPlayer")
                        AboutRow(s.aboutNetworkLabel, "Retrofit + OkHttp")
                        AboutRow(s.aboutImageLabel, "Coil")
                        Spacer(Modifier.height(20.dp))
                    }
                }

                CascadeBlock(delayMillis = 120) {
                    Column {
                        AboutSection(s.aboutSectionTeam.uppercase(), accent)
                        AboutRow(s.aboutRoleDev, "Takahashi_Rinta")
                        AboutRow(s.aboutRoleTester, "白给小子")
                        AboutRow(s.aboutRoleForkMaintainer, "yaxiaiyuting")
                        Spacer(Modifier.height(20.dp))
                    }
                }

                CascadeBlock(delayMillis = 160) {
                    Column {
                        AboutSection(s.aboutSectionCredits.uppercase(), accent)
                        AboutRow(s.aboutCreditCli, "Suxiaoqinx/Netease_url (MIT)")
                        AboutRow(s.aboutCreditAnim, "SaltPlayerSource")
                        AboutRow(s.aboutCreditDesign, "Apple Music for Android")
                        AboutRow("Tux", "Larry Ewing / The GIMP · Arris")
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }

        TopScrimIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = s.back,
            onClick = onBack
        )
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
            .padding(bottom = 10.dp)
    )
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroText(
            label,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = TextStyle(fontSize = 13.sp),
            modifier = Modifier.weight(1f)
        )
        MetroText(
            value,
            color = LocalMetroColors.current.onBackground,
            style = TextStyle(fontSize = 13.sp, textAlign = TextAlign.End),
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
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroText(
            label,
            color = LocalMetroColors.current.primary,
            style = TextStyle(fontSize = 13.sp),
            modifier = Modifier.weight(1f)
        )
        MetroText(
            value,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = TextStyle(fontSize = 13.sp, textAlign = TextAlign.End),
            modifier = Modifier.weight(1.5f)
        )
    }
}
