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
import com.takahashirinta.ncrust.cache.OfflineAudioCache
import com.takahashirinta.ncrust.cache.OfflineContent
import com.takahashirinta.ncrust.cache.OfflineKeys
import com.takahashirinta.ncrust.cache.OfflineUrlStore
import com.takahashirinta.ncrust.network.NetworkAvailability
import com.takahashirinta.ncrust.network.SongItem
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

    private fun runQueue(gapless: Boolean) {
        assertFalse("Run with mobile data and Wi-Fi disabled", NetworkAvailability.isOnline(app))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val settings = app.getSharedPreferences("ncrust_settings", 0)
        settings.edit().putBoolean("gapless_playback", gapless).apply()
        PlaybackStateManager.savePlayMode(app, QueueModes.CYCLE)
        val tracks = listOf(961_001L, 961_002L, 961_003L).map { id ->
            cacheSilence(id)
            SongItem(id = id, name = "离线测试 $id", artists = null, album = null, duration = 24_000L)
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                val queue = app.playback.queue
                while (queue.mode != QueueModes.CYCLE) queue.cycleMode()
                queue.replaceAndPlay(tracks, enterFm = false, startIndex = 0)
            }
            await("First track ready, expected preload state") {
                PlaybackService.instance?.let { service ->
                    service.player.isPlaying && service.playingTrackKey()?.id == tracks[0].id &&
                        service.player.mediaItemCount == if (gapless) 2 else 1
                } == true
            }
            scenario.onActivity { it.finishAndRemoveTask() }
            val until = SystemClock.elapsedRealtime() + 8_000L
            while (scenario.state != Lifecycle.State.DESTROYED &&
                SystemClock.elapsedRealtime() < until
            ) SystemClock.sleep(50)
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
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
