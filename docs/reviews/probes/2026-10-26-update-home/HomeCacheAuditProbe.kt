package com.takahashirinta.ncrust.cache

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Passing assertions characterize defects, not corrected behavior. */
class HomeCacheAuditProbe {
    private class AppliedPrefs(private val memory: SharedPreferences = MemoryPrefs()) : SharedPreferences by memory {
        @Volatile var applied: CountDownLatch? = null
        override fun edit(): SharedPreferences.Editor {
            val editor = memory.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                override fun putLong(key: String, value: Long): SharedPreferences.Editor { editor.putLong(key, value); return this }
                override fun apply() { editor.apply(); applied?.countDown() }
            }
        }
    }
    private class MemoryContext : ContextWrapper(null) {
        val home = AppliedPrefs()
        private val stores = mutableMapOf<String, SharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = synchronized(stores) {
            if (name == "ncrust_home_cache") home else stores.getOrPut(name) { MemoryPrefs() }
        }
    }
    private val daily = """[{"id":101,"name":"fixture-A"}]"""

    @Test fun clearingHomeLeavesFreshMarkerEvenWhenAllSectionsAreMissing() {
        val field = ContentCache.javaClass.getDeclaredField("homeWarmedUpAt").apply { isAccessible = true }
        val before = field.getLong(ContentCache)
        try {
            ContentCache.homeDailySongs = listOf(SongItem(101, "fixture", null, null, 200000))
            ContentCache.markHomeWarmed()
            assertTrue(ContentCache.isHomeFresh())
            ContentCache.clearAll()
            assertNull(ContentCache.homeDailySongs)
            assertNull(ContentCache.homeRecommendPlaylists)
            assertNull(ContentCache.homeNewSongs)
            assertTrue(ContentCache.isHomeFresh())
            println("AUDIT home: clearAll leaves empty cache marked fresh; new HomeScreen initial load returns before fetching or clearing loader")
        } finally { ContentCache.clearAll(); field.setLong(ContentCache, before) }
    }

    @Test fun successfulEmptySnapshotSaveRestoresTheOldNonemptyList() {
        val context = MemoryContext()
        context.home.edit().putString("daily_songs", daily).putLong("saved_at", 1L).apply()
        val applied = CountDownLatch(1)
        context.home.applied = applied
        try {
            HomeSnapshot.save(context, daily = emptyList())
            assertTrue(applied.await(4, TimeUnit.SECONDS))
            assertTrue(HomeSnapshot.savedAt(context) > 1L)
            ContentCache.clearAll()
            assertTrue(HomeSnapshot.restoreIntoCache(context))
            assertEquals(101L, ContentCache.homeDailySongs!!.single().id)
            println("AUDIT home snapshot: confirmed empty daily refresh retains old row 101 while saved_at advances; cold restore reintroduces row")
        } finally { ContentCache.clearAll() }
    }

    @Test fun accountReplacementRetainsPreviousAccountsHomeAndProfileSnapshots() {
        val context = MemoryContext()
        val app = CookieManager.javaClass.getDeclaredField("app").apply { isAccessible = true }
        val previousApp = app.get(CookieManager)
        val previousCookie = CookieManager.snapshot().cookie
        try {
            app.set(CookieManager, null)
            CookieManager.saveCookie(context, "MUSIC_U=fixture-A; __csrf=fixture-csrf-A")
            ContentCache.homeDailySongs = listOf(SongItem(101, "fixture-A", null, null, 200000))
            ContentCache.userProfile = PlaylistApi.UserProfile(10001, "account-A", "")
            context.home.edit().putString("daily_songs", daily).apply()
            CookieManager.clearCookie(context)
            CookieManager.saveCookie(context, "MUSIC_U=fixture-B; __csrf=fixture-csrf-B")
            assertTrue(CookieManager.getCookie(context)!!.contains("fixture-B"))
            assertEquals(10001L, ContentCache.userProfile!!.userId)
            assertEquals(101L, ContentCache.homeDailySongs!!.single().id)
            ContentCache.clearAll()
            assertTrue(HomeSnapshot.restoreIntoCache(context))
            assertEquals(101L, ContentCache.homeDailySongs!!.single().id)
            println("AUDIT home owner: A -> logout -> B retains A profile and daily in memory; disk snapshot restores A daily without owner check")
        } finally {
            CookieManager.saveCookie(context, previousCookie.orEmpty())
            app.set(CookieManager, previousApp)
            ContentCache.clearAll()
        }
    }
}
