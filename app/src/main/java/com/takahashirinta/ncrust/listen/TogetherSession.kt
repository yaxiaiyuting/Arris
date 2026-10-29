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
    private var relay: RoomRelayWs? = null
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

    val connected: Boolean get() = relay != null

    /** MainScreen 在启动时注入播放器能力。 */
    fun bind(playbackSource: PlaybackSource?) {
        source = playbackSource
    }

    /** 房主建房：房间码由调用方给（6 位数字），连上后开始广播。 */
    fun create(base: String, code: String) {
        leave()
        _isHost.value = true
        _roomId.value = code
        _status.value = "连接中…"
        val ws = RoomRelayWs(RoomDirectory.roomUrl(base, code, "host", "房主"))
        ws.onOpen = {
            _status.value = "已创建房间 $code"
            startHostLoop(ws)
        }
        ws.onClosed = { c, r ->
            if (relay === ws) {
                _status.value = "断开（$c ${r.take(24)}）"
                stopHostLoop()
                relay = null
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
        relay = ws
    }

    /** 成员加入：收到房主快照就跟随。 */
    fun join(base: String, code: String) {
        leave()
        _isHost.value = false
        _roomId.value = code
        _status.value = "连接中…"
        val ws = RoomRelayWs(RoomDirectory.roomUrl(base, code, "member", "成员"))
        ws.onOpen = { _status.value = "已加入房间 $code" }
        ws.onClosed = { c, r ->
            if (relay === ws) {
                _status.value = "断开（$c ${r.take(24)}）"
                relay = null
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
        relay = ws
    }

    fun leave() {
        stopHostLoop()
        relay?.close()
        relay = null
        _members.value = 0
        if (_status.value != "未连接") _status.value = "未连接"
    }

    // ---------------------------------------------------------------- 房主

    private fun startHostLoop(ws: RoomRelayWs) {
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
                        publicIds = emptyMap(),
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
        val local = src.current() ?: return
        val target = state.transport.positionMs + (nowMs() - state.stampedAtMs)
        val sameSong = local.title == state.queue.firstOrNull()?.title
        if (!sameSong) {
            // 换歌：直接把本地播放器拉到目标曲目与进度
            src.follow(
                PlaybackSnapshot(
                    title = state.queue.firstOrNull()?.title.orEmpty(),
                    artist = state.queue.firstOrNull()?.artist.orEmpty(),
                    positionMs = target,
                    playing = state.transport.playing,
                )
            )
            _status.value = "房主在放：${state.queue.firstOrNull()?.title.orEmpty()}"
            return
        }
        if (kotlin.math.abs(target - local.positionMs) > SEEK_TOLERANCE_MS) {
            src.follow(local.copy(positionMs = target, playing = state.transport.playing))
        } else if (local.playing != state.transport.playing) {
            src.follow(local.copy(playing = state.transport.playing))
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
