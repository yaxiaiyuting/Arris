/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.9.0：歌词缓存模型层（[CachedLyrics] 的 TTML 字段 + TTL 判定）的单测。
 *
 * 这一层刻意是**纯逻辑**（不碰 Android），所以不需要 Robolectric 也能在 JVM 上真跑 ——
 * 用真 Gson 解析老格式 JSON，验证「老缓存没有新字段也能正常反序列化」。
 * SharedPreferences 的读写路径不在本文件（需要真机/模拟器，见交付说明里的「未验证」）。
 */
class LyricsCacheModelTest {

    private val gson = Gson()

    /** v1.5.0 的缓存格式：有 yrc，没有 ttml / ttmlAt。 */
    private val v150Json =
        """{"lrc":"[00:01.00]甲","tlyric":"[00:01.00]A","timestamp":1700000000000,"yrc":"[1000,500](1000,500,0)甲"}"""

    /** v1.4.0 的缓存格式：连 yrc 都没有。 */
    private val v140Json = """{"lrc":"[00:01.00]甲","tlyric":"[00:01.00]A","timestamp":1700000000000}"""

    // ---------- 老缓存兼容（Gson 真解析） ----------

    @Test
    fun `老缓存 JSON——v1_5_0 格式真解析不抛，ttml 为 null 且 ttmlAt 为 0`() {
        val entry = gson.fromJson(v150Json, CachedLyrics::class.java)
        assertNotNull(entry)
        assertEquals("[00:01.00]甲", entry.lrc)
        assertEquals("[1000,500](1000,500,0)甲", entry.yrc)
        assertNull("老缓存没有 ttml key，反序列化必须留下 null", entry.ttml)
        assertEquals("老缓存没有 ttmlAt key，必须留下 0（= 从未写过）", 0L, entry.ttmlAt)
    }

    @Test
    fun `老缓存 JSON——v1_4_0 格式（连 yrc 都没有）也能解析`() {
        val entry = gson.fromJson(v140Json, CachedLyrics::class.java)
        assertEquals("[00:01.00]甲", entry.lrc)
        assertNull(entry.yrc)
        assertNull(entry.ttml)
        assertEquals(0L, entry.ttmlAt)
    }

    @Test
    fun `老缓存 JSON——整表 map 形态（loadLocked 的真实输入）能解析，不会因新增字段炸掉`() {
        val raw = """{"5257138":$v140Json}"""
        val type = object : TypeToken<MutableMap<String, CachedLyrics>>() {}.type
        val map = gson.fromJson<MutableMap<String, CachedLyrics>>(raw, type)
        assertEquals(1, map.size)
        val entry = map["5257138"]!!
        assertEquals("[00:01.00]甲", entry.lrc)
        assertNull(entry.ttml)
        assertEquals(0L, entry.ttmlAt)
    }

    @Test
    fun `新格式——ttml 与 ttmlAt 能写能读回`() {
        val back = gson.fromJson(
            gson.toJson(CachedLyrics("[00:01.00]甲", "", 1L, null, "<tt/>", 123L)),
            CachedLyrics::class.java
        )
        assertEquals("<tt/>", back.ttml)
        assertEquals(123L, back.ttmlAt)
    }

    @Test
    fun `构造兼容——v1_5_0 的四参调用点仍然可编译，新字段走默认值`() {
        val legacy = CachedLyrics("[00:01.00]甲", "", 1L, "[1000,500](1000,500,0)甲")
        assertNull(legacy.ttml)
        assertEquals(0L, legacy.ttmlAt)
    }

    // ---------- isTtmlFresh ----------

    @Test
    fun `isTtmlFresh——新鲜、过期、以及正好等于 TTL 的边界`() {
        val ttl = LyricsCache.TTML_TTL_MS
        assertEquals("TTL 必须是 7 天", 7L * 24 * 60 * 60 * 1000, ttl)
        assertTrue("刚写入", LyricsCache.isTtmlFresh(1_000L, 1_000L, ttl))
        assertTrue("差 1ms 到期仍是新鲜", LyricsCache.isTtmlFresh(1_000L, 1_000L + ttl - 1, ttl))
        assertFalse("正好等于 TTL = 过期", LyricsCache.isTtmlFresh(1_000L, 1_000L + ttl, ttl))
        assertFalse("过期 1ms", LyricsCache.isTtmlFresh(1_000L, 1_000L + ttl + 1, ttl))
        assertFalse("老缓存没有 ttmlAt", LyricsCache.isTtmlFresh(0L, 0L, ttl))
        assertFalse("负数时间戳也当没有", LyricsCache.isTtmlFresh(-1L, 1_000L, ttl))
    }

