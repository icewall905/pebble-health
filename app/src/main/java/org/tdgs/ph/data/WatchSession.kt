package org.tdgs.ph.data

/** Distance, per-minute pace and km splits of a watch-detected session, from its minute records. */
data class WatchSessionStats(
    val distanceM: Double?,
    /** (minute ts, seconds per km) for minutes that covered ground. */
    val pace: List<Pair<Long, Double>>,
    /** Seconds each completed kilometre took, in order. */
    val splits: List<Double>,
)

/** Minute distances are the watch's step-based estimate: there is no GPS behind them. */
fun watchSessionStats(points: List<IntradayPoint>, start: Long, end: Long): WatchSessionStats {
    val inside = points.filter { it.ts in start until end }.sortedBy { it.ts }
    val withDistance = inside.filter { (it.distanceM ?: 0.0) > 0 }
    if (withDistance.isEmpty()) return WatchSessionStats(null, emptyList(), emptyList())
    // Minute bins cover [ts, ts+60) but can start before or end after the session.
    val pace = withDistance.map { it.ts to 60.0 * 1000 / it.distanceM!! }
    val splits = mutableListOf<Double>()
    var total = 0.0
    var lastSplitAt = start.toDouble()
    withDistance.forEach { p ->
        val metres = p.distanceM!!
        var used = 0.0
        while (total + (metres - used) >= (splits.size + 1) * 1000.0) {
            val need = (splits.size + 1) * 1000.0 - total
            used += need
            total += need
            val at = p.ts + 60.0 * used / metres
            splits.add(at - lastSplitAt)
            lastSplitAt = at
        }
        total += metres - used
    }
    return WatchSessionStats(withDistance.sumOf { it.distanceM!! }, pace, splits)
}

/** Watch sessions overlapping a phone recording: the phone one stands for both. */
fun overlapsAny(start: Long, end: Long, others: List<Pair<Long, Long>>): Boolean =
    others.any { (a, b) -> minOf(end, b) > maxOf(start, a) }
