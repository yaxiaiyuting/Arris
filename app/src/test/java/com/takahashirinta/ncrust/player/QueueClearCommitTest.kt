package com.takahashirinta.ncrust.player

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.PlaybackStateManager
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Real async queue/clear ordering with local memory storage. */
class QueueClearCommitTest {
    private class MemoryContext : ContextWrapper(null) {
        val prefs=MemoryPrefs()
        @Volatile var beforePrefs: (() -> Unit)? = null
        override fun getApplicationContext():Context=this
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
            val action=beforePrefs
            beforePrefs=null
            action?.invoke()
            return prefs
        }
    }
    @Test fun aClearBeforeTheCommitGateInvalidatesThePendingFlush()=runBlocking {
        val manager=PlaybackStateManager
        fun field(name:String)=manager.javaClass.getDeclaredField(name).apply { isAccessible=true }
        val fields=listOf("pendingQueue","pendingIndex","pendingAppContext","flushJob","pendingGeneration")
            .map { field(it) }.map { it to it.get(manager) }
        val context=MemoryContext();val cleared=AtomicBoolean()
        try {
            context.beforePrefs={ PlaybackStateManager.clearQueue(context);cleared.set(true) }
            PlaybackStateManager.saveQueue(context,listOf(SongItem(505,"fixture queue",null,null,null)),0)
            val scope=field("ioScope").get(manager) as CoroutineScope
            withTimeout(6000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
            assertTrue("clear executed at the post-check/pre-write boundary",cleared.get())
            assertFalse("old flush must not restore cleared queue",context.prefs.contains("queue"))
        } finally { fields.asReversed().forEach { (f,v)->f.set(manager,v) } }
    }
}
