/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.listen

import org.json.JSONArray
import org.json.JSONObject

/**
 * 房间线上格式（v3.8.0 · 一起听）。
 *
 * ## 为什么用「换行分隔的 JSON」而不是 WebSocket
 *
 * 一起听的负载是**低频小对象**（切歌、暂停、每 2 秒一次时钟探测），WebSocket 的帧开销与
 * 握手在这里没有收益，而引入服务端 WS 实现会打破"零新依赖"。一条 TCP 连接 + 每行一个
 * JSON 足够，且用 `BufferedReader.readLine()` 就能跑在 JVM 单测里。
 *
 * ## 协议（每条消息一行）
 *
 * | 方向 | 类型 | 字段 |
 * |---|---|---|
 * | 任意→任意 | `hello` | `userId` |
 * | 房主→所有人 | `snapshot` | `state` |
 * | 成员→房主 | `op` | `op`（由房主裁决，成员不能自己改状态） |
 * | 成员→房主 | `ping` | `t0`（成员单调时钟） |
 * | 房主→成员 | `pong` | `t0` + `tr`（房主单调时钟） |
 *
 * `ping/pong` 就是 [RoomClock.Sample] 的来源：`sentAtMs=t0`、`receivedAtMs=本地收到时刻`、
 * `relayNowAtReplyMs=tr`。
 */
sealed interface RoomWire {
    data class Hello(val userId: String) : RoomWire
    data class Snapshot(val state: RoomState) : RoomWire
    data class Op(val op: RoomOp) : RoomWire
    data class Ping(val t0: Long) : RoomWire
    data class Pong(val t0: Long, val relayNow: Long) : RoomWire

    /**
     * 房间结束（v3.8.1）：房主离开时服务端广播。
     *
     * 旧实现在房主退出后什么都不发，成员就对着一个空房间继续等快照 ——
     * 界面上「已连接」但永远不动。
     */
    data class Closed(val reason: String) : RoomWire

    /** 服务端广播的在线状态（谁进来了、谁走了、当前几个人）。 */
    data class Presence(
        val role: String,
        val name: String,
        val members: Int,
        val left: Boolean,
    ) : RoomWire

