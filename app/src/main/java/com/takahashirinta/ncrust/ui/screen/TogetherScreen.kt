/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.listen.RoomDirectory
import com.takahashirinta.ncrust.listen.RoomSummary
import com.takahashirinta.ncrust.listen.TogetherPrefs
import com.takahashirinta.ncrust.listen.TogetherSession
import com.takahashirinta.ncrust.listen.TogetherSessionStatus
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一起听（v3.8.1 界面重做）。
 *
 * 布局顺序按「用户此刻最关心的东西」排：
 *
 * 1. **状态卡**：房间码、角色、在线人数、状态文字 —— 进来看一眼就知道自己在不在房间里；
 * 2. **输入 + 主操作**：填码 → 创建 / 加入，两个按钮是同一件事的两侧；
 * 3. **连接方式**（中继 / 局域网 / P2P）：把八个按钮按传输方式分组，
 *    每种方式自己两个动作 + 一行适用条件，不再是一整面绿格子；
 * 4. **活跃房间**：点一下直接进；
 * 5. **说明**：放最下面，用弱化色，不抢视线。
 *
 * 房主的播放状态每秒广播一次；成员收到后按 [com.takahashirinta.ncrust.listen.FollowPolicy]
 * 跟随（偏差 ≤800ms 不动，超过才 seek，避免每秒打断播放）。
 */
