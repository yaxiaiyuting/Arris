/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · C（hotfix 4）：qm 自绘二维码登录。
 */

package com.takahashirinta.ncrust.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.takahashirinta.ncrust.qq.QqQrClient
import com.takahashirinta.ncrust.qq.QqQrLogin
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay

/**
 * qm 登录：**自绘二维码**（v2.1.0 · C）。
 *
 * ## 流程（两步纯 HTTP + 一步复用已验证的 WebView）
 *
 * 1. `ptqrshow` 取二维码 PNG 与 `qrsig`（[QqQrClient.requestQr]）；
 * 2. `ptqrlogin` 长轮询扫码状态（[QqQrClient.poll]），直到 `ptuiCB` 给出 `0` + 跳转地址；
 * 3. 把那个跳转地址交给 WebView 打开 —— `check_sig` 会下发 `.qq.com` 的登录 cookie，
 *    随后 `y.qq.com` 自己的脚本完成**音乐票据**（`qqmusic_key`）的交换。
 *
 * **为什么第 3 步不自己用 HTTP 做完**：换音乐票据那一步（`QQConnectLogin.LoginServer.QQLogin`
 * 之类）需要扫码方的 OAuth code，本仓库**没有账号可以实测**，参数形状属于「有依据的推测」。
 * 而「在 WebView 里打开站点让它自己换」这条路**已被真机验证可登录**（用户实测反馈）。
 * 与其赌一个没验证过的请求形状，不如把最后一步交给已经证明能用的机制 ——
 * 用户的体验是一样的（扫完码自动完成），但我们不引入未验证的假设。
 *
 * ## 失败时的降级
 *
 * 轮询的两种失败**分开处理**（v2.1.1）：
 * - **服务端拒绝**（403 / 空 body / 不是 `ptuiCB` 的 200）→ 重试没有意义。累计到
 *   [DEGRADE_AFTER_UNAVAILABLE] 次就停止轮询、说明原因，然后**自动切到已经验证可用的
 *   网页登录**（不让用户自己去找那个按钮）。
 * - **真的网络异常**（超时、连接被断）→ 这是抖动，继续重试，只把提示写清楚。
 *
 * v2.1.0 把两者都写成「网络不稳定，仍在重试…」，于是一个恒定的 403 被显示成网络问题、
 * 还每 2 秒重试一次连试 14 分钟 —— 这正是用户报告的现象。
 */
