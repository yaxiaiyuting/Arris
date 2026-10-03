/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P1：B 站扫码登录的**界面外壳**（铁律 23：复用现有扫码登录框架）。
 */

package com.takahashirinta.ncrust.ui.components

import android.graphics.Bitmap
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import io.github.takahashirinta.kanesumi.controls.MetroDialog

/**
 * v3.2.1 · P0：二维码区**该画什么**（纯函数，由 `BiliQrPanelTest` 逐格钉住）。
 *
 * ## 为什么要有这个函数（真机 P0：B站登录「打开即失效」）
 *
 * v3.2.0 的二维码区是这么写的：「有位图 **且** 状态不是 EXPIRED/FAILED」→ 画码，
 * **其它一律**画 `strings.biliLoginExpired`（「二维码已失效，请刷新」）。
 * 于是 `IDLE` / `WAITING` 这两个"还没有位图但一切正常"的状态被显示成**过期** ——
 * 用户打开浮层第一眼看到的就是"已失效"，而实际上那时二维码根本还没被渲染
 * （位图生成被排在了轮询终态之后，见 `BiliQrLogin.poll` 的 KDoc）。
 *
 * 抽成纯函数是为了让"哪一档显示哪句话"变成可执行断言，而不是埋在 Compose 的 `if` 里：
 * 三档必须分开 —— **加载中 / 已失效 / 明确失败**，
 * 「拿不到码」绝不能显示成「码过期了」。根因与证据：
 * `docs/verification/v3.2.1/probe-bili-qr-broken.md`。
 */
internal enum class BiliQrPanel {
    /** 画二维码位图。 */
    QR,

    /** 还在申请 / 正在渲染 —— 「加载中」。 */
    LOADING,

    /** 服务端说 86038 或本地上限先到 —— 「已失效，请刷新」。 */
    EXPIRED,

    /** 申请失败或位图生成失败 —— **明确错误**（不是过期）。 */
    FAILED,
}

/**
 * @param hasBitmap 已经有可画的位图。
 * @param renderFailed 申请二维码或生成位图失败（**与"过期"是两件事**）。
 */
internal fun biliQrPanel(
    state: BiliQrLogin.State,
    hasBitmap: Boolean,
    renderFailed: Boolean,
): BiliQrPanel = when {
    renderFailed -> BiliQrPanel.FAILED
    state == BiliQrLogin.State.EXPIRED -> BiliQrPanel.EXPIRED
    state == BiliQrLogin.State.FAILED -> BiliQrPanel.FAILED
    hasBitmap -> BiliQrPanel.QR
    else -> BiliQrPanel.LOADING
}

