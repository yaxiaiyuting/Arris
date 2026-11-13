/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.takahashirinta.ncrust.MainActivity
import com.takahashirinta.ncrust.NcrustApplication
import com.takahashirinta.ncrust.QueueModes
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.cache.OfflineAudioCache
import com.takahashirinta.ncrust.cache.OfflineContent
import com.takahashirinta.ncrust.cache.OfflineKeys
import com.takahashirinta.ncrust.cache.OfflineUrlStore
import com.takahashirinta.ncrust.listen.TogetherSession
import com.takahashirinta.ncrust.network.NetworkAvailability
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.TrackKey
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@android.annotation.SuppressLint("UnsafeOptInUsageError")
class BackgroundQueueTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as NcrustApplication

    @Test
    fun removalKeepsPreloadedAndLaterTracksInTheSameQueue() = runQueue(gapless = true)

    @Test
    fun removalStillAdvancesAtNaturalEndWithoutPreload() = runQueue(gapless = false)

    @Test
    fun linearQueueStagesFirstTrackWithoutPlayingItAtNaturalEnd() {
        val tracks = prepareTracks(gapless = true)
        val scenario = launchQueue(tracks, mode = QueueModes.LINE, startIndex = tracks.lastIndex)
        try {
            awaitPlaying(tracks.last().id)
            removeTask(scenario)
            finishCurrentTrack()
            await("Linear queue stops and selects its first track") {
                app.playback.queue.index == 0 && !app.playback.isPlaying.value
            }
            main {
                assertEquals(tracks.first().id, app.playback.currentSongId.value)
                assertEquals(tracks.first().id, PlaybackStateManager.getState(app)?.songId)
                assertEquals(tracks.first().id, PlaybackService.mediaSongId)
                assertFalse(requireNotNull(PlaybackService.instance).hasPlayableItem())
                app.startService(PlaybackCommands.intent(app).putExtra("action", "resume"))
            }
            awaitPlaying(tracks.first().id)
        } finally {
            main { app.playback.stopService() }
            scenario.close()
        }
    }

    @Test
    fun manualNextAtLinearTailClearsTheStoppedTrackProgress() {
        val tracks = prepareTracks(gapless = false)
        val scenario = launchQueue(tracks, mode = QueueModes.LINE, startIndex = tracks.lastIndex)
        try {
            awaitPlaying(tracks.last().id)
            main {
                requireNotNull(PlaybackService.instance).player.seekTo(10_000L)
                app.playback.queue.skip(forward = true)
                assertEquals(tracks.first().id, app.playback.currentSongId.value)
                assertEquals(0L, app.playback.currentPosition.value)
                assertEquals(0L, app.playback.duration.value)
                assertFalse(app.playback.isPlaying.value)
            }
        } finally {
            main { app.playback.stopService() }
            scenario.close()
        }
    }

    @Test
    fun shuffleKeepsItsPlannedSuccessorAtTheRoundBoundary() {
        val tracks = prepareTracks(gapless = true)
        val scenario = launchQueue(tracks, mode = QueueModes.SHUFFLE)
        try {
            awaitPlaying(tracks.first().id)
            val round = onMain { app.playback.queue.shuffleOrder.toList() }
            assertEquals(tracks.indices.toSet(), round.toSet())
            removeTask(scenario)
            for (next in round.drop(1)) {
                finishCurrentTrack()
                awaitPlaying(tracks[next].id)
            }
            val last = round.last()
            await("The next round is already in Media3") {
                app.playback.queue.shufflePosition == round.lastIndex &&
                    PlaybackService.instance?.player?.mediaItemCount == 2
            }
            finishCurrentTrack()
            await("The next round starts at the item after the previous round's last track") {
                val queue = app.playback.queue
                val service = PlaybackService.instance ?: return@await false
                queue.shufflePosition == 1 && queue.shuffleOrder.firstOrNull() == last &&
                    queue.index == queue.shuffleOrder[1] && queue.index != last &&
                    service.player.isPlaying && service.playingTrackKey()?.id == tracks[queue.index].id
            }
        } finally {
            main { app.playback.stopService() }
            scenario.close()
        }
    }

    @Test
    fun singleTrackRepeatsAfterRemovalWithoutAnAttachedPreload() {
        val tracks = prepareTracks(gapless = false)
        val scenario = launchQueue(tracks, mode = QueueModes.SINGLE)
        try {
            awaitPlaying(tracks.first().id)
            removeTask(scenario)
            await("First playback has accumulated listening time") {
                listenedMs(tracks.first().id) >= 1_000L
            }
            finishCurrentTrack()
            await("Single track is playing again from the beginning") {
                val service = PlaybackService.instance ?: return@await false
                val player = service.player
                player.isPlaying && player.currentPosition in 100L..2_000L &&
                    service.playingTrackKey()?.id == tracks.first().id && app.playback.queue.index == 0
            }
            main { assertTrue("Repeat starts its own listening interval", listenedMs(tracks.first().id) < 1_000L) }
        } finally {
            main { app.playback.stopService() }
            scenario.close()
        }
    }

    @Test
    fun logoutRevokesAnAlreadyAttachedSuccessorWithoutStoppingCurrentAudio() {
        val tracks = prepareTracks(gapless = true)
        CookieManager.saveCookie(app, "MUSIC_U=local-test; __csrf=local-test")
        val scenario = launchQueue(tracks)
        try {
            await("The old account has a successor attached") {
                PlaybackService.instance?.let {
                    it.player.isPlaying && it.playingTrackKey()?.id == tracks.first().id &&
                        it.player.mediaItemCount == 2
                } == true
            }
            removeTask(scenario)
            // There is no second valid URL after logout, so a reload cannot mask the old slot.
            OfflineAudioCache.get(app).removeResource(OfflineKeys.key(tracks[1].id, "higher"))
            CookieManager.clearCookie(app)
            await("Logout removes only the successor from Media3") {
                PlaybackService.instance?.let {
                    it.player.isPlaying && it.playingTrackKey()?.id == tracks.first().id &&
                        it.player.mediaItemCount == 1
                } == true
            }
        } finally {
            CookieManager.clearCookie(app)
            main { app.playback.stopService() }
            scenario.close()
        }
    }

    @Test
    fun aMemberWithoutItsScreenDoesNotAdvanceThroughTheLocalQueue() {
        val tracks = prepareTracks(gapless = true)
        val scenario = launchQueue(tracks)
        try {
            await("First track has a local successor attached") {
                PlaybackService.instance?.let {
                    it.player.isPlaying && it.playingTrackKey()?.id == tracks.first().id &&
                        it.player.mediaItemCount == 2
                } == true
            }
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { room ->
                room.soTimeout = 3_000
                assertTrue(TogetherSession.joinLan("127.0.0.1:${room.localPort}"))
                room.accept().use {
                    removeTask(scenario)
                    finishCurrentTrack()
                    await("Member waits at the current track's end") {
                        PlaybackService.instance?.let { service ->
                            service.player.playbackState == Player.STATE_ENDED &&
                                service.playingTrackKey()?.id == tracks.first().id
                        } == true
                    }
                }
            }
        } finally {
            TogetherSession.leave()
            main { app.playback.stopService() }
            scenario.close()
        }
    }

    @Test
    fun closingRoomDropsItsUnplayedSuccessorAndRestoresTheLocalQueue() {
        val tracks = prepareTracks(gapless = true)
        val scenario = launchQueue(tracks)
        try {
            awaitPlaying(tracks.first().id)
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { room ->
                room.soTimeout = 3_000
                assertTrue(TogetherSession.joinLan("127.0.0.1:${room.localPort}"))
                room.accept().use {
                    val generation = requireNotNull(TogetherSession.memberPlayback.value.generation)
                    val roomTrack = tracks.last().copy(id = 961_004L, name = "房间测试 961004")
                    cacheSilence(roomTrack.id)
                    main {
                        app.playback.queue.setRoomSuccessor(generation, TrackKey.ofSong(tracks.first()), roomTrack)
                    }
                    await("Member preloads the room's next track without saving it to the local queue") {
                        pendingTrackId() == roomTrack.id && app.playback.queue.songs.none { it.id == roomTrack.id }
                    }
                }
                await("The LAN host closed and the original local successor is attached") {
                    !TogetherSession.connected && pendingTrackId() == tracks[1].id
                }
                finishCurrentTrack()
                awaitPlaying(tracks[1].id)
            }
        } finally {
            TogetherSession.leave()
            main { app.playback.stopService() }
            scenario.close()
        }
    }

    private fun pendingTrackId(): Long? {
        val player = PlaybackService.instance?.player ?: return null
        val next = player.currentMediaItemIndex + 1
        if (next >= player.mediaItemCount) return null
        return TrackKey.fromMediaId(player.getMediaItemAt(next).mediaId)?.id
    }

    private fun listenedMs(songId: Long): Long {
        val clockField = app.playback.javaClass.getDeclaredField("listeningClock").apply { isAccessible = true }
        return (clockField.get(app.playback) as ListeningClock).playedMs(songId)
    }

    @Test
    fun removingTaskDuringCurrentSelectionKeepsForegroundService() {
        val tracks = prepareTracks(gapless = false)
        val scenario = launchQueue(tracks)
        try {
            awaitPlaying(tracks[0].id)
            main {
                val service = requireNotNull(PlaybackService.instance)
                val revision = app.playback.beginUserPlayIntent()
                service.waitForPlaybackSelection(PlaybackCommands.intent(app).putExtra("playRevision", revision))
                assertFalse(service.hasPlayableItem())
                service.onTaskRemoved(null)
                val next = tracks[1]
                assertTrue(service.submitResolvedPlayback(PlaybackCommands.intent(app)
                    .putExtra("playRevision", revision)
                    .putExtra("url", OfflineKeys.withKey("https://offline.invalid/${next.id}.wav", next.id, "higher"))
                    .putExtra("songId", next.id)
                    .putExtra("title", next.name)))
            }
            awaitPlaying(tracks[1].id)
        } finally {
            main { app.playback.stopService() }
            scenario.close()
        }
    }

    private fun runQueue(gapless: Boolean) {
        val tracks = prepareTracks(gapless)
        val scenario = launchQueue(tracks)
        try {
            await("First track ready, expected preload state") {
                PlaybackService.instance?.let { service ->
                    service.player.isPlaying && service.playingTrackKey()?.id == tracks[0].id &&
                        service.player.mediaItemCount == if (gapless) 2 else 1
                } == true
            }
            removeTask(scenario)
            main { assertEquals(tracks[0].id, PlaybackService.instance?.playingTrackKey()?.id) }

            finishCurrentTrack()
            awaitPlaying(tracks[1].id)
            finishCurrentTrack()
            awaitPlaying(tracks[2].id)
            await("Queue cursor follows the third track") { app.playback.queue.index == 2 }

            ActivityScenario.launch(MainActivity::class.java).use { reopened ->
                reopened.onActivity {
                    assertEquals(tracks[2].id, app.playback.queue.currentSong?.id)
                    assertEquals(tracks[2].id, PlaybackService.instance?.playingTrackKey()?.id)
                }
            }
        } finally {
            main { app.playback.stopService() }
            scenario.close()
        }
    }

    private fun prepareTracks(gapless: Boolean): List<SongItem> {
        assertFalse("Run with mobile data and Wi-Fi disabled", NetworkAvailability.isOnline(app))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val settings = app.getSharedPreferences("ncrust_settings", 0)
        settings.edit().putBoolean("gapless_playback", gapless).apply()
        PlaybackStateManager.savePlayMode(app, QueueModes.CYCLE)
        return listOf(961_001L, 961_002L, 961_003L).map { id ->
            cacheSilence(id)
            SongItem(id = id, name = "离线测试 $id", artists = null, album = null, duration = 24_000L)
        }
    }

    private fun launchQueue(tracks: List<SongItem>, mode: Int = QueueModes.CYCLE, startIndex: Int = 0) =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity {
                val queue = app.playback.queue
                while (queue.mode != mode) queue.cycleMode()
                queue.replaceAndPlay(tracks, enterFm = false, startIndex = startIndex)
            }
        }

    private fun removeTask(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.finishAndRemoveTask() }
        val until = SystemClock.elapsedRealtime() + 8_000L
        while (scenario.state != Lifecycle.State.DESTROYED &&
            SystemClock.elapsedRealtime() < until
        ) SystemClock.sleep(50)
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }

    private fun cacheSilence(songId: Long) {
        val silence = app.assets.open("playback-regression-silence.wav").use { it.readBytes() }
        val key = OfflineKeys.key(songId, "higher")
        val cache = OfflineAudioCache.get(app)
        cache.removeResource(key)
        val url = OfflineKeys.withKey("https://offline.invalid/$songId.wav", songId, "higher")
        OfflineUrlStore.rememberResult(app, SongUrlResult(
            url, "higher", 0L, "wav", null,
            cachedContent = OfflineContent(OfflineContent.FULL),
        ))
        val span = cache.startReadWrite(key, 0, silence.size.toLong())
        try {
            if (span.isHoleSpan) {
                val file = cache.startFile(key, 0, silence.size.toLong())
                file.writeBytes(silence)
                cache.commitFile(file, silence.size.toLong())
                cache.applyContentMetadataMutations(key, ContentMetadataMutations().also {
                    ContentMetadataMutations.setContentLength(it, silence.size.toLong())
                })
            }
        } finally {
            if (span.isHoleSpan) cache.releaseHoleSpan(span)
        }
        assertTrue(OfflineAudioCache.coversStart(app, key))
    }

    private fun finishCurrentTrack() = main {
        val player = requireNotNull(PlaybackService.instance).player
        assertTrue(player.duration > 2_000L)
        player.seekTo(player.duration - 1_500L)
    }

    private fun awaitPlaying(songId: Long) = await("Playing $songId") {
        PlaybackService.instance?.let {
            it.playingTrackKey()?.id == songId && it.player.playbackState == Player.STATE_READY &&
                it.player.isPlaying
        } == true
    }

    private fun main(action: () -> Unit) {
        instrumentation.runOnMainSync { action() }
    }

    private fun <T> onMain(action: () -> T): T {
        val result = java.util.concurrent.FutureTask(action)
        instrumentation.runOnMainSync(result)
        return result.get()
    }

    private fun await(description: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 12_000L
        while (SystemClock.elapsedRealtime() < until) {
            var done = false
            main { done = condition() }
            if (done) return
            SystemClock.sleep(50)
        }
        throw AssertionError(description)
    }
}
