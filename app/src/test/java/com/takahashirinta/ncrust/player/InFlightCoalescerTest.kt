package com.takahashirinta.ncrust.player

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.1.10 · C1：`(songId, level)` 取链的**在飞合并**。
 *
 * 为什么这五条用例是这五条：合并的唯一价值是「同 key 只发一次」，而它最容易写错的地方
 * 恰好也是四处 —— ① 两个调用者都发了；② 合并之后把结果也缓存住（下一个同 key 拿到旧值）；
 * ③ 一个等待者取消，把所有人的请求连坐砍掉（这正是服务端看到的 `RST_STREAM(CANCEL)`）；
 * ④ 失败没人广播，等待者自己再发一次或永远挂着。
 *
 * 用 `CoroutineStart.UNDISPATCHED` + 两个 `CompletableDeferred` 闸门（「已开始」/「放行」）
 * 把「谁先登记、谁在等」钉死，不依赖调度顺序。
 *
 * 第六条（发起方被取消 → 等待方接管）是这份实现的核心取舍：请求跑在**发起方自己的 job**
 * 里（切歌即取消，与 v3.7.5 一致），所以必须证明「发起方死了不会把等待方一起拖死」。
 */
class InFlightCoalescerTest {

    @Test
    fun `同 key 并发只执行一次、拿到同一份结果`() = runBlocking {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val coalescer = InFlightCoalescer<Long, String>()
            var calls = 0
            var first: String? = null
            var second: String? = null

            val leader = launch(start = CoroutineStart.UNDISPATCHED) {
                first = coalescer.run(1L) { calls++; started.complete(Unit); gate.await(); "url-A" }
            }
            // 等发起方真的进到请求体里并挂在闸门上
            started.await()
            assertEquals("在飞表里只有一条", 1, coalescer.inFlightCount())

            // 此刻后到者必须 join 而不是再发一次
            val joiner = launch(start = CoroutineStart.UNDISPATCHED) {
                second = coalescer.run(1L) { calls++; "url-B" }
            }
            assertEquals("第二路不该再执行请求体", 1, calls)

            gate.complete(Unit)
            leader.join()
            joiner.join()
            assertEquals("url-A", first)
            assertEquals("两路必须拿到同一份结果", "url-A", second)
            assertEquals(1, calls)
            assertEquals("完成后必须出表", 0, coalescer.inFlightCount())
    }

    @Test
    fun `不同 key 各自执行`() = runBlocking {
            val coalescer = InFlightCoalescer<Long, String>()
            var calls = 0
            assertEquals("a", coalescer.run(1L) { calls++; "a" })
            assertEquals("b", coalescer.run(2L) { calls++; "b" })
            assertEquals(2, calls)
    }

    @Test
    fun `完成后不缓存成功值`() = runBlocking {
            val coalescer = InFlightCoalescer<Long, String>()
            var calls = 0
            assertEquals("a", coalescer.run(7L) { calls++; "a" })
            assertEquals("同一个 key 的下一次必须真的重新请求", "b", coalescer.run(7L) { calls++; "b" })
            assertEquals(2, calls)
    }

    @Test
    fun `失败广播给所有等待者`() = runBlocking {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val coalescer = InFlightCoalescer<Long, String>()
            var calls = 0
            var firstError: Throwable? = null
            var secondError: Throwable? = null

            val leader = launch(start = CoroutineStart.UNDISPATCHED) {
                firstError = runCatching {
                    coalescer.run(9L) { calls++; started.complete(Unit); gate.await(); throw IOException("boom") }
                }.exceptionOrNull()
            }
            started.await()
            val joiner = launch(start = CoroutineStart.UNDISPATCHED) {
                secondError = runCatching { coalescer.run(9L) { calls++; "dup" } }.exceptionOrNull()
            }
            gate.complete(Unit)
            leader.join()
            joiner.join()

            assertEquals(1, calls)
            assertTrue("发起方拿到原始失败", firstError is IOException)
            assertTrue("等待方也拿到同一份失败，不能自己再发一次", secondError is IOException)
            assertEquals(0, coalescer.inFlightCount())
    }

    @Test
    fun `换网丢弃在飞登记，新调用者重新发一份`() = runBlocking {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val coalescer = InFlightCoalescer<Long, String>()
            var calls = 0
            var oldResult: String? = null

            val old = launch(start = CoroutineStart.UNDISPATCHED) {
                oldResult = coalescer.run(5L) { calls++; started.complete(Unit); gate.await(); "old-route" }
            }
            started.await()
            coalescer.clear()
            assertEquals(0, coalescer.inFlightCount())
            // 新路由的调用者不得 join 旧路由的运行中请求
            assertEquals("new-route", coalescer.run(5L) { calls++; "new-route" })
            gate.complete(Unit)
            old.join()
            assertEquals("旧请求照旧跑完（不取消）", "old-route", oldResult)
            assertEquals(2, calls)
    }

    @Test
    fun `发起方被取消时等待方接管并拿到自己的结果`() = runBlocking {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val coalescer = InFlightCoalescer<Long, String>()
            var calls = 0
            var joinerResult: String? = null

            val leader = launch(start = CoroutineStart.UNDISPATCHED) {
                coalescer.run(4L) { calls++; started.complete(Unit); gate.await(); "leader-url" }
            }
            started.await()
            val joiner = launch(start = CoroutineStart.UNDISPATCHED) {
                joinerResult = coalescer.run(4L) { calls++; "takeover-url" }
            }
            // 发起方被取消（切歌）：等待方不得收到「被牵连的取消」，必须自己再发一次
            leader.cancel()
            leader.join()
            assertEquals("takeover-url", joinerResult)
            joiner.join()
            assertEquals("发起方那一份 + 接管那一份", 2, calls)
            assertEquals(0, coalescer.inFlightCount())
    }

    @Test
    fun `等待者取消不连坐发起方`() = runBlocking {
            val started = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val coalescer = InFlightCoalescer<Long, String>()
            var calls = 0
            var leaderResult: String? = null

            val leader = launch(start = CoroutineStart.UNDISPATCHED) {
                leaderResult = coalescer.run(3L) { calls++; started.complete(Unit); gate.await(); "url" }
            }
            started.await()
            val joiner = launch(start = CoroutineStart.UNDISPATCHED) {
                coalescer.run(3L) { calls++; "dup" }
            }
            joiner.cancel()
            joiner.join()

            gate.complete(Unit)
            leader.join()
            assertEquals("发起方仍然拿到结果（请求没有被砍掉）", "url", leaderResult)
            assertEquals(1, calls)
    }
}