/**
 * B 站扫码登录浮层（v3.2.0 · P1，v3.2.1 · P0 修「打开即失效」）。
 *
 * ## 复用而不是新建（铁律 23）
 *
 * 界面外壳、二维码渲染、状态文案都与 [QrLoginDialog] / [QqLoginOverlay] 同形：
 * 同一个 ZXing 渲染函数（[generateQrBitmap]，v3.2.0 把它从 `private` 放宽到 `internal`
 * 就是为了**复用而不是复制第二份**）、同一个「状态 → 一句文案」的映射、
 * 同一个「失效后给刷新按钮」的出口。**没有第二套二维码渲染器、没有第二套登录弹窗。**
 *
 * 状态机本身在 [BiliQrLogin]（纯逻辑、JVM 可测），本文件只做三件事：
 * 1. 按状态渲染（[BiliQrLogin.State] → 文案 + 二维码 + 刷新按钮）；
 * 2. 在 [BiliQrLogin.State.CONFIRMED] 时调 `finishLogin` 落盘并回调 [onLoggedIn]；
 * 3. 关掉浮层时 `cancel()` —— **取消不是失败**（不要写 `FAILED` 文案）。
 *
 * ## v3.2.1 · P0：三步的顺序（本次"打开即失效"的根因）
 *
 * ```
 * prepare()  →  渲染二维码  →  poll()
 * ```
 *
 * v3.2.0 把这三步写成了 `state = login.start { … }` + 之后才 `generateQrBitmap`，
 * 而 `start()` 要到终态才返回 ⇒ 二维码 180 秒内不可能出现，
 * 二维码区只能一直显示「已失效」。现在的形状与 `QqQrLoginDialog` 的两个
 * `LaunchedEffect`（申请 / 轮询分离）等价，区别只在于本状态机把「有界轮询」也收进了类里。
 *
 * ## 为什么二维码内容直接是 URL
 *
 * B 站的 `generate` 返回的 `data.url` **就是二维码要编码的内容**（一个 URL 字符串，
 * 不是 PNG/base64）—— 这一点与 ncm/QQ 两侧都不同，所以这里不需要
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
    // 刷新二维码 = 换一个 generation，`LaunchedEffect` 会重跑一次完整的流程。
    var generation by remember { mutableStateOf(0) }
    // 二维码位图按 generation 缓存：`generateQrBitmap` 是逐像素 setPixel（512×512），
    // 每次重组都重算一次是纯浪费，而同一张码的内容在整个 generation 里不变。
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // v3.2.1 · P0：申请失败 / 位图生成失败 —— **独立于 state** 的一档，
    // 因为它在状态机里可能仍是 WAITING（轮询根本没开始）。
    var renderFailed by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(generation) {
        qrBitmap = null
        renderFailed = false

        // ① 申请二维码（只发一次 generate）。这一步很短，不再把后面的渲染堵住。
        val prepared = login.prepare { state = it }
        if (prepared != BiliQrLogin.State.WAITING) {
            Log.w(TAG, "二维码申请失败：state=$prepared（显示明确错误，不显示「已失效」）")
            renderFailed = true
            return@LaunchedEffect
        }

        // ② 先把二维码画出来 —— 用户能看见码之后才开始轮询（v3.2.1 的顺序修正）。
        //    512×512 逐像素 setPixel 放到 Default 线程，别压主线程。
        val url = login.qrCode?.url
        val bmp = if (url.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) { generateQrBitmap(url) }
        }
        if (bmp == null) {
            Log.w(TAG, "二维码位图生成失败（内容长度=${url?.length ?: 0}）")
            renderFailed = true
            return@LaunchedEffect
        }
        qrBitmap = bmp
        Log.i(TAG, "二维码已渲染，开始有界轮询（TTL ${BiliQrLogin.QR_TTL_SECONDS}s）")

        // ③ 有界轮询（不会再申请一次二维码）。
        state = login.poll { state = it }
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
            val panel = biliQrPanel(state, hasBitmap = qrBitmap != null, renderFailed = renderFailed)
            Box(
                modifier = Modifier.size(220.dp).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = qrBitmap
                when {
                    panel == BiliQrPanel.QR && bmp != null -> Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = strings.biliLoginTitle,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 三档文案分开：加载中 / 已失效 / 明确失败（见 biliQrPanel 的 KDoc）。
                    panel == BiliQrPanel.EXPIRED -> MetroText(
                        text = strings.biliLoginExpired,
                        style = TextStyle(fontSize = 14.sp),
                        color = Color.Black,
                    )

                    panel == BiliQrPanel.FAILED -> MetroText(
                        text = strings.sourceQrLoadFailed,
                        style = TextStyle(fontSize = 14.sp),
                        color = Color.Black,
                    )

                    else -> MetroText(
                        text = strings.loading,
                        style = TextStyle(fontSize = 14.sp),
                        color = Color.Black,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            MetroText(
                text = when {
                    // 「生成失败」优先于状态机文案：它的原因与"等待扫码"无关。
                    renderFailed -> strings.sourceQrLoadFailed
                    state == BiliQrLogin.State.IDLE || state == BiliQrLogin.State.LOADING ->
                        strings.loading

                    state == BiliQrLogin.State.CONFIRMED ->
                        strings.biliLoginSuccess(BiliAuthStore.uname(context).orEmpty())

                    state == BiliQrLogin.State.SCANNED -> strings.biliLoginScanned
                    state == BiliQrLogin.State.EXPIRED -> strings.biliLoginExpired
                    // 失败与取消**分开**：取消不是失败，也不用催用户「重试」。
                    state == BiliQrLogin.State.FAILED -> strings.biliLoginFailed
                    state == BiliQrLogin.State.CANCELLED -> strings.biliLoginWaiting
                    else -> strings.biliLoginWaiting
                },
                style = typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )

            // 只有「失效 / 失败 / 生成失败」给刷新按钮 —— 等待中给一个刷新键会让用户以为二维码坏了。
            if (panel == BiliQrPanel.EXPIRED || panel == BiliQrPanel.FAILED) {
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

/** 日志标签：二维码申请 / 渲染 / 轮询各留一条（不含任何凭据）。 */
private const val TAG = "BiliQrLoginDialog"
