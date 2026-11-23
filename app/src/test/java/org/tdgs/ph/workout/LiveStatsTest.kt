package org.tdgs.ph.workout

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveStatsTest {
    @Test
    fun strengthPacketFitsOlderWatchInboxAndKeepsUtf8Intact() {
        val state = LiveWorkout(Workout("strength", Sport.strength, 0, 0, 0),
            exercise = "Bænkpres 💪".repeat(5), sets = 3, restSeconds = 60)
        val data = workoutWatchData(state)
        assertEquals(1u, data[8u]?.value)
        assertEquals(3u, data[9u]?.value)
        assertEquals(60u, data[10u]?.value)
        val title = data[11u]?.value as String
        assertTrue(state.exercise.startsWith(title))
        assertFalse(title.last().isHighSurrogate())
        assertTrue(2 + data.values.sumOf { 7 + it.size } <= 128)
        assertEquals(0u, workoutWatchData(state, finished = true)[10u]?.value)
    }

    @Test
    fun watchCanDistinguishAnActiveSavedOrDiscardedRecording() {
        val state = LiveWorkout(Workout("run", Sport.run, 0, 0, 0), gpsStatus = "GPS recording")
        assertEquals(state.watchToken, workoutWatchData(state)[12u]?.value)
        assertEquals(0u, workoutWatchData(state)[4u]?.value)
        assertEquals(2u, workoutWatchData(state, finished = true)[4u]?.value)
        assertEquals(3u, workoutWatchData(state, finished = true, discarded = true)[4u]?.value)
    }

    @Test
    fun watchHistoryUsesFixedLittleEndianRowsWithinInboxBudget() {
        val run = WatchHistoryEntry(Sport.run, 1_795_400_000, 900, 2410.0)
        val walk = WatchHistoryEntry(Sport.walk, 1_795_390_000, 120, null)
        val packed = watchHistoryBytes(listOf(run, walk) + List(9) { walk })
        assertEquals(104, packed.size)
        val buffer = java.nio.ByteBuffer.wrap(packed).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, buffer.get().toInt())
        assertEquals(run.start.toInt(), buffer.int)
        assertEquals(run.duration, buffer.int)
        assertEquals(2410, buffer.int)
        assertEquals(1, buffer.get().toInt())
        assertEquals(walk.start.toInt(), buffer.int)
        assertEquals(walk.duration, buffer.int)
        assertEquals(-1, buffer.int)
    }

    @Test
    fun wristFillsMissingStrapSamplesUntilStrapReturns() {
        val strap = TimedHeart(146, 1000)
        val wrist = TimedHeart(135, 7000, fromWatch = true)
        assertEquals(strap, preferredHeart(6000, strap, null))
        assertEquals(wrist, preferredHeart(7000, strap, wrist))
        val resumed = TimedHeart(141, 7100)
        assertEquals(resumed, preferredHeart(7100, resumed, wrist))
        assertNull(preferredHeart(19001, strap, wrist))
    }

    @Test
    fun wristSamplingFlagKeepsSessionTokenAndStopsOnFinish() {
        val state = LiveWorkout(Workout("run", Sport.run, 0, 0, 0), requestWatchHeart = true)
        assertEquals(state.watchToken or WATCH_HEART_REQUEST, workoutWatchData(state)[12u]?.value)
        assertEquals(state.watchToken, workoutWatchData(state, finished = true)[12u]?.value)
        assertEquals(state.watchToken, workoutWatchData(state.copy(paused = true))[12u]?.value)
    }

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
