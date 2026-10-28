package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.ui.viewmodel.PlayRequestGate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PendingPlayRequestsTest {
    @Test fun lastUserIntentWinsEvenWhenOldNetworkIgnoresCancellation() = runBlocking {
        val gate = PlayRequestGate()
        val requests = PendingPlayRequests(this,gate::next,gate::isCurrent)
        val a = CompletableDeferred<String>(); val b = CompletableDeferred<String>()
        val result = mutableListOf<String>()
        val first = requests.launch({ withContext(NonCancellable) { a.await() } },result::add)
        yield()
        val second = requests.launch({ b.await() },result::add)
        a.complete("first"); first.join()
        assertTrue(result.isEmpty())
        b.complete("second"); second.join()
        assertEquals(listOf("second"),result)
    }
    @Test fun directPlaybackInvalidatesAWaitingRemoteList() = runBlocking {
        val gate=PlayRequestGate();val requests=PendingPlayRequests(this,gate::next,gate::isCurrent)
        val response=CompletableDeferred<String>();var committed=false
        val job=requests.launch({response.await()}) {committed=true}
        yield();gate.next();response.complete("stale");job.join()
        assertFalse(committed)
    }
}