    companion object {
        fun decode(line: String): RoomWire? = try {
            val o = JSONObject(line)
            when (o.optString("t")) {
                "hello" -> Hello(o.optString("userId"))
                "snapshot" -> Snapshot(decodeState(o.getJSONObject("state")))
                "op" -> decodeOp(o.getJSONObject("op"))?.let { Op(it) }
                "ping" -> Ping(o.optLong("t0"))
                "pong" -> Pong(o.optLong("t0"), o.optLong("tr"))
                "closed" -> Closed(o.optString("reason"))
                "presence" -> Presence(
                    role = o.optString("role"),
                    name = o.optString("name"),
                    members = o.optInt("members", 0),
                    left = o.optBoolean("left", false),
                )
                else -> null
            }
        } catch (t: Throwable) {
            null
        }

        // ------------------------------------------------------------ 状态

        fun encodeState(s: RoomState): JSONObject = JSONObject()
            .put("protocolVersion", s.protocolVersion)
            .put("roomId", s.roomId)
            .put("epoch", s.epoch)
            .put("seq", s.seq)
            .put("hostUserId", s.hostUserId)
            .put("members", JSONArray().apply {
                s.members.forEach { m ->
                    put(JSONObject().put("userId", m.userId).put("nickname", m.nickname)
                        .put("joinedAtMs", m.joinedAtMs).put("isHost", m.isHost))
                }
            })
            .put("queue", JSONArray().apply { s.queue.forEach { put(encodeTrack(it)) } })
            .put("currentRoomTrackId", s.currentRoomTrackId ?: JSONObject.NULL)
            .put("transport", JSONObject().put("playing", s.transport.playing)
                .put("positionMs", s.transport.positionMs).put("rate", s.transport.rate.toDouble()))
            .put("stampedAtMs", s.stampedAtMs)

        fun decodeState(o: JSONObject): RoomState = RoomState(
            protocolVersion = o.optInt("protocolVersion", RoomState.PROTOCOL_VERSION),
            roomId = o.optString("roomId"),
            epoch = o.optLong("epoch"),
            seq = o.optLong("seq"),
            hostUserId = o.optString("hostUserId"),
            members = o.optJSONArray("members")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val m = arr.getJSONObject(i)
                    RoomMember(m.optString("userId"), m.optString("nickname"),
                        m.optLong("joinedAtMs"), m.optBoolean("isHost"))
                }
            }.orEmpty(),
            queue = o.optJSONArray("queue")?.let { arr ->
                (0 until arr.length()).map { decodeTrack(arr.getJSONObject(it)) }
            }.orEmpty(),
            currentRoomTrackId = o.optString("currentRoomTrackId").takeIf { it.isNotEmpty() && it != "null" },
            transport = o.optJSONObject("transport")?.let {
                RoomTransport(it.optBoolean("playing"), it.optLong("positionMs"),
                    it.optDouble("rate", 1.0).toFloat())
            } ?: RoomTransport(false, 0L),
            stampedAtMs = o.optLong("stampedAtMs"),
        )

        fun encodeTrack(t: CanonicalTrack): JSONObject = JSONObject()
            .put("roomTrackId", t.roomTrackId)
            .put("title", t.title)
            .put("artist", t.artist)
            .put("album", t.album)
            .put("durationMs", t.durationMs)
            .put("editionKinds", JSONArray().apply { t.editionKinds.forEach { put(it) } })
            .put("publicIds", JSONObject().apply { t.publicIds.forEach { (k, v) -> put(k, v) } })

        fun decodeTrack(o: JSONObject): CanonicalTrack = CanonicalTrack(
            roomTrackId = o.optString("roomTrackId"),
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            durationMs = o.optLong("durationMs"),
            editionKinds = o.optJSONArray("editionKinds")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.toSet()
            }.orEmpty(),
            publicIds = o.optJSONObject("publicIds")?.let { ids ->
                ids.keys().asSequence().associateWith { ids.optString(it) }
            }.orEmpty(),
        )

        // ------------------------------------------------------------ 操作

        fun encodeOp(op: RoomOp): JSONObject {
            val base = JSONObject().put("seq", op.seq).put("by", op.byUserId)
            return when (op) {
                is RoomOp.SetQueue -> base.put("k", "setQueue")
                    .put("tracks", JSONArray().apply { op.tracks.forEach { put(encodeTrack(it)) } })
                    .put("startIndex", op.startIndex)
                is RoomOp.Play -> base.put("k", "play").put("positionMs", op.positionMs).put("rate", op.rate.toDouble())
                is RoomOp.Pause -> base.put("k", "pause").put("positionMs", op.positionMs)
                is RoomOp.Seek -> base.put("k", "seek").put("positionMs", op.positionMs)
                is RoomOp.Next -> base.put("k", "next").put("target", op.targetRoomTrackId ?: JSONObject.NULL)
                is RoomOp.AddTrack -> base.put("k", "add").put("track", encodeTrack(op.track))
                    .put("insertNext", op.insertNext)
                is RoomOp.RemoveTrack -> base.put("k", "remove").put("roomTrackId", op.roomTrackId)
            }
        }

        fun decodeOp(o: JSONObject): RoomOp? {
            val seq = o.optLong("seq")
            val by = o.optString("by")
            return when (o.optString("k")) {
                "setQueue" -> RoomOp.SetQueue(
                    seq, by,
                    o.optJSONArray("tracks")!!.let { arr ->
                        (0 until arr.length()).map { decodeTrack(arr.getJSONObject(it)) }
                    },
                    o.optInt("startIndex"),
                )
                "play" -> RoomOp.Play(seq, by, o.optLong("positionMs"), o.optDouble("rate", 1.0).toFloat())
                "pause" -> RoomOp.Pause(seq, by, o.optLong("positionMs"))
                "seek" -> RoomOp.Seek(seq, by, o.optLong("positionMs"))
                "next" -> RoomOp.Next(seq, by, o.optString("target").takeIf { it.isNotEmpty() && it != "null" })
                "add" -> RoomOp.AddTrack(seq, by, decodeTrack(o.getJSONObject("track")), o.optBoolean("insertNext"))
                "remove" -> RoomOp.RemoveTrack(seq, by, o.optString("roomTrackId"))
                else -> null
            }
        }
    }
}

/** 编码成一行 JSON。 */
fun RoomWire.encode(): String = when (this) {
    is RoomWire.Hello -> JSONObject().put("t", "hello").put("userId", userId).toString()
    is RoomWire.Snapshot -> JSONObject().put("t", "snapshot")
        .put("state", RoomWire.encodeState(state)).toString()
    is RoomWire.Op -> JSONObject().put("t", "op").put("op", RoomWire.encodeOp(op)).toString()
    is RoomWire.Ping -> JSONObject().put("t", "ping").put("t0", t0).toString()
    is RoomWire.Pong -> JSONObject().put("t", "pong").put("t0", t0).put("tr", relayNow).toString()
    is RoomWire.Closed -> JSONObject().put("t", "closed").put("reason", reason).toString()
    is RoomWire.Presence -> JSONObject().put("t", "presence").put("role", role)
        .put("name", name).put("members", members).put("left", left).toString()
}

