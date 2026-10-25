/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import com.takahashirinta.ncrust.source.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Debug-only local, silent service probe; no account, network, or phone interaction needed. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
class PlaybackRegressionProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val checks = mutableListOf<String>()
            var observed: TrackKey? = null
            var ended = 0
            val qq = TrackKey(MusicSource.QQMUSIC, (1L shl 62) + 123, "qq-mid", "qq-file")
            val ncm = TrackKey(MusicSource.NETEASE, 456)
            fun play(track: TrackKey, nullSource: Boolean = false) {
                androidx.core.content.ContextCompat.startForegroundService(this@PlaybackRegressionProbeActivity, Intent(this@PlaybackRegressionProbeActivity, PlaybackService::class.java).apply {
                    putExtra("url", "asset:///playback-regression-silence.wav")
                    putExtra("songId", track.id)
                    putExtra("title", "Arris local regression probe")
                    putExtra("sourceKey", if (nullSource) null else track.source.key)
                    putExtra("sourceId", track.sourceId)
                    putExtra("mediaId", track.mediaId)
                })
            }
            suspend fun awaitTrack(track: TrackKey) = withTimeout(8000) {
                while (PlaybackService.instance?.playingTrackKey() != track || observed != track) delay(30)
            }
            try {
                PlaybackService.onTrackTransitioned = { source, id, sid, mid, _, _, _ -> observed = TrackKey(source,id,sid,mid) }
                PlaybackService.onPlaybackEnded = { ended++ }
                play(qq); awaitTrack(qq)
                check(observed?.sourceId == "qq-mid" && observed?.mediaId == "qq-file")
                checks += "QQ source and identifiers installed"
                play(ncm, true); awaitTrack(ncm)
                check(observed?.sourceId == null && observed?.mediaId == null)
                checks += "QQ to Netease with null source clears old source and identifiers"
                val oldPlay = playRequestGeneration.next()
                playRequestGeneration.next()
                startService(Intent(this@PlaybackRegressionProbeActivity, PlaybackService::class.java).apply {
                    putExtra("url", "asset:///playback-regression-silence.wav")
                    putExtra("playRevision", oldPlay);putExtra("songId", qq.id)
                    putExtra("sourceKey", qq.source.key);putExtra("sourceId", qq.sourceId)
                })
                delay(250)
                check(PlaybackService.instance?.playingTrackKey() == ncm)
                checks += "already queued superseded play request cannot replace current item"
                val service = PlaybackService.instance!!
                val playerField = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }
                val player = playerField.get(service) as Player
                startService(Intent(this@PlaybackRegressionProbeActivity,PlaybackService::class.java).apply {
                    putExtra("action","preload_next");putExtra("url","asset:///playback-regression-silence.wav")
                    putExtra("songId",789L);putExtra("sourceKey","netease")
                    putExtra("preloadRevision",preloadGeneration.current())
                    putExtra("preloadOwner",SourceIds.mediaId(qq.source,qq.id))
                })
                delay(250)
                check(player.mediaItemCount == 1)
                checks += "stale QQ-owned preload rejected behind Netease item"
                val next = TrackKey(MusicSource.NETEASE,789)
                PlaybackService.onPlaybackNext = { play(next,true) }
                startService(Intent(this@PlaybackRegressionProbeActivity,PlaybackService::class.java).putExtra("action","next"))
                awaitTrack(next)
                check(ended == 0)
                checks += "manual next replaces actual media item without natural-end callback"
                val token = androidx.media3.session.SessionToken(this@PlaybackRegressionProbeActivity,
                    android.content.ComponentName(this@PlaybackRegressionProbeActivity, PlaybackService::class.java))
                val future = androidx.media3.session.MediaController.Builder(this@PlaybackRegressionProbeActivity,token).buildAsync()
                withTimeout(8000) { while (!future.isDone) delay(30) }
                val controller = future.get()
                try {
                    var nextCalls = 0
                    var target = 900L
                    PlaybackService.onPlaybackNext = { nextCalls++; play(TrackKey(MusicSource.NETEASE,target),true) }
                    check(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
                    controller.seekToNext();awaitTrack(TrackKey(MusicSource.NETEASE,target));delay(150)
                    check(nextCalls == 1 && player.mediaItemCount == 1)
                    checks += "MediaController next on one-item internal playlist dispatches exactly once"
                    target = 901L
                    controller.seekToNextMediaItem();awaitTrack(TrackKey(MusicSource.NETEASE,target));delay(150)
                    check(nextCalls == 2)
                    checks += "MediaController next-media-item dispatches exactly once"
                    var previousCalls = 0
                    PlaybackService.onPlaybackPrevious = { previousCalls++; play(ncm,true) }
                    check(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
                    controller.seekToPrevious();awaitTrack(ncm);delay(150)
                    check(previousCalls == 1 && ended == 0)
                    checks += "MediaController previous replaces item without natural completion"
                } finally { controller.release() }
                val data = JSONObject().put("success",true).put("checks",org.json.JSONArray(checks)).put("naturalEndCount",ended)
                filesDir.resolve("playback-regression.json").writeText(data.toString(2))
            } catch (e: Throwable) {
                filesDir.resolve("playback-regression.json").writeText(JSONObject().put("success",false).put("checks",org.json.JSONArray(checks)).put("error",e.toString()).toString(2))
            } finally {
                stopService(Intent(this@PlaybackRegressionProbeActivity,PlaybackService::class.java))
                PlaybackService.onTrackTransitioned = null
                PlaybackService.onPlaybackNext = null
                PlaybackService.onPlaybackEnded = null
                PlaybackService.onPlaybackPrevious = null
            }
        }
    }
}
