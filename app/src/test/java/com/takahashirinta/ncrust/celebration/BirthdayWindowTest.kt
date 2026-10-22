package com.takahashirinta.ncrust.celebration

import com.takahashirinta.ncrust.ui.i18n.celebrationFor
import org.junit.Assert.*
import org.junit.Test

class BirthdayWindowTest {
    private fun find(month: Int, day: Int, year: Int, nowMonth: Int, nowDay: Int,
        shown: Set<String> = emptySet(), opened: Set<String> = emptySet(), enabled: Boolean = true) =
        BirthdayWindow.find(month, day, enabled, year, nowMonth, nowDay, shown, opened)

    @Test fun birthdayUsesNormalAnimationWithoutLateIntro() {
        assertEquals(BirthdayOccasion(2026, 0), find(10, 22, 2026, 10, 22))
    }
    @Test fun missedBirthdayCanBeObservedOnEveryDayOfTheSevenDayWindow() {
        for (late in 1..7) assertEquals(BirthdayOccasion(2026, late), find(10, 22, 2026, 10, 22 + late))
    }
    @Test fun outsideTheWindowAndBeforeBirthdayDoNotTrigger() {
        assertNull(find(10, 22, 2026, 10, 21)); assertNull(find(10, 22, 2026, 10, 30))
    }
    @Test fun newYearStillConsumesTheBirthdayYearMarker() {
        assertEquals(BirthdayOccasion(2025, 7), find(12, 31, 2026, 1, 7))
        assertNull(find(12, 31, 2026, 1, 8))
        assertNull(find(12, 31, 2026, 1, 3, shown = setOf("2025")))
        assertEquals(BirthdayOccasion(2025, 3), find(12, 31, 2026, 1, 3, shown = setOf("2026")))
    }
    @Test fun anAlreadyDisplayedBirthdayDoesNotRepeat() {
        assertNull(find(10, 22, 2026, 10, 22, shown = setOf("2026")))
        assertNull(find(10, 22, 2026, 10, 26, shown = setOf("2026")))
        assertNotNull(find(10, 22, 2027, 10, 26, shown = setOf("2026")))
    }
    @Test fun openingOnBirthdaySuppressesOnlyTheLateWindow() {
        assertNull(find(10, 22, 2026, 10, 23, opened = setOf("2026")))
        assertNotNull(find(10, 22, 2026, 10, 22, opened = setOf("2026")))
    }
    @Test fun february29UsesTheExistingObservedDatePolicy() {
        assertEquals(BirthdayOccasion(2026, 7), find(2, 29, 2026, 3, 7))
        assertNull(find(2, 29, 2026, 3, 8))
        assertEquals(BirthdayOccasion(2028, 1), find(2, 29, 2028, 3, 1))
        assertNull(find(2, 29, 2028, 2, 28))
    }
    @Test fun disabledOrInvalidDatesDoNotTrigger() {
        assertNull(find(10, 22, 2026, 10, 23, enabled = false))
        assertNull(find(4, 31, 2026, 5, 1))
        assertNull(find(10, 22, 2026, 13, 1))
    }
    @Test fun localizedLateMessagesIncludeTheActualDayCount() {
        for (code in listOf("zh-CN", "zh-TW", "ja-JP", "ja-MY", "ko-KP", "de-DE", "ru-RU", "en-US")) {
            val s = celebrationFor(code)
            assertTrue(s.lateMessage(7).contains("7"))
            assertFalse(s.lateMessage(7).contains("%d"))
            assertTrue(s.lateTitle.isNotBlank() && s.lateContinue.isNotBlank())
        }
    }
}
