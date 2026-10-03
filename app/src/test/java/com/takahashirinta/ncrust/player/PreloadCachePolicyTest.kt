/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · P0-B：URL 预加载 TTL 判据的单测（铁律 22）。
 */

package com.takahashirinta.ncrust.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PreloadCachePolicy.isFresh] 的边界。
 *
 * 这一组用例的存在理由很具体：v3.0.0 的判据是内联在三个调用点上的同一个表达式，
 * 而 v3.1.0 的 B 站**必须**用第二种 TTL 模型（服务端给的绝对过期时刻）。
 * 两种模型都必须有边界用例 —— 少了任何一种，默认值一漂移就会静默地
 * 「用一条已经 403 的 URL 去开播」。
 */
class PreloadCachePolicyTest {

    private fun entry(
        level: String = "lossless",
        timestamp: Long = 1_000_000L,
        expiresAtMs: Long? = null,
    ) = PreloadCacheEntry(
        url = "https://example.invalid/a.m4a",
        actualLevel = "lossless",
        requestedLevel = level,
        timestamp = timestamp,
        expiresAtMs = expiresAtMs,
    )

    // ---------------------------------------------------------------- 模型 ①：固定 TTL

    @Test
    fun `固定 TTL 内可用 超出不可用（v3_0_0 的既有模型逐字不变）`() {
        val e = entry(timestamp = 1_000_000L)
        val ttl = PreloadCachePolicy.DEFAULT_TTL_MS
        assertTrue("正好在 TTL 上应当算新鲜", PreloadCachePolicy.isFresh(e, "lossless", 1_000_000L + ttl))
        assertFalse(
            "超出 1ms 就该判过期（铁律 22：过期不得使用）",
            PreloadCachePolicy.isFresh(e, "lossless", 1_000_000L + ttl + 1),
        )
    }

    @Test
    fun `默认 TTL 是 5 分钟`() {
        // 这个数字必须与 v3.0.0 的 `CACHE_TTL_MS` 逐字相同 —— 常量搬了家，语义没搬。
        assertEquals(5 * 60 * 1000L, PreloadCachePolicy.DEFAULT_TTL_MS)
    }

    @Test
    fun `档位不一致一律不可用（降档重试不得复用上一档的 URL）`() {
        val e = entry(level = "lossless", timestamp = 1_000_000L)
        assertFalse(
            "请求 exhigh 时不能命中 lossless 的缓存条目 —— 那是 v2.2.1 修过的 P0",
            PreloadCachePolicy.isFresh(e, "exhigh", 1_000_000L),
        )
    }

    @Test
    fun `没有条目一律不可用`() {
        assertFalse(PreloadCachePolicy.isFresh(null, "lossless", 1_000_000L))
    }

    // ---------------------------------------------------------------- 模型 ②：显式过期时刻

    @Test
    fun `显式过期时刻优先于固定 TTL`() {
        // 时间戳很老（远超 5 分钟）但服务端说还能用 10 分钟 ⇒ 必须可用。
        // B 站实测就是这个形状：timeout=10800s，而 URL 的 deadline 是 now+7200s。
        val now = 10_000_000L
        val e = entry(timestamp = now - 60 * 60 * 1000L, expiresAtMs = now + 600_000L)
        assertTrue(PreloadCachePolicy.isFresh(e, "lossless", now))
    }

    @Test
    fun `显式过期时刻到了就不可用（边界取过期）`() {
        val now = 10_000_000L
        val e = entry(timestamp = now, expiresAtMs = now + 1_000L)
        assertTrue(PreloadCachePolicy.isFresh(e, "lossless", now + 999L))
        assertFalse(
            "now == expiresAt 必须判过期 —— 服务端的 deadline 是秒级，保守一侧才安全",
            PreloadCachePolicy.isFresh(e, "lossless", now + 1_000L),
        )
        assertFalse(PreloadCachePolicy.isFresh(e, "lossless", now + 1_001L))
    }

    @Test
    fun `已经过期的条目在时间戳很新的情况下也不可用`() {
        // 关键形状：**时间戳新 ≠ 可用**。B 站的 deadline 可能比写入时刻早
        // （服务端给的 URL 本来就只剩几十秒），只按「写入 + 5 分钟」判会放行一条死链。
        val now = 10_000_000L
        val e = entry(timestamp = now, expiresAtMs = now - 1L)
        assertFalse(PreloadCachePolicy.isFresh(e, "lossless", now))
    }

    @Test
    fun `显式过期时刻为 null 时退回固定 TTL（ncm与 QQ 的行为不变）`() {
        val now = 1_000_000L
        val e = entry(timestamp = now, expiresAtMs = null)
        assertTrue(PreloadCachePolicy.isFresh(e, "lossless", now + 1_000L))
        assertFalse(
            PreloadCachePolicy.isFresh(e, "lossless", now + PreloadCachePolicy.DEFAULT_TTL_MS + 1),
        )
    }

    @Test
    fun `两种模型下档位判据都在最前面`() {
        val now = 5_000_000L
        val explicit = entry(level = "standard", timestamp = now, expiresAtMs = now + 999_999L)
        assertFalse(PreloadCachePolicy.isFresh(explicit, "exhigh", now))
        val fixed = entry(level = "standard", timestamp = now, expiresAtMs = null)
        assertFalse(PreloadCachePolicy.isFresh(fixed, "exhigh", now))
    }
}
