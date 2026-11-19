package org.tdgs.ph.ui

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

object Fmt {
    private val nf = NumberFormat.getIntegerInstance()
    fun int(v: Number?): String = v?.let { nf.format(it.toDouble().roundToInt()) } ?: "—"
    fun dec(v: Double?, digits: Int = 1): String = v?.let { String.format(Locale.getDefault(), "%.${digits}f", it) } ?: "—"
    fun hm(seconds: Int?): String {
        if (seconds == null) return "—"
        val m = (seconds + 30) / 60
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }
    fun km(m: Double?): String = m?.let { if (it >= 1000) "${dec(it / 1000, 2)} km" else "${it.roundToInt()} m" } ?: "—"
    fun zone(tz: String): ZoneId = runCatching { ZoneId.of(tz) }.getOrDefault(ZoneId.systemDefault())
    fun clock(ts: Long?, tz: String): String = ts?.let {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochSecond(it).atZone(zone(tz)))
    } ?: "—"
    fun dayLong(date: LocalDate): String = DateTimeFormatter.ofPattern("EEEE, d MMMM").format(date)
    fun dayShort(date: LocalDate): String = DateTimeFormatter.ofPattern("EEE d MMM").format(date)
    fun dayTiny(date: LocalDate): String = DateTimeFormatter.ofPattern("d/M").format(date)
    fun weekday(date: LocalDate): String = DateTimeFormatter.ofPattern("EEEEE").format(date)
    fun month(date: LocalDate): String = DateTimeFormatter.ofPattern("MMM").format(date)
    fun dateTime(ts: Long?, tz: String): String = ts?.let {
        DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").format(Instant.ofEpochSecond(it).atZone(zone(tz)))
    } ?: "—"
    fun ago(seconds: Long?): String = when {
        seconds == null -> "never"
        seconds < 90 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 172800 -> "${seconds / 3600} h ago"
        else -> "${seconds / 86400} days ago"
    }
    fun kind(kind: String): String = when (kind) {
        "walk" -> "Walk"; "run" -> "Run"; "open_workout", "workout" -> "Workout"
        else -> kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
    fun sleepWord(score: Int?): String = when {
        score == null -> "No score"; score >= 90 -> "Excellent"; score >= 80 -> "Good"; score >= 60 -> "Fair"; else -> "Poor"
    }
    fun stressWord(score: Int?): String = when {
        score == null -> "No reading"; score <= 25 -> "Resting"; score <= 50 -> "Low"; score <= 75 -> "Medium"; else -> "High"
    }
    fun readinessWord(score: Int?): String = when {
        score == null -> "Not enough data"; score >= 75 -> "High"; score >= 50 -> "Moderate"; score >= 25 -> "Low"; else -> "Poor"
    }
}
