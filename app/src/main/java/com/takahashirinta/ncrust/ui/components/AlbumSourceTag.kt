/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.0：专辑列表的**音源角标 + 音源筛选**。纯逻辑，JVM 可单测。
 */

package com.takahashirinta.ncrust.ui.components

import com.takahashirinta.ncrust.library.AlbumInfo
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.ui.i18n.Strings

/**
 * 「这张**专辑**来自哪个音源」的**唯一**判定落点（v3.4.0）。
 *
 * ## 用户的原始诉求
 *
 * 「专辑界面建议加个来自 ncm 或者 qq」—— 收藏页的专辑格子与专辑详情页此前
 * 完全不显示音源：用户在收藏页看到一张专辑，无法知道它是从哪个源收藏的
 * （搜索结果的**单曲**行早在 v2.1.0 · E 就有音源角标，专辑一侧是空白）。
 *
 * ## 关键事实：收藏专辑**没有**落盘的音源字段，但能反推
 *
 * `ncrust_library` / `saved_albums` 里的一条 `AlbumInfo` 只有
 * `albumId: Long` / `mid: String?` / 展示字段三样（见 `LibraryManager.AlbumInfo`），
 * **没有** `source` 字段。而 v1.9.3 起的纪律是「加字段 = 加迁移逻辑 = 加单测」，
 * 所以本版**不加字段**，从既有数据反推。两条判据，强度不同：
 *
 * 1. **`mid`（albumMID，字符串身份）—— 这才是主判据**。
 *    它的语义是「QQ 域内的字符串身份」，**ncm 侧恒为 `null`**
 *    （见 `AlbumInfo.mid` 与 `SavedAlbumCodec.AlbumDto.albumMid` 的 KDoc）。
 *    全仓库写 `AlbumItem.mid` 的地方只有 `qq/QqCatalog.kt`（`albumMID`），
 *    ncm 的映射层一个都不写。所以 `mid != null` ⇒ qm，
 *    这是**结构性结论**，不是启发式。
 * 2. **`albumId` 的标志位**（[SourceIds.QQ_ID_FLAG] / [SourceIds.BILI_ID_FLAG]）。
 *    它同样是无损的结构性判据，但它对**收藏专辑基本无效** ——
 *    见下面那条「反向证据」。
 *
 * ### 反向证据：**只**用 `SourceIds.sourceOfId(albumId)` 会把 QQ 专辑说成 ncm
 *
 * `QQ_ID_FLAG` 是 `SourceIds.qqId()` **造曲目 id** 时置的位；收藏专辑这一侧存的是
 * 服务端给的**裸数字 albumID**（`SavedAlbumCodec` 只要求 `albumId > 0`）。
 * 实测里两源的数字专辑 id 完全在同一个量级（陈奕迅《What's Going On...?》：
 * QQ `22276` vs ncm `6451`），裸 id 上**没有任何位可以区分它们**。
 * 于是 `sourceOfId(22276)` 会答「ncm」—— 那正是用户说的「给错信息」。
 * `AlbumSourceTagTest` 把这条反向证据钉成了一个用例（判据不许退回只用 id 反推）。
 *
 * ## 仍然判不出来时：**不显示角标**，绝不回落成 ncm
 *
 * [sourceOf] 返回 `null` 的语义是「判定不了」，UI 侧唯一正确的处置是
 * **什么都不画**（[badgeLabel] 返回 `null`）—— 与 `SongTags` 对
 * `TrackAvailability.UNKNOWN` / `TrackVersionTag.UNKNOWN` 的处置同一条纪律。
 *
 * 今天真正会落到 `null` 的形状只有「`albumId <= 0`」（`SavedAlbumCodec` 逐条丢弃这种
 * 条目，所以它是防御分支）。**已知残余风险**：若将来有别的写入方把 QQ 专辑写进这张表
 * 而**没有**带上 `albumMid`，本判定会把它说成 ncm —— 与「老数据没有字段」是同一个
 * 不可判定形状。缓解手段是写入侧必须保住 `mid`（`AlbumInfo` 的契约已经写明
 * `mid == null` ⇒ 身份不可信），本判定不为此去猜任何一个字符串身份。
 *
 * ## 无标志位 + 无 mid 为什么判 ncm（这**不是**默认值）
 *
 * 因为这张表的**唯一写入方**是 ncm 的收藏专辑端点：
 * `LibraryManager.refreshFromCloud` → `PlaylistApi.getSubscribedAlbums()`（`album_sublist`），
 * 而写入侧的入口 `AlbumDetailScreen` 的「收藏专辑」按钮**只在 `source == NETEASE`
 * 时挂载**（QQ 没有订阅写接口，那里明令「绝不拿一张 QQ 专辑去写一条 ncm 的订阅」）。
 * 也就是说：这个形状的条目在结构上只能来自 ncm。这一点由
 * `SavedAlbumCodec` 的 KDoc 独立佐证（「ncm 的十进制 albumId 就是身份」）。
 */
