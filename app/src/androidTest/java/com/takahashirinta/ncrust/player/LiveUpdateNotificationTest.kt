/* Arris · Copyright (c) 2026 yaxiaiyuting, GPLv3. */
package com.takahashirinta.ncrust.player

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 36)
class LiveUpdateNotificationTest {
    @Test
    fun settingsReadsTheSystemFlagAndFinishingAnOldTaskDoesNotCancelTheCurrentOne() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val nm = app.getSystemService(NotificationManager::class.java)!!
        val kind = LiveUpdateKind.DOWNLOAD
        val old = Any()
        val current = Any()
        try {
            LiveUpdateNotifier.refreshPromotion(app)
            assertEquals(LiveUpdatePromotion.NOT_PROMOTED, LiveUpdateNotifier.promotion.value)
            LiveUpdateNotifier.post(app, kind, old, "Old fixture", "Loading", 12)
            LiveUpdateNotifier.post(app, kind, current, "Current fixture", "Loading", 23)
            LiveUpdateNotifier.cancel(app, kind, old)
            // Give NotificationManager time to apply and return the real system flag.
            val timeout = SystemClock.elapsedRealtime() + 3_000L
            var notification: Notification? = null
            while (SystemClock.elapsedRealtime() < timeout) {
                notification = nm.activeNotifications.firstOrNull {
                    it.id == kind.id && it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == "Current fixture"
                }?.notification
                if (notification != null) break
                SystemClock.sleep(50L)
            }
            if (!nm.canPostPromotedNotifications()) {
                assertFalse(nm.activeNotifications.any { it.id == kind.id })
                assertEquals(LiveUpdatePromotion.NOT_PROMOTED, LiveUpdateNotifier.promotion.value)
                return
            }
            val posted = requireNotNull(notification)
            assertTrue(posted.extras.getBoolean(LiveUpdateNotifier.REQUEST_PROMOTION))
            assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(posted.channelId).importance)
            val promoted = posted.flags and Notification.FLAG_PROMOTED_ONGOING != 0
            val state = if (promoted) LiveUpdatePromotion.PROMOTED else LiveUpdatePromotion.NOT_PROMOTED
            val readbackDeadline = SystemClock.elapsedRealtime() + 1_500L
            while (LiveUpdateNotifier.promotion.value != state && SystemClock.elapsedRealtime() < readbackDeadline) {
                SystemClock.sleep(30L)
            }
            assertEquals(state, LiveUpdateNotifier.promotion.value)
            println("Live Update fixture: promotion allowed=true, promoted=$promoted")
        } finally {
            LiveUpdateNotifier.cancel(app, kind, current)
        }
    }
}
