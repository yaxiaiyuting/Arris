/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.player

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v4.1.10 · C1：**同一 key 的 in-flight 请求只发一次**（single-flight）。
 *
 * ## 为什么需要（取链风暴的第二个源头）
 *
 * 一首歌的链有两条独立的发起路径：
 *
 * | 发起方 | 时机 | 调用 |
 * |---|---|---|
 * | 预载 | 距本曲结束 ≤ 60s | `PlayerViewModel.preloadNextSong` → `fetchUrlOfflineFirst` |
 * | 正式播放 | 用户点下一首 / 上一曲自然结束 | `PlayerViewModel.playSong` → `fetchUrlOfflineFirst` |
 *
 * 两条路最终都落到 `SongUrlFetcher.fetchOutcome(songId, level)`，而后者此前**没有任何去重**：
 * 预载刚发出、用户就点「下一首」，同一个 `(songId, level)` 会同时存在两通
 * `POST /eapi/song/enhance/player/url/v1`。服务端对播放取链有并发阈值，超了就用
 * `REFUSED_STREAM` 拒流（v3.7.5 实测：6 次点击 = 36 个请求 ⇒ 53 秒全面拒流）。
 *
 * ## 为什么工作跑在**调用方自己的 job** 里，而不是一个独立 scope
 *
 * 独立 scope 的诱惑是「一个调用者取消、不连坐其他人」。但代价更贵：
 * **被遗弃的请求会继续跑完并写全局状态**（3 秒取链冷却、地址族回退窗口）——
 * 实测就抓到了这条：某个已被取消的取链在测试里活过了它的调用方，然后把 3 秒冷却
 * 打给了下一首歌（`SongUrlTransportRetryTest` 的失败现场）。
 * 而「切歌即取消」是 v3.7.5 明确要的语义（否则连点下一首会累积请求）。
 *
 * 所以这里用**发起方代管 + 等待方接管**：
 *
 * 1. 第一个调用者是**发起方**，工作作为一个子协程跑在它自己的 job 里 ——
 *    它被取消（切歌 / 改队列）时这份请求一起作废，副作用也不会发生；
 * 2. 后来的调用者是**等待方**，`await` 同一份结果 —— 不重复发请求；
 * 3. 发起方被取消而等待方还活着时，等待方**接管重试**（[MAX_TAKEOVERS] 次上限），
 *    拿到的是自己那份新请求的结果，而不是一个「被别人的取消牵连」的异常。
 *
 * ## 失败也共享
 *
 * 一次失败的结果交给所有等待者（上层各自走自己的有界重试 / 冷却），
 * 不在 key 上缓存成功值 —— 缓存的职责在 `PreloadCachePolicy`，这里只管「在飞」。
 * 换网时 [clear]：上一张网发出的请求，其结论不得被新网的调用者 join。
 */
internal class InFlightCoalescer<K : Any, V> {

    /** 接管次数上限：防止「发起方总被取消」时演成无限重发。 */
    private val maxTakeovers = 1

    private val inFlight = ConcurrentHashMap<K, CompletableDeferred<V>>()

    /**
     * 加入或发起。
     *
     * @param key 去重键。**必须是「同一份请求」的等价类**（例：`songId@level`）。
     * @param block 真正的请求体；只在**本 key 无在飞任务**时被执行。
     */
    suspend fun run(key: K, onLeader: () -> Unit = {}, block: suspend () -> V): V {
        val context = currentCoroutineContext()
        var takeovers = 0
        while (true) {
            var mine: CompletableDeferred<V>? = null
            val shared = inFlight.compute(key) { _, existing ->
                existing ?: CompletableDeferred<V>().also { mine = it }
            }!!
            // 登记与启动分开：`compute` 的 lambda 里不许再动这张表（会踩 ConcurrentHashMap
            // 的重入约束），所以先在表里放一个未完成的 deferred，出来之后再启动工作。
            mine?.let {
                onLeader()
                startSharing(key, it, block, context)
            }
            try {
                return shared.await()
            } catch (e: CancellationException) {
                // 发起方被取消 ⇒ 这一份作废；等待方如果还活着就自己接管。
                if (mine != null || !context.isActive || takeovers >= maxTakeovers) throw e
                takeovers++
            }
        }
    }

    /**
     * 在 [context]（= 发起方的 job）里跑 [block]，结果写进 [result]。
     *
     * `UNDISPATCHED` 保证请求**立刻开始**（与去重之前的行为一致）。两个 `invokeOnCompletion`
     * 负责「工作结束 ⇒ 结果必被补全 ⇒ 出表」，任何一条异常路径都不会留下永远挂着的等待者。
     */
    private fun startSharing(
        key: K,
        result: CompletableDeferred<V>,
        block: suspend () -> V,
        context: CoroutineContext,
    ) {
        val job = CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                result.complete(block())
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        job.invokeOnCompletion {
            // job 没跑起来就被取消（发起方在 launch 之前已取消）时，补一个「已取消」给等待者。
            if (!result.isCompleted) {
                result.completeExceptionally(CancellationException("coalesced request abandoned"))
            }
        }
        result.invokeOnCompletion { inFlight.remove(key, result) }
    }

    /**
     * 丢掉全部在飞登记（**不取消**正在跑的工作）。
     *
     * 换网时调用：上一张网发出的请求即使还在跑，它的结果也不该被新网的调用者 join ——
     * 那会把旧路由的「失败」当成新路由的结论（冷却与地址族记忆都据此判定）。
     */
    fun clear() {
        inFlight.clear()
    }

    /** 诊断 / 单测：当前在飞的 key 数。 */
    internal fun inFlightCount(): Int = inFlight.size
}