object AlbumSourceTag {

    /**
     * 专辑列表上可选的筛选档：**全部 / 只看 ncm / 只看 qm**。
     *
     * 刻意**不含 `SourceFilter.BILIBILI`**：B 站音频区没有专辑实体
     * （`AlbumNavigator.albumIdentityOf(BILIBILI, …)` 恒为 `null`），给它留一个
     * 永远空的档位就是制造一次无意义的点击 —— 与 `SourceFilter.visibleIn` 对
     * 「B 站音源关闭时不出档」是同一条理由。
     */
    val albumFilters: List<SourceFilter> =
        listOf(SourceFilter.ALL, SourceFilter.NETEASE, SourceFilter.QQMUSIC)

    /**
     * 从落盘字段反推音源；判不出来返回 `null`（**调用方不许回落成任何一源**）。
     *
     * @param albumId `AlbumInfo.albumId`（该表里是服务端给的裸数字 albumID）。
     * @param mid `AlbumInfo.mid`（QQ 的 albumMID；ncm 侧恒为 `null`）。
     */
    fun sourceOf(albumId: Long, mid: String?): MusicSource? = when {
        // 非法 / 缺失 id：没有任何依据，如实返回「判定不了」。
        // （codec 已经把这种条目整条丢弃，这里是防御分支，不是常态。）
        albumId <= 0L -> null
        // 标志位优先：它比 mid 更结构化（两个位在本应用里互斥，
        // 判序「先 QQ 再 B 站」与 SourceIds.sourceOfId 的历史行为一致）。
        SourceIds.isQqId(albumId) -> MusicSource.QQMUSIC
        SourceIds.isBiliId(albumId) -> MusicSource.BILIBILI
        // 主判据：字符串身份只可能来自 QQ（ncm 恒为 null）。
        // **判「有没有」只看 null / 空白**：空串的语义是「服务端确实没有」
        // （AGENTS.md v1.9.3 规则 2），把它当身份会造出一个假的 QQ 角标。
        !mid.isNullOrBlank() -> MusicSource.QQMUSIC
        // 剩下的形状是「裸数字 id + 无字符串身份」= 这张表唯一写入方（ncm 收藏端点）的形状。
        // 委托 sourceOfId 而不是写死 NETEASE：判据只有一份。
        else -> SourceIds.sourceOfId(albumId)
    }

    /** [sourceOf] 的 `AlbumInfo` 重载（调用点不必自己拆字段）。 */
    fun sourceOf(album: AlbumInfo): MusicSource? = sourceOf(album.albumId, album.mid)

    /**
     * 角标文案；**判定不了时返回 `null`** ⇒ 调用方不画角标（诚实降级）。
     *
     * 文案出口是 [SongTags.sourceLabel] —— 与搜索结果页的单曲行**同一个函数、
     * 同一套 8 语言文案**（「ncm」/「qm」/「B站」），本版不新造第二套。
     */
    fun sourceLabel(source: MusicSource?, strings: Strings): String? =
        source?.let { SongTags.sourceLabel(it, strings) }

