package com.takahashirinta.ncrust.audit

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.library.*
import com.takahashirinta.ncrust.local.*
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.*
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScheduledPayloadAuditProbe {
    private class MemoryContext:ContextWrapper(null) {
        private val stores=mutableMapOf<String,SharedPreferences>()
        override fun getApplicationContext():Context=this
        override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=stores.getOrPut(name) { MemoryPrefs() }
    }
    @Test fun incompleteLocalPayloadShadowsAResolvableSavedSongDuringCarRecovery() = runBlocking {
        val context=MemoryContext();val id=SourceIds.qqId(101,"complete-mid")
        val localKey=LocalPlaylistSync.localOnlyKey("local:fixture")
        val local=LocalPlaylistStore.createPlaylist(context,localKey,"fixture",1)
        val incomplete=SongItem(id,"fixture",null,null,200000,source="qqmusic")
        LocalPlaylistStore.writePlaylistWithTracks(context,local,listOf(
            LocalPlaylistTrack(TrackKey(MusicSource.QQMUSIC,id),LocalTrackOrigin.LOCAL,2,false,incomplete)),2)
        val complete=incomplete.copy(sourceId="complete-mid",mediaId="complete-file")
        context.getSharedPreferences("ncrust_library",0).edit().putString("saved_songs",SavedSongCodec.encode(listOf(
            SavedSongEntry(TrackKey.ofSong(complete),SavedSongOrigin.LOCAL,3,false,complete)))).commit()
        val f=LibraryManager.javaClass.getDeclaredField("cachedEntries").apply { isAccessible=true }
        val old=f.get(LibraryManager);f.set(LibraryManager,null)
        try {
            assertTrue(LibraryManager.getSavedSongs(context).single().isResolvable)
            val recovered=recoverPayload(context,MusicSource.QQMUSIC,id)!!
            assertFalse("first local match masks a later usable collection entry",recovered.isResolvable)
            assertNull(recovered.sourceId)
            val outcome=SourceRouter.resolveUrlOutcome(recovered,"standard")
            assertNull(outcome.result)
            println("AUDIT payload recovery: incomplete local QQ record shadows the complete saved-song mid, so car routing still refuses the song")
        } finally { f.set(LibraryManager,old) }
    }
    @Test fun qqSongDetailRouteBuildsAnUnplayableCurrentVersionFromAPlayableSong() = runBlocking {
        val original=SongItem(SourceIds.qqId(101,"fixture-mid"),"fixture",null,null,200000,
            source="qqmusic",sourceId="fixture-mid",mediaId="fixture-file")
        assertTrue(original.isResolvable)
        val method=Class.forName("com.takahashirinta.ncrust.ui.screen.SongDetailScreenKt")
            .getDeclaredMethod("routeAnchorSong",MusicSource::class.java,java.lang.Long.TYPE).apply { isAccessible=true }
        val anchor=method.invoke(null,MusicSource.QQMUSIC,SourceIds.qqRawId(original.id)!!) as SongItem
        assertEquals(TrackKey.ofSong(original),TrackKey.ofSong(anchor))
        assertFalse(anchor.isResolvable);assertTrue(anchor.name.isBlank())
        val page=com.takahashirinta.ncrust.crosssource.CatalogAggregator.loadTrack(anchor)
        assertEquals(1,page.versions.size)
        assertFalse(page.versions.single().song.isResolvable)
        assertNull(SourceRouter.resolveUrlOutcome(page.versions.single().song,"standard").result)
        println("AUDIT QQ detail route: a playable song becomes a blank unresolvable current-version row because route construction discards its mid and metadata")
    }
}
