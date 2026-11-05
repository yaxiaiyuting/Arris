/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏写入的业务码判定（**3.7.31 回移主线 4.0.11**）。
 *
 * `PlaylistApi.isLikeWriteAccepted` 的 KDoc 写的是「纯函数，JVM 单测锁定」——
 * 这个文件就是那把锁。判据本身只有一行，但它决定「已收藏的歌会不会被反手取消」：
 * 502 被判成失败 ⇒ 掉进 weapi 老通道 ⇒ 可能触发风控 524「当前环境异常，已取消喜欢」。
 */
class LikeWriteCodeTest {

    /** 200 = 成功；502 = 已在期望状态（幂等），都必须当成功。 */
    @Test
    fun `200 and 502 are accepted`() {
        assertTrue(PlaylistApi.isLikeWriteAccepted(200))
        assertTrue(PlaylistApi.isLikeWriteAccepted(502))
    }

    /**
     * 其余一律拒绝 —— 尤其是老通道会遇到的 301（未登录）/ 405（限流）/ 524（风控取消）。
     * 把它们当成功会让界面显示"已收藏"而服务端什么都没发生。
     */
    @Test
    fun `other codes are rejected`() {
        listOf(-1, 0, 301, 400, 403, 405, 501, 503, 524).forEach {
            assertFalse("code=$it 不该被判成收藏成功", PlaylistApi.isLikeWriteAccepted(it))
        }
    }
}
