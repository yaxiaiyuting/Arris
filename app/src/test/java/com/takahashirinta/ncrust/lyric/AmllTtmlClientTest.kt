/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.9.0：AMLL TTML 拉取客户端的单测。
 *
 * 全部通过假 [TtmlFetcher]（[FakeFetcher]）驱动，**一个真实网络请求都不发**。
 * 覆盖的每条行为都来自 2026-09 curl 实测的镜像语义（见 [AmllTtmlClient] 的 KDoc），不是文档推断。
 */
class AmllTtmlClientTest {

    /** 记录调用顺序的假取数器：既能断言「试了哪几面镜像」，也能断言「有没有多试」。 */
    private class FakeFetcher(private val handler: (String) -> TtmlHttpResult) : TtmlFetcher {
        val calls = mutableListOf<String>()
        override suspend fun get(url: String): TtmlHttpResult {
            calls += url
            return handler(url)
        }
    }

    private val songId = 5257138L
    private val urls = AmllTtmlClient.urlsFor(songId)
    private val tt = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body/></tt>"

    // ---------- URL 构造 ----------

    @Test
    fun `URL 构造——四个镜像模板都按 songId 拼对`() {
        assertEquals(4, urls.size)
        assertEquals("https://amlldb.bikonoo.com/ncm-lyrics/5257138.ttml", urls[0])
        assertEquals(
            "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/ncm-lyrics/5257138.ttml",
            urls[1]
        )
        assertEquals("https://amlldb.bikonoo.com/lyrics/ncm-lyrics/5257138.ttml", urls[2])
        // v1.9.1：jsdelivr 从第 3 位挪到最后 —— 它有 50 MB 单包上限，对存在文件也会 403，
        // 只能当兜底，不能让它排在权威镜像前面抢答。
        assertEquals(
            "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/ncm-lyrics/5257138.ttml",
            urls[3]
        )
        // 每面镜像都必须带上 id，且都是 .ttml
        assertTrue(urls.all { it.contains("5257138") && it.endsWith(".ttml") })
    }

    @Test
    fun `URL 构造——镜像名固定且主镜像排第一（回退顺序 = 表顺序）`() {
        // v1.9.1 顺序：三面权威直出在前，非权威的 jsdelivr 兜底在最后。
        assertEquals(listOf("amlldb", "github-raw", "amlldb-alt", "jsdelivr"), AmllTtmlClient.mirrors.map { it.name })
        assertTrue(AmllTtmlClient.mirrors.first().template.startsWith("https://amlldb.bikonoo.com/ncm-lyrics/"))
    }

    @Test
    fun `URL 构造——不同 songId 各自成 URL（不串号）`() {
        assertEquals(
            "https://amlldb.bikonoo.com/ncm-lyrics/287035.ttml",
            AmllTtmlClient.urlsFor(287035L)[0]
        )
    }

    // ---------- 命中 ----------

    @Test
    fun `命中——主镜像 200 且 body 以 tt 开头时返回原文，且只打一面镜像`() = runBlocking {
        val fake = FakeFetcher { TtmlHttpResult(200, tt) }
        assertEquals(tt, AmllTtmlClient.fetch(songId, fake))
        assertEquals(listOf(urls[0]), fake.calls)
    }

    @Test
    fun `命中——body 前面有换行或空白也算 TTML`() = runBlocking {
        val padded = "\n\n  " + tt
        val fake = FakeFetcher { TtmlHttpResult(200, padded) }
        assertEquals(padded, AmllTtmlClient.fetch(songId, fake))
        assertEquals(1, fake.calls.size)
    }

    // ---------- 「这首歌没有 TTML」的两种形态：都不回退 ----------

    @Test
    fun `404——判定为无此歌词且不再试下一个镜像（主镜像 302 跟随后就是 404）`() = runBlocking {
        // 备用镜像此刻是「有」的：如果实现仍然回退，就会返回 tt —— 用它证明回退真的被省掉了。
        val fake = FakeFetcher { url ->
            if (url == urls[0]) TtmlHttpResult(404, "歌词不存在") else TtmlHttpResult(200, tt)
        }
        assertNull(AmllTtmlClient.fetch(songId, fake))
        assertEquals("404 后必须立刻收工，不能白试备用镜像", listOf(urls[0]), fake.calls)
    }

