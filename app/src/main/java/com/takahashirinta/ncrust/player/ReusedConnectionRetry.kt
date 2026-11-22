/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.network.StreamResetMemo

/**
 * v4.1.10 · A：**复用连接上的传输失败该不该换一条新连接重试**（纯函数，JVM 单测）。
 *
 * ## 证据（PCL110 / vc242，2026-11-23 00:42，见报告）
 *
 * 熄屏 + 手动切 Wi-Fi 之后，`/eapi/v3/song/detail` 出现过这种失败行：
 *
 * ```
 * path=/eapi/v3/song/detail failed=<StreamResetException> host=interface.music.163.com
 *      fam=v4 reused=true msg=stream was reset: CANCEL
 * ```
 *
 * 即：失败确实发生在**复用**的连接上（`reused=true`），但形态是**流级复位**，
 * 不是「SocketException: 连接已死」。这与「熄屏后复用死连接」的假设只对了一半：
 * 复用连接会出问题，但问题在流一级，连接本身还能用（所以 h1/换流重试就能救回来）。
 *
 * ## 决策表（为什么不是「reused 就重试」）
 *
 * | reused | 失败形态 | 重试？ | 理由 |
 * |---|---|---|---|
 * | `false` | 任意 | **否** | 新连接也失败 ⇒ 不是复用的问题，重试只是多打一次服务端 |
 * | `true` | 流级复位 | **是** | 连接是复用的、复位来自对端 ⇒ 换一条新连接才有机会 |
 * | `true` | 其他 IO | **是** | 复用连接最常见的死法就是写进去没回应（SocketException/EOF） |
 * | `null`（拿不到证据） | 流级复位 | **是** | 复位本身已足以说明这条流没救，且复位是**可重试**的传输错误 |
 * | `null` | 其他 IO | **否** | 没有证据就不额外加请求（有界重试的上层仍在） |
 * | 任意 | 已经重试过 | **否** | 铁律 5：失败处理必须有界，一次为限 |
 */
internal object ReusedConnectionRetry {

    /**
     * @param failure 本次传输失败。
     * @param reused 本次失败是否发生在**复用**的连接上；`null` = 拿不到这个证据。
     * @param alreadyRetried 本档位是否已经重试过一次。
     */
    fun shouldRetryOnFreshConnection(failure: Throwable?, reused: Boolean?, alreadyRetried: Boolean): Boolean {
        if (alreadyRetried) return false
        if (failure == null) return false
        val reset = StreamResetMemo.isStreamReset(failure)
        return when (reused) {
            false -> false
            true -> true
            // 拿不到 reused 时只对「流级复位」放行：它本身就是可重试的传输错误，
            // 且换连接是唯一能改变结果的动作。普通超时不在此列（那是地址族/网络的问题）。
            null -> reset
        }
    }
}
