package com.takahashirinta.ncrust.qq

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class QqRefreshFlightTest {
    @Test fun concurrentCallerWaitsForCommitAndRechecksWithoutAnotherRefresh() = runBlocking {
        val flight=QqRefreshFlight()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var refreshed=false;var calls=0
        val first=async(start=CoroutineStart.UNDISPATCHED) {flight.run {calls++;entered.complete(Unit);release.await();refreshed=true}}
        entered.await()
        val second=async(start=CoroutineStart.UNDISPATCHED) {flight.run {if(!refreshed)calls++;refreshed}}
        assertFalse(second.isCompleted)
        release.complete(Unit);first.await()
        assertTrue(second.await());assertEquals(1,calls)
    }
    @Test fun cancellingAWaiterDoesNotCancelTheActiveRefresh() = runBlocking {
        val flight=QqRefreshFlight();val release=CompletableDeferred<Unit>()
        val first=async(start=CoroutineStart.UNDISPATCHED) {flight.run {release.await();"committed"}}
        val waiting=async(start=CoroutineStart.UNDISPATCHED) {flight.run {fail("cancelled waiter must not run")}}
        waiting.cancelAndJoin();assertTrue(first.isActive)
        release.complete(Unit);assertEquals("committed",first.await())
        assertEquals("next",flight.run {"next"})
    }
    @Test fun completedBackgroundRefreshRetriesWithNewTicketWithoutRefreshingAgain() = runBlocking {
        var revision=7L;var attempts=0;var refreshes=0
        val result=QqRefreshRetry.request(
            once={attempts++;if(attempts==1) {revision=9;"rejected"} else "new ticket"},
            rejected={it=="rejected"},refresh={refreshes++;true},revision={revision},loggedIn={true},
        )
        assertEquals("new ticket",result);assertEquals(2,attempts);assertEquals(0,refreshes)
    }
    @Test fun recentSuccessDuringPassiveRefreshAlsoRearmsOneBoundedRetry() = runBlocking {
        var revision=7L;var attempts=0
        val result=QqRefreshRetry.request(
            once={attempts++;"rejected"},rejected={true},
            refresh={revision=9;false},revision={revision},loggedIn={true},
        )
        assertEquals("rejected",result);assertEquals(2,attempts)
    }
    @Test fun failureWithoutTicketChangeDoesNotRepeatTheRejectedRequest() = runBlocking {
        var attempts=0
        QqRefreshRetry.request(once={attempts++;false},rejected={!it},refresh={false},revision={7},loggedIn={true})
        assertEquals(1,attempts)
    }
    @Test fun logoutDuringRequestDoesNotTriggerTicketChangeRetry() = runBlocking {
        var revision=7L;var loggedIn=true;var attempts=0
        QqRefreshRetry.request(once={attempts++;revision=8;loggedIn=false;false},rejected={!it},refresh={false},revision={revision},loggedIn={loggedIn})
        assertEquals(1,attempts)
    }
    @Test fun successfulFirstRequestDoesNotInvokeRefresh() = runBlocking {
        assertEquals("ok",QqRefreshRetry.request(once={"ok"},rejected={false},refresh={fail("must not refresh");false},revision={7},loggedIn={true}))
    }
    @Test fun logoutImmediatelyAfterRefreshDoesNotRetryAnonymously() = runBlocking {
        var loggedIn=true;var attempts=0
        QqRefreshRetry.request(once={attempts++;false},rejected={!it},refresh={loggedIn=false;true},revision={7},loggedIn={loggedIn})
        assertEquals(1,attempts)
    }
}
