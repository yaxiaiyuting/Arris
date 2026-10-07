/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.2 · P0：专辑跳转身份判定 + 值域闸门 + 置信度闸门的单测。
 */

package com.takahashirinta.ncrust.source

import com.takahashirinta.ncrust.crosssource.MatchConfidence
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.ui.navigation.NavRoutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.2 · P0 的回归单测。
 *
 * ## 钉住的是哪两件事（两台真机、同一条菜单项、两种错法）
 *
 * | 场景 | 修复前的真实表现 | 本文件的用例 |
 * |---|---|---|
 * | QQ 曲目**新鲜**加载（`album.id` 在，`mid` 被映射层丢过） | 跳到 ncm 的同号专辑（陈奕迅 `22276` → **《百万金曲 陈小云2 苦恋梦 免失志》**） | [QQ 曲目带 albumMID 时直接进 QQ 专辑页] / [QQ 曲目只有数字 albumID 时跳搜索而不是猜一张专辑] |
 * | QQ 曲目**冷启动恢复**（`album` 只有 `picUrl`） | **毫无反应**（静默失败，连网络请求都不发） | [老队列形状的 QQ 曲目跳搜索而不是没反应] |
 *
 * 夹具里的数字全部来自 2026-09 的真实接口响应与真机落盘数据（匿名可复现）：
 * QQ `album` = `{id:22276, mid:"004Z85XP1c25b7", pmid:"004Z85XP1c25b7_5", name:"What's Going On...?"}`；
 * ncm `api/v1/album/22276` = 《百万金曲 陈小云2 苦恋梦 免失志》/ 陈小云；
 * 同专辑的 ncm id 是 `6451`。
 */
class AlbumNavigatorTest {

    // ---------------------------------------------------------------- 夹具

    private fun song(
        source: MusicSource,
        album: AlbumItem?,
        name: String = "富士山下",
        artist: ArtistItem? = ArtistItem(id = null, name = "陈奕迅", mid = null),
    ) = SongItem(
        id = if (source == MusicSource.QQMUSIC) 0x4000_0000_0001_79ADL else 6451L,
        name = name,
        artists = artist?.let { listOf(it) },
        album = album,
        duration = 258_000L,
        source = if (source == MusicSource.NETEASE) null else source.key,
        sourceId = if (source == MusicSource.QQMUSIC) "003aAPj81VWrbL" else null,
    )

    /** 真实形状：QQ 同时给了数字 id、albumMID 与封面 pmid。 */
    private val qqAlbumFull = AlbumItem(
        id = 22276L,
        name = "What's Going On...?",
        picUrl = "https://y.qq.com/music/photo_new/T002R500x500M000004Z85XP1c25b7_5.jpg",
        mid = "004Z85XP1c25b7",
    )

    /** 旧版本 / 丢失路径的形状：只剩数字 id。 */
    private val qqAlbumNumericOnly = AlbumItem(
        id = 22276L,
        name = "What's Going On...?",
        picUrl = null,
        mid = null,
    )

    /**
     * ★ S6 真机 `ncrust_playback_state` 队列第 847 项的**原文形状**：
     * `{"al":{"picUrl":"…T002R500x500M000002Neh8l0uciQZ_3.jpg"}}` —— 没有 id、没有 name。
     */
    private val qqAlbumLegacyQueue = AlbumItem(
        id = null,
        name = null,
        picUrl = "https://y.qq.com/music/photo_new/T002R500x500M000002Neh8l0uciQZ_3.jpg",
        mid = null,
    )

    /** `MainActivity` 冷启动从 ViewModel 状态恢复时构造的形状。 */
    private val qqAlbumVmRestored = AlbumItem(id = null, name = "", picUrl = "https://x/cover.jpg", mid = null)

    private val neteaseAlbum = AlbumItem(id = 6451L, name = "What's Going On…?", picUrl = null, mid = null)

    // ------------------------------------------------ 1. 本源身份优先（Direct）

    @Test
    fun `QQ 曲目带 albumMID 时直接进 QQ 专辑页`() {
        val nav = AlbumNavigator.resolve(song(MusicSource.QQMUSIC, qqAlbumFull))
        assertEquals(AlbumNav.Direct(MusicSource.QQMUSIC, "004Z85XP1c25b7"), nav)
    }

    @Test
    fun `ncm曲目带十进制 id 时直接进ncm专辑页`() {
        val nav = AlbumNavigator.resolve(song(MusicSource.NETEASE, neteaseAlbum))
        assertEquals(AlbumNav.Direct(MusicSource.NETEASE, "6451"), nav)
    }

