/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P1：B 站扫码登录的**界面外壳**（铁律 23：复用现有扫码登录框架）。
 */

package com.takahashirinta.ncrust.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.bili.BiliAuthStore
import com.takahashirinta.ncrust.bili.BiliQrLogin
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import io.github.takahashirinta.kanesumi.controls.MetroDialog

/**
 * B 站扫码登录浮层（v3.2.0 · P1）。
 *
 * ## 复用而不是新建（铁律 23）
 *
 * 界面外壳、二维码渲染、状态文案都与 [QrLoginDialog] / [QqLoginOverlay] 同形：
 * 同一个 ZXing 渲染函数（[generateQrBitmap]，本版把它从 `private` 放宽到 `internal`
 * 就是为了**复用而不是复制第二份**）、同一个「状态 → 一句文案」的映射、
 * 同一个「失效后给刷新按钮」的出口。**没有第二套二维码渲染器、没有第二套登录弹窗。**
 *
 * 状态机本身在 [BiliQrLogin]（纯逻辑、JVM 可测），本文件只做三件事：
 * 1. 按状态渲染（[BiliQrLogin.State] → 文案 + 二维码 + 刷新按钮）；
 * 2. 在 [BiliQrLogin.State.CONFIRMED] 时调 `finishLogin` 落盘并回调 [onLoggedIn]；
 * 3. 关掉浮层时 `cancel()` —— **取消不是失败**（不要写 `FAILED` 文案）。
 *
 * ## 为什么二维码内容直接是 URL
 *
 * B 站的 `generate` 返回的 `data.url` **就是二维码要编码的内容**（一个 URL 字符串，
 * 不是 PNG/base64）—— 这一点与网易云/QQ 两侧都不同，所以这里不需要
 * `decodeQrImage` 那条分支。证据见 `docs/verification/v3.2.0/probe-bili-login.md`。
 *
 * ## 必须显示的风险提示
 *
 * 登录后请求会带上用户自己的 B 站账号身份（v3.1.0 当初不做登录的顾虑之一）。
 * 探针文档 §7 把「只读接口 + 凭据不出机 + UI 必须如实告知」定成条件，
 * 所以 [BiliAuthStore] 的 `biliLoginRiskNote` 在浮层里**始终可见**，
 * 不做折叠、不做「了解更多」。
 */
@Composable
fun BiliQrLoginDialog(
    onLoggedIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val typography = LocalMetroTypography.current
    val context = LocalContext.current

    val login = remember { BiliQrLogin() }
    var state by remember { mutableStateOf(BiliQrLogin.State.IDLE) }
    // 刷新二维码 = 换一个 generation，`LaunchedEffect` 会重跑一次完整的 start()。
    var generation by remember { mutableStateOf(0) }
    // 二维码位图按 generation 缓存：`generateQrBitmap` 是逐像素 setPixel（512×512），
    // 每次重组都重算一次是纯浪费，而同一张码的内容在整个 generation 里不变。
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(generation) {
        qrBitmap = null
        // `start` 在 CONFIRMED / EXPIRED / FAILED / CANCELLED 时返回，不会永远挂着
        // （有界轮询的三条上限都在状态机里：总时长 180s / 间隔 2s / 次数 90）。
        state = login.start { state = it }
        login.qrCode?.url?.let { qrBitmap = generateQrBitmap(it) }
    }

    // CONFIRMED 只处理一次（每次状态变化重组都会经过这里）。
    LaunchedEffect(state) {
        if (state == BiliQrLogin.State.CONFIRMED && !finished) {
            finished = true
            login.finishLogin(context)
            onLoggedIn()
        }
    }

    DisposableEffect(Unit) {
        onDispose { login.cancel() }
    }

    MetroDialog(onDismissRequest = onDismiss, widthDp = 300.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MetroText(strings.biliLoginTitle, style = typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            MetroText(
                text = strings.sourceBiliAccount,
                style = typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            // 二维码区固定 220dp：状态切换**不改变布局**（否则文案变化会带着二维码跳一下）。
            Box(
                modifier = Modifier.size(220.dp).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = qrBitmap
                val usable = bmp != null &&
                    state != BiliQrLogin.State.EXPIRED &&
                    state != BiliQrLogin.State.FAILED
                if (usable) {
                    Image(
                        bitmap = bmp!!.asImageBitmap(),
                        contentDescription = strings.biliLoginTitle,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    MetroText(
                        text = if (state == BiliQrLogin.State.LOADING) {
                            strings.searchSourcePending
                        } else {
                            strings.biliLoginExpired
                        },
                        style = TextStyle(fontSize = 14.sp),
                        color = Color.Black,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            MetroText(
                text = when (state) {
                    BiliQrLogin.State.IDLE, BiliQrLogin.State.WAITING -> strings.biliLoginWaiting
                    BiliQrLogin.State.LOADING -> strings.searchSourcePending
                    BiliQrLogin.State.SCANNED -> strings.biliLoginScanned
                    BiliQrLogin.State.CONFIRMED ->
                        strings.biliLoginSuccess(BiliAuthStore.uname(context).orEmpty())

                    BiliQrLogin.State.EXPIRED -> strings.biliLoginExpired
                    // 失败与取消**分开**：取消不是失败，也不用催用户「重试」。
                    BiliQrLogin.State.FAILED -> strings.biliLoginFailed
                    BiliQrLogin.State.CANCELLED -> strings.biliLoginWaiting
                },
                style = typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )

            // 只有「失效 / 失败」给刷新按钮 —— 等待中给一个刷新键会让用户以为二维码坏了。
            if (state == BiliQrLogin.State.EXPIRED || state == BiliQrLogin.State.FAILED) {
                Spacer(Modifier.height(12.dp))
                MetroText(
                    text = strings.biliLoginRefresh,
                    style = typography.bodyLarge,
                    color = colors.primary,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            login.reset()
                            generation++
                        }
                        .padding(8.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            // 风险提示：始终可见（见 KDoc 最后一段）。
            MetroText(
                text = strings.biliLoginRiskNote,
                style = typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            // 音质承诺：**如实**写「未验证」，而不是「登录可得无损」。
            MetroText(
                text = strings.biliQualityNote,
                style = typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            MetroText(
                text = strings.cancel,
                style = typography.bodyLarge,
                color = colors.primary,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        login.cancel()
                        onDismiss()
                    }
                    .padding(8.dp),
            )
        }
    }
}
