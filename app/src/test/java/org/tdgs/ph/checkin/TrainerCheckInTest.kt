package org.tdgs.ph.checkin

import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainerCheckInTest {
    private val noon = ZonedDateTime.parse("2026-11-21T12:22:00+01:00[Europe/Copenhagen]")
    private val enabled = CheckInSettings(enabled = true)

    @Test
    fun slotsExpireAndEachCanBeSentOnlyOnce() {
        assertNull(dueCheckIn(noon, CheckInSettings()))
        assertEquals(720, dueCheckIn(noon, enabled))
        assertNull(dueCheckIn(noon, enabled.copy(sentSlots = setOf(720))))
        assertNull(dueCheckIn(noon.plusHours(2), enabled))
        assertEquals(1080, dueCheckIn(noon.plusHours(6), enabled.copy(sentSlots = setOf(360, 720))))
        assertNull(dueCheckIn(noon, enabled.copy(times = setOf(720), sentSlots = setOf(360))))
    }

    @Test
    fun missingAndEarlyDayStepsAreNeverBehindPace() {
        val morning = CheckInProgress(0, 8000, 80.0, 150.0, 360)
        assertFalse(morning.moveBehind)
        assertTrue(morning.copy(minuteOfDay = 720).moveBehind)
        assertFalse(morning.copy(steps = null, minuteOfDay = 1080).moveBehind)
        assertFalse(morning.copy(steps = 8000, minuteOfDay = 1080).moveBehind)
        assertTrue(morning.copy(steps = 8000, weeklyMinutes = 150.0).fallback().contains("Both goals are met"))
    }
}
