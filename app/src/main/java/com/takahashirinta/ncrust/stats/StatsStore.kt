/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 播放统计的落盘（SharedPreferences + Gson）与内存聚合的**唯一持有者**。
 */

package com.takahashirinta.ncrust.stats

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `ncrust_stats` 的读写与「内存聚合 → 落盘」的调度。
 *
 * ## 为什么单开一个 prefs 文件，而不是塞进 `ncrust_settings`
 *
 * `ncrust_settings` 是设置页的**单个键值对**集合（主题/语言/音质…），每次写都要整份重写 XML；
 * 统计快照是 ~80 KB 的一个字符串（见 `StatsSnapshot` 的量级估算）。
 * 把它塞进设置文件会让「切主题」这种本来 1 ms 的写盘变成一次 80 KB 重写。
 * 分开之后，两条链路的写盘互不影响 —— 与 `ncrust_offline` / `ncrust_lyrics_cache` 同一条思路。
 *
 * ## 写入时机：为什么不会卡 UI
 *
 * - 采样路径（2 Hz）只做**内存**累加：一个 Long 加法 + 几次 Map 查改，
 *   没有 IO、没有 Gson、没有协程调度（`StatsRecorder` 的 KDoc 里有开销清单）；
 * - 落盘走 IO 作用域，**首次变更后延迟 [FLUSH_INTERVAL_MS] 触发一次**，
 *   且**不因为后续变更而重置计时器**（若用「防抖」写法，连续播放时计时器会被每个采样点
 *   无限推迟，结果是「一直在播就永远不落盘」—— 掉电即全丢）；
 * - 序列化在 `Dispatchers.Default`、写盘在 `Dispatchers.IO`（与 `PlaybackStateManager` 同款
 *   分工：Gson 反射序列化是 CPU 活，不该占 IO 线程池）。
 *
 * 最坏丢失窗口 = [FLUSH_INTERVAL_MS]（30 秒）—— 这是**有界**的，页面上如实写明。
 */
object StatsStore {

    private const val TAG = "StatsStore"

    internal const val PREFS_NAME = "ncrust_stats"
    internal const val KEY_SNAPSHOT = "snapshot"

    /**
     * 内存里的脏数据最多滞留这么久。取值理由：
     * - 短于一次典型歌曲（3~4 分钟）⇒ 正常切歌时上一首的数据已经落盘过至少一次；
     * - 长于一个采样周期（500 ms）三个数量级 ⇒ 落盘频率与采样率完全解耦；
     * - 30 秒 × 80 KB 的写盘量在真机上可忽略（一次 `apply()` 在 IO 线程）。
     */
    internal const val FLUSH_INTERVAL_MS = 30_000L

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var flushJob: Job? = null

    /**
     * 内存聚合。`null` = 还没读盘。
     *
     * 只在这里持有，且**所有读写都在 [lock] 内**：采样在主线程序、落盘在 IO 线程快照，
     * 两者必须串行化。
     */
    @Volatile
    private var aggregate: StatsAggregate? = null

    /**
     * 「清空」代数。每次 [clear] +1，用于**挡住已经在飞行中的那次落盘**。
     *
     * 不加它有一个真实（虽然窄）的竞态：`flushNow` 先在锁内拷一份快照、释放锁、
     * 再去序列化（几毫秒）；这几毫秒里用户点了「清除统计」 —— 那份**旧快照**
     * 随后落盘，用户刚清掉的数据又回来了。`flushJob.cancel()` 挡不住它：
     * 取消只在挂起点生效，而写盘可能已经进了 `withContext(Dispatchers.IO)`。
     *
     * 代数不同 ⇒ 这一份快照作废（丢一次落盘，下一次变更会重新排）。
     */
    private var generation = 0

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 同步取聚合（首次会读盘 + 解析）。
     *
     * 首次调用会做一次 `getString` + Gson 解析（~80 KB），**不要在主线程第一次调用它**：
     * [StatsRecorder.attach] 会先用 [preload] 在 IO 线程把它读起来，
     * 而 UI 侧一律用 `withContext(Dispatchers.IO) { StatsStore.snapshot(ctx) }`。
     */
    internal fun ensureLoaded(context: Context): StatsAggregate {
        aggregate?.let { return it }
        synchronized(lock) {
            aggregate?.let { return it }
            val app = context.applicationContext
            val raw = try {
                prefs(app).getString(KEY_SNAPSHOT, null)
            } catch (e: Exception) {
                // prefs 读失败按「没有数据」处理：统计是旁路，绝不能因为它读不出来就崩主流程。
                Log.w(TAG, "read stats prefs failed", e)
                null
            }
            // 解码本身也有 try/catch（StatsCodec.decode 的兜底），坏数据不会抛到这里。
            val loaded = StatsAggregate.from(StatsCodec.decode(raw))
            aggregate = loaded
            return loaded
        }
    }

