package org.tdgs.ph.checkin

import java.time.ZonedDateTime

internal val defaultCheckInTimes = setOf(6 * 60, 12 * 60, 18 * 60)

internal data class CheckInSettings(
    val enabled: Boolean = false,
    val times: Set<Int> = defaultCheckInTimes,
    val sentSlots: Set<Int> = emptySet(),
)

internal fun dueCheckIn(now: ZonedDateTime, settings: CheckInSettings): Int? {
    if (!settings.enabled || settings.sentSlots.size >= settings.times.size) return null
    return settings.times.sorted().firstOrNull { minute ->
        val time = now.toLocalDate().atStartOfDay().plusMinutes(minute.toLong()).atZone(now.zone)
        val age = java.time.Duration.between(time, now).seconds
        age in 0 until 90 * 60L && minute !in settings.sentSlots
    }
}

internal data class CheckInProgress(
    val steps: Int?,
    val stepGoal: Int,
    val weeklyMinutes: Double?,
    val weeklyGoal: Double,
    val minuteOfDay: Int,
) {
    val stepsLeft = steps?.let { (stepGoal - it).coerceAtLeast(0) }
    val weeklyMet = weeklyMinutes?.let { it >= weeklyGoal }
    val moveBehind = steps != null && steps < stepGoal * ((minuteOfDay - 9 * 60) / (12 * 60.0)).coerceIn(0.0, 1.0)

    fun contextLine(format: (Number) -> String): String = buildList {
        steps?.let { add("${format(it)} / ${format(stepGoal)} steps today") }
        weeklyMinutes?.let { add("${format(it)} / ${format(weeklyGoal)} intensity min over 7 days") }
    }.joinToString(" · ")

    fun fallback() = when {
        stepsLeft == 0 && weeklyMet == true -> "Both goals are met. Take a moment to enjoy the progress."
        moveBehind -> "You’re behind the pace for your step goal. A short walk now can help close the gap."
        weeklyMet == true -> "You’ve hit your weekly intensity goal. Keep today comfortable."
        stepsLeft == 0 -> "Daily steps are done. If you feel up for it, make time for an activity you enjoy this week."
        minuteOfDay < 9 * 60 -> "Pick one easy moment to get moving today, even if it’s just a short walk."
        weeklyMinutes != null -> "A brisk walk or a favorite activity can move your weekly goal forward."
        stepsLeft != null -> "You’re building toward your daily step goal. Keep it going with an easy walk."
        else -> "There’s no recent goal data yet. Make a little time to move in a way you enjoy."
    }
}
