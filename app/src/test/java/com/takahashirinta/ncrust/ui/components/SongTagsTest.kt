/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 · C/D 单元测试：列表行的角标装配（音源归属 + 版权可用性 + 原唱/翻唱）。
 *
 * 这一组用例存在的理由：**「什么时候什么都不显示」是最容易在 UI 里被顺手写成
 * 「显示一个默认值」的地方**，而那正好是任务书 5.2 明令禁止的
 * 「在搜索阶段就假设某源可播」。把装配抽成纯函数之后，这些「不显示」都能被断言。
 */

package com.takahashirinta.ncrust.ui.components

import com.takahashirinta.ncrust.network.NoCopyrightRecommendation
import com.takahashirinta.ncrust.network.OriginSongRef
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.SongPrivilege
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.search.TrackAvailability
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.ui.i18n.deDE
import com.takahashirinta.ncrust.ui.i18n.en
import com.takahashirinta.ncrust.ui.i18n.jpJP
import com.takahashirinta.ncrust.ui.i18n.jpMY
import com.takahashirinta.ncrust.ui.i18n.koNK
import com.takahashirinta.ncrust.ui.i18n.ruRU
import com.takahashirinta.ncrust.ui.i18n.zhCN
import com.takahashirinta.ncrust.ui.i18n.zhTW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongTagsTest {

    private val strings = zhCN

    private fun ne(
        id: Long = 1L,
        fee: Int? = null,
        st: Int? = null,
        pl: Int? = null,
        noCopyright: Boolean = false,
        oct: Int? = null,
        originSong: OriginSongRef? = null,
    ) = SongItem(
        id = id,
        name = "晴天",
        artists = null,
        album = null,
        duration = null,
        fee = fee,
        privilege = if (st == null && pl == null) null else SongPrivilege(st, pl),
        noCopyright = if (noCopyright) NoCopyrightRecommendation("其它版本可播", 2) else null,
        originCoverType = oct,
        originSong = originSong,
    )

    private fun qq(memberOnly: Boolean? = null) = SongItem(
        id = 2L,
        name = "晴天",
        artists = null,
        album = null,
        duration = null,
        source = MusicSource.QQMUSIC.key,
        sourceId = "00083kc41YcFuR",
        memberOnly = memberOnly,
    )

    // ------------------------------------------------ 音源归属：两源都标 ----

    @Test
    fun `ncm的行也标音源（v230 的改变）`() {
        // 用一个**判不出可用性**的曲目，把音源角标单独隔离出来看。
        val tags = SongTags.of(ne(st = -1, pl = 0, fee = 0), strings)
        assertEquals(1, tags.size)
        assertEquals(SongTagKind.SOURCE, tags[0].kind)
        assertEquals(strings.sourceNetease, tags[0].text)
        // 可播放时它会多出一个可用性角标，但音源仍是第一个。
        val playable = SongTags.of(ne(st = 0, pl = 320000), strings)
        assertEquals(2, playable.size)
        assertEquals(strings.sourceNetease, playable[0].text)
    }

    @Test
    fun `QQ 的行标 qm`() {
        val tags = SongTags.of(qq(), strings)
        assertEquals(strings.sourceQqMusic, tags.single().text)
        assertEquals(SongTagKind.SOURCE, tags.single().kind)
    }

    @Test
    fun `同名不同源的两行角标不同 这正是聚合搜索的歧义来源`() {
        val a = SongTags.of(ne(st = 0, pl = 320000), strings).first().text
        val b = SongTags.of(qq(), strings).first().text
        assertFalse(a == b)
    }

    @Test
    fun `音源角标永远排第一`() {
        val tags = SongTags.of(ne(st = 0, pl = 320000, oct = 1), strings)
        assertEquals(SongTagKind.SOURCE, tags.first().kind)
    }

    // ------------------------------------------------ 版权可用性 ----

    @Test
    fun `可播放角标在 pl 大于 0 时出现`() {
        val tags = SongTags.of(ne(st = 0, pl = 320000), strings)
        assertTrue(tags.any { it.kind == SongTagKind.AVAILABILITY && it.text == strings.tagPlayable })
    }

    @Test
    fun `需会员角标在 st 为 0 且 pl 为 0 且 fee 为 1 时出现`() {
        val tags = SongTags.of(ne(fee = 1, st = 0, pl = 0), strings)
        assertTrue(tags.any { it.kind == SongTagKind.AVAILABILITY && it.text == strings.tagMemberOnly })
    }

    @Test
    fun `无版权角标在服务端显式声明时出现`() {
        val tags = SongTags.of(ne(fee = 1, st = 0, pl = 0, noCopyright = true), strings)
        assertTrue(tags.any { it.kind == SongTagKind.AVAILABILITY && it.text == strings.tagNoCopyright })
    }

    @Test
    fun `判不出来时一个可用性角标都不显示（不许假设某源可播）`() {
        // st == -1：实测 15/30 能播 —— 必须沉默
        val tags = SongTags.of(ne(fee = 1, st = -1, pl = 0), strings)
        assertFalse(tags.any { it.kind == SongTagKind.AVAILABILITY })
        // 完全没有 privilege（老数据）
        val legacy = SongTags.of(ne(), strings)
        assertFalse(legacy.any { it.kind == SongTagKind.AVAILABILITY })
    }

    @Test
    fun `QQ 侧永远没有可用性角标（除了会员）`() {
        assertFalse(SongTags.of(qq(memberOnly = false), strings).any { it.kind == SongTagKind.AVAILABILITY })
        assertTrue(
            SongTags.of(qq(memberOnly = true), strings)
                .any { it.kind == SongTagKind.AVAILABILITY && it.text == strings.tagMemberOnly },
        )
    }

    // ------------------------------------------------ 原唱 / 翻唱 ----

    @Test
    fun `原唱角标只在 originCoverType 为 1 时出现`() {
        val tags = SongTags.of(ne(oct = 1), strings)
        assertTrue(tags.any { it.kind == SongTagKind.VERSION && it.text == strings.tagOriginal })
    }

    @Test
    fun `翻唱角标只在 originCoverType 为 2 时出现`() {
        val tags = SongTags.of(ne(oct = 2), strings)
        assertTrue(tags.any { it.kind == SongTagKind.VERSION && it.text == strings.tagCover })
    }

    @Test
    fun `originCoverType 为 0 或 3 时不显示版本角标`() {
        assertFalse(SongTags.of(ne(oct = 0), strings).any { it.kind == SongTagKind.VERSION })
        assertFalse(SongTags.of(ne(oct = 3), strings).any { it.kind == SongTagKind.VERSION })
        assertFalse(SongTags.of(qq(), strings).any { it.kind == SongTagKind.VERSION })
    }

    @Test
    fun `标题里自称原唱但服务端标注翻唱时 以服务端为准`() {
        // 真实样本：`晴天 (原唱 周杰伦)` 的 originCoverType == 2 —— 它是翻唱。
        val song = SongItem(
            id = 3L, name = "晴天 (原唱 周杰伦)", artists = null, album = null, duration = null,
            originCoverType = 2,
            originSong = OriginSongRef(186016L, "晴天", listOf(ArtistItem(6452L, "周杰伦"))),
        )
        assertTrue(SongTags.of(song, strings).any { it.text == strings.tagCover })
    }

    // ------------------------------------------------ 翻唱的原曲副标题 ----

    @Test
    fun `翻唱且有原曲信息时给出原唱行`() {
        val song = ne(oct = 2, originSong = OriginSongRef(186016L, "晴天", listOf(ArtistItem(6452L, "周杰伦"))))
        val line = SongTags.coverOriginLine(song, strings)
        assertEquals(strings.tagCoverOrigin("周杰伦", "晴天"), line)
    }

    @Test
    fun `翻唱但服务端没给原曲信息时不给原唱行（不猜）`() {
        assertNull(SongTags.coverOriginLine(ne(oct = 2), strings))
    }

    @Test
    fun `不是翻唱时不给原唱行`() {
        val song = ne(oct = 1, originSong = OriginSongRef(1L, "x", null))
        assertNull(SongTags.coverOriginLine(song, strings))
    }

    @Test
    fun `原曲艺人为空时只显示曲名 不编一个未知歌手`() {
        val song = ne(oct = 2, originSong = OriginSongRef(1L, "原曲", null))
        assertEquals(strings.tagCoverOrigin(strings.unknownArtist, "原曲"), SongTags.coverOriginLine(song, strings))
    }

    @Test
    fun `原曲名为空时不给原唱行`() {
        val song = ne(oct = 2, originSong = OriginSongRef(1L, "   ", listOf(ArtistItem(1L, "A"))))
        assertNull(SongTags.coverOriginLine(song, strings))
    }

    // ------------------------------------------------ 组合 ----

    @Test
    fun `三个角标同时存在时顺序是 音源 可用性 版本`() {
        val tags = SongTags.of(ne(st = 0, pl = 320000, oct = 1), strings)
        assertEquals(
            listOf(SongTagKind.SOURCE, SongTagKind.AVAILABILITY, SongTagKind.VERSION),
            tags.map { it.kind },
        )
    }

    @Test
    fun `最坏情况（QQ 且什么都不知道）只有音源一个角标`() {
        val tags = SongTags.of(qq(memberOnly = null), strings)
        assertEquals(1, tags.size)
        assertEquals(SongTagKind.SOURCE, tags.single().kind)
    }

    // ------------------------------------------------ v2.4.0 · 探测结果覆盖 ----

    @Test
    fun `探测结果覆盖 优先于 SongItem 推导出来的可用性`() {
        // SongItem 自己说「可播放」（st=0 且 pl=320000），但本次探测说「需会员」。
        // 聚合页看到的必须是**探测结果** —— 它才是与账号和时刻绑定的那个事实。
        val song = ne(st = 0, pl = 320000)
        val tags = SongTags.of(song, strings, TrackAvailability.MEMBER_ONLY)
        assertTrue(tags.any { it.kind == SongTagKind.AVAILABILITY && it.text == strings.tagMemberOnly })
        assertFalse(tags.any { it.text == strings.tagPlayable })
    }

    @Test
    fun `探测结果覆盖 为 UNKNOWN 时不显示可用性角标（哪怕 SongItem 说可播）`() {
        // 探测缺席 / 探测不出来 ⇒ 沉默。这既是「不允许在搜索阶段假设某源可播」，
        // 也是它的反面：不允许拿**落盘的旧结论**去补一个角标。
        val tags = SongTags.of(ne(st = 0, pl = 320000), strings, TrackAvailability.UNKNOWN)
        assertFalse(tags.any { it.kind == SongTagKind.AVAILABILITY })
    }

    @Test
    fun `null 覆盖 与既有 2 参重载逐字节等价`() {
        // 这一条是「默认行为不能因为多了一条通道就变」的保证：两源、四种可用性、
        // 版本标签齐全/缺失都过一遍。默认值 null 必须与 v2.3.0 的实现完全一致。
        val samples = listOf(
            ne(st = -1, pl = 0, fee = 0),
            ne(st = 0, pl = 320000),
            ne(fee = 1, st = 0, pl = 0),
            ne(fee = 1, st = 0, pl = 0, noCopyright = true),
            ne(oct = 1),
            ne(oct = 2, originSong = OriginSongRef(1L, "原曲", null)),
            ne(),
            qq(memberOnly = true),
            qq(memberOnly = null),
        )
        for (song in samples) {
            assertEquals(SongTags.of(song, strings), SongTags.of(song, strings, null))
        }
    }

    @Test
    fun `覆盖可用性不影响音源与版本角标`() {
        val song = ne(st = 0, pl = 320000, oct = 1)
        val tags = SongTags.of(song, strings, TrackAvailability.NO_COPYRIGHT)
        assertEquals(
            listOf(SongTagKind.SOURCE, SongTagKind.AVAILABILITY, SongTagKind.VERSION),
            tags.map { it.kind },
        )
        assertEquals(strings.sourceNetease, tags[0].text)
        assertEquals(strings.tagNoCopyright, tags[1].text)
        assertEquals(strings.tagOriginal, tags[2].text)
    }

    @Test
    fun `QQ 侧的探测结果同样能覆盖（QQ 推导永远只有会员一档）`() {
        // QQ 的 `of` 只有 pay.pay_play 一个判据，推导不出「可播放」；
        // 只有**探测**（去要一次 purl）才知道。这条是那条通道的用法示例。
        val song = qq(memberOnly = null)
        assertEquals(1, SongTags.of(song, strings).size)
        val tags = SongTags.of(song, strings, TrackAvailability.PLAYABLE)
        assertTrue(tags.any { it.kind == SongTagKind.AVAILABILITY && it.text == strings.tagPlayable })
    }

    // ================================================================
    // v2.5.5 · D：搜索历史的音源角标
    // ================================================================

    /**
     * ★ 单曲分区**必须**给角标 —— 这是「区分同名历史」的唯一线索。
     *
     * 同名场景是真实的：同一关键词下两源会返回逐字同名的条目
     * （《晴天》ncm `186016` / QQ 另一套 songmid），历史列表里两行的
     * 封面、标题、歌手可能完全一样。
     */
    @Test
    fun `单曲历史条目显示音源角标`() {
        assertEquals(
            zhCN.sourceNetease,
            SongTags.historySourceBadge(true, MusicSource.NETEASE, zhCN),
        )
        assertEquals(
            zhCN.sourceQqMusic,
            SongTags.historySourceBadge(true, MusicSource.QQMUSIC, zhCN),
        )
    }

    /**
     * ★ 专辑 / 艺人历史**不显示**角标。
     *
     * 它们的 `HistoryItem.source` 从来没有被写过（`addAlbum` / `addArtist` 不传它），
     * `effectiveSource` 只能靠 bit62 反推 —— 而专辑/艺人的 id 都是 ncm 的普通 id，
     * 反推恒为「ncm」。给每一条挂一个恒定标签是纯噪音。
     */
    @Test
    fun `专辑与艺人历史条目不显示音源角标`() {
        assertNull(SongTags.historySourceBadge(false, MusicSource.NETEASE, zhCN))
        assertNull(SongTags.historySourceBadge(false, MusicSource.QQMUSIC, zhCN))
    }

    /**
     * ★ 两个音源的文案**必须不同**，而且都不为空。
     *
     * 若两源给出同一串（例如都回落成「未知音源」），这个角标就完全失去意义 ——
     * 而它恰恰是「同名历史」唯一的区分手段。
     */
    @Test
    fun `两种音源的历史角标文案互不相同且非空`() {
        val presets = listOf(zhCN, zhTW, en, jpJP, jpMY, koNK, deDE, ruRU)
        presets.forEach { s ->
            val netease = SongTags.historySourceBadge(true, MusicSource.NETEASE, s)
            val qq = SongTags.historySourceBadge(true, MusicSource.QQMUSIC, s)
            assertTrue("ncm 角标为空", !netease.isNullOrBlank())
            assertTrue("QQ 角标为空", !qq.isNullOrBlank())
            assertNotEquals("两种音源的角标文案撞了：$netease", netease, qq)
        }
    }

    /**
     * ★ **老条目**（`source == null` 但 id 的 bit62 置位）必须显示 **qm**。
     *
     * 这是 v2.5.4 修过的那个 bug 的 UI 侧：`SongItem.musicSource` 读的是**字符串**，
     * 而老条目那里是 null ⇒ 会被认成 ncm。角标必须走
     * `SearchHistoryMigration.effectiveSource`（bit62 推断），不能直接读字符串。
     *
     * 本用例通过 `effectiveSource` 的输入口径把这条事实钉住：
     * 用 bit62 合成 id 造一条 `source == null` 的历史条目，断言角标是 QQ。
     */
    @Test
    fun `老条目 source 为空但 bit62 置位时角标是 qm`() {
        val legacyQqId = com.takahashirinta.ncrust.source.SourceIds.qqId(357600093L, "0039MnYb0qxYhV")
        val item = com.takahashirinta.ncrust.library.SearchHistoryManager.HistoryItem(
            id = legacyQqId,
            title = "晴天",
            coverUrl = null,
            subtitle = "周杰伦",
            source = null,          // ← v2.5.4 之前写入的形状
            sourceId = null,
            mediaId = null,
        )
        val effective = com.takahashirinta.ncrust.library.SearchHistoryMigration.effectiveSource(item)
        assertEquals(
            "老条目被认成了 ncm —— 角标必须走 effectiveSource 的 bit62 推断",
            MusicSource.QQMUSIC,
            effective,
        )
        assertEquals(zhCN.sourceQqMusic, SongTags.historySourceBadge(true, effective, zhCN))
    }
}
