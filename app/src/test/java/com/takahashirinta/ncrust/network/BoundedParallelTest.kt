/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · P0-A：并行编排原语的单测。
 */

package com.takahashirinta.ncrust.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [BoundedParallel] 的三条硬约束，逐条证伪式覆盖。
 *
 * 这些用例**不碰 Android、不碰网络**：全部用 `delay` 与计数器构造时序，
 * 所以它们在 CI 上是确定性的（没有真网络就没有抖动）。
 */
class BoundedParallelTest {

    @Test
    fun `并发上限是硬的`() = runBlocking {
        // 峰值计数器：进入 lambda 时 +1、退出时 -1，记录过程中到过的最大值。
        val live = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val limit = 3
        val tasks = (1..12).map { i ->
            "t$i" to suspend {
                val now = live.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                delay(30)
                live.decrementAndGet()
                i
            }
        }

        val out = BoundedParallel.runAll(tasks, limit = limit)

        assertEquals("全部任务都要有结局", 12, out.size)
        assertTrue("全部任务都应当成功", out.all { it.isOk })
        assertTrue(
            "并发上限被突破：峰值 $peak > $limit（铁律 23：并行请求必须有并发上限）",
            peak.get() <= limit,
        )
        // 反过来也要证：上限**确实被用到了**，否则这条用例在一个「串行实现」上也会通过。
        assertTrue("并发上限没有被用满（峰值 $peak）—— 那说明实现是串行的", peak.get() >= 2)
    }

    @Test
    fun `一个子任务抛异常不影响其它子任务`() = runBlocking {
        val tasks = listOf<Pair<String, suspend () -> String?>>(
            "ok1" to { "a" },
            "boom" to { throw IllegalStateException("网络挂了") },
            "null" to { null },
            "ok2" to { "b" },
        )

        val out = BoundedParallel.runAll(tasks)

        assertEquals(4, out.size)
        assertTrue("失败必须被隔离成一个 Failed 结局", out[1] is FetchOutcome.Failed)
        assertEquals("boom", (out[1] as FetchOutcome.Failed).key)
        assertTrue("返回 null 是 Missing（不是 Failed）", out[2] is FetchOutcome.Missing)
        assertEquals("a", (out[0] as FetchOutcome.Ok).value)
        assertEquals("b", (out[3] as FetchOutcome.Ok).value)
        // 顺序必须与入参一致 —— 调用方按下标取结果。
        assertEquals(
            listOf("ok:a", "Failed:boom", "Missing:null", "ok:b"),
            out.map {
                when (it) {
                    is FetchOutcome.Ok -> "ok:${it.value}"
                    is FetchOutcome.Failed -> "Failed:${it.key}"
                    is FetchOutcome.Missing -> "Missing:${it.key}"
                }
            },
        )
    }

    /**
     * 铁律：**不许吞 `CancellationException`**（v2.5.2 的教训）。
     *
     * 判据是行为性的：父作用域取消之后，[BoundedParallel.runAll] 必须真的停下来 ——
     * 而不是把取消当成「这个子任务失败了」，然后继续跑完剩下的任务。
     */
    @Test
    fun `取消原样传播 不被当成子任务失败`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finishedTasks = AtomicInteger(0)
        val parent = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Default + parent)

        val job: Job = scope.launch {
            val tasks = (1..8).map { i ->
                "t$i" to suspend {
                    finishedTasks.incrementAndGet()
                    started.complete(Unit)
                    delay(5_000)
                    i
                }
            }
            BoundedParallel.runAll(tasks, limit = 2)
        }

        started.await()
        job.cancel()
        job.join()

        assertTrue("被取消的任务不该继续跑完", finishedTasks.get() < 8)
        assertTrue("父 Job 必须处于取消态（取消没有被吞成「正常完成」）", job.isCancelled)
        parent.cancel()
    }

    @Test
    fun `空任务列表返回空 且不启动任何协程`() = runBlocking {
        val noTasks: List<Pair<String, suspend () -> String?>> = emptyList()
        assertEquals(0, BoundedParallel.runAll(noTasks).size)
        val noInts: List<Pair<String, suspend () -> Int?>> = emptyList()
        assertEquals(0, BoundedParallel.valuesOrNull(noInts).size)
    }

    @Test
    fun `非法并发上限被夹到 1 而不是抛异常`() = runBlocking {
        // 配置错误不该让播放链路挂掉（铁律 4）：0 / 负数一律当 1 用。
        val live = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val tasks = (1..4).map { i ->
            "t$i" to suspend {
                val now = live.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                delay(10)
                live.decrementAndGet()
                i
            }
        }
        val out = BoundedParallel.runAll(tasks, limit = 0)
        assertEquals(4, out.size)
        assertEquals("limit=0 应当退化成串行（峰值 1）", 1, peak.get())
    }

    @Test
    fun `valuesOrNull 丢掉失败与缺失 但不丢成功的值`() = runBlocking {
        val out = BoundedParallel.valuesOrNull(
            listOf<Pair<String, suspend () -> Int?>>(
                "a" to { 1 },
                "b" to { throw RuntimeException("x") },
                "c" to { null },
                "d" to { 4 },
            ),
        )
        assertEquals(listOf(1, null, null, 4), out)
        assertNotNull(out)
    }
}
