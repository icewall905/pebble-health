package org.tdgs.ph.data

import kotlin.math.roundToInt

/**
 * A recent 7-day median against the median and middle 80% of the 28 days before it,
 * the comparison the hub's insights report uses. Missing days are skipped, never zeroed.
 */
data class Baseline(
    val recent: Double?,
    val base: Double?,
    val low: Double?,
    val high: Double?,
    val recentDays: Int,
    val baseDays: Int,
) {
    val diff: Double? get() = if (recent != null && base != null) recent - base else null
    val outside: Boolean get() = recent != null && low != null && high != null && (recent < low || recent > high)
}

enum class Tone { UP, DOWN, FLAT, NONE }

object Baselines {
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    fun quantile(sorted: List<Double>, q: Double): Double? =
        if (sorted.isEmpty()) null else sorted[(q * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.lastIndex)]

    /** [values] runs oldest to newest; the last 7 are recent, the 28 before them the baseline. */
    fun compute(values: List<Double?>): Baseline {
        val recent = values.takeLast(7).filterNotNull()
        val base = values.dropLast(7).takeLast(28).filterNotNull().sorted()
        val enoughBase = base.size >= 7
        return Baseline(
            recent = if (recent.size >= 3) median(recent) else null,
            base = if (enoughBase) median(base) else null,
            low = if (enoughBase) quantile(base, 0.1) else null,
            high = if (enoughBase) quantile(base, 0.9) else null,
            recentDays = recent.size,
            baseDays = base.size,
        )
    }

    fun fromInsight(m: InsightMetric) = Baseline(m.recentMedian, m.baselineMedian, m.baselineRange?.first, m.baselineRange?.second,
        m.recentDays, m.baselineDays)

    /**
     * [good] is +1 when higher is better, -1 when lower is better, 0 when neither.
     * Only a reading outside the usual range is coloured; inside it is just normal variation.
     */
    fun tone(b: Baseline, good: Int): Tone {
        val d = b.diff ?: return Tone.NONE
        if (!b.outside || good == 0 || d == 0.0) return Tone.FLAT
        return if ((d > 0) == (good > 0)) Tone.UP else Tone.DOWN
    }
}
