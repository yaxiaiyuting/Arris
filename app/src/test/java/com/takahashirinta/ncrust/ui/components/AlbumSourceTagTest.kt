/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.0 单元测试：专辑的音源判定（角标）+ 按音源筛选（含「判定不了」的处置）。
 *
 * 这一组用例存在的理由与 `SongTagsTest` 同源：**「什么时候什么都不显示」和
 * 「判定不了时说什么」是最容易在 UI 里被顺手写成「显示一个默认值」的地方**。
 * 收藏专辑是**已经落盘**的数据（`ncrust_library` / `saved_albums`，跨版本存活），
 * 判错一次就是给用户一句假话，且没有任何修复入口 —— 所以判据全部抽成纯函数钉在这里。
 */

package com.takahashirinta.ncrust.ui.components

import com.takahashirinta.ncrust.library.AlbumInfo
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.ui.i18n.zhCN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumSourceTagTest {

    private val strings = zhCN

    /** 真机形状（`SavedAlbumMidMigrationTest` 用过的那一条）：ncm 量级的裸 id + QQ 的 albumMID。 */
    private val qqMid = "004Z85XP1c25b7"

    // ------------------------------------------------ 1. 判定不了：**不显示**，不猜

    @Test
    fun `albumId 为 0 判不出来——返回 null 而不是ncm`() {
        assertNull("没有依据时必须说「判定不了」", AlbumSourceTag.sourceOf(0L, null))
        assertNull(AlbumSourceTag.badgeLabel(0L, null, strings))
    }

    @Test
    fun `albumId 为负判不出来——返回 null 而不是ncm`() {
        assertNull(AlbumSourceTag.sourceOf(-1L, null))
        assertNull(AlbumSourceTag.sourceOf(-1L, qqMid))
        assertNull(AlbumSourceTag.badgeLabel(-1L, null, strings))
    }

    @Test
    fun `判定不了时角标是 null——UI 侧不许回落成「ncm」`() {
        // 这条是「诚实降级」的可执行版本：null ⇒ 调用点不画角标（LibraryAlbumGridItem 里
        // 它被 listOfNotNull 丢掉），而不是画一个「未知」或「ncm」。
        val label = AlbumSourceTag.badgeLabel(0L, null, strings)
        assertNull(label)
    }

    // ------------------------------------------------ 2. 主判据：albumMID ⇒ qm

    @Test
    fun `有 albumMID 就是 qm——即使数字 id 是ncm量级`() {
        // 收藏专辑这一侧存的是服务端给的**裸**数字 albumID，两个源的编号空间量级相同，
        // 所以字符串身份是唯一不会误判的判据。
        assertEquals(MusicSource.QQMUSIC, AlbumSourceTag.sourceOf(6451L, qqMid))
        assertEquals(MusicSource.QQMUSIC, AlbumSourceTag.sourceOf(22276L, qqMid))
    }

    @Test
    fun `没有 albumMID 且无标志位 ⇒ ncm（这张表唯一写入方的形状）`() {
        // 依据：LibraryManager.refreshFromCloud → PlaylistApi.getSubscribedAlbums()（ncm
        // album_sublist）是这张表的唯一写入方，写入入口只在 source == NETEASE 时挂载。
        assertEquals(MusicSource.NETEASE, AlbumSourceTag.sourceOf(6451L, null))
        assertEquals(MusicSource.NETEASE, AlbumSourceTag.sourceOf(1L, null))
    }

    @Test
    fun `albumMID 为空串 ⇒ 当作没有身份（不误判成 QQ）`() {
        // AGENTS.md v1.9.3 规则 2：空串是「服务端确实没有」的权威结论，不是「字段缺失」。
        assertEquals(MusicSource.NETEASE, AlbumSourceTag.sourceOf(6451L, ""))
    }

    @Test
    fun `albumMID 为纯空白 ⇒ 当作没有身份`() {
        assertEquals(MusicSource.NETEASE, AlbumSourceTag.sourceOf(6451L, "   "))
    }

    // ------------------------------------------------ 3. 结构性判据：id 的标志位

    @Test
    fun `带 QQ 标志位的 id ⇒ qm（即使没有 albumMID）`() {
        val flagged = SourceIds.qqId(22276L, qqMid)
        assertTrue(SourceIds.isQqId(flagged))
        assertEquals(MusicSource.QQMUSIC, AlbumSourceTag.sourceOf(flagged, null))
    }

    @Test
    fun `带 B 站标志位的 id ⇒ B站`() {
        val flagged = SourceIds.biliId(22760301L)
        assertTrue(SourceIds.isBiliId(flagged))
        assertEquals(MusicSource.BILIBILI, AlbumSourceTag.sourceOf(flagged, null))
    }

    @Test
    fun `两个标志位同时置位时先判 QQ——与 sourceOfId 的历史判序一致`() {
        val both = SourceIds.QQ_ID_FLAG or SourceIds.BILI_ID_FLAG or 123L
        assertEquals(
            "判序必须与 SourceIds.sourceOfId 一致，否则同一条 id 在两处会得到两个答案",
            SourceIds.sourceOfId(both),
            AlbumSourceTag.sourceOf(both, null),
        )
        assertEquals(MusicSource.QQMUSIC, AlbumSourceTag.sourceOf(both, null))
    }

    @Test
    fun `标志位优先于 albumMID——冲突数据不会得出两个答案`() {
        // 现实中不会出现（B 站专辑没有 albumMID），这里钉的是**判序**本身。
        val bili = SourceIds.biliId(22760301L)
        assertEquals(MusicSource.BILIBILI, AlbumSourceTag.sourceOf(bili, qqMid))
    }

    // ------------------------------------------------ 4. 反向证据：只用 id 反推会答错

    @Test
    fun `只用 sourceOfId 会把 QQ 收藏专辑说成ncm——所以判据必须带上 albumMID`() {
        // 陈奕迅《What's Going On...?》实测：QQ `album.id = 22276`、ncm = 6451。
        // 裸 id 上没有任何位可区分，`sourceOfId` 只能答「ncm」——那正是用户说的「给错信息」。
        assertEquals(MusicSource.NETEASE, SourceIds.sourceOfId(22276L))
        assertEquals(MusicSource.QQMUSIC, AlbumSourceTag.sourceOf(22276L, qqMid))
    }

    // ------------------------------------------------ 5. 文案出口：与搜索页同一处

    @Test
    fun `角标文案走 SongTags 的同一出口——与搜索结果页逐字相同`() {
        assertEquals(
            SongTags.sourceLabel(MusicSource.NETEASE, strings),
            AlbumSourceTag.badgeLabel(6451L, null, strings),
        )
        assertEquals(
            SongTags.sourceLabel(MusicSource.QQMUSIC, strings),
            AlbumSourceTag.badgeLabel(6451L, qqMid, strings),
        )
    }

    @Test
    fun `AlbumInfo 重载与裸字段重载给出同一答案`() {
        val qq = AlbumInfo(albumId = 6451L, name = "x", picUrl = "p", artist = "a", songCount = 1, mid = qqMid)
        val ne = AlbumInfo(albumId = 6451L, name = "x", picUrl = "p", artist = "a", songCount = 1, mid = null)
        assertEquals(AlbumSourceTag.sourceOf(6451L, qqMid), AlbumSourceTag.sourceOf(qq))
        assertEquals(AlbumSourceTag.sourceOf(6451L, null), AlbumSourceTag.sourceOf(ne))
        assertNotNull(AlbumSourceTag.badgeLabel(qq, strings))
    }

    // ------------------------------------------------ 6. 筛选：未知源只在「全部」档

    private data class Row(val id: Long, val mid: String?)

    private val rows = listOf(
        Row(6451L, null),        // ncm
        Row(22276L, qqMid),      // QQ
        Row(0L, null),           // 判定不了
    )

    private fun filter(which: SourceFilter) =
        AlbumSourceTag.filterAlbums(rows, which) { AlbumSourceTag.sourceOf(it.id, it.mid) }

    @Test
    fun `全部档：一条都不丢，含判定不了的条目`() {
        assertEquals(rows, filter(SourceFilter.ALL))
    }

    @Test
    fun `只看ncm：留下ncm那条，且保序`() {
        assertEquals(listOf(6451L), filter(SourceFilter.NETEASE).map { it.id })
    }

    @Test
    fun `只看 qm：留下 QQ 那条`() {
        assertEquals(listOf(22276L), filter(SourceFilter.QQMUSIC).map { it.id })
    }

    @Test
    fun `判定不了音源的条目不会被塞进任何单源档`() {
        // 它不属于任何一源；把它算进「只看 ncm」就是替用户下结论。
        assertTrue(filter(SourceFilter.NETEASE).none { it.id == 0L })
        assertTrue(filter(SourceFilter.QQMUSIC).none { it.id == 0L })
    }

    @Test
    fun `筛选不重排——顺序仍由数据源决定`() {
        val mixed = listOf(
            Row(22276L, qqMid),
            Row(6451L, null),
            Row(2L, null),
        )
        val filtered = AlbumSourceTag.filterAlbums(mixed, SourceFilter.ALL) { AlbumSourceTag.sourceOf(it.id, it.mid) }
        assertEquals(mixed, filtered)
        assertEquals(
            "按源过滤也只做 filter，不 sortedBy",
            listOf(6451L, 2L),
            AlbumSourceTag.filterAlbums(mixed, SourceFilter.NETEASE) { AlbumSourceTag.sourceOf(it.id, it.mid) }
                .map { it.id },
        )
    }

    // ------------------------------------------------ 7. 筛选档的可见性（P0-D 教训）

    @Test
    fun `一张专辑都没有时不挂筛选档`() {
        assertEquals(false, AlbumSourceTag.shouldShowFilterRow(0))
    }

    @Test
    fun `有专辑就挂筛选档——判据只用筛选前的总数`() {
        assertEquals(true, AlbumSourceTag.shouldShowFilterRow(1))
        assertEquals(true, AlbumSourceTag.shouldShowFilterRow(50))
    }

    @Test
    fun `专辑列表的档位固定三档——不含 B 站（B 站没有专辑实体）`() {
        assertEquals(
            listOf(SourceFilter.ALL, SourceFilter.NETEASE, SourceFilter.QQMUSIC),
            AlbumSourceTag.albumFilters,
        )
        assertTrue("B 站档不该出现在专辑筛选里", SourceFilter.BILIBILI !in AlbumSourceTag.albumFilters)
    }

    @Test
    fun `档位文案：单源档指名该源，全部档非空`() {
        val netease = AlbumSourceTag.filterLabel(SourceFilter.NETEASE, strings)
        val qq = AlbumSourceTag.filterLabel(SourceFilter.QQMUSIC, strings)
        assertEquals(SourceFilter.NETEASE.label(strings), netease)
        assertEquals(SourceFilter.QQMUSIC.label(strings), qq)
        assertTrue("单源档必须与另一档不同", netease != qq)
        assertTrue(AlbumSourceTag.filterLabel(SourceFilter.ALL, strings).isNotBlank())
    }

    @Test
    fun `筛空文案必须指名当前档位——与「暂无收藏专辑」是两句话`() {
        val text = AlbumSourceTag.filteredEmptyText(SourceFilter.QQMUSIC, strings)
        assertTrue("空态必须写出当前档位：$text", text.contains(AlbumSourceTag.filterLabel(SourceFilter.QQMUSIC, strings)))
        assertTrue("不能复用「一张都没收藏」那句：$text", text != strings.noSavedAlbums)
    }
}
