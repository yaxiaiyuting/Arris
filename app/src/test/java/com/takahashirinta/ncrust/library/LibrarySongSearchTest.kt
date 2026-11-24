package com.takahashirinta.ncrust.library

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 库页「单曲」tab 的本地搜索：**空查询原样返回、大小写不敏感、多词 AND、只搜三段可见文本**。
 *
 * 这些用例钉的是 UI 判据的两处因果，而不是「能不能搜到字」：
 *  - 空查询必须返回**原列表**（同一个对象）—— UI 要用「未过滤列表是否为空」区分
 *    「库里没有歌」与「这次没搜到」两档空态，两份判据一旦分家就会互相抢文案；
 *  - 多词必须是 AND —— OR 会让「周杰伦 晴天」的结果比「晴天」更多，搜索框越长越不像搜索。
 */
class LibrarySongSearchTest {

    private fun song(
        id: Long,
        title: String,
        artists: List<String> = emptyList(),
        album: String? = null,
    ): SongItem = SongItem(
        id = id,
        name = title,
        artists = if (artists.isEmpty()) null else artists.map { ArtistItem(name = it) },
        album = album?.let { AlbumItem(name = it, picUrl = null) },
        duration = null,
    )

    private val qingTian = song(1L, "晴天", artists = listOf("周杰伦"), album = "叶惠美")
    private val fuShi = song(2L, "富士山下", artists = listOf("陈奕迅"), album = "What's Going On...?")
    private val loveStory = song(3L, "Love Story", artists = listOf("Taylor Swift"), album = "Fearless")
    private val songs = listOf(qingTian, fuShi, loveStory)

    // ---------------------------------------------------------------- 空查询

    @Test
    fun `空查询原样返回整个列表（同一个对象，UI 用它区分两档空态）`() {
        assertSame(songs, LibrarySongSearch.match(songs, ""))
    }

    @Test
    fun `只有空白的查询不算关键词：空格、制表符、换行、全角空格`() {
        listOf("   ", "\t\n", "\u3000\u3000", " \u3000\t").forEach { query ->
            assertEquals(
                "查询 <$query> 不应过滤任何东西",
                songs,
                LibrarySongSearch.match(songs, query),
            )
        }
    }

    // ---------------------------------------------------------------- 单段命中

    @Test
    fun `歌名命中且不区分大小写`() {
        assertEquals(listOf(loveStory), LibrarySongSearch.match(songs, "love"))
        assertEquals(listOf(loveStory), LibrarySongSearch.match(songs, "LOVE"))
        assertEquals(listOf(loveStory), LibrarySongSearch.match(songs, "sToRy"))
        assertEquals(listOf(qingTian), LibrarySongSearch.match(songs, "晴天"))
    }

    @Test
    fun `艺人命中（中文与拉丁文各一例，后者大小写不敏感）`() {
        assertEquals(listOf(qingTian), LibrarySongSearch.match(songs, "周杰伦"))
        assertEquals(listOf(loveStory), LibrarySongSearch.match(songs, "taylor"))
        assertEquals(listOf(loveStory), LibrarySongSearch.match(songs, "TAYLOR SWIFT"))
    }

    @Test
    fun `专辑命中（含空格与标点的专辑名按子串匹配）`() {
        assertEquals(listOf(qingTian), LibrarySongSearch.match(songs, "叶惠美"))
        assertEquals(listOf(fuShi), LibrarySongSearch.match(songs, "going on"))
        assertEquals(listOf(loveStory), LibrarySongSearch.match(songs, "FEARLESS"))
    }

    // ---------------------------------------------------------------- 多词 AND

    @Test
    fun `多个词是 AND：每个词各自在某一段里命中才算`() {
        // 「周杰伦」在艺人段、「晴天」在歌名段 —— 两个词分别命中不同字段，整首保留。
        assertEquals(listOf(qingTian), LibrarySongSearch.match(songs, "周杰伦 晴天"))
        // AND 与词序无关。
        assertEquals(listOf(qingTian), LibrarySongSearch.match(songs, "晴天 周杰伦"))
        // 只要有一个词完全没命中，整首就不进结果（OR 的话这里会返回 qingTian）。
        assertTrue(
            "「周杰伦 富士山下」跨两首歌，AND 语义下应当没有任何一首同时命中",
            LibrarySongSearch.match(songs, "周杰伦 富士山下").isEmpty(),
        )
    }

    @Test
    fun `词间多个空格不会产生空 token（空 token 会让 AND 恒真）`() {
        assertEquals(listOf(qingTian), LibrarySongSearch.match(songs, "周杰伦    晴天"))
        assertTrue(LibrarySongSearch.match(songs, "周杰伦    富士山下").isEmpty())
    }

    // ---------------------------------------------------------------- 空结果

    @Test
    fun `没有任何命中时返回空列表，而不是全部`() {
        assertTrue(LibrarySongSearch.match(songs, "不存在的歌名").isEmpty())
        assertTrue(LibrarySongSearch.match(songs, "周杰伦 不存在").isEmpty())
    }

    @Test
    fun `空库永远返回空`() {
        assertTrue(LibrarySongSearch.match(emptyList(), "晴天").isEmpty())
    }

    @Test
    fun `命中项保持原顺序（搜索不该打乱用户熟悉的排列）`() {
        val all = listOf(
            song(11L, "夜曲", artists = listOf("周杰伦")),
            song(12L, "稻香", artists = listOf("周杰伦")),
            song(13L, "七里香", artists = listOf("周杰伦")),
        )
        assertEquals(listOf(11L, 12L, 13L), LibrarySongSearch.match(all, "周杰伦").map { it.id })
    }

    @Test
    fun `字段为空时不崩：models 里 artists 与 album 都可空`() {
        val bare = song(21L, "光年之外")
        assertEquals(listOf(bare), LibrarySongSearch.match(listOf(bare), "光年"))
        assertTrue(LibrarySongSearch.match(listOf(bare), "邓紫棋").isEmpty())
    }

    // ---------------------------------------------------------------- keyword

    @Test
    fun `keyword 去首尾空白并把连续空白压成一个空格`() {
        assertEquals("周杰伦 晴天", LibrarySongSearch.keyword("  周杰伦 \t\n 晴天  "))
        assertEquals("周杰伦 晴天", LibrarySongSearch.keyword("\u3000周杰伦\u3000\u3000晴天\u3000"))
        assertEquals("", LibrarySongSearch.keyword("   "))
        assertEquals("", LibrarySongSearch.keyword(""))
    }
}
