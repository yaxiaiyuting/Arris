/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.listen

/**
 * 房主侧的**权威状态机**：所有操作在这里串行应用，产出新状态。
 *
 * 纯函数 + 纯数据，没有协程、没有 IO、没有 Android 依赖 —— 这样「谁在什么位置」这件事
 * 可以用 JVM 单测逐条钉住，而不是靠在两台设备上手动点。
 *
 * ## 三条不变量
 *
 * 1. **只有房主能改**：非房主提交的操作一律 `Rejected.NotHost`（成员的操作先发给房主，由房主转成 op）。
 * 2. **序号必须严格递增**：`op.seq <= state.seq` ⇒ `Rejected.Stale`。这条挡住"重连后把旧操作又发一遍"，
 *    也挡住网络乱序。
 * 3. **`transport.positionMs` 永远配一个 `stampedAtMs`**：位置是"某时刻的位置"，不是"现在的位置"，
 *    否则延迟一来就系统性偏。
 */
object RoomEngine {

    sealed interface ApplyResult {
        data class Applied(val state: RoomState) : ApplyResult
        data class Rejected(val reason: Reason) : ApplyResult
    }

    enum class Reason {
        NotHost,
        Stale,
        UnknownTrack,
        EmptyQueue,
        ProtocolMismatch,
    }

    /**
     * 应用一条操作。
     *
     * @param nowMs 房主本地/中继时钟的当前时刻，写进新状态的 `stampedAtMs`
     */
    fun apply(state: RoomState, op: RoomOp, nowMs: Long): ApplyResult {
        if (op.seq <= state.seq) return ApplyResult.Rejected(Reason.Stale)
        if (!state.isHost(op.byUserId)) return ApplyResult.Rejected(Reason.NotHost)

        val nextSeq = op.seq
        return when (op) {
            is RoomOp.SetQueue -> {
                if (op.tracks.isEmpty()) {
                    ApplyResult.Rejected(Reason.EmptyQueue)
                } else {
                    val start = op.startIndex.coerceIn(0, op.tracks.size - 1)
                    ApplyResult.Applied(
                        state.copy(
                            seq = nextSeq,
                            queue = op.tracks,
                            currentRoomTrackId = op.tracks[start].roomTrackId,
                            transport = RoomTransport(playing = false, positionMs = 0L),
                            stampedAtMs = nowMs,
                        )
                    )
                }
            }

            is RoomOp.Play -> {
                if (state.currentRoomTrackId == null) {
                    ApplyResult.Rejected(Reason.EmptyQueue)
                } else {
                    ApplyResult.Applied(
                        state.copy(
                            seq = nextSeq,
                            transport = RoomTransport(
                                playing = true,
                                positionMs = op.positionMs.coerceAtLeast(0L),
                                rate = op.rate.coerceIn(0.5f, 2f),
                            ),
                            stampedAtMs = nowMs,
                        )
                    )
                }
            }

            is RoomOp.Pause -> ApplyResult.Applied(
                state.copy(
                    seq = nextSeq,
                    transport = state.transport.copy(
                        playing = false,
                        positionMs = op.positionMs.coerceAtLeast(0L),
                    ),
                    stampedAtMs = nowMs,
                )
            )

            is RoomOp.Seek -> ApplyResult.Applied(
                state.copy(
                    seq = nextSeq,
                    transport = state.transport.copy(positionMs = op.positionMs.coerceAtLeast(0L)),
                    stampedAtMs = nowMs,
                )
            )

            is RoomOp.Next -> {
                val target = op.targetRoomTrackId
                    ?: state.queue.getOrNull(state.currentIndex + 1)?.roomTrackId
                if (target == null || state.queue.none { it.roomTrackId == target }) {
                    ApplyResult.Rejected(Reason.UnknownTrack)
                } else {
                    ApplyResult.Applied(
                        state.copy(
                            seq = nextSeq,
                            currentRoomTrackId = target,
                            transport = RoomTransport(playing = state.transport.playing, positionMs = 0L),
                            stampedAtMs = nowMs,
                        )
                    )
                }
            }

            is RoomOp.AddTrack -> {
                val at = if (op.insertNext) {
                    (state.currentIndex + 1).coerceIn(0, state.queue.size)
                } else {
                    state.queue.size
                }
                val queue = state.queue.toMutableList().apply { add(at, op.track) }
                ApplyResult.Applied(
                    state.copy(
                        seq = nextSeq,
                        queue = queue,
                        // 空房间加第一首 ⇒ 直接成为当前曲（否则界面会停在"没有正在播放"）。
                        currentRoomTrackId = state.currentRoomTrackId ?: op.track.roomTrackId,
                        stampedAtMs = nowMs,
                    )
                )
            }

            is RoomOp.RemoveTrack -> {
                val idx = state.queue.indexOfFirst { it.roomTrackId == op.roomTrackId }
                if (idx < 0) {
                    ApplyResult.Rejected(Reason.UnknownTrack)
                } else {
                    val queue = state.queue.filterNot { it.roomTrackId == op.roomTrackId }
                    val current = when {
                        queue.isEmpty() -> null
                        state.currentRoomTrackId == op.roomTrackId ->
                            queue.getOrNull(idx.coerceAtMost(queue.size - 1))?.roomTrackId
                        else -> state.currentRoomTrackId
                    }
                    ApplyResult.Applied(
                        state.copy(
                            seq = nextSeq,
                            queue = queue,
                            currentRoomTrackId = current,
                            transport = if (state.currentRoomTrackId == op.roomTrackId) {
                                RoomTransport(playing = state.transport.playing, positionMs = 0L)
                            } else {
                                state.transport
                            },
                            stampedAtMs = nowMs,
                        )
                    )
                }
            }
        }
    }

    /**
     * 成员侧收到全量状态：只接受**更新的**（`seq` 更大或 `epoch` 变化）。
     *
     * 为什么必须判这个：中继是无状态的，重连后会补发一次快照；没有闸门就会出现
     * "刚跳到的位置被一张 3 秒前的旧快照拽回去"。
     */
    fun accept(local: RoomState?, incoming: RoomState): Acceptance = when {
        local == null -> Acceptance.Accept(epochChanged = false)
        incoming.protocolVersion != RoomState.PROTOCOL_VERSION ->
            Acceptance.Reject(Reason.ProtocolMismatch)
        incoming.epoch != local.epoch -> Acceptance.Accept(epochChanged = true)
        incoming.seq > local.seq -> Acceptance.Accept(epochChanged = false)
        else -> Acceptance.Reject(Reason.Stale)
    }

    sealed interface Acceptance {
        data class Accept(val epochChanged: Boolean) : Acceptance
        data class Reject(val reason: Reason) : Acceptance
    }
}
