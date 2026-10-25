package com.takahashirinta.ncrust.cache

import com.takahashirinta.ncrust.account.*
import org.junit.Assert.*
import org.junit.Test

class OfflineContentRecoveryTest {
    private fun key(level:String)=OfflineKeys.key(42,level)
    @Test fun firstMissingQualityDoesNotHideAnotherPlayableQuality() {
        val i=OfflineUrlIndex();i.put(key("lossless"),"lost");i.put(key("standard"),"cached")
        val hit=i.recallRecord(listOf(key("lossless")),42) {it==key("standard")}
        assertEquals("cached",hit?.second?.url)
    }
    @Test fun rejectedQualityDoesNotPreventFallback() {
        val i=OfflineUrlIndex();i.put(key("lossless"),"bad");i.put(key("standard"),"good")
        val failed=setOf(key("lossless"))
        assertEquals(key("standard"),i.recallRecord(listOf(key("lossless")),42){it !in failed}?.first)
    }
    @Test fun noUsableCandidateReturnsNullWithoutOtherSongLeak() {
        val i=OfflineUrlIndex();i.put(OfflineKeys.key(99,"standard"),"other");i.put(key("lossless"),"bad")
        assertNull(i.recallRecord(listOf(key("lossless")),42){false})
    }
    @Test fun legacyStringMapMigratesToUnknownRatherThanFull() {
        val i=OfflineUrlIndex.fromJson("""{"song:42:standard":"old"}""")
        assertFalse(i.record(key("standard"))!!.content.known)
        assertNull(i.record(key("standard"))!!.content.playbackItem("old","standard"))
        assertFalse(OfflineUrlIndex.fromJson(i.toJson()).record(key("standard"))!!.content.known)
    }
    @Test fun trialSurvivesColdRoundTripAndKeepsDurationAndClassification() {
        val content=OfflineContent.from(PlaybackItem(url="trial",code=200,fee=1,freeTrialInfo=FreeTrialInfo(0,45)))
        val i=OfflineUrlIndex();i.put(key("standard"),"trial",content)
        val restored=OfflineUrlIndex.fromJson(i.toJson()).record(key("standard"))!!.content
        val e=classifyPlayback("standard",true,restored.playbackItem("trial","standard"),null)
        assertEquals(PlaybackEntitlement.Trial(45,EntitlementReason.VIP),e)
    }
    @Test fun fullAndTrialUseDifferentFileKeysAndRetainQuality() {
        val full=OfflineKeys.withContentKey("https://x/a",42,"lossless",OfflineContent(OfflineContent.FULL))
        val trial=OfflineKeys.withContentKey("https://x/a",42,"lossless",OfflineContent(OfflineContent.TRIAL,0,45))
        assertNotEquals(OfflineKeys.keyOf(full),OfflineKeys.keyOf(trial))
        assertEquals("lossless",OfflineKeys.levelOf(OfflineKeys.keyOf(trial)!!));assertEquals(42L,OfflineKeys.songIdOf(OfflineKeys.keyOf(full)!!))
    }
    @Test fun serviceRememberDoesNotEraseMetadataForSameFile() {
        val i=OfflineUrlIndex();i.put(key("standard"),"trial",OfflineContent(OfflineContent.TRIAL,0,45));i.put(key("standard"),"trial")
        assertEquals(OfflineContent.TRIAL,i.record(key("standard"))!!.content.kind)
        i.put(key("standard"),"different")
        assertFalse(i.record(key("standard"))!!.content.known)
    }
    @Test fun verifiedFullPreferredOverLegacyAndTrialAtSameQuality() {
        val i=OfflineUrlIndex();i.put(key("standard"),"legacy")
        i.put(key("standard"),"full",OfflineContent(OfflineContent.FULL))
        i.put(key("standard~trial_0_45"),"trial",OfflineContent(OfflineContent.TRIAL,0,45))
        assertEquals("full",i.recallRecord(listOf(key("standard")),42){true}!!.second.url)
        assertEquals(OfflineContent.FULL,i.recallRecord(listOf(key("standard")),42){true}!!.second.content.kind)
    }
    @Test fun fullDownloadKeepsHistoricalKeySoExistingFilesStillHit() {
        val url=OfflineKeys.withContentKey("https://x/a",42,"lossless",OfflineContent(OfflineContent.FULL))
        assertEquals(OfflineKeys.key(42,"lossless"),OfflineKeys.keyOf(url))
    }
    @Test fun malformedEntryDoesNotDiscardOtherValidRecords() {
        val i=OfflineUrlIndex.fromJson("""{"song:42:lossless":[],"song:42:standard":"good"}""")
        assertEquals("good",i.get(key("standard")))
    }
    @Test fun preV374FullRecordRemainsUnverifiedUntilNewBytesAreBound() {
        val i=OfflineUrlIndex.fromJson("""{"song:42:standard":{"url":"old","content":{"kind":"full"}}}""")
        val c=i.record(key("standard"))!!.content
        assertFalse(c.known);assertNull(c.playbackItem("old","standard"))
    }
    @Test fun freshFullCannotReadLegacyUnverifiedTrialBytes() {
        val full=OfflineContent(OfflineContent.FULL)
        val isolated=verifiedCacheKey(key("standard"),full,OfflineContent(),true)
        assertNotEquals(key("standard"),isolated)
        assertEquals("standard",OfflineKeys.levelOf(isolated));assertEquals(42L,OfflineKeys.songIdOf(isolated))
        assertEquals(key("standard"),verifiedCacheKey(key("standard"),full,full,true))
        assertEquals(key("standard"),verifiedCacheKey(key("standard"),full,null,false))
    }
}
