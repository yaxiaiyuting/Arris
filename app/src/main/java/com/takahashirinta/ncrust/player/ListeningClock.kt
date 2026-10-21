package com.takahashirinta.ncrust.player

/** Actual isPlaying elapsed time; positions and seeks are deliberately not inputs. */
internal class ListeningClock(private val now: () -> Long) {
    var songId: Long? = null
        private set
    private var playing = false
    private var anchor = now()
    private var listened = 0L
    private var pendingRestart: Long? = null

    /** Consume a user replay once, regardless of which service start callback arrives first. */
    fun restartOnNextBegin(id: Long) {
        pendingRestart = id
    }

    fun begin(id: Long, restart: Boolean = false): Boolean {
        settle()
        val requestedRestart = pendingRestart == id
        if (requestedRestart) pendingRestart = null
        if (id == songId && !restart && !requestedRestart) return false
        songId = id
        listened = 0L
        return true
    }

    fun setPlaying(value: Boolean) {
        settle()
        playing = value
    }

    fun playedMs(id: Long): Long {
        settle()
        return if (songId == id) listened else 0L
    }

    private fun settle() {
        val time = now()
        if (playing && songId != null && time >= anchor) listened += time - anchor
        anchor = time
    }
}
