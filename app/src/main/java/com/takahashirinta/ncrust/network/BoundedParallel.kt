/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · P0-A：并行请求编排的**唯一**原语。纯逻辑（只依赖 kotlinx.coroutines），JVM 可单测。
 */

package com.takahashirinta.ncrust.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 一个子任务的结局。**失败与成功都是合法结果**，调用方必须两条都处理。
 *
 * 为什么不用 `Result<T>`：`Result` 的失败侧是 `Throwable`，而本应用里
 * 「取链失败」是一个**业务结论**（无版权 / 无会员 / 下架），不是异常 ——
 * 把业务结论塞进异常里，下一个读代码的人就会开始 `catch` 它。
 */
sealed interface FetchOutcome<out T> {
    data class Ok<T>(val value: T) : FetchOutcome<T>

    /** 子任务抛了异常。[error] 原样保留（日志要能看到类型）。 */
    data class Failed(val key: String, val error: Throwable) : FetchOutcome<Nothing>

    /** 子任务自己返回了 null（「没有」，不是「出错」）。 */
    data class Missing(val key: String) : FetchOutcome<Nothing>
}

/** 便于日志与断言：这个结局是否拿到了值。 */
val FetchOutcome<*>.isOk: Boolean get() = this is FetchOutcome.Ok

/**
 * v3.1.0 · P0-A：**有并发上限的并行编排**。
 *
 * ## 为什么需要它（探针给的，不是设计洁癖）
 *
 * `docs/verification/v3.1.0/net-research/request-orchestration.md` 的源码审计结论：
 * 点一首歌之后，`PlayerViewModel.playSong` 里**取链是阻塞的**
 * （`withContext(Dispatchers.IO) { fetchUrlOfflineFirst(...) }`），
 * 而 `viewModelScope.launch { fetchLyrics(track) }` 排在它**之后** ——
 * 于是「取链 RTT」与「歌词 RTT」**相加**，而不是取最大值。
 *
 * 探针实测这两段各自 P50 在 80~250ms 量级（ncm 搜索 TTFB 249ms），
 * 相加就是用户感知里那一段「点了没反应」。
 *
 * ## 三条硬约束（都有单测）
 *
 * 1. **并发上限是硬的**（默认 [DEFAULT_MAX_CONCURRENCY] = 4）。
 *    铁律 23：并行请求必须有并发上限。上限用 [Semaphore] 实现，不是「大概这么多」——
 *    `BoundedParallelTest.并发上限是硬的` 用一个会记录峰值的计数器把它钉死。
 *    这个数字不是拍的：同时进行的还有首页刷新、封面预取与连接预热，
 *    OkHttp 自己的 `maxRequestsPerHost` 是 5 —— 再往上加只会把请求堆在队列里
 *    （探针实测排队本身不是瓶颈，堆队列只是把延迟换个地方藏起来）。
 * 2. **绝不吞 [CancellationException]**。
 *    v2.5.2 的教训（`runCatching` 捕获 `Throwable` ⇒ 把取消吞掉 ⇒ 超时形同虚设）
 *    在这里是**结构性**防住的：本函数只 `catch (e: Exception)` 并对
 *    `CancellationException` 立即 `throw`。取消是控制流，不是「这个子任务失败了」。
 * 3. **失败必须隔离**：一个子任务抛异常，**不影响**其它子任务的结果。
 *    铁律 4：非核心组件不得破坏核心播放链路。
 *
 * ## 与「先到先发布」的关系
 *
 * 本函数是**收集型**的（等齐了返回）。调用方若要「最快可用的先显示」，
 * 应当在每个 task 内部自己写 StateFlow，而不是等 [runAll] 返回 ——
 * 那正是 `SearchViewModel` 的 `select` 已经在做的事。
 * 本函数解决的是**另一件事**：让 N 个本来串行的请求在时间上重叠，并且收口错误。
 */
object BoundedParallel {

    /**
     * 默认并发上限。**4** 而不是 6：
     *
     * - 取链 / 歌词 / 封面 / 详情 恰好四件事，上限 4 = 「同时都发出去」；
     * - 冷启动时 OkHttp 还要同时跑首页三请求与封面预取，再高就会堆在同一条 h2 连接上；
     * - 低端机（3GB RAM、API 24）上每个请求背后都有一份 Gson 反序列化，
     *   单线程 IO 池上并发过高会让「谁先回来」变得不可预测。
     */
    const val DEFAULT_MAX_CONCURRENCY = 4

    /**
     * 并行跑完 [tasks]，**保持与入参相同的顺序**返回结局。
     *
     * @param tasks `(key, block)` 列表；`key` 只用于日志与 [FetchOutcome.Failed]/[FetchOutcome.Missing] 的自描述。
     * @param limit 并发上限，必须 >= 1（`coerceAtLeast(1)` 而不是抛异常 ——
     *   一个配置错误不该让播放链路挂掉）。
     */
    suspend fun <T> runAll(
        tasks: List<Pair<String, suspend () -> T?>>,
        limit: Int = DEFAULT_MAX_CONCURRENCY,
    ): List<FetchOutcome<T>> {
        if (tasks.isEmpty()) return emptyList()
        val semaphore = Semaphore(limit.coerceAtLeast(1))
        return coroutineScope {
            tasks
                .map { (key, block) ->
                    // async 而不是 launch：结果要按顺序收回来。
                    // 注意 async 的 start 是 DEFAULT ⇒ 每一个都会立刻开始等信号量，
                    // 于是「最多 limit 个真的在跑」由信号量保证，而不是由调用顺序保证。
                    async { runOne(key, semaphore, block) }
                }
                .awaitAll()
        }
    }

    /**
     * 便利重载：只要结果，失败/缺失**按 null 收进列表**。
     *
     * ⚠️ 它**故意**丢掉了「失败」与「缺失」的区别。只在调用方确实不关心区别时用
     * （例如「把能拿到的封面都预取一遍」）；任何会影响用户可见结论的地方请用 [runAll]。
     */
    suspend fun <T> valuesOrNull(
        tasks: List<Pair<String, suspend () -> T?>>,
        limit: Int = DEFAULT_MAX_CONCURRENCY,
    ): List<T?> = runAll(tasks, limit).map { (it as? FetchOutcome.Ok)?.value }

    private suspend fun <T> runOne(
        key: String,
        semaphore: Semaphore,
        block: suspend () -> T?,
    ): FetchOutcome<T> = semaphore.withPermit {
        try {
            val value = block()
            if (value == null) FetchOutcome.Missing(key) else FetchOutcome.Ok(value)
        } catch (e: CancellationException) {
            // ★ 约束 2：取消原样抛出。绝不允许落进下面的 catch。
            throw e
        } catch (e: Exception) {
            // ★ 约束 3：失败只影响自己。
            FetchOutcome.Failed(key, e)
        }
    }
}
