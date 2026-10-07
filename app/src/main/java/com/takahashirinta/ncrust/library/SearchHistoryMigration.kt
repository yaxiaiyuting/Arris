/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.4 · B：搜索历史的音源身份、去重键与「信息不完整」判据。**纯逻辑，JVM 可单测。**
 */

package com.takahashirinta.ncrust.library

import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.trackKeyOf

/**
 * `HistoryItem` 的音源语义（v2.5.4 · B）。
 *
 * ## 为什么是「读时推断」而不是「启动时刷写」
 *
 * 老条目只有裸 id。唯一**可证明**的推断是 bit62（[SourceIds.sourceOfId]）：
 *
 * - 它是**结构性**的，不是启发式 —— 全部 QQ 曲目的 id 都由 [SourceIds.qqId] 产出、
 *   `1L shl 62` 恒置位，而 ncm id 是十进制的百万~十亿量级（`< 2^40`），
 *   两者在 64 位整数上不可能相交（`MusicSource.kt:147-163` 的完整论证）；
 * - **id 区间启发式必须放弃**：仓库里那句「1e6~3e9」只是量级声明，而 QQ 的
 *   **裸 songid 同样是 9~10 位十进制**，两个区间是重叠的 —— 拿它判音源会误判。
 *
 * 推断结果**只用于内存判定，不写回磁盘**（有意为之）：
 *
 * | 选择 | 后果 |
 * |---|---|
 * | 读时推断、保持 `source == null` 落盘 | 「这条记录不完整」这个事实**一直在**，[isIncomplete] 每次都能给出正确结论；条目按 14 天 TTL 自然换代 |
 * | 启动时把推断结果刷回去 | 「不完整」被伪装成「正常条目」，UI 从此分不清「新写入的 QQ 记录」与「老记录」 |
 *
 * 搜索历史每段 ≤10 条、TTL 14 天（[SearchHistoryManager.MAX_ITEMS] / `TTL_MS`），
 * 所以「等它自然过期」是一个**有界**的迁移策略，不需要破坏性写入。
 *
 * ## 不做什么（明确记录）
 *
 * **不用标题/艺人模糊匹配去补 songmid。** QQ 侧没有「按 songid 取 mid」的端点，
 * 只能搜索 + 模糊匹配；`QqApi` 对同类兜底已有先例判决（`QqApi.kt:126-131`：
 * 「media_mid 失败就用 mid 再试一次」拿到的 purl 是坏的，会把一次干净的失败
 * 换成一次诡异的播放错误）。猜测性写操作在这里同样只有坏处。
 */
object SearchHistoryMigration {

    /**
     * 这条记录的**有效音源**。
     *
     * - 显式 `source`（v2.5.4 起写入）以它为准 —— 与 [com.takahashirinta.ncrust.source.TrackKey.of]
     *   的「声明优先于结构」一致；
     * - `source == null` ⇒ **老条目**（字段缺失，不是「没有音源」）⇒ 只允许 bit62 这一条推断。
     *
     * 判「老条目」只看 `null`、**不看空串**：空串是「读不懂的未知值」，
     * 那正是 [MusicSource.fromKey] 回落 [MusicSource.DEFAULT] 的语义（`AGENTS.md` 规则 2）。
     */
    fun effectiveSource(item: SearchHistoryManager.HistoryItem): MusicSource =
        if (item.source == null) SourceIds.sourceOfId(item.id) else MusicSource.fromKey(item.source)

    /**
     * 去重键 = **有效音源 + id**。
     *
     * 老条目与新条目对同一首歌必须给同一个串，否则会出现「LazyColumn 里同一首歌两行」
     * （`key` 撞了还会直接抛异常）。三处必须用**同一个**函数：
     * 判重（[SearchHistoryManager.add]）、删除（[SearchHistoryManager.remove]）、
     * LazyColumn 的 `key`（`SearchScreen`）。
     */
    fun dedupeKey(item: SearchHistoryManager.HistoryItem): String =
        SourceIds.trackKey(effectiveSource(item), item.id)

    /**
     * 这条记录是否**信息不完整**（音源是 QQ 但缺 songmid ⇒ 取链必然失败）。
     *
     * 与 [com.takahashirinta.ncrust.source.isResolvable]（`SongSourceExt.kt:31-32`）
     * 必须是**同一条规则的两种写法**：服务端取链的判据不能有两份，
     * 否则会出现「UI 说能播、播放器说不能播」。
     */
    fun isIncomplete(item: SearchHistoryManager.HistoryItem): Boolean =
        effectiveSource(item).requiresSourceId && item.sourceId.isNullOrEmpty()

    /**
     * 历史条目 → 可播放对象（原先内联在 `SearchScreen.kt:749-755`，为了让 §12 的路由用例
     * 够得着而搬到这里；**没有 **`@Composable`，纯函数）。
     *
     * 音源侧的两个约定：
     * 1. ncm 一侧 `source` 刻意写 `null`（与 `songRefOf` 同一条约定，
     *    `SongSourceExt.kt:96-98`）—— 这样它与 v2.1.0 之前持久化的条目在 data class
     *    意义上仍然相等，队列判重 / 收藏命中 / 离线命中都不受影响；
     * 2. `sourceId` / `mediaId` **绝不猜**：老 QQ 条目缺 songmid 就是 `null`，
     *    于是 [isResolvable] 为 false，上层按「不可播放」处理而不是拿错 mid 去要一个坏链。
     */
    fun toSongItem(item: SearchHistoryManager.HistoryItem): SongItem {
        val source = effectiveSource(item)
        return SongItem(
            id = item.id,
            name = item.title,
            artists = item.subtitle?.let { listOf(ArtistItem(name = it)) },
            album = AlbumItem(id = null, name = null, picUrl = item.coverUrl),
            duration = null,
            source = if (source == MusicSource.NETEASE) null else source.key,
            sourceId = item.sourceId,
            mediaId = item.mediaId,
        )
    }

    /**
     * 给单测用的「同一性」断言：重建出来的对象与写入前的对象必须在
     * **队列身份**（[trackKeyOf]）上相等 —— 这正是搜索历史要修的那件事。
     */
    internal fun identityOf(item: SearchHistoryManager.HistoryItem) = toSongItem(item).trackKeyOf()
}
