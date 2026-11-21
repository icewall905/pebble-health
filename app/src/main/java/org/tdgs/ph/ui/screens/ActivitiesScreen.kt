package org.tdgs.ph.ui.screens

import android.content.Intent
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tdgs.ph.planner.WorkoutPlannerActivity
import org.tdgs.ph.workout.WorkoutActivity
import org.tdgs.ph.workout.WorkoutEntry
import org.tdgs.ph.workout.WorkoutStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.tdgs.ph.data.Intraday
import org.tdgs.ph.data.Repository
import org.tdgs.ph.data.Workout
import org.tdgs.ph.ui.Fmt
import org.tdgs.ph.ui.components.Bar
import org.tdgs.ph.ui.components.BarChart
import org.tdgs.ph.ui.components.BigValue
import org.tdgs.ph.ui.components.Note
import org.tdgs.ph.ui.components.PhCard
import org.tdgs.ph.ui.components.Pill
import org.tdgs.ph.ui.components.SectionTitle
import org.tdgs.ph.ui.components.StatRow
import org.tdgs.ph.ui.components.TimeLineChart
import org.tdgs.ph.ui.theme.LocalMetrics
import java.time.Instant

private fun icon(kind: String) = when (kind) {
    "run" -> Icons.AutoMirrored.Rounded.DirectionsRun
    "walk" -> Icons.AutoMirrored.Rounded.DirectionsWalk
    else -> Icons.Rounded.FitnessCenter
}

@Composable
fun ActivitiesScreen(repo: Repository, open: (String) -> Unit) {
    val state by repo.state.collectAsState()
    val d = state.dashboard
    val context = LocalContext.current
    Page(repo, "Activities", d?.let { "${it.workouts.size} in the last 6 weeks" }) {
        item { WorkoutEntry() }
        item {
            PhCard(onClick = { context.startActivity(Intent(context, WorkoutPlannerActivity::class.java)) }) {
                Text("Workout planner", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (d == null) { item { Loading() }; return@Page }
        if (d.workouts.isEmpty()) { item { Empty("No activities recorded yet") }; return@Page }
        val zone = Fmt.zone(d.tz)
        d.workouts.groupBy { Instant.ofEpochSecond(it.start).atZone(zone).toLocalDate().let { day -> day.minusDays(day.dayOfWeek.value - 1L) } }
            .forEach { (week, list) ->
                item { SectionTitle("Week of ${Fmt.dayShort(week)} · ${Fmt.hm(list.sumOf { it.seconds })}") }
                items(list, key = { it.id }) { w -> WorkoutRow(w, d.tz) { open(w.id) } }
            }
    }
}

@Composable
private fun WorkoutRow(w: Workout, tz: String, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val color = if (w.kind == "run") m.heart else m.steps
    PhCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon(w.kind), null, tint = color)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(Fmt.kind(w.kind), style = MaterialTheme.typography.titleMedium)
                Text(Fmt.dateTime(w.start, tz), style = MaterialTheme.typography.bodySmall, color = m.muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.hm(w.seconds), style = MaterialTheme.typography.titleMedium)
                if (w.excluded) Pill("Excluded", m.muted)
            }
        }
    }
}

@Composable
fun ActivityDetailScreen(repo: Repository, id: String, back: () -> Unit) {
    val state by repo.state.collectAsState()
    val d = state.dashboard
    val w = d?.workouts?.firstOrNull { it.id == id }
    val context = LocalContext.current
    val phoneWorkout = w?.id?.startsWith("phone:") == true
    val localId = w?.id?.removePrefix("phone:")
    var hasLocalWorkout by remember(id) { mutableStateOf(false) }
    LaunchedEffect(localId, phoneWorkout) {
        if (phoneWorkout && localId != null) {
            hasLocalWorkout = withContext(Dispatchers.IO) {
                runCatching { WorkoutStore.get(context).contains(localId) }.getOrDefault(false)
            }
        }
    }
    var intraday by remember { mutableStateOf<List<Intraday>?>(null) }
    LaunchedEffect(id, d?.tz) {
        if (w == null || phoneWorkout) return@LaunchedEffect
        val zone = Fmt.zone(d.tz)
        val first = Instant.ofEpochSecond(w.start).atZone(zone).toLocalDate()
        val last = Instant.ofEpochSecond(w.start + w.seconds).atZone(zone).toLocalDate()
        val days = generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.toList()
        intraday = days.mapNotNull { repo.intraday(it).getOrNull() }
    }
    Page(repo, w?.let { Fmt.kind(it.kind) } ?: "Activity", w?.let { Fmt.dateTime(it.start, d.tz) }, back = back) {
        if (w == null) { item { Empty("Activity not found") }; return@Page }
        if (phoneWorkout) {
            item {
                PhCard {
                    BigValue(Fmt.hm(w.activeSeconds ?: w.seconds), sub = "Active time")
                    StatRow("Distance" to (w.distanceM?.let { "${Fmt.dec(it / 1000, 2)} km" } ?: "—"),
                        "HR coverage" to (w.hrCoverage?.let { "${Fmt.int(it * 100)}%" } ?: "—"),
                        "Load" to Fmt.int(w.load))
                    if (hasLocalWorkout && localId != null) {
                        TextButton(onClick = {
                            context.startActivity(Intent(context, WorkoutActivity::class.java)
                                .putExtra("history", true).putExtra("workout_id", localId))
                        }) { Text("Open recording") }
                    }
                }
            }
            return@Page
        }
        item {
            val m = LocalMetrics.current
            val pts = intraday?.flatMap { it.points }?.filter { it.ts >= w.start && it.ts < w.start + w.seconds }
            PhCard {
                BigValue(Fmt.hm(w.seconds), sub = "${Fmt.clock(w.start, d.tz)} – ${Fmt.clock(w.start + w.seconds, d.tz)}")
                if (pts == null) { Loading(120); return@PhCard }
                val hr = pts.mapNotNull { p -> p.hr?.let { p.ts to it.toDouble() } }
                val steps = pts.sumOf { it.steps ?: 0 }
                StatRow("Steps" to Fmt.int(steps), "Avg HR" to Fmt.int(hr.map { it.second }.average().takeIf { hr.isNotEmpty() }),
                    "Max HR" to Fmt.int(hr.maxOfOrNull { it.second }))
                StatRow("Cadence" to (if (w.seconds > 0) "${Fmt.int(steps * 60.0 / w.seconds)} spm" else "—"),
                    "Effort" to (w.rpe?.let { Fmt.dec(it, 0) + "/10" } ?: "—"), "Load" to Fmt.int(w.load))
                if (hr.size >= 2) {
                    Text("Heart rate", style = MaterialTheme.typography.labelSmall, color = m.muted, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                    TimeLineChart(hr, w.start, w.start + w.seconds, m.heart, height = 160.dp, gapS = 180)
                } else {
                    Note("No heart rate was recorded during this activity. Turn on \"HR During Activity\" in the watch's Health settings.")
                }
                val perMin = pts.map { Bar(Fmt.clock(it.ts, d.tz), it.steps?.toDouble()) }
                if (perMin.any { (it.value ?: 0.0) > 0 }) {
                    Text("Steps per minute", style = MaterialTheme.typography.labelSmall, color = m.muted, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                    BarChart(perMin, m.steps, height = 100.dp, labelEvery = (perMin.size / 4).coerceAtLeast(1))
                }
            }
        }
    }
}

