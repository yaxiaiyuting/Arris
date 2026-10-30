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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var code by remember { mutableStateOf("") }
    // 中继地址可改：workers.dev 在国内不可达，必须允许换成自定义域名/自建中继
    var base by remember { mutableStateOf(com.takahashirinta.ncrust.listen.TogetherPrefs.base(ctx)) }
    var editingBase by remember { mutableStateOf(false) }
    var baseDraft by remember { mutableStateOf(base) }
    // DNS 被污染时用固定 IP 直连（域名仍用于 SNI/Host，证书校验不变）
    var ips by remember { mutableStateOf(com.takahashirinta.ncrust.listen.TogetherPrefs.ips(ctx)) }
    // 固定 IP 只对「域名被污染」的场景有意义（典型就是 *.workers.dev）。
    // 换成自有域名后 DNS 是干净的，再钉 IP 反而会被针对 IP 的阻断打死 —— 实测会 Connection reset。
    fun effectiveIps(): List<String> = ips
    var ipsDraft by remember { mutableStateOf(ips.joinToString(",")) }
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
            "房主的切歌与进度会同步给房间里的每个人；音频不经过服务器。\n" +
                "同一 WiFi/热点：用「局域网建房 / 局域网加入」（不需要任何服务器）。\n" +
                "异地：① 一方开热点，另一方连上，同上去；" +
                "①b P2P 直连（无服务器，家用宽带/NAT 成功率高，移动数据基本打不通）；" +
                "② 双方装 Tailscale 后把 100.x 地址填进「局域网加入」；" +
                "③ 有域名的话可把中继换成自己的域名。",
            color = colors.onSurfaceVariant,
            style = TextStyle(fontSize = 12.sp),
        )

        // 中继地址行（点一下展开编辑）
        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant, RectangleShape)
                .clickable { editingBase = !editingBase; baseDraft = base }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            MetroText(
                if (editingBase) "" else buildString {
                    append("中继：$base")
                    if (ips.isNotEmpty()) append("\n固定 IP：").append(ips.joinToString(","))
                    append("（点击修改）")
                },
                color = colors.onSurfaceVariant,
                style = TextStyle(fontSize = 12.sp),
            )
            if (editingBase) {
                Column {
                    BasicTextField(
                        value = baseDraft,
                        onValueChange = { baseDraft = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp, color = colors.onBackground),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    MetroText(
                        "固定 IP（可空；workers.dev 域名被污染时填 Cloudflare 优选 IP）",
                        color = colors.onSurfaceVariant,
                        style = TextStyle(fontSize = 11.sp),
                    )
                    BasicTextField(
                        value = ipsDraft,
                        onValueChange = { ipsDraft = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 13.sp, color = colors.onBackground),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row2(
                        "保存" to {
                            base = com.takahashirinta.ncrust.listen.TogetherPrefs.normalize(baseDraft)
                            com.takahashirinta.ncrust.listen.TogetherPrefs.setBase(ctx, base)
                            com.takahashirinta.ncrust.listen.TogetherPrefs.setIps(ctx, ipsDraft)
                            ips = com.takahashirinta.ncrust.listen.TogetherPrefs.ips(ctx)
                            editingBase = false
                            TogetherSessionStatus.note(
                                "中继已改为 $base" + if (ips.isEmpty()) "" else "（固定 IP ${ips.joinToString(",")}）"
                            )
                        },
                        "用优选 IP" to {
                            baseDraft = com.takahashirinta.ncrust.listen.TogetherPrefs.DEFAULT_BASE
                            ipsDraft = com.takahashirinta.ncrust.listen.TogetherPrefs.DEFAULT_IPS
                            base = com.takahashirinta.ncrust.listen.TogetherPrefs.DEFAULT_BASE
                            com.takahashirinta.ncrust.listen.TogetherPrefs.setBase(ctx, base)
                            com.takahashirinta.ncrust.listen.TogetherPrefs.setIps(ctx, ipsDraft)
                            ips = com.takahashirinta.ncrust.listen.TogetherPrefs.ips(ctx)
                            editingBase = false
                        },
                    )
                }
            }
        }

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
                TogetherSession.create(base, c, effectiveIps())
            },
            "加入房间" to {
                if (code.isNotBlank()) TogetherSession.join(base, code, effectiveIps())
                else TogetherSessionStatus.note("先输入房间码")
            },
        )
        Row2(
            "局域网建房" to {
                val addr = TogetherSession.createLan()
                if (addr != null) {
                    code = addr
                    // 一键复制：房主把这句话发给朋友，对方粘贴到「局域网加入」即可
                    val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clip.setPrimaryClip(
                        android.content.ClipData.newPlainText("Arris 一起听", addr)
                    )
                    TogetherSessionStatus.note("已复制 $addr，粘贴给朋友即可加入")
                }
            },
            "局域网加入" to {
                if (code.contains(':')) TogetherSession.joinLan(code)
                else TogetherSessionStatus.note("房间码填 主机IP:端口")
            },
        )
        Row2(
            "P2P 建房" to {
                // STUN 查询是网络操作：必须在 IO 上跑，否则 NetworkOnMainThreadException
                scope.launch {
                    val c = withContext(Dispatchers.IO) { TogetherSession.createP2p() }
                    if (c != null) {
                        code = c
                    val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("Arris 一起听", c))
                        TogetherSessionStatus.note("已复制 P2P 房间码 $c（家用宽带成功率最高）")
                    }
                }
            },
            "P2P 加入" to {
                if (code.contains('#')) {
                    scope.launch { withContext(Dispatchers.IO) { TogetherSession.joinP2p(code) } }
                } else {
                    TogetherSessionStatus.note("P2P 房间码形如 1.2.3.4:5678#123456")
                }
            },
        )
        Row2(
            (if (scanning) "扫描中…" else "扫描房间") to {
                if (!scanning) {
                    scanning = true
                    scope.launch {
                        // 必须切 IO：rememberCoroutineScope() 默认是主线程，
                        // 直接发 HTTP 会抛 NetworkOnMainThreadException（message 是 null，
                        // UI 只显示"扫描失败：null"，完全看不出原因）。
                        val list = runCatching { withContext(Dispatchers.IO) { RoomDirectory.scan(base, effectiveIps()) } }
                        withContext(Dispatchers.Main) {
                            scanning = false
                            list.onSuccess { rooms = it }
                                .onFailure { e ->
                                    // 失败时直接给"下一招"，而不是把异常文字甩给用户：
                                    // 中继连不上在国内是常态（SNI 阻断/域名被投毒），
                                    // 局域网和 P2P 是不依赖任何服务器的现成替代。
                                    val why = when (e) {
                                        is java.net.SocketException,
                                        is java.net.SocketTimeoutException,
                                        is java.net.UnknownHostException -> "中继连不上（${e.javaClass.simpleName}）"
                                        else -> "扫描失败：${e.message ?: e.javaClass.simpleName}"
                                    }
                                    TogetherSessionStatus.note(
                                        "$why —— 可以改用「局域网建房/加入」（同一 WiFi/热点，不需要服务器），" +
                                            "或「P2P 建房」（家用宽带）"
                                    )
                                }
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
                        .clickable { code = r.roomId; TogetherSession.join(base, r.roomId, effectiveIps()) }
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
