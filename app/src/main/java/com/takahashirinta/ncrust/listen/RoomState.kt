/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.listen

/** 房间里的一个成员。token 只用于重连认领，**不是**任何平台的登录凭证。 */
data class RoomMember(
    val userId: String,
    val nickname: String,
    val joinedAtMs: Long,
    val isHost: Boolean = false,
)

/** 播放传输状态：只有「是否在放 / 从哪一刻起算 / 什么倍率」三件事。 */
data class RoomTransport(
    val playing: Boolean,
    /** 该状态成立时，**在 [stampedAtMs] 这个时刻**的播放位置。 */
    val positionMs: Long,
    val rate: Float = 1f,
)

/** 一次房间操作（append-only）。`seq` 由房主单调递增分配。 */
sealed interface RoomOp {
    val seq: Long
    val byUserId: String

    data class SetQueue(
        override val seq: Long,
        override val byUserId: String,
        val tracks: List<CanonicalTrack>,
        val startIndex: Int = 0,
    ) : RoomOp

    data class Play(
        override val seq: Long,
        override val byUserId: String,
        val positionMs: Long = 0L,
        val rate: Float = 1f,
    ) : RoomOp

    data class Pause(
        override val seq: Long,
        override val byUserId: String,
        val positionMs: Long,
    ) : RoomOp

    data class Seek(
        override val seq: Long,
        override val byUserId: String,
        val positionMs: Long,
    ) : RoomOp

    data class Next(
        override val seq: Long,
        override val byUserId: String,
        /** 目标房间曲目 id；null = 房主同意"按队列顺序下一首"。 */
        val targetRoomTrackId: String? = null,
    ) : RoomOp

    data class AddTrack(
        override val seq: Long,
        override val byUserId: String,
        val track: CanonicalTrack,
        /** true = 插到当前曲之后（下一首播放）。 */
        val insertNext: Boolean = false,
    ) : RoomOp

    data class RemoveTrack(
        override val seq: Long,
        override val byUserId: String,
        val roomTrackId: String,
    ) : RoomOp
}

/**
 * 房间的**完整**状态（不是补丁）。
 *
 * 为什么用全量而不是增量：增量需要"我漏了几条消息就再也对不上"，而房间状态很小
 * （一个队列 + 一个传输三元组），全量重发是最省心的自愈手段。`seq` 只用来判断
 * 「这条是不是比我手上的新」，不用来重放。
 */
data class RoomState(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val roomId: String,
    /** 房间纪元：换房主 / 重置房间时 +1，成员收到变化必须无条件丢弃旧状态并 seek。 */
    val epoch: Long = 0L,
    /** 已应用的最后一个操作序号。 */
    val seq: Long = 0L,
    val hostUserId: String,
    val members: List<RoomMember> = emptyList(),
    val queue: List<CanonicalTrack> = emptyList(),
    val currentRoomTrackId: String? = null,
    val transport: RoomTransport = RoomTransport(playing = false, positionMs = 0L),
    /** 上面 `transport.positionMs` 对应的**服务器时刻**（毫秒，relay 时钟）。 */
    val stampedAtMs: Long,
) {
    val currentTrack: CanonicalTrack?
        get() = currentRoomTrackId?.let { id -> queue.firstOrNull { it.roomTrackId == id } }

    val currentIndex: Int
        get() = currentRoomTrackId?.let { id -> queue.indexOfFirst { it.roomTrackId == id } } ?: -1

    fun memberOf(userId: String): RoomMember? = members.firstOrNull { it.userId == userId }

    fun isHost(userId: String): Boolean = userId == hostUserId

    companion object {
        const val PROTOCOL_VERSION = 1
    }
}
