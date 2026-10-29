package com.takahashirinta.ncrust.local

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.*
import org.junit.Assert.*
import org.junit.Test

class LocalBiliIdentityMigrationTest {
    @Test fun videoAliasesMergeWithTheirPayloadAndDeleteIntentIntact() {
        val old=SourceIds.biliId(777);val canonical=old or SourceIds.BILI_AV_PARTITION_FLAG
        fun row(id:Long,deleted:Boolean,at:Long):LocalPlaylistTrack {
            val song=SongItem(id,"video",null,null,null,source="bilibili",sourceId="bv:BV1xx411c7mD:123:1")
            return LocalPlaylistTrack(TrackKey.ofSong(song),LocalTrackOrigin.LOCAL,at,deleted,song)
        }
        val result=LocalPlaylistCodec.decodeTracks(LocalPlaylistCodec.encodeTracks(listOf(row(old,false,1),row(canonical,true,2))))
        assertEquals(1,result.size);assertEquals(canonical,result.single().trackKey.id)
        assertEquals(canonical,result.single().song!!.id);assertTrue(result.single().tombstoned)
    }
    @Test fun audioIdentityIsNeverGuessedToBeAVideo() {
        val id=SourceIds.biliId(777)
        val track=LocalPlaylistTrack(TrackKey(MusicSource.BILIBILI,id,"au:777"),LocalTrackOrigin.LOCAL,1)
        assertEquals(id,LocalPlaylistCodec.decodeTracks(LocalPlaylistCodec.encodeTracks(listOf(track))).single().trackKey.id)
    }
}
