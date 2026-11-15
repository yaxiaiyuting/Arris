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
            val idleState = if (nm.canPostPromotedNotifications()) LiveUpdateStatus.IDLE
                else LiveUpdateStatus.PROMOTION_DISABLED
            assertEquals(idleState, LiveUpdateNotifier.status.value)
            LiveUpdateNotifier.post(app, kind, old, "Old fixture", "Loading", 12)
            LiveUpdateNotifier.post(app, kind, current, "Current fixture", "Loading", 23)
            LiveUpdateNotifier.cancel(app, kind, old)
            if (!nm.canPostPromotedNotifications()) {
                assertFalse(nm.activeNotifications.any { it.id == kind.id })
                assertEquals(LiveUpdateStatus.PROMOTION_DISABLED, LiveUpdateNotifier.status.value)
                return
            }
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
            val posted = requireNotNull(notification)
            assertTrue(posted.extras.getBoolean(LiveUpdateNotifier.REQUEST_PROMOTION))
            assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(posted.channelId).importance)
            val promoted = posted.flags and Notification.FLAG_PROMOTED_ONGOING != 0
            val state = if (promoted) LiveUpdateStatus.PROMOTED else LiveUpdateStatus.ACTIVE_NOT_PROMOTED
            await { LiveUpdateNotifier.status.value == state }
            println("Live Update fixture: promotion allowed=true, promoted=$promoted")
        } finally {
            LiveUpdateNotifier.cancel(app, kind, current)
        }
        await { LiveUpdateNotifier.status.value == LiveUpdateStatus.IDLE }
    }

    @Test
    fun returningFromSettingsPublishesTheLatestActiveTaskWithoutAnotherProgressEvent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)!!
        org.junit.Assume.assumeTrue(manager.canPostPromotedNotifications())
        val prefs = app.getSharedPreferences("ncrust_settings", 0)
        val hadPref = prefs.contains("live_update_enabled")
        val previous = prefs.getBoolean("live_update_enabled", true)
        val waiting = Any()
        val ended = Any()
        val room = LiveUpdateKind.ROOM
        val recognition = LiveUpdateKind.RECOGNITION
        try {
            prefs.edit().putBoolean("live_update_enabled", false).apply()
            LiveUpdateNotifier.post(app, room, waiting, "Room fixture", "Connecting")
            LiveUpdateNotifier.post(app, room, waiting, "Room fixture", "Waiting for a friend")
            LiveUpdateNotifier.post(app, recognition, ended, "Finished fixture", "Matching")
            LiveUpdateNotifier.cancel(app, recognition, ended)
            LiveUpdateNotifier.onForeground(app)
            assertEquals(LiveUpdateStatus.APP_DISABLED, LiveUpdateNotifier.status.value)
            assertFalse(manager.activeNotifications.any { it.id == room.id })

            prefs.edit().putBoolean("live_update_enabled", true).apply()
            LiveUpdateNotifier.onForeground(app)
            await {
                manager.activeNotifications.any {
                    it.id == room.id &&
                        it.notification.extras.getCharSequence(Notification.EXTRA_TEXT) == "Waiting for a friend"
                }
            }
            assertFalse(manager.activeNotifications.any { it.id == recognition.id })
        } finally {
            LiveUpdateNotifier.cancel(app, room, waiting)
            LiveUpdateNotifier.cancel(app, recognition, ended)
            prefs.edit().apply {
                if (hadPref) putBoolean("live_update_enabled", previous) else remove("live_update_enabled")
            }.apply()
        }
        await { manager.activeNotifications.none { it.id == room.id } }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        while (!condition()) {
            assertTrue("Timed out waiting for notification state", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(30L)
        }
    }
}