    // -------------------------------- 2. 本 P0 的确切形状（绝不用数字 id 猜）

    @Test
    fun `QQ 曲目只有数字 albumID 时跳搜索而不是猜一张专辑`() {
        val nav = AlbumNavigator.resolve(song(MusicSource.QQMUSIC, qqAlbumNumericOnly))
        assertEquals(
            "22276 是 QQ 域的数字专辑 id；拿它去 ncm 查会跳到陈小云的《百万金曲…》",
            AlbumNav.Search("What's Going On...?", AlbumNavReason.AMBIGUOUS_NUMERIC_ID),
            nav,
        )
    }

    @Test
    fun `数字 22276 永远不会出现在任何 Direct 里`() {
        // 「跳错专辑」比「找不到」严重（铁律 15）。这条用例是那句话的可执行版本：
        // 遍历所有"身份不可信"的形状，断言 Direct 一次都不出现。
        val shapes = listOf(
            song(MusicSource.QQMUSIC, qqAlbumNumericOnly),
            song(MusicSource.QQMUSIC, qqAlbumLegacyQueue),
            song(MusicSource.QQMUSIC, qqAlbumVmRestored),
            song(MusicSource.QQMUSIC, AlbumItem(id = 22276L, name = null, picUrl = null, mid = null)),
            song(MusicSource.QQMUSIC, null),
        )
        for (s in shapes) {
            val nav = AlbumNavigator.resolve(s)
            assertFalse("${s.album} 竟然产出了 Direct：$nav", nav is AlbumNav.Direct)
        }
    }

    @Test
    fun `把 QQ 的数字 id 塞进 mid 字段同样不跳`() {
        // 值域闸门是**结构性**的：不看字段叫什么名字，只看形状。
        // 「映射层把 id 抄进 mid」这种改法在这里必然失败。
        val smuggled = AlbumItem(id = 22276L, name = "x", picUrl = null, mid = "22276")
        assertFalse(AlbumNavigator.idDomainMatches(MusicSource.QQMUSIC, smuggled.mid))
        val nav = AlbumNavigator.resolve(song(MusicSource.QQMUSIC, smuggled))
        assertTrue("数字 id 顶替 mid 必须被拦下，实际：$nav", nav is AlbumNav.Search)
    }

    @Test
    fun `QQ 的 pmid 不是身份——带下划线会被值域闸门挡下`() {
        // pmid = "<albumMid>_<封面序号>"。实测 QQ 服务端**碰巧**能容忍它，
        // 但那是服务端的宽容、不是契约；把它当身份用等于把契约建立在巧合上。
        val pmidAsMid = AlbumItem(id = 22276L, name = "x", picUrl = null, mid = "004Z85XP1c25b7_5")
        assertFalse(AlbumNavigator.idDomainMatches(MusicSource.QQMUSIC, pmidAsMid.mid))
        val nav = AlbumNavigator.resolve(song(MusicSource.QQMUSIC, pmidAsMid))
        assertTrue("pmid 必须被拦下，实际：$nav", nav is AlbumNav.Search)
    }

    // ------------------------------------------- 3. 第二种症状：不许静默无反应

    @Test
    fun `老队列形状的 QQ 曲目跳搜索而不是没反应`() {
        // 修复前：albumId == null ⇒ 一个分支都不匹配 ⇒ 页面完全不变、无提示、无日志。
        val nav = AlbumNavigator.resolve(song(MusicSource.QQMUSIC, qqAlbumLegacyQueue, name = "稻香"))
        assertEquals(
            "老缓存里连专辑名都没有 ⇒ 关键词必须回落到「曲名 + 艺人名」，否则又变成没反应",
            AlbumNav.Search("稻香 陈奕迅", AlbumNavReason.MISSING_ALBUM_META),
            nav,
        )
    }

    @Test
    fun `冷启动从 ViewModel 恢复的形状也跳搜索`() {
        // MainActivity.kt:944-958 构造的是 AlbumItem(id=null, name="", picUrl=artwork)：
        // 空名字必须当成「没有名字」，不能当成一个长度为 0 的关键词。
        val nav = AlbumNavigator.resolve(song(MusicSource.QQMUSIC, qqAlbumVmRestored, name = "稻香"))
        assertEquals(AlbumNav.Search("稻香 陈奕迅", AlbumNavReason.MISSING_ALBUM_META), nav)
    }

