package org.tdgs.ph.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.tdgs.ph.data.Dashboard
import org.tdgs.ph.data.DaySummary
import org.tdgs.ph.data.Intraday
import org.tdgs.ph.data.Prefs
import org.tdgs.ph.data.Repository
import org.tdgs.ph.ui.Fmt
import org.tdgs.ph.ui.components.Bar
import org.tdgs.ph.ui.components.BarChart
import org.tdgs.ph.ui.components.BigValue
import org.tdgs.ph.ui.components.Note
import org.tdgs.ph.ui.components.PhCard
import org.tdgs.ph.ui.components.RangeChart
import org.tdgs.ph.ui.components.Segmented
import org.tdgs.ph.ui.components.SleepTimeline
import org.tdgs.ph.ui.components.StatRow
import org.tdgs.ph.ui.components.TimeLineChart
import org.tdgs.ph.ui.theme.LocalMetrics
import java.time.LocalDate
import java.time.YearMonth

private val RANGES = listOf("Day", "Week", "Month", "Year")
private val RANGE_DAYS = listOf(1, 7, 28, 365)

private val TITLES = mapOf(
    "steps" to "Steps", "heart" to "Heart rate", "sleep" to "Sleep", "stress" to "Stress", "readiness" to "Readiness",
    "oxygen" to "Pulse Ox", "weight" to "Weight", "battery" to "Watch battery", "intensity" to "Intensity minutes",
    "energy" to "Calories",
)

@Composable
fun MetricDetailScreen(repo: Repository, name: String, back: () -> Unit) {
    val state by repo.state.collectAsState()
    val d = state.dashboard
    val today = d?.today?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
    Page(repo, TITLES[name] ?: name, back = back) {
        if (d == null) { item { Loading() }; return@Page }
        when (name) {
            "steps", "heart", "energy" -> item { RangedDaily(repo, d, name, today) }
            "sleep" -> sleepDetail(d)
            "stress" -> stressDetail(d)
            "readiness" -> readinessDetail(d)
            "oxygen" -> oxygenDetail(d)
            "weight" -> weightDetail(d)
            "battery" -> batteryDetail(d)
            "intensity" -> intensityDetail(d)
        }
    }
}

