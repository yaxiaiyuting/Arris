/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.9：**QQ 音乐 App 扫码**登录浮层（可续期的那条路）。
 */

package com.takahashirinta.ncrust.ui.components

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.qq.QqApi
import com.takahashirinta.ncrust.qq.QqPhoneLogin
import com.takahashirinta.ncrust.qq.QqQrMqttSession
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 「QQ 音乐 App 扫码」登录浮层（v3.4.9）。
 *
 * ## 它与 [QqQrLoginDialog] 的**根本区别**（不是又一个扫码入口）
 *
 * | | [QqQrLoginDialog]（QQ 互联扫码） | 本浮层（QQ 音乐 App 扫码） |
 * |---|---|---|
 * | 谁扫 | 手机 **QQ** | 手机 **QQ 音乐 App** |
 * | 最后一步 | 交给 WebView，`y.qq.com` 换 `qqmusic_key` | 服务端 MQTT 推送 `qqmusic_key`，我们自己调 `Login` |
 * | 拿到的东西 | **cookie** | **完整凭证 JSON**（含 `refreshKey`） |
 * | 到期 | **要重新登录** | [com.takahashirinta.ncrust.qq.QqTokenRefresher] 自动续期 |
 *
 * 所以这个浮层存在的唯一理由是**可续期**（用户报障：一周左右掉登录）。
 * 界面文案必须让用户看出这个差别（`sourceQqScanNote`），否则他没有任何依据选哪一条。
 *
 * ## 流程
 *
 * ```
 * ① QqApi.requestScanQrCode()            → PNG + 91 字符 qrCodeID
 * ② QqQrMqttSession.open()               → WSS 连上 + CONNECT + SUBSCRIBE
 * ③ awaitEvent() 循环，同时按协商的 keep-alive 发 PINGREQ
 * ④ cookies 推送 → QqApi.loginWithScanCode() → cookie + 续期凭证落盘
 * ```
 *
 * ## 失败一律**回落而不是卡住**
 *
 * 与既有扫码路径同一条纪律：任何一步失败（取码失败 / 连不上 / 订阅被拒 / 超时 /
 * 推送里缺字段）都落到一个**用户能行动**的状态 —— 要么「刷新二维码」，
 * 要么「改用网页登录」。绝不出现「扫了码但界面永远停在等待」。
 */
