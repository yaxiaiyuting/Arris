package com.takahashirinta.ncrust.audit

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.cache.HomeSnapshot
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.PlaybackStateManager
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Characterizes defects via actual persistence entry points, with no HTTP or device. */
class ScheduledLifecycleAuditProbe {
    private class MemoryContext : ContextWrapper(null) {
        private val stores=mutableMapOf<String,SharedPreferences>()
        override fun getApplicationContext():Context=this
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=synchronized(stores) {
            stores.getOrPut(name) { MemoryPrefs() }
        }
    }
    private fun field(o:Any,name:String)=o.javaClass.getDeclaredField(name).apply { isAccessible=true }
    @Test fun queuedHomeSnapshotRetagsThePreviousAccountsDataAsTheNewOwner()=runBlocking {
        val context=MemoryContext()
        val app=field(CookieManager,"app");val oldApp=app.get(CookieManager);app.set(CookieManager,null)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val list=object:AbstractList<SongItem>() {
            override val size=1
            override fun get(index:Int):SongItem {
                entered.countDown();check(release.await(5,TimeUnit.SECONDS))
                return SongItem(id=101,name="private-A-recommendation",artists=null,album=null,duration=180_000)
            }
        }
        val scope=field(HomeSnapshot,"ioScope").get(HomeSnapshot) as CoroutineScope
        suspend fun settle()=withTimeout(6000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
        try {
            CookieManager.saveCookie(context,"MUSIC_U=fixture-A; __csrf=fixture-A")
            HomeSnapshot.save(context,daily=list)
            assertTrue(entered.await(4,TimeUnit.SECONDS))
            CookieManager.clearCookie(context)
            CookieManager.saveCookie(context,"MUSIC_U=fixture-B; __csrf=fixture-B")
            val ownerB=CookieManager.accountOwner()
            release.countDown();settle()
            val prefs=context.getSharedPreferences("ncrust_home_cache",0)
            assertEquals(ownerB,prefs.getString("owner",null))
            assertTrue(prefs.getString("daily_songs",null)!!.contains("private-A-recommendation"))
            ContentCache.clearAll();ContentCache.bindOwner(ownerB)
            assertTrue(HomeSnapshot.restoreIntoCache(context))
            assertEquals("private-A-recommendation",ContentCache.homeDailySongs!!.single().name)
            println("AUDIT home snapshot ownership: A's delayed save survives account clear, is labelled owner B, and restores A's data under B")
        } finally {
            release.countDown();settle();CookieManager.clearCookie(context)
            app.set(CookieManager,oldApp);ContentCache.clearAll()
        }
    }
    @Test fun seekingBackBeforeTheResumeThresholdLeavesTheOldResumePoint() {
        val context=MemoryContext()
        val cache=field(PlaybackStateManager,"positionCache");val old=cache.get(PlaybackStateManager)
        cache.set(PlaybackStateManager,null)
        try {
            PlaybackStateManager.saveSongPosition(context,101,60_000)
            assertEquals(60_000,PlaybackStateManager.getSongPosition(context,101))
            PlaybackStateManager.saveSongPosition(context,101,1_000)
            assertEquals(60_000,PlaybackStateManager.getSongPosition(context,101))
            println("AUDIT resume rewind: persisting position 1s after a 60s record keeps 60s; restarting the song resumes at the obsolete position")
        } finally {
            val job=field(PlaybackStateManager,"posFlushJob").get(PlaybackStateManager) as Job?
            job?.cancel();cache.set(PlaybackStateManager,old)
        }
    }
}
