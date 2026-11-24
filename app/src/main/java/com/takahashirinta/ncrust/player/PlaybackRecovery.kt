/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v4.2.0：**服务侧的最后一道复活判据**（纯逻辑，JVM 可单测）。
 */

package com.takahashirinta.ncrust.player

/**
 * 播放「在后台死掉」之后的复活判据。
 *
 * ## 为什么需要它（真机现场）
 *
 * 已有的两条恢复路径都**有次数上限**：
 *
 * 1. 服务侧 [PlaybackService.reprepareStalledItem]：同一曲目、同一播放代次只试一次
 *    （`stop → seekTo → prepare`，不换 URL）；
 * 2. UI 侧 `PlayerViewModel.recoverCurrentRequest`：走 `urlRetryGate` 的预算，用尽就
 *    `stopForQualityFailure` 把播放**停掉**，日志写「缓冲恢复次数已用尽」。
 *
 * 于是「网络抖了两三分钟」这种完全会自愈的场景，在熄屏后台会停在最后一步：
 * 用户半小时后解锁，看到的是「播放器开着、歌没在放、也没报错」。而这类失败里
 * **URL 过期**（网易的签名 URL 只有 20 分钟）与**CDN 长连接被中间设备掐掉**占大头 ——
 * 两者都只能靠「重新取一次链」解决，就地 `prepare()` 播的还是那条死 URL。
 *
 * 所以这里给出一条**没有次数上限、只按时间退避**的判据：只要用户还有播放意图，
 * 就每隔一段时间再试一次重新取链 + 从断点续播，直到真的放出声为止。
 *
 * ## 为什么不设「最多 N 次」
 *
 * 次数上限解决的是「别把用户的流量打光」，而这件事在本仓库已经由两层管住：
 * 取链本身有 8 秒连接超时与 3 分钟地址族冷却（v4.1.10），退避表最长也就 3 分钟一次。
 * 反过来，设一个硬上限就会重演上面那个现场：网络三分钟后好了，而播放器已经放弃了。
 * **「放弃」只在一种情况下发生 —— 用户自己按了暂停或清了队列**，那是 [shouldResurrect]
 * 里 `hasIntent` 的事，不是次数的事。
 *
 * ## 「死掉了」怎么判
 *
 * 三个条件同时成立才算：
 * - **还有播放意图**（`playWhenReady` 且队列里还有东西）—— 用户暂停/清队列时这条立刻不成立，
 *   所以它同时也是「绝不擅自重播用户已经停下的事」的闸门；
 * - **没在出声**（`isPlaying == false`）；
 * - **这个状态已经持续 [DEAD_AFTER_MS]**，且期间位置没有前进（由调用方把
 *   「位置前进」当作重置信号喂进来，见 [nextDeadSinceMs]）。
 *
 * 12 秒的卡顿判据（[PlaybackStallDetector.DEFAULT_STALL_MS]）比这个更早触发，
 * 所以两条判据不会抢同一现场：先是「卡顿自愈」，它救不回来才落到这里。
 */
object PlaybackRecovery {

    /**
     * 「没在出声」持续多久算死。
     *
     * 15 秒的来历：卡顿判据是 12 秒，服务侧就地恢复（`stop → seekTo → prepare`）在真机上
     * 通常 1–3 秒内出结论；再留一拍（2 秒采样 × 1）才宣告「这条 URL 真的不行了」。
     * 再短会与卡顿自愈抢跑，再长则用户已经能明显感觉到「卡住了」。
     */
    const val DEAD_AFTER_MS = 15_000L

    /** 第一次复活尝试前的等待（= 判死阈值，保留常量语义，便于单测直接引用）。 */
    const val FIRST_ATTEMPT_DELAY_MS = DEAD_AFTER_MS

    /**
     * 复活的**总窗口**：从「第一次因为失败而停下」起算，最多试 30 分钟。
     *
     * 为什么需要一个窗口而退避不设上限：退避管的是「多勤快」，窗口管的是「还算不算同一件事」。
     * 用户在熄屏时网络断了半小时以上，多半已经不需要我们替他续播了（他到地方了、换设备了）；
     * 而一次真实的抖动（地铁过隧道、电梯）通常在几分钟内结束，30 分钟的窗口绰绰有余。
     * 窗口在真的放出声音、或者用户自己操作播放器时清零。
     */
    const val RETRY_WINDOW_MS = 30 * 60_000L

