/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.6.0 · D1：离线缓存 key 规则与 URL 索引的单测（纯逻辑，不需要设备）。 */
class OfflineKeysTest {

    // 真实形态的播放 URL：host 会轮换、路径里带签发时间戳与签名，query 是装饰。
    private val url = "http://m704.music.126.net/20260922230000/deadbeef/abc.mp3?vuutv=xyz"

    @Test
    fun `key 的形状是 song_id_level`() {
        assertEquals("song:123:lossless", OfflineKeys.key(123L, "lossless"))
    }

    @Test
    fun `挂 key——已有 query 用 & 连接，没有 query 用 ? 连接`() {
        val withQ = OfflineKeys.withKey(url, 123L, "lossless")
        assertTrue(withQ.endsWith("&" + OfflineKeys.QUERY_KEY + "=song:123:lossless"))
        assertEquals(url, withQ.substringBefore("&" + OfflineKeys.QUERY_KEY + "="))
        val noQ = OfflineKeys.withKey("http://m704.music.126.net/a.mp3", 123L, "hires")
        assertEquals("http://m704.music.126.net/a.mp3?" + OfflineKeys.QUERY_KEY + "=song:123:hires", noQ)
    }

    @Test
    fun `挂 key 是幂等的——同一个 URL 挂两次不会出现两个 key`() {
        val once = OfflineKeys.withKey(url, 123L, "lossless")
        assertEquals(once, OfflineKeys.withKey(once, 123L, "lossless"))
    }

    @Test
    fun `挂 key——空 URL、非法 id、空档位时原样返回`() {
        assertEquals("", OfflineKeys.withKey("", 1L, "lossless"))
        assertEquals(url, OfflineKeys.withKey(url, 0L, "lossless"))
        assertEquals(url, OfflineKeys.withKey(url, 1L, ""))
    }

    @Test
    fun `取 key——从任意位置的 query 参数里都能取回，取不到返回 null`() {
        assertEquals("song:123:lossless", OfflineKeys.keyOf(OfflineKeys.withKey(url, 123L, "lossless")))
        assertEquals("song:9:hires", OfflineKeys.keyOf("http://h/a.mp3?ncrustkey=song:9:hires&vuutv=1"))
        assertEquals("song:9:hires", OfflineKeys.keyOf("http://h/a.mp3?a=1&b=2&ncrustkey=song:9:hires"))
        assertNull(OfflineKeys.keyOf("http://h/a.mp3?vuutv=1"))
        assertNull(OfflineKeys.keyOf("http://h/a.mp3"))
        assertNull(OfflineKeys.keyOf("http://h/a.mp3?ncrustkey="))
    }

    @Test
    fun `key 里能解回 id 与档位，坏 key 返回 null`() {
        assertEquals(123L, OfflineKeys.songIdOf("song:123:lossless"))
        assertEquals("lossless", OfflineKeys.levelOf("song:123:lossless"))
        assertNull(OfflineKeys.songIdOf("song:abc:lossless"))
        assertNull(OfflineKeys.songIdOf("song:0:lossless"))
        assertNull(OfflineKeys.songIdOf("ncrust:123:lossless"))
        assertNull(OfflineKeys.levelOf("song:123"))
    }

    @Test
    fun `走一遍真实流程——挂 key 再去回来是同一个 key`() {
        val k = OfflineKeys.key(247936L, "exhigh")
        assertEquals(k, OfflineKeys.keyOf(OfflineKeys.withKey(url, 247936L, "exhigh")))
    }
}

/** [OfflineUrlIndex]：有界、LRU、离线兜底按歌曲 id 退化。 */
class OfflineUrlIndexTest {

    @Test
    fun `put 之后能取回，同一个 key 覆盖不重复计数`() {
        val idx = OfflineUrlIndex()
        idx.put("song:1:lossless", "http://a")
        idx.put("song:1:lossless", "http://b")
        assertEquals(1, idx.size())
        assertEquals("http://b", idx.get("song:1:lossless"))
    }

