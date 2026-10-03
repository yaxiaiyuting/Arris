/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

/**
 * v2.0.2：歌词行变化时「要不要重发媒体通知」的判定。**纯逻辑，JVM 可单测。**
 *
 * ## 为什么需要它（v1.8.0 的版本闸门被真机推翻）
 *
 * v1.8.0 · T5 给「状态栏歌词不更新」加过一个修法：跨行时补一次重 post，但
 * **只在 `SDK_INT < P`（28）生效**（见 `PlaybackService.onMediaLyricLineChanged`）。
 * 依据是「API 28+ 的 SystemUI 会自己从 MediaSession metadata 重建媒体通知
 * （`NotificationMediaManager` / `MediaDataManager`），逐行重 post 反而让媒体轮播卡反复刷新」。
 *
 * 那个依据**只对 AOSP 成立**。v2.0.2 在华为平板 WGR-W09（HarmonyOS 4.2 / EMUI 14.2.0 /
 * Android 12 / API 31）上实测：连续播放 47 秒、会话元数据推进了 **14 个不同的歌词行**，
 * 而应用 post 的通知正文 `android.title` **47 秒一个字节没变** —— 系统没有替应用重建。
 * 于是这个「新系统不用管」的闸门在真实 ROM 上直接变成「永远不刷新」。
 *
 * 结论：**不要再按 API 版本猜系统会不会重建**。通知的正文由发通知的人负责刷新，
 * 内容变了就重发；代价只是一次同 id 的 `notify()`（`setOnlyAlertOnce(true)`，不响不震），
 * 而这个代价远小于「歌词停在第一句」。
 *
 * ## 语义（三条，全部可单测）
 *
 * 1. 服务还没进前台（`startForeground` 都没调过）→ **不能发**（[Decision.SkipNotStarted]）；
 * 2. 要显示的歌词行与**通知里当前那一行**相同 → 没有内容变化，不必重发
 *    （[Decision.SkipSameLine]）—— 这条同时挡掉了「同一行被反复推」造成的无谓刷新；
 * 3. 距上次 post 不足 [MIN_INTERVAL_MS] → **延后重试，不是丢弃**（[Decision.Defer]）。
 *    说唱段落里行变化可能快到 200ms 一次，直接丢弃会让通知永远停在被丢掉的那一行上；
 *    调用方按 [Decision.Defer.retryInMs] 挂一次延迟重试，保证「最后一行一定会到达」。
 *
 * 注意 [NEVER_POSTED]：`null` 是一个**有意义的歌词行取值**（「当前没有歌词行」），
 * 所以「从来没 post 过」不能也用 `null` 表示，否则第一首歌没有歌词时会被误判成「内容没变」。
 */
internal object LyricNotifyGate {

    /**
     * 两次重 post 之间的最小间隔（毫秒）。
     *
     * 只用来**错峰**，不用来丢内容：被限流的那一次会由调用方按剩余时间延迟重试。
     * 正常人声段落一行 2–4 秒，这个下限根本不会触发；它只在说唱等密集段落起作用。
     * （v1.8.0 起本常量一直叫 `LYRIC_NOTIFY_MIN_INTERVAL_MS`，v2.0.2 收敛进本对象。）
     */
    const val MIN_INTERVAL_MS = 250L

    /**
     * 「还没有 post 过任何歌词行」的哨兵。
     *
     * 用不可能出现在真实歌词里的控制字符打头，避免与真实行（含 `null`）撞值。
     * 第一首歌第一行到达时必须无条件 post —— 否则通知会停在歌名上不再更新。
     */
    const val NEVER_POSTED = "\u0000NEVER_POSTED\u0000"

    /** [decide] 的判定结果。 */
    sealed interface Decision {
        /** 立刻重发通知。 */
        data object Post : Decision

        /** 服务还没进前台，这次不能发（也不该排重试：服务起来后自然会走别的 post 路径）。 */
        data object SkipNotStarted : Decision

        /** 通知里已经是这一行，内容没变。 */
        data object SkipSameLine : Decision

        /** 距上次 post 太近，[retryInMs] 毫秒后重试。 */
        data class Defer(val retryInMs: Long) : Decision
    }

    /**
     * @param line            现在应该显示的歌词行（`null` = 当前没有歌词行）
     * @param lastPostedLine  通知里**当前**那一行（没 post 过时传 [NEVER_POSTED]）
     * @param nowMs           当前时刻（`SystemClock.elapsedRealtime()`）
     * @param lastPostAtMs    上次 post 通知的时刻（`SystemClock.elapsedRealtime()`；没 post 过传 0）
     * @param serviceStarted  服务是否已经 `startForeground` 过
     */
    fun decide(
        line: String?,
        lastPostedLine: String?,
        nowMs: Long,
        lastPostAtMs: Long,
        serviceStarted: Boolean,
    ): Decision {
        if (!serviceStarted) return Decision.SkipNotStarted
        if (line == lastPostedLine) return Decision.SkipSameLine
        val elapsed = nowMs - lastPostAtMs
        if (elapsed < MIN_INTERVAL_MS) {
            // 注意方向：elapsed 有可能是负数（时钟被回拨 / 调用方传了 0），
            // 那时 remaining 会大于 MIN_INTERVAL_MS，仍然是一个合法的「稍后再试」。
            return Decision.Defer((MIN_INTERVAL_MS - elapsed).coerceAtMost(MIN_INTERVAL_MS))
        }
        return Decision.Post
    }
}
