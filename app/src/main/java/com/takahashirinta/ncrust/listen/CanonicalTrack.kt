/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.listen

/**
 * 房间里的**曲目身份**（v3.8.0 · 一起听）。
 *
 * ## 为什么房间不能存 `TrackKey`
 *
 * `TrackKey`（`source + id`）是**本机视角**的身份：同一首歌，我用网易云是 `netease:123`，
 * 你用 QQ 是 `qqmusic:461168601842...`，B站又是 `bilibili:BV1xx`。房间要是存 TrackKey，
 * 就等于宣判"进房间的人必须和房主用同一个音源"—— 那正是本功能要解决的问题的反面。
 *
 * 所以房间存的是**与音源无关的描述**（[CanonicalTrack]），每个成员各自在本地解析成自己能播的
 * TrackKey（见 `RoomTrackResolver`，属于下一阶段）。[publicIds] 只是"已知的公开 id"，
 * 命中时省一次跨源搜索，**不是**身份本身。
 *
 * ## `editionKinds` 必须是快照
 *
 * 原唱 / Live / 伴奏 / Remix 混作同一首是这类功能最常见的事故。判据复用
 * `NameNormalizer.editionKinds`（与跨源匹配同一个函数），并在**建立房间时**把结果快照进房间：
 * 之后即便本机改了归一化规则，也不会让"已经在播的同一首"忽然变成两首。
 */
data class CanonicalTrack(
    /** 稳定的房间内 id（房主创建时分配，与任何音源 id 无关）。 */
    val roomTrackId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long,
    /** 版本性质快照（见 `NameNormalizer.ImpactKind` 之类的枚举，这里用字符串集合以便跨版本兼容）。 */
    val editionKinds: Set<String> = emptySet(),
    /** 已知来源 id：`netease` / `qqmusic` / `bilibili` → 该源的 id 字符串。 */
    val publicIds: Map<String, String> = emptyMap(),
) {
    /**
     * 同名同艺人同版本 ⇒ 视为同一首（用于房间内去重，不做跨源解析）。
     *
     * 刻意**不**比较 `durationMs`：同一首歌在不同音源上的时长可能差 1–2 秒（前奏裁剪 / 淡出），
     * 用时长做同一性判据会把它们判成两首。时长只用于 [samePerformance] 的容差比较。
     */
    fun sameSong(other: CanonicalTrack): Boolean =
        title.trim().equals(other.title.trim(), ignoreCase = true) &&
            artist.trim().equals(other.artist.trim(), ignoreCase = true) &&
            editionKinds == other.editionKinds

    /** 同一次「演出」：同一首歌且时长在 [toleranceMs] 内（用于"这是不是同一段录音"）。 */
    fun samePerformance(other: CanonicalTrack, toleranceMs: Long = 2_000L): Boolean =
        sameSong(other) && kotlin.math.abs(durationMs - other.durationMs) <= toleranceMs
}
