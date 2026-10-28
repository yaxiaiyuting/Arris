package com.takahashirinta.ncrust.ui.theme

import org.junit.Assert.*
import org.junit.Test

class BackgroundImportGateTest {
    @Test fun clearRejectsTheOldImportAndALaterSelectionCanCommit() {
        val gate=BackgroundImportGate();var file:String?="previous"
        val old=gate.begin();gate.invalidate {file=null}
        assertFalse(gate.commit(old) {file="old"});assertNull(file)
        assertTrue(gate.commit(gate.begin()) {file="new"});assertEquals("new",file)
    }
    @Test fun onlyTheMostRecentlySelectedImageCanPublish() {
        val gate=BackgroundImportGate();val first=gate.begin();val second=gate.begin();var file="previous"
        assertTrue(gate.commit(second) {file="second"})
        assertFalse(gate.commit(first) {file="first"});assertEquals("second",file)
    }
}
