package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.auth.NeteaseCookie
import com.takahashirinta.ncrust.auth.QrPair
import org.junit.Assert.*
import org.junit.Test

class ScheduledPairIntegrityAuditProbe {
    @Test fun changingAnIvByteChangesTheCookieWithoutAnyIntegrityRejection() {
        val secret=QrPair.newSecret()
        val original="MUSIC_U=fixture-session; __csrf=fixture-csrf"
        val wire=QrPair.encrypt(secret,"fixture-key",original)
        // A receiver observes this modification without the modifier knowing the secret.
        // Offset 8 is the first value character after the stable ASCII MUSIC_U= prefix.
        wire[8]=(wire[8].toInt() xor 1).toByte()
        val accepted=QrPair.decrypt(secret,"fixture-key",wire)
        assertNotNull(accepted)
        assertNotEquals(original,accepted)
        assertTrue(NeteaseCookie.isUsable(accepted))
        assertEquals("MUSIC_U=gixture-session; __csrf=fixture-csrf",accepted)
        println("AUDIT pair payload integrity: one IV bit flips a session-token character; decrypt still accepts a usable-shaped cookie. This is not a forged valid cloud credential or a real LAN attack")
    }
}
