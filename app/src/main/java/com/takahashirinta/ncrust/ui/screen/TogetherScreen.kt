/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import com.takahashirinta.ncrust.ui.theme.AppShapes
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors
import com.takahashirinta.ncrust.ui.components.ArrisPageHeader
import com.takahashirinta.ncrust.ui.components.ArrisButton
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
    val diag by TogetherSession.diag.collectAsState()
    val nextUp by TogetherSession.nextUp.collectAsState()

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

    val state = TogetherUi(code, base, editingBase, baseDraft, ipsDraft, rooms, scanning, mode,
        status, room, host, members, diag, nextUp)
    val actions = TogetherActions(
        back = onBack,
        code = { code = it.filterNot(Char::isWhitespace).take(160) },
        mode = { mode = it },
        copyInvite = { copy(room, "已复制房间码 $room") },
        leave = { TogetherSession.leave() },
        scan = { scan() },
        create = {
            when (mode) {
                TransportMode.Relay -> {
                    val c = TogetherSession.newRoomCode(); code = c
                    TogetherSession.create(base, c, ips)
                }
                TransportMode.Lan -> TogetherSession.createLan()?.let {
                    code = it; copy(it, "已复制 $it，把这句话发给朋友即可")
                }
                TransportMode.P2p -> scope.launch {
                    val c = withContext(Dispatchers.IO) { TogetherSession.createP2p() }
                    if (c != null) { code = c; copy(c, "已复制 P2P 房间码 $c") }
                }.let { }
            }
        },
        join = {
            when (mode) {
                TransportMode.Relay -> if (code.isNotBlank()) TogetherSession.join(base, code, ips)
                    else TogetherSessionStatus.note("先输入房间码")
                TransportMode.Lan -> if (code.contains(':')) TogetherSession.joinLan(code)
                    else TogetherSessionStatus.note("房间码要填「主机IP:端口」")
                TransportMode.P2p -> if (code.contains('#')) {
                    scope.launch { withContext(Dispatchers.IO) { TogetherSession.joinP2p(code) } }
                } else TogetherSessionStatus.note("P2P 房间码形如 1.2.3.4:5678#123456")
            }
        },
        joinRoom = { r -> code = r.roomId; TogetherSession.join(base, r.roomId, ips) },
        toggleEdit = { editingBase = !editingBase; baseDraft = base; ipsDraft = ips.joinToString(",") },
        base = { baseDraft = it }, ips = { ipsDraft = it },
        save = {
            base = TogetherPrefs.normalize(baseDraft)
            TogetherPrefs.setBase(ctx, base); TogetherPrefs.setIps(ctx, ipsDraft)
            ips = TogetherPrefs.ips(ctx); editingBase = false
            TogetherSessionStatus.note("中继已改为 $base")
        },
        reset = {
            base = TogetherPrefs.DEFAULT_BASE
            TogetherPrefs.setBase(ctx, base); TogetherPrefs.setIps(ctx, "")
            ips = emptyList(); editingBase = false
            TogetherSessionStatus.note("中继已恢复默认")
        },
    )
    BackHandler(onBack = onBack)
    TogetherContent(state, actions)
}

/** Rendering snapshots carry values only; all remembered state and IO stay in TogetherScreen. */
private data class TogetherUi(val code: String, val base: String, val editingBase: Boolean,
    val baseDraft: String, val ipsDraft: String, val rooms: List<RoomSummary>, val scanning: Boolean,
    val mode: TransportMode, val status: String, val room: String, val host: Boolean,
    val members: Int, val diag: List<String>, val nextUp: String?)

private class TogetherActions(val back: () -> Unit, val code: (String) -> Unit,
    val mode: (TransportMode) -> Unit, val copyInvite: () -> Unit, val leave: () -> Unit,
    val scan: () -> Unit, val create: () -> Unit, val join: () -> Unit,
    val joinRoom: (RoomSummary) -> Unit, val toggleEdit: () -> Unit,
    val base: (String) -> Unit, val ips: (String) -> Unit, val save: () -> Unit, val reset: () -> Unit)

