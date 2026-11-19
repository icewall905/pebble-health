package org.tdgs.ph.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.tdgs.ph.data.DaySummary
import org.tdgs.ph.data.Prefs
import org.tdgs.ph.data.Repository
import org.tdgs.ph.ui.Fmt
import org.tdgs.ph.ui.components.Bar
import org.tdgs.ph.ui.components.BarChart
import org.tdgs.ph.ui.components.CardHeader
import org.tdgs.ph.ui.components.PhCard
import org.tdgs.ph.ui.components.Segmented
import org.tdgs.ph.ui.components.StatRow
import org.tdgs.ph.ui.theme.LocalMetrics
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
fun TrendsScreen(repo: Repository, open: (String) -> Unit) {
    val state by repo.state.collectAsState()
    val prefs by repo.settings.prefs.collectAsState(initial = Prefs(8000, 8, "system"))
    val d = state.dashboard
    val today = d?.today?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
    var range by remember { mutableIntStateOf(0) }
    var days by remember { mutableStateOf<List<DaySummary>?>(null) }
    val span = listOf(12L * 7, 26L * 7, 52L * 7)[range]
    LaunchedEffect(range, state.fetchedAt) {
        days = null
        repo.daily(today.minusDays(span - 1), today).onSuccess { days = it }
    }
    Page(repo, "Trends", "Weekly averages") {
        item { Segmented(listOf("12 weeks", "6 months", "1 year"), range, { range = it }) }
        val list = days
        if (list == null) { item { Loading() }; return@Page }
        val weeks = list.groupBy { LocalDate.parse(it.date).with(DayOfWeek.MONDAY) }.toSortedMap()
        fun label(w: LocalDate) = Fmt.dayTiny(w)
        val every = (weeks.size / 6).coerceAtLeast(1)
        item {
            val m = LocalMetrics.current
            PhCard(onClick = { open("metric/steps") }) {
                CardHeader("Steps per day", Icons.AutoMirrored.Rounded.DirectionsWalk, m.steps)
                Spacer(Modifier.height(8.dp))
                BarChart(weeks.map { (w, ds) -> Bar(label(w), ds.mapNotNull { it.steps }.takeIf { it.isNotEmpty() }?.average()) },
                    m.steps, goal = prefs.stepGoal.toDouble(), labelEvery = every, height = 140.dp)
            }
        }
        item {
            val m = LocalMetrics.current
            PhCard(onClick = { open("metric/heart") }) {
                CardHeader("Resting heart rate", Icons.Rounded.Favorite, m.heart)
                Spacer(Modifier.height(8.dp))
                BarChart(weeks.map { (w, ds) -> Bar(label(w), ds.mapNotNull { it.restingHr ?: it.hrMin }.takeIf { it.isNotEmpty() }?.average()) },
                    m.heart, labelEvery = every, height = 120.dp, valueText = { "${Fmt.int(it)} bpm" })
            }
        }
        d?.let { dash ->
            item {
                val m = LocalMetrics.current
                val nights = dash.nights.groupBy { LocalDate.parse(it.night).with(DayOfWeek.MONDAY) }.toSortedMap()
                PhCard(onClick = { open("metric/sleep") }) {
                    CardHeader("Sleep per night", Icons.Rounded.Bedtime, m.sleep)
                    Spacer(Modifier.height(8.dp))
                    BarChart(nights.map { (w, ns) -> Bar(label(w), ns.map { it.sleepS / 3600.0 }.average(), "${ns.size} nights") },
                        m.sleep, goal = prefs.sleepGoalH.toDouble(), valueText = { "${Fmt.dec(it)} h" }, height = 120.dp)
                }
            }
        }
        item { StepCalendar(list.takeLast(35), prefs.stepGoal) { open("metric/steps") } }
        item {
            val withSteps = list.mapNotNull { it.steps }
            PhCard {
                Text("Totals", style = MaterialTheme.typography.titleSmall)
                StatRow("Steps" to Fmt.int(withSteps.sum()), "Distance" to Fmt.km(list.mapNotNull { it.distanceM }.sum()),
                    "Active" to Fmt.hm(list.mapNotNull { it.activeMinutes }.sum() * 60))
                StatRow("Goal days" to "${withSteps.count { it >= prefs.stepGoal }}", "Recorded days" to "${withSteps.size}",
                    "Best day" to Fmt.int(withSteps.maxOrNull()))
            }
        }
    }
}

/** Five-week calendar, each day shaded by goal progress. */
@Composable
private fun StepCalendar(days: List<DaySummary>, goal: Int, onClick: () -> Unit) {
    val m = LocalMetrics.current
    if (days.isEmpty()) return
    val first = LocalDate.parse(days.first().date).with(DayOfWeek.MONDAY)
    val byDate = days.associateBy { it.date }
    val last = LocalDate.parse(days.last().date)
    PhCard(onClick = onClick) {
        Text("Step goal calendar", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = m.muted)
            }
        }
        var week = first
        while (week <= last) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0..6) {
                    val day = week.plusDays(i.toLong())
                    val steps = byDate[day.toString()]?.steps
                    val p = ((steps ?: 0) / goal.toFloat()).coerceIn(0f, 1f)
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (steps == null || day > last) m.cardAlt else m.steps.copy(alpha = 0.15f + 0.85f * p))
                            .clickable(onClick = onClick),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (day <= last) Text("${day.dayOfMonth}", style = MaterialTheme.typography.labelSmall,
                            color = if (p > 0.6f) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            week = week.plusWeeks(1)
        }
    }
}
