/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v4.1.12 · 艺人页单曲取全：分页取 id / 按热门顺序摆回 的回归单测（**纯逻辑，无网络**）。
 */

package com.takahashirinta.ncrust.crosssource

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.source.MusicSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户报的是「歌手详情界面里面单曲默认还是只有三十首」。
 *
 * 取数本身（`PlaylistApi.getArtistSongIdsOrNull`）直连 Retrofit，JVM 单测里没法注入；
 * 所以把**「取多少、翻几页、什么时候停、怎么摆回顺序」**抽成纯逻辑钉在这里：
 * 只要 [CatalogAggregator.ARTIST_SONGS_PAGE] 还是 30 级别的数字，或者分页把上限写回一页，
 * 这些用例就会红，而不是等到真机上再被人发现「还是 30 首」。
 */
class ArtistSongsPagingTest {

    private fun neSong(id: Long, name: String) = SongItem(
        id = id,
        name = name,
        artists = listOf(ArtistItem(id = null, name = "周杰伦")),
        album = null,
        duration = 269_000L,
        source = MusicSource.NETEASE.key,
    )

    // =========================================================== 取多少 / 翻几页 ===========================================================

    @Test
    fun `单页请求量至少 100 首 且两页能拿到 200 首`() = runBlocking {
        // 改回 30 首口径会在这里红：两个断言分别是「一页要多少」与「一共能拿多少」。
        assertTrue(
            "一页至少 100 首，实际 ${CatalogAggregator.ARTIST_SONGS_PAGE}",
            CatalogAggregator.ARTIST_SONGS_PAGE >= 100,
        )
        assertEquals("最多两页（再多就不是「一进页面就渲染」的成本了）", 2, CatalogAggregator.ARTIST_SONGS_MAX_PAGES)

        val requests = ArrayList<Pair<Int, Int>>()
        val ids = CatalogAggregator.collectArtistSongIds { limit, offset ->
            requests += limit to offset
            // 服务端语义：从 offset 起给满 limit 条（实测 200 首的艺人在 offset=200 仍是满页）。
            (0 until limit).map { 1_000L + offset + it }
        }

        val page = CatalogAggregator.ARTIST_SONGS_PAGE
        assertEquals("两页各请求一次", listOf(page to 0, page to page), requests)
        assertEquals("周杰伦这类艺人不能再被截断到 30 首", page * 2, ids.size)
        assertEquals("顺序 = 服务端热门顺序（第一页第一条在最前）", 1_000L, ids.first())
        assertEquals("第二页接在第一页之后", 1_000L + page, ids[page])
        assertEquals("id 不重复", page * 2, ids.toSet().size)
    }

    @Test
    fun `短页即到底 不再发下一页`() = runBlocking {
        var calls = 0
        // 把页数上限放到 5：停在第 2 次必须是**因为短页**，而不是因为翻满了 2 页。
        val ids = CatalogAggregator.collectArtistSongIds(maxPages = 5) { limit, offset ->
            calls++
            if (offset == 0) (0 until limit).map { 500L + it } else (0 until 40).map { 5_000L + it }
        }
        assertEquals("第一页满、第二页只有 40 条 ⇒ 到底，不再发第三次", 2, calls)
        assertEquals(CatalogAggregator.ARTIST_SONGS_PAGE + 40, ids.size)
    }

    @Test
    fun `首请求失败时保留空结果 不抛异常`() = runBlocking {
        val ids = CatalogAggregator.collectArtistSongIds { _, _ -> null }
        assertTrue("失败 ⇒ 空列表（调用方据此回落到按名字搜索）", ids.isEmpty())
    }

    @Test
    fun `第二页失败时保留第一页的结果`() = runBlocking {
        val ids = CatalogAggregator.collectArtistSongIds { limit, offset ->
            if (offset == 0) (0 until limit).map { 7_000L + it } else null
        }
        assertEquals(
            "第一页整页照用，不让一页失败把整页干掉",
            CatalogAggregator.ARTIST_SONGS_PAGE,
            ids.size,
        )
    }

    @Test
    fun `整页重复（分页回卷）会停 不会把同一页翻满两页`() = runBlocking {
        var calls = 0
        val ids = CatalogAggregator.collectArtistSongIds(maxPages = 5) { limit, _ ->
            calls++
            // 服务端「回卷」：第二次仍然回第一页那一批 id。
            (0 until limit).map { 42L + it }
        }
        assertEquals("第二页整页重复 ⇒ 立刻停，不会翻满 5 页", 2, calls)
        assertEquals("重复的 id 只保留一份", CatalogAggregator.ARTIST_SONGS_PAGE, ids.size)
    }

    @Test
    fun `空页即止 不再发后续请求`() = runBlocking {
        var calls = 0
        val ids = CatalogAggregator.collectArtistSongIds { _, _ ->
            calls++
            emptyList()
        }
        assertEquals(1, calls)
        assertTrue(ids.isEmpty())
    }

    // =========================================================== 顺序摆回 / 去重 ===========================================================

    @Test
    fun `按 id 摆回热门顺序 即使详情返回顺序被打乱`() {
        val ids = listOf(3L, 1L, 2L)
        val songs = listOf(neSong(1L, "A"), neSong(2L, "B"), neSong(3L, "C"))
        assertEquals(listOf(3L, 1L, 2L), CatalogAggregator.orderSongsByIds(ids, songs).map { it.id })
    }

    @Test
    fun `详情多出来的曲目被丢掉 缺失的曲目不会补空行`() {
        val ids = listOf(1L, 2L, 3L)
        val songs = listOf(neSong(1L, "A"), neSong(9L, "多出来的"), neSong(2L, "B"))
        val ordered = CatalogAggregator.orderSongsByIds(ids, songs)
        assertEquals("只保留请求里出现过的 id，且按请求顺序", listOf(1L, 2L), ordered.map { it.id })
    }

    @Test
    fun `重复 id 只保留第一条`() {
        val ids = listOf(1L, 1L, 2L)
        val songs = listOf(neSong(1L, "A"), neSong(2L, "B"))
        assertEquals(listOf(1L, 2L), CatalogAggregator.orderSongsByIds(ids, songs).map { it.id })
    }

    @Test
    fun `空输入不炸`() {
        assertTrue(CatalogAggregator.orderSongsByIds(emptyList(), listOf(neSong(1L, "A"))).isEmpty())
        assertTrue(CatalogAggregator.orderSongsByIds(listOf(1L), emptyList()).isEmpty())
    }
}
