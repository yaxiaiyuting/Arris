/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.listen

import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 一起听需要播放器提供的最小能力。由 MainScreen 实现并注入，避免把 ViewModel 拖进 listen 包。 */
interface PlaybackSource {
    /** 当前播放状态；没有在播的歌时返回 null。 */
    fun current(): PlaybackSnapshot?
    /** 跟随房主：把本地播放器调到给定状态。 */
    fun follow(snapshot: PlaybackSnapshot)
}

/** 一个"正在播什么"的快照（跨源无关，只用展示字段 + 进度）。 */
data class PlaybackSnapshot(
    val title: String,
    val artist: String,
    val positionMs: Long,
    val playing: Boolean,
    /**
     * 歌曲的**平台 ID** 与音源 key（v3.8.1）。
     *
     * 为什么必须带：成员侧原来只按 `title` 字符串对齐，一旦两边音源/命名不同就永远播不上，
     * 于是表现为「连接正常但不同步播放」。带上 id 后成员可以直接按 id 起播，
     * 搜索匹配只作为兜底。
     */
    val songId: Long? = null,
    val sourceKey: String? = null,
)

/**
 * 一起听会话（v3.8.0）。
 *
 * ## 职责边界
 *
 * - **房主**：每秒把本地播放状态打包成 `Snapshot` 广播；**只有房主能改状态**。
 * - **成员**：收到 `Snapshot` 只做跟随（seek / play / pause），不产生操作。
 * - 传输：Cloudflare Worker 中继（[RoomRelayWs]）；协议：[RoomWire]。
 *
 * ## 为什么成员侧只在"偏差够大"时才 seek
 *
 * 每秒一次的快照 + 网络抖动会让 position 一直有几十毫秒误差。每次都 seek 会
 * 反复打断播放（听感上就是"咔哒"）。所以：
 * - `|目标 - 当前| ≤ 800ms` → 什么都不做（靠音频本身的连续性）
 * - 超过就 seek 一次；播放/暂停状态不一致则立即纠正
 */
object TogetherSession {

    private const val TAG = "Together"
    private const val HOST_TICK_MS = 1_000L

    /** 房主侧轮询间隔：只读 StateFlow，120ms 足够细，切歌能立刻上送。 */
    private const val HOST_POLL_MS = 120L

    /** 进度与「按上次快照推算的位置」偏差超过它就算跳变（seek），立刻补发。 */
    private const val DRIFT_SEND_MS = 400L
    private const val SEEK_TOLERANCE_MS = 800L

    /** 界面诊断区保留的行数。 */
    private const val DIAG_LINES = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var hostJob: Job? = null
    private var link: RoomLink? = null
    private var source: PlaybackSource? = null

    /** 供 UI 观察的状态文本 + 房间信息。 */
    private val _status = MutableStateFlow("未连接")
    val status: StateFlow<String> = _status
    private val _roomId = MutableStateFlow("")
    val roomId: StateFlow<String> = _roomId
    private val _members = MutableStateFlow(0)
    val members: StateFlow<Int> = _members
    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost

    val connected: Boolean get() = link != null

    // ---------------------------------------------------------------- 诊断
    /**
     * 最近若干条会话事件（v3.8.1）。
     *
     * 为什么要有它：一起听涉及「房主 → 中继 → 成员 → 播放器」四段，
     * 任何一段断掉，用户看到的都是同一个「不同步」。真机排查时只能靠 logcat +
     * 猜坐标，一次就是十分钟。把关键事件直接画在状态卡下面，
     * 一张截图就能判断断在哪一段。
     */
    private val _diag = MutableStateFlow<List<String>>(emptyList())
    val diag: StateFlow<List<String>> = _diag

    internal fun trace(tag: String, detail: String = "") {
        val line = if (detail.isBlank()) tag else "$tag $detail"
        _diag.value = (_diag.value + line).takeLast(DIAG_LINES)
        // 同一份事件也进 logcat：界面上看得到，远程也能读
        Log.i("TogetherDiag", line)
    }

    /** MainScreen 在启动时注入播放器能力。 */
    fun bind(playbackSource: PlaybackSource?) {
        source = playbackSource
    }

