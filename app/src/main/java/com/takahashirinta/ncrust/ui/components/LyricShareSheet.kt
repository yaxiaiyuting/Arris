/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.components

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.share.LyricShareActions
import com.takahashirinta.ncrust.share.LyricShareLine
import com.takahashirinta.ncrust.share.LyricShareOptions
import com.takahashirinta.ncrust.share.LyricSharePoster
import com.takahashirinta.ncrust.share.LyricShareScope
import com.takahashirinta.ncrust.share.LyricShareSelection
import com.takahashirinta.ncrust.share.LyricShareText
import com.takahashirinta.ncrust.share.LyricShareTrack
import com.takahashirinta.ncrust.share.LyricPosterColors
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroDialog
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.controls.MetroSwitch
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * v3.3.0 · 用户需求第 9 条：**歌词复制 / 分享 / 生成图片**的弹层。
 *
 * ## 为什么用 [MetroDialog]（`Dialog` 窗口）而不是 `MetroBottomSheet`
 *
 * 本弹层是从**全屏播放器卡片**里打开的，而播放器卡片是
 * `Box(fillMaxSize)` + `graphicsLayer { translationY = … }` 的独立图层（zIndex 1f），
 * 底部导航在 1.5f —— 仓库为此吃过一整类「看不见的地方还能点 / 看得见的地方点不到」的亏
 * （见 AGENTS.md 的「Compose 触摸陷阱」）。`MetroDialog` 内部是
 * `androidx.compose.ui.window.Dialog`，**是一个独立的 Window**，天然在所有图层之上、
 * 也不参与卡片的命中测试，因此这里不会新增一条死带。
 *
 * ## 职责边界
 *
 * 本文件只做「状态 + 排版 + 调 API」：真正的判定都在 `share/` 包里
 * （选段 [LyricShareSelection]、文本 [LyricShareText]、版式 [com.takahashirinta.ncrust.share.LyricPosterLayout]、
 * 出图与降级 [LyricSharePoster]），所以这个文件里没有任何可被单测的算法。
 *
 * ## 失败处理
 *
 * - 复制 / 分享的**提示与实际结果绑定**：`LyricShareActions` 返回 false 时不弹成功提示
 *   （v2.6.0 修过的「提示与事实脱钩」不能在这里复发）；
 * - 出图失败时**自动降级为分享文本**并说明（`ShareStrings.imageFailed`），
 *   只有连文本都发不出去才报错；
 * - 出图期间 `busy = true`：所有动作行置灰、`onDismissRequest` 变空实现
 *   （用户点外面不会把正在生成的协程连根取消）。
 */
