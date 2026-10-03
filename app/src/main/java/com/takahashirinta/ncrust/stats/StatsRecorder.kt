/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 播放统计的**采集端**：以只读观察者的身份挂在既有的 PlayerViewModel 上。
 */

package com.takahashirinta.ncrust.stats

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * 把「播放器正在播什么」翻译成统计。
 *
 * ## 为什么是**观察者**而不是在播放链路里打点
 *
 * 任务书点名的「最佳复用时机」是 `PlayReporter`（自然播完 或 进度 ≥ 80%，
 * 且 per-song 去重）。但那个时机**只有两个瞬间**，用它当唯一数据源会有两个洞：
 *
 * 1. 它拿不到「中途切歌但听了两分钟」的那一段（未达 80% ⇒ 一次都不上报），
 *    而「总共听了多久」必须把这一段算进去；
 * 2. 它上报的是**播放器位置**（`positionMs`），不是**真实收听时长** ——
 *    拖进度条拖出来的位置也会被它当成 `time` 上报（那是官方 webLog 的口径，与本页无关）。
 *
 * 所以本文件的判据是**自己的**（[StatsMath.listenDelta] + [StatsMath.countsAsPlay]），
 * 但**阈值与去重语义与 `PlayReporter` 对齐**（80% 那条用的是同一个数）：
 * 两条链路对「这算不算一次播放」的回答必须一致，否则用户会看到
 * 「上报了但没统计」/「统计了但没上报」这种解释不通的差异。
 *
 * ## 为什么不去改 `PlaybackService.onProgressUpdate`
 *
 * 那是**单槽位**的静态回调（`PlaybackService.onProgressUpdate = { … }`），
 * 已经被 `PlayerViewModel` 独占。抢过来再转调会引入「谁先赋值」的初始化顺序依赖，
 * 而且 `player/` 与 `ui/viewmodel/` 在本轮是**别人的并行工作区**（见任务边界）。
 * 观察 `PlayerViewModel` 的 StateFlow 是只读的：不改任何既有写入点，
 * 也不可能影响播放（读 `.value` 不触发任何副作用）。
 *
 * ## 开销（铁律 4：统计是旁路，不是功能）
 *
 * 每次采样（2 Hz）做：1 次 `System.currentTimeMillis()`、1 次 `Calendar` 组桶（≤2 次）、
 * 3 次 Map 查改、1 次 Long 加法。**没有 IO、没有 Gson、没有新协程、没有锁竞争**
 * （采样与落盘快照共用一把锁，但落盘只在 30 秒一次的快照瞬间持锁）。
 */
object StatsRecorder {

    private const val TAG = "StatsRecorder"

    /** 单曲循环 / 拖回开头：位置一次回退超过它才可能是一次「重播」。 */
    private const val RESTART_BACK_MS = StatsMath.RESTART_BACK_MS

    /**
     * 「上一采样点已经接近结尾」的比例（与 `PlayReporter` 的 80% 不同，这里用 90%）：
     * 90% 之后的位置回跳，最合理的解释是这首歌播完又从头开始（单曲循环 / 自然重播）。
     * 取 90% 而不是 80%：80% 时用户完全可能手动拖回前面重听副歌，
     * 而那一次拖动**不该**被记成新的一次播放。
     */
    private const val NEAR_END_RATIO = 0.9f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableListOf<Job>()

    /**
     * 被观察的 ViewModel。
     *
     * 持有它意味着「进程活着期间会攥着一个 Activity 作用域的 ViewModel」。
     * 这是刻意的，且**有界**：Activity 真正销毁（`onActivityDestroyed` 且非配置变更）时
     * 会 [detach]；配置变更时 Android 会复用同一个 ViewModel 实例，重新 attach 是幂等的。
     * 不做弱引用：弱引用会让「GC 恰好在两帧之间回收」变成「统计静默断流」，
     * 而那种故障在真机上无法复现也无法归因。
     */
    private var model: PlayerViewModel? = null

