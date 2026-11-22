package com.takahashirinta.ncrust.network

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.1.10 · C3：`RST_STREAM` 的分类与「见过复位就用 h1」的记忆。
 *
 * 分类规则只认 message：release 包里 OkHttp 的 `StreamResetException` 会被 R8 改名
 * （实测日志里 `failed=C`），类名不可依赖。
 */
class StreamResetMemoTest {

    private val host = "interface3.music.163.com"

    @Test
    fun `流级复位的两种形态都被识别`() {
        assertTrue(StreamResetMemo.isStreamReset(IOException("stream was reset: REFUSED_STREAM")))
        assertTrue(StreamResetMemo.isStreamReset(IOException("stream was reset: CANCEL")))
        assertTrue(StreamResetMemo.isStreamReset(IOException("stream was reset: INTERNAL_ERROR")))
        assertTrue("裸文案也要认（旧实现的判据）", StreamResetMemo.isStreamReset(IOException("REFUSED_STREAM")))
    }

    @Test
    fun `普通传输失败不是流级复位`() {
        assertFalse(StreamResetMemo.isStreamReset(IOException("timeout")))
        assertFalse(StreamResetMemo.isStreamReset(SocketTimeoutException("connect timed out")))
        assertFalse(StreamResetMemo.isStreamReset(IOException("Canceled")))
        assertFalse(StreamResetMemo.isStreamReset(IOException("Unable to resolve host \"x\": No address")))
        assertFalse(StreamResetMemo.isStreamReset(null))
    }

    @Test
    fun `见过复位之后该 host 优先走 h1，其他 host 不受影响`() {
        StreamResetMemo.clear()
        assertFalse("没学过就别预判", StreamResetMemo.prefersHttp1(host, 1_000L))
        StreamResetMemo.record(host, 1_000L)
        assertTrue(StreamResetMemo.prefersHttp1(host, 1_000L))
        assertTrue(StreamResetMemo.prefersHttp1(host, 1_000L + StreamResetMemo.TTL_MS))
        assertFalse("另一个 host 不该被连坐", StreamResetMemo.prefersHttp1("interface.music.163.com", 1_000L))
    }

    @Test
    fun `TTL 到点回到 h2`() {
        StreamResetMemo.clear()
        StreamResetMemo.record(host, 1_000L)
        assertFalse(
            "5 分钟之后应回到 h2（v3.7.6 撤过全局强制 h1，这里只做限时偏置）",
            StreamResetMemo.prefersHttp1(host, 1_000L + StreamResetMemo.TTL_MS + 1L),
        )
        assertEquals("过期的记录会被顺手清掉", 0, StreamResetMemo.size())
    }

    @Test
    fun `换网清空记忆`() {
        StreamResetMemo.clear()
        StreamResetMemo.record(host, 1_000L)
        assertEquals(1, StreamResetMemo.size())
        StreamResetMemo.clear()
        assertFalse(StreamResetMemo.prefersHttp1(host, 1_000L))
    }

    @Test
    fun `空 host 不入表`() {
        StreamResetMemo.clear()
        StreamResetMemo.record("", 1_000L)
        assertEquals(0, StreamResetMemo.size())
    }
}
