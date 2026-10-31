package com.takahashirinta.ncrust.library

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.*
import org.junit.Assert.*
import org.junit.Test

class SavedIdentityConsolidationTest {
    private val old = SourceIds.biliId(12345L)
    private val fresh = old or SourceIds.BILI_AV_PARTITION_FLAG
    private fun entry(id: Long, deleted: Boolean, at: Long, origin: SavedSongOrigin = SavedSongOrigin.LOCAL): SavedSongEntry {
        val song = SongItem(id, "video", null, null, null, source = "bilibili", sourceId = "bv:BV1xx411c7mD:123:1")
        return SavedSongEntry(TrackKey.ofSong(song), origin, at, deleted, song)
    }
    @Test fun migratedVideoAliasesBecomeOneRemovableEntry() {
        val items = SavedSongCodec.decode(SavedSongCodec.encode(listOf(entry(old,false,1),entry(fresh,false,2))))
        assertEquals(1, items.size)
        assertEquals(fresh, items.single().trackKey.id)
        val removed = SavedSongSync.remove(items, TrackKey(MusicSource.BILIBILI,fresh))
        assertFalse(removed.any { it.isVisible })
        assertSame(removed, SavedSongSync.remove(removed,TrackKey(MusicSource.BILIBILI,fresh)))
    }
    @Test fun removeAlsoHandlesAlreadyDuplicatedInMemoryEntries() {
        val items = listOf(entry(fresh,true,1),entry(fresh,false,2))
        assertFalse(SavedSongSync.remove(items,TrackKey(MusicSource.BILIBILI,fresh)).any { it.isVisible })
    }
    @Test fun remoteDataCannotUndoAManualDeleteDuringConsolidation() {
        val items = SavedSongCodec.decode(SavedSongCodec.encode(listOf(entry(old,true,1),entry(fresh,false,3,SavedSongOrigin.REMOTE))))
        assertTrue(items.single().tombstoned)
        assertEquals(SavedSongOrigin.LOCAL,items.single().origin)
    }
    @Test fun laterManualSaveCanUndoAnOlderDeleteAndStaysRemovable() {
        val items = SavedSongCodec.decode(SavedSongCodec.encode(listOf(entry(old,true,1),entry(fresh,false,2))))
        assertTrue(items.single().isVisible)
        assertFalse(SavedSongSync.remove(items,items.single().trackKey).any { it.isVisible })
    }
}