    /** 房主建房：房间码由调用方给（6 位数字），连上后开始广播。 */
    fun create(base: String, code: String, fixedIps: List<String> = emptyList()) {
        trace("建房", "$code @ ${base.removePrefix("https://").removePrefix("wss://")}")
        leave()
        _isHost.value = true
        _roomId.value = code
        _status.value = "连接中…"
        val ws = RoomLink.Wss(RoomDirectory.roomUrl(base, code, "host", "房主"), fixedIps = fixedIps)
        ws.onOpen = {
            _status.value = "已创建房间 $code"
            trace("房主已连接", "开始广播")
            startHostLoop(ws)
        }
        // 必须**先**登记 link 再 connect：连接可能瞬间失败，回调里要靠它判断
        // 「这次失败是不是当前连接」。反过来写的话状态会永远停在「连接中…」。
        link = ws
        ws.onClosed = { c, r ->
            if (link === ws) {
                _status.value = closeReason(c, r)
                trace("连接断开", "$c ${r.take(16)}")
                stopHostLoop()
                link = null
            }
        }
        ws.onMessage = { msg ->
            when (msg) {
                is RoomWire.Ping -> ws.send(RoomWire.Pong(msg.t0, nowMs()))
                is RoomWire.Presence -> _members.value = msg.members
                else -> Unit
            }
        }
        ws.connect()
    }

    /** 成员加入：收到房主快照就跟随。 */
    fun join(base: String, code: String, fixedIps: List<String> = emptyList()) {
        trace("加入", "$code @ ${base.removePrefix("https://").removePrefix("wss://")}")
        leave()
        _isHost.value = false
        _roomId.value = code
        _status.value = "连接中…"
        val ws = RoomLink.Wss(RoomDirectory.roomUrl(base, code, "member", "成员"), fixedIps = fixedIps)
        ws.onOpen = {
            _status.value = "已加入房间 $code"
            trace("成员已连接")
        }
        link = ws
        ws.onClosed = { c, r ->
            if (link === ws) {
                _status.value = closeReason(c, r)
                trace("连接断开", "$c ${r.take(16)}")
                link = null
            }
        }
        ws.onMessage = { msg ->
            when (msg) {
                is RoomWire.Snapshot -> followSnapshot(msg.state)
                is RoomWire.Ping -> ws.send(RoomWire.Pong(msg.t0, nowMs()))
                is RoomWire.Presence -> {
                    _members.value = msg.members
                    if (msg.role == "host" && !msg.left && msg.name.isNotBlank()) {
                        _status.value = "已加入房间 ${_roomId.value}（房主 ${msg.name}）"
                    }
                }
                is RoomWire.Closed -> {
                    _status.value = when (msg.reason) {
                        "host-left" -> "房主已离开，房间结束"
                        "no-host" -> "房间里没有房主了（可能已结束），换个房间吧"
                        else -> "房间已结束"
                    }
                    _members.value = 0
                }
                is RoomWire.Hello -> Unit
                else -> Unit
            }
        }
        ws.connect()
        link = ws
    }

    /**
     * 局域网建房（无需任何服务器）：本机监听，房间码就是 `IP:端口`。
     * 返回连接串供界面展示（找不到局域网地址时返回 null）。
     */
    fun createLan(): String? {
        leave()
        _isHost.value = true
        val lan = RoomLink.Lan.host()
        _roomId.value = "LAN"
        _status.value = "局域网房间启动中…"
        lan.onMessage = { msg ->
            when (msg) {
                is RoomWire.Ping -> lan.send(RoomWire.Pong(msg.t0, nowMs()))
                is RoomWire.Presence -> _members.value = msg.members
                else -> Unit
            }
        }
        lan.onClosed = { _, _ -> }
        lan.startHost()
        startHostLoop(lan)
        link = lan
        val addr = lan.address()
        _status.value = if (addr != null) "局域网房间：$addr（同一 WiFi/热点可加入）" else "局域网房间已启动，但没找到局域网地址"
        return addr
    }

    /** 局域网加入：`ip:port`。 */
    fun joinLan(address: String): Boolean {
        val host = address.substringBeforeLast(':', "")
        val port = address.substringAfterLast(':', "").toIntOrNull() ?: 0
        if (host.isBlank() || port <= 0) {
            _status.value = "地址格式应为 IP:端口"
            return false
        }
        leave()
        _isHost.value = false
        _roomId.value = "LAN"
        _status.value = "连接 $address …"
        val lan = runCatching { RoomLink.Lan.join(host, port) }.getOrElse {
            _status.value = "连不上 $address（${it.message}）"
            return false
        }
        lan.onMessage = { msg ->
            when (msg) {
                is RoomWire.Snapshot -> followSnapshot(msg.state)
                is RoomWire.Ping -> lan.send(RoomWire.Pong(msg.t0, nowMs()))
                is RoomWire.Presence -> _members.value = msg.members
                else -> Unit
            }
        }
        lan.onOpen = { _status.value = "已加入局域网 $address" }
        lan.startMember()
        link = lan
        return true
    }

    /**
     * P2P 建房（无服务器、不装第三方软件）：绑定 UDP、问 STUN、返回房间码 `公网IP:端口#令牌`。
     * 打洞成功率取决于 NAT 类型：家用锥形 NAT 通常可以，移动数据/CGNAT 基本不行。
     */
    fun createP2p(): String? {
        leave()
        _isHost.value = true
        _roomId.value = "P2P"
        val link = P2pLink.host(P2pLink.newToken())
        link.onMessage = { msg ->
            when (msg) {
                is RoomWire.Ping -> link.send(RoomWire.Pong(msg.t0, nowMs()))
                is RoomWire.Presence -> _members.value = msg.members
                else -> Unit
            }
        }
        val code = link.startHost()
        startHostLoop(link)
        this.link = link
        _status.value = "P2P 房间码：${code ?: "（获取公网地址失败）"}"
        return code
    }

    /** P2P 加入：房间码 `公网IP:端口#令牌`。 */
    fun joinP2p(code: String): Boolean {
        val parsed = P2pLink.parseCode(code) ?: run {
            _status.value = "房间码格式应为 IP:端口#令牌"
            return false
        }
        leave()
        _isHost.value = false
        _roomId.value = "P2P"
        _status.value = "正在打洞连接 ${parsed.first}:${parsed.second} …"
        val link = P2pLink.join(parsed.third)
        link.onMessage = { msg ->
            when (msg) {
                is RoomWire.Snapshot -> followSnapshot(msg.state)
                is RoomWire.Ping -> link.send(RoomWire.Pong(msg.t0, nowMs()))
                is RoomWire.Presence -> _members.value = msg.members
                else -> Unit
            }
        }
        link.onOpen = { _status.value = "P2P 已连通（${parsed.first}:${parsed.second}）" }
        link.startMember(parsed.first, parsed.second)
        this.link = link
        return true
    }

    fun leave() {
        stopHostLoop()
        link?.close()
        link = null
        _members.value = 0
        if (_status.value != "未连接") _status.value = "未连接"
    }

    // ---------------------------------------------------------------- 房主

    /**
     * 房主广播循环（v3.8.1 改为**变化即发**）。
     *
     * 旧实现固定每 1000ms 发一次：房主切歌后，成员最多要等一秒才收到新快照，
     * 听起来就是「切歌慢半拍、上报太慢」。现在分两种触发：
     *
     * - **变化即发**：歌曲（id/标题）、播放态、进度跳变（seek 或者自然走位偏差 >400ms）
     *   任一项变化，立刻发一条，不等节拍；
     * - **心跳**：没有变化时仍按 [HOST_TICK_MS] 发一次，用来抵消时钟漂移、
     *   也给新加入的成员一个基准（对方一进来就能对齐）。
     *
     * 轮询间隔压到 [HOST_POLL_MS]（只读 StateFlow，代价可以忽略），
     * 这样切歌在 120ms 内就会触发上送。
     */
    private fun startHostLoop(ws: RoomLink) {
        stopHostLoop()
        hostJob = scope.launch {
            var lastSnap: PlaybackSnapshot? = null
            var lastSentAt = 0L
            while (true) {
                val snap = source?.current()
                if (snap != null) {
                    val now = nowMs()
                    val prev = lastSnap
                    val jumped = prev != null &&
                        kotlin.math.abs(snap.positionMs - (prev.positionMs + (now - lastSentAt))) > DRIFT_SEND_MS
                    val changed = prev == null ||
                        prev.songId != snap.songId ||
                        prev.title != snap.title ||
                        prev.playing != snap.playing ||
                        jumped
                    if (changed || now - lastSentAt >= HOST_TICK_MS) {
                        val track = CanonicalTrack(
                            roomTrackId = "rt-${Integer.toHexString(snap.title.hashCode())}",
                            title = snap.title,
                            artist = snap.artist,
                            album = "",
                            durationMs = 0,
                            editionKinds = emptySet(),
                            publicIds = buildMap {
                                snap.songId?.let { put("songId", it.toString()) }
                                snap.sourceKey?.let { put("source", it) }
                            },
                        )
                        val state = RoomState(
                            roomId = _roomId.value,
                            epoch = 0L,
                            seq = seq++,
                            hostUserId = "host",
                            members = emptyList(),
                            queue = listOf(track),
                            currentRoomTrackId = track.roomTrackId,
                            transport = RoomTransport(snap.playing, snap.positionMs, 1f),
                            stampedAtMs = now,
                        )
                        ws.send(RoomWire.Snapshot(state))
                        lastSnap = snap
                        lastSentAt = now
                    }
                }
                delay(HOST_POLL_MS)
            }
        }
    }

    private fun stopHostLoop() {
        hostJob?.cancel()
        hostJob = null
    }

    // ---------------------------------------------------------------- 成员

    private fun followSnapshot(state: RoomState) {
        val src = source ?: return
        val track = state.queue.firstOrNull()
        val target = state.transport.positionMs + (nowMs() - state.stampedAtMs)
        val remote = PlaybackSnapshot(
            title = track?.title.orEmpty(),
            artist = track?.artist.orEmpty(),
            positionMs = target,
            playing = state.transport.playing,
            songId = track?.publicIds?.get("songId")?.toLongOrNull(),
            sourceKey = track?.publicIds?.get("source")?.takeIf { it.isNotBlank() },
        )
        // 本地可能**根本没有在播的歌**（刚进房间的常态）。旧实现在这里 `?: return`，
        // 于是整条快照被丢掉 —— 这就是「连上了但不同步」的根因。
        val local = src.current()
        if (!FollowPolicy.isSameSong(local, remote)) {
            trace("收到切歌", "${remote.title} id=${remote.songId ?: "?"}")
            src.follow(remote)
            _status.value = "房主在放：${remote.title}"
            return
        }
        if (FollowPolicy.needsSeek(local, remote, SEEK_TOLERANCE_MS)) {
            src.follow(local!!.copy(positionMs = target, playing = state.transport.playing))
        } else if (FollowPolicy.needsTransportFix(local, remote)) {
            src.follow(local!!.copy(playing = state.transport.playing))
        }
    }

    private var seq = 0L
    /** 把 WebSocket 关闭码翻译成人话（-1 = 连接层失败，没有 HTTP 响应）。 */
    private fun closeReason(code: Int, reason: String): String = when {
        code == -1 -> "连不上中继（网络被重置或超时）"
        code == 1000 -> "已退出房间"
        code == 4001 -> "房间已满"
        else -> "断开（$code ${reason.take(20)}）"
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    /** 供播放适配层回写提示（不改变连接状态）。 */
    internal fun noteExternal(text: String) {
        _status.value = text
    }

    /** 生成一个 6 位房间码。 */
    fun newRoomCode(): String = (100000..999999).random().toString()
    @Suppress("unused") private val sessionUuid = UUID.randomUUID()
    private fun log(msg: String) = Log.i(TAG, msg)
}

/** 让播放适配层能把"本地没有这首歌"之类的提示回写到会话状态。 */
object TogetherSessionStatus {
    fun note(text: String) = TogetherSession.noteExternal(text)
}
