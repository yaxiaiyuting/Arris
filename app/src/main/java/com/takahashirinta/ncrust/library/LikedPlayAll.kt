/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 「库 → 单曲 → 播放全部」的**纯逻辑**：把本地可见条目（含手动收藏的 QQ / B 站）与
 * 云端红心 id 顺序合成一条完整可播队列。不碰 Android / 网络，JVM 可单测。
 */

package com.takahashirinta.ncrust.library

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey

/**
 * 一次「整列播放」的选择结果。
 *
 * @property songs 有序、去重、tombstone 已剔除的完整曲目列表。
 * @property missingCloudIds 云端红心 id 里**取不到详情**的那些（下架 / 无版权 / 本批失败）。
 *   非空时 UI 要给「部分未取到」的反馈，而不是假装 N 首全在。
 * @property cloudCount 云端红心 id 去重后的总数（反馈里的分母）。
 */
data class LikedPlayAllSelection(
    val songs: List<SongItem>,
    val missingCloudIds: List<Long>,
    val cloudCount: Int,
)

/**
 * 合成「播放全部」列表。顺序口径（与库页「只加不减」一致）：
 *
 * 1. **本地手动来源的非 ncm 条目**（QQ / B 站）按表内顺序排在最前 —— 它们永远不会出现在
 *    ncm 的 likedIds 里，任何「只按云端 id 循环」的实现都会把它们整类丢掉；
 * 2. **ncm 红心 id 按云端顺序**补全（缓存命中优先，缺的由调用方先补齐后传进来）；
 *    tombstone 的 id **跳过**，绝不因为「云端还有」而复活；
 * 3. 云端没有、但本地手动收藏的 ncm 条目按表内顺序追在最后（同样不丢）。
 *
 * 去重键是 [TrackKey]（source + id），因此 ncm 123 与 QQ 123 会各留一份。
 *
 * @param localVisible 当前收藏库的可见条目（tombstone 已由调用方过滤，或在这里再滤一次）。
 * @param cloudIds 云端红心歌单的全部 id（有序；可含重复，内部会 distinct）。
 * @param details id → 详情。调用方应把缓存命中与本次网络补齐的都放进来。
 * @param tombstoned 用户手动删过的 ncm id。即使云端仍返回也必须跳过。
 */
fun selectLikedPlayAll(
    localVisible: List<SavedSongEntry>,
    cloudIds: List<Long>,
    details: Map<Long, SongItem>,
    tombstoned: Set<Long>,
): LikedPlayAllSelection {
    val visible = localVisible.filter { it.isVisible }
    val out = LinkedHashMap<TrackKey, SongItem>()

    // ① 本地手动来源的非 ncm 条目：QQ / B 站 / 以后的新音源。
    for (entry in visible) {
        if (entry.trackKey.source != MusicSource.NETEASE) out[entry.trackKey] = entry.song
    }

    // ② 云端红心顺序。tombstone 与取不到详情的 id 都跳过（后者进 missing）。
    val distinctCloud = cloudIds.distinct()
    val cloudSet = distinctCloud.toHashSet()
    val missing = ArrayList<Long>()
    for (id in distinctCloud) {
        if (id in tombstoned) continue
        val key = TrackKey(MusicSource.NETEASE, id)
        val song = details[id] ?: visible.firstOrNull { it.trackKey == key }?.song
        if (song == null) {
            missing.add(id)
            continue
        }
        out[key] = song
    }

    // ③ 本地手动收藏、但不在云端红心表里的 ncm 条目：只加不减。
    for (entry in visible) {
        if (entry.trackKey.source != MusicSource.NETEASE) continue
        if (entry.trackKey.id in cloudSet) continue
        out.getOrPut(entry.trackKey) { entry.song }
    }

    return LikedPlayAllSelection(
        songs = out.values.toList(),
        missingCloudIds = missing,
        cloudCount = distinctCloud.size,
    )
}
