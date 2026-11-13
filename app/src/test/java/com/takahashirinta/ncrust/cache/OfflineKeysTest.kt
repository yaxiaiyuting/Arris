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
        assertEquals(123L, OfflineKeys.songIdOf("song:123:"))
        assertNull(OfflineKeys.levelOf("song:123:"))
        assertEquals("lossless", OfflineKeys.levelOf("song:123:lossless~trial"))
        assertNull(OfflineKeys.songIdOf("song:123:lossless:extra"))
        assertEquals(Long.MAX_VALUE, OfflineKeys.songIdOf("song:9223372036854775807:standard"))
        assertNull(OfflineKeys.songIdOf("song:9223372036854775808:standard"))
        assertEquals(123L, OfflineKeys.songIdOf("song:+٠١٢٣:standard"))
        assertNull(OfflineKeys.songIdOf("song:-123:standard"))
    }

    @Test
    fun `按歌曲批量汇总多档位大小并保留无法测量的语义`() {
        val full = OfflineKeys.key(1L, "standard")
        val lossless = OfflineKeys.key(1L, "lossless")
        val zero = OfflineKeys.key(2L, "standard")
        val unavailable = OfflineKeys.key(3L, "standard")
        val irrelevant = OfflineKeys.key(4L, "standard")
        val measured = mutableListOf<String>()
        val bytes = OfflineKeys.sumBytesBySong(
            listOf(full, lossless, zero, unavailable, irrelevant, "invalid"),
            setOf(1L, 2L, 3L, 5L),
        ) { key ->
            measured += key
            when (key) {
                full -> 12L
                lossless -> 24L
                zero -> 0L
                else -> null
            }
        }

        assertEquals(mapOf(1L to 36L, 2L to 0L), bytes)
        assertEquals(listOf(full, lossless, zero, unavailable), measured)
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

class OfflineKeysCoversStartTest {
    @Test fun anEmptyCacheOrMiddleOnlySpanCannotStart() {
        for (initial in listOf(0L, -200L, -Long.MAX_VALUE)) {
            assertFalse(OfflineKeys.coversStart { offset, limit ->
                assertEquals(0L, offset)
                assertEquals(Long.MAX_VALUE, limit)
                initial
            })
        }
    }

    @Test fun aPartialPrefixCanStartWhenNoMoreBytesFollow() {
        assertTrue(OfflineKeys.coversStart { offset, limit ->
            if (offset == 0L) 128L else {
                assertEquals(128L, offset)
                assertEquals(Long.MAX_VALUE - 128L, limit)
                -limit
            }
        })
    }

    @Test fun anotherCachedSpanAfterTheFirstHoleRejectsTheKey() {
        assertFalse(OfflineKeys.coversStart { offset, _ -> if (offset == 0L) 128L else -384L })
    }

    @Test fun aPrefixSpanningTheWholeRangeNeedsOnlyOneLookup() {
        assertTrue(OfflineKeys.coversStart { offset, limit ->
            assertEquals(0L, offset)
            assertEquals(Long.MAX_VALUE, limit)
            limit
        })
    }
}
