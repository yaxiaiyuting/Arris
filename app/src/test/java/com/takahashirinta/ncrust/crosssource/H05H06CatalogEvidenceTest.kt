/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 2026-10-27 审查 H05 / H06 / E03 的回归：**候选证据不得被注入**、
 * **QQ 主源也要取网易侧**、**封面归属必须与专辑源一致**。全部纯逻辑，无网络。
 */

package com.takahashirinta.ncrust.crosssource

import com.takahashirinta.ncrust.qq.QqAlbum
import com.takahashirinta.ncrust.source.MusicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H05 / H06 / E03 三条审查项在**纯逻辑层**的可执行判据。
 *
 * 三条的共同点：真实缺陷都在 `CatalogAggregator` 的 `suspend` 编排里（要网络），
 * 而根因都可以下沉成「给定输入该得到什么」的决定 —— 所以这里钉的是**决定**，
 * 而不是"某次线上响应的样子"：
 *
 * | 项 | 判据 |
 * |---|---|
 * | H05 | 候选专辑的艺人证据只来自候选；锚点歌手不得出现在候选的证据里 |
 * | H05 | 「同名不同歌手」的判据是三值的 `ArtistVerdict`，不是 `score() == null` |
 * | H06 | 两侧取数计划在**任一主源**下都包含对端（QQ 主源也要取网易侧） |
 * | E03 | 网易专辑的封面保留接口给的 `picUrl`，**绝不**被拼成 QQ 图片模板 |
 */
class H05H06CatalogEvidenceTest {

    private fun album(
        id: Long,
        mid: String,
        name: String,
        singer: String? = null,
        tracks: Int? = null,
        coverUrl: String? = null,
    ) = QqAlbum(
        id = id,
        mid = mid,
        name = name,
        trackCount = tracks,
        singerName = singer,
        coverUrl = coverUrl,
    )

    // ------------------------------------------------------------------ H05

    /**
     * 报告原文的复现条件：Alpha 专辑（歌手 Alpha 艺人）匹配到 Beta 的同名专辑，
     * 曲目名也相同 ⇒ 旧实现给 HIGH 并合并。
     *
     * 旧实现的成因是**候选的艺名列表里被塞进了锚点歌手**，于是
     * `gradeAlbum` 的 `artistsOverlap(anchor.artistNames, candidate.artistNames)` 必然成立。
     * 这条用例先把它**照原样构造出来**（只用于证明"注入即可通过判据"），
     * 再断言生产构造点 [CatalogAggregator.albumCandidateOf] 不会造出这种候选。
     */
    @Test
    fun `候选专辑的艺人证据只来自候选自己 同名不同歌手不再合并（H05）`() {
        val anchorName = "Alpha"
        val anchorTracks = listOf("t1", "t2", "t3")
        val anchorArtist = "Anchor Artist"

        val anchorCandidate = CrossSourceMatcher.AlbumCandidate(
            key = AlbumKey(MusicSource.NETEASE, "100", anchorName),
            trackNames = anchorTracks,
            trackCount = 3,
            rawName = anchorName,
            artistNames = listOf(anchorArtist),
        )
        val beta = album(200L, "betaMid", anchorName, singer = "Beta Artist", tracks = 3)

        // ① 缺陷的"人为注入"版本：把锚点歌手塞进候选的证据里 —— 判据立刻失真。
        val injected = CrossSourceMatcher.AlbumCandidate(
            key = AlbumKey(MusicSource.QQMUSIC, beta.mid, beta.name),
            trackNames = anchorTracks,
            trackCount = 3,
            rawName = beta.name,
            artistNames = listOfNotNull(beta.singerName, anchorArtist),
        )
        val injectedVerdict = CrossSourceMatcher.gradeAlbum(
            anchorCandidate,
            injected,
            CrossSourceMatcher.trackOverlapRatio(anchorTracks, anchorTracks),
        )
        // 注入之后四条判据全中 ⇒ 直接 EXACT（比报告里写的 HIGH 还高）。
        assertEquals("这就是 H05 的根因：候选证据里混进锚点歌手 ⇒ 同名不同歌手也判 EXACT",
            MatchConfidence.EXACT, injectedVerdict.confidence)
        assertTrue(injectedVerdict.mergeable)

        // ② 生产构造点：候选只带自己的歌手 ⇒ 艺人这条判据不成立，落到 LOW（不合并）。
        val produced = CatalogAggregator.albumCandidateOf(beta, MusicSource.QQMUSIC, anchorTracks)
        assertEquals(listOf("Beta Artist"), produced.artistNames)
        val verdict = CrossSourceMatcher.gradeAlbum(
            anchorCandidate,
            produced,
            CrossSourceMatcher.trackOverlapRatio(anchorTracks, produced.trackNames),
        )
        assertEquals(MatchConfidence.LOW, verdict.confidence)
        assertFalse("同名不同歌手绝不能合并", verdict.mergeable)
    }

