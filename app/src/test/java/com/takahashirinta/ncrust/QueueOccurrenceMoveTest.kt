package com.takahashirinta.ncrust

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.trackKeyOf
import org.junit.Assert.*
import org.junit.Test

class QueueOccurrenceMoveTest {
    @Test fun movingTheUpcomingDuplicateLeavesThePlayingOccurrenceInPlace() {
        val playing=SongItem(1,"playing",null,null,null)
        val upcoming=playing.copy(name="upcoming")
        val b=SongItem(2,"b",null,null,null)
        val queue=listOf(playing,upcoming,b)
        val result=reorderBySnapshot(queue,0,queue,1,2)!!
        assertEquals(listOf("playing","b","upcoming"),result.queue.map { it.name })
        assertEquals(0,result.currentIndex)
        assertNull(reorderByIdentity(queue,0,upcoming.trackKeyOf(),b.trackKeyOf()))
    }
    @Test fun aChangedQueueAbandonsTheOldDragInsteadOfMovingANewIndex() {
        val queue=(1L..4L).map {SongItem(it,"$it",null,null,null)}
        val now=queue.filter {it.id!=2L}
        assertNull(reorderBySnapshot(now,0,queue,2,3))
    }
}