/** Day view from intraday minutes; Week/Month/Year from the hub's daily rollup. */
@Composable
private fun RangedDaily(repo: Repository, d: Dashboard, name: String, today: LocalDate) {
    val m = LocalMetrics.current
    val prefs by repo.settings.prefs.collectAsState(initial = Prefs(8000, 8, "system"))
    var range by remember { mutableIntStateOf(0) }
    var day by remember { mutableStateOf(today) }
    var intraday by remember { mutableStateOf<Intraday?>(null) }
    var days by remember { mutableStateOf<List<DaySummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(range, day) {
        error = null
        if (range == 0) {
            intraday = null
            repo.intraday(day).onSuccess { intraday = it }.onFailure { error = it.message }
        } else {
            days = null
            repo.daily(today.minusDays(RANGE_DAYS[range] - 1L), today).onSuccess { days = it }.onFailure { error = it.message }
        }
    }
    val color = when (name) { "heart" -> m.heart; "energy" -> m.energy; else -> m.steps }
    androidx.compose.foundation.layout.Column {
        Segmented(RANGES, range, { range = it })
        Spacer(Modifier.height(12.dp))
        if (range == 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { day = day.minusDays(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous day") }
                Text(if (day == today) "Today" else Fmt.dayShort(day), Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { if (day < today) day = day.plusDays(1) }, enabled = day < today) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next day")
                }
            }
        }
        PhCard {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (range == 0) {
                val i = intraday ?: run { Loading(); return@PhCard }
                DayView(name, i, d, color, prefs.stepGoal)
            } else {
                val list = days ?: run { Loading(); return@PhCard }
                RangeView(name, list, range, color, prefs.stepGoal)
            }
        }
    }
}

private fun hourAxis(start: Long) = listOf(0, 6, 12, 18).map { start + it * 3600L to "%02d".format(it) }

@Composable
private fun DayView(name: String, i: Intraday, d: Dashboard, color: Color, goal: Int) {
    val m = LocalMetrics.current
    when (name) {
        "steps" -> {
            val total = i.points.sumOf { it.steps ?: 0 }
            val active = i.points.count { it.active == 1 }
            BigValue(Fmt.int(total), "steps", "${total * 100 / goal}% of ${Fmt.int(goal)} goal")
            Spacer(Modifier.height(12.dp))
            val hourly = (0 until 24).map { h ->
                val from = i.start + h * 3600L
                val v = i.steps15.filter { it.first >= from && it.first < from + 3600 }.sumOf { it.second }
                Bar("%02d:00".format(h), v.toDouble())
            }
            BarChart(hourly, color, labelEvery = 6)
            StatRow("Active" to Fmt.hm(active * 60), "Distance" to Fmt.km(d.days.firstOrNull { it.date == i.date }?.distanceM),
                "Minutes" to Fmt.int(i.points.size))
        }
        "heart" -> {
            val pts = i.points.mapNotNull { p -> p.hr?.let { p.ts to it.toDouble() } }
            val day = d.days.firstOrNull { it.date == i.date }
            BigValue(Fmt.int(day?.hrAvg), "bpm avg", "${pts.size} readings")
            Spacer(Modifier.height(12.dp))
            val sleep = i.sleep.filter { it.third == "sleep" }.map { it.first to it.first + it.second }
            TimeLineChart(pts, i.start, i.end, color, axis = hourAxis(i.start), gapS = 30 * 60, bands = sleep,
                bandColor = m.sleep.copy(alpha = 0.10f))
            StatRow("Resting" to Fmt.int(day?.restingHr), "Low" to Fmt.int(day?.hrMin), "High" to Fmt.int(day?.hrMax))
            if (pts.isNotEmpty()) zoneSummary(pts.map { it.second }, d.hrMax)
            if (pts.size < 120) Note("Heart rate was recorded in ${pts.size} of ${i.points.size} minutes. Set the watch's heart rate monitoring to its most frequent interval for continuous data.")
        }
        else -> {
            val day = d.days.firstOrNull { it.date == i.date }
            BigValue(Fmt.int((day?.activeKcal ?: 0.0) + (day?.restingKcal ?: 0.0)), "kcal total")
            StatRow("Active" to Fmt.int(day?.activeKcal), "Resting" to Fmt.int(day?.restingKcal), "Distance" to Fmt.km(day?.distanceM))
        }
    }
}

@Composable
private fun zoneSummary(hr: List<Double>, hrMax: Int?) {
    val max = hrMax ?: return
    val m = LocalMetrics.current
    val bounds = listOf(0.5, 0.6, 0.7, 0.8, 0.9)
    val counts = bounds.mapIndexed { idx, lo ->
        val hi = bounds.getOrNull(idx + 1) ?: 9.0
        hr.count { it >= lo * max && it < hi * max }
    }
    Text("Time in zones (minutes with readings)", style = MaterialTheme.typography.labelSmall, color = m.muted,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
    BarChart(counts.mapIndexed { i, c -> Bar("Z${i + 1}", c.toDouble()) }, m.heart, height = 80.dp)
}

private fun monthly(list: List<DaySummary>, pick: (DaySummary) -> Double?): List<Bar> =
    list.groupBy { YearMonth.from(LocalDate.parse(it.date)) }.toSortedMap().map { (ym, ds) ->
        val vals = ds.mapNotNull(pick)
        Bar(Fmt.month(ym.atDay(1)), if (vals.isEmpty()) null else vals.average(), "${vals.size} days")
    }

@Composable
private fun RangeView(name: String, list: List<DaySummary>, range: Int, color: Color, goal: Int) {
    val labelEvery = when (range) { 1 -> 1; 2 -> 7; else -> 2 }
    fun label(s: String) = LocalDate.parse(s).let { if (range == 1) Fmt.weekday(it) else Fmt.dayTiny(it) }
    when (name) {
        "steps" -> {
            val vals = list.mapNotNull { it.steps }
            BigValue(Fmt.int(vals.takeIf { it.isNotEmpty() }?.average()), "avg / day", "${Fmt.int(vals.sum())} total")
            Spacer(Modifier.height(12.dp))
            val bars = if (range == 3) monthly(list) { it.steps?.toDouble() } else list.map { Bar(label(it.date), it.steps?.toDouble()) }
            BarChart(bars, color, goal = goal.toDouble(), labelEvery = labelEvery)
            StatRow("Best" to Fmt.int(vals.maxOrNull()), "Goal days" to "${vals.count { it >= goal }}",
                "Active" to Fmt.hm(list.mapNotNull { it.activeMinutes }.sum() * 60))
        }
        "heart" -> {
            val rest = list.mapNotNull { it.restingHr }
            BigValue(Fmt.int(rest.takeIf { it.isNotEmpty() }?.average()), "bpm resting avg")
            Spacer(Modifier.height(12.dp))
            if (range == 3) {
                BarChart(monthly(list) { it.restingHr?.toDouble() ?: it.hrMin?.toDouble() }, color, valueText = { "${Fmt.int(it)} bpm" })
            } else {
                RangeChart(list.map { Triple(it.hrMin?.toDouble(), it.hrAvg, it.hrMax?.toDouble()) },
                    list.map { label(it.date) }, color, labelEvery = labelEvery, unit = "bpm")
            }
            StatRow("Lowest" to Fmt.int(list.mapNotNull { it.hrMin }.minOrNull()), "Highest" to Fmt.int(list.mapNotNull { it.hrMax }.maxOrNull()),
                "HR minutes" to Fmt.int(list.sumOf { it.hrMinutes }))
        }
        else -> {
            val totals = list.map { if (it.activeKcal == null && it.restingKcal == null) null else (it.activeKcal ?: 0.0) + (it.restingKcal ?: 0.0) }
            BigValue(Fmt.int(totals.filterNotNull().takeIf { it.isNotEmpty() }?.average()), "kcal / day")
            Spacer(Modifier.height(12.dp))
            if (range == 3) BarChart(monthly(list) { it.activeKcal }, color)
            else BarChart(list.mapIndexed { i, it -> Bar(label(it.date), totals[i]) }, color.copy(alpha = 0.35f),
                labelEvery = labelEvery, stacked = list.map { it.activeKcal }, stackedColor = color)
            Note("Bright: active calories. Faint: total including resting energy.")
        }
    }
}

private fun LazyListScope.sleepDetail(d: Dashboard) {
    val nights = d.nights.takeLast(28)
    item {
        val m = LocalMetrics.current
        PhCard {
            BigValue(d.sleep.score?.toString() ?: "—", "score", "${Fmt.sleepWord(d.sleep.score)} · ${d.sleep.reason ?: ""}")
            StatRow("Duration" to (d.sleep.duration?.toString() ?: "—"), "Regularity" to (d.sleep.regularity?.toString() ?: "—"),
                "Need" to Fmt.hm(d.sleep.needS))
            Spacer(Modifier.height(12.dp))
            BarChart(nights.takeLast(14).map { n -> Bar(Fmt.dayTiny(LocalDate.parse(n.night)), n.sleepS / 3600.0, "restful ${Fmt.hm(n.restfulS)}") },
                m.sleep.copy(alpha = 0.55f), goal = (d.sleep.needS ?: 28800) / 3600.0, valueText = { "${Fmt.dec(it)} h" },
                labelEvery = 2, stacked = nights.takeLast(14).map { it.restfulS / 3600.0 }, stackedColor = m.restful)
            Note("Dark: restful sleep as labelled by the watch. Nights without a recording are absent, not zero.")
        }
    }
    items(nights.reversed().take(14)) { n ->
        val m = LocalMetrics.current
        PhCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.dayShort(LocalDate.parse(n.night)), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(Fmt.hm(n.sleepS), style = MaterialTheme.typography.titleMedium)
            }
            if (n.onset != null && n.wake != null) {
                SleepTimeline(n.onset, n.wake, n.sleepIntervals, n.restfulIntervals, Modifier.padding(top = 8.dp), height = 22.dp)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(Fmt.clock(n.onset, d.tz), style = MaterialTheme.typography.labelSmall, color = m.muted, modifier = Modifier.weight(1f))
                    Text(Fmt.clock(n.wake, d.tz), style = MaterialTheme.typography.labelSmall, color = m.muted)
                }
            }
        }
    }
}

