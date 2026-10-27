package com.takahashirinta.ncrust.audit

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.cache.*
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Local memory only. Passing means the defect exists, not fixed. */
class EveningCacheAuditProbe {
    private class UnavailableCacheContext:ContextWrapper(null) {
        val prefs=MemoryPrefs()
        var cacheOpenAttempts=0
        override fun getApplicationContext():Context=this
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=prefs
        override fun getFilesDir():File { cacheOpenAttempts++;throw IllegalStateException("local fixture: cache directory unavailable") }
    }
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply { isAccessible=true }
    private fun isolated(action:(UnavailableCacheContext)->Unit) {
        val entries=listOf(OfflineAudioCache to "cache",OfflineLibrary to "index",OfflineUrlStore to "index")
        val originals=entries.map { (o,n)->field(o,n).get(o) }
        entries.forEach { (o,n)->field(o,n).set(o,null) }
        try { action(UnavailableCacheContext()) }
        finally { entries.zip(originals).forEach { (entry,value)->field(entry.first,entry.second).set(entry.first,value) } }
    }
    private fun seed(c:UnavailableCacheContext) {
        val key=OfflineKeys.key(701,"standard")
        assertTrue(OfflineLibrary.record(c,701,"local fixture","fixture",null,180000,key,"standard",1))
        OfflineUrlStore.remember(c,key,"https://fixture.invalid/audio")
        assertEquals(1,OfflineLibrary.size(c))
        assertNotNull(OfflineUrlStore.recall(c,701,listOf("standard")))
    }
    @Test fun failedSingleDeletionDropsPersistedMetadataAndUrlEvenThoughNoCacheCanBeOpened()=isolated { c ->
        seed(c)
        assertFalse(OfflineAudioCache.removeSong(c,701))
        assertTrue(c.cacheOpenAttempts>0)
        assertEquals(0,OfflineLibrary.size(c))
        assertNull(OfflineUrlStore.recall(c,701,listOf("standard")))
        field(OfflineLibrary,"index").set(OfflineLibrary,null)
        assertEquals(0,OfflineLibrary.size(c))
        println("AUDIT deletion failure: real removeSong returns false after injected local cache-opening failure, but both track metadata and URL are removed and remain missing after a cold metadata reload. No Cache/Android file operations or network performed; UI success toast is source evidence only")
    }
    @Test fun failedClearStillErasesBothOfflineIndexesAndHasNoFailureResult()=isolated { c ->
        seed(c)
        OfflineAudioCache.clear(c)
        assertTrue(c.cacheOpenAttempts>0)
        field(OfflineLibrary,"index").set(OfflineLibrary,null)
        field(OfflineUrlStore,"index").set(OfflineUrlStore,null)
        assertEquals(0,OfflineLibrary.size(c))
        assertNull(OfflineUrlStore.recall(c,701,listOf("standard")))
        println("AUDIT clear failure: real clear swallows a local cache-opening failure and persists empty URL/track indexes, so existing bytes cannot have been removed by that invocation but their management metadata is lost. Fault injection at Context.filesDir, not a real filesystem or device reproduction")
    }
}
