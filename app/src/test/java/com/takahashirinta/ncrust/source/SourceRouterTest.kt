package com.takahashirinta.ncrust.source

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.SongUrlResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 · A：音源路由的纯逻辑单测（注入假 Provider，不发网络请求）。
 *
 * 重点覆盖「路由错了会串台」这件事：同 id 不同音源必须走到各自的 Provider，
 * 缺 songmid 的 QQ 曲目必须**失败**而不是退回 ncm。
 */
class SourceRouterTest {

    private class FakeProvider(
        override val source: MusicSource,
        var result: SongUrlResult? = SongUrlResult("http://x", "lossless"),
        var searchResult: List<SongItem> = emptyList(),
        var throwOnSearch: Boolean = false,
    ) : MusicSourceProvider {
        var urlCalls = 0
        var lastSearchedKeyword: String? = null
        /** v3.4.11：分页透传的直接证据 —— 路由必须把 `page` 原样交给 Provider。 */
        var lastSearchedPage: Int? = null
        override val isLoggedIn: Boolean = true
        override suspend fun searchSongs(
            keyword: String,
            limit: Int,
            page: Int,
            totalOut: MutableMap<String, Int>?,
        ): List<SongItem> {
            lastSearchedKeyword = keyword
            lastSearchedPage = page
            if (throwOnSearch) throw IllegalStateException("boom")
            return searchResult
        }
        override suspend fun resolveUrl(song: SongItem, level: String): SongUrlResult? {
            urlCalls++
            return result
        }
        override suspend fun songDetail(song: SongItem): SongItem? = null
    }

    private val netease = FakeProvider(MusicSource.NETEASE)
    private val qq = FakeProvider(MusicSource.QQMUSIC)

    private fun registerFakes() {
        SourceRouter.register(netease)
        SourceRouter.register(qq)
    }

    @After
    fun restore() {
        // 注册表是全局单例，测完必须把真实现放回去，否则后续用例会用到假实现。
        SourceRouter.register(NeteaseSourceProvider)
    }

    private fun song(
        id: Long,
        source: MusicSource = MusicSource.NETEASE,
        sourceId: String? = null,
    ) = SongItem(
        id = id, name = "歌", artists = null, album = null, duration = null,
        source = if (source == MusicSource.NETEASE) null else source.key,
        sourceId = sourceId,
    )

    @Test
    fun `ncm曲目路由到ncm Provider`() = runBlocking {
        registerFakes()
        val r = SourceRouter.resolveUrl(song(1L), "lossless")
        assertNotNull(r)
        assertEquals(1, netease.urlCalls)
        assertEquals(0, qq.urlCalls)
    }

    @Test
    fun `QQ 曲目路由到 QQ Provider —— 同 id 也不会串到ncm`() = runBlocking {
        registerFakes()
        val r = SourceRouter.resolveUrl(song(1L, MusicSource.QQMUSIC, "mid1"), "lossless")
        assertNotNull(r)
        assertEquals(0, netease.urlCalls)
        assertEquals(1, qq.urlCalls)
    }

    @Test
    fun `QQ 曲目缺 songmid 时不取链也不退回ncm`() = runBlocking {
        registerFakes()
        assertNull(SourceRouter.resolveUrl(song(1L, MusicSource.QQMUSIC, null), "lossless"))
        assertEquals(0, netease.urlCalls)
        assertEquals(0, qq.urlCalls)
    }

    @Test
    fun `未注册的音源返回 null 而不是回落ncm`() = runBlocking {
        // 只注册 ncm：QQ 未接入 / 未登录时就是这种状态。
        SourceRouter.register(netease)
        assertNull(SourceRouter.resolveUrl(song(2L, MusicSource.QQMUSIC, "mid2"), "lossless"))
        assertEquals(0, netease.urlCalls)
    }

    @Test
    fun `Provider 返回 null 时原样向上传递`() = runBlocking {
        registerFakes()
        qq.result = null
        assertNull(SourceRouter.resolveUrl(song(3L, MusicSource.QQMUSIC, "mid3"), "lossless"))
    }

    @Test
    fun `搜索按音源分发`() = runBlocking {
        registerFakes()
        netease.searchResult = listOf(song(10L))
        qq.searchResult = listOf(song(20L, MusicSource.QQMUSIC, "m"))
        val a = SourceRouter.searchSongs(MusicSource.NETEASE, "周杰伦", 30)
        val b = SourceRouter.searchSongs(MusicSource.QQMUSIC, "周杰伦", 30)
        assertEquals(listOf(10L), a.map { it.id })
        assertEquals(listOf(20L), b.map { it.id })
        assertEquals("周杰伦", qq.lastSearchedKeyword)
    }

    @Test
    fun `一个音源搜索抛异常不影响另一个`() = runBlocking {
        registerFakes()
        qq.throwOnSearch = true
        netease.searchResult = listOf(song(10L))
        assertTrue(SourceRouter.searchSongs(MusicSource.QQMUSIC, "x", 10).isEmpty())
        assertEquals(1, SourceRouter.searchSongs(MusicSource.NETEASE, "x", 10).size)
    }

    @Test
    fun `未注册音源的搜索返回空列表`() = runBlocking {
        SourceRouter.register(netease)
        assertTrue(SourceRouter.searchSongs(MusicSource.QQMUSIC, "x", 10).isEmpty())
    }

    @Test
    fun `重复注册同一音源时后者生效`() {
        registerFakes()
        val replacement = FakeProvider(MusicSource.QQMUSIC)
        SourceRouter.register(replacement)
        assertEquals(replacement, SourceRouter.provider(MusicSource.QQMUSIC))
        assertFalse(SourceRouter.registeredSources().isEmpty())
    }

    @Test
    fun `默认注册表里至少有ncm`() {
        // 真实单例的初始状态：没有 QQ（B 阶段才注册），但不能连 ncm 都没有。
        assertNotNull(SourceRouter.provider(MusicSource.NETEASE))
    }
}