@Composable
fun QqScanLoginDialog(
    /** 登录成功：cookie 已落盘（含续期凭证），调用方只需刷新界面状态。 */
    onLoggedIn: () -> Unit,
    /** 用户选择「改用网页登录」。 */
    onUseWebLogin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current

    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var status by remember { mutableStateOf(Status.LOADING) }
    var loadFailed by remember { mutableStateOf(false) }
    // 换一张二维码（或重试）时 +1，用来重启下面整条协程。
    var generation by remember { mutableIntStateOf(0) }

    LaunchedEffect(generation) {
        bitmap = null
        loadFailed = false
        status = Status.WAITING

        val code = QqApi.requestScanQrCode()
        if (code == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(code.png, 0, code.png.size)
        if (bmp == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        bitmap = bmp

        // MQTT 会话的生命周期**严格包在这个 try/finally 里**：
        // 协程被取消（用户关掉浮层、换一张码）时连接必须一起断，
        // 否则会留下一条没人消费推送、还在按 keep-alive 发心跳的连接。
        val session = QqQrMqttSession(
            qrCodeId = code.qrCodeId,
            clientId = clientId(),
        )
        try {
            if (!session.open()) {
                status = Status.FAILED
                return@LaunchedEffect
            }
            val deadline = System.currentTimeMillis() + QR_TTL_MS
            var lastPingAt = System.currentTimeMillis()
            val pingEveryMs = (session.keepAliveSeconds() * 1000L / 2L).coerceAtLeast(5_000L)

            while (isActive && System.currentTimeMillis() < deadline) {
                // PINGREQ 与「等推送」共用同一个循环：MQTT 的 keep-alive 是**应用层**的，
                // 不发 PINGREQ 服务端会按自己的时间判我们掉线（见 MqttWebSocketTransport 的 KDoc）。
                if (System.currentTimeMillis() - lastPingAt >= pingEveryMs) {
                    session.ping()
                    lastPingAt = System.currentTimeMillis()
                }
                val event = session.awaitEvent(EVENT_WAIT_MS)
                when (event) {
                    null -> Unit // 这段时间没有推送：回到循环顶部，可能该发心跳了
                    QqQrMqttSession.Event.Scanned -> status = Status.SCANNED
                    QqQrMqttSession.Event.Canceled -> {
                        status = Status.FAILED
                        return@LaunchedEffect
                    }
                    QqQrMqttSession.Event.Expired -> {
                        status = Status.EXPIRED
                        return@LaunchedEffect
                    }
                    is QqQrMqttSession.Event.Failed -> {
                        status = Status.FAILED
                        return@LaunchedEffect
                    }
                    is QqQrMqttSession.Event.Credentials -> {
                        // 换凭证 + 落盘（cookie 与续期凭证都由这一步负责）。
                        val attempt = QqApi.loginWithScanCode(
                            qrCodeId = code.qrCodeId,
                            uin = event.uin,
                            token = event.token,
                        )
                        if (attempt.outcome == QqPhoneLogin.LoginOutcome.OK && attempt.cookie != null) {
                            status = Status.DONE
                            onLoggedIn()
                        } else {
                            // 服务端明确回绝（受限/设备数超限/参数错）——重试同一张码没有意义。
                            status = Status.FAILED
                        }
                        return@LaunchedEffect
                    }
                }
            }
            // 循环退出 = 超过二维码寿命
            status = Status.EXPIRED
        } finally {
            session.close()
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
                text = strings.sourceQqScanTitle,
                style = TextStyle(fontSize = 18.sp),
                color = colors.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            // 二维码：源图约 178×178，放到 220dp。**必须关掉平滑缩放**
            // （`FilterQuality.None`）——插值会把模块边缘糊掉，扫描识别率明显下降
            // （与 QqQrLoginDialog 同一条实测经验）。
            Box(
                modifier = Modifier.size(220.dp).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp != null && status != Status.EXPIRED) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = strings.sourceQqScanTitle,
                        modifier = Modifier.size(220.dp),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    MetroText(
                        text = strings.sourceQrRefresh,
                        style = TextStyle(fontSize = 14.sp),
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            MetroText(
                text = when {
                    loadFailed -> strings.sourceQrLoadFailed
                    status == Status.EXPIRED -> strings.sourceQrExpired
                    status == Status.FAILED -> strings.sourceQrFailed
                    status == Status.SCANNED -> strings.sourceQrScanned
                    else -> strings.sourceQrWaiting
                },
                style = TextStyle(fontSize = 13.sp),
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            // 这条说明是**承重的**：它告诉用户为什么该选这一条而不是互联扫码。
            MetroText(
                text = strings.sourceQqScanNote,
                style = TextStyle(fontSize = 12.sp),
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            MetroButton(text = strings.sourceQrRefresh, onClick = { generation++ })
            Spacer(Modifier.height(8.dp))
            // 常驻降级入口：任何一步失败都还有路可走（与 QqQrLoginDialog 一致）。
            MetroButton(text = strings.sourceWebLogin, onClick = onUseWebLogin)
            Spacer(Modifier.height(8.dp))
            MetroButton(text = strings.close, onClick = onDismiss)
        }
    }
}

/** 浮层内部的六态。与 `QqQrLogin.QrStatus` 分开：这一条有「已换到凭证」这个终态。 */
private enum class Status { LOADING, WAITING, SCANNED, EXPIRED, FAILED, DONE }

/**
 * MQTT 客户端 id：**毫秒时间戳 + 4 位随机数**（与参考实现同一个形状）。
 *
 * 服务端把它当成这条连接的标识，重复的 id 会让它踢掉前一条连接 ——
 * 所以不能是常量，也不该复用。
 */
private fun clientId(): String =
    System.currentTimeMillis().toString() + (1000..9999).random().toString()

/** 二维码寿命。与既有扫码路径同一个量级（服务端 `expiresIn` 实测 900 秒，这里留出刷新余量）。 */
private const val QR_TTL_MS = 840_000L

/**
 * 单次「等推送」的窗口。
 *
 * 取 5 秒而不是「等到超时」：`awaitEvent` 会一直占着这条协程，
 * 而 keep-alive 的心跳要在这个协程里发（见循环注释）。
 * 5 秒的粒度对心跳（协商值的一半，约 22 秒）足够细。
 */
private const val EVENT_WAIT_MS = 5_000L
