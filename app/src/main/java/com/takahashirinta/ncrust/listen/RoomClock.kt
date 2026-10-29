/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.listen

/**
 * 时钟对齐与目标位置计算（v3.8.0 · 一起听）。**纯逻辑，JVM 可单测。**
 *
 * ## 为什么必须先对齐钟
 *
 * 房间广播的是「**在 `stampedAtMs` 这个时刻**，这首歌在 `positionMs`」。成员收到时已经过去
 * 若干毫秒，必须补上。有两种等价写法，**但只能选一种**，混用就系统性偏一个单程延迟：
 *
 * - 写法 A（对齐后）：`target = positionMs + (relayNow − stampedAtMs)`
 * - 写法 B（未对齐）：`target = positionMs + (now − stampedAtMs) − oneWayDelay`
 *
 * 本实现走 **A**：`relayNow = localNow + offset`，其中 `offset` 由 [estimateOffset] 用
 * NTP-lite（最小 RTT 样本）估出来，延迟已经被 offset 吸收。**公式里不再出现 `− 单程延迟`。**
 * （调研报告 §3.3.1 用 ε 推导了两种前提，这是最容易被实现者写错的一处。）
 */
object RoomClock {

    /** 一次请求/响应对（毫秒，全部是本机单调时钟）。 */
    data class Sample(val sentAtMs: Long, val receivedAtMs: Long, val relayNowAtReplyMs: Long) {
        val rttMs: Long get() = receivedAtMs - sentAtMs
    }

    data class Estimate(
        /** `relayNow ≈ localNow + offsetMs` */
        val offsetMs: Long,
        /** 参与估计的最小 RTT；RTT 越小，offset 越可信。 */
        val rttMs: Long,
        val samples: Int,
    )

    /**
     * NTP-lite：取 **RTT 最小的样本**算 offset（不取平均）。
     *
     * 平均会被一两个拥塞样本污染；最小 RTT 样本最接近"没有排队"的真实路径，
     * 这也是 CoJam（`apps/web/lib/clockSync.ts`）的做法。
     *
     * @return 无样本时 null（调用方应保持"未对齐"并拒绝做微调）
     */
    fun estimateOffset(samples: List<Sample>): Estimate? {
        val best = samples.minByOrNull { it.rttMs } ?: return null
        // 假设往返对称：单程 = rtt/2，于是 relay 时刻 ≈ (发出 + 收到)/2 + 单程 = 收到 - rtt/2
        val localMid = (best.sentAtMs + best.receivedAtMs) / 2
        val offset = best.relayNowAtReplyMs - localMid
        return Estimate(offsetMs = offset, rttMs = best.rttMs, samples = samples.size)
    }

    /**
     * 成员此刻应该处在的位置。
     *
     * @param estimate null = 还没对齐 ⇒ 只能用本机时钟粗算（首帧允许，之后必须丢弃，见 [DriftPolicy]）
     */
    fun targetPositionMs(
        transport: RoomTransport,
        stampedAtMs: Long,
        localNowMs: Long,
        estimate: Estimate?,
        durationMs: Long,
    ): Long {
        val relayNow = if (estimate == null) localNowMs else localNowMs + estimate.offsetMs
        val elapsed = (relayNow - stampedAtMs).coerceAtLeast(0L)
        val raw = if (transport.playing) {
            transport.positionMs + (elapsed * transport.rate).toLong()
        } else {
            // 暂停态：位置就是广播的那个值，不随时间推进（但允许它被 stamp 更新覆盖）
            transport.positionMs
        }
        return raw.coerceIn(0L, durationMs.coerceAtLeast(0L))
    }
}
