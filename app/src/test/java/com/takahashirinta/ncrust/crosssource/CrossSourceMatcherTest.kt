/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.4.0 · E：跨源匹配与版权优先排序的回归单测。
 *
 * 用例里的名字、专辑数、时长**全部来自探针的真实数据**
 * （`docs/verification/v2.4.0/probe-raw/{artist,album,song}-mapping.json`），
 * 不是编出来的 —— 编出来的数据只会让测试通过，不会让功能正确。
 */

package com.takahashirinta.ncrust.crosssource

import com.takahashirinta.ncrust.search.TrackAvailability
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossSourceMatcherTest {

    private val ne = MusicSource.NETEASE
    private val qq = MusicSource.QQMUSIC

    private fun artist(source: MusicSource, id: String, name: String, albums: List<String>, hint: Int = 0) =
        CrossSourceMatcher.ArtistCandidate(ArtistKey(source, id, name), albums, hint)

    private fun album(
        source: MusicSource,
        id: String,
        name: String,
        tracks: List<String>,
        count: Int? = tracks.size,
        artists: List<String> = emptyList(),
    ) = CrossSourceMatcher.AlbumCandidate(
        key = AlbumKey(source, id, name),
        trackNames = tracks,
        trackCount = count,
        rawName = name,
        artistNames = artists,
    )

    private fun track(source: MusicSource, id: Long, name: String, artists: List<String>, ms: Long?) =
        CrossSourceMatcher.TrackCandidate(TrackKey(source, id, if (source == qq) "mid$id" else null), name, artists, ms)

    // ================================================================== 艺人 ==================================================================

    @Test
    fun `艺人 EXACT —— 唯一同名候选且专辑高度重合（周杰伦探针实测 33 张）`() {
        val neAlbums = listOf("Jay", "范特西", "八度空间", "叶惠美", "七里香")
        val candidate = artist(qq, "0025NhlN2yWrP4", "周杰伦", neAlbums, hint = 43)
        val verdict = CrossSourceMatcher.gradeArtist("周杰伦", neAlbums, candidate, unique = true)
        assertEquals(MatchConfidence.EXACT, verdict.confidence)
        assertTrue(verdict.reason.contains("专辑重合"))
    }

    @Test
    fun `艺人 HIGH —— 同名且专辑重合达标但候选不唯一`() {
        val neAlbums = listOf("Jay", "范特西", "叶惠美")
        val candidate = artist(qq, "aaa", "李健", neAlbums, hint = 44)
        val verdict = CrossSourceMatcher.gradeArtist("李健", neAlbums, candidate, unique = false)
        assertEquals(MatchConfidence.HIGH, verdict.confidence)
    }

    @Test
    fun `艺人 MEDIUM —— 同名但只重合 1 到 2 张`() {
        val neAlbums = listOf("Jay", "范特西", "叶惠美")
        val candidate = artist(qq, "aaa", "周杰伦", listOf("叶惠美", "我很忙"), hint = 2)
        val verdict = CrossSourceMatcher.gradeArtist("周杰伦", neAlbums, candidate, unique = false)
        assertEquals(MatchConfidence.MEDIUM, verdict.confidence)
        assertTrue(verdict.mergeable)
    }

    @Test
    fun `艺人 LOW —— 仅同名且专辑零重合，不许合并`() {
        val neAlbums = listOf("Jay", "范特西")
        val candidate = artist(qq, "aaa", "周杰伦jay", listOf("完全不同的专辑"), hint = 1)
        val verdict = CrossSourceMatcher.gradeArtist("周杰伦", neAlbums, candidate, unique = false)
        assertEquals(MatchConfidence.LOW, verdict.confidence)
        assertFalse(verdict.mergeable)
    }

    @Test
    fun `艺人 LOW —— 一侧专辑列表拿不到时不猜（校验数据缺失宁可不合并）`() {
        val candidate = artist(qq, "aaa", "周杰伦", emptyList(), hint = 43)
        val verdict = CrossSourceMatcher.gradeArtist("周杰伦", listOf("Jay"), candidate, unique = true)
        assertEquals(MatchConfidence.LOW, verdict.confidence)
        assertTrue(verdict.reason.contains("无法校验"))
    }

    @Test
    fun `艺人 NONE —— 名称召回不上`() {
        val candidate = artist(qq, "aaa", "林俊杰", listOf("JJ陆"), hint = 40)
        val verdict = CrossSourceMatcher.gradeArtist("周杰伦", listOf("Jay"), candidate, unique = true)
        assertEquals(MatchConfidence.NONE, verdict.confidence)
    }

    /**
     * 这条是本版**最重要的一条回归**：探针里朴素同名算法在邓紫棋上给出 `NONE`，
     * 而正确结果是 `EXACT`。用例逐字复刻那次数据。
     */
    @Test
    fun `艺人匹配能认出艺名不等于真名的那一对（邓紫棋 探针 P6）`() {
        val neAlbums = listOf("18...", "My Secret", "Xposed", "新的心跳", "摩天动物园")
        val candidates = listOf(
            // 仿冒号：专辑少、名字是子串
            artist(ne, "62017015", "邓紫棋", listOf("仿冒专辑"), hint = 1),
            // 真身：名字更长，专辑多
            artist(qq, "001fNHEf1SFEFN", "G.E.M.邓紫棋", neAlbums, hint = 68),
        )
        val matched = CrossSourceMatcher.matchArtist("邓紫棋", neAlbums, candidates)
        assertNotNull(matched)
        assertEquals("G.E.M.邓紫棋", matched!!.first.key.name)
        assertTrue(matched.second.confidence.mergeable)
    }

    @Test
    fun `艺人召回按专辑数降序 且候选校验有上限（有界）`() {
        val candidates = (1..10).map { artist(qq, "m$it", "李健", listOf("专辑$it"), hint = it) }
        val recalled = CrossSourceMatcher.recallArtists("李健", candidates)
        assertEquals(10, recalled.size)
        assertEquals(10, recalled.first().albumCountHint)
        // 上限是常量，不是「看情况」—— 铁律 6 要求所有循环路径都有界。
        assertEquals(3, CrossSourceMatcher.MAX_ARTIST_CANDIDATES)
    }

    // ================================================================== 专辑 ==================================================================

    @Test
    fun `专辑 EXACT —— 名相同 加 艺人一致 加 曲目数一致 加 曲目名重合`() {
        val tracks = listOf("以父之名", "懦夫", "晴天", "三年二班", "东风破")
        val a = album(ne, "18905", "叶惠美", tracks, artists = listOf("周杰伦"))
        val b = album(qq, "000MkMni19ClKG", "叶惠美", tracks, artists = listOf("周杰伦"))
        val verdict = CrossSourceMatcher.gradeAlbum(a, b, CrossSourceMatcher.trackOverlapRatio(a.trackNames, b.trackNames))
        assertEquals(MatchConfidence.EXACT, verdict.confidence)
    }

    @Test
    fun `专辑 HIGH —— 名需要归一化才相等（Taylor's Version 探针实测这一类占 15 of 48）`() {
        val tracks = listOf("Welcome To New York", "Blank Space", "Style")
        val a = album(ne, "177825165", "1989 (Taylor's Version) (Deluxe)", tracks, artists = listOf("Taylor Swift"))
        val b = album(qq, "002Kz5Jo1uzHjz", "1989 (Taylor's Version) (Deluxe)", tracks, artists = listOf("Taylor Swift"))
        // 名称相同 ⇒ EXACT；把一侧改成带额外版本词，验证降级到 HIGH 而不是 NONE
        val c = album(qq, "002Kz5Jo1uzHjz", "1989 (Taylor's Version) Deluxe", tracks, artists = listOf("Taylor Swift"))
        val ratio = CrossSourceMatcher.trackOverlapRatio(a.trackNames, c.trackNames)
        assertEquals(MatchConfidence.HIGH, CrossSourceMatcher.gradeAlbum(a, c, ratio).confidence)
        assertEquals(MatchConfidence.EXACT, CrossSourceMatcher.gradeAlbum(a, b, ratio).confidence)
    }

    @Test
    fun `专辑 MEDIUM —— 名一致 艺人一致 但曲目名几乎不重合（探针里 4 对 MEDIUM）`() {
        val a = album(ne, "1", "后青春期的诗", listOf("突然好想你", "生存以上生活以下"), artists = listOf("五月天"))
        val b = album(qq, "2", "后青春期的诗", listOf("完全不同的曲目"), artists = listOf("五月天"))
        val ratio = CrossSourceMatcher.trackOverlapRatio(a.trackNames, b.trackNames)
        assertEquals(MatchConfidence.MEDIUM, CrossSourceMatcher.gradeAlbum(a, b, ratio).confidence)
    }

    @Test
    fun `专辑 LOW —— 名一致但艺人不同，不许合并`() {
        val a = album(ne, "1", "叶惠美", listOf("晴天"), artists = listOf("周杰伦"))
        val b = album(qq, "2", "叶惠美", listOf("晴天"), artists = listOf("夜未央"))
        val ratio = CrossSourceMatcher.trackOverlapRatio(a.trackNames, b.trackNames)
        val verdict = CrossSourceMatcher.gradeAlbum(a, b, ratio)
        assertEquals(MatchConfidence.LOW, verdict.confidence)
        assertFalse(verdict.mergeable)
    }

    @Test
    fun `专辑 NONE —— 名归一化后不等`() {
        val a = album(ne, "1", "叶惠美", listOf("晴天"), artists = listOf("周杰伦"))
        val b = album(qq, "2", "叶惠美 现场", listOf("晴天"), artists = listOf("周杰伦"))
        val ratio = CrossSourceMatcher.trackOverlapRatio(a.trackNames, b.trackNames)
        assertEquals(MatchConfidence.NONE, CrossSourceMatcher.gradeAlbum(a, b, ratio).confidence)
    }

    @Test
    fun `曲目重合率在两侧都空时是 0 而不是 1（空不能变成满分）`() {
        assertEquals(0.0, CrossSourceMatcher.trackOverlapRatio(emptyList(), emptyList()), 1e-9)
        assertEquals(0.0, CrossSourceMatcher.trackOverlapRatio(listOf("a"), emptyList()), 1e-9)
        assertEquals(1.0, CrossSourceMatcher.trackOverlapRatio(listOf("a"), listOf("a")), 1e-9)
    }

    // ================================================================== 单曲 ==================================================================

    @Test
    fun `单曲 EXACT —— 曲名 加 艺人 加 时长一致 加 版本标记一致`() {
        val a = track(ne, 186016, "晴天", listOf("周杰伦"), 269_000)
        val b = track(qq, 97773, "晴天", listOf("周杰伦"), 269_000)
        val verdict = CrossSourceMatcher.gradeTrack(a, b)
        assertEquals(MatchConfidence.EXACT, verdict.confidence)
    }

    @Test
    fun `单曲 EXACT 的时长容差是 2 秒（探针实测 2 秒内占 184 of 230）`() {
        val a = track(ne, 1, "晴天", listOf("周杰伦"), 269_000)
        val b = track(qq, 2, "晴天", listOf("周杰伦"), 270_543)
        assertEquals(MatchConfidence.EXACT, CrossSourceMatcher.gradeTrack(a, b).confidence)
        val c = track(qq, 3, "晴天", listOf("周杰伦"), 271_500)
        assertEquals(MatchConfidence.LOW, CrossSourceMatcher.gradeTrack(a, c).confidence)
    }

    @Test
    fun `单曲 HIGH —— 时长一致但版本标记不同（原曲 vs 伴奏）`() {
        val a = track(ne, 1, "你过得好吗", listOf("薛之谦"), 240_000)
        val b = track(qq, 2, "你过得好吗(伴奏)", listOf("薛之谦"), 240_000)
        assertEquals(MatchConfidence.HIGH, CrossSourceMatcher.gradeTrack(a, b).confidence)
    }

    @Test
    fun `单曲 MEDIUM —— 一侧没有时长时给 MEDIUM 而不是 EXACT`() {
        val a = track(ne, 1, "晴天", listOf("周杰伦"), 269_000)
        val b = track(qq, 2, "晴天", listOf("周杰伦"), null)
        assertEquals(MatchConfidence.MEDIUM, CrossSourceMatcher.gradeTrack(a, b).confidence)
        assertTrue(CrossSourceMatcher.gradeTrack(a, b).mergeable)
    }

    @Test
    fun `单曲 LOW —— 同名但艺人不同 不许合并（宁可分开也不给错单曲）`() {
        val a = track(ne, 1, "晴天", listOf("周杰伦"), 269_000)
        val b = track(qq, 2, "晴天", listOf("RyaVocal"), 269_000)
        val verdict = CrossSourceMatcher.gradeTrack(a, b)
        assertEquals(MatchConfidence.LOW, verdict.confidence)
        assertFalse(verdict.mergeable)
    }

    @Test
    fun `单曲 LOW —— 同名同艺人但时长差很大（探针里 15 条超过 30s 的全是这一类）`() {
        val a = track(ne, 1, "Enrich Your Life", listOf("五月天"), 240_000)
        val b = track(qq, 2, "Enrich Your Life(伴奏)", listOf("五月天"), 435_000)
        val verdict = CrossSourceMatcher.gradeTrack(a, b)
        assertEquals(MatchConfidence.LOW, verdict.confidence)
        assertFalse(verdict.mergeable)
    }

    @Test
    fun `单曲 NONE —— 曲名归一化后不等`() {
        val a = track(ne, 1, "晴天", listOf("周杰伦"), 269_000)
        val b = track(qq, 2, "雨天", listOf("周杰伦"), 269_000)
        assertEquals(MatchConfidence.NONE, CrossSourceMatcher.gradeTrack(a, b).confidence)
    }

    // ------------------------------------------------------------------ 配对 ----

    @Test
    fun `同名一对一时按置信度择优（原曲配原曲 而不是配伴奏）`() {
        val anchor = listOf(track(ne, 1, "你过得好吗", listOf("薛之谦"), 240_000))
        val others = listOf(
            track(qq, 10, "你过得好吗(伴奏)", listOf("薛之谦"), 240_000),
            track(qq, 11, "你过得好吗", listOf("薛之谦"), 240_000),
        )
        val pairs = CrossSourceMatcher.pairTracks(anchor, others)
        assertEquals(1, pairs.size)
        assertEquals(MatchConfidence.EXACT, pairs[0].verdict.confidence)
        assertEquals(11L, pairs[0].other.key.id)
    }

    @Test
    fun `候选只能用一次（不能两条锚点抢同一个候选）`() {
        val anchor = listOf(
            track(ne, 1, "晴天", listOf("周杰伦"), 269_000),
            track(ne, 2, "晴天", listOf("周杰伦"), 269_000),
        )
        val others = listOf(track(qq, 10, "晴天", listOf("周杰伦"), 269_000))
        val pairs = CrossSourceMatcher.pairTracks(anchor, others)
        assertEquals(1, pairs.size)
    }

    @Test
    fun `低于阈值的候选干脆不配对（宁可没有对应 也不要错的对应）`() {
        val anchor = listOf(track(ne, 1, "晴天", listOf("周杰伦"), 269_000))
        val others = listOf(track(qq, 10, "晴天", listOf("别人"), 269_000))
        val pairs = CrossSourceMatcher.pairTracks(anchor, others)
        // 配对结果仍然返回（UI 要能显示「我们试过了」），但 mergeable 为 false。
        assertEquals(1, pairs.size)
        assertFalse(pairs[0].verdict.mergeable)
    }

    // ================================================================== 排序 ==================================================================

    @Test
    fun `可用性排序 可播放 大于 未知 大于 需会员 大于 无版权`() {
        assertEquals(0, CrossSourceMatcher.availabilityOrder(TrackAvailability.PLAYABLE))
        assertEquals(1, CrossSourceMatcher.availabilityOrder(TrackAvailability.UNKNOWN))
        assertEquals(2, CrossSourceMatcher.availabilityOrder(TrackAvailability.MEMBER_ONLY))
        assertEquals(3, CrossSourceMatcher.availabilityOrder(TrackAvailability.NO_COPYRIGHT))
    }

    @Test
    fun `未知排在受限项之前（不知道不等于不能播）`() {
        assertTrue(
            CrossSourceMatcher.availabilityOrder(TrackAvailability.UNKNOWN) <
                CrossSourceMatcher.availabilityOrder(TrackAvailability.MEMBER_ONLY),
        )
        assertTrue(
            CrossSourceMatcher.availabilityOrder(TrackAvailability.UNKNOWN) <
                CrossSourceMatcher.availabilityOrder(TrackAvailability.NO_COPYRIGHT),
        )
    }

    @Test
    fun `排序是稳定的 同档保持接口原顺序`() {
        val items = listOf("a" to TrackAvailability.UNKNOWN, "b" to TrackAvailability.PLAYABLE,
            "c" to TrackAvailability.MEMBER_ONLY, "d" to TrackAvailability.PLAYABLE,
            "e" to TrackAvailability.NO_COPYRIGHT)
        val ranked = CrossSourceMatcher.rankByAvailability(items) { it.second }.map { it.first }
        assertEquals(listOf("b", "d", "a", "c", "e"), ranked)
    }

    @Test
    fun `排序不改动输入列表（纯函数）`() {
        val items = listOf("a" to TrackAvailability.NO_COPYRIGHT, "b" to TrackAvailability.PLAYABLE)
        CrossSourceMatcher.rankByAvailability(items) { it.second }
        assertEquals(listOf("a", "b"), items.map { it.first })
    }

    // ------------------------------------------------------------------ 选源 ----

    @Test
    fun `默认选源 选可播放的那一源（特性 C 的验收点）`() {
        val versions = listOf(
            ne to TrackAvailability.NO_COPYRIGHT,
            qq to TrackAvailability.PLAYABLE,
        )
        val picked = CrossSourceMatcher.pickPlayable(versions, { it.first }, { it.second })
        assertEquals(qq, picked?.first)
    }

    @Test
    fun `默认选源 两源都可播时按用户偏好`() {
        val versions = listOf(
            ne to TrackAvailability.PLAYABLE,
            qq to TrackAvailability.PLAYABLE,
        )
        assertEquals(
            ne,
            CrossSourceMatcher.pickPlayable(versions, { it.first }, { it.second }, preferred = ne)?.first,
        )
        assertEquals(
            qq,
            CrossSourceMatcher.pickPlayable(versions, { it.first }, { it.second }, preferred = qq)?.first,
        )
    }

    @Test
    fun `默认选源 都不可播时回落到原顺序第一条 而不是空`() {
        val versions = listOf(
            ne to TrackAvailability.NO_COPYRIGHT,
            qq to TrackAvailability.MEMBER_ONLY,
        )
        assertEquals(ne, CrossSourceMatcher.pickPlayable(versions, { it.first }, { it.second })?.first)
    }

    @Test
    fun `只有确证可播才允许说「已默认选中有版权的音源」（真机抓到的回显虚高）`() {
        // 设备实测反例：Adele《Strangers By Nature》两源都是「需会员」，
        // 而单曲页仍写着「已默认选中有版权的音源」⇒ 违反铁律 7。修法是把
        // 「排序回退」与「能力断言」分开：默认行可以不等于可播放行。
        assertTrue(CrossSourceMatcher.isConfirmedPlayable(TrackAvailability.PLAYABLE))
        assertFalse(CrossSourceMatcher.isConfirmedPlayable(TrackAvailability.MEMBER_ONLY))
        assertFalse(CrossSourceMatcher.isConfirmedPlayable(TrackAvailability.NO_COPYRIGHT))
        // ★ UNKNOWN 不算确证：探测失败与「能播」是两件事。
        assertFalse(CrossSourceMatcher.isConfirmedPlayable(TrackAvailability.UNKNOWN))
    }

    @Test
    fun `没有任何可播版本时 默认行是排序回退而不是能力断言`() {
        val versions = listOf(
            ne to TrackAvailability.MEMBER_ONLY,
            qq to TrackAvailability.MEMBER_ONLY,
        )
        val picked = CrossSourceMatcher.pickPlayable(versions, { it.first }, { it.second })
        assertNotNull("仍然要挑一行作为默认（用户点了就能试）", picked)
        assertFalse(
            "但这一行**不能**被说成「有版权的音源」",
            CrossSourceMatcher.isConfirmedPlayable(picked!!.second),
        )
    }

    @Test
    fun `默认选源 列表为空时返回 null`() {
        assertNull(CrossSourceMatcher.pickPlayable(emptyList<Pair<MusicSource, TrackAvailability>>(),
            { it.first }, { it.second }))
    }

    // ================================================================== 模型 ==================================================================

    @Test
    fun `MergedArtist 只在配上别名时才算双源`() {
        val primary = ArtistKey(ne, "6452", "周杰伦")
        val single = MergedArtist(primary, emptyList(), "周杰伦", MatchConfidence.LOW)
        assertFalse(single.isDual)
        assertNull(single.keyOf(qq))

        val paired = MergedArtist(primary, listOf(ArtistKey(qq, "0025NhlN2yWrP4", "周杰伦")),
            "周杰伦", MatchConfidence.EXACT)
        assertTrue(paired.isDual)
        assertEquals("0025NhlN2yWrP4", paired.keyOf(qq)?.id)
    }

    @Test
    fun `ArtistKey 与 AlbumKey 的相等只看 音源 加 id（名字可以改 id 不会）`() {
        assertEquals(ArtistKey(ne, "6452", "周杰伦"), ArtistKey(ne, "6452", "周杰倫"))
        assertFalse(ArtistKey(ne, "6452", "周杰伦") == ArtistKey(qq, "6452", "周杰伦"))
        assertEquals(AlbumKey(ne, "18905", "叶惠美"), AlbumKey(ne, "18905", "叶惠美（Deluxe）"))
    }

    @Test
    fun `MergedTrack 复用 TrackKey 不另起一套身份`() {
        val merged = MergedTrack(
            primary = TrackKey(ne, 186016),
            aliases = listOf(TrackKey(qq, 97773, "0039MnYb0qxYhV", "003Qui1q2u1Zho")),
            confidence = MatchConfidence.EXACT,
        )
        assertTrue(merged.isDual)
        assertEquals("qqmusic:97773", merged.keyOf(qq)?.tag)
        assertEquals(2, merged.allKeys.size)
    }

    @Test
    fun `SourceFilter 的三种口径与循环切换`() {
        assertTrue(SourceFilter.BOTH.accepts(ne) && SourceFilter.BOTH.accepts(qq))
        assertTrue(SourceFilter.NETEASE_ONLY.accepts(ne))
        assertFalse(SourceFilter.NETEASE_ONLY.accepts(qq))
        // 循环顺序 = 枚举声明顺序：双源 → 只看 ncm → 只看 QQ → 双源。
        assertEquals(SourceFilter.NETEASE_ONLY, SourceFilter.BOTH.next())
        assertEquals(SourceFilter.QQMUSIC_ONLY, SourceFilter.NETEASE_ONLY.next())
        assertEquals(SourceFilter.BOTH, SourceFilter.QQMUSIC_ONLY.next())
    }

    @Test
    fun `MatchConfidence 的合并阈值只定义在 mergeable 上`() {
        assertTrue(MatchConfidence.EXACT.mergeable)
        assertTrue(MatchConfidence.HIGH.mergeable)
        assertTrue(MatchConfidence.MEDIUM.mergeable)
        assertFalse(MatchConfidence.LOW.mergeable)
        assertFalse(MatchConfidence.NONE.mergeable)
    }
}