    /** 一张专辑的角标文案；`null` ⇒ 不画（不要拿「未知」当文案，那就是噪音）。 */
    fun badgeLabel(albumId: Long, mid: String?, strings: Strings): String? =
        sourceLabel(sourceOf(albumId, mid), strings)

    /** [badgeLabel] 的 `AlbumInfo` 重载。 */
    fun badgeLabel(album: AlbumInfo, strings: Strings): String? =
        sourceLabel(sourceOf(album), strings)

    /**
     * 按档位过滤专辑。**保序**（筛选不重排）。
     *
     * 判定不了音源的条目（`sourceOf == null`）**只在 [SourceFilter.ALL] 档出现**：
     * 它不属于任何一源，把它塞进「只看 ncm」就是替用户下结论
     * （`SourceFilter.filter` 收不了可空音源，所以这里是它旁边的一个**新**函数，
     * 而不是去改那个已被搜索页使用的重载 —— 改它等于改搜索页的语义）。
     */
    fun <T> filterAlbums(items: List<T>, which: SourceFilter, sourceOf: (T) -> MusicSource?): List<T> =
        if (which == SourceFilter.ALL) items
        else items.filter { sourceOf(it)?.let(which::accepts) == true }

    /**
     * 筛选档该不该挂载。**纯函数**，判据只有一条：**筛选前**的专辑总数 > 0。
     *
     * 与 `SourceFilter.shouldShowFilterRow` 是同一条教训（v3.2.0 · P0-D）：
     * 用「筛选后的结果」决定筛选档自己的可见性，等于让它在最该出现的那一刻消失
     * （用户点「只看 qm」→ 结果为空 → 连筛选档一起被换成空态 → **没有任何路径点回「全部」**）。
     *
     * 刻意**不看**当前选中的档位，也刻意**不做**「当前有哪些源就显示哪些档」的收敛：
     * 那两种做法都会在「筛选后为空」时把档位本身改掉，同一条 P0。
     *
     * @param totalAlbums **未筛选**的收藏专辑总数。
     */
    fun shouldShowFilterRow(totalAlbums: Int): Boolean = totalAlbums > 0

    /**
     * 筛选档文案。**唯一的文案出口**（UI 不自己写 `when`，见 `SourceFilter.label`）。
     *
     * ⚠️ **待插入的 i18n**（见 v3.4.0 交付报告）：`ALL` 档语义是「全部」，
     * 这里先借用搜索页的 `aggFilterBoth`（「双源」/ "Both"）当占位 ——
     * 理由是它同样是「两源都显示、不过滤」那一档，且 8 语言都有译文
     * （裸中文字面量会让 `StringsMigrationTest` 变红）。新键落地后这一行换成
     * `strings.source.albumSourceFilterAll` 即可，其余代码不用动。
     */
    fun filterLabel(filter: SourceFilter, strings: Strings): String = when (filter) {
        SourceFilter.ALL -> strings.source.albumSourceFilterAll
        SourceFilter.NETEASE, SourceFilter.QQMUSIC, SourceFilter.BILIBILI -> filter.label(strings)
    }

    /**
     * 「当前筛选下没有专辑」的诚实文案 —— **必须指名当前档位**，
     * 与「一张专辑都没收藏」（`strings.noSavedAlbums`）是两句不同的话。
     *
     * ⚠️ **待插入的 i18n**：先借用 `searchFilterEmpty`（「「只看 qm」下没有结果」），
     * 它对专辑同样成立、8 语言齐备；新键 `albumFilterEmpty` 落地后换成它即可。
     */
    fun filteredEmptyText(filter: SourceFilter, strings: Strings): String =
        strings.source.albumFilterEmpty(filterLabel(filter, strings))
}
