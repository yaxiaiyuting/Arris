package com.takahashirinta.ncrust.celebration

import java.util.GregorianCalendar
import java.util.TimeZone

internal data class BirthdayOccasion(val birthdayYear: Int, val daysLate: Int)

/** Local calendar dates are supplied by the caller; UTC is only used for DST-free date math. */
internal object BirthdayWindow {
    fun find(month: Int, day: Int, enabled: Boolean, year: Int, todayMonth: Int, todayDay: Int,
        shown: Set<String>, openedOnBirthday: Set<String> = emptySet()): BirthdayOccasion? {
        if (!enabled || !BirthdayRule.valid(month, day)) return null
        val today = ordinal(year, todayMonth, todayDay) ?: return null
        for (birthdayYear in listOf(year, year - 1)) {
            val leap = birthdayYear % 4 == 0 && (birthdayYear % 100 != 0 || birthdayYear % 400 == 0)
            val observed = if (month == 2 && day == 29 && !leap) 28 else day
            val birthday = ordinal(birthdayYear, month, observed) ?: continue
            val late = today - birthday
            val key = birthdayYear.toString()
            if (late in 0L..7L && key !in shown && (late == 0L || key !in openedOnBirthday)) {
                return BirthdayOccasion(birthdayYear, late.toInt())
            }
        }
        return null
    }

    private fun ordinal(year: Int, month: Int, day: Int): Long? = runCatching {
        require(year in 1..9999)
        GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear(); isLenient = false; set(year, month - 1, day)
        }.timeInMillis / 86_400_000L
    }.getOrNull()
}