    @Test
    fun `200 但 body 不是 TTML——同样判无此歌词且不回退`() = runBlocking {
        // 实测风险：CDN/代理层用 200 返回 HTML 错误页。判据是「去掉前导空白后以 <tt 开头」。
        val fake = FakeFetcher { url ->
            if (url == urls[0]) TtmlHttpResult(200, "<!DOCTYPE html><html>Not Found</html>")
            else TtmlHttpResult(200, tt)
        }
        assertNull(AmllTtmlClient.fetch(songId, fake))
        assertEquals(listOf(urls[0]), fake.calls)
    }

    @Test
    fun `200 但 body 为空——判无此歌词`() = runBlocking {
        val fake = FakeFetcher { TtmlHttpResult(200, "") }
        assertNull(AmllTtmlClient.fetch(songId, fake))
        assertEquals(1, fake.calls.size)
    }

    @Test
    fun `网络异常后遇到 404——同样立即收工`() = runBlocking {
        val fake = FakeFetcher { url ->
            when (url) {
                urls[0] -> TtmlHttpResult(AmllTtmlClient.NETWORK_ERROR, null)
                urls[1] -> TtmlHttpResult(404, "歌词不存在")
                else -> TtmlHttpResult(200, tt)
            }
        }
        assertNull(AmllTtmlClient.fetch(songId, fake))
        assertEquals(listOf(urls[0], urls[1]), fake.calls)
    }

    // ---------- 回退 ----------

    @Test
    fun `网络异常——回退到下一面镜像并返回命中结果`() = runBlocking {
        val fake = FakeFetcher { url ->
            if (url == urls[0]) TtmlHttpResult(AmllTtmlClient.NETWORK_ERROR, null) else TtmlHttpResult(200, tt)
        }
        assertEquals(tt, AmllTtmlClient.fetch(songId, fake))
        assertEquals("第一面失败后应立即试第二面", listOf(urls[0], urls[1]), fake.calls)
    }

    @Test
    fun `非 200（500）——回退到备用镜像`() = runBlocking {
        val fake = FakeFetcher { url ->
            if (url == urls[0]) TtmlHttpResult(500, "Internal Server Error") else TtmlHttpResult(200, tt)
        }
        assertEquals(tt, AmllTtmlClient.fetch(songId, fake))
        assertEquals(listOf(urls[0], urls[1]), fake.calls)
    }

    @Test
    fun `取数实现抛异常——按网络异常处理并回退，不把异常抛给调用方`() = runBlocking {
        // 生产实现自己吞异常，但接口是公开的：任何 TtmlFetcher 实现抛出都不该炸到播放路径。
        val fake = FakeFetcher { url ->
            if (url == urls[0]) throw java.io.IOException("boom") else TtmlHttpResult(200, tt)
        }
        assertEquals(tt, AmllTtmlClient.fetch(songId, fake))
        assertEquals(listOf(urls[0], urls[1]), fake.calls)
    }

    // ---------- 全部失败 ----------

    @Test
    fun `全部镜像网络异常——返回 null，每面只试一次（不重试）`() = runBlocking {
        val fake = FakeFetcher { TtmlHttpResult(AmllTtmlClient.NETWORK_ERROR, null) }
        assertNull(AmllTtmlClient.fetch(songId, fake))
        assertEquals("四面镜像各试一次，顺序固定，不重复", urls, fake.calls)
    }

    @Test
    fun `全部镜像 500——返回 null`() = runBlocking {
        val fake = FakeFetcher { TtmlHttpResult(503, "unavailable") }
        assertNull(AmllTtmlClient.fetch(songId, fake))
        assertEquals(urls, fake.calls)
    }

    @Test
    fun `网络异常码约定——TtmlHttpResult 负 code 表示没拿到响应`() {
        assertEquals(-1, AmllTtmlClient.NETWORK_ERROR)
        assertNull(TtmlHttpResult(AmllTtmlClient.NETWORK_ERROR, null).body)
    }

    // ---------- v1.9.1 修正：权威 / 非权威镜像的 404 语义不同 ----------

