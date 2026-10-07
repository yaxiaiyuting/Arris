/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.0 · T3：离线曲目索引（[OfflineLibraryIndex]）的纯逻辑单测。
 *
 * 覆盖任务书点名的五类：有界 LRU / 坏 JSON / 字段缺失迁移 / upsert 幂等 /
 * 删除后 URL 清单同步 / 排序稳定性（外加对账用的 retainSongIds）。
 */
class OfflineLibraryIndexTest {

    private fun track(
        id: Long,
        name: String? = "歌$id",
        at: Long? = 1_000L,
    ) = OfflineTrack(songId = id, name = name, cacheKey = OfflineKeys.key(id, "lossless"), completedAt = at)

    @Test
    fun `upsert 之后能读回，size 与 songIds 一致`() {
        val idx = OfflineLibraryIndex()
        assertTrue(idx.upsert(track(1L)))
        assertTrue(idx.upsert(track(2L)))
        assertEquals(2, idx.size())
        assertEquals(setOf(1L, 2L), idx.songIds())
        assertEquals("歌1", idx.get(1L)?.name)
        assertNull(idx.get(3L))
    }

    @Test
    fun `主键非法（0 或负数）的条目被忽略`() {
        val idx = OfflineLibraryIndex()
        assertFalse(idx.upsert(OfflineTrack(songId = 0L)))
        assertFalse(idx.upsert(OfflineTrack(songId = -5L)))
        assertEquals(0, idx.size())
    }

    @Test
    fun `upsert 幂等——同一首歌写十遍只有一条，完整信息保留`() {
        val idx = OfflineLibraryIndex()
        repeat(10) { idx.upsert(track(42L)) }
        assertEquals(1, idx.size())
        assertEquals(OfflineKeys.key(42L, "lossless"), idx.get(42L)?.cacheKey)
    }

    @Test
    fun `重播不刷新 completedAt（首次可离线播放的时刻）但更新元数据`() {
        val idx = OfflineLibraryIndex()
        idx.upsert(track(7L, name = "旧名", at = 1_000L))
        idx.upsert(track(7L, name = "新名", at = 9_999L))
        assertEquals(1, idx.size())
        assertEquals(1_000L, idx.get(7L)?.completedAt)
        assertEquals("新名", idx.get(7L)?.name)
    }

    @Test
    fun `有界 LRU——超过上限淘汰最久未 upsert 的一条，重播把它拉回队尾`() {
        val idx = OfflineLibraryIndex(maxEntries = 3)
        idx.upsert(track(1L)); idx.upsert(track(2L)); idx.upsert(track(3L))
        idx.upsert(track(1L)) // 重播 1 → 1 变成最近使用，2 成为最久未用
        idx.upsert(track(4L))
        assertEquals(3, idx.size())
        assertNull(idx.get(2L))
        assertNotNull(idx.get(1L))
        assertNotNull(idx.get(4L))
    }

    @Test
    fun `list 按 completedAt 倒序`() {
        val idx = OfflineLibraryIndex()
        idx.upsert(track(1L, at = 300L))
        idx.upsert(track(2L, at = 100L))
        idx.upsert(track(3L, at = 200L))
        assertEquals(listOf(1L, 3L, 2L), idx.list().map { it.songId })
    }

    @Test
    fun `list 排序是稳定的——同一时刻保持写入先后`() {
        val idx = OfflineLibraryIndex()
        idx.upsert(track(11L, at = 500L))
        idx.upsert(track(12L, at = 500L))
        idx.upsert(track(13L, at = 500L))
        assertEquals(listOf(11L, 12L, 13L), idx.list().map { it.songId })
        // completedAt 缺失（老条目）按 0 处理，排在最后且同样稳定
        idx.upsert(track(14L, at = null))
        idx.upsert(track(15L, at = null))
        assertEquals(listOf(11L, 12L, 13L, 14L, 15L), idx.list().map { it.songId })
    }

    @Test
    fun `remove 返回被删的记录，重复删返回 null`() {
        val idx = OfflineLibraryIndex()
        idx.upsert(track(5L))
        assertEquals(5L, idx.remove(5L)?.songId)
        assertNull(idx.remove(5L))
        assertEquals(0, idx.size())
    }

