/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

/** 一档更新的性质：状态/曲目变化必须立刻发；只有「内容推进」需要限频。 */
internal enum class NotifyReason {
    /** 播放/暂停、切歌、质量变化、封面 —— 用户能立刻察觉，必须立即更新。 */
    STATE,

    /** 歌词行跨行、进度推进 —— 高频且非关键，限频即可（但**不能丢**）。 */
    CONTENT,
}

/**
 * 播放通知的**重建频率**控制（v4.1.8 · 纯逻辑，JVM 可单测）。
 *
 * ## 为什么需要它（真机取证）
 *
 * 真机 PLC110 / Android 17 / ColorOS，2026-11-22 19:45–19:46（熄屏后台播放）：
 *
 * ```
 * 19:45:53.440 osense.compress: com.takahashirinta.ncrust_10341 do shrink, target ratio = 41 …
 * 19:46:13.439 osense.compress: com.takahashirinta.ncrust_10341 do shrink, target ratio = 43 …
 * OplusNotificationFixHelper: fixAppIcon: appIcon = null, pkg = com.takahashirinta.ncrust   ← 约每 2.5 s 一条
 * ```
 *
 * 也就是说：**通知大约每 2.5 秒被重建一次**，而 ROM 每次都要重新解析一次应用图标
 * （`appIcon` 还解析成 null，纯白做功）。我们的通知更新源只有两条：
 *
 * 1. [NotifyReason.STATE] —— 播放/暂停、切歌、质量、封面；频率天然是「每分钟几次」；
 * 2. [NotifyReason.CONTENT] —— 跨歌词行（[LyricNotifyGate] 之后）与 API 36+ 的
 *    Live Update 进度（`LiveUpdateNotifier` 的 `Content.percent` 每 1% 变一次
 *    ⇒ 按 4 分钟的歌算是 **每 2–3 秒一次**）。
 *
 * 2.5 秒的节奏正好对上 ②。本类把 ② 压到 **≥ [DEFAULT_CONTENT_INTERVAL_MS]**，
 * 状态类更新保持零延迟。
 *
 * ## 语义
 *
 *  - [NotifyReason.STATE]：**永远放行**，并把「内容预算」清零 —— 切歌后第一行歌词
 *    立刻可见，不会因为上一首刚刷过而被延后 5 秒；
 *  - [NotifyReason.CONTENT]：距上次内容更新不足阈值 → 返回 [Decision.Defer]，
 *    **调用方按 `retryInMs` 延迟补发**（不丢弃）—— 通知最终一定会显示最新内容；
 *  - 时钟回退（`nowMs` 比上次小）按放行处理：宁可多发一次，也不要卡住。
 */
internal class NotifyPace(
    private val contentMinIntervalMs: Long = DEFAULT_CONTENT_INTERVAL_MS,
) {

    companion object {
        /**
         * 内容类通知的最小间隔。
         *
         * 5 秒 = 用户仍能在通知栏看到「正在唱哪一行 / 进度到哪」的粒度，
         * 同时把重建次数从 ~24 次/分压到 ≤ 12 次/分。
         */
        const val DEFAULT_CONTENT_INTERVAL_MS: Long = 5_000L

        /** `lastContentAtMs` 的「还没发过」哨兵（真实时刻永远 ≥ 0）。 */
        private const val NEVER = Long.MIN_VALUE
    }

    sealed interface Decision {
        /** 现在就可以发。 */
        data object Post : Decision

        /** [retryInMs] 毫秒之后补发这一次（**不要丢弃**）。 */
        data class Defer(val retryInMs: Long) : Decision
    }

    /** 上一次**内容类**更新的时刻；[NEVER] = 还没发过。 */
    private var lastContentAtMs = NEVER

    fun decide(nowMs: Long, reason: NotifyReason): Decision {
        if (reason == NotifyReason.STATE) {
            lastContentAtMs = NEVER
            return Decision.Post
        }
        val previous = lastContentAtMs
        val elapsed = if (previous == NEVER) Long.MAX_VALUE else nowMs - previous
        if (elapsed >= contentMinIntervalMs || elapsed < 0L) {
            lastContentAtMs = nowMs
            return Decision.Post
        }
        return Decision.Defer(contentMinIntervalMs - elapsed)
    }

    /** 服务重建后重新开始计时（避免拿上一次会话的时间戳算账）。 */
    fun reset() {
        lastContentAtMs = NEVER
    }
}
