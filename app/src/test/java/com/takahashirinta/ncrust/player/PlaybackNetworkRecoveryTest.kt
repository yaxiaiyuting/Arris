package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.network.PlaybackNetworkRoute
import com.takahashirinta.ncrust.source.MusicSource
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackNetworkRecoveryTest {
    private val wifi = PlaybackNetworkRoute(1L, true)
    private val mobile = PlaybackNetworkRoute(2L, true)
    private val offline = PlaybackNetworkRoute(null, false)
    @Test fun offlineCacheMissNeverCallsNetwork() = runBlocking {
        var calls = 0
        val result = resolveOfflineFirst(false, MusicSource.NETEASE, { null }, {
            calls++; error("must not request while offline")
        })
        assertEquals(ResolveFailureKind.NETWORK, result.failure?.kind)
        assertEquals(0, calls)
    }
    @Test fun offlineCachedSongIsImmediatelyPlayable() = runBlocking {
        val cached = SongUrlResult("https://fixture.invalid/cached", "standard")
        assertSame(cached, resolveOfflineFirst(false, MusicSource.QQMUSIC, { cached }, {
            error("must not request")
        }).result)
    }
    @Test fun connectionLostDuringRequestCanFallBackToCache() = runBlocking {
        val cached = SongUrlResult("https://fixture.invalid/cached", "standard")
        assertSame(cached, resolveOfflineFirst(true, MusicSource.NETEASE, { cached }, {
            ResolveOutcome.failed(ResolveFailureKind.NETWORK, MusicSource.NETEASE)
        }).result)
    }
    @Test fun networkReturnRecoversTheLastUncachedChoice() {
        val gate = PlaybackRecoveryGate(wifi)
        gate.begin(); gate.failed(true)
        assertFalse(gate.networkChanged(offline))
        assertTrue(gate.networkChanged(mobile))
        assertTrue(gate.needsRecovery(false))
        assertFalse(gate.networkChanged(mobile)) // Repeated capabilities notifications do nothing.
        gate.begin(); gate.resolved()
        assertFalse(gate.needsRecovery(false))
    }
    @Test fun healthyPlaybackIsNotRestartedOnWifiMobileHandover() {
        val gate = PlaybackRecoveryGate(wifi)
        gate.begin(); gate.resolved()
        assertTrue(gate.networkChanged(mobile))
        assertFalse(gate.needsRecovery(false))
        assertTrue(gate.needsRecovery(true)) // Only a stalled stream needs recovery.
    }
    @Test fun manualPauseOrDifferentSelectionRevokesPendingRecovery() {
        val gate = PlaybackRecoveryGate(wifi)
        gate.begin(); gate.failed(true); gate.cancel()
        assertTrue(gate.networkChanged(mobile))
        assertFalse(gate.needsRecovery(true))
        gate.begin() // Clicking another uncached song is always accepted.
        assertTrue(gate.resolving)
        assertTrue(gate.needsRecovery(false))
    }
    @Test fun copyrightOrAuthenticationFailureDoesNotAutoResumeAfterHandover() {
        val gate = PlaybackRecoveryGate(wifi)
        gate.begin(); gate.failed(false)
        assertTrue(gate.networkChanged(mobile))
        assertFalse(gate.needsRecovery(true))
    }
    @Test fun playButtonAfterUncachedFailureMustResolveInsteadOfResumingOldAudio() {
        val gate = PlaybackRecoveryGate(wifi)
        gate.begin(); gate.failed(true)
        assertTrue(gate.shouldResolveBeforePlay(true, false))
        gate.cancel() // User paused while URL was still missing.
        assertTrue(gate.shouldResolveBeforePlay(false, false))
        gate.begin(); gate.resolved()
        assertFalse(gate.shouldResolveBeforePlay(true, false))
        assertTrue(gate.shouldResolveBeforePlay(true, true))
    }
    @Test fun validationOrVpnUnderlyingTransportChangeCanRecoverWithoutHardValidationGate() {
        val gate = PlaybackRecoveryGate(PlaybackNetworkRoute(3L, true, 1))
        gate.begin(); gate.failed(true)
        assertTrue(gate.networkChanged(PlaybackNetworkRoute(3L, true, 2)))
        assertTrue(gate.needsRecovery(false))
        assertFalse(gate.networkChanged(PlaybackNetworkRoute(3L, true, 2)))
    }

    @Test fun newNetworkAndNewSongDoNotInheritAnOldRequestsRetryTimer() {
        val retry = UrlRetryGate()
        retry.onNewSong("A")
        assertTrue(retry.requestRetry(1000))
        assertFalse(retry.requestRetry(1100))
        retry.onUserAction() // A settled new route explicitly starts a new recovery budget.
        assertTrue(retry.requestRetry(1100))
        retry.onNewSong("B")
        assertTrue(retry.requestRetry(1200))
    }
    @Test fun zeroElapsedTimeStillEnforcesTheRetryInterval() {
        val retry = UrlRetryGate()
        assertTrue(retry.requestRetry(0))
        assertFalse(retry.requestRetry(1))
    }

}
