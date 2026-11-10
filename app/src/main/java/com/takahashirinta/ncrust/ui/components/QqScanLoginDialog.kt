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

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.qq.QqScanSession
import com.takahashirinta.ncrust.qq.QqScanSession.Status
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/** 展示进程持有的扫码会话。页面重建时沿用同一张二维码。 */
@Composable
internal fun QqScanLoginDialog(
    state: QqScanSession.State,
    onRefresh: () -> Unit,
    onUseWebLogin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    BackHandler(onBack = onDismiss)

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
                val bmp = state.bitmap
                if (bmp != null && state.status != Status.EXPIRED) {
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
                text = when (state.status) {
                    Status.QR_UNAVAILABLE -> strings.sourceQrLoadFailed
                    Status.EXPIRED -> strings.sourceQrExpired
                    Status.FAILED -> strings.sourceQrFailed
                    Status.SCANNED -> strings.sourceQrScanned
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

            MetroButton(text = strings.sourceQrRefresh, onClick = onRefresh)
            Spacer(Modifier.height(8.dp))
            // 常驻降级入口：任何一步失败都还有路可走（与 QqQrLoginDialog 一致）。
            MetroButton(text = strings.sourceWebLogin, onClick = onUseWebLogin)
            Spacer(Modifier.height(8.dp))
            MetroButton(text = strings.close, onClick = onDismiss)
        }
    }
}
