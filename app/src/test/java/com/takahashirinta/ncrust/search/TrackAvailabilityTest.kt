/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 · C/D 单元测试：**版权可用性**与**原唱/翻唱**的判据。
 *
 * 每一条判据的取值都来自 `docs/verification/v2.3.0/probe-copyright.md` 的实测，
 * 而不是文档推断。用例名后面括号里是那条实测的来源分层。
 */

package com.takahashirinta.ncrust.search

import com.takahashirinta.ncrust.network.NoCopyrightRecommendation
import com.takahashirinta.ncrust.network.OriginSongRef
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.SongPrivilege
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.source.MusicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackAvailabilityTest {

    private fun ne(
        fee: Int? = null,
        st: Int? = null,
        pl: Int? = null,
        noCopyright: Boolean = false,
        oct: Int? = null,
        originSong: OriginSongRef? = null,
    ) = SongItem(
        id = 1L,
        name = "x",
        artists = null,
        album = null,
        duration = null,
        fee = fee,
        privilege = if (st == null && pl == null) null else SongPrivilege(st = st, pl = pl),
        noCopyright = if (noCopyright) NoCopyrightRecommendation("其它版本可播", 2) else null,
        originCoverType = oct,
        originSong = originSong,
    )

    private fun qq(memberOnly: Boolean?) = SongItem(
        id = 2L,
        name = "y",
        artists = null,
        album = null,
        duration = null,
        source = MusicSource.QQMUSIC.key,
        sourceId = "mid",
        memberOnly = memberOnly,
    )

    // ------------------------------------------------ 可播放（pl > 0）----
    // 实测：30/30 可播，假阳性 0（`0/pl>0` 分层）

    @Test
    fun `pl 大于 0 判为可播放`() {
        assertEquals(TrackAvailability.PLAYABLE, TrackAvailability.ofNetease(0, 320000, false, 8))
        assertEquals(TrackAvailability.PLAYABLE, TrackAvailability.ofNetease(0, 128000, false, 0))
        assertEquals(TrackAvailability.PLAYABLE, TrackAvailability.ofNetease(-1, 320000, false, 0))
    }

    // ------------------------------------------------ 需会员（st==0, pl==0）----
    // 实测：30/30 不可播，且这 30 首 fee 全是 1

    @Test
    fun `st 为 0 且 pl 为 0 且 fee 指向会员时判为需会员`() {
        assertEquals(TrackAvailability.MEMBER_ONLY, TrackAvailability.ofNetease(0, 0, false, 1))
        assertEquals(TrackAvailability.MEMBER_ONLY, TrackAvailability.ofNetease(0, 0, false, 4))
    }

    // ------------------------------------------------ ★ st == -1 绝不可用 ----
    // 实测：15 可播 / 15 不可播 —— 正负各半。这是本文件最重要的一条否定断言。

    @Test
    fun `st 为 -1 且 pl 为 0 一律沉默（实测正负各半 不可当判据）`() {
        assertEquals(TrackAvailability.UNKNOWN, TrackAvailability.ofNetease(-1, 0, false, 0))
        assertEquals(TrackAvailability.UNKNOWN, TrackAvailability.ofNetease(-1, 0, false, 1))
        assertEquals(TrackAvailability.UNKNOWN, TrackAvailability.ofNetease(-1, 0, false, 8))
    }

    @Test
    fun `不可用的判据不能把 st 小于 0 一律当成不可播`() {
        // 反向断言：如果实现写成 `st < 0 -> NO_COPYRIGHT`，这个用例会红。
        // 实测那一档里有一半（15/30）是**匿名就能播**的伴奏/纯音乐。
        val v = TrackAvailability.ofNetease(-1, 0, false, 0)
        assertFalse(v == TrackAvailability.NO_COPYRIGHT)
        assertFalse(v == TrackAvailability.MEMBER_ONLY)
    }

    // ------------------------------------------------ 无版权（显式声明）----

    @Test
    fun `noCopyrightRcmd 非空判为无版权（实测零假阳性 但只有 0_3% 覆盖率）`() {
        assertEquals(TrackAvailability.NO_COPYRIGHT, TrackAvailability.ofNetease(0, 0, true, 1))
    }

    @Test
    fun `st 等于 -200 判为无版权（实测样本 晴天 钢琴版）`() {
        assertEquals(TrackAvailability.NO_COPYRIGHT, TrackAvailability.ofNetease(-200, 0, true, 1))
    }

    @Test
    fun `无版权优先于可播放（自相矛盾的数据信显式声明）`() {
        assertEquals(TrackAvailability.NO_COPYRIGHT, TrackAvailability.ofNetease(0, 320000, true, 0))
    }

    // ------------------------------------------------ 沉默 ----

    @Test
    fun `缺 privilege 一律沉默（老队列 JSON 里没有这个 key）`() {
        assertEquals(TrackAvailability.UNKNOWN, TrackAvailability.ofNetease(null, null, false, 0))
        assertEquals(TrackAvailability.UNKNOWN, TrackAvailability.ofNetease(null, null, false, 1))
    }

    @Test
    fun `st 为 0 且 pl 为 0 但 fee 是免费时沉默 而不是断言需会员`() {
        assertEquals(TrackAvailability.UNKNOWN, TrackAvailability.ofNetease(0, 0, false, 0))
        assertEquals(TrackAvailability.UNKNOWN, TrackAvailability.ofNetease(0, 0, false, 8))
    }

    // ------------------------------------------------ QQ：只有会员一个判据 ----

    @Test
    fun `QQ 的 pay_play 等于 1 判为需会员`() {
        assertEquals(TrackAvailability.MEMBER_ONLY, TrackAvailability.ofQq(true))
    }

    @Test
    fun `QQ 的 pay_play 等于 0 不判为可播放（免付费不等于有版权）`() {
        // 反向断言：QQ 侧没有 privilege/pl，也不该拿「不用付费」冒充「可播放」。
        assertEquals(TrackAvailability.UNKNOWN, TrackAvailability.ofQq(false))
        assertEquals(TrackAvailability.UNKNOWN, TrackAvailability.ofQq(null))
    }

    @Test
    fun `按音源分派：QQ 曲目走 QQ 判据 ncm曲目走ncm判据`() {
        assertEquals(TrackAvailability.MEMBER_ONLY, TrackAvailability.of(qq(true)))
        assertEquals(TrackAvailability.UNKNOWN, TrackAvailability.of(qq(false)))
        assertEquals(TrackAvailability.PLAYABLE, TrackAvailability.of(ne(st = 0, pl = 320000)))
    }

    @Test
    fun `只有能确证的取值才带角标`() {
        assertTrue(TrackAvailability.PLAYABLE.hasBadge)
        assertTrue(TrackAvailability.MEMBER_ONLY.hasBadge)
        assertTrue(TrackAvailability.NO_COPYRIGHT.hasBadge)
        assertFalse(TrackAvailability.UNKNOWN.hasBadge)
    }

    // ------------------------------------------------ 排序分组 ----

    @Test
    fun `可播放单独一组 不知道与受限同组靠后`() {
        assertEquals(0, TrackAvailability.rankGroup(TrackAvailability.PLAYABLE))
        assertEquals(1, TrackAvailability.rankGroup(TrackAvailability.MEMBER_ONLY))
        assertEquals(1, TrackAvailability.rankGroup(TrackAvailability.NO_COPYRIGHT))
        assertEquals(1, TrackAvailability.rankGroup(TrackAvailability.UNKNOWN))
    }

    // ------------------------------------------------ 原唱 / 翻唱 ----

    @Test
    fun `originCoverType 等于 1 是原唱 等于 2 是翻唱`() {
        assertEquals(TrackVersionTag.ORIGINAL, TrackVersionTag.ofOriginCoverType(1))
        assertEquals(TrackVersionTag.COVER, TrackVersionTag.ofOriginCoverType(2))
    }

    @Test
    fun `originCoverType 为 0 或 3 或缺失一律沉默（语义未确证 不许猜）`() {
        assertEquals(TrackVersionTag.UNKNOWN, TrackVersionTag.ofOriginCoverType(0))
        assertEquals(TrackVersionTag.UNKNOWN, TrackVersionTag.ofOriginCoverType(3))
        assertEquals(TrackVersionTag.UNKNOWN, TrackVersionTag.ofOriginCoverType(null))
        assertEquals(TrackVersionTag.UNKNOWN, TrackVersionTag.ofOriginCoverType(99))
    }

    @Test
    fun `QQ 曲目恒不显示原唱翻唱（130 条样本里一个可用字段都没有）`() {
        assertEquals(TrackVersionTag.UNKNOWN, TrackVersionTag.of(qq(true)))
        assertEquals(TrackVersionTag.UNKNOWN, TrackVersionTag.of(qq(false)))
    }

    @Test
    fun `ncm曲目按 originCoverType 判`() {
        assertEquals(TrackVersionTag.ORIGINAL, TrackVersionTag.of(ne(oct = 1)))
        assertEquals(TrackVersionTag.COVER, TrackVersionTag.of(ne(oct = 2)))
    }

    @Test
    fun `翻唱的原曲引用是可回查的结构化数据 不是名字匹配`() {
        val ref = OriginSongRef(songId = 186016L, name = "晴天", artists = listOf(ArtistItem(6452L, "周杰伦")))
        val song = ne(oct = 2, originSong = ref)
        assertEquals(186016L, song.originSong?.songId)
        assertEquals("周杰伦", song.originSong?.artists?.first()?.name)
    }

    @Test
    fun `只有能确证的版本标签才带角标`() {
        assertTrue(TrackVersionTag.ORIGINAL.hasBadge)
        assertTrue(TrackVersionTag.COVER.hasBadge)
        assertFalse(TrackVersionTag.UNKNOWN.hasBadge)
    }
}