@Composable
fun TogetherScreen(onBack: () -> Unit) {
    val colors = LocalMetroColors.current
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    var code by remember { mutableStateOf("") }
    // 中继地址可改：workers.dev 在国内不可达，必须允许换成自定义域名/自建中继
    var base by remember { mutableStateOf(TogetherPrefs.base(ctx)) }
    var editingBase by remember { mutableStateOf(false) }
    var baseDraft by remember { mutableStateOf(base) }
    // 固定 IP 只对「域名被污染」有意义（典型就是 *.workers.dev）。
    // 换成自有域名后 DNS 是干净的，再钉旧 IP 只会连到被阻断的地址 ——
    // 实测表现就是 WebSocket 直接失败、界面报 -1。
    var ips by remember {
        mutableStateOf(
            TogetherPrefs.ips(ctx).takeIf { TogetherPrefs.base(ctx).contains("workers.dev") }.orEmpty(),
        )
    }
    var ipsDraft by remember { mutableStateOf(ips.joinToString(",")) }
    var rooms by remember { mutableStateOf<List<RoomSummary>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(TransportMode.Relay) }

    val status by TogetherSession.status.collectAsState()
    val room by TogetherSession.roomId.collectAsState()
    val host by TogetherSession.isHost.collectAsState()
    val members by TogetherSession.members.collectAsState()

    fun copy(text: String, note: String) {
        val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clip.setPrimaryClip(android.content.ClipData.newPlainText("Arris 一起听", text))
        TogetherSessionStatus.note(note)
    }

    fun scan() {
        if (scanning) return
        scanning = true
        scope.launch {
            // 必须切 IO：rememberCoroutineScope() 默认主线程，直接发 HTTP 会抛
            // NetworkOnMainThreadException，UI 上只显示"扫描失败：null"。
            val list = runCatching { withContext(Dispatchers.IO) { RoomDirectory.scan(base, ips) } }
            withContext(Dispatchers.Main) {
                scanning = false
                list.onSuccess { rooms = it }
                    .onFailure { e ->
                        val why = when (e) {
                            is java.net.SocketException,
                            is java.net.SocketTimeoutException,
                            is java.net.UnknownHostException -> "中继连不上（${e.javaClass.simpleName}）"
                            else -> "扫描失败：${e.message ?: e.javaClass.simpleName}"
                        }
                        TogetherSessionStatus.note("$why —— 改用「局域网」或「P2P」试试")
                    }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---------------------------------------------------------------- 标题
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MetroText(
                    "一起听",
                    color = colors.onBackground,
                    style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.height(4.dp))
                MetroText(
                    "同步的是播放状态，音频各放各的，账号留在自己设备",
                    color = colors.onSurfaceVariant,
                    style = TextStyle(fontSize = 12.sp),
                )
            }
            TextAction("返回", accent = false, onClick = onBack)
        }

        // ---------------------------------------------------------------- 状态卡
        Card(bold = room.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(7.dp)
                        .height(7.dp)
                        .background(if (room.isBlank()) colors.onSurfaceVariant.copy(alpha = 0.5f) else colors.primary),
                )
                Spacer(Modifier.width(8.dp))
                MetroText(
                    if (room.isBlank()) "未连接" else if (host) "房主 · 在线 $members 人" else "已连接 · 在线 $members 人",
                    color = colors.onSurfaceVariant,
                    style = TextStyle(fontSize = 11.sp),
                )
            }
            Spacer(Modifier.height(10.dp))
            MetroText(
                if (room.isBlank()) "未加入房间" else room,
                color = if (room.isBlank()) colors.onSurfaceVariant else colors.onBackground,
                style = TextStyle(
                    fontSize = if (room.isBlank()) 18.sp else 34.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Spacer(Modifier.height(6.dp))
            MetroText(status, color = colors.onSurfaceVariant, style = TextStyle(fontSize = 12.sp))
            if (room.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    TextAction("复制邀请") { copy(room, "已复制房间码 $room") }
                    TextAction("退出房间", accent = false) { TogetherSession.leave() }
                }
            }
        }

        // ---------------------------------------------------------------- 房间码输入（已加入时不需要）
        if (room.isBlank()) SectionLabel("房间码")
        if (room.isBlank()) BasicTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isLetterOrDigit).take(8) },
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 20.sp,
                color = colors.onBackground,
                fontFamily = FontFamily.Monospace,
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, colors.onSurfaceVariant.copy(alpha = 0.3f))
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                ) {
                    if (code.isEmpty()) {
                        MetroText(
                            "输入 6 位数字，或从下面挑一个房间",
                            color = colors.onSurfaceVariant.copy(alpha = 0.65f),
                            style = TextStyle(fontSize = 14.sp),
                        )
                    }
                    inner()
                }
            },
        )

        // ---------------------------------------------------------------- 连接方式
        SectionLabel("连接方式")
        TransportTabs(mode) { mode = it }

        when (mode) {
            TransportMode.Relay -> {
                Hint("双方都能上网即可，走我们的中继服务器（默认 $base）")
                ActionRow(
                    "创建房间" to {
                        val c = TogetherSession.newRoomCode()
                        code = c
                        TogetherSession.create(base, c, ips)
                    },
                    "加入房间" to {
                        if (code.isNotBlank()) TogetherSession.join(base, code, ips)
                        else TogetherSessionStatus.note("先输入房间码")
                    },
                    primaryFirst = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    TextAction(if (scanning) "扫描中…" else "扫描附近房间", accent = false) { scan() }
                    TextAction("检查中继连接", accent = false) { scan() }
                }
            }

            TransportMode.Lan -> {
                Hint("同一个 Wi-Fi 或热点，不经过任何服务器，最稳")
                ActionRow(
                    "局域网建房" to {
                        val addr = TogetherSession.createLan()
                        if (addr != null) {
                            code = addr
                            copy(addr, "已复制 $addr，把这句话发给朋友即可")
                        }
                    },
                    "局域网加入" to {
                        if (code.contains(':')) TogetherSession.joinLan(code)
                        else TogetherSessionStatus.note("房间码要填「主机IP:端口」")
                    },
                    primaryFirst = true,
                )
            }

            TransportMode.P2p -> {
                Hint("无服务器打洞；家用宽带成功率高，移动数据基本打不通")
                ActionRow(
                    "P2P 建房" to {
                        // STUN 查询是网络操作：必须在 IO 上跑，否则 NetworkOnMainThreadException
                        scope.launch {
                            val c = withContext(Dispatchers.IO) { TogetherSession.createP2p() }
                            if (c != null) {
                                code = c
                                copy(c, "已复制 P2P 房间码 $c（家用宽带成功率最高）")
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
                    primaryFirst = true,
                )
            }
        }

        // ---------------------------------------------------------------- 活跃房间
        if (rooms.isNotEmpty()) {
            SectionLabel("活跃房间")
            rooms.forEach { r ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceVariant, RectangleShape)
                        .clickable {
                            code = r.roomId
                            TogetherSession.join(base, r.roomId, ips)
                        }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MetroText(
                            r.roomId,
                            color = colors.onBackground,
                            style = TextStyle(
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                        Spacer(Modifier.width(14.dp))
                        MetroText(
                            "房主 ${r.host.ifBlank { "?" }} · ${r.members} 人",
                            color = colors.onSurfaceVariant,
                            style = TextStyle(fontSize = 12.sp),
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------- 中继设置
        SectionLabel("中继设置")
        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant, RectangleShape)
                .clickable { editingBase = !editingBase; baseDraft = base; ipsDraft = ips.joinToString(",") }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column {
                MetroText(
                    if (editingBase) "中继地址" else "$base　·　点击修改",
                    color = if (editingBase) colors.onBackground else colors.onSurfaceVariant,
                    style = TextStyle(fontSize = 12.sp),
                )
                if (editingBase) {
                    Spacer(Modifier.height(8.dp))
                    BasicTextField(
                        value = baseDraft,
                        onValueChange = { baseDraft = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = colors.onBackground),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    MetroText(
                        "固定 IP（可空；只有域名被污染时才需要）",
                        color = colors.onSurfaceVariant,
                        style = TextStyle(fontSize = 11.sp),
                    )
                    BasicTextField(
                        value = ipsDraft,
                        onValueChange = { ipsDraft = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = colors.onBackground),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    ActionRow(
                        "保存" to {
                            base = TogetherPrefs.normalize(baseDraft)
                            TogetherPrefs.setBase(ctx, base)
                            TogetherPrefs.setIps(ctx, ipsDraft)
                            ips = TogetherPrefs.ips(ctx)
                            editingBase = false
                            TogetherSessionStatus.note("中继已改为 $base")
                        },
                        "恢复默认" to {
                            base = TogetherPrefs.DEFAULT_BASE
                            TogetherPrefs.setBase(ctx, base)
                            TogetherPrefs.setIps(ctx, "")
                            ips = emptyList()
                            editingBase = false
                            TogetherSessionStatus.note("中继已恢复默认")
                        },
                        primaryFirst = true,
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))
        MetroText(
            "异地连不上时：① 一方开热点，另一方连上，用「局域网」；" +
                "② 双方装 Tailscale，把 100.x 地址填进「局域网加入」；" +
                "③ 有自己域名的话，把中继换成它。",
            color = colors.onSurfaceVariant.copy(alpha = 0.75f),
            style = TextStyle(fontSize = 11.sp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** 传输方式：界面上按它分页签，避免八个按钮糊在一起。 */
private enum class TransportMode(val label: String) {
    Relay("中继"),
    Lan("局域网"),
    P2p("P2P"),
}

@Composable
private fun TransportTabs(current: TransportMode, onSelect: (TransportMode) -> Unit) {
    val colors = LocalMetroColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TransportMode.entries.forEach { m ->
            val active = m == current
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onSelect(m) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MetroText(
                    m.label,
                    color = if (active) colors.onBackground else colors.onSurfaceVariant,
                    style = TextStyle(fontSize = 14.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal),
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (active) 2.dp else 1.dp)
                        .background(if (active) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.25f)),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    val colors = LocalMetroColors.current
    MetroText(
        text,
        color = colors.onSurfaceVariant,
        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold),
    )
}

@Composable
private fun Hint(text: String) {
    val colors = LocalMetroColors.current
    MetroText(text, color = colors.onSurfaceVariant.copy(alpha = 0.8f), style = TextStyle(fontSize = 11.sp))
}

@Composable
private fun Card(bold: Boolean = false, content: @Composable () -> Unit) {
    val colors = LocalMetroColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant, RectangleShape)
            .then(
                if (bold) Modifier.border(2.dp, colors.primary, RectangleShape) else Modifier,
            )
            .padding(16.dp),
    ) { Column { content() } }
}

@Composable
private fun TextAction(label: String, accent: Boolean = true, onClick: () -> Unit) {
    val colors = LocalMetroColors.current
    Box(
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        MetroText(
            label,
            color = if (accent) colors.primary else colors.onSurfaceVariant,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold),
        )
    }
}

/** 一行两个按钮：左实心右描边（`primaryFirst` 决定谁是实心）。 */
@Composable
private fun ActionRow(
    left: Pair<String, () -> Unit>,
    right: Pair<String, () -> Unit>,
    primaryFirst: Boolean,
) {
    val colors = LocalMetroColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(left, right).forEachIndexed { index, (label, action) ->
            val filled = (index == 0) == primaryFirst
            Box(
                Modifier
                    .weight(1f)
                    .background(if (filled) colors.primary else colors.surfaceVariant, RectangleShape)
                    .then(
                        if (filled) Modifier else Modifier.border(1.dp, colors.onSurfaceVariant.copy(alpha = 0.35f)),
                    )
                    .clickable(onClick = action)
                    .padding(vertical = 14.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                MetroText(
                    label,
                    color = if (filled) colors.onPrimary else colors.onBackground,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}