private fun LazyListScope.stressDetail(d: Dashboard) {
    item {
        val m = LocalMetrics.current
        PhCard {
            val latest = d.stress.score ?: d.stressPoints.lastOrNull { it.score != null }?.score
            BigValue(latest?.toString() ?: "—", null, Fmt.stressWord(latest))
            val dayStart = d.now - 24 * 3600
            val pts = d.stressPoints.filter { it.ts >= dayStart && it.score != null }.map { it.ts to it.score!!.toDouble() }
            Spacer(Modifier.height(12.dp))
            if (pts.isEmpty()) Note("No stress readings in the last 24 hours. Stress needs quiet periods with heart-rate readings.")
            else TimeLineChart(pts, dayStart, d.now, m.stress, yMin = 0.0, yMax = 100.0, gapS = 45 * 60)
            Spacer(Modifier.height(12.dp))
            val days = d.stressDays.takeLast(14)
            BarChart(days.map { Bar(Fmt.dayTiny(LocalDate.parse(it.date)), it.score?.toDouble()) }, m.stress, labelEvery = 2, height = 120.dp)
            Note("Estimated from pulse elevation above your own quiet baseline while still; it is not a validated HRV stress score.")
        }
    }
}

private fun LazyListScope.readinessDetail(d: Dashboard) {
    item {
        PhCard {
            BigValue(d.readiness.score?.toString() ?: "—", null, Fmt.readinessWord(d.readiness.score))
            if (d.readinessComponents.isNotEmpty()) {
                StatRow(*d.readinessComponents.entries.take(3).map { (k, v) -> k.replace('_', ' ') to (v?.toString() ?: "—") }.toTypedArray())
            }
            d.readiness.reason?.let { Note(it) }
            StatRow("Training" to (d.trainingStatus ?: "—"), "Acute" to Fmt.int(d.acuteLoad), "Chronic" to Fmt.int(d.chronicLoad))
        }
    }
}

