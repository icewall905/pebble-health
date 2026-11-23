package org.tdgs.ph.workout

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tdgs.ph.PhApp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

private const val HISTORY_LIMIT = 8

internal data class WatchHistoryEntry(val sport: Sport, val start: Long, val duration: Int, val distance: Double?)

internal fun watchHistoryBytes(rows: List<WatchHistoryEntry>): ByteArray {
    val selected = rows.take(HISTORY_LIMIT)
    return ByteBuffer.allocate(selected.size * 13).order(ByteOrder.LITTLE_ENDIAN).apply {
        selected.forEach {
            put(watchSports.indexOf(it.sport).toByte())
            putInt(it.start.toInt())
            putInt(it.duration.coerceIn(0, 172800))
            putInt(it.distance?.takeIf { distance -> distance.isFinite() && distance >= 0 }?.roundToInt() ?: -1)
        }
    }.array()
}

internal suspend fun watchHistory(context: Context): PebbleDictionary = withContext(Dispatchers.IO) {
    val repo = (context.applicationContext as PhApp).repository
    repo.loadCached()
    val dashboard = repo.state.value.dashboard?.workouts.orEmpty()
    val ignored = dashboard.filter { it.ignored }.mapTo(HashSet()) { it.id }
    val local = WorkoutStore.get(context).history(limit = 32).filterNot { "phone:${it.workout.id}" in ignored }
    val localIds = local.mapTo(HashSet()) { "phone:${it.workout.id}" }
    val now = System.currentTimeMillis() / 1000
    val rows = local.map {
        WatchHistoryEntry(it.workout.sport, it.workout.start / 1000, (it.workout.active / 1000).toInt(), it.distance)
    } + dashboard.asSequence().filter { !it.ignored && it.id !in localIds && it.start <= now }.map {
        val kind = it.kind.lowercase()
        val sport = when (kind) {
            "walking" -> Sport.walk
            "running" -> Sport.run
            else -> Sport.entries.firstOrNull { sport -> sport.name == kind } ?: Sport.workout
        }
        WatchHistoryEntry(sport, it.start, it.activeSeconds ?: it.seconds, it.distanceM)
    }.toList()
    val packed = watchHistoryBytes(rows.sortedByDescending { it.start })
    // The marker also handles an empty list without relying on an empty AppMessage byte array.
    buildMap {
        put(27u, PebbleDictionaryItem.UInt32((packed.size / 13).toUInt()))
        if (packed.isNotEmpty()) put(26u, PebbleDictionaryItem.Bytes(packed))
    }
}

internal fun watchSensorStatus(context: Context, state: LiveWorkout?): PebbleDictionary {
    val prefs = context.getSharedPreferences("workout_setup", 0)
    val hasStrap = !prefs.getString("strap_address", null).isNullOrBlank()
    val strap = when {
        state?.bpm != null -> 4
        state?.strapStatus == "Chest strap connected" -> 3
        !state?.strapStatus.isNullOrEmpty() -> 2
        hasStrap -> 1
        else -> 0
    }
    val gps = when (state?.gpsStatus) {
        "GPS recording" -> 3
        "Waiting for GPS…" -> 2
        "GPS paused" -> 4
        null -> {
            val gpsReady = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED && runCatching {
                    context.getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)
                }.getOrDefault(false)
            if (gpsReady) 1 else 5
        }
        "" -> 0
        else -> 5
    }
    val recording = when {
        state?.paused == true -> 2
        state != null -> 1
        WorkoutRecorder.armed.value -> 3
        else -> 0
    }
    val status = strap or (gps shl 4) or (recording shl 8) or (((state?.strapBattery ?: -1) + 1) shl 16)
    return mapOf(24u to PebbleDictionaryItem.UInt32(status.toUInt()),
        25u to PebbleDictionaryItem.UInt32((state?.bpm ?: 0).toUInt()))
}
