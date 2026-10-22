package com.takahashirinta.ncrust.local

import android.content.SharedPreferences
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.PlaylistKey
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class LocalPlaylistTransactionTest {
    private class MemoryPrefs {
        val values = mutableMapOf<String, String>()
        val snapshots = mutableListOf<Map<String, String>>()
        var failNext = false
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preferences method: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, String?>()
            lateinit var result: SharedPreferences.Editor
            result = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putString" -> { changes[args!![0] as String] = args[1] as String?; result }
                    "remove" -> { changes[args!![0] as String] = null; result }
                    "commit" -> {
                        changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        snapshots += values.toMap()
                        val success = !failNext
                        failNext = false
                        success
                    }
                    else -> error("Unexpected editor method: ${method.name}")
                }
            } as SharedPreferences.Editor
            return result
        }
    }

    private fun playlist(id: String, time: Long = 1) = LocalPlaylist(
        PlaylistKey(MusicSource.NETEASE, "owner", id), id, 1, time,
    )

    @Test fun metadataAndTracksBecomeVisibleInTheSameCommit() {
        val memory = MemoryPrefs()
        val item = playlist("1").copy(lastSyncedAt = 100)
        assertNotNull(LocalPlaylistStore.writePlaylistWithTracks(memory.prefs, item, emptyList(), 100))
        assertEquals(1, memory.snapshots.size)
        val committed = memory.snapshots.single()
        assertEquals(100L, LocalPlaylistCodec.decodePlaylists(committed[LocalPlaylistStore.KEY_PLAYLISTS]).single().lastSyncedAt)
        assertTrue(committed.containsKey(LocalPlaylistStore.tracksKey(item.key)))
    }

    @Test fun failedCommitRestoresOldContentsAndSyncTimestampInMemory() {
        val memory = MemoryPrefs()
        val old = playlist("1")
        memory.values[LocalPlaylistStore.KEY_PLAYLISTS] = LocalPlaylistCodec.encodePlaylists(listOf(old))
        memory.values[LocalPlaylistStore.tracksKey(old.key)] = "[]"
        val before = memory.values.toMap()
        memory.failNext = true
        assertNull(LocalPlaylistStore.writePlaylistWithTracks(memory.prefs, old.copy(lastSyncedAt = 100), emptyList(), 100))
        assertEquals(before, memory.values)
        assertEquals(0L, LocalPlaylistCodec.decodePlaylists(memory.values[LocalPlaylistStore.KEY_PLAYLISTS]).single().lastSyncedAt)
    }

    @Test fun capacityPruningRemovesDroppedTracksInTheSameCommit() {
        val memory = MemoryPrefs()
        val old = (1..LocalPlaylistSync.MAX_PLAYLISTS).map { playlist(it.toString(), it.toLong()) }
        memory.values[LocalPlaylistStore.KEY_PLAYLISTS] = LocalPlaylistCodec.encodePlaylists(old)
        memory.values[LocalPlaylistStore.tracksKey(old.first().key)] = "[]"
        val added = playlist("new")
        assertNotNull(LocalPlaylistStore.writePlaylistWithTracks(memory.prefs, added, emptyList(), 1000))
        assertEquals(1, memory.snapshots.size)
        assertFalse(memory.values.containsKey(LocalPlaylistStore.tracksKey(old.first().key)))
        assertTrue(memory.values.containsKey(LocalPlaylistStore.tracksKey(added.key)))
        assertEquals(LocalPlaylistSync.MAX_PLAYLISTS, LocalPlaylistCodec.decodePlaylists(memory.values[LocalPlaylistStore.KEY_PLAYLISTS]).size)
    }
}
