package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.playlist.PlaylistDegradation
import com.takahashirinta.ncrust.playlist.PlaylistHardFailure

/** Preserve failure meaning all the way to both cached and uncached repository paths. */
internal fun playlistAuthFailure(result: QqPlaylistResult<*>): Pair<PlaylistDegradation, PlaylistHardFailure>? = when(result) {
    is QqPlaylistResult.Ok -> null
    QqPlaylistResult.NeedLogin -> PlaylistDegradation.NEED_LOGIN to PlaylistHardFailure.NeedLogin
    QqPlaylistResult.Network -> PlaylistDegradation.OFFLINE to PlaylistHardFailure.Network
    QqPlaylistResult.NotFound -> PlaylistDegradation.OFFLINE to PlaylistHardFailure.NotFound
    is QqPlaylistResult.Failure -> PlaylistDegradation.OFFLINE to PlaylistHardFailure.Other(result.code)
}
