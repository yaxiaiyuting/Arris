package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.source.*
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import com.takahashirinta.ncrust.stats.StatsRecorder
import com.takahashirinta.ncrust.stats.StatsStore
import kotlinx.coroutines.Job

class NoonIdentityAuditProbe {
    private fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply { isAccessible=true }
    private fun vm():PlayerViewModel {
        val type=Class.forName("sun.misc.Unsafe")
        val unsafe=type.getDeclaredField("theUnsafe").apply { isAccessible=true }.get(null)
        return type.getMethod("allocateInstance",Class::class.java).invoke(unsafe,PlayerViewModel::class.java) as PlayerViewModel
    }
    @Test fun queueIdentityBackfillRejectsNewMidBecauseTrackEqualityIgnoresPayload() {
        // Constructorless method fixture: only the fields actually read before the erroneous return.
        // No Android lifecycle, service, playback, coroutine, or HTTP code is started.
        val vm=vm()
        fun set(name:String,value:Any)=PlayerViewModel::class.java.getDeclaredField(name).apply { isAccessible=true }.set(vm,value)
        val old=TrackKey.of(MusicSource.QQMUSIC.key,610000001L)
        set("currentSongId",MutableStateFlow<Long?>(old.id));set("currentTrack",old)
        val expected=old.copy(sourceId="fixture-song-mid",mediaId="fixture-media-mid")
        assertEquals(old,expected)
        assertNotEquals(old.sourceId,expected.sourceId)
        vm.adoptTrackIdentity(expected.source.key,expected.id,expected.sourceId,expected.mediaId)
        assertSame(old,vm.currentTrackKey)
        assertNull(vm.currentTrackKey!!.sourceId)
        assertNull(vm.currentTrackKey!!.mediaId)
        println("AUDIT identity backfill: real adoptTrackIdentity sees the same logical track and returns before copying missing QQ payload from the restored queue; constructorless isolated method fixture, not an Activity restoration test")
    }
    @Test fun staleConfirmedTrackReceivesTheNewManualSongsListeningTime() {
        val ctx=object:ContextWrapper(null) {
            val stores=mutableMapOf<String,SharedPreferences>()
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(n:String,m:Int)=stores.getOrPut(n) { MemoryPrefs() }
        }
        val vm=vm();val a=TrackKey.of("netease",101);val b=TrackKey.of("netease",102)
        fun set(n:String,v:Any)=field(vm,n).set(vm,v)
        set("playingTrack",a);set("currentTrack",b);set("currentSongId",MutableStateFlow<Long?>(102L))
        set("currentSongName",MutableStateFlow<String?>("audible B"));set("currentSongArtist",MutableStateFlow<String?>("artist B"))
        set("duration",MutableStateFlow(200000L));set("isPlaying",MutableStateFlow(true))
        val changes=listOf(StatsRecorder to "model",StatsRecorder to "sessionKey",StatsRecorder to "sessionSourceKey",StatsRecorder to "sessionName",StatsRecorder to "sessionArtist",StatsRecorder to "sessionListenedMs",StatsRecorder to "sessionDurationMs",StatsRecorder to "lastPosMs",StatsRecorder to "lastNowMs",StatsRecorder to "anchorValid",StatsStore to "aggregate",StatsStore to "flushJob")
        val saved=changes.map { (o,n)->Triple(o,field(o,n),field(o,n).get(o)) }
        val sample=StatsRecorder.javaClass.getDeclaredMethod("onSample",Context::class.java,Long::class.javaPrimitiveType).apply { isAccessible=true }
        try {
            field(StatsStore,"aggregate").set(StatsStore,null)
            field(StatsRecorder,"model").set(StatsRecorder,vm);field(StatsRecorder,"sessionKey").set(StatsRecorder,null)
            field(StatsRecorder,"sessionListenedMs").setLong(StatsRecorder,0)
            sample.invoke(StatsRecorder,ctx,1000L)
            field(StatsRecorder,"lastNowMs").setLong(StatsRecorder,System.currentTimeMillis()-1000)
            sample.invoke(StatsRecorder,ctx,1500L)
            val snapshot=StatsStore.snapshot(ctx)
            assertEquals(500L,snapshot.songs.getValue(a.tag).ms)
            assertEquals("audible B",snapshot.songs.getValue(a.tag).name)
            assertFalse(snapshot.songs.containsKey(b.tag))
            println("AUDIT confirmed identity: after an auto transition pins A, a later manual B leaves playingTrack=A because onSongTransitioned updates it only when nextTrack != currentTrack; the real StatsRecorder sampler writes B's 500ms and metadata under A. State fixture plus source lifecycle evidence, no audio transition executed")
        } finally {
            (field(StatsStore,"flushJob").get(StatsStore) as? Job)?.cancel()
            saved.asReversed().forEach { (o,f,v)->f.set(o,v) }
        }
    }
}
