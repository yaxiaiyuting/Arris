package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.ui.navigation.NavRoutes
import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

/** Codec seam only. Navigation's first Uri.decode is independently confirmed in its 2.8.5 bytecode. */
class ScheduledNavigationAuditProbe {
    @Test fun literalPercentNameFailsAfterNavigationsPathDecoding() {
        val route=NavRoutes.qqPlaylistDetail("55","10001",201,"100% Love")
        val afterFirstDecode=URI("https://fixture.invalid/$route").path.substringAfterLast('/')
        assertEquals("100%+Love",afterFirstDecode)
        val outcome=runCatching { URLDecoder.decode(afterFirstDecode,"UTF-8") }
        assertTrue(outcome.exceptionOrNull() is IllegalArgumentException)
        println("AUDIT navigation codec: 100% Love becomes 100%+Love in the argument; the page's second URLDecoder throws")
    }
    @Test fun literalPlusInAnArtistNameBecomesASpace() {
        val route=NavRoutes.artist(MusicSource.QQMUSIC,"mid","A+B")
        val afterFirstDecode=URI("https://fixture.invalid/$route").path.substringAfterLast('/')
        assertEquals("A+B",afterFirstDecode)
        assertEquals("A B",URLDecoder.decode(afterFirstDecode,"UTF-8"))
        println("AUDIT navigation codec: A+B is silently rewritten to A B by the second decoder")
    }
}