private fun LazyListScope.oxygenDetail(d: Dashboard) {
    item {
        val m = LocalMetrics.current
        PhCard {
            BigValue(d.oxygenLatest?.percent?.let { Fmt.int(it) } ?: "—", "%", d.oxygenLatest?.ts?.let { Fmt.dateTime(it, d.tz) })
            Spacer(Modifier.height(12.dp))
            val days = d.oxygenDays.takeLast(14)
            RangeChart(days.map { Triple(it.min, it.mean, it.max) }, days.map { Fmt.dayTiny(LocalDate.parse(it.date)) }, m.oxygen,
                labelEvery = 2, unit = "%")
            Note("Experimental optical SpO₂ from the watch; spot readings, not continuous monitoring.")
        }
    }
}

private fun LazyListScope.weightDetail(d: Dashboard) {
    item {
        val m = LocalMetrics.current
        PhCard {
            val last = d.weight.lastOrNull()
            BigValue(Fmt.dec(last?.kg), "kg", last?.trendKg?.let { "Trend ${Fmt.dec(it)} kg" })
            Spacer(Modifier.height(12.dp))
            val pts = d.weight.map { LocalDate.parse(it.date).toEpochDay() * 86400 to (it.trendKg ?: it.kg) }
            if (pts.size > 1) TimeLineChart(pts, pts.first().first, pts.last().first, m.weight, gapS = 30L * 86400, fill = false)
            StatRow("7 days" to (d.weightChange7d?.let { "${if (it >= 0) "+" else ""}${Fmt.dec(it)} kg" } ?: "—"),
                "Lowest" to Fmt.dec(d.weight.minOfOrNull { it.kg }), "Highest" to Fmt.dec(d.weight.maxOfOrNull { it.kg }))
        }
    }
}

private fun LazyListScope.batteryDetail(d: Dashboard) {
    item {
        val m = LocalMetrics.current
        val b = d.battery
        PhCard {
            BigValue((b.percent ?: b.lastPercent)?.toString() ?: "—", "%", b.observedAt?.let { "Read ${Fmt.dateTime(it, d.tz)}" })
            Spacer(Modifier.height(12.dp))
            val s = b.series.map { it.first to it.second.toDouble() }
            if (s.size > 1) TimeLineChart(s, s.first().first, d.now, m.battery, yMin = 0.0, yMax = 100.0, gapS = 3600)
            StatRow("Per charge" to (b.runtimeHours?.let { "${Fmt.dec(it / 24)} d" } ?: "—"), "Status" to (b.status ?: "—"))
        }
    }
}

private fun LazyListScope.intensityDetail(d: Dashboard) {
    item {
        val m = LocalMetrics.current
        PhCard {
            BigValue(Fmt.int(d.intensityWeek), "min", "of ${Fmt.int(d.intensityGoal ?: 150.0)} weekly goal")
            Spacer(Modifier.height(12.dp))
            val days = d.intensityDays.takeLast(14)
            BarChart(days.map { Bar(Fmt.dayTiny(LocalDate.parse(it.date)), it.total) }, m.intensity, labelEvery = 2,
                stacked = days.map { it.vigorous?.times(2) }, stackedColor = m.heart)
            Note("Moderate and vigorous minutes from heart rate; vigorous minutes count double. Needs continuous heart rate.")
        }
    }
}

