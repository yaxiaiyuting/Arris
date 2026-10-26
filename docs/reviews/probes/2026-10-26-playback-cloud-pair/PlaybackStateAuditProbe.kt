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

/** Characterization of existing defects; passing assertions are not repair acceptance. */
class PlaybackStateAuditProbe {
    private class ProbePrefs(private val memory: SharedPreferences = MemoryPrefs()) : SharedPreferences by memory {
        @Volatile var onReadQueue: (() -> Unit)? = null
        val removedQueue = CountDownLatch(1)
        override fun getString(key: String, fallback: String?): String? {
            val value = memory.getString(key, fallback)
            if (key == "queue") onReadQueue?.let { onReadQueue = null; it() }
            return value
        }
        override fun edit(): SharedPreferences.Editor {
            val editor = memory.edit()
            return object : SharedPreferences.Editor by editor {
                var removed = false
                override fun remove(key: String): SharedPreferences.Editor {
                    if (key == "queue") removed = true
                    editor.remove(key); return this
                }
                override fun putString(key: String, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                override fun putInt(key: String, value: Int): SharedPreferences.Editor { editor.putInt(key, value); return this }
                override fun apply() { editor.apply(); if (removed) removedQueue.countDown() }
            }
        }
    }
    private class ProbeContext(val prefs: ProbePrefs = ProbePrefs()) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }
    private fun song(id: Long) = SongItem(id, "fixture-$id", null, null, 200000)

    @Test fun cancellingQueueReadDeletesAnOtherwiseHealthySavedQueue() = runBlocking {
        val context = ProbeContext()
        context.prefs.edit().putString("queue", """[{"id":101,"name":"fixture"}]""").putInt("queue_index", 0).apply()
        val reading = launch(start = CoroutineStart.LAZY) { PlaybackStateManager.getQueue(context) }
        context.prefs.onReadQueue = { reading.cancel() }
        reading.start(); reading.join()
        assertTrue(context.prefs.removedQueue.await(3, TimeUnit.SECONDS))
        assertFalse(context.prefs.contains("queue"))
        println("AUDIT playback: cancellation between queue JSON read and Default parsing clears healthy persisted queue")
    }

    @Test fun replacingAnInFlightQueueFlushLetsOldCancellationDeleteNewPendingQueue() = runBlocking {
        val context = ProbeContext()
        val serializing = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val blocked = object : AbstractList<SongItem>() {
            override val size: Int get() = 1
            override fun get(index: Int): SongItem {
                serializing.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                return song(101)
            }
        }
        try {
            PlaybackStateManager.saveQueue(context, blocked, 0)
            assertTrue(serializing.await(4, TimeUnit.SECONDS))
            PlaybackStateManager.saveQueue(context, listOf(song(202)), 0)
            resume.countDown()
            assertTrue(context.prefs.removedQueue.await(4, TimeUnit.SECONDS))
            delay(350)
            assertNull(PlaybackStateManager.getQueue(context))
            val field = PlaybackStateManager.javaClass.getDeclaredField("pendingQueue").apply { isAccessible = true }
            assertNull(field.get(PlaybackStateManager))
            println("AUDIT playback: superseded A flush catches cancellation, clearQueue cancels B and discards its pending snapshot")
        } finally { resume.countDown(); PlaybackStateManager.clearQueue(context) }
    }

    @Test fun clearingPlayerStateAlsoErasesTheUsersPersistedPlayMode() {
        val context = ProbeContext()
        PlaybackStateManager.savePlayMode(context, 3)
        PlaybackStateManager.saveState(context, 101, "fixture", "", "", false)
        assertEquals(3, PlaybackStateManager.getPlayMode(context))
        PlaybackStateManager.clearState(context)
        assertFalse(PlaybackStateManager.hasState(context))
        assertEquals(0, PlaybackStateManager.getPlayMode(context))
        println("AUDIT playback: stop clearState clears the whole prefs file; persisted play mode 3 -> 0")
    }
}