    @Test
    fun `超过上限时淘汰最久未写入的那条（LRU）`() {
        val idx = OfflineUrlIndex(maxEntries = 3)
        idx.put("k1", "u1"); idx.put("k2", "u2"); idx.put("k3", "u3")
        idx.get("k1") // 读不算「使用」，顺序仍按写入时间
        idx.put("k4", "u4")
        assertEquals(3, idx.size())
        assertNull(idx.get("k1"))
        assertEquals("u4", idx.get("k4"))
    }

    @Test
    fun `recall 先按偏好档位找，找不到再退化成这首歌的任意档位`() {
        val idx = OfflineUrlIndex()
        idx.put(OfflineKeys.key(7L, "standard"), "http://s")
        // 用户现在选的是无损 —— 严格找会落空，退化后仍能拿到 standard 那份
        val got = idx.recall(listOf(OfflineKeys.key(7L, "lossless")), 7L)
        assertEquals(OfflineKeys.key(7L, "standard"), got?.first)
        assertEquals("http://s", got?.second)
        assertNull(idx.recall(listOf(OfflineKeys.key(8L, "lossless")), 8L))
    }

    @Test
    fun `recall 不会把别的歌当成命中`() {
        val idx = OfflineUrlIndex()
        idx.put(OfflineKeys.key(11L, "lossless"), "http://11")
        assertNull(idx.recall(emptyList(), 12L))
        assertEquals("http://11", idx.recall(emptyList(), 11L)?.second)
    }

    @Test
    fun `JSON 往返——落盘再读回，顺序与内容都在`() {
        val idx = OfflineUrlIndex()
        idx.put("song:1:lossless", "http://a")
        idx.put("song:2:hires", "http://b")
        val back = OfflineUrlIndex.fromJson(idx.toJson())
        assertEquals(2, back.size())
        assertEquals("http://a", back.get("song:1:lossless"))
        assertEquals("http://b", back.get("song:2:hires"))
    }

    @Test
    fun `JSON 坏数据不抛异常，回落成空索引`() {
        assertEquals(0, OfflineUrlIndex.fromJson(null).size())
        assertEquals(0, OfflineUrlIndex.fromJson("").size())
        assertEquals(0, OfflineUrlIndex.fromJson("{not json").size())
        assertEquals(0, OfflineUrlIndex.fromJson("[1,2,3]").size())
    }

    @Test
    fun `空 key 或空 url 不入索引`() {
        val idx = OfflineUrlIndex()
        idx.put("", "http://a")
        idx.put("k", "")
        assertEquals(0, idx.size())
    }
}

/**
 * v3.3.0：[OfflineKeys.coversStart] 的行为契约 —— 「缓存能不能**从头播**」。
 *
 * 这条纯函数是为用户反馈第 1 条「离线播放不太行」的第二个缺陷写的：
 * 旧判据是「缓存里有没有这个 key 的片段」，而播放器**从 position 0 读**。
 * 缓存只有中段时旧判据会放行 ⇒ 起播正常、播到洞的位置突然卡死再弹降级。
 *
 * 形状全部来自 `SimpleCache.getCachedSpans` 的真实语义：
 * 一个 key 下可以有**多个** span，`position` 是它们在文件里的字节偏移，
 * `length` 是该 span 的长度，`isCached=false` 表示它是「已锁定但尚未下载」的占位。
 */
class OfflineKeysCoversStartTest {

    private fun span(position: Long, length: Long, isCached: Boolean = true) =
        OfflineKeys.CachedSpan(position, length, isCached)

    @Test
    fun `没有任何片段时不能从头播`() {
        assertFalse(OfflineKeys.coversStart(emptyList()))
    }

    @Test
    fun `从 0 起的完整片段可以播`() {
        assertTrue(OfflineKeys.coversStart(listOf(span(0, 1_000_000))))
    }