    /** 候选没给歌手 ⇒ 是「资料缺失」，落 LOW（不合并），而不是被当成「同歌手」。 */
    @Test
    fun `候选缺少歌手字段时按资料缺失处理 不许当成艺人一致（H05）`() {
        val anchor = CrossSourceMatcher.AlbumCandidate(
            key = AlbumKey(MusicSource.NETEASE, "100", "Same"),
            trackNames = listOf("a", "b"),
            trackCount = 2,
            rawName = "Same",
            artistNames = listOf("Some Artist"),
        )
        val produced = CatalogAggregator.albumCandidateOf(
            album(300L, "mid300", "Same", singer = null, tracks = 2),
            MusicSource.QQMUSIC,
            listOf("a", "b"),
        )
        assertTrue(produced.artistNames.isEmpty())
        val verdict = CrossSourceMatcher.gradeAlbum(anchor, produced, 1.0)
        assertEquals(MatchConfidence.LOW, verdict.confidence)
    }

    /**
     * H05 的第二半：排除闸门必须是三值判据。
     *
     * `AlbumLookup.score(...) == null` 永远为 false（同名就有分），所以旧闸门形同不存在。
     */
    @Test
    fun `同名不同歌手的排除闸门看的是冲突而不是score是否为null（H05）`() {
        // 旧判据：同名 ⇒ score 非 null ⇒ 「不是冲突」—— 这就是闸门失效的机制。
        val scored = AlbumLookup.score(
            queryName = "Alpha",
            queryArtist = "Anchor Artist",
            candidate = AlbumLookup.Candidate(id = 0L, name = "Alpha", artistName = "Beta Artist"),
        )
        assertTrue("同名时 score 必然非 null（旧闸门因此拦不住）", scored != null)

        // 新判据：冲突是显式的一档。
        assertEquals(
            AlbumLookup.ArtistVerdict.CONFLICT,
            AlbumLookup.artistVerdict("Anchor Artist", "Beta Artist"),
        )
        // 信息缺失不能算冲突（否则会凭猜测丢候选）。
        assertEquals(
            AlbumLookup.ArtistVerdict.UNKNOWN,
            AlbumLookup.artistVerdict("Anchor Artist", null),
        )
        assertEquals(AlbumLookup.ArtistVerdict.SAME, AlbumLookup.artistVerdict("A", "a"))
        assertEquals(AlbumLookup.ArtistVerdict.CONTAINS, AlbumLookup.artistVerdict("A / B", "A"))
    }

    /** 打分口径不变：同歌手 +4、包含 +2、缺失/冲突 0 分。 */
    @Test
    fun `歌手判据重构之后打分口径不变（H05 的伴随约束）`() {
        fun scoreOf(candidateArtist: String?): Int = AlbumLookup.score(
            queryName = "Album",
            queryArtist = "Artist",
            candidate = AlbumLookup.Candidate(0L, "Album", candidateArtist),
        )!!.score
        assertEquals(4, scoreOf("Artist"))
        assertEquals(2, scoreOf("Artist / Guest"))
        assertEquals(0, scoreOf("Someone Else"))
        assertEquals(0, scoreOf(null))
    }

    // ------------------------------------------------------------------ H06

    /**
     * 报告原文：以 QQ 为主源进专辑页时，网易侧曲目整块缺失（alias 有、列表没有）。
     *
     * 取数计划是这条链的纯逻辑部分 —— 旧实现没有计划，取哪几侧由三处 `if (锚点 == ncm)`
     * 拼出来，漏写一处就少一半。
     */
    @Test
    fun `QQ 主源也要取网易侧 专辑取数计划两侧都在（H06）`() {
        val anchor = AlbumKey(MusicSource.QQMUSIC, "qqMid", "Album")
        val counterpart = AlbumKey(MusicSource.NETEASE, "12345", "Album")
        val sides = CatalogAggregator.albumSides(anchor, counterpart)
        assertEquals(2, sides.size)
        assertEquals(anchor, sides[0])
        assertEquals("对端必须按它**自己的** source 与 id 取数（不能拿 QQ mid 去问网易）", counterpart, sides[1])
    }