    /**
     * **v1.9.0 真实缺陷的回归测试**（独立验证者发现，抽样 60 个 DB 内 ID 有 4 个能复现）。
     *
     * jsdelivr 有自己的**单包 50 MB 上限**，对**确实存在**的文件也会返回
     * `403 Package size exceeded the configured limit of 50 MB` 或偶发 404。
     * v1.9.0 的实现对**任一**镜像的 404 都立刻 `return null`，于是当权威镜像同时网络抖动、
     * 而 jsdelivr 又对存在文件报 404 时，后面的镜像**再也没机会被请求**，这首歌静默丢掉 TTML。
     *
     * 这里构造最坏情形：前三面（权威）全部网络异常 + 最后一面（jsdelivr）404
     * ⇒ 必须把**四面都试完**，而不是在第 4 面走「无此歌词」提前收工。
     * （第 4 面本来就是最后一面，所以用「调用次数 == 4」来证明它没有触发提前返回路径。）
     */
    @Test
    fun `非权威镜像的 404 不判「无此歌词」——必须继续试完（v190 缺陷回归）`() = runBlocking {
        val fake = FakeFetcher { url ->
            if (url == urls[3]) TtmlHttpResult(404, "Package size exceeded the configured limit of 50 MB")
            else TtmlHttpResult(AmllTtmlClient.NETWORK_ERROR, null)
        }
        assertNull(AmllTtmlClient.fetch(songId, fake))
        assertEquals("非权威镜像的 404 不该终结回退链", urls, fake.calls)
    }

    /** 非权威镜像的 403 同样只当「这面服务不了」，不能判「无此歌词」。 */
    @Test
    fun `非权威镜像的 403 当作镜像不可用，不判无此歌词`() = runBlocking {
        val fake = FakeFetcher { url ->
            if (url == urls[3]) TtmlHttpResult(403, "Package size exceeded the configured limit of 50 MB")
            else TtmlHttpResult(AmllTtmlClient.NETWORK_ERROR, null)
        }
        assertNull(AmllTtmlClient.fetch(songId, fake))
        assertEquals(urls, fake.calls)
    }

    /** 非权威镜像用 200 返回一个非 TTML 的 body 时，同样不能判「无此歌词」。 */
    @Test
    fun `非权威镜像的 200-非TTML 不判无此歌词`() = runBlocking {
        val fake = FakeFetcher { url ->
            if (url == urls[3]) TtmlHttpResult(200, "<html>error</html>")
            else TtmlHttpResult(AmllTtmlClient.NETWORK_ERROR, null)
        }
        assertNull(AmllTtmlClient.fetch(songId, fake))
        assertEquals(urls, fake.calls)
    }

    /** 权威镜像仍然保留「404 = 确无歌词 ⇒ 立即收工」的省流量行为（不能被上面几条改坏）。 */
    @Test
    fun `权威镜像的 404 仍然立即收工——不因为修正而丢掉省流量`() = runBlocking {
        for (idx in 0..2) {
            // 构造必须让**前面的镜像失败**，否则第 1 面就命中 tt 了，测不到 idx 那一面。
            val fake = FakeFetcher { url ->
                val i = urls.indexOf(url)
                when {
                    i < idx -> TtmlHttpResult(AmllTtmlClient.NETWORK_ERROR, null)
                    i == idx -> TtmlHttpResult(404, "歌词不存在")
                    else -> TtmlHttpResult(200, tt)
                }
            }
            assertNull(AmllTtmlClient.fetch(songId, fake))
            assertEquals("第 ${idx + 1} 面（权威）404 后必须立刻收工", urls.take(idx + 1), fake.calls)
        }
    }

    /**
     * 前置约定：镜像表里**恰好 3 面权威、最后一面非权威**，且 jsdelivr 排在最后。
     * 顺序本身是策略的一部分（常见情形在第 1 面就终结，jsdelivr 抽风不影响主路径）。
     */
    @Test
    fun `镜像表约定——前 3 面权威、jsdelivr 非权威且排最后`() {
        val m = AmllTtmlClient.mirrors
        assertEquals(4, m.size)
        assertEquals(listOf("amlldb", "github-raw", "amlldb-alt", "jsdelivr"), m.map { it.name })
        assertEquals(listOf(true, true, true, false), m.map { it.authoritative })
    }
}
