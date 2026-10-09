/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · A：多音源 Provider 抽象。
 */

package com.takahashirinta.ncrust.source

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.ResolveFailureKind
import com.takahashirinta.ncrust.player.ResolveOutcome
import com.takahashirinta.ncrust.player.SongUrlResult

/**
 * 一个音源要对外提供的能力（v2.1.0 · A）。
 *
 * ## 为什么是这五个
 *
 * 只抽「两个音源**都有、且语义一致**」的能力。ncm 与本应用耦合很深（云歌单、收藏、
 * 播放上报、艺人/专辑详情……），那些能力 qm 要么没有、要么语义不同，硬抽成接口
 * 只会得到一个「每个方法都要 `TODO()`」的空壳。所以本接口只覆盖**让一首歌能放出来**
 * 这条链路上必须分叉的部分：
 *
 * | 能力 | 不分叉会怎样 |
 * |---|---|
 * | [isLoggedIn] | UI 无法区分「两个账号各自登录了没」 |
 * | [searchSongs] | 搜索只能搜到一个平台 |
 * | [resolveUrl] | **核心**：拿错音源取链 = 404 / 放到别人的歌 |
 * | [songDetail] | 队列里只有 id 没有元数据时无法补齐 |
 *
 * ## 契约（实现方必须遵守）
 *
 * 1. **绝不抛异常给出调用方**：网络失败一律返回 null / 空列表。取链失败是常态
 *    （无版权、无 VIP、下架），调用方靠 null 决定「跳歌」还是「提示」。
 * 2. **导出的 [SongItem] 必须带上 [MusicSource] 与 qm 的 sourceId**：
 *    下游（队列持久化、离线缓存、歌词请求）全靠这两个字段路由，漏了就串台。
 * 3. **不要把平台内部标识（如 QQ 的 songmid）当 [SongItem.id]**：id 是数字型的对外身份，
 *    QQ 的 mid 放 [SongItem.sourceId]。
 */
interface MusicSourceProvider {

    /** 本实现对应哪个音源。注册表以它为 key，同一个音源只允许一个实现。 */
    val source: MusicSource

    /**
     * 该音源当前是否已登录。**只读本地状态，不发网络请求** ——
     * 它会被 Compose 在组合期读取（音源标识、登录入口的显隐），不能有 IO。
     */
    val isLoggedIn: Boolean

    /**
     * 搜索歌曲。失败返回空列表；实现方负责把 [MusicSource] 标进结果。
     *
     * @param page **1 起**的页码（v3.4.11）。只有第一页时它恒为 1 ——
     *   加这个参数是为了让「搜索只有 30 条、没有下一页」这件事有个出口
     *   （用户报障：「每次拉歌曲只拉三十首也太少了吧」）。
     */
    /**
     * @param totalOut v3.4.11：**服务端声明的总数**的出参（可为空表 = 这个音源不给）。
     *
     *   为什么用出参而不是改返回类型：调用点有 5 处（三个 Provider + 聚合搜索 +
     *   若干单测的 Fake），改返回类型会让**每一个**都必须动；而出参对
     *   「不关心的调用方」是透明的（默认不传），并且它天然表达「不知道」
     *   （表里没有这个 key）与「总数是 0」的区别。
     *
     *   实测两个音源都给：ncm 的 `result.songCount`（周杰伦 = **273**）、
     *   QQ 的 `data.song.totalnum`（同为周杰伦 = **999**）。
     */
    suspend fun searchSongs(
        keyword: String,
        limit: Int,
        page: Int = 1,
        totalOut: MutableMap<String, Int>? = null,
    ): List<SongItem>

    /**
     * 该音源**还有没有下一页**（v3.4.11）。
     *
     * 判据只能是「这一页有没有被填满」：两个音源的搜索响应都**没有**稳定的
     * 「总条数」字段可按（ncm 的 `result.songCount` 只在部分返回里出现，
     * qm 旧版搜索压根不给）。所以实现是「取回 [limit] 条 ⇒ 可能还有下一页」——
     * 它会在最后一页**多给用户一次点击**，而不会漏掉任何一页（后者才是缺陷）。
     *
     * 默认实现直接说「没有了」：这样**不实现分页的音源不会显示一个点了没反应的按钮**
     * （v3.4.11 只给 ncm/qm/B站 三条路做了分页，默认值让将来的音源必须显式声明）。
     */
    suspend fun hasMorePages(keyword: String, limit: Int, page: Int): Boolean = false

    /**
     * 取该曲在 [level] 档位下的可播放 URL 与**实际**文件参数。
     *
     * [level] 用本应用统一的档位名（`standard`/`higher`/`exhigh`/`lossless`/`hires`/…，
     * 见 `QualityLadder.LEVELS`），由实现方负责映射到自己平台的音质档位并做降级协商。
     * 同一个档位名在两个平台对应的**不是**同一个文件（码率、容器都可能不同），
     * 但「用户选『无损』就应该尽量给无损」这条语义必须一致。
     *
     * 取不到任何可用档位时返回 null（调用方据此跳歌），**不要**返回一个指向
     * HTML 错误页 / 空流的 URL。
     */
    suspend fun resolveUrl(song: SongItem, level: String): SongUrlResult?

    /**
     * v3.2.0 · P0：[resolveUrl] 的**带分类**版本。播放链应当只调这一个。
     *
     * ## 为什么不能继续只返回 null（铁律 20/21 的落点）
     *
     * 「拿不到 URL」至少有七种原因，处置两两不同：网络抖动该重试、会员不足该提示、
     * 结构性缺失才允许跳歌。折叠成一个 `null` 之后播放链只能一律按「这首放不了」处理 ——
     * 用户看到的就是「VIP 歌曲被说成没有版权，然后被自动跳过」。
     *
     * ## 默认实现是**保守**的那一侧
     *
     * 没覆写这一条的 Provider 会得到 [ResolveFailureKind.UNKNOWN]，
     * 而 `UNKNOWN` 的处置是「停下 + 提示、**不跳歌**」。也就是说：
     * **忘了覆写的代价是「少跳一次歌」，不是「误报版权 + 跳歌」** ——
     * 保守方向选对了，漏改就不会造成本版要修的那种伤害。
     */
    suspend fun resolveUrlOutcome(song: SongItem, level: String): ResolveOutcome {
        val result = resolveUrl(song, level)
        return if (result != null) {
            ResolveOutcome.ok(result)
        } else {
            ResolveOutcome.failed(ResolveFailureKind.UNKNOWN, source)
        }
    }

    /**
     * 补齐元数据（队列里可能只有 id）。失败返回 null，调用方保留原对象即可。
     * 实现方应当原样带回 [song] 的音源字段。
     */
    suspend fun songDetail(song: SongItem): SongItem?
}