    /** 采集用的 Application 上下文（只用于 prefs 与生命周期注册，不持有任何 Activity）。 */
    private var appContext: Context? = null

    private var lifecycleRegistered = false

    // ---------------------------------------------------------- 会话状态 ----
    // 只在主线程读写（两个 collector 都跑在 Dispatchers.Main.immediate）。

    private var sessionKey: String? = null
    private var sessionSourceKey: String = MusicSource.DEFAULT.key
    private var sessionName: String = ""
    private var sessionArtist: String = ""
    private var sessionListenedMs: Long = 0L
    private var sessionDurationMs: Long = 0L

    private var lastPosMs: Long = 0L
    private var lastNowMs: Long = 0L

    /**
     * 起播/恢复后的第一个采样点只用来**定位锚点**，不产生增量。
     *
     * 没有它，暂停期间的 stale 锚点会让恢复后的第一个增量等于「暂停时长 + 500 ms」，
     * 而它的位置增量其实很小 —— 那种「墙上时钟与位置严重不匹配」的样本一律会被
     * [StatsMath.listenDelta] 判成前跳丢掉，本身不会算错；这里显式重锚是为了
     * 「暂停后恢复的第一帧就可能有真实增量」这件事不被静默吞掉。
     */
    private var anchorValid = false

    /**
     * 接上采集。**幂等**：同一个 ViewModel 重复调用是空操作。
     *
     * 由 `MainScreen` 调用（`LaunchedEffect(Unit)`）—— 采集必须**全程在跑**，
     * 不能等用户打开统计页才开始，否则「打开统计页之前听的全部不算」。
     */
    fun attach(context: Context, viewModel: PlayerViewModel) {
        if (model === viewModel) return
        detach()
        val app = context.applicationContext
        model = viewModel
        appContext = app
        // 先把落盘数据读起来（IO）：第一次采样通常在半秒之后，那时它已经就绪，
        // 不会出现「主线程第一次采样顺手做一次 80 KB 解析」的抖动。
        StatsStore.preload(app)
        if (app is Application) registerLifecycle(app)
        Log.i(TAG, "attached")
        jobs += scope.launch {
            viewModel.currentPosition.collect { onSample(app, it) }
        }
        jobs += scope.launch {
            viewModel.isPlaying.collect { playing -> if (!playing) anchorValid = false }
        }
    }

