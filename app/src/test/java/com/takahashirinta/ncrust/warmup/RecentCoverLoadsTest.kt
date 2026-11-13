/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.warmup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentCoverLoadsTest {
    @Test fun overlappingListsReuseSuccessAndNewCoversLoadImmediately() = runBlocking {
        var time = 0L
        val covers = RecentCoverLoads { time }
        val release = CompletableDeferred<Unit>()
        var firstRequests = 0
        var secondRequests = 0
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            covers.load("shared") {
                firstRequests++
                release.await()
                true
            }
        }
        covers.load("shared") { error("The first request still owns this cover") }
        covers.load("new") { secondRequests++; true }
        release.complete(Unit)
        first.join()

        time = 59_999L
        covers.load("shared") { error("The first cover is still fresh") }
        assertEquals(1, firstRequests)
        assertEquals(1, secondRequests)
        time = 60_000L
        covers.load("shared") { firstRequests++; true }
        assertEquals(2, firstRequests)
    }

    @Test fun failureAndCancellationReleaseTheCoverForReentry() = runBlocking {
        val covers = RecentCoverLoads { 0L }
        val pending = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            covers.load("retry") { pending.await(); true }
        }
        first.cancelAndJoin()
        var requests = 0
        covers.load("retry") { requests++; false }
        covers.load("retry") { requests++; true }
        covers.load("retry") { error("A successful retry is reusable") }
        assertEquals(2, requests)
    }
}
