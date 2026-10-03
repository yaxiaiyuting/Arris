/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.1：腾讯风控要求的图形验证码（手机号登录 `20276`）承载页。
 */

package com.takahashirinta.ncrust.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.takahashirinta.ncrust.qq.QqCookie
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay
import java.net.URL

/**
 * 腾讯风控的图形验证码（v2.1.1）。
 *
 * ## 这是什么、为什么必须有
 *
 * 手机号登录发短信时，服务端可能回 `20276`（需要滑块/图形验证码）并在 `data.securityURL`
 * 里给一个验证页地址。**这是风控在正常工作，不是 bug**：官方客户端遇到它也是弹一个 WebView
 * 让用户滑一下。绕过去（伪造设备指纹、换 IP、代理池）既不在本 fork 的范围内，
 * 也违反本项目的红线 —— 这里做的是把官方那条路走出来。
 *
 * ## 为什么要「把 cookie 带回去」（整个流程的关键）
 *
 * 验证是**会话级**的：验证通过的结果落在 cookie 上（`.qq.com` 域）。
 * 我们的 `SendPhoneAuthCode` 走 OkHttp，而 WebView 有自己独立的 cookie store，
 * 两边默认互不相通。所以：
 *
 * 1. 验证页在 WebView 里跑，验证结果写进 WebView 的 cookie store；
 * 2. 完成时把那份 cookie **读出来回传给调用方**；
 * 3. 调用方重发 `SendPhoneAuthCode` 时带上它（[com.takahashirinta.ncrust.qq.QqClient.musicuLogin]
 *    的 `extraCookie`）。
 *
 * 少了第 2/3 步，用户看到的就是「验证完了还要再验证」—— 那是我们自己没接上，不是腾讯刁难。
 *
 * ## 完成检测：cookie 变化（主）+ 手动按钮（兜底）
 *
 * 不去猜验证页的 DOM 结构：它会变，而且我们在开发环境复现不出这个页面。
 * 「cookie store 里出现了新的 cookie」是「这个会话被写过东西」的可靠信号。
 *
 * ⚠️ **基线必须在页面首次加载完成之后才取**：验证页自己一进来就会写会话 cookie，
 * 若把进入时的空快照当基线，第一次 `onPageFinished` 就会被误判成「验证完成」，
 * 于是拿一份没验证过的 cookie 去重试 —— 结果是被再次要求验证，用户看到死循环。
 * 所以 [baseline] 在首次 `onPageFinished` 才落定，之后再出现的**新** cookie 才算数。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun QqCaptchaOverlay(
    /** `data.securityURL`。 */
    url: String,
    /** 验证完成：回传 WebView 里拿到的 cookie（可能为 null = 什么都没拿到）。 */
    onVerified: (cookie: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    // null = 还没取基线（页面尚未首次加载完）。
    var baseline by remember { mutableStateOf<Set<String>?>(null) }
    var verified by remember { mutableStateOf(false) }

    fun snapshot(): String? {
        val cm = CookieManager.getInstance()
        val parts = mutableListOf<String>()
        // 验证页自己的域
        runCatching { URL(url).host }.getOrNull()
            ?.let { cm.getCookie("https://$it/")?.takeIf { c -> c.isNotEmpty() }?.let(parts::add) }
        // 业务请求打的是 u.y.qq.com，验证结果更可能写在父域 .qq.com 上
        cm.getCookie("https://y.qq.com/")?.takeIf { c -> c.isNotEmpty() }?.let(parts::add)
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    fun maybeFinish(): Boolean {
        val base = baseline ?: return false
        if (verified) return false
        val now = QqCookie.parse(snapshot())
        if (now.keys.any { it !in base }) {
            verified = true
            onVerified(snapshot())
            return true
        }
        return false
    }

    LaunchedEffect(webView) {
        while (true) {
            delay(COOKIE_POLL_MS)
            maybeFinish()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding(),
    ) {
        // 顶部提示条：说清这一步是什么、做完会自动继续。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            MetroText(
                text = strings.sourceQqCaptchaHint,
                style = TextStyle(fontSize = 13.sp),
                color = colors.onSurfaceVariant,
            )
        }
        AndroidView(
            modifier = Modifier.weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    // 不覆写 UA：这个页面来自**移动客户端**协议（musicu.fcg 的手机登录链路），
                    // 官方客户端也是用它自己的移动 UA 打开的。这里沿用 WebView 默认 UA。
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            // 快路径：验证通过往往紧接一次跳转，不必等下一个轮询间隔。
                            maybeFinish()
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            // 首次加载完成 = 验证页自己的会话 cookie 已经写完 ⇒ 此刻才取基线。
                            baseline = baseline ?: QqCookie.parse(snapshot()).keys
                            maybeFinish()
                        }
                    }
                    webChromeClient = object : WebChromeClient() {}
                    loadUrl(url)
                }.also { webView = it }
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetroButton(
                text = strings.sourceQqCaptchaRetry,
                onClick = {
                    // 手动兜底：cookie 变化检测可能不适用于某些验证方式
                    //（例如验证结果只回在 URL 上）。用户说做完了就照做。
                    if (!verified) {
                        verified = true
                        onVerified(snapshot())
                    }
                },
            )
            Spacer(Modifier.width(12.dp))
            MetroButton(
                text = strings.close,
                containerColor = colors.surfaceVariant,
                contentColor = colors.onSurface,
                onClick = onDismiss,
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

/** cookie 变化检测的轮询间隔。验证页是交互式的，0.7 秒足够跟手又不会空转太凶。 */
private const val COOKIE_POLL_MS = 700L