    @Test
    fun `删除曲目时 URL 清单里的所有档位条目一起删（联动）`() {
        val idx = OfflineLibraryIndex()
        val urls = OfflineUrlIndex()
        idx.upsert(track(8L))
        urls.put(OfflineKeys.key(8L, "lossless"), "http://8/lossless")
        urls.put(OfflineKeys.key(8L, "hires"), "http://8/hires")
        urls.put(OfflineKeys.key(9L, "lossless"), "http://9/lossless")

        val removed = idx.removeWithUrls(8L, urls)

        assertEquals(8L, removed?.songId)
        assertNull(urls.get(OfflineKeys.key(8L, "lossless")))
        assertNull(urls.get(OfflineKeys.key(8L, "hires")))
        // 别的歌一条都不能少
        assertEquals("http://9/lossless", urls.get(OfflineKeys.key(9L, "lossless")))
        assertNull(idx.get(8L))
    }

    @Test
    fun `曲目表里没有、URL 清单里有——孤儿条目同样被清掉`() {
        val idx = OfflineLibraryIndex()
        val urls = OfflineUrlIndex()
        urls.put(OfflineKeys.key(3L, "standard"), "http://3")
        assertNull(idx.removeWithUrls(3L, urls))
        assertEquals(0, urls.size())
    }

    @Test
    fun `retainSongIds 丢掉缓存里已经没有的歌`() {
        val idx = OfflineLibraryIndex()
        idx.upsert(track(1L)); idx.upsert(track(2L)); idx.upsert(track(3L))
        assertEquals(1, idx.retainSongIds(setOf(1L, 3L)))
        assertEquals(listOf(1L, 3L), idx.songIds().sorted())
    }

    /**
     * v2.5.6 · P2：**真机形状**的对账用例 —— PLC110 上那条注入记录（songId `503616`）。
     *
     * ## 为什么单开一条（而不是复用上面那条三首歌的小用例）
     *
     * 上面那条只证明「不在 keep 集合里就会被丢」。真机上真正会犯的错是**判据选错**：
     * PLC110 实测 50 条索引里，有 **6 条真实条目没有 `urls` 条目**
     * （`1854421609 / 1380176 / 1983686033 / 22259257 / 381962 / 1970006`）——
     * 因为写 URL 的路径（`PlaybackService` 的 `playUrl`）并不覆盖所有起播路径。
     * 于是「没有 urls 条目 ⇒ 是脏数据 ⇒ 删掉」这条看似合理的启发式会**误删 6 首真实离线歌**。
     *
     * 唯一正确的判据是**音频缓存里还有没有片段**（`OfflineAudioCache.reconcileLibrary`
     * 正是这么算的：`keepSongIds = keys(app).mapNotNull { OfflineKeys.songIdOf(it) }`）。
     * 注入的那条 `503616` 是**双孤儿** —— 没有 `urls`、也没有缓存片段 ——
     * 所以它被丢掉的**唯一**理由是「缓存里没有」，而不是「urls 里没有」。
     *
     * 本用例把这两个集合显式分开，任何「改用 urls 判据」的改动都会在这里变红。
     */
    @Test
    fun `retainSongIds 只认缓存判据——缺 urls 的真实条目不许被误伤（真机 v256 形状）`() {
        val idx = OfflineLibraryIndex()

        // 注入记录：PLC110 上 2026-09-26 14:10:31 写入，字段取自真机快照。
        val injectedId = 503_616L
        idx.upsert(
            OfflineTrack(
                songId = injectedId,
                name = "EM10_C_Long_Premix#070705",
                artist = "鷺巣詩郎",
                durationMs = 137_160L,
                level = "jymaster",
                cacheKey = OfflineKeys.key(injectedId, "jymaster"),
                completedAt = 1_790_403_031_347L,
            )
        )

        // 6 条真实但**没有 urls 条目**的歌（真机实测）—— 它们全都必须在。
        val realWithoutUrls = listOf(
            1_854_421_609L, 1_380_176L, 1_983_686_033L, 22_259_257L, 381_962L, 1_970_006L
        )
        realWithoutUrls.forEach { idx.upsert(track(it, at = 1_700_000_000_000L)) }

        // 另外若干条有缓存、也有 urls 的普通真实条目。
        val ordinary = listOf(561_105_553L, 1_854_421_610L, 247_936L, 5_257_138L)
        ordinary.forEach { idx.upsert(track(it, at = 1_700_000_000_000L)) }

        val allReal = (realWithoutUrls + ordinary).toSet()
        assertEquals(1 + allReal.size, idx.size())

        // keep 集合 = 「音频缓存里还有片段的 songId」，**不含** 503616。
        val dropped = idx.retainSongIds(allReal)

        assertEquals("只许丢掉那一条注入记录", 1, dropped)
        assertNull(idx.get(injectedId))
        allReal.forEach { id ->
            assertNotNull("真实条目 $id 被误删了", idx.get(id))
        }
        assertEquals(allReal, idx.songIds())
    }