    @Test
    fun `ncm 主源的专辑取数计划同样两侧都在（H06 对称）`() {
        val anchor = AlbumKey(MusicSource.NETEASE, "12345", "Album")
        val counterpart = AlbumKey(MusicSource.QQMUSIC, "qqMid", "Album")
        assertEquals(listOf(anchor, counterpart), CatalogAggregator.albumSides(anchor, counterpart))
    }

    @Test
    fun `没找到对端时不重复取同一侧（H06）`() {
        val anchor = AlbumKey(MusicSource.QQMUSIC, "qqMid", "Album")
        assertEquals(listOf(anchor), CatalogAggregator.albumSides(anchor, null))
        // 同源同 id 的"对端"是数据错误，不能被当成两侧（否则会发两次同样的请求）。
        assertEquals(listOf(anchor), CatalogAggregator.albumSides(anchor, anchor.copy(name = "别的名字")))
    }

    @Test
    fun `艺人页的取数计划同样两侧都在（H06 的艺人分支）`() {
        val anchor = ArtistKey(MusicSource.QQMUSIC, "qqSingerMid", "Artist")
        val counterpart = ArtistKey(MusicSource.NETEASE, "6452", "Artist")
        val sides = CatalogAggregator.artistSides(anchor, counterpart)
        assertEquals(listOf(anchor, counterpart), sides)
        assertEquals(listOf(anchor), CatalogAggregator.artistSides(anchor, null))
    }

    // ------------------------------------------------------------------ E03

    /** 网易专辑：封面必须是接口给的 `picUrl`，不能变成 QQ 模板。 */
    @Test
    fun `网易专辑的封面保留接口 picUrl 而不是拼成QQ图片地址（E03）`() {
        val neCover = "https://p1.music.126.net/abcdef/109951167000000000.jpg"
        val ne = album(12345L, "12345", "网易专辑", singer = "网易歌手", tracks = 2, coverUrl = neCover)
        val rows = CatalogAggregator.assembleAlbums(
            primary = listOf(ne),
            primarySource = MusicSource.NETEASE,
            secondary = emptyList(),
            secondarySource = MusicSource.QQMUSIC,
        )
        assertEquals(1, rows.size)
        assertEquals(neCover, rows[0].picUrl)
        assertFalse(
            "把网易的十进制专辑 id 填进腾讯模板 = 封面归属错源：${rows[0].picUrl}",
            rows[0].picUrl.orEmpty().contains("y.qq.com"),
        )
    }

    /** 网易专辑**没有**封面字段时返回 null（占位图），绝不猜一个 QQ 地址。 */
    @Test
    fun `网易专辑没有封面就留空 不拼QQ模板（E03）`() {
        val ne = album(12345L, "12345", "网易专辑", singer = "网易歌手", tracks = 2, coverUrl = null)
        val rows = CatalogAggregator.assembleAlbums(
            listOf(ne), MusicSource.NETEASE, emptyList(), MusicSource.QQMUSIC,
        )
        assertNull(rows.single().picUrl)
    }

    /** QQ 专辑仍然按 albumMID 拼模板（既有行为不变），次源独有的那一支也一样。 */
    @Test
    fun `QQ 专辑仍然按 albumMID 拼封面 主列表与次源独有都一致（E03）`() {
        val qqOnly = album(9L, "000MkMni19ClKG", "QQ专辑", singer = "QQ歌手", tracks = 3)
        val rows = CatalogAggregator.assembleAlbums(
            listOf(album(1L, "neAsQq", "同名专辑", singer = "歌手", tracks = 1)),
            MusicSource.NETEASE,
            listOf(qqOnly),
            MusicSource.QQMUSIC,
        )
        assertEquals(2, rows.size)
        assertEquals(
            "https://y.qq.com/music/photo_new/T002R500x500M000000MkMni19ClKG.jpg",
            rows[1].picUrl,
        )
        // 主列表（ncm 侧）没有封面字段 ⇒ null，而不是被 QQ 模板接手。
        assertNull(rows[0].picUrl)
    }
}
