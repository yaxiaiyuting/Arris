/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * J01 回归：同一 clear 代次内的持久化反序。
 */

package com.takahashirinta.ncrust.stats

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * J01：统计落盘的**提交序**。
 *
 * 审查证据是两条真实持久化入口的隔离并发探针：
 *
 * - `olderSerializedSnapshotOverwritesANewerSuccessfulFlushWithoutAnyClear` —— 旧快照晚到落盘，
 *   把新值覆盖回旧值（全程没有 clear，旧代码只校验代次，放行）；
 * - `delayedClearRemovesNewListeningDataThatWasAlreadyFlushed` —— 清空的异步删除晚到，
 *   把「清空之后已经成功落盘的新数据」删掉。
 *
 * 这里直接驱动修复后的判定入口 [StatsCommitGate]（时序由票号显式表达，不靠撞线程），
 * 最后一条再用真实 [StatsStore] 走一遍，确认闸门确实被接在两条生产路径上。
 */
class StatsCommitGateTest {

    private val prefs = MemoryPrefs()

    private fun stored(): String? = prefs.getString(StatsStore.KEY_SNAPSHOT, null)

    /**
     * 反序 1：旧快照**晚于**新快照提交时不得覆盖。
     *
     * 票号在 `StatsStore` 里是「拷快照的同一把锁内」领的，所以票号顺序 == 内容新旧顺序；
     * 这里按真实顺序领票（先旧后新），再让旧的最后提交。
     */
    @Test
    fun `anOlderSnapshotCannotOverwriteANewerCommittedOne()`() {
        val gate = StatsCommitGate()
        val generation = gate.currentGeneration()
        val olderTicket = gate.newTicket()
        val newerTicket = gate.newTicket()

        assertTrue(gate.commit(prefs, generation, newerTicket, "new-500ms"))
        assertFalse(
            "旧快照晚到必须被票号挡掉（否则磁盘回退成 100ms）",
            gate.commit(prefs, generation, olderTicket, "old-100ms"),
        )
        assertEquals("new-500ms", stored())
    }

    /** 清空之后，清空前领的票一律作废（代次已变）。 */
    @Test
    fun `aSnapshotTakenBeforeClearCannotBeWrittenAfterIt()`() {
        val gate = StatsCommitGate()
        val generation = gate.currentGeneration()
        val ticket = gate.newTicket()

        gate.beginClear()

        assertFalse(gate.commit(prefs, generation, ticket, "before-clear"))
        assertNull(stored())
    }

    /**
     * 反序 2：清空的删除**晚于**清空后的新落盘 —— 那批新数据必须活下来。
     *
     * 这是「内存里有 500ms、磁盘却是零」的那条路径：代次因清空已经前进并且**不会再变**
     * （新增采样不推进代次），所以只有票号能识别出「清空之后已经有更新的内容落盘」。
     */
    @Test
    fun `clearDoesNotDeleteDataFlushedAfterIt()`() {
        val gate = StatsCommitGate()
        val clearTicket = gate.beginClear()
        val freshTicket = gate.newTicket()

        assertTrue(gate.commit(prefs, clearTicket.generation, freshTicket, "after-clear"))
        assertFalse(
            "清空后已经落盘的新数据不许被迟到的删除抹掉",
            gate.deleteAfterClear(prefs, clearTicket),
        )
        assertEquals("after-clear", stored())
    }

    /** 清空之后什么都没落盘 ⇒ 删除必须真的执行（用户的「清除统计」不能被票号逻辑吃掉）。 */
    @Test
    fun `clearDeletesWhenNothingNewerWasFlushed()`() {
        prefs.edit().putString(StatsStore.KEY_SNAPSHOT, "old").commit()
        val gate = StatsCommitGate()
        val clearTicket = gate.beginClear()

        assertTrue(gate.deleteAfterClear(prefs, clearTicket))
        assertNull(stored())
    }

    /** 连续两次清空：第一张票作废，由第二次清空负责删除。 */
    @Test
    fun `aSecondClearSupersedesTheFirstOnesDelete()`() {
        prefs.edit().putString(StatsStore.KEY_SNAPSHOT, "old").commit()
        val gate = StatsCommitGate()
        val first = gate.beginClear()
        val second = gate.beginClear()

        assertFalse(gate.deleteAfterClear(prefs, first))
        assertTrue(gate.deleteAfterClear(prefs, second))
        assertNull(stored())
    }

    /**
     * 接线：真实 [StatsStore] 的 `flushSoon` / `clear` 都必须走这道闸。
     *
     * 只断言「写了 / 删了」这一层（竞态时序由上面几条覆盖）：如果哪天有人把
     * `commitGate` 从这两条路径上摘掉，这里会立刻失败。
     */
    @Test
    fun `StatsStoreFlushAndClearGoThroughTheGate()`() = runBlocking {
        val context = MemoryContext()
        val storePrefs = context.store()
        val now = System.currentTimeMillis()
        val slices = StatsMath.splitAcrossDays(now - 1_000L, now, java.util.TimeZone.getDefault())
        fun storedNow(): String? = storePrefs.getString(StatsStore.KEY_SNAPSHOT, null)

        StatsStore.addListen(context, slices, "song:1", "ncrust", "n", "a", 200_000L, now)
        StatsStore.flushSoon(context)
        await("落盘") { !storedNow().isNullOrEmpty() }
        assertTrue("flushSoon 必须把快照写进 prefs", !storedNow().isNullOrEmpty())

        StatsStore.clear(context)
        await("清空") { storedNow().isNullOrEmpty() }
        assertNull("clear 必须把快照删掉", storedNow())
    }

    /** 轮询式等待（生产路径是 fire-and-forget 协程，这里只等一个可观测结果）。 */
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("等待${what}超时")
    }

    /** `ncrust_stats` 指向内存 prefs 的最小 Context（与仓库既有探针同一套夹具）。 */
    private class MemoryContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, MemoryPrefs>()

        fun store(): MemoryPrefs = synchronized(stores) {
            stores.getOrPut(StatsStore.PREFS_NAME) { MemoryPrefs() }
        }

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            synchronized(stores) { stores.getOrPut(name) { MemoryPrefs() } }
    }
}