    @Test
    fun `isTtmlFresh——ttlMs 可覆盖，走同一套左闭右开边界`() {
        assertFalse(LyricsCache.isTtmlFresh(100L, 600L, 500L))
        assertTrue(LyricsCache.isTtmlFresh(100L, 600L, 501L))
        assertFalse("ttlMs=0 时任何条目都算过期", LyricsCache.isTtmlFresh(100L, 100L, 0L))
    }

    // ---------- ttmlOf（getTtml / getTtmlStale 共用的纯选择逻辑） ----------

    @Test
    fun `ttmlOf——TTL 内返回原文，过期返回 null，stale 模式忽略 TTL`() {
        val now = 10_000L
        val fresh = CachedLyrics("", "", now, null, "<tt>a</tt>", now - 1_000L)
        assertEquals("<tt>a</tt>", LyricsCache.ttmlOf(fresh, now, requireFresh = true))
        assertEquals("<tt>a</tt>", LyricsCache.ttmlOf(fresh, now, requireFresh = false))

        val stale = CachedLyrics("", "", now, null, "<tt>b</tt>", now - LyricsCache.TTML_TTL_MS)
        assertNull("过期就不该被在线路径命中", LyricsCache.ttmlOf(stale, now, requireFresh = true))
        assertEquals("离线兜底可以拿到过期数据", "<tt>b</tt>", LyricsCache.ttmlOf(stale, now, requireFresh = false))
    }

    @Test
    fun `ttmlOf——没有条目、老缓存、空白内容都不算命中`() {
        assertNull(LyricsCache.ttmlOf(null, 1_000L))
        assertNull("老缓存条目 ttml 为 null", LyricsCache.ttmlOf(CachedLyrics("a", "b", 1L), 1_000L))
        assertNull(
            "空白不是歌词",
            LyricsCache.ttmlOf(CachedLyrics("", "", 1L, null, "   ", 1L), 1_000L, requireFresh = false)
        )
        assertNull(
            "有 ttml 但 ttmlAt 为 0（半截老数据）不算新鲜",
            LyricsCache.ttmlOf(CachedLyrics("", "", 1L, null, "<tt/>", 0L), 1_000L, requireFresh = true)
        )
    }

    // ---------- v1.9.2：romalrc + 译文/音译来源标记 ----------

    /** v1.9.1 的缓存格式：有 ttml/ttmlAt，没有 romalrc / translationSource / romanSource。 */
    private val v191Json =
        """{"lrc":"[00:01.00]甲","tlyric":"[00:01.00]A","timestamp":1700000000000,""" +
            """"ttml":"<tt/>","ttmlAt":1700000000000}"""

    @Test
    fun `老缓存 JSON——v1_9_1 格式真解析不抛，三个新字段都是 null`() {
        val entry = gson.fromJson(v191Json, CachedLyrics::class.java)
        assertNotNull(entry)
        assertEquals("<tt/>", entry.ttml)
        assertNull("老缓存没有 romalrc key ⇒ null（读取处 orEmpty() 退化成没有音译轨）", entry.romalrc)
        assertNull("老缓存没有 translationSource ⇒ null（= 来源未知，不影响任何决策）", entry.translationSource)
        assertNull(entry.romanSource)
    }

    @Test
    fun `整表 map——v1_9_1 的老表混进新条目也能解析`() {
        val raw = """{"5257138":$v191Json,"22704409":""" +
            """{"lrc":"a","tlyric":"b","timestamp":2,"romalrc":"c","translationSource":"NETEASE","romanSource":"MIXED"}}"""
        val type = object : TypeToken<MutableMap<String, CachedLyrics>>() {}.type
        val map = gson.fromJson<MutableMap<String, CachedLyrics>>(raw, type)
        assertEquals(2, map.size)
        assertNull(map["5257138"]!!.romalrc)
        assertEquals("c", map["22704409"]!!.romalrc)
        assertEquals("NETEASE", map["22704409"]!!.translationSource)
        assertEquals("MIXED", map["22704409"]!!.romanSource)
    }

