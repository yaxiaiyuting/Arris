package com.takahashirinta.ncrust.stats

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.cache.*
import com.takahashirinta.ncrust.source.*
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Characterization of existing defects, not passing regression requirements. */
class PlaybackAccountingProbe {
    private class MemoryContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, SharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            synchronized(stores) { stores.getOrPut(name) { MemoryPrefs() } }
        override fun getFilesDir(): File = throw IllegalStateException("fixture audio cache unavailable")
    }
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Test fun pendingTrackIdentityReceivesOldAudiosListeningTime() {
        val ctx = MemoryContext()
        // Only the state read by StatsRecorder is populated. No Android VM lifecycle or service runs.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null)
        val vm = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, PlayerViewModel::class.java) as PlayerViewModel
        field(vm, "currentTrack").set(vm, TrackKey(MusicSource.NETEASE, 101))
        field(vm, "currentSongId").set(vm, MutableStateFlow<Long?>(101))
        field(vm, "currentSongName").set(vm, MutableStateFlow<String?>("audible A"))
        field(vm, "currentSongArtist").set(vm, MutableStateFlow<String?>("artist A"))
        field(vm, "duration").set(vm, MutableStateFlow(200000L))
        field(vm, "isPlaying").set(vm, MutableStateFlow(true))
        field(StatsStore, "aggregate").set(StatsStore, null)
        field(StatsRecorder, "model").set(StatsRecorder, vm)
        field(StatsRecorder, "sessionKey").set(StatsRecorder, null)
        val sample = StatsRecorder.javaClass.getDeclaredMethod("onSample", Context::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
        try {
            sample.invoke(StatsRecorder, ctx, 1000L)
            val pendingB = TrackKey(MusicSource.QQMUSIC, SourceIds.qqId(202, "fixture-B"), "fixture-B")
            // Matches playSong before its URL resolves: requested track B, actual item/id/title still A.
            field(vm, "currentTrack").set(vm, pendingB)
            sample.invoke(StatsRecorder, ctx, 1500L)
            field(StatsRecorder, "lastNowMs").setLong(StatsRecorder, System.currentTimeMillis() - 1000)
            sample.invoke(StatsRecorder, ctx, 2000L)
            val b = StatsStore.snapshot(ctx).songs.getValue(pendingB.tag)
            assertEquals(500L, b.ms)
            assertEquals("audible A", b.name)
            assertEquals(101L, vm.currentSongId.value)
            println("AUDIT actual A id=101 still playing, pending B receives 500 ms and A metadata")
        } finally {
            (field(StatsStore, "flushJob").get(StatsStore) as? Job)?.cancel()
            field(StatsStore, "aggregate").set(StatsStore, null)
            field(StatsRecorder, "model").set(StatsRecorder, null)
            field(StatsRecorder, "sessionKey").set(StatsRecorder, null)
        }
    }

    @Test fun cacheEnumerationFailurePrunesPersistedOfflineMetadata() {
        val ctx = MemoryContext()
        val cacheField = field(OfflineAudioCache, "cache")
        val previousCache = cacheField.get(OfflineAudioCache)
        cacheField.set(OfflineAudioCache, null)
        field(OfflineLibrary, "index").set(OfflineLibrary, null)
        try {
            assertTrue(OfflineLibrary.record(ctx, 101, "fixture", "artist", null, 200000,
                OfflineKeys.key(101, "standard"), "standard"))
            assertEquals(1, OfflineLibrary.size(ctx))
            assertEquals(1, OfflineAudioCache.reconcileLibrary(ctx))
            assertEquals(0, OfflineLibrary.size(ctx))
            field(OfflineLibrary, "index").set(OfflineLibrary, null)
            assertEquals(0, OfflineLibrary.size(ctx))
            println("AUDIT unavailable audio directory becomes empty cache truth, persisted offline list 1 -> 0")
        } finally {
            cacheField.set(OfflineAudioCache, previousCache)
            field(OfflineLibrary, "index").set(OfflineLibrary, null)
        }
    }
}