    @Test
    fun `ncm曲目 id 缺失时同样跳搜索——不允许拿名字去猜源`() {
        val noId = AlbumItem(id = null, name = "叶惠美", picUrl = null, mid = null)
        assertEquals(
            AlbumNav.Search("叶惠美", AlbumNavReason.MISSING_ID),
            AlbumNavigator.resolve(song(MusicSource.NETEASE, noId)),
        )
    }

    @Test
    fun `没有专辑名时用「曲名 + 主艺人名」当关键词`() {
        val nav = AlbumNavigator.resolve(song(MusicSource.QQMUSIC, qqAlbumLegacyQueue, name = "晴天"))
        assertEquals("晴天 陈奕迅", (nav as AlbumNav.Search).keyword)
    }

    @Test
    fun `连曲名都没有时什么都不做`() {
        assertEquals(
            AlbumNav.Unavailable,
            AlbumNavigator.resolve(song(MusicSource.QQMUSIC, qqAlbumLegacyQueue, name = "   ")),
        )
        assertEquals(
            AlbumNav.Unavailable,
            AlbumNavigator.resolve(song(MusicSource.QQMUSIC, null, name = "")),
        )
    }

    @Test
    fun `降级为搜索时必须带得出原因`() {
        // 线上排查要靠这个区分「老数据」与「服务端没给」——两者的处置相同、方向不同。
        val reasons = listOf(
            song(MusicSource.QQMUSIC, qqAlbumNumericOnly) to AlbumNavReason.AMBIGUOUS_NUMERIC_ID,
            song(MusicSource.QQMUSIC, qqAlbumLegacyQueue) to AlbumNavReason.MISSING_ALBUM_META,
            song(MusicSource.NETEASE, AlbumItem(id = null, name = "叶惠美", picUrl = null)) to
                AlbumNavReason.MISSING_ID,
        )
        for ((s, expected) in reasons) {
            val nav = AlbumNavigator.resolve(s)
            assertTrue("应当是 Search，实际：$nav", nav is AlbumNav.Search)
            assertEquals(expected, (nav as AlbumNav.Search).reason)
        }
    }

    // ------------------------------------------------- 4. 不变量 1：source 必须带

    @Test
    fun `任何情况下 Direct 的 source 都等于歌曲自己的音源`() {
        // 这条是「跨源跳转在类型上不存在」的可执行版本。
        val songs = listOf(
            song(MusicSource.QQMUSIC, qqAlbumFull),
            song(MusicSource.NETEASE, neteaseAlbum),
            song(MusicSource.QQMUSIC, qqAlbumNumericOnly),
            song(MusicSource.QQMUSIC, qqAlbumLegacyQueue),
            song(MusicSource.NETEASE, neteaseAlbum.copy(id = null, name = "x")),
        )
        for (s in songs) {
            val nav = AlbumNavigator.resolve(s)
            if (nav is AlbumNav.Direct) {
                assertEquals("Direct 的音源跑偏了：$nav", s.musicSource, nav.source)
            }
        }
    }

    @Test
    fun `跳转路由必须把 source 编进路径`() {
        // 「只传数值 ID 不传 source」是本 P0 的成因之一。两段路由把 source 变成
        // **必填参数** —— 这条用例钉住它的形状，防止有人图省事退回单段老路由。
        val qq = AlbumNavigator.resolve(song(MusicSource.QQMUSIC, qqAlbumFull)) as AlbumNav.Direct
        val ne = AlbumNavigator.resolve(song(MusicSource.NETEASE, neteaseAlbum)) as AlbumNav.Direct
        assertEquals("album/qqmusic/004Z85XP1c25b7", NavRoutes.album(qq.source, qq.id))
        assertEquals("album/netease/6451", NavRoutes.album(ne.source, ne.id))
        // 两条路由**不可能相同**：ncm 的 6451 与 QQ 的 004Z85XP1c25b7 落在不同段里。
        assertFalse(NavRoutes.album(qq.source, qq.id) == NavRoutes.album(ne.source, ne.id))
    }