    @Test
    fun `新格式——romalrc 与两个来源标记能写能读回，来源用枚举名当稳定标记`() {
        val entry = CachedLyrics(
            lrc = "[00:01.00]甲", tlyric = "A", timestamp = 1L,
            romalrc = "[00:01.00]jia", translationSource = "TTML", romanSource = "MIXED"
        )
        val back = gson.fromJson(gson.toJson(entry), CachedLyrics::class.java)
        assertEquals("[00:01.00]jia", back.romalrc)
        assertEquals("TTML", back.translationSource)
        assertEquals("MIXED", back.romanSource)
        assertEquals("TTML", LyricTrackSource.TTML.cacheTag)
        assertEquals("NETEASE", LyricTrackSource.NETEASE.cacheTag)
        assertEquals("MIXED", LyricTrackSource.MIXED.cacheTag)
    }

    // ---------- v1.9.2：升级前的老条目要重取一次 romalrc ----------

    @Test
    fun `needsRomalrcRefetch——缺字段的老条目要重取，空串（确实没有音译）不重取`() {
        assertTrue(
            "v1.9.1 写下的条目没有 romalrc 字段 ⇒ 当作 miss 重取一次",
            LyricsCache.needsRomalrcRefetch(gson.fromJson(v191Json, CachedLyrics::class.java))
        )
        assertTrue(
            "更老的缓存同样",
            LyricsCache.needsRomalrcRefetch(gson.fromJson(v140Json, CachedLyrics::class.java))
        )
        assertFalse(
            "v1.9.2 已经写过的「空音译轨」是权威结论，不该反复重取",
            LyricsCache.needsRomalrcRefetch(
                CachedLyrics("a", "b", 1L, null, null, 0L, romalrc = "")
            )
        )
        assertFalse(
            "有音译更不重取",
            LyricsCache.needsRomalrcRefetch(
                CachedLyrics("a", "b", 1L, null, null, 0L, romalrc = "[00:01.00]jia")
            )
        )
        assertFalse("没有条目本来就打网络，不算「老条目」", LyricsCache.needsRomalrcRefetch(null))
    }

    @Test
    fun `withTrackSources——没有条目就不建条目（绝不为了记标记写出 lrc=空 的假「确无歌词」）`() {
        assertNull(LyricsCache.withTrackSources(null, "TTML", "NETEASE"))
    }

    @Test
    fun `withTrackSources——值未变返回 null（缓存命中路径不该反复序列化整张表）`() {
        val entry = CachedLyrics("a", "b", 1L, null, null, 0L, "r", "TTML", "NETEASE")
        assertNull(LyricsCache.withTrackSources(entry, "TTML", "NETEASE"))
        assertNull("两边都是未知也视为未变", LyricsCache.withTrackSources(CachedLyrics("a", "b", 1L), null, null))
    }

    @Test
    fun `withTrackSources——值变了返回新条目，其余字段原样保留`() {
        val entry = CachedLyrics("a", "b", 7L, "y", "<tt/>", 9L, "r", "TTML", null)
        val updated = LyricsCache.withTrackSources(entry, "MIXED", "NETEASE")!!
        assertEquals("MIXED", updated.translationSource)
        assertEquals("NETEASE", updated.romanSource)
        assertEquals("原文与时间戳不得被顺手改掉", entry.copy(
            translationSource = "MIXED", romanSource = "NETEASE"
        ), updated)
    }

    @Test
    fun `withTrackSources——源退回「没有这一轨」时标记被清成 null`() {
        val entry = CachedLyrics("a", "b", 1L, null, null, 0L, null, "NETEASE", "NETEASE")
        val cleared = LyricsCache.withTrackSources(entry, null, null)!!
        assertNull(cleared.translationSource)
        assertNull(cleared.romanSource)
    }
}
