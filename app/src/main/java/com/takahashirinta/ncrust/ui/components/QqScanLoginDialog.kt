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
import androidx.compose.runtime.collectAsState
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
import com.takahashirinta.ncrust.qq.QqScanSession
import com.takahashirinta.ncrust.qq.QqPhoneLogin
import com.takahashirinta.ncrust.qq.QqQrMqttSession
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.controls.MetroDialog
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
    /** 用户选择「改用网页登录」。 */
    onUseWebLogin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current

    // 状态**不放在 composition 里**：扫码那一刻 Arris 必须在后台（QQ 音乐要占前台/开小窗），
    // 原来的 LaunchedEffect + remember 会在切后台/Activity 重建时被销毁，
    // 连带把还在等确认的 MQTT 连接一起关掉 —— 表现就是「扫了、确认了，回来没登录」。
    // 现在整条会话活在 QqScanSession（进程级），这里只负责渲染它的 StateFlow。
    val state by QqScanSession.state.collectAsState()
    val bitmap = state.bitmap
    val status = remember(state.status) { state.status.toUiStatus() }
    val loadFailed = state.loadFailed

    // 打开浮层就确保会话在跑；已经在跑就复用（切后台再回来不换二维码）。
    LaunchedEffect(Unit) { QqScanSession.ensureStarted() }

    MetroDialog(onDismissRequest = { QqScanSession.stop(); onDismiss() }) {
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

            MetroButton(text = strings.sourceQrRefresh, onClick = { QqScanSession.restart() })
            Spacer(Modifier.height(8.dp))
            // 常驻降级入口：任何一步失败都还有路可走（与 QqQrLoginDialog 一致）。
            MetroButton(text = strings.sourceWebLogin, onClick = { QqScanSession.stop(); onUseWebLogin() })
            Spacer(Modifier.height(8.dp))
            MetroButton(text = strings.close, onClick = { QqScanSession.stop(); onDismiss() })
        }
    }
}

/** 浮层内部的六态。与 `QqQrLogin.QrStatus` 分开：这一条有「已换到凭证」这个终态。 */
private enum class Status { LOADING, WAITING, SCANNED, EXPIRED, FAILED, DONE }

/** 会话状态 → 界面状态。两边分开是为了让 UI 不依赖会话内部的枚举顺序。 */
private fun QqScanSession.Status.toUiStatus(): Status = when (this) {
    QqScanSession.Status.LOADING -> Status.LOADING
    QqScanSession.Status.WAITING -> Status.WAITING
    QqScanSession.Status.SCANNED -> Status.SCANNED
    QqScanSession.Status.EXPIRED -> Status.EXPIRED
    QqScanSession.Status.FAILED -> Status.FAILED
    QqScanSession.Status.DONE -> Status.DONE
}
