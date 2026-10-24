package com.takahashirinta.ncrust.auth

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NeteaseSessionRenewalTest {
    @Test fun oldSuccessAndOldRejectionCannotCommitAfterAccountSwitch() {
        val g=NeteaseSessionGuard();g.replace("A");val a=g.snapshot();g.replace("B")
        var writes=0
        assertNull(g.ifCurrent(a) {writes++;g.replace(null)})
        assertNull(g.ifCurrent(a) {writes++})
        assertEquals(0,writes);assertEquals("B",g.snapshot().cookie)
    }
    @Test fun identicalCookieReloginStillInvalidatesOldWork() {
        val g=NeteaseSessionGuard();g.replace("A");val a=g.snapshot();g.replace(null);g.replace("A")
        assertFalse(g.isCurrent(a));assertNotEquals(a.loginGeneration,g.snapshot().loginGeneration)
    }
    @Test fun renewalKeepsLoginButChangesCredentialGeneration() {
        val g=NeteaseSessionGuard();g.replace("A");val a=g.snapshot();g.replace("B",renewal=true)
        assertFalse(g.isCurrent(a));assertEquals(a.loginGeneration,g.snapshot().loginGeneration)
    }
    @Test fun credentialNeverAppearsInSnapshotDescription() {
        val g=NeteaseSessionGuard();g.replace("MUSIC_U=SECRET")
        assertFalse(g.snapshot().toString().contains("SECRET"))
    }
    @Test fun failureCooldownIsSessionBoundAndExpiresAtBoundary() {
        val t=NeteaseRefreshThrottle();t.record(1,100,false)
        assertFalse(t.allowed(1,60099));assertTrue(t.allowed(1,60100));assertTrue(t.allowed(2,101))
    }
    @Test fun recentSuccessSuppressesRefreshStorm() {
        val t=NeteaseRefreshThrottle();t.record(1,10,true)
        assertFalse(t.allowed(1,600009));assertTrue(t.allowed(1,600010))
    }
    private data class Reply(val session:NeteaseSessionSnapshot,val rejected:Boolean)
    private suspend fun request(g:NeteaseSessionGuard,once:()->Reply,refresh:suspend(NeteaseSessionSnapshot)->NeteaseTokenRefresher.Result,closed:MutableList<Reply>) = renewRead(
        once,{it.session},g::snapshot,{it.rejected},refresh,{closed.add(it)},
    )
    @Test fun successfulReadDoesNotRefreshOrCloseResponse()=runBlocking {
        val g=NeteaseSessionGuard();g.replace("A");val closed=mutableListOf<Reply>()
        val r=request(g,{Reply(g.snapshot(),false)},{error("unexpected refresh")},closed)
        assertFalse(r.rejected);assertTrue(closed.isEmpty())
    }
    @Test fun renewalRetriesOnceAndClosesOnlyReplacedResponse()=runBlocking {
        val g=NeteaseSessionGuard();g.replace("A");var requests=0;var refreshes=0;val closed=mutableListOf<Reply>()
        val r=request(g,{requests++;Reply(g.snapshot(),true)},{refreshes++;g.replace("A2",true);NeteaseTokenRefresher.Result.REFRESHED},closed)
        assertTrue(r.rejected);assertEquals(2,requests);assertEquals(1,refreshes);assertEquals(1,closed.size)
    }
    @Test fun concurrentRenewalAllowsOneRetryWithAlreadyCommittedTicket()=runBlocking {
        val g=NeteaseSessionGuard();g.replace("A");var requests=0;val closed=mutableListOf<Reply>()
        request(g,{requests++;Reply(g.snapshot(),requests==1)},{g.replace("A2",true);NeteaseTokenRefresher.Result.STALE},closed)
        assertEquals(2,requests);assertEquals(1,closed.size)
    }
    @Test fun networkFailureDuringRefreshKeepsResponseAndTicket()=runBlocking {
        val g=NeteaseSessionGuard();g.replace("A");var requests=0;val closed=mutableListOf<Reply>()
        request(g,{requests++;Reply(g.snapshot(),true)},{NeteaseTokenRefresher.Result.RETRYABLE},closed)
        assertEquals(1,requests);assertTrue(closed.isEmpty());assertEquals("A",g.snapshot().cookie)
    }
    @Test fun accountSwitchDuringRefreshCannotReplayOldAccountRead()=runBlocking {
        val g=NeteaseSessionGuard();g.replace("A");var requests=0;val closed=mutableListOf<Reply>()
        try { request(g,{requests++;Reply(g.snapshot(),true)},{g.replace("B");NeteaseTokenRefresher.Result.STALE},closed);fail() }
        catch (_:NeteaseSessionChangedException) {}
        assertEquals(1,requests);assertEquals(1,closed.size);assertEquals("B",g.snapshot().cookie)
    }
    @Test fun cancellationClosesOriginalResponseAndNeverRetries()=runBlocking {
        val g=NeteaseSessionGuard();g.replace("A");var requests=0;val closed=mutableListOf<Reply>()
        try {request(g,{requests++;Reply(g.snapshot(),true)},{throw CancellationException()},closed);fail()}
        catch (_:CancellationException) {}
        assertEquals(1,requests);assertEquals(1,closed.size)
    }
}
