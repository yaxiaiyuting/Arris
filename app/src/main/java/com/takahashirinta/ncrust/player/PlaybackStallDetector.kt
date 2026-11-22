/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

/**
 * 播放卡顿判据（v4.1.8 · **纯逻辑，JVM 可直接单测**）。
 *
 * ## 它修的是哪一条真机故障
 *
 * 现场（2026-11-22 19:45，屏幕熄灭、应用在后台）：`playWhenReady=true`、
 * `playbackState=STATE_READY`、`isPlaying=true`，但**时钟停在 18396 ms 不动**，
 * `dumpsys media_session` 两分钟以上没有新的状态事件，`logcat` 里既没有
 * `Playback stalled` 也没有 `onPlayerError`，切回前台后立刻恢复。
 *
 * 旧实现（[PlaybackStallWatch]，v4.1.7 之前）本身不区分状态，但**观察循环**只在
 * `isActivelyBuffering()`（`playbackState == STATE_BUFFERING`）为真时才启动
 * （`PlaybackService.restartStallObservation`）。本次现场是 `STATE_READY` ——
 * 循环从未启动，判据从未跑过，于是既没有日志也没有恢复动作。
 *
 * 所以判据必须**自己**回答「该不该判卡顿」，而不是让调用方先按状态把循环关掉。
 *
 * ## 三个条件（同时成立才判卡顿）
 *
 * 1. **有播放意图** —— `playWhenReady && !suppressed && state ∈ {READY, BUFFERING}`。
 *    暂停、被音频焦点抑制、IDLE/ENDED 都不算意图，一律不判（[Sample.playbackIntent]）。
 * 2. **时钟/位置 N 秒内没有前进** —— 位置一毫秒都没动才算；
 *    **buffering 期间**数据还在往下走（`bufferedPosition` 在涨）算「合法的等待」，不判。
 *    READY 期间**只认位置**：READY 意味着「播放器认为自己能出声音」，
 *    这时候 buffer 再涨也不该掩盖一个不动的时钟（现场正是这种情况）。
 * 3. **没有用户主动暂停 / seek / 换歌** —— 这一类动作由调用方递增
 *    [Sample.actionGeneration]，判据一看到代数变了就丢弃旧的计时窗口。
 *
 * 判据不读 `Player`、不读时钟、不碰 Android API：调用方把 [Sample] 喂进来，
 * 它只回一个 [Reading]。这样四条关键行为（READY 不前进 / 正常播放 / 用户暂停 /
 * buffering 正常等待）都能在 JVM 单测里跑。
 *
 * ## 为什么「一次卡顿只报一次」
 *
 * 报一次之后就置 `reported`，直到**位置重新前进**或**代数变化**才复位。
 * 上层拿这个布尔去做 `stop → seekTo → prepare` 这类有代价的恢复动作，
 * 不能因为 2 秒一次的采样而在一次卡顿里触发十几次。
 */
internal class PlaybackStallDetector(
    private val stallThresholdMs: Long = DEFAULT_STALL_MS,
) {

    companion object {
        /**
         * 多久不动算卡顿。
         *
         * 12 秒的依据：ExoPlayer 的 `DefaultLoadControl` 在本应用里是 30 s 起播 / 60 s 上限，
         * 一次正常的解码器重配或 CDN 换连接都在 1 s 级；12 s 足够越过「曲间无缝衔接」这类
         * 正常停顿，又不会让用户听着一片死寂等到半分钟。
         */
        const val DEFAULT_STALL_MS: Long = 12_000L

        /** 从未采样过的哨兵（真实位置永远 ≥ 0）。 */
        private const val UNKNOWN = Long.MIN_VALUE
    }

    /** 一次采样。调用方（服务侧的观察循环）负责把 `Player` 的状态翻译成这几个字段。 */
    data class Sample(
        /** 单调时钟（`SystemClock.elapsedRealtime()`）。 */
        val nowMs: Long,
        val positionMs: Long,
        val bufferedMs: Long,
        /** `playbackState == STATE_BUFFERING`。 */
        val buffering: Boolean,
        /** 有播放意图：`playWhenReady && !suppressed && state ∈ {READY, BUFFERING}`。 */
        val playbackIntent: Boolean,
        /** 用户动作代数（seek / 换歌 / 手动播放）：变了就丢弃旧窗口。 */
        val actionGeneration: Long = 0L,
    )

    /** 一次判定的结果。 */
    data class Reading(
        /** 本次采样**新**判出卡顿（同一次卡顿只返回一次 `true`）。 */
        val stalled: Boolean,
        /** 距上次看到前进过去了多久；没有意图时为 0。 */
        val stalledForMs: Long,
        /** 本次采样位置（或 buffering 期间的 buffer）有没有前进。 */
        val advanced: Boolean,
        /** 判据是否在计时（有播放意图）。 */
        val armed: Boolean,
    ) {
        companion object {
            val DISARMED = Reading(stalled = false, stalledForMs = 0L, advanced = false, armed = false)
        }
    }

    private var lastPositionMs = UNKNOWN
    private var lastBufferedMs = UNKNOWN
    private var progressAtMs = UNKNOWN
    private var lastGeneration = UNKNOWN
    private var reported = false

    /** 丢弃当前计时窗口（调用方在切歌 / seek / 暂停时显式调用）。 */
    fun reset() {
        lastPositionMs = UNKNOWN
        lastBufferedMs = UNKNOWN
        progressAtMs = UNKNOWN
        reported = false
    }

    fun observe(sample: Sample): Reading {
        if (lastGeneration != UNKNOWN && sample.actionGeneration != lastGeneration) reset()
        lastGeneration = sample.actionGeneration

        if (!sample.playbackIntent) {
            // 暂停 / 被抑制 / 空闲：没有播放意图就没有「卡顿」这件事，同时丢掉旧窗口，
            // 免得恢复播放的第一次采样拿一个几小时前的时间戳去算 stalledFor。
            reset()
            return Reading.DISARMED
        }

        val firstSample = lastPositionMs == UNKNOWN
        val advanced = !firstSample && (
            sample.positionMs > lastPositionMs ||
                // buffering 期间数据还在来 = 合法的等待（网络慢但活着），不判卡顿。
                (sample.buffering && sample.bufferedMs > lastBufferedMs)
            )
        lastPositionMs = sample.positionMs
        lastBufferedMs = sample.bufferedMs

        if (firstSample || advanced) {
            progressAtMs = sample.nowMs
            reported = false
            return Reading(stalled = false, stalledForMs = 0L, advanced = advanced, armed = true)
        }

        val stalledForMs = (sample.nowMs - progressAtMs).coerceAtLeast(0L)
        val stalled = !reported && stalledForMs >= stallThresholdMs
        if (stalled) reported = true
        return Reading(stalled, stalledForMs, advanced = false, armed = true)
    }
}
