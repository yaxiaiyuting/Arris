/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import com.takahashirinta.ncrust.listen.RoomDirectory
import com.takahashirinta.ncrust.listen.RoomSummary
import com.takahashirinta.ncrust.listen.TogetherSession
import com.takahashirinta.ncrust.listen.TogetherSessionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一起听（v3.8.0 正式入口）。
 *
 * 公网中继是 Cloudflare Worker（`relay/` 目录里有部署说明），房间码 6 位。
 * 房主的播放状态每秒广播一次；成员收到后按 `DriftPolicy` 跟随
 * （偏差 ≤800ms 不动，超过才 seek，避免每秒打断播放）。
 */
@Composable
fun TogetherScreen(onBack: () -> Unit) {
    val colors = LocalMetroColors.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var rooms by remember { mutableStateOf<List<RoomSummary>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    val status by TogetherSession.status.collectAsState()
    val room by TogetherSession.roomId.collectAsState()
    val host by TogetherSession.isHost.collectAsState()
    val members by TogetherSession.members.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MetroText("一起听", color = colors.onBackground, style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold))
        MetroText(
            "房主的切歌与进度会同步给房间里的每个人；音频不经过服务器。",
            color = colors.onSurfaceVariant,
            style = TextStyle(fontSize = 12.sp),
        )

        BasicTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isLetterOrDigit).take(8) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 18.sp, color = colors.onBackground),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceVariant, RectangleShape)
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                ) {
                    if (code.isEmpty()) {
                        MetroText("输入 6 位房间码", color = colors.onSurfaceVariant, style = TextStyle(fontSize = 14.sp))
                    }
                    inner()
                }
            },
        )

        Row2(
            "创建房间" to {
                val c = TogetherSession.newRoomCode()
                code = c
                TogetherSession.create(RoomDirectory.DEFAULT_BASE, c)
            },
            "加入房间" to {
                if (code.isNotBlank()) TogetherSession.join(RoomDirectory.DEFAULT_BASE, code)
                else TogetherSessionStatus.note("先输入房间码")
            },
        )
        Row2(
            (if (scanning) "扫描中…" else "扫描房间") to {
                if (!scanning) {
                    scanning = true
                    scope.launch {
                        val list = runCatching { RoomDirectory.scan() }
                        withContext(Dispatchers.Main) {
                            scanning = false
                            list.onSuccess { rooms = it }
                                .onFailure { TogetherSessionStatus.note("扫描失败：${it.message}") }
                        }
                    }
                }
            },
            "退出房间" to { TogetherSession.leave() },
        )

        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant, RectangleShape)
                .padding(14.dp),
        ) {
            Column {
                MetroText(
                    "房间：${room.ifBlank { "（未加入）" }}${if (room.isNotBlank()) if (host) "（房主）" else "（成员）" else ""}",
                    color = colors.onBackground,
                    style = TextStyle(fontSize = 14.sp),
                )
                if (members > 0) {
                    MetroText("在线 $members 人", color = colors.onSurfaceVariant, style = TextStyle(fontSize = 12.sp))
                }
                Spacer(Modifier.height(4.dp))
                MetroText(status, color = colors.onSurfaceVariant, style = TextStyle(fontSize = 12.sp))
            }
        }

        if (rooms.isNotEmpty()) {
            MetroText("当前活跃房间", color = colors.onBackground, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold))
            rooms.forEach { r ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceVariant, RectangleShape)
                        .clickable { code = r.roomId; TogetherSession.join(RoomDirectory.DEFAULT_BASE, r.roomId) }
                        .padding(12.dp),
                ) {
                    MetroText(
                        "${r.roomId}　房主 ${r.host.ifBlank { "?" }}　${r.members} 人",
                        color = colors.onBackground,
                        style = TextStyle(fontSize = 13.sp),
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant, RectangleShape)
                .clickable(onClick = onBack)
                .padding(14.dp),
        ) {
            MetroText("返回", color = colors.primary, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun Row2(left: Pair<String, () -> Unit>, right: Pair<String, () -> Unit>) {
    val colors = LocalMetroColors.current
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        listOf(left, right).forEach { (label, action) ->
            Box(
                Modifier
                    .weight(1f)
                    .background(colors.primary, RectangleShape)
                    .clickable(onClick = action)
                    .padding(vertical = 14.dp),
            ) {
                MetroText(
                    label,
                    color = colors.onPrimary,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
        }
    }
}
