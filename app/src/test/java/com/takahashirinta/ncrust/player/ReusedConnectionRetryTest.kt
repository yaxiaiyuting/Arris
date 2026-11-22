package com.takahashirinta.ncrust.player

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.1.10 · A：**复用连接上的传输失败要不要换新连接重试**。
 *
 * 真机证据（vc242，00:42，见报告）：
 * `path=/eapi/v3/song/detail … fam=v4 reused=true msg=stream was reset: CANCEL`
 * —— 失败确实发生在复用连接上，但形态是流级复位，不是死连接。
 * 所以决策不能是「reused 就重试」，必须把形态一起看。
 */
class ReusedConnectionRetryTest {

    private val reset = IOException("stream was reset: CANCEL")
    private val refused = IOException("stream was reset: REFUSED_STREAM")
    private val timeout = SocketTimeoutException("timeout")
    private val dead = SocketException("Connection reset")

    @Test
    fun `复用连接上的失败一律换新连接重试一次`() {
        assertTrue(ReusedConnectionRetry.shouldRetryOnFreshConnection(reset, reused = true, alreadyRetried = false))
        assertTrue(ReusedConnectionRetry.shouldRetryOnFreshConnection(dead, reused = true, alreadyRetried = false))
        assertTrue(ReusedConnectionRetry.shouldRetryOnFreshConnection(timeout, reused = true, alreadyRetried = false))
    }

    @Test
    fun `新连接上的失败不重试（不是复用的问题）`() {
        assertFalse(ReusedConnectionRetry.shouldRetryOnFreshConnection(reset, reused = false, alreadyRetried = false))
        assertFalse(ReusedConnectionRetry.shouldRetryOnFreshConnection(dead, reused = false, alreadyRetried = false))
    }

    @Test
    fun `拿不到 reused 证据时只对流级复位放行`() {
        assertTrue(ReusedConnectionRetry.shouldRetryOnFreshConnection(reset, reused = null, alreadyRetried = false))
        assertTrue(ReusedConnectionRetry.shouldRetryOnFreshConnection(refused, reused = null, alreadyRetried = false))
        assertFalse(
            "没有证据就不额外加请求（超时属于地址族/网络问题，不是复用问题）",
            ReusedConnectionRetry.shouldRetryOnFreshConnection(timeout, reused = null, alreadyRetried = false),
        )
        assertFalse(ReusedConnectionRetry.shouldRetryOnFreshConnection(dead, reused = null, alreadyRetried = false))
    }

    @Test
    fun `重试过一次就不再重试`() {
        assertFalse(ReusedConnectionRetry.shouldRetryOnFreshConnection(reset, reused = true, alreadyRetried = true))
        assertFalse(ReusedConnectionRetry.shouldRetryOnFreshConnection(reset, reused = null, alreadyRetried = true))
    }

    @Test
    fun `没有异常时不重试`() {
        assertFalse(ReusedConnectionRetry.shouldRetryOnFreshConnection(null, reused = true, alreadyRetried = false))
    }
}
