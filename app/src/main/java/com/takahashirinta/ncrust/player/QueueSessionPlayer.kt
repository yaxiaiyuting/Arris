/* Copyright (c) 2026 yaxiaiyuting. Arris fork modification, GPL-3.0. */
package com.takahashirinta.ncrust.player

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Authorisation callbacks do not consume Player commands. Route navigation at execution time. */
@android.annotation.SuppressLint("UnsafeOptInUsageError")
internal class QueueSessionPlayer(
    player: Player,
    private val nextAction: () -> Unit,
    private val previousAction: () -> Unit,
) : ForwardingSimpleBasePlayer(player) {
    override fun getState(): State {
        val state = super.getState()
        return state.buildUpon().setAvailableCommands(state.availableCommands.buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()).build()
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> =
        when (MediaSessionMerge.route(seekCommand)) {
            MediaSessionMerge.Route.APP_NEXT -> { nextAction(); Futures.immediateVoidFuture() }
            MediaSessionMerge.Route.APP_PREVIOUS -> { previousAction(); Futures.immediateVoidFuture() }
            MediaSessionMerge.Route.MEDIA3_DEFAULT -> super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
}
