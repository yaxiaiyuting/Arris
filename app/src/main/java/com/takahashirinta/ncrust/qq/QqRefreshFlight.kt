package com.takahashirinta.ncrust.qq

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Waiting callers re-check the session/window after the active refresh has committed. */
internal class QqRefreshFlight {
    private val mutex=Mutex()
    suspend fun <T> run(block:suspend ()->T):T=mutex.withLock {block()}
}

internal object QqRefreshRetry {
    private fun credentialChanged(started:Long,current:Long,loggedIn:Boolean):Boolean = loggedIn && started!=current
    suspend fun <T> request(
        once:suspend ()->T,
        rejected:(T)->Boolean,
        refresh:suspend ()->Boolean,
        revision:()->Long,
        loggedIn:()->Boolean,
    ):T {
        val started=revision()
        val first=once()
        if(!rejected(first))return first
        // The old rejection has no authority over a ticket committed since the request began.
        if(credentialChanged(started,revision(),loggedIn()))return once()
        val refreshed=refresh()
        return if(loggedIn() && (refreshed || started!=revision()))once() else first
    }
}