    @Test
    fun `所有宿主入口的曲目形状都产出带 source 的参数`() {
        // 9 个宿主（首页/库页/搜索/歌单/QQ 歌单/本地歌单/专辑/艺人/播放器卡）
        // 交出来的都是同一个 `SongItem`，所以「入口对照」的判据是：
        // 同一首歌无论从哪条路来，`(source, id)` 必须一致。
        // 这里用「同一张 QQ 专辑、同一首歌」的多种字段组合来代表各入口。
        val forms = listOf(
            qqAlbumFull,
            qqAlbumFull.copy(picUrl = null),
            qqAlbumFull.copy(name = null),
            qqAlbumFull.copy(id = null),
            qqAlbumFull.copy(id = null, name = null, picUrl = null),
        )
        val targets = forms.map { AlbumNavigator.resolve(song(MusicSource.QQMUSIC, it)) }
            .filterIsInstance<AlbumNav.Direct>()
        assertTrue("至少要有一种形状能跳成功", targets.isNotEmpty())
        for (t in targets) {
            assertEquals(MusicSource.QQMUSIC, t.source)
            assertEquals("004Z85XP1c25b7", t.id)
        }
    }

    // ------------------------------------------------------ 5. 值域闸门本身

    @Test
    fun `ncm值域只吃十进制且小于 2 的 40 次方`() {
        assertTrue(AlbumNavigator.idDomainMatches(MusicSource.NETEASE, "6451"))
        assertTrue(AlbumNavigator.idDomainMatches(MusicSource.NETEASE, "18905"))
        assertTrue(AlbumNavigator.idDomainMatches(MusicSource.NETEASE, " 6451 "))
        assertFalse(AlbumNavigator.idDomainMatches(MusicSource.NETEASE, "0"))
        assertFalse(AlbumNavigator.idDomainMatches(MusicSource.NETEASE, "-1"))
        assertFalse(AlbumNavigator.idDomainMatches(MusicSource.NETEASE, "004Z85XP1c25b7"))
        assertFalse(AlbumNavigator.idDomainMatches(MusicSource.NETEASE, null))
        assertFalse(AlbumNavigator.idDomainMatches(MusicSource.NETEASE, ""))
        assertFalse(
            "QQ 的 bit62 合成 id 必须落在 ncm 值域之外",
            AlbumNavigator.idDomainMatches(MusicSource.NETEASE, "4611686018427837109"),
        )
    }

    @Test
    fun `QQ 值域只吃 base62 且拒绝ncm值域内的纯数字`() {
        assertTrue(AlbumNavigator.idDomainMatches(MusicSource.QQMUSIC, "004Z85XP1c25b7"))
        assertTrue(AlbumNavigator.idDomainMatches(MusicSource.QQMUSIC, "000MkMni19ClKG"))
        assertTrue(AlbumNavigator.idDomainMatches(MusicSource.QQMUSIC, "003J6fvc0bVJon"))
        // 本 P0 的确切形状：QQ 的数字 albumID
        assertFalse(AlbumNavigator.idDomainMatches(MusicSource.QQMUSIC, "22276"))
        assertFalse(AlbumNavigator.idDomainMatches(MusicSource.QQMUSIC, "7879"))
        assertFalse(AlbumNavigator.idDomainMatches(MusicSource.QQMUSIC, "8220"))
        // pmid（封面照片 id）
        assertFalse(AlbumNavigator.idDomainMatches(MusicSource.QQMUSIC, "004Z85XP1c25b7_5"))
        assertFalse(AlbumNavigator.idDomainMatches(MusicSource.QQMUSIC, "abcd"))
        assertFalse(AlbumNavigator.idDomainMatches(MusicSource.QQMUSIC, null))
    }

    @Test
    fun `艺人闸门与专辑闸门是同一份实现`() {
        // v2.6.2 把值域判据抽到 SourceIdDomain 之后，两边必须逐值一致 ——
        // 「艺人跳得对、专辑跳错」正是分成两份规则时的典型症状。
        val ids = listOf(
            "6451", "22276", "7879", "004Z85XP1c25b7", "004Z85XP1c25b7_5",
            "0025NhlN2yWrP4", "4611686018427837109", "", "abcd", "0",
        )
        for (src in MusicSource.values()) {
            for (id in ids) {
                assertEquals(
                    "($src, $id) 在艺人闸门与专辑闸门上结论不一致",
                    ArtistNavigator.idDomainMatches(src, id),
                    AlbumNavigator.idDomainMatches(src, id),
                )
            }
        }
    }

    // ---------------------------------------------------- 6. 置信度闸门

