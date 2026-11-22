package org.tdgs.ph.workout

import java.io.Writer
import java.time.Instant
import kotlin.math.*

internal enum class Sport(val label: String, val gpsDefault: Boolean, val estimatedMet: Double? = null) {
    run("Running", true),
    hike("Hiking", true),
    walk("Walking", true),
    cycle("Cycling", true),
    strength("Strength", false),
    tennis("Tennis", false),
    rowing("Rowing", false),
    yoga("Yoga", false),
    beat_saber("Beat Saber", false, estimatedMet = 5.5),
    workout("Other workout", false),
}

internal data class Workout(
    val id: String,
    val sport: Sport,
    val start: Long,
    val end: Long,
    val active: Long,
    val interrupted: Boolean = false,
    val rpe: Int? = null,
    val title: String? = null,
    val notes: String? = null,
    val manual: Boolean = false,
    val manualDistance: Double? = null,
    val uploaded: Boolean = false,
)

/** Estimated total energy using the standard MET conversion (1 MET = 3.5 mL O₂/kg/min). */
internal fun Workout.estimatedCalories(weightKg: Double?): Int? {
    val met = sport.estimatedMet ?: return null
    val weight = weightKg?.takeIf { it.isFinite() && it > 0 } ?: return null
    return (met * 3.5 * weight / 200 * (active / 60_000.0)).roundToInt()
}

internal data class Fix(
    val time: Long, val active: Long, val segment: Int,
    val lat: Double, val lon: Double, val altitude: Double?,
    val accuracy: Float, val meters: Double,
)

internal data class Pulse(val active: Long, val bpm: Int, val time: Long, val segment: Int,
    val rrTicks: List<Int> = emptyList(), val energyKj: Int? = null)
internal data class HeartMeasurement(val bpm: Int, val rrTicks: List<Int>, val energyKj: Int?)
internal data class Lap(val active: Long, val meters: Double)
internal data class StrengthSet(val exercise: String, val reps: Int, val kg: Double?)
internal data class WorkoutDetail(
    val workout: Workout,
    val route: List<Fix>,
    val heart: List<Pulse>,
    val laps: List<Lap>,
    val sets: List<StrengthSet>,
) {
    val distance get() = workout.manualDistance ?: route.lastOrNull()?.meters ?: 0.0
}

internal fun decodeHeartRate(bytes: ByteArray): Int? = decodeHeartMeasurement(bytes)?.bpm

internal fun decodeHeartMeasurement(bytes: ByteArray): HeartMeasurement? {
    val flags = bytes.firstOrNull()?.toInt() ?: return null
    // Bit 2 means contact detection is supported. Bit 1 then indicates contact.
    if (flags and 6 == 4 || bytes.size < 2) return null
    val low = bytes[1].toInt() and 255
    val bpm = if (flags and 1 == 0) low else {
        if (bytes.size < 3) return null
        low + ((bytes[2].toInt() and 255) shl 8)
    }
    if (bpm !in 1..300) return null
    var offset = if (flags and 1 == 0) 2 else 3
    fun word(index: Int) = (bytes[index].toInt() and 255) or ((bytes[index + 1].toInt() and 255) shl 8)
    val energy = if (flags and 8 != 0 && offset + 1 < bytes.size) word(offset) else null
    if (flags and 8 != 0) offset += 2
    val intervals = if (flags and 16 == 0) emptyList() else
        (offset until bytes.lastIndex step 2).map { word(it) }.filter { it > 0 }
    return HeartMeasurement(bpm, intervals, energy)
}

internal fun WorkoutDetail.writeStrapCsv(out: Writer) {
    out.write("received_at,active_ms,segment,bpm,rr_ticks_1024hz,energy_counter_kj\n")
    heart.forEach { pulse ->
        out.write("${Instant.ofEpochMilli(pulse.time)},${pulse.active},${pulse.segment},${pulse.bpm},")
        out.write(pulse.rrTicks.joinToString(" "))
        out.write(",${pulse.energyKj ?: ""}\n")
    }
}

internal fun metersBetween(a: Fix, b: Fix): Double {
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 12_742_000 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

internal fun appendFix(previous: Fix?, point: Fix): Fix? {
    if (point.accuracy !in 0f..25f || point.lat !in -90.0..90.0 || point.lon !in -180.0..180.0) return null
    if (previous == null) return point.copy(meters = 0.0)
    if (point.active <= previous.active) return null
    if (previous.segment != point.segment) return point.copy(meters = previous.meters)
    val elapsed = (point.active - previous.active) / 1000.0
    if (elapsed > 30) return point.copy(segment = point.segment + 1, meters = previous.meters)
    val distance = metersBetween(previous, point)
    if (distance > elapsed * 40 || distance < max(3.0, max(previous.accuracy, point.accuracy) * .5)) return null
    return point.copy(meters = previous.meters + distance)
}

internal fun WorkoutDetail.writeGpx(out: Writer) {
    out.write("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="GravelEx" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
<trk><name>${workout.sport.label}</name><type>${workout.sport.name}</type>
""")
    var segment: Int? = null
    var pulseIndex = 0
    for (fix in route) {
        if (segment != fix.segment) {
            if (segment != null) out.write("</trkseg>\n")
            out.write("<trkseg>\n")
            segment = fix.segment
        }
        out.write("""<trkpt lat="${fix.lat}" lon="${fix.lon}">""")
        fix.altitude?.let { out.write("<ele>$it</ele>") }
        out.write("<time>${Instant.ofEpochMilli(fix.time)}</time>")
        while (pulseIndex < heart.size && heart[pulseIndex].active <= fix.active) pulseIndex++
        heart.getOrNull(pulseIndex - 1)?.takeIf { fix.time - it.time in 0..3000 && fix.segment == it.segment }?.let {
            out.write("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>${it.bpm}</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
        }
        out.write("</trkpt>\n")
    }
    if (segment != null) out.write("</trkseg>\n")
    out.write("</trk></gpx>\n")
}

internal data class HeartBucket(val offset: Int, val bpm: Int, val seconds: Int)

/** Quantize by observed seconds; empty buckets and Bluetooth gaps stay unobserved. */
internal fun heartBuckets(heart: List<Pulse>, activeSeconds: Int): List<HeartBucket> =
    heart.groupBy { (it.active / 1000).toInt() }.filterKeys { it in 0 until activeSeconds }
        .entries.groupBy { it.key / 60 }.toSortedMap().map { (minute, observations) ->
            HeartBucket(
                minute * 60,
                observations.map { entry -> entry.value.map { it.bpm }.average() }.average().roundToInt(),
                observations.size,
            )
        }


/** Zone zero is below 50%; zones 1 through 5 begin at 50% through 90%. */
internal fun heartZone(bpm: Double, maxBpm: Int): Int? =
    maxBpm.takeIf { it in 100..240 }?.let { ((bpm * 100 / it).toInt() / 10 - 4).coerceIn(0, 5) }

/** Six groups, beginning below 50% of configured maximum heart rate. */
internal fun zoneSeconds(samples: List<Pulse>, maxBpm: Int): List<Int> {
    require(maxBpm in 100..240)
    val result = MutableList(6) { 0 }
    samples.groupBy { it.active / 1000 }.values.forEach { second ->
        heartZone(second.map { it.bpm }.average(), maxBpm)?.let { result[it]++ }
    }
    return result
}
