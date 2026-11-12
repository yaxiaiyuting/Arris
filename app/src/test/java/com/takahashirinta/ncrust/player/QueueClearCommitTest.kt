package com.takahashirinta.ncrust.player

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the asynchronous save and clear entry points with in-memory preferences. */
class QueueClearCommitTest {
    private class MemoryContext : ContextWrapper(null) {
        val prefs = MemoryPrefs()
        @Volatile var beforePrefs: (() -> Unit)? = null
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val action = beforePrefs
            beforePrefs = null
            action?.invoke()
            return prefs
        }
    }

    private val manager = PlaybackStateManager
    private fun song(id: Long) = SongItem(id, "fixture $id", null, null, null)

    private suspend fun awaitWrites() {
        val scopeField = manager.javaClass.getDeclaredField("ioScope").apply { isAccessible = true }
        val scope = scopeField.get(manager) as CoroutineScope
        withTimeout(6_000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
    }

    @Test fun aClearBeforeTheCommitGateInvalidatesThePendingFlush() = runBlocking {
        val context = MemoryContext()
        val cleared = AtomicBoolean()
        try {
            context.beforePrefs = { manager.clearQueue(context); cleared.set(true) }
            manager.saveQueue(context, listOf(song(505)), 0)
            awaitWrites()
            assertTrue("clear executed at the post-check/pre-write boundary", cleared.get())
            assertFalse("old flush must not restore cleared queue", context.prefs.contains("queue"))
        } finally {
            manager.clearQueue(context)
            awaitWrites()
        }
    }

    @Test fun cursorWritesReuseTheJsonUntilAnExplicitQueueWriteOrClear() = runBlocking {
        val context = MemoryContext()
        val items = MutableList(5_000) { song(it + 1L) }
        val reads = AtomicInteger()
        val queue = object : AbstractList<SongItem>() {
            override val size get() = items.size
            override fun get(index: Int): SongItem {
                reads.incrementAndGet()
                return items[index]
            }
        }
        try {
            manager.saveQueue(context, queue, 0)
            awaitWrites()
            assertEquals(5_000, reads.get())
            val firstJson = context.prefs.getString("queue", null)
            for (index in 1..3) {
                manager.saveQueueCursor(context, queue, index)
                awaitWrites()
                assertEquals(index, context.prefs.getInt("queue_index", -1))
                assertSame(firstJson, context.prefs.getString("queue", null))
            }
            assertEquals(5_000, reads.get())

            items[0] = song(6_000)
            manager.saveQueue(context, queue, 0)
            awaitWrites()
            assertEquals(10_000, reads.get())
            assertEquals(6_000L, manager.getQueue(context)?.first?.first()?.id)

            manager.clearQueue(context)
            manager.saveQueueCursor(context, queue, 1)
            awaitWrites()
            assertEquals(15_000, reads.get())
            assertEquals(1, context.prefs.getInt("queue_index", -1))
        } finally {
            manager.clearQueue(context)
            awaitWrites()
        }
    }

    @Test fun aNewQueueWinsWhileAnOlderQueueIsEncoding() = runBlocking {
        val context = MemoryContext()
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val oldQueue = object : AbstractList<SongItem>() {
            override val size = 1
            override fun get(index: Int): SongItem {
                started.countDown()
                check(resume.await(4, TimeUnit.SECONDS))
                return song(1)
            }
        }
        try {
            manager.saveQueue(context, oldQueue, 0)
            assertTrue(started.await(4, TimeUnit.SECONDS))
            manager.saveQueueCursor(context, listOf(song(2), song(3)), 1)
            resume.countDown()
            awaitWrites()
            val saved = manager.getQueue(context)
            assertEquals(listOf(2L, 3L), saved?.first?.map(SongItem::id))
            assertEquals(1, saved?.second)
        } finally {
            resume.countDown()
            manager.clearQueue(context)
            awaitWrites()
        }
    }

    @Test fun aNewerShuffleWinsWhileTheOlderRoundIsEncoding() = runBlocking {
        val context = MemoryContext()
        val queue = listOf(song(1), song(2), song(3))
        val keys = queue.map { QueueKeys.keyOf(it).tag }
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val caller = Thread.currentThread()
        manager.saveShuffle(context, listOf(2, 0, 1), 0, 2) {
            assertNotSame(caller, Thread.currentThread())
            started.countDown()
            check(resume.await(4, TimeUnit.SECONDS))
            keys
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(started.await(4, TimeUnit.SECONDS)) }
            manager.saveShuffle(context, listOf(2, 0, 1), 1, 0) { keys }
            resume.countDown()
            awaitWrites()
            assertEquals(ShuffleSnapshot(keys, listOf(2, 0, 1), 1), manager.restoreShuffle(context, queue, 0))
        } finally {
            resume.countDown()
            manager.clearQueue(context)
            awaitWrites()
        }
    }

    @Test fun clearingDuringShuffleCommitInvalidatesTheRound() = runBlocking {
        val context = MemoryContext()
        val queue = listOf(song(1))
        try {
            val cleared = AtomicBoolean()
            context.beforePrefs = { manager.clearQueue(context); cleared.set(true) }
            manager.saveShuffle(context, listOf(0), 0, 0) { queue.map { QueueKeys.keyOf(it).tag } }
            awaitWrites()
            assertTrue(cleared.get())
            assertNull(manager.restoreShuffle(context, queue, 0))
        } finally {
            manager.clearQueue(context)
        }
    }

    @Test fun acceptedShuffleWriteOutlivesItsCaller() = runBlocking {
        val context = MemoryContext()
        val queue = listOf(song(1))
        val keys = queue.map { QueueKeys.keyOf(it).tag }
        val started = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val caller = launch {
            manager.saveShuffle(context, listOf(0), 0, 0) {
                started.countDown()
                check(resume.await(4, TimeUnit.SECONDS))
                keys
            }
            awaitCancellation()
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(started.await(4, TimeUnit.SECONDS)) }
            caller.cancelAndJoin()
            resume.countDown()
            awaitWrites()
            assertEquals(ShuffleSnapshot(keys, listOf(0), 0), manager.restoreShuffle(context, queue, 0))
        } finally {
            resume.countDown()
            caller.cancelAndJoin()
            manager.clearQueue(context)
            awaitWrites()
        }
    }
}