    @Test
    fun `retainSongIds 的边界——keep 为空时清空，keep 全覆盖时一条不动`() {
        val idx = OfflineLibraryIndex()
        idx.upsert(track(1L)); idx.upsert(track(2L))

        assertEquals(0, idx.retainSongIds(setOf(1L, 2L)))
        assertEquals(2, idx.size())

        // 空 keep = 「缓存里一条都没有」⇒ 全丢（这是 clear() 之外的另一条合法路径，
        // 也是 `OfflineAudioCache.clear()` 之后可能出现的一致状态）。
        assertEquals(2, idx.retainSongIds(emptySet()))
        assertEquals(0, idx.size())
    }

    @Test
    fun `totalBytes 只累加 approxBytes，缺失按 0`() {
        val idx = OfflineLibraryIndex()
        idx.upsert(track(1L).copy(approxBytes = 1_000L))
        idx.upsert(track(2L))
        idx.upsert(track(3L).copy(approxBytes = 24L))
        assertEquals(1_024L, idx.totalBytes())
    }

    @Test
    fun `JSON 往返——所有字段与顺序都在`() {
        val idx = OfflineLibraryIndex()
        idx.upsert(
            OfflineTrack(
                songId = 247936L,
                name = "屋顶",
                artist = "周杰伦 / 温岚",
                albumPicUrl = "http://p1.music.126.net/x.jpg",
                durationMs = 321_000L,
                level = "exhigh",
                cacheKey = OfflineKeys.key(247936L, "exhigh"),
                approxBytes = 8_912_345L,
                completedAt = 1_700_000_000_000L,
            )
        )
        idx.upsert(track(2L, at = 900L))
        val back = OfflineLibraryIndex.fromJson(idx.toJson())
        assertEquals(2, back.size())
        val first = back.get(247936L)
        assertEquals("屋顶", first?.name)
        assertEquals("周杰伦 / 温岚", first?.artist)
        assertEquals("http://p1.music.126.net/x.jpg", first?.albumPicUrl)
        assertEquals(321_000L, first?.durationMs)
        assertEquals("exhigh", first?.level)
        assertEquals(OfflineKeys.key(247936L, "exhigh"), first?.cacheKey)
        assertEquals(8_912_345L, first?.approxBytes)
        assertEquals(1_700_000_000_000L, first?.completedAt)
        assertEquals(listOf(247936L, 2L), back.list().map { it.songId })
    }

    @Test
    fun `坏 JSON 一律回落成空索引，不抛异常`() {
        assertEquals(0, OfflineLibraryIndex.fromJson(null).size())
        assertEquals(0, OfflineLibraryIndex.fromJson("").size())
        assertEquals(0, OfflineLibraryIndex.fromJson("{not json").size())
        assertEquals(0, OfflineLibraryIndex.fromJson("[1,2,3]").size())
        assertEquals(0, OfflineLibraryIndex.fromJson("\"just a string\"").size())
    }

    @Test
    fun `字段缺失迁移——老条目只有 songId 也能装载，缺字段是 null 而不是崩溃`() {
        // 手工写一份「上个版本落下的」JSON：只有主键与 cacheKey。
        val legacy = "[{\"songId\":42,\"cacheKey\":\"song:42:lossless\"}]"
        val idx = OfflineLibraryIndex.fromJson(legacy)
        assertEquals(1, idx.size())
        val t = idx.get(42L)
        assertNotNull(t)
        assertEquals("song:42:lossless", t?.cacheKey)
        assertNull(t?.name)
        assertNull(t?.artist)
        assertNull(t?.albumPicUrl)
        assertNull(t?.durationMs)
        assertNull(t?.level)
        assertNull(t?.approxBytes)
        assertNull(t?.completedAt)
        // 缺 completedAt 的条目排在最后（按 0 处理），不是排在最前
        assertEquals(0L, idx.totalBytes())
    }

    @Test
    fun `字段缺失迁移——JSON 里主键非法的条目被丢掉`() {
        val json = "[{\"songId\":0,\"name\":\"坏\"},{\"name\":\"没有主键\"},{\"songId\":9}]"
        val idx = OfflineLibraryIndex.fromJson(json)
        assertEquals(1, idx.size())
        assertEquals(9L, idx.list().first().songId)
    }

    @Test
    fun `装载时同样受上限约束——超出的按写入顺序淘汰`() {
        val json = (1..5).joinToString(",", "[", "]") { "{\"songId\":$it}" }
        // fromJson 用默认上限（300），这里用 5 条验证「装载路径也走 upsert」：不该抛、顺序保留
        val idx = OfflineLibraryIndex.fromJson(json)
        assertEquals(5, idx.size())
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), idx.list().map { it.songId })
    }
}
