package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.playlist.*
import org.junit.Assert.*
import org.junit.Test

class QqPlaylistAuthTest {
    @Test fun networkFailurePreservesOfflineMeaningForListAndDetail() {
        assertEquals(PlaylistDegradation.OFFLINE to PlaylistHardFailure.Network,playlistAuthFailure(QqPlaylistResult.Network))
    }
    @Test fun unknownBusinessFailureNeverBecomesNeedLogin() {
        val result=playlistAuthFailure(QqPlaylistResult.Failure(104604))!!
        assertEquals(PlaylistDegradation.OFFLINE,result.first);assertEquals(PlaylistHardFailure.Other(104604),result.second)
    }
    @Test fun onlyExplicitAuthRejectionUsesLoginDegradation() {
        assertEquals(PlaylistDegradation.NEED_LOGIN to PlaylistHardFailure.NeedLogin,playlistAuthFailure(QqPlaylistResult.NeedLogin))
    }
    @Test fun successContinuesLoadingInsteadOfReturningCacheDegradation() {
        assertNull(playlistAuthFailure(QqPlaylistResult.Ok(Unit)))
    }
}
