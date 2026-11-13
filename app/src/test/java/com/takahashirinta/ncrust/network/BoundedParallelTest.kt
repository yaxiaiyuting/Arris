/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 */

package com.takahashirinta.ncrust.network

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedParallelTest {
    @Test fun batchRespectsItsParallelismLimit() = runBlocking {
        val live = AtomicInteger()
        val peak = AtomicInteger()
        val completed = AtomicInteger()
        val tasks = List(12) {
            suspend {
                val now = live.incrementAndGet()
                peak.updateAndGet { maxOf(it, now) }
                delay(30)
                live.decrementAndGet()
                completed.incrementAndGet()
                Unit
            }
        }

        BoundedParallel.runAll(tasks, limit = 3)

        assertEquals(12, completed.get())
        assertEquals(3, peak.get())
    }

    @Test fun aFailedTaskDoesNotInterruptItsPeers() = runBlocking {
        val completed = mutableListOf<String>()
        BoundedParallel.runAll(listOf(
            { completed.add("first") },
            { throw IllegalStateException("fixture") },
            { completed.add("last") },
        ))
        assertEquals(setOf("first", "last"), completed.toSet())
    }

    @Test fun cancellingTheParentStopsWaitingAndRunningTasks() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val job = launch {
            BoundedParallel.runAll(listOf(
                {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                },
                { error("A waiting task should be cancelled") },
            ), limit = 1)
        }
        withTimeout(5_000L) { started.await() }
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(stopped.isCompleted)
    }

    @Test fun childCancellationPropagatesToTheBatch() = runBlocking {
        val cancelled = try {
            BoundedParallel.runAll(listOf({ throw CancellationException("fixture") }))
            false
        } catch (_: CancellationException) {
            true
        }
        assertTrue(cancelled)
    }

    @Test fun emptyBatchReturnsImmediately() = runBlocking {
        BoundedParallel.runAll(emptyList())
    }

    @Test fun invalidLimitRunsTasksSequentially() = runBlocking {
        val running = AtomicInteger()
        val completed = AtomicInteger()
        val tasks = List(4) {
            suspend {
                assertEquals(1, running.incrementAndGet())
                delay(10)
                running.decrementAndGet()
                completed.incrementAndGet()
                Unit
            }
        }
        BoundedParallel.runAll(tasks, limit = 0)
        assertEquals(4, completed.get())
    }
}
