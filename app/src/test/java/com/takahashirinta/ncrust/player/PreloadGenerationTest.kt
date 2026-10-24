package com.takahashirinta.ncrust.player

import org.junit.Assert.*
import org.junit.Test

class PreloadGenerationTest {
    @Test fun removingPendingTrackRejectsAlreadyQueuedIntentAndInFlightResult() {
        val g=PreloadGeneration();val request=g.current();val queuedIntent=request
        g.invalidate()
        assertFalse(g.accepts(request));assertFalse(g.accepts(queuedIntent))
        assertTrue(g.accepts(g.current()))
    }
    @Test fun deleteThenReinsertSameTrackDoesNotResurrectOldIntent() {
        val g=PreloadGeneration();val old=g.current();g.invalidate();g.invalidate()
        assertFalse(g.accepts(old))
    }
    @Test fun movingOrAppendingAlsoChangesTheNextTrackPlan() {
        val g=PreloadGeneration();var old=g.current()
        repeat(5) {g.invalidate();assertFalse(g.accepts(old));old=g.current();assertTrue(g.accepts(old))}
    }
}
