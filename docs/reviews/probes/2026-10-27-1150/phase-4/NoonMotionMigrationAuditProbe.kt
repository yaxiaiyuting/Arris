package com.takahashirinta.ncrust.audit

import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import com.takahashirinta.ncrust.ui.player.motion.MotionIntensity
import com.takahashirinta.ncrust.ui.player.motion.MotionPrefs
import org.junit.Assert.*
import org.junit.Test

class NoonMotionMigrationAuditProbe {
    @Test fun upgradingAnAlreadyMigratedPreferenceReappliesAnObsoleteShowcaseChoice() {
        val prefs=MemoryPrefs().apply {
            putRaw(MotionPrefs.KEY_VERSION,MotionPrefs.VERSION_V300)
            putRaw(MotionPrefs.KEY_TIER,MotionIntensity.SIMPLE)
            putRaw(MotionPrefs.LegacyKeys.KEY_TIER,MotionIntensity.SHOWCASE)
            putRaw(MotionPrefs.LegacyKeys.KEY_SHOWCASE,true)
        }
        assertEquals(MotionIntensity.SIMPLE,MotionPrefs.readTier(prefs,MotionIntensity.REFINED))
        assertTrue(MotionPrefs.migrate(prefs,MotionIntensity.REFINED))
        assertEquals(MotionIntensity.SHOWCASE,MotionPrefs.readTier(prefs,MotionIntensity.REFINED))
        assertEquals(MotionPrefs.CURRENT_VERSION,prefs.getInt(MotionPrefs.KEY_VERSION,-1))
        println("AUDIT migration: a version-4 preference already migrated to motion_tier and later explicitly set to SIMPLE is upgraded to version 5 by reapplying preserved legacy visualizer SHOWCASE; the user's current setting is overwritten")
    }
}
