package com.takahashirinta.ncrust.audit

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.stats.*
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import java.util.ConcurrentModificationException
import java.util.concurrent.*

class ScheduledStatsSnapshotAuditProbe {
    private class MemoryContext:ContextWrapper(null) {
        private val prefs=MemoryPrefs()
        override fun getApplicationContext():Context=this
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=prefs
    }
    /** Only adds a latch around a normal fail-fast LinkedHashMap iterator. */
    private class PausedMap(val entered:CountDownLatch,val release:CountDownLatch):LinkedHashMap<String,Long>() {
        @Volatile var armed=false
        override val entries:MutableSet<MutableMap.MutableEntry<String,Long>>
            get() {
                val original=super.entries
                return object:AbstractMutableSet<MutableMap.MutableEntry<String,Long>>() {
                    override val size:Int get()=original.size
                    override fun add(element:MutableMap.MutableEntry<String,Long>):Boolean=throw UnsupportedOperationException()
                    override fun iterator():MutableIterator<MutableMap.MutableEntry<String,Long>> {
                        val actual=original.iterator()
                        return object:MutableIterator<MutableMap.MutableEntry<String,Long>> {
                            override fun hasNext()=actual.hasNext()
                            override fun remove()=actual.remove()
                            override fun next():MutableMap.MutableEntry<String,Long> {
                                if(armed) { armed=false;entered.countDown();check(release.await(5,TimeUnit.SECONDS)) }
                                return actual.next()
                            }
                        }
                    }
                }
            }
    }
    @Test fun readingStatsDuringAListeningTickCanThrowConcurrentModification() {
        val context=MemoryContext();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val aggregate=StatsAggregate()
        val map=PausedMap(entered,release).apply { put("2026-10-25",100);put("2026-10-26",200) }
        aggregate.javaClass.getDeclaredField("byDay").apply { isAccessible=true }.set(aggregate,map)
        val field=StatsStore.javaClass.getDeclaredField("aggregate").apply { isAccessible=true }
        val old=field.get(StatsStore);field.set(StatsStore,aggregate)
        val executor=Executors.newSingleThreadExecutor()
        try {
            map.armed=true
            val reading=executor.submit<StatsSnapshot> { StatsStore.snapshot(context) }
            assertTrue(entered.await(4,TimeUnit.SECONDS))
            StatsStore.addListen(context,listOf(DaySlice("2026-10-27",500)),"netease:101","netease","fixture","fixture",180_000,1000)
            release.countDown()
            val failure=runCatching { reading.get(4,TimeUnit.SECONDS) }.exceptionOrNull()
            assertTrue(failure is ExecutionException)
            assertTrue(failure!!.cause is ConcurrentModificationException)
            println("AUDIT stats snapshot: the real unlocked snapshot iterates byDay while a real addListen tick mutates it under another lock, raising ConcurrentModificationException")
        } finally {
            release.countDown();executor.shutdownNow()
            val job=StatsStore.javaClass.getDeclaredField("flushJob").apply { isAccessible=true }
            (job.get(StatsStore) as Job?)?.cancel();job.set(StatsStore,null)
            field.set(StatsStore,old)
        }
    }
}
