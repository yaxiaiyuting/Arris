/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.1：qm「手机号 + 短信验证码」登录浮层。
 */

package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.qq.QqApi
import com.takahashirinta.ncrust.qq.QqPhoneLogin
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * qm 手机号验证码登录（v2.1.1）。
 *
 * ## 为什么需要这条路
 *
 * 网页登录里的**微信登录**是「网站应用扫码」，那个二维码**只能被另一台设备的微信扫**；
 * 官方 App 的「一键微信登录」走微信开放平台移动应用 SDK，要求 `appid` 与**签名**
 * 在腾讯侧配对注册 —— 本 fork 的签名必然不同，结构上走不通（详见 [QqPhoneLogin] 的注释）。
 * 手机号 + 短信验证码是纯 HTTP，单机就能完成，是这些用户唯一可用的登录方式。
 *
 * ## 合规（本项目不索取、不存储的东西）
 *
 * - **不读短信**：验证码由用户手输，所以**不需要** `READ_SMS` 权限（本版没有新增任何权限）。
 * - **不存手机号**：号码只在这一层的内存里活着，请求发完即弃；落盘的只有服务端下发的 cookie
 *   （[QqAuthStore]）。界面上的合规说明见 `strings.sourceQqPhoneNote`。
 * - **不存密码**：这条链路本来就没有密码。
 *
 * ## 失败时的降级
 *
 * 常驻「改用网页登录」按钮：短信通道可能因为号码未注册、服务端要图形验证码、
 * 或地区限制而走不通，用户不该因此完全登不上。
 */
