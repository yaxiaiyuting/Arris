/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 */

package com.takahashirinta.ncrust.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 并行执行预取任务，限制单批并发并隔离各任务的失败。 */
internal object BoundedParallel {
    const val DEFAULT_MAX_CONCURRENCY = 4

    suspend fun runAll(
        tasks: List<suspend () -> Unit>,
        limit: Int = DEFAULT_MAX_CONCURRENCY,
    ) {
        if (tasks.isEmpty()) return
        val permits = Semaphore(limit.coerceAtLeast(1))
        coroutineScope {
            tasks.map { task ->
                async {
                    permits.withPermit {
                        try {
                            task()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // 可选任务失败，其他任务继续。
                        }
                    }
                }
            }.awaitAll()
        }
    }
}
