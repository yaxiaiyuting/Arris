/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.viewmodel

import com.takahashirinta.ncrust.player.PlaybackFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.1.5：[poisonsOfflineUrl] —— 「这次播放失败能不能给这条 URL 判死刑」。
 *
 * 这条判据的两侧都真实存在，而且**方向必须保守**：
 *
 *  · 判得太松（把网络抖动、解码失败也算进去）⇒ 地铁里听一次歌，这个档位的离线兜底就
 *    整个进程内不可用，用户看到的是「明明下过离线，却一直在转圈」；
 *  · 判得太紧（403 过期也不摘）⇒ 离线兜底把同一条死链反复喂回播放器，就是
 *    「点了播放没声音」的那个固定点。
 *
 * 所以每个用例都写清了它属于哪一侧、以及为什么。
 */
class OfflineUrlPoisonGateTest {

    private fun failure(
        code: String,
        causeClass: String? = null,
        causeMessage: String? = null,
    ) = PlaybackFailure(
        errorCode = 0,
        errorCodeName = code,
        causeClass = causeClass,
        causeMessage = causeMessage,
    )

    // ── 该摘：服务端拒绝这条 URL / 字节不是音频 ──────────────────────────────────

    @Test
    fun `HTTP 状态码不是 2xx —— 服务端明确拒绝，摘掉`() {
        assertTrue(poisonsOfflineUrl(failure("ERROR_CODE_IO_BAD_HTTP_STATUS")))
    }

    @Test
    fun `404 文件不在、403 无权限——摘掉`() {
        assertTrue(poisonsOfflineUrl(failure("ERROR_CODE_IO_FILE_NOT_FOUND")))
        assertTrue(poisonsOfflineUrl(failure("ERROR_CODE_IO_NO_PERMISSION")))
    }

    @Test
    fun `响应不是音频——摘掉（302 到 404 HTML 就是这个形状）`() {
        assertTrue(poisonsOfflineUrl(failure("ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE")))
    }

    @Test
    fun `容器与清单解析失败——字节本身坏了，摘掉`() {
        assertTrue(poisonsOfflineUrl(failure("ERROR_CODE_PARSING_CONTAINER_MALFORMED")))
        assertTrue(poisonsOfflineUrl(failure("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED")))
    }

    @Test
    fun `媒体3只留下 root cause 的 4xx——照样摘掉`() {
        // 实测常见形状：errorCode 仍是 IO_UNSPECIFIED，真实原因只在 message 里。
        assertTrue(
            poisonsOfflineUrl(
                failure(
                    "ERROR_CODE_IO_UNSPECIFIED",
                    "com.google.android.exoplayer2.upstream.HttpDataSource\$InvalidResponseCodeException",
                    "Response code: 403",
                ),
            ),
        )
        assertTrue(
            poisonsOfflineUrl(
                failure("ERROR_CODE_IO_UNSPECIFIED", "java.io.FileNotFoundException", "no such file"),
            ),
        )
    }

    // ── 不该摘：网络抖动 / 设备解码 / 输出链 ────────────────────────────────────

    @Test
    fun `网络连不上、超时、IO 未分类——URL 是好的，绝不摘`() {
        assertFalse(poisonsOfflineUrl(failure("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")))
        assertFalse(poisonsOfflineUrl(failure("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT")))
        assertFalse(poisonsOfflineUrl(failure("ERROR_CODE_IO_UNSPECIFIED")))
        assertFalse(poisonsOfflineUrl(failure("ERROR_CODE_TIMEOUT")))
    }

    @Test
    fun `服务端 5xx——临时故障，不构成对一个文件的判决`() {
        assertFalse(
            poisonsOfflineUrl(
                failure(
                    "ERROR_CODE_IO_UNSPECIFIED",
                    "com.google.android.exoplayer2.upstream.HttpDataSource\$InvalidResponseCodeException",
                    "Response code: 503",
                ),
            ),
        )
    }

    @Test
    fun `读位置越界——可能是服务端提前掐了连接，不摘`() {
        assertFalse(poisonsOfflineUrl(failure("ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE")))
    }

    @Test
    fun `设备解码不了——文件是好的，摘掉会连带藏掉一份能播的离线字节`() {
        assertFalse(poisonsOfflineUrl(failure("ERROR_CODE_DECODING_FORMAT_UNSUPPORTED")))
        assertFalse(poisonsOfflineUrl(failure("ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES")))
        assertFalse(poisonsOfflineUrl(failure("ERROR_CODE_DECODING_FAILED")))
        assertFalse(poisonsOfflineUrl(failure("ERROR_CODE_DECODER_INIT_FAILED")))
    }

    @Test
    fun `音频输出链故障——坏的是 sink，不是文件`() {
        // `PlaybackService.failureOf(Exception)` 的形状：6 声道 FLAC 的声道矩阵异常。
        assertFalse(
            poisonsOfflineUrl(
                failure(
                    "AUDIO_SINK_ERROR",
                    "java.lang.UnsupportedOperationException",
                    "Default channel mixing coefficients for 6->1 are not implemented",
                ),
            ),
        )
    }

    @Test
    fun `完全读不懂的失败——不猜`() {
        assertFalse(poisonsOfflineUrl(failure("ERROR_CODE_UNSPECIFIED")))
        assertFalse(poisonsOfflineUrl(failure("ERROR_CODE_REMOTE_ERROR")))
    }

    @Test
    fun `错误码大小写不影响判定`() {
        assertTrue(poisonsOfflineUrl(failure("error_code_io_bad_http_status")))
        assertFalse(poisonsOfflineUrl(failure("error_code_io_network_connection_failed")))
    }
}
