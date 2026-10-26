package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.bili.BiliTrack
import com.takahashirinta.ncrust.cache.OfflineKeys
import com.takahashirinta.ncrust.library.SavedSongSync
import com.takahashirinta.ncrust.player.QueueKeys
import com.takahashirinta.ncrust.source.TrackKey
import org.junit.Assert.*
import org.junit.Test

class ScheduledBiliIdentityAuditProbe {
    @Test fun audioZoneAndVideoWithTheSameNumericIdCollapseInQueueAndCollection() {
        val audio=BiliTrack(auid=101,title="audio fixture",author="A",coverUrl="",durationMs=200000).toSongItem()!!
        val video=BiliTrack(aid=101,bvid="BV-fixture",cid=202,title="different video",author="B",coverUrl="",durationMs=300000).toSongItem()!!
        assertNotEquals(audio.sourceId,video.sourceId)
        assertEquals(audio.id,video.id)
        assertEquals(TrackKey.ofSong(audio),TrackKey.ofSong(video))
        val plan=QueueKeys.planPlayItem(listOf(audio),0,video)
        assertFalse(plan.queueChanged)
        assertEquals("au:101",plan.queue[plan.playIndex].sourceId)
        val saved=SavedSongSync.addManual(emptyList(),audio,1)
        val after=SavedSongSync.addManual(saved,video,2)
        assertEquals(1,after.size)
        assertEquals(audio.sourceId,after.single().song.sourceId)
        assertEquals(OfflineKeys.key(audio.id,"standard"),OfflineKeys.key(video.id,"standard"))
        println("AUDIT Bili identity: AU and AV share an id; selecting the video preserves the queued audio, collection drops the second item, offline keys collide")
    }
}
