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
    private const val SEEK_TOLERANCE_MS = 800L

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

    /** MainScreen 在启动时注入播放器能力。 */
    fun bind(playbackSource: PlaybackSource?) {
        source = playbackSource
    }

    /** 房主建房：房间码由调用方给（6 位数字），连上后开始广播。 */
    fun create(base: String, code: String, fixedIps: List<String> = emptyList()) {
        leave()
        _isHost.value = true
        _roomId.value = code
        _status.value = "连接中…"
        val ws = RoomLink.Wss(RoomDirectory.roomUrl(base, code, "host", "房主"), fixedIps = fixedIps)
        ws.onOpen = {
            _status.value = "已创建房间 $code"
            startHostLoop(ws)
        }
        ws.onClosed = { c, r ->
            if (link === ws) {
                _status.value = "断开（$c ${r.take(24)}）"
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
        link = ws
    }

    /** 成员加入：收到房主快照就跟随。 */
    fun join(base: String, code: String, fixedIps: List<String> = emptyList()) {
        leave()
        _isHost.value = false
        _roomId.value = code
        _status.value = "连接中…"
        val ws = RoomLink.Wss(RoomDirectory.roomUrl(base, code, "member", "成员"), fixedIps = fixedIps)
        ws.onOpen = { _status.value = "已加入房间 $code" }
        ws.onClosed = { c, r ->
            if (link === ws) {
                _status.value = "断开（$c ${r.take(24)}）"
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

    private fun startHostLoop(ws: RoomLink) {
        stopHostLoop()
        hostJob = scope.launch {
            while (true) {
                val snap = source?.current()
                if (snap != null) {
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
                        stampedAtMs = nowMs(),
                    )
                    ws.send(RoomWire.Snapshot(state))
                }
                delay(HOST_TICK_MS)
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
