/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.7.38 · 回移自主线 4.1.5：离线兜底的「这条 URL 播不出来」闸门。
 */

package com.takahashirinta.ncrust.cache

import com.takahashirinta.ncrust.player.PlaybackFailure
import com.takahashirinta.ncrust.player.FailureKind
import com.takahashirinta.ncrust.player.classifyFailure
import com.takahashirinta.ncrust.player.poisonsOfflineUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OfflineUrlIndex` 的失败标记 + `poisonsOfflineUrl` 的判据。
 *
 * 这一组钉住的是**同一个固定点**的两端：
 *  · 索引侧：标记过的 key 不许再出现在 `recallRecord` 的候选里；
 *  · 判据侧：哪些失败才算「这条 URL 不能用了」（宁窄勿宽 —— 输出链故障不算）。
 *
 * 都是纯逻辑，不需要设备（`OfflineUrlIndex` 只依赖 Gson 的结构，构造时甚至不读盘）。
 */
class OfflineUrlUsabilityTest {

    private val songId = 4242L
    private val level = "lossless"
    private val key = OfflineKeys.key(songId, level)
    private val url = "http://m704.music.126.net/20260922230000/deadbeef/abc.mp3"

    private fun index() = OfflineUrlIndex()

    // ---------------------------------------------------------------- 索引闸门

    @Test
    fun `标记为不可播之后 recallRecord 不再返回它`() {
        val idx = index()
        idx.put(key, url)
        assertNotNull("没标记时应当命中", idx.recallRecord(listOf(key), songId) { true })

        idx.setPlaybackUsable(key, url, false)
        assertNull(
            "标记过的 key 不许再被离线兜底选中（固定点闸门）",
            idx.recallRecord(listOf(key), songId) { true },
        )
        assertEquals(1, idx.failureCount())
    }

    @Test
    fun `解除标记后恢复正常`() {
        val idx = index()
        idx.put(key, url)
        idx.setPlaybackUsable(key, url, false)
        idx.setPlaybackUsable(key, url, true)
        assertNotNull(idx.recallRecord(listOf(key), songId) { true })
        assertEquals(0, idx.failureCount())
    }

    @Test
    fun `标记必须对上索引里现存的那条 URL——换了一条就不算`() {
        val idx = index()
        idx.put(key, url)
        // 索引里存的是 url，调用方却拿另一条 URL 来标（说明索引已被新 URL 覆盖：
        // 旧结论已经没有意义，忽略它才是对的）。
        idx.setPlaybackUsable(key, "$url?stale=1", false)
        assertEquals("url 对不上就不该打标记", 0, idx.failureCount())
        assertNotNull(idx.recallRecord(listOf(key), songId) { true })
    }

    @Test
    fun `重新解析出同档位的**新** URL 会自动解除标记`() {
        val idx = index()
        idx.put(key, url)
        idx.setPlaybackUsable(key, url, false)
        assertEquals(1, idx.failureCount())

        // 服务端换了签名 ⇒ 同一个 key 写了另一条 URL：上一个 URL 的结论不适用于它。
        idx.put(key, "$url?fresh=1")
        assertEquals("URL 变了就解除", 0, idx.failureCount())
        assertNotNull(idx.recallRecord(listOf(key), songId) { true })
    }

    @Test
    fun `同一条坏链再写一次时**保留**标记`() {
        val idx = index()
        idx.put(key, url)
        idx.setPlaybackUsable(key, url, false)
        // 重新取链仍然拿到同一条（固定点）：必须继续禁，否则就是无限「点了没声音」。
        idx.put(key, url)
        assertEquals(1, idx.failureCount())
        assertNull(idx.recallRecord(listOf(key), songId) { true })
    }

    @Test
    fun `删掉这首歌、清空清单都会把标记一起清掉`() {
        val idx = index()
        idx.put(key, url)
        idx.setPlaybackUsable(key, url, false)
        assertEquals(1, idx.removeSong(songId))
        assertEquals("条目没了，标记也必须跟着没", 0, idx.failureCount())

        idx.put(key, url)
        idx.setPlaybackUsable(key, url, false)
        idx.clear()
        assertEquals(0, idx.failureCount())
        assertEquals(0, idx.size())
    }

    @Test
    fun `条目被 LRU 淘汰时标记跟着走——不给后来的新 URL 留冤案`() {
        // 上限 1：写入第二个条目就会把第一个挤出去。
        val idx = OfflineUrlIndex(maxEntries = 1)
        idx.put(key, url)
        idx.setPlaybackUsable(key, url, false)
        assertEquals(1, idx.failureCount())

        idx.put(OfflineKeys.key(7L, "exhigh"), "http://x/7.mp3")
        assertEquals("上一个条目已被淘汰，它的标记必须一起走", 0, idx.failureCount())
    }

    @Test
    fun `recall 与 recallRecord 走同一道闸`() {
        val idx = index()
        idx.put(key, url)
        idx.setPlaybackUsable(key, url, false)
        // `recall` 是 `recallRecord { true }` 的便捷版，闸门只有一处实现。
        assertNull(idx.recall(listOf(key), songId))
    }

    // ---------------------------------------------------------------- 判据

    private fun failure(code: String, causeClass: String? = null, causeMessage: String? = null) =
        PlaybackFailure(
            errorCode = 0,
            errorCodeName = code,
            causeClass = causeClass,
            causeMessage = causeMessage,
        )

    @Test
    fun `服务端说了不的四类 IO 码 + 解析失败才算「这条 URL 不能用了」`() {
        listOf(
            "ERROR_CODE_IO_BAD_HTTP_STATUS",
            "ERROR_CODE_IO_FILE_NOT_FOUND",
            "ERROR_CODE_IO_NO_PERMISSION",
            "ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE",
            "ERROR_CODE_PARSING_CONTAINER_MALFORMED",
            "ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED",
        ).forEach { code ->
            assertTrue("$code 应当判成 URL 级失败", poisonsOfflineUrl(failure(code)))
        }
    }

    @Test
    fun `输出链故障绝不算——那条 URL 是好的`() {
        // 实测触发者：QQ 的「臻品音质」6 声道 FLAC，ChannelMixingMatrix 没有 6→1 的系数。
        // 把它判成 URL 级失败会把一个完全能播的离线档位摘掉。
        val sink = failure(
            "ERROR_CODE_UNSPECIFIED",
            causeClass = "java.lang.UnsupportedOperationException",
            causeMessage = "Default channel mixing coefficients for 6->1 are not provided.",
        )
        assertEquals(FailureKind.SINK, classifyFailure(sink))
        assertFalse("输出链故障不得摘 URL", poisonsOfflineUrl(sink))
    }

    @Test
    fun `cause 里的 response code 4xx 与 FileNotFoundException 也算`() {
        assertTrue(
            poisonsOfflineUrl(
                failure("ERROR_CODE_UNSPECIFIED", causeMessage = "Response code: 403"),
            )
        )
        assertTrue(
            poisonsOfflineUrl(
                failure("ERROR_CODE_UNSPECIFIED", causeClass = "java.io.FileNotFoundException"),
            )
        )
        assertFalse(
            poisonsOfflineUrl(
                failure("ERROR_CODE_UNSPECIFIED", causeMessage = "Response code: 500"),
            )
        )
    }
}
