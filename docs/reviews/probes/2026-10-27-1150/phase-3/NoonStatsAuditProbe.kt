package com.takahashirinta.ncrust.audit

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.stats.*
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local persistence only. Passing asserts a defect exists, not that it is fixed. */
class NoonStatsAuditProbe {
    private class MemoryContext:ContextWrapper(null) {
        val prefs=ObservedPrefs()
        override fun getApplicationContext():Context=this
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=prefs
    }
    private class ObservedPrefs:SharedPreferences by MemoryPrefs() {
        private val memory=MemoryPrefs()
        @Volatile var onCommit:()->Unit={}
        @Synchronized override fun getString(key:String,defValue:String?)=memory.getString(key,defValue)
        override fun edit():SharedPreferences.Editor {
            val editor=memory.edit()
            return Proxy.newProxyInstance(javaClass.classLoader,arrayOf(SharedPreferences.Editor::class.java)) { proxy,method,args ->
                if(method.name=="commit" || method.name=="apply") synchronized(this) {
                    editor.commit();onCommit();if(method.name=="commit") true else null
                } else {
                    val v=method.invoke(editor,*(args?:emptyArray()));if(v is SharedPreferences.Editor) proxy else v
                }
            } as SharedPreferences.Editor
        }
    }
    /** Pause one queued IO start without changing the code that job executes. */
    private class HoldFirstDispatcher:CoroutineDispatcher() {
        private val first=AtomicBoolean(true)
        val held=AtomicReference<Pair<CoroutineContext,Runnable>>()
        override fun dispatch(context:CoroutineContext,block:Runnable) {
            if(first.compareAndSet(true,false)) held.set(context to block)
            else Dispatchers.IO.dispatch(context,block)
        }
        fun release() { held.getAndSet(null)?.let { Dispatchers.IO.dispatch(it.first,it.second) } }
    }
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply { isAccessible=true }
    @Test fun delayedClearRemovesNewListeningDataThatWasAlreadyFlushed()=runBlocking {
        val context=MemoryContext();val dispatcher=HoldFirstDispatcher()
        val scope=field(StatsStore,"ioScope").get(StatsStore) as CoroutineScope
        val scopeContext=scope.javaClass.declaredFields.single { CoroutineContext::class.java.isAssignableFrom(it.type) }.apply { isAccessible=true }
        val oldScopeContext=scopeContext.get(scope)
        val fields=listOf("aggregate","flushJob","generation").associateWith { field(StatsStore,it) }
        val originals=fields.mapValues { it.value.get(StatsStore) }
        val old=StatsAggregate().apply { addListen(listOf(DaySlice("2026-10-26",30_000)),"netease:1","netease","old","old",180_000,1) }
        scopeContext.set(scope,SupervisorJob()+dispatcher)
        fields.getValue("aggregate").set(StatsStore,old)
        fields.getValue("flushJob").set(StatsStore,null)
        try {
            StatsStore.clear(context)
            val clearJob=dispatcher.held.get().first[Job]!!
            StatsStore.addListen(context,listOf(DaySlice("2026-10-27",500)),"netease:2","netease","new","new",180_000,2)
            val beforeFlush=scope.coroutineContext[Job]!!.children.toSet()
            StatsStore.flushSoon(context)
            val flushing=scope.coroutineContext[Job]!!.children.single { it !in beforeFlush }
            withTimeout(5000) { flushing.join() }
            assertEquals(500L,StatsCodec.decode(context.prefs.getString("snapshot",null)).totalMs)
            dispatcher.release();withTimeout(5000) { clearJob.join() }
            assertNull(context.prefs.getString("snapshot",null))
            assertEquals(500L,StatsStore.snapshot(context).totalMs)
            fields.getValue("aggregate").set(StatsStore,null)
            assertEquals(0L,StatsStore.snapshot(context).totalMs)
            println("AUDIT stats clear order: clear's queued remove runs after a newer listening tick and successful flush in the same generation; memory has 500ms but a cold load has zero")
        } finally {
            dispatcher.release();scope.cancel();withTimeout(5000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
            fields.forEach { (name,f)->f.set(StatsStore,originals[name]) }
            scopeContext.set(scope,oldScopeContext)
        }
    }
    @Test fun olderSerializedSnapshotOverwritesANewerSuccessfulFlushWithoutAnyClear()=runBlocking {
        val context=MemoryContext()
        val scope=field(StatsStore,"ioScope").get(StatsStore) as CoroutineScope
        val stateFields=listOf("aggregate","flushJob").associateWith { field(StatsStore,it) }
        val oldState=stateFields.mapValues { it.value.get(StatsStore) }
        stateFields.getValue("aggregate").set(StatsStore,StatsAggregate())
        stateFields.getValue("flushJob").set(StatsStore,null)
        val gson=field(StatsCodec,"gson").get(StatsCodec) as Gson
        val factories=field(gson,"factories");val oldFactories=factories.get(gson) as List<TypeAdapterFactory>
        val cache=field(gson,"typeTokenCache").get(gson) as MutableMap<Any,Any>
        val oldCache=cache.toMap()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val first=AtomicBoolean(true);val wroteNew=CountDownLatch(1);val wroteOld=CountDownLatch(1)
        val gate=object:TypeAdapterFactory {
            override fun <T> create(g:Gson,type:TypeToken<T>):TypeAdapter<T>? {
                if(type.rawType!=StatsCodec.SnapshotDto::class.java) return null
                val delegate=g.getDelegateAdapter(this,type)
                return object:TypeAdapter<T>() {
                    override fun write(out:JsonWriter,value:T) {
                        if(first.compareAndSet(true,false)) { entered.countDown();check(release.await(5,TimeUnit.SECONDS)) }
                        delegate.write(out,value)
                    }
                    override fun read(input:JsonReader):T=delegate.read(input)
                }
            }
        }
        factories.set(gson,listOf(gate)+oldFactories);cache.clear()
        context.prefs.onCommit={ when(StatsCodec.decode(context.prefs.getString("snapshot",null)).totalMs) {500L->wroteNew.countDown();100L->wroteOld.countDown()} }
        try {
            StatsStore.addListen(context,listOf(DaySlice("2026-10-27",100)),"netease:2","netease","fixture","fixture",180_000,1)
            StatsStore.flushSoon(context)
            assertTrue(entered.await(4,TimeUnit.SECONDS))
            StatsStore.addListen(context,listOf(DaySlice("2026-10-27",400)),"netease:2","netease","fixture","fixture",180_000,2)
            StatsStore.flushSoon(context)
            assertTrue(wroteNew.await(4,TimeUnit.SECONDS))
            assertEquals(500L,StatsCodec.decode(context.prefs.getString("snapshot",null)).totalMs)
            release.countDown();assertTrue(wroteOld.await(4,TimeUnit.SECONDS))
            assertEquals(100L,StatsCodec.decode(context.prefs.getString("snapshot",null)).totalMs)
            assertEquals(500L,StatsStore.snapshot(context).totalMs)
            println("AUDIT stats flush order: older 100ms serialization commits after a newer 500ms snapshot; no clear/generation change occurs, yet disk regresses to 100ms")
        } finally {
            release.countDown()
            (field(StatsStore,"flushJob").get(StatsStore) as Job?)?.cancel()
            withTimeout(5000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
            factories.set(gson,oldFactories);cache.clear();cache.putAll(oldCache)
            stateFields.forEach { (name,f)->f.set(StatsStore,oldState[name]) }
        }
    }
}
