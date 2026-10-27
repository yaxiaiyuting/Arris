package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.qq.QqSongMapper
import com.takahashirinta.ncrust.library.SavedSongSync
import com.takahashirinta.ncrust.source.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NoonQqMappingAuditProbe {
    @Test fun legacySongidAndSongnameAreIgnoredAndTheSameMidBecomesTwoSavedSongs() {
        val legacy=QqSongMapper.songsFromLegacySearch(JSONObject("""{"data":{"song":{"list":[{"songid":97773,"songmid":"fixture-same-mid","songname":"fixture title","interval":269}]}}}""")).single()
        val modern=QqSongMapper.fromSongObject(JSONObject("""{"id":97773,"mid":"fixture-same-mid","name":"fixture title","interval":269}"""))!!
        assertEquals(legacy.sourceId,modern.sourceId)
        assertTrue(SourceIds.isSynthesizedQqId(legacy.id,legacy.sourceId))
        assertEquals(97773L,SourceIds.qqRawId(modern.id))
        assertNotEquals(TrackKey.ofSong(legacy),TrackKey.ofSong(modern))
        assertEquals("",legacy.name)
        val saved=SavedSongSync.addManualAll(emptyList(),listOf(legacy,modern),1)
        assertEquals(2,saved.size)
        println("AUDIT QQ legacy mapping: the parser accepts songmid but ignores the paired songid/songname fields, hashes identity and loses title; the same modern mid/id is saved as a second song. Parser/sync fixture only; current remote legacy response frequency was not measured")
    }
}
