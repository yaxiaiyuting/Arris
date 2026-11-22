/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v4.2.0：后台播放保护引导页（设置 → 通用 → 后台播放保护）。
 */

package com.takahashirinta.ncrust.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.takahashirinta.ncrust.power.BackgroundActivity
import com.takahashirinta.ncrust.power.OemBackgroundActions
import com.takahashirinta.ncrust.power.OemBackgroundGuide
import com.takahashirinta.ncrust.ui.components.ArrisButton
import com.takahashirinta.ncrust.ui.components.SectionTitle
import com.takahashirinta.ncrust.ui.components.arrisSurface
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.AppShapes
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 后台播放保护引导页。
 *
 * ## 为什么是全屏 Dialog 而不是导航页
 *
 * 设置二级页已经占着导航栈的一个位置，再给引导页开一条路由需要动 `NavRoutes` /
 * `MainNavGraph`（三个文件 + 转场），而这一页只有「看完就关」一种退出方式。
 * 本仓库已有同类做法：`OfflineCacheOverlay`（存储页的离线缓存管理）就是全屏 Dialog。
 * 备选方案：若后续需要从通知/播放器卡片直达本页，再抽成独立路由。
 *
 * ## 三块内容各自的作用
 *
 * 1. **状态区**：AOSP 层的电池优化白名单。这是唯一有系统 API 可读的状态
 *    （`PowerManager.isIgnoringBatteryOptimizations`），所以它必须显示**真实状态**，
 *    而不是一个不带反馈的按钮 —— 已在白名单时点授权框会让系统页启动即 finish，
 *    屏幕上什么都不发生，用户只能判定「点了没反应」。
 * 2. **厂商区**：按 [OemBackgroundActions.currentProfile] 识别出的档位给三步说明 + 深链。
 *    识别与候选表都在 `power/OemBackgroundGuide.kt`（纯函数 + 数据表，单测覆盖）。
 * 3. **最近任务**：系统「一键清理」会连最近任务卡片一起清掉，锁卡是**唯一**能挡住它的
 *    用户侧操作；手势因 ROM 而异，所以文案按档位取（见 `BackgroundGuideStrings`）。
 *
 * ## 从系统页回来要重读状态
 *
 * 与设置页那一行同一个理由（K08）：跳去系统设置点「允许」再返回，本页若不重读，
 * 会继续显示「未允许」。观察者只在本页挂载期间存在，弹窗关闭即注销。
 */
@Composable
internal fun BackgroundGuideOverlay(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val texts = strings.settings.backgroundGuide
    val metro = LocalMetroColors.current

    val profile = remember { OemBackgroundActions.currentProfile() }
    val guide = remember(profile, texts) { OemBackgroundGuide.guideOf(profile, texts) }
    var unrestricted by remember { mutableStateOf(BackgroundActivity.isUnrestricted(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                unrestricted = BackgroundActivity.isUnrestricted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalNcrustColors.current.background)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // 顶栏固定在外层（滚动区里只有一个滚动容器，标题不会跟着走）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroText(
                    texts.pageTitle,
                    color = metro.onBackground,
                    style = LocalMetroTypography.current.pageHeading,
                    modifier = Modifier.weight(1f)
                )
                // 触摸契约（AGENTS.md 触摸陷阱第 7 条）：视觉 22dp，命中盒 48dp，
                // 并带 contentDescription —— 关闭键是这个全屏页唯一的出口。
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(onClick = onDismiss)
                        .semantics { contentDescription = strings.close },
                    contentAlignment = Alignment.Center
                ) {
                    MetroIcon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = metro.onBackground,
                        sizeDp = 22.dp,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                SectionTitle(texts.statusSection)
                GuideCard {
                    MetroText(
                        if (unrestricted) strings.batteryStatusAllowed else strings.batteryStatusDenied,
                        color = if (unrestricted) metro.primary else metro.onSurfaceVariant,
                        style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                    )
                    if (!unrestricted) {
                        Spacer(Modifier.height(12.dp))
                        ArrisButton(
                            text = texts.requestOptimize,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val launched = OemBackgroundActions.openBatteryOptimizationSettings(context)
                                if (!launched) {
                                    Toast.makeText(context, strings.batteryJumpFailed, Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }

                SectionTitle(texts.oemSection(profile.displayName))
                GuideCard {
                    guide.steps.forEachIndexed { index, step ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            MetroText(
                                "${index + 1}",
                                color = metro.primary,
                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
                                modifier = Modifier.width(20.dp),
                            )
                            MetroText(
                                step,
                                color = metro.onBackground,
                                style = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (index != guide.steps.lastIndex) Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    ArrisButton(
                        text = texts.openSettings,
                        modifier = Modifier.fillMaxWidth(),
                        primary = false,
                        onClick = {
                            val launched = OemBackgroundActions.openStartupSettings(context, profile)
                            if (!launched) {
                                Toast.makeText(context, strings.batteryJumpFailed, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }

                SectionTitle(texts.recentsSection)
                GuideCard {
                    MetroText(
                        texts.recentsIntro,
                        color = metro.onSurfaceVariant,
                        style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
                    )
                    Spacer(Modifier.height(8.dp))
                    MetroText(
                        guide.recents,
                        color = metro.onBackground,
                        style = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * 引导页里的一块内容面：Kanesumi 的薄表面 + `AppShapes.large` 圆角。
 *
 * 抽成一个本地组合函数而不是直接写 `Modifier.arrisSurface(...)`：
 * 三块内容的边距必须完全一致，写在三处迟早会有一处被改歪。
 */
@Composable
private fun GuideCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .arrisSurface(AppShapes.large)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        content()
    }
}
