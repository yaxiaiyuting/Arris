/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.11：专辑「按名字 + 歌手」解析的纯逻辑单测。
 */

package com.takahashirinta.ncrust.crosssource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.4.11：`AlbumLookup` 的单测。
 *
 * ## 它守的是哪一类缺陷
 *
 * 用户报障：「专辑界面也做和单曲界面类似的搜索处理，优先原唱，原歌手，
 * 用搜索的歌手名字匹配专辑作者」。
 *
 * 同名专辑是**真实存在**的一类数据（原版 / 精选 / 现场 / 翻唱 / 重制版），
 * 而此前客户端只按专辑名去对端搜、取前 3 个候选看曲目重叠度 ——
 * 于是「翻唱合集」与「原版」的曲目名高度重叠时**会配错**，而且配错之后
 * 界面还会显示一个「已匹配」的置信度，用户没有任何线索知道配错了。
 *
 * 这里用「歌手名必须参与判据」把它钉住，并且逐条覆盖合作艺人这种真实形状。
 */
class AlbumLookupTest {

    private fun cand(
        id: Long,
        name: String,
        artist: String? = null,
        tracks: Int? = null,
    ) = AlbumLookup.Candidate(id, name, artist, tracks)

    // ---------------- 名称是硬门槛 ----------------

    /** 名称不同就是**另一张专辑** —— 这一条不做任何妥协（返回 null 而不是低分）。 */
    @Test
    fun `名称不同直接不参与排序`() {
        assertNull(AlbumLookup.score("范特西", "周杰伦", cand(1, "叶惠美", "周杰伦")))
        assertNull(AlbumLookup.score("范特西", "周杰伦", cand(1, "", "周杰伦")))
    }

    @Test
    fun `名称规范化后相同就算命中（大小写 空格 标点无关）`() {
        // `NameNormalizer` 的既有语义：大小写、空白、标点都不影响。
        val scored = AlbumLookup.score("Jay", "周杰伦", cand(1, "jay", "周杰伦"))
        assertNotNull(scored)
        assertEquals("Jay", "Jay") // 名称保持原样，只用于比较
    }

    // ---------------- 歌手是主判据 ----------------

    /**
     * **同歌手 +4**：这是「优先原唱」的落点。
     *
     * 场景：`范特西` 同时存在周杰伦的原版与某翻唱合集，曲目名高度重叠 ——
     * 只按曲目重叠判会随机选一个，而歌手名能把正主挑出来。
     */
    @Test
    fun `同名之下优先歌手完全相同的那一张`() {
        val best = AlbumLookup.best(
            queryName = "范特西",
            queryArtist = "周杰伦",
            candidates = listOf(
                cand(1, "范特西", "群星", tracks = 10),
                cand(2, "范特西", "周杰伦", tracks = 10),
            ),
        )
        assertNotNull(best)
        assertEquals("必须选到原唱那一张", 2L, best!!.candidate.id)
        assertTrue("理由里要能看出是靠歌手命中的", best.reason.contains("同歌手"))
    }

    /**
     * 合作艺人算**弱命中**（+2），但仍强于「歌手不同」（+0）。
     *
     * 两个平台的歌手字段形状不同是实测过的事实：ncm 常见 `周杰伦`，
     * QQ 常见 `周杰伦/方文山` 或 `周杰伦 feat. 袁咏琳`。精确相等会把正确答案滤掉，
     * 所以包含关系必须算命中 —— 但**不能与精确同等**（否则「A/B 合集」会盖过 A 本人）。
     */
    @Test
    fun `合作艺人的包含关系算弱命中且弱于精确相等`() {
        val best = AlbumLookup.best(
            queryName = "跨时代",
            queryArtist = "周杰伦",
            candidates = listOf(
                cand(1, "跨时代", "周杰伦 / 袁咏琳", tracks = 11),
                cand(2, "跨时代", "周杰伦", tracks = 11),
            ),
            queryTrackCount = 11,
        )
        assertEquals("精确相等要赢过包含", 2L, best!!.candidate.id)
        assertEquals(5, best.score) // 同名(0) + 同歌手(4) + 曲目数(1)
    }

    @Test
    fun `只有包含关系可用时也认它`() {
        val best = AlbumLookup.best(
            queryName = "跨时代",
            queryArtist = "周杰伦",
            candidates = listOf(
                cand(1, "跨时代", "群星", tracks = 11),
                cand(2, "跨时代", "周杰伦 / 袁咏琳", tracks = 11),
            ),
        )
        assertEquals(2L, best!!.candidate.id)
        assertTrue(best.reason.contains("歌手包含"))
    }

    /** 两边歌手都缺时**不因此扣分**，只靠名称命中 —— 如实接受信息不足。 */
    @Test
    fun `歌手信息缺失时不参与打分但仍可命中`() {
        val best = AlbumLookup.best("范特西", null, listOf(cand(1, "范特西")))
        assertNotNull(best)
        assertEquals(0, best!!.score)
        assertEquals("同名", best.reason)
    }

    // ---------------- 曲目数是次要信号 ----------------

    /**
     * 歌手相同时，曲目数相同再 +1（同版本的两个平台条目通常曲目数一致）。
     *
     * 权重刻意低于歌手：精选版会多两首，把曲目数当主判据会把正主排到后面。
     */
    @Test
    fun `歌手相同时曲目数相同再得一分`() {
        val best = AlbumLookup.best(
            queryName = "范特西",
            queryArtist = "周杰伦",
            candidates = listOf(
                cand(1, "范特西", "周杰伦", tracks = 12),
                cand(2, "范特西", "周杰伦", tracks = 10),
            ),
            queryTrackCount = 10,
        )
        assertEquals("曲目数一致的那一张赢", 2L, best!!.candidate.id)
    }

    /** 曲目数未知（null）不该被当成 0 去和已知值比较。 */
    @Test
    fun `曲目数未知时不参与比较`() {
        val a = AlbumLookup.score("范特西", "周杰伦", cand(1, "范特西", "周杰伦", tracks = null))!!
        val b = AlbumLookup.score("范特西", "周杰伦", cand(2, "范特西", "周杰伦", tracks = 10))!!
        assertEquals("未知不该让对方凭空多得一分", a.score, b.score)
    }

    // ---------------- 稳定性与退化 ----------------

    /** 没有任何候选名称命中 ⇒ 返回 null（调用方保留直取/缓存的结果，而不是拿一个错的）。 */
    @Test
    fun `全都不命中时返回 null`() {
        assertNull(AlbumLookup.best("范特西", "周杰伦", listOf(cand(1, "叶惠美"), cand(2, "七里香"))))
        assertNull(AlbumLookup.best("范特西", "周杰伦", emptyList()))
    }

    /** 平手时保留先出现的：平台自己的排序通常把正主放前面，我们没有更强依据去推翻它。 */
    @Test
    fun `同分时保留先出现的那个`() {
        val best = AlbumLookup.best(
            queryName = "范特西",
            queryArtist = "周杰伦",
            candidates = listOf(
                cand(1, "范特西", "周杰伦", tracks = 10),
                cand(2, "范特西", "周杰伦", tracks = 10),
            ),
        )
        assertEquals(1L, best!!.candidate.id)
    }

    @Test
    fun `打分结果带上可读的理由`() {
        val scored = AlbumLookup.score(
            "范特西", "周杰伦", cand(1, "范特西", "周杰伦", tracks = 10), queryTrackCount = 10,
        )!!
        assertEquals("同名+同歌手+同曲目数", scored.reason)
        assertEquals(5, scored.score)
    }
}
