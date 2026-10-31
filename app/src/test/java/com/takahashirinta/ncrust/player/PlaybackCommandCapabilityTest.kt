package com.takahashirinta.ncrust.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackCommandCapabilityTest {
    @Test fun callersWithoutTheCurrentProcessCapabilityCannotSubmitCommands() {
        val current=PlaybackCommandCapability();val oldProcess=PlaybackCommandCapability()
        assertTrue(current.accepts(current.value))
        assertFalse(current.accepts(null));assertFalse(current.accepts(""))
        assertFalse(current.accepts(oldProcess.value));assertFalse(current.accepts("action=next"))
    }
}
