package org.tdgs.ph.workout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveStatsTest {
    private fun fix(time: Long, meters: Double, segment: Int = 0) =
        Fix(time, time, segment, 55.0, 12.0, null, 5f, meters)

    @Test
    fun usesRecentDistanceAndActiveTime() {
        assertEquals(300_000L, recentPace(listOf(fix(0, 0.0), fix(30_000, 100.0), fix(45_000, 150.0)), 45_000))
    }

    @Test
    fun hidesPaceForStaleOrSparseSamplesAndAcrossSegments() {
        val points = listOf(fix(0, 0.0), fix(10_000, 40.0))
        assertNull(recentPace(points, 17_001))
        assertNull(recentPace(points + fix(12_000, 40.0, segment = 1), 12_000))
        assertNull(recentPace(listOf(fix(0, 0.0), fix(3000, 20.0)), 3000))
    }
}