    /** 当前是否还在复活窗口内。[windowStartMs] 为 0 表示窗口还没开始（= 在窗口内）。 */
    fun withinRetryWindow(nowMs: Long, windowStartMs: Long): Boolean =
        windowStartMs == 0L || nowMs - windowStartMs <= RETRY_WINDOW_MS

    /** 退避表的步长：15s → 30s → 60s → 120s → 180s（封顶）。 */
    private val BACKOFF_STEPS_MS = longArrayOf(
        DEAD_AFTER_MS,
        30_000L,
        60_000L,
        120_000L,
        180_000L,
    )

    /**
     * 第 [attempt] 次（从 0 起）复活尝试之后，下一次要等多久。
     *
     * 封顶 180 秒而不是无限增长：熄屏后台听歌时，用户对「三分钟一次的重试」是没有感的
     * （一次取链只有几 KB），但对他来说「过一会儿自己又响了」和「彻底没声了」是天壤之别。
     */
    fun backoffMs(attempt: Int): Long {
        if (attempt <= 0) return BACKOFF_STEPS_MS.first()
        val index = attempt.coerceAtMost(BACKOFF_STEPS_MS.lastIndex)
        return BACKOFF_STEPS_MS[index]
    }

    /**
     * 维护「从什么时候开始算死」。
     *
     * - 位置前进了 / 正在出声 / 没有播放意图 ⇒ 归零（这一轮结束了）；
     * - 否则第一次观察到「死」时记下 `nowMs`，之后保持不变（这样 `nowMs - deadSinceMs`
     *   才是持续时长）。
     *
     * 之所以要求**位置不前进**而不是只看 `isPlaying`：`STATE_READY + buffered 有数据 +
     * 时钟不走` 那种形状（上游 VLC 系的经典卡死）里播放器自认为在播，只有位置能戳穿它。
     *
     * [advanced] 由调用方给「这一次采样的位置比上一次大」这个**布尔**，而不是传两个位置：
     * `stop()` 之后 `currentPosition` 会回到 0，用位置相减会把「0 != 上次的 30 秒」
     * 误读成「前进」，从而让死判据永远归零、复活永远不触发（这正是本条曾经的样子）。
     *
     * @return 新的 `deadSinceMs`，0 表示「此刻是活的」。
     */
    fun nextDeadSinceMs(
        nowMs: Long,
        deadSinceMs: Long,
        advanced: Boolean,
        playing: Boolean,
        hasIntent: Boolean,
    ): Long {
        if (playing || !hasIntent || advanced) return 0L
        return if (deadSinceMs == 0L) nowMs else deadSinceMs
    }

    /**
     * 现在该不该由服务自己发一次「重新取链 + 从断点续播」。
     *
     * @param nowMs 当前单调时钟（`SystemClock.elapsedRealtime()`）。
     * @param deadSinceMs [nextDeadSinceMs] 的结果；0 表示没在死的状态里。
     * @param hasIntent 还有播放意图（用户没暂停、队列没清空）。
     * @param playing 正在出声。
     * @param attempt 本轮已经尝试过几次（[backoffMs] 的入参）。
     * @param lastAttemptAtMs 上一次尝试的时刻；0 表示本轮还没试过。
     * @param resolvable 这首歌是否还能取链（缺 sourceId 的 qm 曲目取不了，
     *   这种情况重试多少次都是空的，直接不试）。注意这里**不**包含「有没有登录」——
     *   登录态过期时用户重新登录后就该能复活，那由下一轮判据自然覆盖。
     */
    fun shouldResurrect(
        nowMs: Long,
        deadSinceMs: Long,
        hasIntent: Boolean,
        playing: Boolean,
        attempt: Int,
        lastAttemptAtMs: Long,
        resolvable: Boolean,
    ): Boolean {
        if (!hasIntent || playing || !resolvable) return false
        if (deadSinceMs == 0L) return false
        if (nowMs - deadSinceMs < DEAD_AFTER_MS) return false
        if (lastAttemptAtMs != 0L && nowMs - lastAttemptAtMs < backoffMs(attempt)) return false
        return true
    }
}
