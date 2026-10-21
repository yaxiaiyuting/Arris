package com.takahashirinta.ncrust.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MonotonicWriteGateTest {
    @Test fun firstWriteAtBootZeroDoesNotWaitAndWallClockRollbackDoesNotChangePacing() = runBlocking {
        var elapsed=0L;var wall=1_700_003_600_000L;val waits=mutableListOf<Long>()
        val gate=MonotonicWriteGate({elapsed},{waits+=it;elapsed+=it})
        gate.run {assertEquals(1_700_003_600_000L,wall)}
        elapsed+=500;wall-=3_600_000
        gate.run {assertEquals(1_700_000_000_000L,wall)}
        assertEquals(listOf(1500L),waits)
    }
    @Test fun slowRequestSpacesFromCompletionAndLongIdleDoesNotWait() = runBlocking {
        var elapsed=100L;val waits=mutableListOf<Long>()
        val gate=MonotonicWriteGate({elapsed},{waits+=it;elapsed+=it})
        gate.run {elapsed+=9000}
        gate.run { }
        elapsed+=3_000;gate.run { }
        assertEquals(listOf(2000L),waits)
    }
    @Test fun unexpectedMonotonicResetCannotCauseAnUnboundedDelay() = runBlocking {
        var elapsed=100_000L;val waits=mutableListOf<Long>()
        val gate=MonotonicWriteGate({elapsed},{waits+=it})
        gate.run { };elapsed=2;gate.run { }
        assertTrue(waits.isEmpty())
    }
    @Test fun failingWriteStillPacesTheNextAttempt() = runBlocking {
        var elapsed=100L;val waits=mutableListOf<Long>()
        val gate=MonotonicWriteGate({elapsed},{waits+=it;elapsed+=it})
        try {gate.run {throw IllegalStateException("offline")}}catch(_:IllegalStateException) { }
        gate.run { }
        assertEquals(listOf(2000L),waits)
    }
}