@Composable
fun LyricShareSheet(
    track: LyricShareTrack,
    lyrics: List<LyricShareLine>,
    /** 打开弹层那一刻的播放位置（ms）——「当前唱段」的锚点。刻意不做成实时流： */
    positionMs: Long,
    /** 「包含译文」的初值 = 屏幕上是否正显示译文（复制到的 == 看到的）。 */
    defaultShowTranslation: Boolean,
    /** 「包含音译」的初值，同上。 */
    defaultShowRomanization: Boolean,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val share = strings.share
    val colors = LocalMetroColors.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var shareScope by remember { mutableStateOf(LyricShareScope.CURRENT_SECTION) }
    var includeTranslation by remember { mutableStateOf(defaultShowTranslation) }
    var includeRomanization by remember { mutableStateOf(defaultShowRomanization) }
    var includeTimestamps by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun options() = LyricShareOptions(
        scope = shareScope,
        includeTranslation = includeTranslation,
        includeRomanization = includeRomanization,
        includeTimestamps = includeTimestamps,
    )

    /** 复制 / 分享文本共用的正文；返回 null = 这段区间没有可分享的内容。 */
    fun buildText(): String? {
        val lines = LyricShareSelection.linesForText(lyrics, positionMs, shareScope)
        if (lines.isEmpty()) return null
        val text = LyricShareText.format(track, lines, options(), share.creditLine)
        return text.ifBlank { null }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /** 出图请求：颜色从当前主题拍平成 ARGB，离屏渲染读不到 CompositionLocal（见 LyricPosterRenderer 的 KDoc）。 */
    fun posterRequest(): LyricSharePoster.Request {
        val selection = LyricShareSelection.selectionForPoster(lyrics, positionMs, shareScope)
        return LyricSharePoster.Request(
            track = track,
            lines = selection.lines,
            currentIndex = selection.currentIndex,
            colors = LyricPosterColors(
                background = colors.background.toArgb(),
                primary = colors.primary.toArgb(),
                onBackground = colors.onBackground.toArgb(),
                onSurfaceVariant = colors.onSurfaceVariant.toArgb(),
                divider = colors.divider.toArgb(),
                onPrimary = colors.onPrimary.toArgb(),
            ),
            showTranslation = includeTranslation,
            showRomanization = includeRomanization,
            credit = share.creditLine,
        )
    }

    fun posterFileName(): String =
        LyricShareText.fileName(track, LyricShareText.stamp(System.currentTimeMillis(), TimeZone.getDefault()))

    val maxHeight = (LocalConfiguration.current.screenHeightDp - 180).coerceAtLeast(240).dp

    MetroDialog(
        // busy 时吞掉「点外面 / 返回键」：正在生成的协程挂在本层的作用域上，
        // 中途退场会让出图白做（用户看到的是「点了没反应」）。
        onDismissRequest = { if (!busy) onDismiss() },
        widthDp = 340.dp,
        containerColor = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── 标题 ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetroText(
                    share.action,
                    color = colors.onBackground,
                    style = LocalMetroTypography.current.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            MetroDivider()

            // ── 范围 ────────────────────────────────────────────────────────
            ScopeRow(share.scopeSection, shareScope == LyricShareScope.CURRENT_SECTION) {
                shareScope = LyricShareScope.CURRENT_SECTION
            }
            ScopeRow(share.scopeWhole, shareScope == LyricShareScope.WHOLE_SONG) {
                shareScope = LyricShareScope.WHOLE_SONG
            }

            MetroDivider()

            // ── 选项 ────────────────────────────────────────────────────────
            ToggleRow(share.optionTranslation, includeTranslation) { includeTranslation = it }
            ToggleRow(share.optionRomanization, includeRomanization) { includeRomanization = it }
            ToggleRow(share.optionTimestamps, includeTimestamps) { includeTimestamps = it }

            MetroDivider()

            // ── 动作 ────────────────────────────────────────────────────────
            ActionRow(
                icon = Icons.Default.ContentCopy,
                label = share.copyLyrics,
                enabled = !busy,
            ) {
                val text = buildText()
                if (text == null) {
                    toast(share.nothingToShare)
                    return@ActionRow
                }
                val ok = LyricShareActions.copyToClipboard(context, track.title, text)
                if (!ok) {
                    toast(share.nothingToShare)
                    return@ActionRow
                }
                // Android 13 起系统自己会弹「已复制」气泡，应用再弹一次是重复提示。
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(share.copied)
                onDismiss()
            }

            ActionRow(
                icon = Icons.Default.Send,
                label = share.shareText,
                enabled = !busy,
            ) {
                val text = buildText()
                if (text == null) {
                    toast(share.nothingToShare)
                    return@ActionRow
                }
                if (LyricShareActions.shareText(context, track.title, text, share.chooserTitle)) {
                    onDismiss()
                } else {
                    toast(share.shareUnavailable)
                }
            }

            ActionRow(
                icon = Icons.Default.Share,
                label = if (busy) share.generating else share.shareImage,
                enabled = !busy,
            ) {
                busy = true
                coroutineScope.launch {
                    val appContext = context.applicationContext
                    val uri = withContext(Dispatchers.IO) {
                        LyricSharePoster.renderToShareCache(appContext, posterRequest(), posterFileName())
                    }
                    busy = false
                    if (uri != null) {
                        if (LyricShareActions.shareImage(context, uri, share.chooserTitle)) {
                            onDismiss()
                        } else {
                            toast(share.shareUnavailable)
                        }
                        return@launch
                    }
                    // 降级：出图失败 ⇒ 分享纯文本（有界失败处理，不再重试出图）。
                    val text = buildText()
                    if (text != null &&
                        LyricShareActions.shareText(context, track.title, text, share.chooserTitle)
                    ) {
                        toast(share.imageFailed)
                        onDismiss()
                    } else {
                        toast(share.imageFailedOnly)
                    }
                }
            }

            // 「保存到相册」只在 API 29+ 出现：旧系统要 WRITE_EXTERNAL_STORAGE，
            // 而本版按边界没有新增任何权限声明（见 LyricShareActions.canSaveToGallery）。
            if (LyricShareActions.canSaveToGallery()) {
                ActionRow(
                    icon = Icons.Default.Save,
                    label = if (busy) share.generating else share.saveImage,
                    enabled = !busy,
                ) {
                    busy = true
                    coroutineScope.launch {
                        val appContext = context.applicationContext
                        val ok = withContext(Dispatchers.IO) {
                            LyricSharePoster.renderToGallery(appContext, posterRequest(), posterFileName())
                        }
                        busy = false
                        if (ok) {
                            toast(share.savedToGallery)
                            onDismiss()
                        } else {
                            toast(share.saveFailed)
                        }
                    }
                }
            }

            MetroDivider()

            ActionRow(icon = null, label = strings.cancel, enabled = !busy) { onDismiss() }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------- 行

/**
 * 范围行（单选）：选中态用左侧的**主色小方块**表示，而不是圆点 —— Kanesumi 风格里
 * 没有圆形控件（直角 / 信息优先），色块与海报页脚的应用标识也是同一个记号。
 */
@Composable
private fun ScopeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalMetroColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(if (selected) colors.primary else colors.divider)
        )
        Spacer(Modifier.width(14.dp))
        MetroText(
            label,
            color = if (selected) colors.onBackground else colors.onSurfaceVariant,
            style = LocalMetroTypography.current.bodyLarge,
        )
    }
}

/** 开关行：标签 + 右侧 [MetroSwitch]。 */
@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = LocalMetroColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MetroText(
            label,
            color = colors.onBackground,
            style = LocalMetroTypography.current.bodyLarge,
        )
        MetroSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 动作行。`enabled = false` 时只置灰、**仍然吃掉点击**（否则出图期间点它会穿到下层去）。
 */
@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalMetroColors.current
    val contentColor = if (enabled) colors.onBackground else colors.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            MetroIcon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                sizeDp = 20.dp,
            )
            Spacer(Modifier.width(14.dp))
        } else {
            Spacer(Modifier.width(34.dp))
        }
        MetroText(
            label,
            color = contentColor,
            style = LocalMetroTypography.current.bodyLarge,
        )
    }
}
