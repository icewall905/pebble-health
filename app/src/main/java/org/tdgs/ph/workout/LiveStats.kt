package org.tdgs.ph.workout

import android.content.Context
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.TransmissionResult
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.math.roundToInt

/** Use recent movement within one GPS segment. A stale or sparse fix yields no live pace. */
internal fun recentPace(route: List<Fix>, active: Long): Long? {
    val last = route.lastOrNull() ?: return null
    if (active - last.active > 7000) return null
    val first = route.asReversed().takeWhile {
        it.segment == last.segment && last.active - it.active <= 20_000
    }.last()
    val elapsed = last.active - first.active
    val meters = last.meters - first.meters
    return if (elapsed >= 5000 && meters >= 10) (elapsed * 1000 / meters).toLong() else null
}

internal fun workoutWatchData(state: LiveWorkout, finished: Boolean = false): Map<UInt, PebbleDictionaryItem> {
    val values = listOf(state.active.div(1000).toInt(), state.distance.roundToInt(),
        state.livePace?.div(1000)?.toInt() ?: 0, state.bpm ?: 0,
        if (finished) 2 else if (state.paused) 1 else 0,
        state.heartZone?.plus(1) ?: 0, state.strapBattery?.plus(1) ?: 0, state.maxBpm)
    val mode = when {
        state.workout.sport == Sport.strength -> 1
        state.gpsStatus.isEmpty() -> 3
        state.workout.sport == Sport.cycle -> 2
        else -> 0
    }
    return buildMap<UInt, PebbleDictionaryItem> {
        values.forEachIndexed { key, value ->
            if ((mode != 1 && mode != 3) || (key != 1 && key != 2 && key != 6)) {
                put(key.toUInt(), PebbleDictionaryItem.UInt32(value.toUInt()))
            }
        }
        put(8u, PebbleDictionaryItem.UInt32(mode.toUInt()))
        if (mode == 1 || mode == 3) {
            put(9u, PebbleDictionaryItem.UInt32((if (mode == 1) state.sets else state.laps).toUInt()))
            val rest = if (finished) 0 else if (state.restComplete) 601 else state.restSeconds
            put(10u, PebbleDictionaryItem.UInt32(rest.toUInt()))
            val title = if (mode == 1) state.exercise else state.workout.sport.label
            // Keep the complete dictionary within the 128-byte inbox of installed watch apps.
            val watchTitle = title.take(32).dropLastWhile { it.isHighSurrogate() }
            val bounded = watchTitle.indices.lastOrNull { index ->
                !watchTitle[index].isHighSurrogate() && watchTitle.substring(0, index + 1).encodeToByteArray().size <= 30
            }?.let { watchTitle.substring(0, it + 1) }.orEmpty()
            put(11u, PebbleDictionaryItem.Text(bounded))
        }
    }
}

internal class WorkoutWatch(context: Context) : AutoCloseable {
    private val sender = DefaultPebbleSender(context.applicationContext)

    init {
        DefaultPebbleAndroidAppPicker.getInstance(context.applicationContext).enableAutoSelect = false
    }

    suspend fun launch() {
        withTimeoutOrNull(5000) { sender.startAppOnTheWatch(WATCH_UUID) }
    }

    suspend fun update(state: LiveWorkout, finished: Boolean = false): String {
        val data = workoutWatchData(state, finished)
        val results = withTimeoutOrNull(5000) { sender.sendDataToPebble(WATCH_UUID, data) }
        return when {
            results == null -> "Watch connection unavailable. Check Settings → Workouts."
            results.isEmpty() -> "No connected watch reported. Check PebbleKit 2 in your watch app."
            results.values.any { it == TransmissionResult.Success } -> "Live stats on Pebble"
            results.values.any { it == TransmissionResult.FailedNoPermissions } ->
                "Reinstall Workout Live and allow Pebble Health in your watch companion."
            results.values.any { it == TransmissionResult.FailedDifferentAppOpen } ->
                "Open Workout Live on your Pebble."
            else -> "Waiting for the watch to acknowledge live stats…"
        }
    }

    override fun close() = sender.close()

    companion object {
        private val WATCH_UUID = UUID.fromString("23a97c1c-672f-4285-9b6c-5244cae8526e")
    }
}