@Composable
fun QqQrLoginDialog(
    /** 扫码确认成功：把跳转地址与已拿到的 cookie 交给调用方去完成登录。 */
    onConfirmed: (url: String, cookies: String) -> Unit,
    /** 用户选择「改用网页登录」。 */
    onUseWebLogin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var qr by remember { mutableStateOf<QqQrClient.QrCode?>(null) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var status by remember { mutableStateOf(QqQrLogin.QrStatus.WAITING) }
    var loadFailed by remember { mutableStateOf(false) }
    // 换一张二维码时 +1，用来重启下面的轮询协程。
    var generation by remember { mutableStateOf(0) }
    var resumeTrigger by remember { mutableIntStateOf(0) }
    var isForeground by remember {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    // 连续「服务端拒绝」次数（403/空 body/解析不出 ptuiCB）与连续「网络异常」次数。
    // 两者分开计数：前者重试无意义、到阈值就降级；后者是抖动、继续重试。
    var unavailableStreak by remember { mutableStateOf(0) }
    var networkStreak by remember { mutableStateOf(0) }
    val degraded = unavailableStreak >= DEGRADE_AFTER_UNAVAILABLE

    // 监听生命周期：用户切到 QQ/相册扫码确认后回到应用时，立刻发起一次轮询，并重置后台造成的被拒/超时抖动
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isForeground = true
                unavailableStreak = 0
                resumeTrigger++
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                isForeground = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // 取二维码
    LaunchedEffect(generation) {
        qr = null
        bitmap = null
        loadFailed = false
        status = QqQrLogin.QrStatus.WAITING
        unavailableStreak = 0
        networkStreak = 0
        val code = QqQrClient.requestQr()
        if (code == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        qr = code
        bitmap = BitmapFactory.decodeByteArray(code.png, 0, code.png.size)
        if (bitmap == null) loadFailed = true
    }

    // 轮询：进入页面或从后台切回前台（resumeTrigger 变化）时立刻发起一次探测
    LaunchedEffect(qr, generation, resumeTrigger) {
        val code = qr ?: return@LaunchedEffect
        val deadline = System.currentTimeMillis() + QqQrLogin.QR_TTL_SECONDS * 1000L
        while (System.currentTimeMillis() < deadline) {
            when (val result = QqQrClient.poll(code.qrsig)) {
                is QqQrLogin.PollResult.Status -> {
                    unavailableStreak = 0
                    networkStreak = 0
                    val cb = result.cb
                    status = cb.status
                    when (cb.status) {
                        QqQrLogin.QrStatus.CONFIRMED -> {
                            val url = cb.url
                            if (url.isNullOrEmpty()) {
                                // 确认成功却没给跳转地址：这在协议里不该发生，按失败处理而不是静默卡住
                                status = QqQrLogin.QrStatus.FAILED
                            } else {
                                onConfirmed(url, QqQrClient.cookies())
                            }
                            return@LaunchedEffect
                        }
                        QqQrLogin.QrStatus.EXPIRED, QqQrLogin.QrStatus.FAILED -> return@LaunchedEffect
                        else -> Unit // WAITING / SCANNED 继续轮询
                    }
                }
                is QqQrLogin.PollResult.Unavailable -> {
                    // 仅在前台活跃时计入回绝次数；后台休眠/网络受限时不误判降级
                    if (isForeground) {
                        unavailableStreak++
                        networkStreak = 0
                        if (unavailableStreak >= DEGRADE_AFTER_UNAVAILABLE) return@LaunchedEffect
                    }
                }
                QqQrLogin.PollResult.NetworkError -> {
                    networkStreak++
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        // 超时：让用户能刷新，而不是永远停在「等待扫码」
        status = QqQrLogin.QrStatus.EXPIRED
    }

    // 自动降级：连续被拒到阈值 → 先让用户看清原因，再切到已实测可用的网页登录
    LaunchedEffect(degraded) {
        if (degraded) {
            delay(DEGRADE_NOTICE_MS)
            onUseWebLogin()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(colors.surface)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MetroText(
                text = strings.sourceQqAccount,
                style = androidx.compose.ui.text.TextStyle(fontSize = 18.sp),
                color = colors.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            // 二维码。222×222 的源图放到 220dp：**必须关掉平滑缩放**（FilterQuality.None），
            // 否则插值会把二维码的模块边缘糊掉，扫描识别率明显下降。
            Box(
                modifier = Modifier.size(220.dp).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp != null && status != QqQrLogin.QrStatus.EXPIRED) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = strings.sourceQqLoginAction,
                        modifier = Modifier.size(220.dp),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    MetroText(
                        text = strings.sourceQrRefresh,
                        style = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            MetroText(
                text = when {
                    loadFailed -> strings.sourceQrLoadFailed
                    // 降级中的提示优先于状态：「服务不可用」比「等待扫码」更该被看见
                    degraded -> strings.sourceQrSwitchingToWeb
                    unavailableStreak > 0 -> strings.sourceQrServiceUnavailable
                    status == QqQrLogin.QrStatus.EXPIRED -> strings.sourceQrExpired
                    status == QqQrLogin.QrStatus.FAILED -> strings.sourceQrFailed
                    status == QqQrLogin.QrStatus.SCANNED -> strings.sourceQrScanned
                    networkStreak >= NETWORK_HINT_AFTER -> strings.sourceQrNetworkHint
                    else -> strings.sourceQrWaiting
                },
                style = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            MetroButton(text = strings.sourceQrRefresh, onClick = { generation++ })
            Spacer(Modifier.height(8.dp))
            // 常驻的降级入口：轮询在部分出口 IP 上会被 WAF 拦（本机就是），
            // 用户不该因为这条路不通就完全登不上。
            MetroButton(text = strings.sourceWebLogin, onClick = onUseWebLogin)
            Spacer(Modifier.height(8.dp))
            MetroButton(text = strings.close, onClick = onDismiss)
        }
    }
}

/** 轮询间隔。2 秒：与人扫码确认的节奏相称，又不会把服务端打得太勤。 */
private const val POLL_INTERVAL_MS = 2_000L

/**
 * 连续被服务端回绝多少次就自动降级到网页登录。
 *
 * 15 次 ≈ 30 秒：必须保证用户在同一台手机切屏到 QQ 扫描相册/相机确认时有充分的等待窗口，
 * 避免因后台网络休眠抖动误触降级。
 */
private const val DEGRADE_AFTER_UNAVAILABLE = 15

/** 降级提示至少显示这么久，再切页面 —— 否则用户只看到界面闪一下，不知道发生了什么。 */
private const val DEGRADE_NOTICE_MS = 2_000L

/** 连续多少次「真的网络异常」才提示「网络不稳定」（单次抖动不值得吓用户）。 */
private const val NETWORK_HINT_AFTER = 3
