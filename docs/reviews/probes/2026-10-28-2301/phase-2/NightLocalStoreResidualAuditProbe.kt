package com.takahashirinta.ncrust.audit

import android.content.*
import com.takahashirinta.ncrust.local.*
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.*
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import org.junit.Assert.*
import org.junit.Test

class NightLocalStoreResidualAuditProbe {
    private class FailOncePrefs(private val base: MemoryPrefs = MemoryPrefs()) : SharedPreferences by base {
        var failNext = false
        override fun edit(): SharedPreferences.Editor {
            val editor = base.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean {
                    editor.commit()
                    if (failNext) { failNext = false; return false }
                    return true
                }
            }
        }
    }
    private class LocalContext(val storage: FailOncePrefs) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = storage
    }
    private fun fixture(block: (LocalContext, FailOncePrefs) -> Unit) {
        val rev = LocalPlaylistStore.javaClass.getDeclaredField("revision").apply { isAccessible = true }
        val before = rev.getLong(LocalPlaylistStore)
        try { val prefs=FailOncePrefs();block(LocalContext(prefs),prefs) } finally { rev.setLong(LocalPlaylistStore,before) }
    }
    @Test fun failedMetadataDeletionStillDeletesItsTrackTable() = fixture { ctx,prefs ->
        val key=LocalPlaylistSync.localOnlyKey("local-failure-fixture")
        val meta=LocalPlaylistStore.createPlaylist(ctx,key,"fixture",1L)!!
        val song=SongItem(123L,"fixture",null,null,null)
        val tracks=LocalPlaylistSync.addManual(emptyList(),song,2L)
        LocalPlaylistStore.writeTracks(ctx,key,tracks)
        prefs.failNext=true
        assertFalse(LocalPlaylistStore.deletePlaylist(ctx,key))
        assertEquals(meta.key,LocalPlaylistStore.readPlaylists(ctx).single().key)
        assertTrue(LocalPlaylistStore.readTracks(ctx,key).isEmpty())
        println("AUDIT real deletePlaylist: metadata commit fails and rollback succeeds, but tracks are then removed; operation returns false while retained playlist loses its songs. In-memory prefs only; no disk")
    }
    @Test fun failedAdoptionReturnsAPlaylistThatWasRolledBack() = fixture { ctx,prefs ->
        val key=PlaylistKey(MusicSource.QQMUSIC,"123","LOCAL_FAKE_OWNER")
        prefs.failNext=true
        val result=LocalPlaylistRepository.adoptRemote(ctx,key,"fixture",0L,1L)
        assertEquals(key,result.key)
        assertTrue(LocalPlaylistStore.readPlaylists(ctx).isEmpty())
        println("AUDIT real adoptRemote ignores false from writePlaylists; returns success-shaped playlist absent from memory store. No cloud/UI requests")
    }
}