@Composable
fun QqPhoneLoginDialog(
    /** 登录成功：cookie 已由 [QqApi.loginWithPhoneCode] 拼好，交给调用方落盘。 */
    onLoggedIn: (cookie: String) -> Unit,
    /** 用户选择「改用网页登录」。 */
    onUseWebLogin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    val scope = rememberCoroutineScope()

    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // 提示行。用「分类 + 可选文案」而不是拼好的字符串：状态变化时要能自动让位给更新的提示。
    var message by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    // 重发倒计时（秒）。0 = 可以发。
    var countdown by remember { mutableIntStateOf(0) }
    // 风控要求的图形验证码：非空时整个浮层让位给验证页（见 QqCaptchaOverlay）。
    var captchaUrl by remember { mutableStateOf<String?>(null) }
    // 验证通过后从 WebView 拿回来的会话 cookie。**必须留着**：验证结果是会话级的，
    // 之后每次发码/登录请求都要带上，否则会被重新要求验证（用户看到的就是死循环）。
    var captchaCookie by remember { mutableStateOf<String?>(null) }
    // 被要求验证的次数，用于兜住「验证过了还要验证」的循环。
    var captchaRounds by remember { mutableIntStateOf(0) }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1_000L)
            countdown -= 1
        }
    }

    /**
     * 发验证码。首次发送与「验证通过后重发」共用这一条路径 ——
     * 拆成两段写的话，很容易出现「验证完了却忘了把 cookie 带上」这种死循环。
     */
    fun sendCode(phoneNo: String, captcha: String?) {
        busy = true
        scope.launch {
            val attempt = QqApi.sendPhoneAuthCode(phoneNo, captcha)
            busy = false
            when (attempt.outcome) {
                QqPhoneLogin.SendOutcome.SENT -> {
                    codeSent = true
                    countdown = RESEND_SECONDS
                    message = strings.sourceQqCodeSent
                }
                QqPhoneLogin.SendOutcome.BAD_NUMBER -> message = strings.sourceQqPhoneBadNumber
                QqPhoneLogin.SendOutcome.TOO_FREQUENT -> message = strings.sourceQqPhoneTooFrequent
                QqPhoneLogin.SendOutcome.NEED_CAPTCHA -> {
                    val url = attempt.securityUrl
                    // 轮次上限：验证明明过了却还一直被要求验证时，说明 cookie 那条假设不成立
                    // （见 QqCaptchaOverlay 的注释）。与其把用户关进「验证 → 再验证」的循环，
                    // 不如停下来把网页登录这条路明确摆出来。
                    captchaRounds += 1
                    if (url.isNullOrEmpty() || captchaRounds > MAX_CAPTCHA_ROUNDS) {
                        message = strings.sourceQqPhoneNeedCaptcha
                    } else {
                        captchaUrl = url
                        message = ""
                    }
                }
                QqPhoneLogin.SendOutcome.FAILED -> message = strings.sourceQqCodeSendFailed
            }
        }
    }

    // 图形验证码：整屏让位给验证页。验证完成 → 自动重发（用户点「发送验证码」的意图
    // 就是「把短信发出去」，让他再点一次是多余的）。
    val pendingCaptcha = captchaUrl
    if (pendingCaptcha != null) {
        QqCaptchaOverlay(
            url = pendingCaptcha,
            onVerified = { cookie ->
                if (!cookie.isNullOrEmpty()) captchaCookie = cookie
                captchaUrl = null
                QqPhoneLogin.normalizePhone(phone)?.let { sendCode(it, cookie) }
            },
            onDismiss = { captchaUrl = null },
        )
        return
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
                .padding(24.dp)
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MetroText(
                text = strings.sourceQqPhoneTitle,
                style = TextStyle(fontSize = 18.sp),
                color = colors.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            PhoneField(
                label = strings.sourceQqPhoneLabel,
                value = phone,
                hint = strings.sourceQqPhoneHint,
                // 只留数字：号码里的空格/横线由 QqPhoneLogin.normalizePhone 负责剥，
                // 但在输入这一层就限制住字符集，用户不可能打进一个字母。
                onValueChange = { phone = it.filter { c -> c.isDigit() }.take(18) },
                keyboardType = KeyboardType.Phone,
            )
            Spacer(Modifier.height(8.dp))
            MetroButton(
                text = if (countdown > 0) {
                    strings.sourceQqResendCode + " (" + countdown + "s)"
                } else {
                    strings.sourceQqSendCode
                },
                enabled = !busy && countdown == 0,
                onClick = {
                    val normalized = QqPhoneLogin.normalizePhone(phone)
                    if (normalized == null) {
                        // 本地就拦下：省一次注定 104400 的往返（号码格式服务端也会校验）
                        message = strings.sourceQqPhoneBadNumber
                        return@MetroButton
                    }
                    phone = normalized
                    // 带上已经拿到的验证 cookie（若有）：否则每次重发都会被再要求验证一次。
                    sendCode(normalized, captchaCookie)
                },
            )

            Spacer(Modifier.height(16.dp))
            PhoneField(
                label = strings.sourceQqCodeLabel,
                value = code,
                hint = strings.sourceQqCodeHint,
                onValueChange = { code = it.filter { c -> c.isDigit() }.take(8) },
                keyboardType = KeyboardType.NumberPassword,
            )
            Spacer(Modifier.height(12.dp))

            MetroButton(
                text = strings.sourceQqPhoneSubmit,
                // 没发过验证码就不给点：绝大多数「登录失败」其实是没点发送就点了登录。
                enabled = !busy && codeSent && code.isNotEmpty(),
                onClick = {
                    val normalized = QqPhoneLogin.normalizePhone(phone)
                    val cleanCode = QqPhoneLogin.normalizeCode(code)
                    if (normalized == null) {
                        message = strings.sourceQqPhoneBadNumber
                        return@MetroButton
                    }
                    if (cleanCode == null) {
                        message = strings.sourceQqCodeWrong
                        return@MetroButton
                    }
                    busy = true
                    scope.launch {
                        // 登录同样带上验证 cookie：风控的验证结果是会话级的，
                        // 只给发码请求带、不给登录请求带，会在最后一步被拦下来。
                        val attempt = QqApi.loginWithPhoneCode(normalized, cleanCode, captchaCookie)
                        busy = false
                        when (attempt.outcome) {
                            QqPhoneLogin.LoginOutcome.OK -> {
                                val cookie = attempt.cookie
                                if (cookie == null) {
                                    // 协议上不该发生（API 层已经拦过一次），兜底不静默
                                    message = strings.sourceQqLoginFailed
                                } else {
                                    onLoggedIn(cookie)
                                }
                            }
                            QqPhoneLogin.LoginOutcome.CODE_WRONG -> message = strings.sourceQqCodeWrong
                            QqPhoneLogin.LoginOutcome.TOO_FREQUENT ->
                                message = strings.sourceQqLoginRateLimited
                            QqPhoneLogin.LoginOutcome.NEED_CAPTCHA ->
                                message = strings.sourceQqPhoneNeedCaptcha
                            QqPhoneLogin.LoginOutcome.ACCOUNT_RESTRICTED ->
                                message = strings.sourceQqAccountRestricted
                            QqPhoneLogin.LoginOutcome.DEVICE_LIMIT ->
                                message = strings.sourceQqDeviceLimit
                            QqPhoneLogin.LoginOutcome.FAILED ->
                                message = strings.sourceQqLoginFailed
                        }
                    }
                },
            )

            if (message.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                MetroText(
                    text = message,
                    style = TextStyle(fontSize = 13.sp),
                    color = colors.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            // 常驻的降级入口（与扫码浮层同一约定）：这条路不通时用户不该被卡死。
            MetroButton(text = strings.sourceWebLogin, onClick = onUseWebLogin)
            Spacer(Modifier.height(8.dp))
            MetroButton(text = strings.close, onClick = onDismiss)

            Spacer(Modifier.height(12.dp))
            // 合规说明写在界面上，而不是只写在代码注释里 —— 用户有权知道短信是谁发的、
            // 这个应用碰不碰他的短信与号码。
            MetroText(
                text = strings.sourceQqPhoneNote,
                style = TextStyle(fontSize = 11.sp),
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** 一行「标签 + 输入框」。直角、无圆角、无阴影（Kanesumi Design）。 */
@Composable
private fun PhoneField(
    label: String,
    value: String,
    hint: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
) {
    val colors = LocalMetroColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        MetroText(
            text = label,
            style = TextStyle(fontSize = 12.sp),
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                // BasicTextField 没有 M3 TextField 的隐式 56dp 最小高度，
                // 得在 decorationBox 里补上，否则输入框会塌成一条细线（SearchScreen 同款处理）。
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done,
                ),
                textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.onSurface),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty()) {
                            MetroText(
                                text = hint,
                                style = TextStyle(fontSize = 16.sp),
                                color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

/** 重发倒计时。60 秒是腾讯侧短信下发的最小间隔量级，太短只会撞「操作过于频繁」。 */
private const val RESEND_SECONDS = 60

/**
 * 最多让用户过几次图形验证。
 *
 * 正常情况下 1 次就够（验证结果落在 cookie 上，之后带着它就不再被要求验证）。
 * 若连续两次仍然被要求验证，说明「验证结果写 cookie」这个前提不成立 ——
 * 那时继续弹验证页只是把用户关进循环，应当停下来引导他去网页登录。
 */
private const val MAX_CAPTCHA_ROUNDS = 2
