package com.takahashirinta.ncrust.local

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.qq.QqPlaylistRepository
import com.takahashirinta.ncrust.qq.QqPlaylistStore
import com.takahashirinta.ncrust.source.*
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalSyncRevisionAuditProbe {
    private class GatedPrefs(private val memory: SharedPreferences = MemoryPrefs()) : SharedPreferences by memory {
        @Volatile var afterRead: (() -> Unit)? = null
        override fun getString(key: String, fallback: String?): String? {
            val snapshot = memory.getString(key, fallback)
            if (key.startsWith("tracks:")) afterRead?.let { afterRead = null; it() }
            return snapshot
        }
    }
    private class MemoryContext : ContextWrapper(null) {
        val local = GatedPrefs()
        private val stores = mutableMapOf<String, SharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = synchronized(stores) {
            if (name == LocalPlaylistStore.PREFS) local else stores.getOrPut(name) { MemoryPrefs() }
        }
    }
    /**
     * 回归（原 S2 探针）：在途同步**不得**覆盖同步期间用户提交的删除标记。
     *
     * 修复前：sync 返回 Success 并把 tombstone 抹掉（曲目复活）。
     * 修复后：Store 的修订号在 removeTrack 时前进，sync 的提交被拒 ⇒ 返回 Failed，
     * 磁盘上的 tombstone 原样保留，用户删除意图不丢。
     */
    @Test fun inFlightSyncMustNotOverwriteANewUserDeletionTombstone() = runBlocking {
        val context = MemoryContext()
        val key = PlaylistKey(MusicSource.QQMUSIC, "333", "10001")
        // H09 之后 createPlaylist 可空：夹具不注入写盘失败，因此这里必然拿到对象。
        val meta = LocalPlaylistStore.createPlaylist(context, key, "fixture", 1)!!
        fun song(n: Long) = SongItem(SourceIds.qqId(n, "mid-$n"), "fixture-$n", null, null, 200000,
            source = "qqmusic", sourceId = "mid-$n", mediaId = "file-$n")
        val first = song(101)
        LocalPlaylistRepository.addTrack(context, key, first, 2)
        val appField = QqPlaylistRepository.javaClass.getDeclaredField("appContext").apply { isAccessible = true }
        val previousApp = appField.get(QqPlaylistRepository)
        QqPlaylistRepository.init(context)
        QqPlaylistStore.writeDetail(context, key, listOf(first, song(202)), System.currentTimeMillis(), complete = true, total = 2)
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        context.local.afterRead = { captured.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val syncing = async(Dispatchers.Default) { LocalPlaylistRepository.sync(context, meta, false, 2000) }
        try {
            assertTrue(captured.await(4, TimeUnit.SECONDS))
            LocalPlaylistRepository.removeTrack(context, key, TrackKey.ofSong(first), 3000)
            assertTrue(LocalPlaylistStore.readTracks(context, key).single().tombstoned)
            release.countDown()
            val outcome = withTimeout(5000) { syncing.await() }
            assertTrue("late sync must be rejected, got $outcome", outcome is LocalSyncOutcome.Failed)
            val after = LocalPlaylistStore.readTracks(context, key)
            assertTrue("tombstone must survive the late sync", after.single { it.trackKey == TrackKey.ofSong(first) }.tombstoned)
        } finally { release.countDown(); syncing.cancelAndJoin(); appField.set(QqPlaylistRepository, previousApp) }
    }
}
