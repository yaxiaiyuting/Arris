package com.takahashirinta.ncrust.cache

import com.takahashirinta.ncrust.account.*
import org.junit.Assert.*
import org.junit.Test

class OfflineContentRecoveryTest {
    private fun key(level:String)=OfflineKeys.key(42,level)
    @Test fun firstMissingQualityDoesNotHideAnotherPlayableQuality() {
        val i=OfflineUrlIndex();i.put(key("lossless"),"lost");i.put(key("standard"),"cached")
        val hit=i.recallRecord(42, "lossless") {it==key("standard")}
        assertEquals("cached",hit?.second?.url)
    }
    @Test fun rejectedQualityDoesNotPreventFallback() {
        val i=OfflineUrlIndex();i.put(key("lossless"),"bad");i.put(key("standard"),"good")
        val failed=setOf(key("lossless"))
        assertEquals(key("standard"),i.recallRecord(42, "lossless"){it !in failed}?.first)
    }
    @Test fun noUsableCandidateReturnsNullWithoutOtherSongLeak() {
        val i=OfflineUrlIndex();i.put(OfflineKeys.key(99,"standard"),"other");i.put(key("lossless"),"bad")
        assertNull(i.recallRecord(42, "lossless"){false})
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
    @Test fun identicalLastUrlReusesEncodingAndOtherChangesInvalidateIt() {
        val index = OfflineUrlIndex()
        val standard = key("standard")
        val lossless = key("lossless")
        index.put(standard, "fixture")
        index.put(lossless, "fixture", OfflineContent(OfflineContent.FULL))
        val original = index.toJson()

        index.put(lossless, "fixture")
        assertSame(original, index.toJson())
        index.put(standard, "fixture")
        assertEquals(listOf(lossless, standard), index.keys())
        assertNotEquals(original, index.toJson())
        assertEquals(2, index.removeSong(42))
        assertEquals("{}", index.toJson())
    }
    @Test fun verifiedFullPreferredOverLegacyAndTrialAtSameQuality() {
        val i=OfflineUrlIndex();i.put(key("standard"),"legacy")
        i.put(key("standard"),"full",OfflineContent(OfflineContent.FULL))
        i.put(key("standard~trial_0_45"),"trial",OfflineContent(OfflineContent.TRIAL,0,45))
        assertEquals("full",i.recallRecord(42, "standard"){true}!!.second.url)
        assertEquals(OfflineContent.FULL,i.recallRecord(42, "standard"){true}!!.second.content.kind)
    }

    @Test fun probesRequestedQualityFirstAndPreservesOrderWithinEachContentRank() {
        val index = OfflineUrlIndex()
        val full = OfflineContent(OfflineContent.FULL)
        val trial = OfflineContent(OfflineContent.TRIAL, 0, 45)
        val requestedFull = key("lossless~full-v1")
        val requestedTrial = key("lossless~trial_0_45")
        val requestedLegacy = key("lossless")
        val otherFull = key("hires")
        val otherTrial = key("standard~trial_0_45")
        val secondFull = key("standard")
        index.put(otherFull, "higher", full)
        index.put(requestedLegacy, "legacy")
        index.put(otherTrial, "preview", trial)
        index.put(requestedTrial, "requested-preview", trial)
        index.put(secondFull, "lower", full)
        index.put(requestedFull, "requested-full", full)

        val visited = mutableListOf<String>()
        val hit = index.recallRecord(42, "lossless") {
            visited.add(it)
            it == secondFull
        }

        assertEquals(
            listOf(requestedFull, requestedTrial, requestedLegacy, otherFull, secondFull),
            visited,
        )
        assertEquals("lower", hit?.second?.url)
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
        val isolated=verifiedCacheKey(key("standard"),full,OfflineContent()) { true }
        assertNotEquals(key("standard"),isolated)
        assertEquals("standard",OfflineKeys.levelOf(isolated));assertEquals(42L,OfflineKeys.songIdOf(isolated))
        assertEquals(key("standard"),verifiedCacheKey(key("standard"),full,full) { true })
        assertEquals(key("standard"),verifiedCacheKey(key("standard"),full,null) { false })
    }

    @Test fun cacheKeysAreEnumeratedOnlyForAPossibleFullFileCollision() {
        val base = key("standard")
        val full = OfflineContent(OfflineContent.FULL)
        assertEquals(base, verifiedCacheKey(base, full, full) { error("已确认完整文件") })
        assertEquals(base, verifiedCacheKey(base, OfflineContent(), null) { error("内容未知") })

        var probes = 0
        val isolated = verifiedCacheKey(base, full, null) {
            probes++
            true
        }
        assertEquals("$base~full-v1", isolated)
        assertEquals(1, probes)
    }
}
