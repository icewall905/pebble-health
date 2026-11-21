package org.tdgs.ph.workout

import kotlinx.serialization.json.*
import kotlin.math.roundToInt

private const val MINUTE_MS = 60_000L

private class ReviewMinute {
    val heart = mutableMapOf<Long, Int>()
    var peakHeart: Int? = null
    var distance = 0.0
    var gpsMs = 0L
    var elevation: Double? = null

    fun json() = buildJsonObject {
        if (heart.isNotEmpty()) {
            put("hr", heart.values.average().roundToInt())
            peakHeart?.let { put("peak_hr", it) }
            put("hr_seconds", heart.size)
        }
        if (gpsMs > 0) {
            put("distance_m", distance.roundToInt())
            put("gps_seconds", ((gpsMs + 999) / 1000).toInt())
        }
        elevation?.takeIf { it in -12000.0..12000.0 }?.let { put("elevation_m", it.roundToInt()) }
    }
}

internal fun WorkoutDetail.trainerEvidence(): JsonObject {
    val elapsedMs = workout.end - workout.start
    require(elapsedMs in 1..172_800_000L) { "Trainer review supports activities up to 48 hours." }
    val seconds = ((elapsedMs + 999) / 1000).toInt()
    val minutes = List((seconds + 59) / 60) { ReviewMinute() }
    fun offset(ts: Long) = (ts - workout.start).takeIf { it in 0 until elapsedMs }
    for (sample in heart) {
        val time = offset(sample.time) ?: continue
        if (sample.bpm !in 25..300) continue
        val minute = minutes[(time / MINUTE_MS).toInt()]
        minute.heart[time / 1000] = sample.bpm
        minute.peakHeart = maxOf(minute.peakHeart ?: sample.bpm, sample.bpm)
    }
    for (fix in route) {
        val time = offset(fix.time) ?: continue
        fix.altitude?.let { minutes[(time / MINUTE_MS).toInt()].elevation = it }
    }
    for ((first, last) in route.zipWithNext()) {
        if (first.segment != last.segment) continue
        val start = offset(first.time) ?: continue
        val end = last.time - workout.start
        val duration = end - start
        val meters = last.meters - first.meters
        if (duration !in 1..30_000 || meters !in 0.0..(duration * 0.04)) continue
        var cursor = start
        while (cursor < minOf(end, elapsedMs)) {
            val index = (cursor / MINUTE_MS).toInt()
            val stop = minOf((index + 1) * MINUTE_MS, end, elapsedMs)
            val overlap = stop - cursor
            minutes[index].distance += meters * overlap / duration
            minutes[index].gpsMs += overlap
            cursor = stop
        }
    }
    return buildJsonObject {
        put("kind", workout.sport.name)
        put("seconds", seconds)
        put("active_seconds", (workout.active / 1000).coerceIn(0, seconds.toLong()))
        put("manual", workout.manual)
        workout.rpe?.let { put("effort", it) }
        if (sets.isNotEmpty()) put("strength_sets", sets.size)
        if (route.isNotEmpty() || workout.manualDistance != null) put("distance_m", distance)
        put("minutes", buildJsonArray { minutes.forEach { add(it.json()) } })
    }
}
