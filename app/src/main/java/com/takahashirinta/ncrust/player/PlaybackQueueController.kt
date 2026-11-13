/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.takahashirinta.ncrust.QueueModes
import com.takahashirinta.ncrust.listen.PlaybackSnapshot
import com.takahashirinta.ncrust.listen.TogetherSession
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main-thread queue state shared by the player and any attached UI. */
internal class PlaybackQueueController(
    private val context: Context,
    private val player: PlayerViewModel,
    private val scope: CoroutineScope,
) {
    var songs by mutableStateOf<List<SongItem>>(emptyList())
    var index by mutableIntStateOf(-1)
    var currentSong by mutableStateOf<SongItem?>(null)
        private set
    var mode by mutableIntStateOf(PlaybackStateManager.getPlayMode(context))
        private set
    var shuffleOrder by mutableStateOf<List<Int>>(emptyList())
    var shufflePosition by mutableIntStateOf(0)
    private var fmMode = false
    private var pendingRound: List<Int>? = null
    private var lastNonInfinityMode = QueueModes.CYCLE

    private val selections = LatestPlaybackAction(
        scope, player::beginUserPlayIntent, playRequestGeneration::isCurrent,
    )
    private var restoreJob: Job? = null
    private var extensionJob: Job? = null
    private var extension: Extension? = null
    private var pendingTransition: Transition? = null
    private var roomSuccessor: RoomSuccessor? = null
    private var shuffleKeysFor: List<SongItem>? = null
    private var shuffleKeys: Lazy<List<String>>? = null

    private class Extension(
        val songs: List<SongItem>,
        val seed: SongItem,
        val fm: Boolean,
        val epoch: Int,
        var startOrigin: PlayOrigin?,
    )

    private data class Transition(
        val auto: Boolean,
        val track: TrackKey?,
        val epoch: Int,
        val playedRoomSuccessor: SongItem?,
    )
    private data class RoomSuccessor(val generation: Long, val owner: TrackKey, val song: SongItem)

    fun restore() {
        val initialSongs = songs
        val epoch = playRequestGeneration.current
        restoreJob = scope.launch {
            val (saved, cursor) = PlaybackStateManager.getQueue(context) ?: return@launch
            if (saved.isEmpty()) return@launch
            val savedIndex = cursor.coerceIn(saved.indices)
            val savedMode = mode
            val round = if (savedMode == QueueModes.SHUFFLE) {
                PlaybackStateManager.restoreShuffle(context, saved, savedIndex)
            } else null
            if (!playRequestGeneration.isCurrent(epoch) || songs !== initialSongs || index >= 0) return@launch

            val track = player.currentTrackKey
            songs = saved
            index = PlaybackQueueLogic.resolveCurrentIndex(saved, track, savedIndex)
            if (mode == QueueModes.SHUFFLE) {
                if (round == null || mode != savedMode) reshuffle() else {
                    shuffleOrder = round.order.orEmpty()
                    shufflePosition = PlaybackQueueLogic.realignShuffledPosition(shuffleOrder, index, round.position)
                }
            }
            val selected = saved[index]
            if (QueueKeys.keyOf(selected) == track) {
                player.adoptTrackIdentity(selected.source, selected.id, selected.sourceId, selected.mediaId)
                currentSong = selected
            }
            if (index != savedIndex) saveCursor() else saveShuffle()
            preloadCurrentSuccessor()
        }
    }

    suspend fun resumeSavedSelection() {
        val epoch = playRequestGeneration.current
        restoreJob?.join()
        if (playRequestGeneration.isCurrent(epoch) && PlaybackService.instance?.hasPlayableItem() != true) {
            playAt(index)
        }
    }

    fun saveQueue(replan: Boolean = true) {
        PlaybackStateManager.saveQueue(context, songs, index)
        pendingRound = null
        saveShuffle()
        if (replan) preloadCurrentSuccessor()
    }

    private fun saveCursor() {
        PlaybackStateManager.saveQueueCursor(context, songs, index)
        saveShuffle()
    }

    private fun saveShuffle() {
        val queue = songs
        if (mode != QueueModes.SHUFFLE || queue.isEmpty()) {
            shuffleKeysFor = null
            shuffleKeys = null
            return
        }
        val keys = shuffleKeys?.takeIf { shuffleKeysFor === queue }
            ?: lazy { queue.map { QueueKeys.keyOf(it).tag } }.also {
                shuffleKeysFor = queue
                shuffleKeys = it
            }
        PlaybackStateManager.saveShuffle(context, shuffleOrder, shufflePosition, index) { keys.value }
    }

    fun resetShuffle() {
        shuffleOrder = emptyList()
        shufflePosition = 0
        pendingRound = null
    }

    fun clear() {
        restoreJob?.cancel()
        selections.cancel()
        extensionJob?.cancel()
        extension = null
        pendingTransition = null
        roomSuccessor = null
        player.invalidatePreload()
        songs = emptyList()
        index = -1
        currentSong = null
        resetShuffle()
        saveShuffle()
    }

    private fun newRound(): List<Int> = ShuffleRound.newRound(songs.size, index, keyOf = {
        songs.getOrNull(it)?.artists?.firstOrNull()?.id
    })

    fun reshuffle() {
        shuffleOrder = newRound()
        shufflePosition = 0
        pendingRound = null
    }

    private fun alignShuffle(usePendingRound: Boolean) {
        val pending = pendingRound.takeIf { usePendingRound }
        pendingRound = null
        val planned = pending?.indexOf(index) ?: -1
        if (pending != null && planned >= 0) {
            shuffleOrder = pending
            shufflePosition = planned
            return
        }
        val position = shuffleOrder.indexOf(index)
        if (position >= 0) shufflePosition = position else reshuffle()
    }

    private fun preloadIndex(): Int {
        if (index !in songs.indices) return -1
        val next = PlaybackQueueLogic.nextIndex(songs, index, mode, shuffleOrder, shufflePosition)
        if (next >= 0 || mode != QueueModes.SHUFFLE || shuffleOrder.isEmpty() ||
            shufflePosition < shuffleOrder.lastIndex
        ) return next
        val round = pendingRound ?: newRound().also { pendingRound = it }
        return round.getOrNull(1)?.takeIf { it in songs.indices } ?: -1
    }

    fun preloadItem(index: Int, allowCurrent: Boolean = mode == QueueModes.SINGLE): Boolean {
        val song = songs.getOrNull(index) ?: return false
        preloadSong(song, allowCurrent)
        return true
    }

    private fun preloadSong(song: SongItem, allowCurrent: Boolean, roomGeneration: Long? = null) {
        val (title, artist, artwork) = songParams(song)
        player.preloadNextSong(
            song.id, title, artist, artwork, allowCurrent,
            song.source, song.sourceId, song.mediaId, song.privilege, roomGeneration,
        )
    }

    fun preloadCurrentSuccessor() {
        val follow = TogetherSession.memberPlayback.value
        if (follow.generation != null) {
            val next = roomSuccessor?.takeIf {
                follow.allowsPreload(it.generation) && it.owner == player.currentTrackKey
            } ?: return
            preloadSong(next.song, allowCurrent = false, roomGeneration = next.generation)
            return
        }
        preloadItem(preloadIndex())
    }

    /** A proposed room track belongs to the room until Media3 actually starts it. */
    fun setRoomSuccessor(generation: Long, owner: TrackKey, song: SongItem?) {
        if (!TogetherSession.memberPlayback.value.allowsPreload(generation)) return
        if (song != null && PlaybackService.instance?.playingTrackKey() != owner) return
        val next = song?.let { RoomSuccessor(generation, owner, it) }
        if (next == roomSuccessor) return
        roomSuccessor = next
        player.invalidatePreload()
        if (next != null) preloadCurrentSuccessor()
    }

    fun onMemberPlaybackChanged(follow: TogetherSession.MemberPlayback) {
        roomSuccessor?.let { if (!follow.allowsPreload(it.generation)) roomSuccessor = null }
        val service = PlaybackService.instance ?: return
        if (service.player.isPlaying || service.isActivelyBuffering()) preloadCurrentSuccessor()
    }

    private fun applyMode(value: Int, replan: Boolean) {
        player.invalidatePreload()
        if (value != QueueModes.INFINITY) lastNonInfinityMode = value
        mode = value
        PlaybackStateManager.savePlayMode(context, value)
        if (value == QueueModes.SHUFFLE) reshuffle() else resetShuffle()
        saveShuffle()
        if (replan) preloadCurrentSuccessor()
    }

    fun cycleMode() {
        fmMode = false
        applyMode((mode + 1) % 5, replan = true)
    }

    fun replaceAndPlay(queue: List<SongItem>, enterFm: Boolean, startIndex: Int) {
        if (startIndex !in queue.indices) return
        fmMode = enterFm
        when {
            enterFm -> applyMode(QueueModes.INFINITY, replan = false)
            mode == QueueModes.INFINITY -> applyMode(lastNonInfinityMode, replan = false)
            else -> player.invalidatePreload()
        }
        songs = queue
        index = startIndex
        PlaybackStateManager.clearSongPositions(context, queue)
        if (mode == QueueModes.SHUFFLE) reshuffle()
        saveQueue(replan = false)
        playAt(startIndex)
    }

    fun playAt(
        target: Int,
        origin: PlayOrigin = PlayOrigin.USER,
        room: PlaybackSnapshot? = null,
        defer: Boolean = false,
    ) {
        if (room != null && !TogetherSession.isCurrentFollow(room.followGeneration)) return
        val song = songs.getOrNull(target) ?: return
        index = target
        if (mode == QueueModes.SHUFFLE) alignShuffle(usePendingRound = false)
        val queue = songs
        val start: () -> Unit = {
            if (songs === queue && index == target &&
                (room == null || TogetherSession.isCurrentFollow(room.followGeneration))
            ) {
                extension?.startOrigin = null
                val (title, artist, artwork) = songParams(song)
                player.playSong(
                    song.id, title, artist, artwork,
                    sourceKey = song.source, sourceId = song.sourceId, mediaId = song.mediaId,
                    origin = origin, privilege = song.privilege,
                    startPositionMs = room?.positionMs ?: -1L,
                    roomGeneration = room?.followGeneration, startPlaying = room?.playing ?: true,
                )
                saveCursor()
                preloadCurrentSuccessor()
            }
        }
        if (defer) selections.submit(start) else {
            selections.cancel()
            start()
        }
    }

    fun cancelPendingSelection() = selections.cancel()

    /** Confirm which queue occurrence is selected before using its metadata. */
    private fun indexOf(track: TrackKey?): Int {
        if (track != null && songs.getOrNull(index)?.let(QueueKeys::keyOf) == track) return index
        return PlaybackQueueLogic.indexOfKey(songs, track)
    }

    fun showSelection(track: TrackKey) {
        if (selections.hasPending) return
        val mapped = indexOf(track)
        if (mapped >= 0 && mapped != index) {
            index = mapped
            if (mode == QueueModes.SHUFFLE) alignShuffle(usePendingRound = false)
            saveCursor()
        }
        if (currentSong?.let(QueueKeys::keyOf) == track) return
        currentSong = songs.getOrNull(mapped) ?: SongItem(
            id = track.id,
            name = player.currentSongName.value.orEmpty(),
            artists = player.currentSongArtist.value?.takeIf { it.isNotBlank() }
                ?.let { listOf(ArtistItem(name = it)) },
            album = AlbumItem(id = null, name = "", picUrl = player.currentSongArtwork.value),
            duration = null,
            source = track.source.key,
            sourceId = track.sourceId,
            mediaId = track.mediaId,
        )
    }

    fun onMediaTransition(auto: Boolean, track: TrackKey?, room: Long?) {
        val played = roomSuccessor?.takeIf { room == it.generation && track == QueueKeys.keyOf(it.song) }
        if (played != null) roomSuccessor = null
        pendingTransition = Transition(auto, track, playRequestGeneration.current, played?.song)
        // Apply playlist changes after Media3 has left its listener.
        scope.launch(Dispatchers.Main) {
            if (reconcileTransition()) preloadOrExtendQueue()
        }
    }

    private fun reconcileTransition(): Boolean {
        val transition = pendingTransition ?: return false
        pendingTransition = null
        if (selections.hasPending || !playRequestGeneration.isCurrent(transition.epoch)) return false
        transition.playedRoomSuccessor?.let { next ->
            val plan = QueueKeys.planPlayItem(songs, index, next)
            songs = plan.queue
            index = plan.playIndex
            currentSong = songs[index]
            if (mode == QueueModes.SHUFFLE) {
                if (plan.queueChanged) reshuffle() else alignShuffle(usePendingRound = false)
            }
            if (plan.queueChanged) saveQueue(replan = false) else saveCursor()
            return true
        }
        if (songs.isEmpty()) return false
        val mapped = indexOf(transition.track)
        if (mapped >= 0) {
            index = mapped
            if (mode == QueueModes.SHUFFLE) alignShuffle(usePendingRound = transition.auto)
        } else {
            if (!transition.auto || transition.track != null) {
                transition.track?.let(::showSelection)
                return false
            }
            index = when (mode) {
                QueueModes.SHUFFLE -> advanceShuffle()
                QueueModes.SINGLE -> index
                QueueModes.LINE -> (index + 1).coerceIn(songs.indices)
                else -> if (index < songs.lastIndex) index + 1 else 0
            }
        }
        currentSong = songs.getOrNull(index)
        saveCursor()
        return true
    }

    fun onPlaybackEnded() {
        if (selections.hasPending || TogetherSession.memberPlayback.value.generation != null) return
        reconcileTransition()
        val playing = PlaybackService.instance?.playingTrackKey()
        if (playing != null && songs.isNotEmpty()) {
            val mapped = indexOf(playing)
            if (mapped < 0) return
            if (mapped != index) {
                index = mapped
                if (mode == QueueModes.SHUFFLE) alignShuffle(usePendingRound = false)
            }
        }
        if (mode == QueueModes.SINGLE && playing != null) {
            player.replayCurrent()
            return
        }
        playNext(PlayOrigin.AUTO_NEXT)
    }

    fun canSkipUnplayable(roomGeneration: Long?): Boolean =
        roomGeneration == null && TogetherSession.memberPlayback.value.generation == null &&
            songs.isNotEmpty() && mode != QueueModes.SINGLE

    fun onUnplayable() {
        playNext(PlayOrigin.AUTO_NEXT)
    }

    fun skip(forward: Boolean, defer: Boolean = false) {
        reconcileTransition()
        if (forward) playNext(PlayOrigin.USER, defer)
        else playPrevious(defer)
    }

    private fun advanceShuffle(): Int {
        if (shuffleOrder.isEmpty() || shufflePosition >= shuffleOrder.lastIndex) {
            shuffleOrder = pendingRound ?: newRound()
            shufflePosition = if (shuffleOrder.size > 1) 1 else 0
            pendingRound = null
        } else {
            shufflePosition++
        }
        return shuffleOrder.getOrElse(shufflePosition) { 0 }
    }

    private fun playNext(origin: PlayOrigin, defer: Boolean = false) {
        if (songs.isEmpty()) return
        when (mode) {
            QueueModes.SINGLE -> if (origin != PlayOrigin.AUTO_NEXT) {
                manualQueueIndex(songs.size, index, 1)?.let { playAt(it, origin, defer = defer) }
            }
            QueueModes.SHUFFLE -> playAt(advanceShuffle(), origin, defer = defer)
            QueueModes.LINE -> if (index < songs.lastIndex) playAt(index + 1, origin, defer = defer)
                else {
                    index = 0
                    currentSong = songs.first()
                    saveCursor()
                    player.prepareSongWithoutPlay(songs.first())
                }
            QueueModes.INFINITY -> if (index < songs.lastIndex) playAt(index + 1, origin, defer = defer)
                else extendQueue(origin)
            else -> playAt(if (index < songs.lastIndex) index + 1 else 0, origin, defer = defer)
        }
    }

    private fun playPrevious(defer: Boolean) {
        if (songs.isEmpty()) return
        val target = when (mode) {
            QueueModes.SINGLE -> manualQueueIndex(songs.size, index, -1)
            QueueModes.SHUFFLE -> ShuffleNav.previousPosition(shufflePosition, shuffleOrder.size)
                ?.let { shuffleOrder[it] }
            else -> if (index > 0) index - 1 else songs.lastIndex
        }
        if (target != null) playAt(target, defer = defer)
    }

    fun preloadOrExtendQueue() {
        if (TogetherSession.memberPlayback.value.generation != null) {
            preloadCurrentSuccessor()
            return
        }
        if (!preloadItem(preloadIndex()) && mode == QueueModes.INFINITY && index == songs.lastIndex) {
            extendQueue(startOrigin = null)
        }
    }

    private fun isCurrent(request: Extension): Boolean =
        songs === request.songs && mode == QueueModes.INFINITY && fmMode == request.fm &&
            playRequestGeneration.isCurrent(request.epoch) && songs.getOrNull(index) === request.seed

    private fun extendQueue(startOrigin: PlayOrigin?) {
        val seed = songs.getOrNull(index) ?: return
        extension?.takeIf { isCurrent(it) && extensionJob?.isActive == true }?.let {
            if (startOrigin != null) it.startOrigin = startOrigin
            return
        }
        val request = Extension(songs, seed, fmMode, playRequestGeneration.current, startOrigin)
        extensionJob?.cancel()
        extension = request
        extensionJob = scope.launch {
            try {
                val continuation = withContext(Dispatchers.IO) {
                    val existing = QueueKeys.keySetOf(request.songs)
                    val primary = loadContinuation {
                        if (request.fm) PlaylistApi.getPersonalFm() else PlaylistApi.getSimilarSongs(seed.id)
                    }.filter { QueueKeys.keyOf(it) !in existing }
                    primary.ifEmpty {
                        loadContinuation { PlaylistApi.getDailyRecommendSongs() }
                            .filter { QueueKeys.keyOf(it) !in existing }
                    }
                }
                if (continuation.isEmpty() || !isCurrent(request) ||
                    TogetherSession.memberPlayback.value.generation != null
                ) return@launch
                val start = songs.size
                player.invalidatePreload()
                songs = songs + continuation
                val origin = request.startOrigin
                if (origin != null) playAt(start, origin)
                else {
                    saveQueue(replan = false)
                    preloadItem(start, allowCurrent = false)
                }
            } finally {
                if (extension === request) extension = null
            }
        }
    }

    private suspend fun loadContinuation(load: suspend () -> List<SongItem>): List<SongItem> = try {
        load()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d("PlaybackQueue", "Could not extend queue", e)
        emptyList()
    }
}

internal fun songParams(song: SongItem) = Triple(
    song.name,
    song.artists?.joinToString("/") { it.name }.orEmpty(),
    song.album?.picUrl.orEmpty(),
)
