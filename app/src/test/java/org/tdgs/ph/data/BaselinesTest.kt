package org.tdgs.ph.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BaselinesTest {
    @Test
    fun comparesLastWeekWithTheFourWeeksBefore() {
        val base = (1..28).map { it.toDouble() }
        val recent = listOf(40.0, 41.0, 42.0, null, 43.0, 44.0, 45.0)
        val b = Baselines.compute(base + recent)
        assertEquals(42.5, b.recent)
        assertEquals(14.5, b.base)
        assertEquals(6, b.recentDays)
        assertEquals(28, b.baseDays)
        assertTrue(b.outside)
        assertEquals(Tone.UP, Baselines.tone(b, good = 1))
        assertEquals(Tone.DOWN, Baselines.tone(b, good = -1))
        assertEquals(Tone.FLAT, Baselines.tone(b, good = 0))
    }

    @Test
    fun missingDaysAreSkippedAndShortHistoryHasNoBaseline() {
        val b = Baselines.compute(listOf(null, 5.0, null, 6.0, 7.0, 8.0))
        assertNull(b.base, "fewer than 7 earlier days cannot form a usual range")
        assertNull(b.diff)
        assertEquals(Tone.NONE, Baselines.tone(b, good = 1))
    }

    @Test
    fun withinTheUsualRangeIsNotColoured() {
        val b = Baselines.compute(List(28) { (it % 10).toDouble() } + List(7) { 5.0 })
        assertFalse(b.outside)
        assertEquals(Tone.FLAT, Baselines.tone(b, good = 1))
    }
}