    @Test
    fun `只有中段片段时不能从头播 —— 这正是播一半就断的形状`() {
        // 用户 seek 到中段听了一段：缓存里只有 [500000, 1000000)，段落起点不是 0。
        assertFalse(
            "从 0 起没有覆盖 ⇒ 起播那一刻就会回源",
            OfflineKeys.coversStart(listOf(span(500_000, 500_000))),
        )
    }

    @Test
    fun `多个片段首尾相接能覆盖 0 时可以播`() {
        // 真实缓存常见形态：下载器按 fragment 切，0 到 1MB 由若干段拼起来。
        val spans = listOf(
            span(0, 200_000),
            span(200_000, 200_000),
            span(400_000, 600_000),
        )
        assertTrue(OfflineKeys.coversStart(spans))
    }

    @Test
    fun `片段之间有洞时判为不可播 —— 判据是连续覆盖到某一点`() {
        // ⚠️ 这条**曾经写反过**（原来叫「洞在 0 之后仍算可播」，断言 true）。
        // 写反的原因是把两件事混成了一件事：
        //   - 「需求上允许缓存不完整」—— 这是**产品**口径（没有下载功能，离线范围就是播过的片段）；
        //   - 「能不能从 0 起连续读」—— 这是**缓存**口径，洞就是读不过去。
        // `coversStart` 回答的是后者。洞在后面 ⇒ 播到洞就卡死，所以必须判 false。
        //
        // 允许「不完整」的表达方式是**不要求覆盖整曲**（见下面那条用例），
        // 而不是「遇到洞也说能播」。
        val spans = listOf(
            span(0, 200_000),
            span(800_000, 200_000), // 与上一段之间有 600KB 的洞
        )
        assertFalse(
            "洞之后的部分读不到，播到那里就会回源",
            OfflineKeys.coversStart(spans),
        )
    }

    @Test
    fun `不要求覆盖整曲 —— 只要 0 起连续就够`() {
        // 这是「允许不完整」的正确表达方式：本应用没有下载功能，
        // 离线范围就是「本机真播过的片段」，要求整曲会把绝大多数真实缓存判死。
        // 但要求的是**从 0 起连续**，不要求到文件末尾。
        val spans = listOf(span(0, 200_000))
        assertTrue(OfflineKeys.coversStart(spans))
    }

    @Test
    fun `未缓存的占位片段不算覆盖`() {
        // isCached=false 是「已锁定但还没下载」的占位：它在 keys 里，但磁盘上没有字节。
        assertFalse(
            "占位不是数据",
            OfflineKeys.coversStart(listOf(span(0, 200_000, isCached = false))),
        )
        // 真实片段 + 占位混在一起时，以真实片段为准
        assertTrue(
            OfflineKeys.coversStart(
                listOf(span(0, 200_000), span(200_000, 200_000, isCached = false)),
            ),
        )
    }

    @Test
    fun `长度为 0 的片段不构成覆盖`() {
        assertFalse(OfflineKeys.coversStart(listOf(span(0, 0))))
    }

    @Test
    fun `片段顺序无关 —— 内部会排序`() {
        val a = OfflineKeys.coversStart(listOf(span(0, 100), span(100, 100)))
        val b = OfflineKeys.coversStart(listOf(span(100, 100), span(0, 100)))
        assertEquals("排序不应影响结论", a, b)
        assertTrue(a)
    }

    @Test
    fun `第一段不从 0 起时后面接得再满也没用`() {
        assertFalse(
            OfflineKeys.coversStart(listOf(span(100, 100), span(200, 100), span(300, 100))),
        )
    }

    @Test
    fun `相邻片段起点等于已覆盖位置时算连续`() {
        // 边界：`position == coveredUpTo` 必须算连续，否则下载器按片段切出来的缓存
        // 会被整体判成不可播（每个 fragment 的起点都恰好等于上一段的终点）。
        assertTrue(OfflineKeys.coversStart(listOf(span(0, 100), span(100, 100))))
    }

    @Test
    fun `重叠片段不会让判据出错`() {
        assertTrue(OfflineKeys.coversStart(listOf(span(0, 300), span(100, 100), span(200, 100))))
    }
}
