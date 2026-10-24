package com.takahashirinta.ncrust.settings

import com.takahashirinta.ncrust.cache.OfflineAudioCache
import com.takahashirinta.ncrust.ui.settings.SettingsRegistry
import org.junit.Assert.*
import org.junit.Test

class OfflineCacheBudgetTest {
    @Test fun `settings default and both selectors share the enlarged runtime budget`() {
        assertEquals(1024L * 1024 * 1024, OfflineAudioCache.DEFAULT_MAX_BYTES)
        val entry = SettingsRegistry.allEntries().first { it.key == "offline_cache_mb" }
        assertEquals(1024, entry.defaultValue)
        assertTrue(SettingsRegistry.OFFLINE_CACHE_MB_CHOICES.containsAll(listOf(512, 1024, 8192, 12288, 16384)))
        assertEquals(OfflineAudioCache.MAX_MB, SettingsRegistry.OFFLINE_CACHE_MB_CHOICES.last())
    }
}
