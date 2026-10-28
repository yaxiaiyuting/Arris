package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.library.*
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.*
import org.junit.Assert.*
import org.junit.Test

class NightIdentityAuditProbe {
    private val mid = "LOCAL_FAKE_MID"
    private val raw = 777L
    private val real = SourceIds.qqId(raw, mid)
    private fun entry(id: Long, tombstoned: Boolean, added: Long): SavedSongEntry {
        val song = SongItem(id, "local fixture", null, null, null, source = "qqmusic", sourceId = mid, qqSongId = raw)
        return SavedSongEntry(TrackKey.ofSong(song), SavedSongOrigin.LOCAL, added, tombstoned, song)
    }
    @Test fun migratedAliasDuplicatesCannotBothBeRemoved() {
        val json = SavedSongCodec.encode(listOf(entry(SourceIds.qqId(0L, mid), false, 1L), entry(real, false, 2L)))
        val decoded = SavedSongCodec.decode(json)
        assertEquals(2, decoded.size)
        assertEquals(listOf(real, real), decoded.map { it.trackKey.id })
        val removed = SavedSongSync.remove(decoded, TrackKey(MusicSource.QQMUSIC, real))
        assertEquals(1, removed.count { it.isVisible })
        assertSame(removed, SavedSongSync.remove(removed, TrackKey(MusicSource.QQMUSIC, real)))
        println("AUDIT production decode migrates two historical IDs to the same key without consolidation; remove tombstones first only and every later removal is a no-op. No Android/UI/network")
    }
    @Test fun earlierAliasTombstoneMasksRemovalOfVisibleCanonicalEntry() {
        val json = SavedSongCodec.encode(listOf(entry(SourceIds.qqId(0L, mid), true, 1L), entry(real, false, 2L)))
        val decoded = SavedSongCodec.decode(json)
        val removed = SavedSongSync.remove(decoded, TrackKey(MusicSource.QQMUSIC, real))
        assertSame(decoded, removed)
        assertEquals(1, removed.count { it.isVisible })
        println("AUDIT a migrated tombstone first in persisted order makes a visible canonical duplicate undeletable through production remove; payload contains real qq_songid required by the new migration")
    }
}
