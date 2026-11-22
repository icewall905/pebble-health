package org.tdgs.ph.ui.screens

import android.content.Intent
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tdgs.ph.planner.WorkoutPlannerActivity
import org.tdgs.ph.workout.ActivityTrainerReview
import org.tdgs.ph.workout.TrainerReviewEntry
import org.tdgs.ph.workout.WorkoutActivity
import org.tdgs.ph.workout.DashboardActivityRemoval
import org.tdgs.ph.workout.WorkoutUploadWorker
import org.tdgs.ph.workout.WorkoutEntry
import org.tdgs.ph.workout.WorkoutStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import androidx.compose.material.icons.rounded.DateRange
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
import org.tdgs.ph.ui.Fmt
import org.tdgs.ph.ui.components.Bar
import org.tdgs.ph.ui.components.BarChart
import org.tdgs.ph.ui.components.BigValue
import org.tdgs.ph.ui.components.CardHeader
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

private data class ActivityLogRow(
    val id: String,
    val kind: String,
    val start: Long,
    val seconds: Int,
    val local: Boolean = false,
    val title: String? = null,
    val badge: String? = null,
    val distanceM: Double? = null,
    val removed: Boolean = false,
)

@Composable
fun ActivitiesScreen(repo: Repository, open: (String) -> Unit) {
    val state by repo.state.collectAsState()
    val d = state.dashboard
    val context = LocalContext.current
    val tz = d?.tz ?: java.time.ZoneId.systemDefault().id
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        WorkoutUploadWorker.enqueueMissing(context)
        onPauseOrDispose { }
    }
    val local by produceState<Result<List<WorkoutStore.HistoryEntry>>?>(null, refresh) {
        value = withContext(Dispatchers.IO) { runCatching { WorkoutStore.get(context).history() } }
    }
    val logs = remember(d?.workouts, local) {
        val localRows = local?.getOrNull().orEmpty().map { (it, distance) ->
            ActivityLogRow("phone:${it.id}", it.sport.name, it.start / 1000, (it.active / 1000).toInt(),
                local = true, title = it.title, badge = when {
                    it.manual -> "Manual"
                    it.interrupted -> "Interrupted"
                    else -> null
                }, distanceM = distance)
        }
        val hub = d?.workouts.orEmpty().associateBy { it.id }
        val localIds = localRows.mapTo(mutableSetOf()) { it.id }
        (localRows.map { row -> row.copy(distanceM = row.distanceM ?: hub[row.id]?.distanceM,
                badge = if (hub[row.id]?.ignored == true) "Removed" else row.badge,
                removed = hub[row.id]?.ignored == true) } +
            hub.values.filterNot { it.id in localIds }.map {
                ActivityLogRow(it.id, it.kind, it.start, it.activeSeconds ?: it.seconds,
                    badge = if (it.ignored) "Removed" else if (it.excluded) "Excluded" else if (it.id.startsWith("pebble:")) "Watch" else null,
                    distanceM = it.distanceM, removed = it.ignored)
            }).sortedByDescending { it.start }
    }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    val kinds = remember(logs) { logs.map { it.kind }.distinct().sortedBy(Fmt::kind) }
    val selected = kind?.takeIf { it in kinds }
    var showRemoved by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(logs, selected, showRemoved) {
        logs.filter { (showRemoved || !it.removed) && (selected == null || it.kind == selected) }
    }
    Page(repo, "Activities", null) {
        item { WorkoutEntry() }
        item {
            PhCard(onClick = { context.startActivity(Intent(context, WorkoutPlannerActivity::class.java)) }) {
                CardHeader("Workout planner", Icons.Rounded.DateRange, LocalMetrics.current.steps)
                Note("Your training calendar and Trainer")
            }
        }
        if (local?.isFailure == true) item { Note("Could not load activities saved on this phone.") }
        if (logs.isEmpty()) {
            item {
                when {
                    local == null && d == null -> Loading()
                    else -> Empty("No activities recorded yet")
                }
            }
            return@Page
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle("Activity history") }
                Box {
                    OutlinedButton(onClick = { filterOpen = true }) { Text(selected?.let(Fmt::kind) ?: "All types") }
                    DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                        DropdownMenuItem(text = { Text("All types") }, onClick = { kind = null; filterOpen = false })
                        kinds.forEach { type ->
                            DropdownMenuItem(text = { Text(Fmt.kind(type)) }, onClick = { kind = type; filterOpen = false })
                        }
                    }
                }
            }
        }
        if (logs.any { it.removed }) item {
            TextButton(onClick = { showRemoved = !showRemoved }) { Text(if (showRemoved) "Hide removed" else "Show removed") }
        }
        val zone = Fmt.zone(tz)
        filtered.groupBy {
            val day = Instant.ofEpochSecond(it.start).atZone(zone).toLocalDate()
            day.minusDays(day.dayOfWeek.value - 1L)
        }.forEach { (week, list) ->
            item { SectionTitle("Week of ${Fmt.dayShort(week)} · ${Fmt.hm(list.sumOf { it.seconds })}") }
            items(list, key = { it.id }) { row ->
                WorkoutRow(row, tz) {
                    if (row.local) {
                        context.startActivity(Intent(context, WorkoutActivity::class.java)
                            .putExtra("history", true).putExtra("workout_id", row.id.removePrefix("phone:")))
                    } else open(row.id)
                }
            }
        }
    }
}

@Composable
private fun WorkoutRow(row: ActivityLogRow, tz: String, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val color = if (row.kind == "run") m.heart else m.steps
    PhCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon(row.kind), null, tint = color)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.title ?: Fmt.kind(row.kind), style = MaterialTheme.typography.titleMedium)
                val activityType = row.title?.let { Fmt.kind(row.kind) + " · " }.orEmpty()
                Text(activityType + Fmt.dateTime(row.start, tz), style = MaterialTheme.typography.bodySmall, color = m.muted)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.hm(row.seconds), style = MaterialTheme.typography.titleMedium)
                row.distanceM?.let { Text("${Fmt.dec(it / 1000, 2)} km", style = MaterialTheme.typography.bodySmall) }
                row.badge?.let { Pill(it, m.muted) }
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
    var trainerReview by rememberSaveable(id) { mutableStateOf(false) }
    if (trainerReview && w != null) {
        ActivityTrainerReview(repo, Fmt.kind(w.kind), hubId = id, back = { trainerReview = false })
        return
    }
    Page(repo, w?.let { Fmt.kind(it.kind) } ?: "Activity", w?.let { Fmt.dateTime(it.start, d.tz) }, back = back) {
        if (w == null) { item { Empty("Activity not found") }; return@Page }
        item { DashboardActivityRemoval(repo, w) }
        item { TrainerReviewEntry { trainerReview = true } }
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

