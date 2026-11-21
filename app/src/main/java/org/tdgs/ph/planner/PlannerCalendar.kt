package org.tdgs.ph.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.tdgs.ph.ui.components.CardHeader
import org.tdgs.ph.ui.components.Note
import org.tdgs.ph.ui.components.PhCard
import org.tdgs.ph.ui.components.Segmented
import org.tdgs.ph.ui.theme.LocalMetrics
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.WeekFields
import java.util.Locale

internal enum class CalendarView(val label: String) { MONTH("Month"), WEEK("Week"), AGENDA("Agenda") }

internal fun LocalDate.startOfWeek(locale: Locale): LocalDate =
    with(WeekFields.of(locale).dayOfWeek(), 1)

internal data class CalendarWorkout(val data: JSONObject, val day: LocalDate, val recorded: Boolean)

internal fun calendarWorkouts(planned: List<JSONObject>, recorded: List<JSONObject>): List<CalendarWorkout> =
    (planned.mapNotNull { calendarWorkout(it, false) } + recorded.mapNotNull { calendarWorkout(it, true) })
        .sortedBy { it.data.optString("start") }

private fun calendarWorkout(data: JSONObject, recorded: Boolean): CalendarWorkout? =
    runCatching { LocalDate.parse(data.optString("start").take(10)) }.getOrNull()
        ?.let { CalendarWorkout(data, it, recorded) }

@Composable
internal fun PlannerCalendarHeader(
    day: LocalDate,
    today: LocalDate,
    view: CalendarView,
    locale: Locale,
    workouts: List<CalendarWorkout>,
    busy: Boolean,
    selectDay: (LocalDate) -> Unit,
    selectView: (CalendarView) -> Unit,
    add: () -> Unit,
    export: () -> Unit,
) {
    val month = YearMonth.from(day)
    val weekStart = day.startOfWeek(locale)
    val weekEnd = weekStart.plusDays(6)
    val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    val shortDate = DateTimeFormatter.ofPattern("d MMM", locale)
    val title = if (view == CalendarView.WEEK) {
        "${weekStart.format(shortDate)} – ${weekEnd.format(shortDate)} ${weekEnd.year}"
    } else day.format(monthFormat)
    val gridStart = if (view == CalendarView.WEEK) weekStart else month.atDay(1).startOfWeek(locale)
    val rows = if (view == CalendarView.WEEK) 1 else
        ((month.lengthOfMonth() + month.atDay(1).toEpochDay() - gridStart.toEpochDay() + 6) / 7).toInt()
    val byDay = workouts.groupBy { it.day }
    var menu by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Segmented(CalendarView.entries.map { it.label }, view.ordinal, { selectView(CalendarView.entries[it]) })
        PhCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                val unit = if (view == CalendarView.WEEK) "week" else "month"
                IconButton(onClick = { selectDay(if (view == CalendarView.WEEK) day.minusWeeks(1) else day.minusMonths(1)) }) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous $unit")
                }
                IconButton(onClick = { selectDay(if (view == CalendarView.WEEK) day.plusWeeks(1) else day.plusMonths(1)) }) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next $unit")
                }
            }
            if (view != CalendarView.AGENDA) {
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { index ->
                        val label = gridStart.plusDays(index.toLong()).format(DateTimeFormatter.ofPattern("EEEEE", locale))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(label, color = LocalMetrics.current.muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Column(Modifier.selectableGroup()) {
                    repeat(rows) { row ->
                        Row(Modifier.fillMaxWidth()) {
                            repeat(7) { column ->
                                val date = gridStart.plusDays((row * 7L) + column)
                                val entries = byDay[date].orEmpty()
                                CalendarDay(date, day, today, YearMonth.from(date) == month || view == CalendarView.WEEK,
                                    entries.count { !it.recorded }, entries.count { it.recorded }, locale,
                                    Modifier.weight(1f)) { selectDay(date) }
                            }
                        }
                    }
                }
                FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CalendarLegend("Scheduled", LocalMetrics.current.steps)
                    CalendarLegend("Recorded", LocalMetrics.current.readiness)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(enabled = !busy, onClick = add, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                Text("Add workout", Modifier.padding(start = 6.dp))
            }
            TextButton(onClick = { selectDay(today) }) { Text("Today") }
            Box {
                IconButton(enabled = !busy, onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Calendar actions") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Export calendar (.ics)") }, onClick = { menu = false; export() })
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(
    date: LocalDate,
    day: LocalDate,
    today: LocalDate,
    inPeriod: Boolean,
    scheduled: Int,
    recorded: Int,
    locale: Locale,
    modifier: Modifier,
    select: () -> Unit,
) {
    val metrics = LocalMetrics.current
    val colors = MaterialTheme.colorScheme
    val selected = date == day
    val label = buildString {
        append(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)))
        if (date == today) append(", today")
        if (scheduled > 0) append(", $scheduled scheduled")
        if (recorded > 0) append(", $recorded recorded")
    }
    Column(
        modifier.clip(RoundedCornerShape(10.dp))
            .then(if (selected) Modifier.background(metrics.steps.copy(alpha = .16f)) else Modifier)
            .selectable(selected, role = Role.Button, onClick = select)
            .semantics { contentDescription = label }
            .heightIn(min = 48.dp).padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            Modifier.clearAndSetSemantics {},
            color = when {
                selected || date == today -> colors.primary
                inPeriod -> colors.onSurface
                else -> metrics.muted
            },
            style = if (selected || date == today) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.padding(top = 3.dp).heightIn(min = 5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (scheduled > 0) Box(Modifier.size(5.dp).background(metrics.steps, CircleShape))
            if (recorded > 0) Box(Modifier.size(5.dp).background(metrics.readiness, CircleShape))
        }
    }
}

@Composable
private fun CalendarLegend(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(label, color = LocalMetrics.current.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun CalendarWorkoutCard(
    entry: CalendarWorkout,
    busy: Boolean,
    locale: Locale,
    edit: () -> Unit,
    delete: () -> Unit,
    ask: () -> Unit,
    addToPhone: () -> Unit,
) {
    val workout = entry.data
    val metrics = LocalMetrics.current
    val status = workout.optString("status", "planned")
    var menu by remember { mutableStateOf(false) }
    PhCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                CardHeader(
                    if (entry.recorded) "Recorded" else workoutStatus(workout),
                    if (entry.recorded) Icons.Rounded.Timeline else Icons.Rounded.DateRange,
                    if (entry.recorded) metrics.readiness else metrics.steps,
                    chevron = false,
                )
            }
            if (!entry.recorded) Box {
                IconButton(enabled = !busy, onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Workout actions") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit workout") }, onClick = { menu = false; edit() })
                    DropdownMenuItem(text = { Text("Ask Trainer") }, onClick = { menu = false; ask() })
                    if (status !in setOf("skipped", "rescheduled")) {
                        DropdownMenuItem(text = { Text("Add to phone calendar") }, onClick = { menu = false; addToPhone() })
                    }
                    DropdownMenuItem(text = { Text("Delete workout", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; delete() })
                }
            }
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WorkoutDescription(workout, timeOnly = true)
            if (entry.recorded) {
                val distance = workout.optJSONObject("metrics")?.optDouble("distance_m")?.takeIf { it.isFinite() }
                val stats = listOfNotNull(
                    distance?.let { String.format(locale, "%.2f km", it / 1000) },
                    if (workout.isNull("rpe")) null else "Effort ${workout.optInt("rpe")}/10",
                )
                if (stats.isNotEmpty()) Note(stats.joinToString(" · "))
            }
        }
    }
}