@Composable
private fun TogetherContent(state: TogetherUi, actions: TogetherActions) {
    val c = LocalMetroColors.current
    Box(Modifier.fillMaxSize().background(c.background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize().statusBarsPadding()
            .verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ArrisPageHeader("一起听", "ARRIS / TOGETHER", "相隔很远，也能听同一首。", actions.back)
            MetroText("分享此刻的旋律，账号留在自己的设备。", color = c.onSurfaceVariant,
                style = TextStyle(fontSize = 13.sp, lineHeight = 20.sp))
            RoomStatusCard(state, actions)
            SectionLabel("连接方式")
            TransportTabs(state.mode, actions.mode)
            if (state.room.isBlank()) RoomInviteField(state, actions.code)
            TransportControls(state, actions)
            AvailableRooms(state.rooms, actions.joinRoom)
            SectionLabel("中继设置")
            RelaySettingsPanel(state, actions)
            Hint("同一 Wi-Fi 或热点优先使用局域网；严格 NAT 环境直连困难时，可以使用中继或已配置的虚拟局域网。")
            if (state.diag.isNotEmpty()) {
                SectionLabel("连接诊断")
                state.diag.forEach { Hint(it) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RoomStatusCard(s: TogetherUi, a: TogetherActions) {
    val c = LocalMetroColors.current
    Card(bold = s.room.isNotBlank()) {
        MetroText(if (s.room.isBlank()) "尚未连接" else if (s.host) "房主 · 在线 ${s.members} 人" else "已连接 · 在线 ${s.members} 人",
            color = c.primary, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(12.dp))
        MetroText(s.room.ifBlank { "为朋友留一个位置。" }, color = c.onBackground,
            style = TextStyle(fontSize = if (s.room.isBlank()) 22.sp else 26.sp,
                lineHeight = 34.sp, fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(8.dp))
        MetroText(s.status, color = c.onSurfaceVariant, style = TextStyle(fontSize = 13.sp, lineHeight = 21.sp))
        s.nextUp?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(10.dp))
            MetroText("接下来 · $it", color = c.primary, style = TextStyle(fontSize = 13.sp))
        }
        if (s.room.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                TextAction("复制邀请", onClick = a.copyInvite)
                TextAction("退出房间", accent = false, onClick = a.leave)
            }
        }
    }
}

@Composable
private fun RoomInviteField(s: TogetherUi, change: (String) -> Unit) {
    val c = LocalMetroColors.current
    SectionLabel("房间邀请")
    BasicTextField(s.code, onValueChange = change, singleLine = true,
        textStyle = TextStyle(fontSize = 18.sp, color = c.onBackground, fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "房间邀请或地址" },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().clip(AppShapes.large).background(c.surface)
                .border(1.dp, LocalNcrustColors.current.outlineVariant, AppShapes.large)
                .heightIn(min = 56.dp).padding(16.dp), contentAlignment = Alignment.CenterStart) {
                if (s.code.isEmpty()) MetroText(when (s.mode) {
                    TransportMode.Relay -> "输入房间码"
                    TransportMode.Lan -> "主机 IP:端口"
                    TransportMode.P2p -> "IP:端口#令牌"
                }, color = c.onSurfaceVariant, style = TextStyle(fontSize = 14.sp))
                inner()
            }
        })
}

@Composable
private fun TransportControls(s: TogetherUi, a: TogetherActions) {
    Hint(when (s.mode) {
        TransportMode.Relay -> "双方都能访问同一个中继即可，不需要直接连接彼此。"
        TransportMode.Lan -> "连接同一个 Wi-Fi 或热点，音频与账号不经过中继。"
        TransportMode.P2p -> "无服务器直连；连接成功率取决于双方网络和 NAT。"
    })
    val create = when (s.mode) { TransportMode.Relay -> "创建房间"; TransportMode.Lan -> "局域网建房"; TransportMode.P2p -> "P2P 建房" }
    val join = when (s.mode) { TransportMode.Relay -> "加入房间"; TransportMode.Lan -> "局域网加入"; TransportMode.P2p -> "P2P 加入" }
    ActionRow(create to a.create, join to a.join, primaryFirst = true)
    if (s.mode == TransportMode.Relay) {
        TextAction(if (s.scanning) "扫描中…" else "扫描可用房间", accent = false, onClick = a.scan)
    }
}

@Composable
private fun AvailableRooms(rooms: List<RoomSummary>, join: (RoomSummary) -> Unit) {
    if (rooms.isEmpty()) return
    val c = LocalMetroColors.current
    SectionLabel("活跃房间")
    rooms.forEach { room ->
        Column(Modifier.fillMaxWidth().clip(AppShapes.large).background(c.surface)
            .clickable(role = Role.Button) { join(room) }.padding(16.dp)) {
            MetroText(room.roomId, color = c.onBackground, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
            MetroText("房主 ${room.host.ifBlank { "?" }} · ${room.members} 人", color = c.onSurfaceVariant,
                style = TextStyle(fontSize = 13.sp, lineHeight = 21.sp))
        }
    }
}

@Composable
private fun RelaySettingsPanel(s: TogetherUi, a: TogetherActions) {
    val c = LocalMetroColors.current
    Column(Modifier.fillMaxWidth().clip(AppShapes.large).background(c.surface).padding(16.dp)) {
        MetroText(if (s.editingBase) "中继地址 · 点击收起" else "中继地址 · 点击修改\n${s.base}",
            color = c.onSurfaceVariant, style = TextStyle(fontSize = 13.sp, lineHeight = 22.sp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = a.toggleEdit))
        if (s.editingBase) {
            RelayFields(s, a)
            Spacer(Modifier.height(12.dp))
            ActionRow("保存" to a.save, "恢复默认" to a.reset, primaryFirst = true)
        }
    }
}

@Composable
private fun RelayFields(s: TogetherUi, a: TogetherActions) {
    val c = LocalMetroColors.current
    BasicTextField(s.baseDraft, onValueChange = a.base, singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, color = c.onBackground),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp)
            .semantics { contentDescription = "中继地址" })
    Spacer(Modifier.height(8.dp))
    Hint("固定 IP（可空；仅在域名解析异常时使用）")
    BasicTextField(s.ipsDraft, onValueChange = a.ips, singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, color = c.onBackground),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp)
            .semantics { contentDescription = "中继固定 IP" })
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
    Row(Modifier.fillMaxWidth().clip(AppShapes.full).background(colors.surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TransportMode.entries.forEach { mode ->
            val active = mode == current
            Box(Modifier.weight(1f).clip(AppShapes.full)
                .background(if (active) colors.primary else colors.surface)
                .selectable(selected = active, role = Role.Tab, onClick = { onSelect(mode) })
                .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                MetroText(mode.label, color = if (active) colors.onPrimary else colors.onSurfaceVariant,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
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
        style = TextStyle(fontSize = 12.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.Bold),
    )
}

@Composable
private fun Hint(text: String) {
    val colors = LocalMetroColors.current
    MetroText(text, color = colors.onSurfaceVariant, style = TextStyle(fontSize = 13.sp, lineHeight = 21.sp))
}

@Composable
private fun Card(bold: Boolean = false, content: @Composable () -> Unit) {
    val colors = LocalMetroColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(AppShapes.large)
                        .background(colors.surface)
            .then(
                if (bold) Modifier.border(1.dp, colors.primary, AppShapes.large) else Modifier,
            )
            .padding(20.dp),
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(left, right).forEachIndexed { index, (label, action) ->
            ArrisButton(label, Modifier.weight(1f), primary = (index == 0) == primaryFirst, onClick = action)
        }
    }
}
