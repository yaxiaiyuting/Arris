package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.reorderByIdentity
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.*
import org.junit.Assert.*
import org.junit.Test

class NightQueueIdentityAuditProbe {
    @Test fun draggingAnUpcomingDuplicateMovesCurrentOccurrenceInstead() {
        val a=SongItem(SourceIds.biliId(12345L) or SourceIds.BILI_AV_PARTITION_FLAG,"A",null,null,null,source="bilibili",sourceId="bv:BV1xx411c7mD:123:1")
        val b=SongItem(222L,"B",null,null,null)
        val queue=listOf(a,a.copy(name="upcoming A occurrence"),b)
        val result=reorderByIdentity(queue,0,TrackKey.ofSong(queue[1]),TrackKey.ofSong(b))!!
        assertEquals(2,result.currentIndex)
        assertEquals("upcoming A occurrence",result.queue.first().name)
        assertEquals("A",result.queue.last().name)
        println("AUDIT real reorderByIdentity with current A and upcoming same-key A: dragging upcoming A past B moves CURRENT A to index 2. Duplicate queue rows are explicitly allowed by the new occurrence-suffixed keys; no Compose UI executed")
    }
}
