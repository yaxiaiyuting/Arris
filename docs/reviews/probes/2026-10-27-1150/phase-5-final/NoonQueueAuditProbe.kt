package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.PlaybackQueueLogic
import com.takahashirinta.ncrust.player.QueueKeys
import com.takahashirinta.ncrust.QueueModes
import org.junit.Assert.*
import org.junit.Test

class NoonQueueAuditProbe {
    @Test fun deletingThePlayingSongPointsAtItsSuccessorButNextSelectionSkipsThatSuccessor() {
        val a=SongItem(id=101,name="A",artists=null,album=null,duration=null)
        val b=SongItem(id=102,name="B",artists=null,album=null,duration=null)
        val c=SongItem(id=103,name="C",artists=null,album=null,duration=null)
        val result=PlaybackQueueLogic.removeAt(listOf(a,b,c),0,0)
        assertEquals(listOf(b,c),result.queue)
        assertEquals(0,result.currentIndex)
        val cursor=PlaybackQueueLogic.resolveCurrentIndex(result.queue,QueueKeys.keyOf(a),result.currentIndex)
        val next=PlaybackQueueLogic.nextIndex(result.queue,cursor,QueueModes.CYCLE)
        assertEquals(c,result.queue[next])
        println("AUDIT queue composition: removeAt([A playing,B,C],0,0) yields [B,C] at B; nextIndex then selects C. MainScreen.removeFromQueue leaves audio A playing and playNext uses this cursor. The helper composition is executed; Compose/audio behavior is established by source call chain only")
    }
}
