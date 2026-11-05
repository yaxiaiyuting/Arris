/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.network.FeedbackClient
import io.github.takahashirinta.kanesumi.controls.MetroButton
import io.github.takahashirinta.kanesumi.controls.MetroDialog
import io.github.takahashirinta.kanesumi.controls.MetroTextField
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.launch

/**
 * 发送反馈（**3.7.31 回移主线 4.0.11**）。
 *
 * 三个输入项：标题（可不填）、描述（必填）、联系方式（可选）。
 * 下方常驻一段**诊断信息预览** —— 用户按"发送"之前必须能看见**究竟发了什么**，
 * 这是隐私底线：不发 cookie / 令牌 / 曲目历史，只发版本、机型、系统、语言主题、登录与否的布尔值。
 *
 * 失败两条出路：① 再试一次；② 一键复制全文，去 Gitee 仓库手动开 issue
 * （`gitee.com/yaxiaiyuting/arris-feedback`），不给用户"写了半天结果丢了"的体验。
 *
 * LTS 移植说明：文案为**中文字面量**，不进 `Strings`（LTS 不扩 i18n 面，
 * 与 `KaraokeCopy` 的做法一致 —— 那是本仓库既定的"新功能不扩 8 语言"约定）。
 */
@Composable
fun FeedbackDialog(
    language: String,
    themeMode: String,
    qualityIndex: Int,
    loggedInNcm: Boolean,
    loggedInQq: Boolean,
    onDismiss: () -> Unit,
) {
    val colors: MetroColors = LocalMetroColors.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }

    val diagnostics = remember(language, themeMode, qualityIndex, loggedInNcm, loggedInQq) {
        FeedbackClient.collect(
            language = language,
            themeMode = themeMode,
            qualityIndex = qualityIndex,
            loggedInNcm = loggedInNcm,
            loggedInQq = loggedInQq,
        )
    }

    MetroDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            MetroText("发送反馈", style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            MetroText(
                "会一并带上版本、机型与系统版本，方便定位问题。不上传账号凭证、曲目历史。",
                color = colors.onSurfaceVariant,
                style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            )
            Spacer(Modifier.height(14.dp))

            MetroTextField(
                value = title,
                onValueChange = { if (it.length <= 60) title = it },
                placeholder = "标题（可不填）",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            MetroTextField(
                value = message,
                onValueChange = { if (it.length <= 2000) message = it },
                placeholder = "发生了什么 / 想建议什么",
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            )
            Spacer(Modifier.height(10.dp))
            MetroTextField(
                value = contact,
                onValueChange = { if (it.length <= 80) contact = it },
                placeholder = "联系方式（可选）",
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))
            MetroText("将发送的诊断信息", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium))
            Spacer(Modifier.height(6.dp))
            MetroText(
                listOf(
                    "版本 ${diagnostics.appVersion} (${diagnostics.versionCode})",
                    "系统 Android ${diagnostics.androidRelease} (API ${diagnostics.sdkInt})",
                    "设备 ${diagnostics.brand} ${diagnostics.model}".trim(),
                    "语言 ${diagnostics.language} · 主题 ${diagnostics.themeMode} · 音质档 ${diagnostics.qualityIndex}",
                    "登录 ncm=${diagnostics.loggedInNcm} qm=${diagnostics.loggedInQq}",
                ).joinToString("\n"),
                color = colors.onSurfaceVariant,
                style = TextStyle(fontSize = 11.sp, lineHeight = 17.sp),
            )

            if (status.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                MetroText(status, style = TextStyle(fontSize = 12.sp))
            }
            if (draft.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                MetroText(
                    "复制全文，去 gitee.com/yaxiaiyuting/arris-feedback 手动提交",
                    color = colors.primary,
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString(draft))
                        status = "已复制到剪贴板"
                    },
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetroButton(
                    text = if (sending) "发送中…" else "发送",
                    enabled = !sending && message.trim().length >= 5,
                    onClick = {
                        sending = true
                        status = ""
                        scope.launch {
                            when (val r = FeedbackClient.send(title, message, contact, diagnostics)) {
                                is FeedbackClient.Result.Sent -> {
                                    status = "已提交，感谢反馈" + (r.issueUrl?.let { "：$it" } ?: "")
                                    message = ""
                                    title = ""
                                    contact = ""
                                    draft = ""
                                }

                                is FeedbackClient.Result.Failed -> {
                                    status = "发送失败：${r.reason}"
                                    draft = r.draft
                                }
                            }
                            sending = false
                        }
                    },
                )
                MetroButton(text = "关闭", enabled = !sending, onClick = onDismiss)
            }
        }
    }
}
