/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.11：搜索分页纯逻辑的单测。
 */

package com.takahashirinta.ncrust.search

import com.takahashirinta.ncrust.network.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.4.11：搜索分页的纯逻辑单测。
 *
 * ## 这一层守的是什么
 *
 * 用户报障：「每次拉歌曲只拉三十首也太少了吧，而且下拉还不刷新新的歌」。
 * 修法把搜索结果从「只有一页」改成可翻页，而翻页的三件事**写错了都不会崩**：
 * `offset` 算错 → 第二页拿到第一页的内容（看起来像「点了没反应」）；
 * 不去重 → 同一条歌出现两次、Compose 的 `items(key = { it.id })` 因 key 重复抛异常；
 * `hasMore` 判反 → 最后一页永远取不到（缺陷），或者按钮常驻（点了没反应）。
 *
 * 所以三条判据都在这里逐条钉住，而不是留给真机去发现。
 */
class SearchPagingTest {

    private fun song(id: Long, source: String = "netease"): SongItem =
        SongItem(
            id = id,
            name = "song$id",
            artists = null,
            album = null,
            duration = null,
            source = source,
        )

    // ---------------- offset ----------------

    @Test
    fun `页码到 offset 的换算是 页减一 乘 每页`() {
        assertEquals(0, SearchPaging.offsetOf(1))
        assertEquals(30, SearchPaging.offsetOf(2))
        assertEquals(60, SearchPaging.offsetOf(3))
        assertEquals(270, SearchPaging.offsetOf(10))
    }

    /** 页码非法时按第一页处理：**宁可多取一次第一页，也不要发一个负 offset**。 */
    @Test
    fun `非法页码回落成第一页而不是发负 offset`() {
        assertEquals(0, SearchPaging.offsetOf(0))
        assertEquals(0, SearchPaging.offsetOf(-5))
        // 每页条数非法时用默认值，绝不出现「除以 0」或负页宽
        assertEquals(30, SearchPaging.offsetOf(2, pageSize = 0))
        assertEquals(30, SearchPaging.offsetOf(2, pageSize = -10))
    }

    @Test
    fun `自定义页宽同样成立`() {
        assertEquals(0, SearchPaging.offsetOf(1, pageSize = 50))
        assertEquals(50, SearchPaging.offsetOf(2, pageSize = 50))
    }

    // ---------------- hasMore ----------------

    /**
     * 判据是「这一页被填满」。
     *
     * ⚠️ 反向判（「不满一页才算有下一页」）会让**最后一整页永远取不到** —— 那是缺陷；
     * 而本判据的代价只是最后一页多给一次点击。这条用例把这个方向钉住。
     */
    @Test
    fun `填满一页判定为还有下一页`() {
        assertTrue(SearchPaging.hasMore(30))
        assertTrue("超过一页（服务端偶尔多给）也算还有", SearchPaging.hasMore(31))
        assertFalse("差一条都不算满", SearchPaging.hasMore(29))
        assertFalse("空页 = 到底了", SearchPaging.hasMore(0))
    }

    /** 三条腿取「或」：只要有一条还有货，「加载更多」就还有意义。 */
    @Test
    fun `三条腿里任意一条填满就算还有下一页`() {
        assertTrue(SearchPaging.hasMoreAny(30, 0, 0))
        assertTrue(SearchPaging.hasMoreAny(0, 30, 0))
        assertTrue(SearchPaging.hasMoreAny(0, 0, 30))
        assertTrue(SearchPaging.hasMoreAny(12, 30))
        assertFalse(SearchPaging.hasMoreAny(29, 12, 7))
        assertFalse(SearchPaging.hasMoreAny(0, 0, 0))
        assertFalse("一条腿都没有时也是到底", SearchPaging.hasMoreAny())
    }

    // ---------------- 追加与去重 ----------------

    @Test
    fun `追加保留先出现的那一条`() {
        val first = listOf(song(1), song(2))
        val second = listOf(song(2), song(3)) // 2 是分页边界重发的
        val merged = SearchPaging.appendDistinct(first, second)
        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
    }

    /**
     * 跨源同号**不能**被去重掉。
     *
     * ncm 与 QQ 的数字 id 会撞号（两边都有 `id=1` 这种），而它们是**两首不同的歌**。
     * 判据是身份（`source:id`）而不是裸 id —— 本仓库在 v2.5.5 的跨源上报闸门那里
     * 已经因为「拿裸 id 当身份」踩过一次（QQ 的合成 id 与 ncm 的正数 id 撞在一起）。
     */
    @Test
    fun `跨源同号不会被误去重`() {
        val ne = listOf(song(1, "netease"))
        val qq = listOf(song(1, "qqmusic"))
        val merged = SearchPaging.appendDistinct(ne, qq)
        assertEquals("两个音源的 id=1 是两首歌", 2, merged.size)
    }

    @Test
    fun `重复取同一页不会让列表变长`() {
        val page = listOf(song(1), song(2), song(3))
        var acc = SearchPaging.appendDistinct(emptyList(), page)
        assertEquals(3, acc.size)
        // 再取一次同一页（服务端重发）⇒ 长度不变
        acc = SearchPaging.appendDistinct(acc, page)
        assertEquals(3, acc.size)
    }

    @Test
    fun `按身份去重不追加`() {
        val list = listOf(song(1), song(1), song(2, "qqmusic"), song(2, "qqmusic"))
        assertEquals(2, SearchPaging.distinct(list).size)
    }

    /** 每页条数是 30 —— 它是两个音源**共用**的口径（QQ 的页码与页宽耦合）。 */
    @Test
    fun `默认页宽是 30`() {
        assertEquals(30, SearchPaging.PAGE_SIZE)
        assertEquals(SearchPaging.PAGE_SIZE, SearchPaging.offsetOf(2))
    }
}
