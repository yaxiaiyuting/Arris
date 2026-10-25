/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.source.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackRequestPolicyTest {
    private val qq = TrackKey(MusicSource.QQMUSIC, 1L.shl(62) + 123, "qq-song-mid", "qq-file-mid")
    private val ncm = TrackKey(MusicSource.NETEASE, 456)

    @Test fun qqToNeteaseReplacesSourceAndClearsBothQqIdentifiers() {
        val next = playbackRequestIdentity(qq, true, ncm.id, null, null, null)!!
        assertEquals(MusicSource.NETEASE, next.source)
        assertEquals(ncm.id, next.id)
        assertNull(next.sourceId);assertNull(next.mediaId)
        assertEquals("song:456",SourceIds.mediaId(next.source,next.id))
    }
    @Test fun explicitNeteaseAlsoRejectsStaleQqPayload() {
        val next = playbackRequestIdentity(qq,true,ncm.id,"netease","old-mid","old-file")!!
        assertNull(next.sourceId);assertNull(next.mediaId)
    }
    @Test fun nextQqSongNeverInheritsPreviousSongsFileId() {
        val next=playbackRequestIdentity(qq,true,qq.id+1,"qqmusic","new-mid",null)!!
        assertEquals("new-mid",next.sourceId);assertNull(next.mediaId)
    }
    @Test fun controlsWithoutNewUrlPreserveCurrentIdentity() {
        assertSame(qq,playbackRequestIdentity(qq,false,-1,null,null,null))
    }
    @Test fun malformedPlaybackRequestCannotReusePreviousTrackIdentity() {
        assertNull(playbackRequestIdentity(qq,true,-1,null,null,null))
    }
    @Test fun legacyQqIdStillInfersCorrectPlatformWithoutExplicitSource() {
        assertEquals(MusicSource.QQMUSIC,playbackRequestIdentity(null,true,qq.id,null,"mid",null)!!.source)
    }
    @Test fun manualNextAndPreviousAdvanceAndWrap() {
        assertEquals(2,manualQueueIndex(3,1,1));assertEquals(0,manualQueueIndex(3,2,1))
        assertEquals(0,manualQueueIndex(3,1,-1));assertEquals(2,manualQueueIndex(3,0,-1))
        assertNull(manualQueueIndex(0,0,1))
    }
    @Test fun manualNextForSingleTrackIsStillWellDefined() {
        assertEquals(0,manualQueueIndex(1,0,1));assertEquals(0,manualQueueIndex(1,0,-1))
    }
    @Test fun preloadCannotBeAttachedWhileRequestedCurrentSongIsStillResolving() {
        assertFalse(admitPreload(true,ncm,qq))
        assertTrue(admitPreload(true,ncm,ncm))
    }
    @Test fun queuedOldPreloadIntentIsRejectedAfterCurrentTrackChanges() {
        assertFalse(admitPreload(true,qq,ncm));assertFalse(admitPreload(false,ncm,ncm))
    }
    @Test fun sameNumericIdOnDifferentSourcesDoesNotAuthorizePreload() {
        assertFalse(admitPreload(true,TrackKey(MusicSource.NETEASE,123),TrackKey(MusicSource.QQMUSIC,123)))
    }
    @Test fun missingOwnerOrPlayerCannotAuthorizePreload() {
        assertFalse(admitPreload(true,null,ncm));assertFalse(admitPreload(true,ncm,null))
    }
}
