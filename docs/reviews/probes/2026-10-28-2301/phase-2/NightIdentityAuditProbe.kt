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

    private fun biliEntry(id: Long, tombstoned: Boolean, added: Long): SavedSongEntry {
        val song = SongItem(id, "local video fixture", null, null, null, source = "bilibili", sourceId = "bv:BV1xx411c7mD:123:1")
        return SavedSongEntry(TrackKey.ofSong(song), SavedSongOrigin.LOCAL, added, tombstoned, song)
    }
    @Test fun realHistoricalBiliAliasesBecomeUndeletableDuplicates() {
        val old = SourceIds.biliId(12345L)
        val fresh = old or SourceIds.BILI_AV_PARTITION_FLAG
        val decoded = SavedSongCodec.decode(SavedSongCodec.encode(listOf(biliEntry(old,false,1),biliEntry(fresh,false,2))))
        assertEquals(listOf(fresh,fresh),decoded.map { it.trackKey.id })
        val removed = SavedSongSync.remove(decoded,TrackKey(MusicSource.BILIBILI,fresh))
        assertEquals(1,removed.count { it.isVisible })
        assertSame(removed,SavedSongSync.remove(removed,TrackKey(MusicSource.BILIBILI,fresh)))
        println("AUDIT Bili AV records need no new field: pre-3.7.9 ID plus 3.7.9 partitioned ID normalize to same key at 3.7.10; removal becomes permanently no-op for the visible duplicate")
    }
    @Test fun historicalBiliTombstoneStopsRemovalOfVisibleMigratedVideo() {
        val old = SourceIds.biliId(12345L)
        val fresh = old or SourceIds.BILI_AV_PARTITION_FLAG
        val decoded = SavedSongCodec.decode(SavedSongCodec.encode(listOf(biliEntry(old,true,1),biliEntry(fresh,false,2))))
        assertSame(decoded,SavedSongSync.remove(decoded,TrackKey(MusicSource.BILIBILI,fresh)))
        assertEquals(1,decoded.count { it.isVisible })
        println("AUDIT Bili historical delete marker collides with visible new partitioned entry. Existing sourceId is sufficient for migration; unlike the conditional QQ fixtures, no newly added metadata is assumed")
    }
}
