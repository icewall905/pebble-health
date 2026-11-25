package org.tdgs.ph.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSessionTest {
    private fun pt(ts: Long, m: Double?) = IntradayPoint(ts, null, null, null, null, m)

    @Test fun distanceSplitsAndPaceFromMinutes() {
        // 100 m per minute for 25 minutes: 2.5 km, two 10-minute splits.
        val points = (0 until 25).map { pt(1000L + it * 60, 100.0) } + pt(1000L + 30 * 60, 500.0)
        val s = watchSessionStats(points, 1000L, 1000L + 25 * 60)
        assertEquals(2500.0, s.distanceM!!, 1e-6)
        assertEquals(listOf(600.0, 600.0), s.splits)
        assertEquals(600.0, s.pace.first().second, 1e-6)
    }

    @Test fun noDistanceMeansNoStats() {
        val s = watchSessionStats(listOf(pt(0, null), pt(60, 0.0)), 0, 120)
        assertEquals(null, s.distanceM)
        assertTrue(s.splits.isEmpty())
    }

    @Test fun overlapIsStrict() {
        assertTrue(overlapsAny(10, 20, listOf(15L to 30L)))
        assertFalse(overlapsAny(10, 20, listOf(20L to 30L)))
    }
}