    /** 在 IO 线程把内存聚合提前读起来（`StatsRecorder.attach` 调用）。 */
    fun preload(context: Context) {
        val app = context.applicationContext
        ioScope.launch { runCatching { ensureLoaded(app) } }
    }

    /** 当前快照。**阻塞**：调用方自己决定线程（UI 侧放 IO）。 */
    fun snapshot(context: Context): StatsSnapshot = ensureLoaded(context).snapshot()

    // ------------------------------------------------------------ 写路径 ----

    /**
     * 记一段有效收听（采样路径调用，**必须在主线程**：见类 KDoc 的线程约定）。
     *
     * `slices` 由调用方用 [StatsMath.splitAcrossDays] 切好 —— 时区只在采样那一刻有意义。
     */
    internal fun addListen(
        context: Context,
        slices: List<DaySlice>,
        songKey: String,
        sourceKey: String,
        name: String,
        artist: String,
        durationMs: Long,
        nowMs: Long,
    ) {
        val app = context.applicationContext
        synchronized(lock) {
            ensureLoaded(app).addListen(slices, songKey, sourceKey, name, artist, durationMs, nowMs)
        }
        scheduleFlush(app)
    }

    /** 记一次播放（切歌时调用）。 */
    internal fun addPlay(context: Context, songKey: String, sourceKey: String) {
        val app = context.applicationContext
        synchronized(lock) {
            ensureLoaded(app).addPlay(songKey, sourceKey)
        }
        scheduleFlush(app)
    }

    /** 清空全部统计（用户在页面上点「清除统计」）。**立即落盘**，不给「清了又复活」留窗口。 */
    fun clear(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            flushJob?.cancel()
            flushJob = null
            // 先让代数前进：任何**已经拿着旧快照**的 flushNow 会在写盘前放弃。
            generation += 1
            aggregate = StatsAggregate()
        }
        ioScope.launch {
            runCatching {
                // 用 commit() 而不是 apply()：用户点的是「删除」，这一次必须真的落盘
                // 才算数（apply 是异步的，进程随后被杀就会「清了又回来」）。
                // 它在 IO 线程上，不阻塞 UI。
                withContext(Dispatchers.IO) { prefs(app).edit().remove(KEY_SNAPSHOT).commit() }
            }
        }
    }

    /**
     * 请求「尽快落盘一次」（切歌、退到后台）。
     *
     * 与 [scheduleFlush] 的区别：那个是「最多 30 秒内落一次」，这个是「现在就落」。
     * 两者都**不阻塞调用方**（调用点在主线程）。
     */
    fun flushSoon(context: Context) {
        val app = context.applicationContext
        ioScope.launch { runCatching { flushNow(app) } }
    }

    /**
     * 安排一次延迟落盘。**已经在排队时直接返回，不重置计时器** ——
     * 这条就是「一直在播也一定会落盘」的保证（理由见类 KDoc）。
     */
    private fun scheduleFlush(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (flushJob?.isActive == true) return
            flushJob = ioScope.launch {
                delay(FLUSH_INTERVAL_MS)
                runCatching { flushNow(app) }
            }
        }
    }

    /**
     * 落盘一次。序列化前先在锁内**拷一份快照**：这样 Gson 序列化（几毫秒）与写盘期间，
     * 采样线程可以继续改内存聚合，不需要等 IO。
     */
    private suspend fun flushNow(context: Context) {
        // 快照与代数一起取：代数变了就说明「用户在这中间清了统计」，
        // 这一份不能再写回去（见 [generation]）。
        val pending = synchronized(lock) {
            val agg = aggregate ?: return
            agg.prune()
            agg.snapshot() to generation
        }
        val snapshot = pending.first
        val snapshotGeneration = pending.second
        try {
            val json = withContext(Dispatchers.Default) { StatsCodec.encode(snapshot) }
            withContext(Dispatchers.IO) {
                val stillCurrent = synchronized(lock) { generation == snapshotGeneration }
                if (!stillCurrent) return@withContext
                prefs(context).edit().putString(KEY_SNAPSHOT, json).apply()
            }
        } catch (e: Exception) {
            // 写失败不影响统计继续累加：内存里还是完整的，下一次变更会再排一次落盘。
            Log.w(TAG, "flush stats failed", e)
        }
    }
}