    /**
     * 断开采集（Activity 真正销毁时）。**内存聚合与落盘数据都保留**。
     *
     * 断开前先 [closeSession]：会话的「有效收听时长」已经逐段写进统计了，
     * 但「算不算一次播放」是在收尾时判定的 —— 不收尾就等于「听了整首、关掉 App、
     * 这次播放不算」，那是用户一眼能看出来的错。
     */
    fun detach() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        appContext?.let { closeSession(it) }
        model = null
        appContext = null
        // 会话是「上一段播放」的临时状态，断开时清干净，避免下次 attach 把它接在
        // 另一台播放器（另一个 VM）的进度上。
        sessionKey = null
        sessionListenedMs = 0L
        sessionDurationMs = 0L
        anchorValid = false
    }

    /**
     * 生命周期钩子。
     *
     * 两个动作都**必须有**：
     * - `onActivityStopped` ⇒ 立即落盘。进程被后台杀死是常态（低内存、用户划掉任务卡），
     *   而 `onStop` 之后系统再给多少时间是不确定的 —— 这是把丢失窗口从 30 秒
     *   压到「本次停止之后」的唯一时机；
     * - `onActivityDestroyed` 且**不是配置变更** ⇒ 断开（见 [model] 的 KDoc）。
     */
    private fun registerLifecycle(app: Application) {
        if (lifecycleRegistered) return
        lifecycleRegistered = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStopped(activity: Activity) {
                StatsStore.flushSoon(activity.applicationContext)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (!activity.isChangingConfigurations) detach()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    // ------------------------------------------------------------ 采样 ----

    /**
     * 一个位置采样点。
     *
     * 顺序很重要：**先判会话切换，再算增量**。反过来的话，切歌的第一个采样点会拿
     * 上一首的位置当基准（`delta` 是一个跨越两首歌的乱数），并且会被记到上一首头上。
     */
    private fun onSample(context: Context, pos: Long) {
        val vm = model ?: return
        val now = System.currentTimeMillis()
        val track = vm.currentTrackKey
        val key = track?.tag
        val durationMs = vm.duration.value

        // 单曲循环 / 自然重播：身份没变，但进度从尾部回到开头。
        //
        // ⚠️ 这一段必须在「更新本会话时长」**之前**：`duration.value` 在切歌的第一个
        // 采样点上已经是**新歌**的时长了，先更新再 [closeSession] 会拿新歌的时长去判
        // 上一首「听够了没有」（4 分钟的老歌听了 20 秒本来不算一次播放，
        // 而下一首恰好是 20 秒的间奏 ⇒ 会被误判成听满 80%）。
        val restarted = key != null &&
            key == sessionKey &&
            pos < lastPosMs - RESTART_BACK_MS &&
            sessionDurationMs > 0L &&
            lastPosMs.toFloat() >= sessionDurationMs.toFloat() * NEAR_END_RATIO

        if (key != sessionKey || restarted) {
            closeSession(context)
            sessionKey = key
            sessionSourceKey = track?.source?.key ?: MusicSource.DEFAULT.key
            sessionName = vm.currentSongName.value ?: ""
            sessionArtist = vm.currentSongArtist.value ?: ""
            sessionDurationMs = if (durationMs > 0L) durationMs else 0L
            lastPosMs = pos
            lastNowMs = now
            anchorValid = true
            return
        }

        // 本会话内：时长取「见过的最大值」—— 起播头几百毫秒里 `duration` 还是 0，
        // 用 0 判 80% 会让短曲永远算不上一次播放。
        if (durationMs > sessionDurationMs) sessionDurationMs = durationMs

        if (!anchorValid) {
            lastPosMs = pos
            lastNowMs = now
            anchorValid = true
            return
        }

        val wallDelta = StatsMath.wallDeltaOf(lastNowMs, now)
        val delta = StatsMath.listenDelta(
            prevPosMs = lastPosMs,
            posMs = pos,
            wallDeltaMs = wallDelta,
            playing = vm.isPlaying.value,
        )
        lastPosMs = pos
        lastNowMs = now

        if (delta <= 0L || key == null) return

        sessionListenedMs += delta
        // 时区在**采样那一刻**取：用户跨时区旅行时，只有当时那一段能被正确分桶
        // （事后无法知道他是几点听的）。`TimeZone.getDefault()` 走的是 JVM 缓存，不是 IO。
        val zone = TimeZone.getDefault()
        StatsStore.addListen(
            context = context,
            slices = StatsMath.splitAcrossDays(now - delta, now, zone),
            songKey = key,
            sourceKey = sessionSourceKey,
            name = sessionName,
            artist = sessionArtist,
            durationMs = sessionDurationMs,
            nowMs = now,
        )
    }

    /**
     * 收尾当前会话：够阈值就 +1 次播放，然后立刻落盘一次。
     *
     * 切歌时**立即落盘**而不是等 30 秒：切歌是最常见的「用户要走了」信号
     * （切完就锁屏 / 退出），而「这一次播放」正是统计里最不容易重建的一个数。
     */
    private fun closeSession(context: Context) {
        val key = sessionKey ?: return
        val counted = StatsMath.countsAsPlay(sessionListenedMs, sessionDurationMs)
        sessionKey = null
        sessionListenedMs = 0L
        sessionDurationMs = 0L
        if (counted) {
            StatsStore.addPlay(context, key, sessionSourceKey)
            StatsStore.flushSoon(context)
        }
    }
}
