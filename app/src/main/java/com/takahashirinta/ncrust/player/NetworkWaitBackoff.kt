/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v4.1.7 · P0（熄屏/后台播放中断）：**网络型失败不是「失败」，是「还没到时候」**。
 */

package com.takahashirinta.ncrust.player

/**
 * 网络型播放失败的**等待重试退避**（纯逻辑，JVM 可直接单测）。
 *
 * ## 它修的是哪一条真机故障
 *
 * 锁定/后台播放时，取链与流式读取会因为 Wi-Fi 省电、路由器抖动、Doze 的按应用网络限制
 * 而短暂失败。这类失败在 media3 里长得和「这条 URL 是死的」一模一样，于是旧实现走的是
 * [UrlRetryGate] 那条**定额**：每首歌最多原样重试 2 次、两次至少隔 3 秒。
 *
 * 真机取证（Droidloom cell / API 37 / 4.1.6-gpl，2026-11-22，屏幕已熄灭，DNS 被切断模拟
 * 「网络短暂不可用」）：
 *
 * ```
 * 10:46:39  SongUrlFetcher: transport failure level=jymaster, retrying once
 * 10:46:47  SongUrlFetcher: transport retry exhausted songId=29307041 level=jymaster
 * 10:47:00  PlaybackService: Playback error songId=29307041 code=ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
 * 10:47:03  playSong（第 1 次定额重试）
 * 10:47:26  playSong（第 2 次定额重试）
 * 10:47:46  PlayerViewModel: quality pipeline gave up … 取链网络失败且重试额度已用尽 — pausing for user action
 * 10:47:46  PlaybackService: onStartCommand action=pause_failed
 * ```
 *
 * 也就是说：**网络断 67 秒就换来一次无声的永久暂停**；之后的恢复完全依赖
 * `ConnectivityManager` 发一次 route/capability 变化（本次实测是 62 秒后 DNS 恢复、
 * 网络重新 validated 才触发），而「Wi-Fi 一直连着但出口短暂不通」这种最常见的形状
 * **永远不会触发那个回调** —— 用户看到的就是「熄屏后自己停了，点一下才能继续」。
 *
 * ## 语义
 *
 *  - 退避是**单调不减**的（2s → 5s → 10s → 20s → 30s，之后固定 30s），退避状态只活在
 *    一次「连续网络故障」里；
 *  - 窗口 [windowMs] 内**不设次数上限**：只要用户没停、没切歌、也没播出声，就一直等；
 *  - 超出窗口才返回 `null`（调用方才转「暂停 + 提示」），所以「一直没网」仍然有界；
 *  - [reset] 的三个调用点写在调用侧：用户手动操作、换歌、以及**真的播出声了**。
 *
 * 时钟用 `SystemClock.elapsedRealtime()`（单调）；即使调用方传进来一个回退的时间戳，
 * 本类也只会**放行**（多试一次）而不会误判成「窗口已过」。
 */
class NetworkWaitBackoff(
    private val stepsMs: List<Long> = DEFAULT_STEPS_MS,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {

    companion object {
        /** 退避阶梯（毫秒）。最后一级重复使用。 */
        val DEFAULT_STEPS_MS: List<Long> = listOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)

        /** 一次「连续网络故障」最多等多久。超过就交给用户（与旧行为一致）。 */
        const val DEFAULT_WINDOW_MS: Long = 10L * 60_000L
    }

    /** 本轮窗口的起点；`-1` = 不在等待。 */
    private var windowStartedAtMs: Long = -1L
    private var attempt: Int = 0

    /** 上一次给出的退避值（诊断用，`-1` = 还没给过）。 */
    var lastDelayMs: Long = -1L
        private set

    /** 是否正处在「连续网络故障」的等待窗口里。 */
    fun isWaiting(): Boolean = windowStartedAtMs >= 0L

    /** 已经等了多久（诊断 / 日志用）。 */
    fun waitedMs(nowMs: Long): Long =
        if (windowStartedAtMs < 0L) 0L else (nowMs - windowStartedAtMs).coerceAtLeast(0L)

    /** 本轮结束（用户操作 / 换歌 / 播出声）：下一次网络故障从最小退避重新开始。 */
    fun reset() {
        windowStartedAtMs = -1L
        attempt = 0
        lastDelayMs = -1L
    }

    /**
     * 申请下一次重试的等待时间。
     *
     * @param nowMs 单调时钟（`SystemClock.elapsedRealtime()`）。
     * @return 还要等多久（毫秒）；`null` = 已超出 [windowMs]，调用方必须转「暂停 + 提示」。
     */
    fun nextDelay(nowMs: Long): Long? {
        if (windowStartedAtMs < 0L) {
            windowStartedAtMs = nowMs
            attempt = 0
        }
        // 时钟回退时 nowMs - windowStartedAtMs 为负 ⇒ 判成「窗口内」，保守放行。
        if (nowMs - windowStartedAtMs >= windowMs) return null
        val step = stepsMs[minOf(attempt, stepsMs.lastIndex)]
        attempt++
        lastDelayMs = step
        return step
    }
}
