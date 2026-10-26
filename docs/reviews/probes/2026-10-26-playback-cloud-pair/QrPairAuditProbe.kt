package com.takahashirinta.ncrust.auth

import org.junit.Assert.*
import org.junit.Test

/** Pure protocol fixture; no UDP broadcasts or TCP connections, and no real credentials. */
class QrPairAuditProbe {
    @Test fun publicDiscoveryTokenIsAlsoTheCookieEncryptionKeyMaterial() {
        val scannedKey = "fixture-screen-key"
        val discoveryPacket = QrPair.requestPayload(scannedKey)
        val observedKey = QrPair.parseRequest(discoveryPacket)!!
        assertEquals(scannedKey, observedKey)
        val forgedAnswer = QrPair.parseResponse(QrPair.responsePayload(observedKey, 23456))!!
        // These are precisely the token/port acceptance conditions in QrPairClient.discover.
        assertEquals(scannedKey, forgedAnswer.first)
        assertEquals(23456, forgedAnswer.second)
        val credential = "MUSIC_U=fixture-ticket; __csrf=fixture-csrf"
        val wire = QrPair.encrypt(scannedKey, credential)
        assertEquals(credential, QrPair.decrypt(observedKey, wire))
        println("AUDIT QR pairing: clear discovery exposes all key material; a forged same-token discovery response passes client token check and can decrypt cookie payload")
    }
}
