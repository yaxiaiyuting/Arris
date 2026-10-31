package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.ui.viewmodel.PlayRequestGate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LatestPlaybackActionTest {
    @Test fun rapidSkipsAdvanceEverySelectionButRunOnlyTheFinalTransition() = runBlocking {
        val epoch = PlayRequestGate(); val played = mutableListOf<Int>(); var selected = 0
        val action = LatestPlaybackAction(this, epoch::next, epoch::isCurrent, 35)
        repeat(7) { selected++; val target = selected; action.submit { played.add(target) }; delay(3) }
        delay(80)
        assertEquals(7, selected)
        assertEquals(listOf(7), played)
    }
    @Test fun choosingAnotherSongCancelsAnOlderQueuedSkip() = runBlocking {
        val epoch = PlayRequestGate(); var played = false
        val action = LatestPlaybackAction(this, epoch::next, epoch::isCurrent, 35)
        action.submit { played = true }
        epoch.next() // A direct song selection takes playback ownership immediately.
        delay(80)
        assertFalse(played)
    }
    @Test fun directionChangesKeepOnlyTheFinalSelectedDestination() = runBlocking {
        val epoch = PlayRequestGate(); val played = mutableListOf<Int>(); var selected = 2
        val action = LatestPlaybackAction(this, epoch::next, epoch::isCurrent, 35)
        for (direction in listOf(1, 1, -1)) {
            selected += direction; val target = selected; action.submit { played.add(target) }
        }
        delay(80)
        assertEquals(listOf(3), played)
    }
    @Test fun explicitCancelDoesNotRunADeferredTransition() = runBlocking {
        val epoch = PlayRequestGate(); var played = false
        val action = LatestPlaybackAction(this, epoch::next, epoch::isCurrent, 35)
        action.submit { played = true }; action.cancel(); delay(80)
        assertFalse(played)
    }
}