    @Test
    fun `跨源跳转只有 mergeable 才放行`() {
        assertNotNullNav(AlbumNavigator.crossSourceJump(MatchConfidence.EXACT, MusicSource.QQMUSIC, "004Z85XP1c25b7"))
        assertNotNullNav(AlbumNavigator.crossSourceJump(MatchConfidence.HIGH, MusicSource.QQMUSIC, "004Z85XP1c25b7"))
        assertNotNullNav(AlbumNavigator.crossSourceJump(MatchConfidence.MEDIUM, MusicSource.QQMUSIC, "004Z85XP1c25b7"))
        assertNull(AlbumNavigator.crossSourceJump(MatchConfidence.LOW, MusicSource.QQMUSIC, "004Z85XP1c25b7"))
        assertNull(AlbumNavigator.crossSourceJump(MatchConfidence.NONE, MusicSource.QQMUSIC, "004Z85XP1c25b7"))
        assertNull("没有结论（null）不是「匹配失败」，同样不放行", AlbumNavigator.crossSourceJump(null, MusicSource.QQMUSIC, "004Z85XP1c25b7"))
    }

    @Test
    fun `跨源跳闸门两道都要过——置信度够但目标值域不对也不放行`() {
        assertNull(
            "EXACT 也救不了一个属于另一个值域的 id",
            AlbumNavigator.crossSourceJump(MatchConfidence.EXACT, MusicSource.QQMUSIC, "22276"),
        )
        assertNull(AlbumNavigator.crossSourceJump(MatchConfidence.EXACT, MusicSource.NETEASE, "004Z85XP1c25b7"))
        assertNull(AlbumNavigator.crossSourceJump(MatchConfidence.EXACT, MusicSource.QQMUSIC, null))
    }

    @Test
    fun `当前这条路径上没有可用的跨源结论`() {
        // 探针结论：这条 P0 连匹配都没做，直接把 QQ 的数字 id 交给了 ncm 路由。
        // 所以 resolve 在任何输入下都不该产出"跨源"的 Direct —— 用穷举钉住。
        val all = listOf(
            song(MusicSource.QQMUSIC, qqAlbumFull),
            song(MusicSource.QQMUSIC, qqAlbumNumericOnly),
            song(MusicSource.QQMUSIC, qqAlbumLegacyQueue),
            song(MusicSource.NETEASE, neteaseAlbum),
            song(MusicSource.NETEASE, neteaseAlbum.copy(id = null)),
        )
        for (s in all) {
            val nav = AlbumNavigator.resolve(s)
            if (nav is AlbumNav.Direct) assertEquals(s.musicSource, nav.source)
        }
    }

    // ------------------------------------------------------ 7. 名字不是身份

    @Test
    fun `名字不影响身份判定——同名专辑不会互相顶替`() {
        // 真机复现里两个源的同名专辑，名字在归一化前就不相等
        // （`What's Going On...?` 三个点 vs `What's Going On…?` 省略号）。
        // 即便**完全相等**，判定也不看名字：QQ 侧只有数字 id 时仍然跳搜索。
        val qqNamed = AlbumItem(id = 22276L, name = "What's Going On…?", picUrl = null, mid = null)
        val nav = AlbumNavigator.resolve(song(MusicSource.QQMUSIC, qqNamed))
        assertTrue("同名的 ncm 专辑不能成为 QQ 曲目的跳转目标", nav is AlbumNav.Search)
    }

    @Test
    fun `空名字不会把合法身份降级成搜索`() {
        val nav = AlbumNavigator.resolve(song(MusicSource.QQMUSIC, qqAlbumFull.copy(name = "")))
        assertEquals(AlbumNav.Direct(MusicSource.QQMUSIC, "004Z85XP1c25b7"), nav)
    }

    @Test
    fun `身份可信度是显式的判据而不是调用方自己写 null 判断`() {
        assertTrue(AlbumNavigator.identityTrusted(MusicSource.QQMUSIC, qqAlbumFull))
        assertFalse(AlbumNavigator.identityTrusted(MusicSource.QQMUSIC, qqAlbumNumericOnly))
        assertFalse(AlbumNavigator.identityTrusted(MusicSource.QQMUSIC, qqAlbumLegacyQueue))
        assertFalse(AlbumNavigator.identityTrusted(MusicSource.QQMUSIC, null))
        assertTrue(AlbumNavigator.identityTrusted(MusicSource.NETEASE, neteaseAlbum))
        assertFalse(AlbumNavigator.identityTrusted(MusicSource.NETEASE, neteaseAlbum.copy(id = null)))
    }

    private fun assertNotNullNav(nav: AlbumNav.Direct?) {
        assertTrue("应当放行，实际 null", nav != null)
    }
}
