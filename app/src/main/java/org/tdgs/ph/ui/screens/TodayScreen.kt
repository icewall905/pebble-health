package org.tdgs.ph.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Timeline
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.tdgs.ph.data.Baseline
import org.tdgs.ph.data.Baselines
import org.tdgs.ph.data.Dashboard
import org.tdgs.ph.data.DaySummary
import org.tdgs.ph.data.InsightsReport
import org.tdgs.ph.data.Intraday
import org.tdgs.ph.data.Prefs
import org.tdgs.ph.data.Repository
import org.tdgs.ph.data.Tone
import org.tdgs.ph.data.Workout
import org.tdgs.ph.ui.Fmt
import org.tdgs.ph.ui.components.Bar
import org.tdgs.ph.ui.components.BarChart
import org.tdgs.ph.ui.components.BigValue
import org.tdgs.ph.ui.components.CardHeader
import org.tdgs.ph.ui.components.ComponentMeter
import org.tdgs.ph.ui.components.DayRibbon
import org.tdgs.ph.ui.components.Heatmap
import org.tdgs.ph.ui.components.Note
import org.tdgs.ph.ui.components.PhCard
import org.tdgs.ph.ui.components.Pill
import org.tdgs.ph.ui.components.ProgressBar
import org.tdgs.ph.ui.components.RibbonLegend
import org.tdgs.ph.ui.components.Ring
import org.tdgs.ph.ui.components.Segmented
import org.tdgs.ph.ui.components.Sparkline
import org.tdgs.ph.ui.components.StatRow
import org.tdgs.ph.ui.components.StatusChip
import org.tdgs.ph.ui.components.TrendSpark
import org.tdgs.ph.ui.theme.LocalMetrics
import java.time.LocalDate

/**
 * Today: everything about the last day in one scroll, each metric shown once.
 * Status, a 30-hour timeline, readiness, sleep, recovery signals, activity, how the
 * last week compares with your usual, then training, the latest workout and your year.
 * On wide screens (tablets, unfolded phones) cards pair up side by side.
 */
@Composable
fun TodayScreen(repo: Repository, open: (String) -> Unit) {
    val state by repo.state.collectAsState()
    val insights by repo.insights.collectAsState()
    val prefs by repo.settings.prefs.collectAsState(initial = Prefs(8000, 8, "system"))
    val d = state.dashboard
    val today = d?.today?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    var intraday by remember { mutableStateOf<Intraday?>(null) }
    var year by remember { mutableStateOf<List<DaySummary>?>(null) }
    LaunchedEffect(today, state.fetchedAt) {
        val day = today ?: return@LaunchedEffect
        repo.intraday(day).onSuccess { intraday = it }
        repo.refreshInsights()
        repo.daily(day.minusDays(364), day).onSuccess { year = it }
    }
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= 600.dp
    val title = today?.let { Fmt.dayLong(it) } ?: "Today"
    val sub = d?.let { "Synced ${Fmt.ago(it.lastReceived?.let { r -> it.now - r })}" + (it.wearer?.let { w -> " · $w" } ?: "") }
    Page(repo, title, sub) {
        if (d == null || today == null) {
            item { if (state.loading) Loading() else Empty(state.error ?: "Pull to load your data") }
            return@Page
        }
        item { StatusRow(d) }
        d.briefing.firstOrNull { it.key == "today" }?.takeUnless { d.briefingNeedsRefresh }?.let { b -> item { BriefingStrip(b) } }
        item { TimelineCard(d, today, open) }
        pair(wide, { ReadinessPanel(d, it) { open("metric/readiness") } }, { SleepPanel(d, it) { open("metric/sleep") } })
        pair(wide, { RecoverySignals(d, it, open) }, { ActivityPanel(d, intraday, insights.report, prefs.stepGoal, it) { open("metric/steps") } })
        coverageHint(d)
        item { TilesHeader { open("insights") } }
        baselineTiles(d, insights.report, today, if (wide) 4 else 2, open)
        pair(wide, { TrainingPanel(d, it) { open("activities") } }, { LatestActivity(d, it, open) })
        item { YearPanel(year, today) { open("metric/heart") } }
    }
}

/** Two cards side by side on wide screens, stacked on phones. */
private fun LazyListScope.pair(wide: Boolean, a: @Composable (Modifier) -> Unit, b: @Composable (Modifier) -> Unit) {
    if (wide) item {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            a(Modifier.weight(1f).fillMaxHeight()); b(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        item { a(Modifier) }
        item { b(Modifier) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusRow(d: Dashboard) {
    val m = LocalMetrics.current
    val b = d.battery
    val pct = b.percent ?: b.lastPercent
    val left = if (b.percent != null && b.status != "stale") b.runtimeHours?.let { it * b.percent / 100 / 24 } else null
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip("Upload", Fmt.ago(d.lastReceived?.let { d.now - it }), m.battery)
        StatusChip("Battery", (pct?.let { "$it%" } ?: "—") + (left?.let { " · ~${Fmt.int(it)} d" } ?: "") +
            (if (b.status == "stale") " (stale)" else ""), m.battery)
        StatusChip("HR coverage", d.hrCoveragePct?.let { "${Fmt.dec(it, 0)}%" } ?: "—", m.heart)
    }
}

@Composable
private fun BriefingStrip(b: org.tdgs.ph.data.BriefingPage) {
    val m = LocalMetrics.current
    var open by remember { mutableStateOf(false) }
    PhCard(Modifier.animateContentSize(), onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("AI", m.readiness)
            Spacer(Modifier.width(10.dp))
            Text(b.headline ?: b.summary ?: "", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis)
            Text(if (open) "Less" else "More", style = MaterialTheme.typography.labelSmall, color = m.muted)
        }
        if (open) {
            b.summary?.takeIf { b.headline != null }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = m.muted, modifier = Modifier.padding(top = 8.dp)) }
            b.highlights.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            b.focus?.let { Note("Focus today: $it") }
            Note("Written by a language model from your computed numbers.")
        }
    }
}

@Composable
private fun TimelineCard(d: Dashboard, today: LocalDate, open: (String) -> Unit) {
    val m = LocalMetrics.current
    val zone = Fmt.zone(d.tz)
    val start = today.atStartOfDay(zone).toEpochSecond() - 6 * 3600
    val end = today.plusDays(1).atStartOfDay(zone).toEpochSecond()
    PhCard(onClick = { open("metric/heart") }) {
        CardHeader("Last night and today", Icons.Rounded.Timeline, m.heart, "18:00 → 24:00")
        Spacer(Modifier.height(10.dp))
        DayRibbon(start, end, d.now, d.tz, d.observations, d.nights, d.workouts, d.stressPoints, d.oxygenReadings, d.hrMax,
            onWorkout = { open("activity/${it.id}") })
        RibbonLegend()
    }
}

@Composable
private fun ReadinessPanel(d: Dashboard, modifier: Modifier, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val r = d.readinessDetail
    val focus = d.briefing.firstOrNull { it.key == "today" }?.takeUnless { d.briefingNeedsRefresh }?.focus
    PhCard(modifier, onClick) {
        CardHeader("Readiness", Icons.Rounded.Bolt, m.readiness,
            when { r.stockEstimate -> "Estimate"; r.provisional -> "Provisional"; else -> "Experimental" })
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Ring((r.score ?: 0) / 100f, m.readiness, size = 96.dp, stroke = 9.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(r.score?.toString() ?: "–", style = MaterialTheme.typography.headlineSmall)
                    Text(if (r.sleepOnly) "sleep only" else Fmt.readinessWord(r.score), style = MaterialTheme.typography.labelSmall, color = m.muted)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                r.components.forEach { c ->
                    ComponentMeter(COMPONENT_NAMES[c.key] ?: c.key.replace('_', ' '), c.score, m.readiness, c.detail, c.weight)
                }
            }
        }
        focus?.let { Note("Focus today: $it") } ?: r.reason?.let { Note(it) }
    }
}

val COMPONENT_NAMES = mapOf("hrv" to "HRV", "resting_hr" to "Resting heart rate", "sleep" to "Sleep", "training_load" to "Training load")

@Composable
private fun SleepPanel(d: Dashboard, modifier: Modifier, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val n = d.lastNight
    val zone = Fmt.zone(d.tz)
    val prior = d.nights.filter { it.night != d.today && it.onset != null && it.wake != null }.takeLast(14)
    fun minutes(ts: Long, evening: Boolean): Double {
        val t = java.time.Instant.ofEpochSecond(ts).atZone(zone).toLocalTime()
        val v = t.hour * 60 + t.minute
        return (if (evening && v < 12 * 60) v + 1440 else v).toDouble()
    }
    fun clockOf(v: Double?) = v?.let { "%02d:%02d".format((it.toInt() / 60) % 24, it.toInt() % 60) } ?: "—"
    val usualBed = Baselines.median(prior.map { minutes(it.onset!!, true) })
    val usualUp = Baselines.median(prior.map { minutes(it.wake!!, false) })
    PhCard(modifier, onClick) {
        CardHeader("Sleep", Icons.Rounded.Bedtime, m.sleep, d.sleep.score?.let { "Score $it · ${Fmt.sleepWord(it)}" })
        Spacer(Modifier.height(10.dp))
        if (n == null) {
            Text("No sleep recorded for last night yet", style = MaterialTheme.typography.bodyMedium, color = m.muted)
            return@PhCard
        }
        val need = d.sleep.needS
        BigValue(Fmt.hm(n.sleepS), sub = need?.let { val diff = n.sleepS - it; "${if (diff >= 0) "+" else "−"}${Fmt.hm(kotlin.math.abs(diff))} vs need of ${Fmt.hm(it)}" })
        StatRow(
            "Bed" to "${Fmt.clock(n.onset, d.tz)}",
            "Up" to "${Fmt.clock(n.wake, d.tz)}",
            "Restful" to (n.restfulS?.let { "${Fmt.int(it * 100.0 / n.sleepS.coerceAtLeast(1))}%" } ?: "—"),
        )
        Text("Usually ${clockOf(usualBed)} → ${clockOf(usualUp)}" +
            (d.sleepRegularitySdMin?.let { " · timing spread ±${Fmt.int(it)} min" } ?: ""),
            style = MaterialTheme.typography.labelSmall, color = m.muted, modifier = Modifier.padding(top = 6.dp))
        val last14 = d.nights.takeLast(14)
        if (last14.size >= 3) {
            BarChart(last14.map { Bar(Fmt.dayTiny(LocalDate.parse(it.night)), it.sleepS / 3600.0) }, m.sleep, Modifier.padding(top = 8.dp),
                goal = need?.let { it / 3600.0 }, height = 70.dp, valueText = { "${Fmt.dec(it)} h" }, labelEvery = 4,
                stacked = last14.map { it.restfulS?.div(3600.0) }, stackedColor = m.restful)
        }
        if (n.estimated) Note("Estimated from quiet minutes: the watch exported no complete sleep session for this night.")
    }
}

@Composable
private fun SignalRow(label: String, note: String?, value: String?, values: List<Double?>, color: Color,
                      band: Pair<Double, Double>? = null, onClick: () -> Unit) {
    val m = LocalMetrics.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = m.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (values.any { it != null }) TrendSpark(values, color, Modifier.width(76.dp).height(26.dp).padding(horizontal = 6.dp), band)
        Text(value ?: "—", style = MaterialTheme.typography.titleMedium, color = if (value != null) MaterialTheme.colorScheme.onSurface else m.muted,
            modifier = Modifier.width(78.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)
    }
}

@Composable
private fun RecoverySignals(d: Dashboard, modifier: Modifier, open: (String) -> Unit) {
    val m = LocalMetrics.current
    val today = LocalDate.parse(d.today)
    val span = (13 downTo 0).map { today.minusDays(it.toLong()).toString() }
    val hrv = d.hrvStatus
    val hrvBy = d.hrvNights.associateBy { it.night }
    val pulseBy = d.overnightPulse.associateBy { it.night }
    val sleepPulseBy = d.sleepPulse.associateBy { it.night }
    val oxBy = d.oxygenDays.associateBy { it.date }
    val stressBy = d.stressDays.associateBy { it.date }
    val ox = oxBy[d.today]
    val strainFresh = d.strainMeasuredAt?.let { d.now - it in 0..36 * 3600 } == true
    val stressNow = d.stress.score ?: d.stressToday
    PhCard(modifier) {
        CardHeader("Recovery signals", Icons.Rounded.MonitorHeart, m.readiness, "14 nights", chevron = false)
        Spacer(Modifier.height(4.dp))
        SignalRow("HRV last night",
            listOfNotNull(hrv.status?.let { HRV_WORDS[it] ?: it.replace('_', ' ') },
                if (hrv.lowMs != null && hrv.highMs != null) "usual ${Fmt.int(hrv.lowMs)}–${Fmt.int(hrv.highMs)}" else null,
                if (hrv.estimate) "estimate" else null).joinToString(" · "),
            hrv.lastNightMs?.let { "${Fmt.int(it)} ms" }, span.map { hrvBy[it]?.rmssdMs }, m.readiness,
            if (hrv.lowMs != null && hrv.highMs != null) hrv.lowMs to hrv.highMs else null) { open("metric/recovery") }
        SignalRow("Overnight pulse", if (d.stockMode) "stock estimate" else "spot pulse",
            d.overnightPulse.lastOrNull()?.value?.let { "${Fmt.dec(it)} bpm" }, span.map { pulseBy[it]?.value }, m.heart) { open("metric/recovery") }
        SignalRow("Pulse during sleep", null, sleepPulseBy[d.today]?.value?.let { "${Fmt.dec(it)} bpm" },
            span.map { sleepPulseBy[it]?.value }, m.heart) { open("metric/recovery") }
        SignalRow("SpO₂ overnight", ox?.let { "low ${Fmt.int(it.min)}% · ${it.count} readings" } ?: "no readings today",
            ox?.mean?.let { "${Fmt.dec(it)}%" }, span.map { oxBy[it]?.mean }, m.oxygen) { open("metric/oxygen") }
        if (d.stockMode) SignalRow("Overnight strain", "estimate, not mental stress",
            d.strainScore?.takeIf { strainFresh }?.let { "$it / 100" }, emptyList(), m.stress) { open("metric/recovery") }
        SignalRow("Daytime stress", "quiet-pulse estimate", stressNow?.let { "$it / 100" },
            span.map { stressBy[it]?.score?.toDouble() }, m.stress) { open("metric/stress") }
    }
}

val HRV_WORDS = mapOf("balanced" to "Balanced", "unbalanced_low" to "Unbalanced, low", "unbalanced_high" to "Unbalanced, high",
    "low" to "Low", "insufficient" to "Not enough nights", "baseline_building" to "Building baseline")

@Composable
private fun ActivityPanel(d: Dashboard, intraday: Intraday?, report: InsightsReport?, goal: Int, modifier: Modifier, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val steps = d.stepsToday ?: d.todayDay?.steps
    val usual = report?.metrics?.firstOrNull { it.key == "steps" }?.baselineMedian
    val hourly = intraday?.let { i -> (0 until 24).map { h -> i.points.filter { p -> (p.ts - i.start) / 3600 == h.toLong() }.sumOf { it.steps ?: 0 }.toDouble() } }
    PhCard(modifier, onClick) {
        CardHeader("Activity today", Icons.AutoMirrored.Rounded.DirectionsWalk, m.steps, "Goal ${Fmt.int(goal)}")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Ring((steps ?: 0) / goal.toFloat(), m.steps, size = 96.dp, stroke = 9.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Fmt.int(steps), style = MaterialTheme.typography.titleLarge)
                    Text("${(steps ?: 0) * 100 / goal}% of goal", style = MaterialTheme.typography.labelSmall, color = m.muted)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                usual?.let { Text("${Fmt.int((steps ?: 0) * 100.0 / it)}% of your usual ${Fmt.int(it)}", style = MaterialTheme.typography.bodySmall, color = m.muted) }
                Text("${Fmt.km(d.distanceTodayM)} · ${Fmt.hm(d.activeSecondsToday)} active", style = MaterialTheme.typography.bodySmall)
                Text("${Fmt.int((d.activeKcalToday ?: 0.0) + (d.restingKcalToday ?: 0.0))} kcal (${Fmt.int(d.activeKcalToday)} active)",
                    style = MaterialTheme.typography.bodySmall)
                val goalMin = d.intensityGoal ?: 150.0
                Text("Intensity ${Fmt.int(d.intensityWeek)} / ${Fmt.int(goalMin)} min this week", style = MaterialTheme.typography.bodySmall)
                ProgressBar(((d.intensityWeek ?: 0.0) / goalMin).toFloat(), m.intensity)
            }
        }
        if (hourly != null && hourly.any { it > 0 }) {
            Text("Steps per hour", style = MaterialTheme.typography.labelSmall, color = m.muted, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Sparkline(hourly, m.steps, Modifier.fillMaxWidth().height(40.dp), bars = true)
        }
    }
}

@Composable
private fun TilesHeader(onAll: () -> Unit) {
    val m = LocalMetrics.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Compared with your usual", style = MaterialTheme.typography.titleMedium)
            Text("Last 7 days against the 4 weeks before; band = your usual range", style = MaterialTheme.typography.labelSmall, color = m.muted)
        }
        Text("Patterns", style = MaterialTheme.typography.labelSmall, color = m.readiness, modifier = Modifier.clickable(onClick = onAll).padding(8.dp))
    }
}

private data class TileDef(val key: String, val label: String, val color: Color, val route: String, val good: Int, val unit: String,
                           val insight: String?, val estimate: Boolean, val format: (Double) -> String, val value: (String) -> Double?, val endsYesterday: Boolean)

private fun LazyListScope.baselineTiles(d: Dashboard, report: InsightsReport?, today: LocalDate, perRow: Int, open: (String) -> Unit) {
    val daily = d.days.associateBy { it.date }
    val nights = d.nights.associateBy { it.night }
    val hrv = d.hrvNights.associateBy { it.night }
    val pulse = d.overnightPulse.associateBy { it.night }
    val weight = d.weight.associateBy { it.date }
    val ox = d.oxygenDays.associateBy { it.date }
    val stress = d.stressDays.associateBy { it.date }
    item {
        val m = LocalMetrics.current
        val defs = listOf(
            TileDef("steps", "Steps", m.steps, "metric/steps", 1, "", "steps", false, { Fmt.int(it) }, { daily[it]?.steps?.toDouble() }, true),
            TileDef("sleep", "Sleep", m.sleep, "metric/sleep", 1, "", "sleep_minutes", false, { Fmt.hm((it * 60).toInt()) }, { nights[it]?.sleepS?.div(60.0) }, false),
            TileDef("hrv", "HRV", m.readiness, "metric/recovery", 1, "ms", "rmssd_ms", true, { Fmt.int(it) }, { hrv[it]?.rmssdMs }, false),
            TileDef("pulse", "Overnight pulse", m.heart, "metric/recovery", -1, "bpm", "overnight_pulse", true, { Fmt.dec(it) }, { pulse[it]?.value }, false),
            TileDef("active", "Active minutes", m.intensity, "metric/intensity", 1, "min", "active_minutes", false, { Fmt.int(it) }, { daily[it]?.activeMinutes?.toDouble() }, true),
            TileDef("weight", "Weight", m.weight, "metric/weight", 0, "kg", "weight_kg", false, { Fmt.dec(it) }, { weight[it]?.kg }, false),
            TileDef("spo2", "SpO₂", m.oxygen, "metric/oxygen", 1, "%", null, true, { Fmt.dec(it) }, { ox[it]?.mean }, false),
            TileDef("stress", "Daytime stress", m.stress, "metric/stress", -1, "/100", null, true, { Fmt.int(it) }, { stress[it]?.score?.toDouble() }, false),
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            defs.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { def ->
                        val end = if (def.endsYesterday) today.minusDays(1) else today
                        val series = (34 downTo 0).map { def.value(end.minusDays(it.toLong()).toString()) }
                        val fromServer = def.insight?.let { k -> report?.metrics?.firstOrNull { it.key == k } }
                            ?.takeIf { it.recentMedian != null || it.baselineMedian != null }
                        val b = fromServer?.let(Baselines::fromInsight) ?: Baselines.compute(series)
                        BaselineTile(def, b, series.takeLast(28), Modifier.weight(1f).fillMaxHeight()) { open(def.route) }
                    }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun BaselineTile(def: TileDef, b: Baseline, series: List<Double?>, modifier: Modifier, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val tone = Baselines.tone(b, def.good)
    val toneColor = when (tone) { Tone.UP -> m.steps; Tone.DOWN -> m.stress; else -> m.muted }
    PhCard(modifier, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(def.label, style = MaterialTheme.typography.labelSmall, color = m.muted, modifier = Modifier.weight(1f), maxLines = 1)
            if (def.estimate) Text("est.", style = MaterialTheme.typography.labelSmall, color = m.muted)
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
            Text(b.recent?.let(def.format) ?: "—", style = MaterialTheme.typography.titleLarge, color = def.color.takeIf { b.recent != null } ?: m.muted)
            if (def.unit.isNotEmpty() && b.recent != null) Text(" ${def.unit}", style = MaterialTheme.typography.labelSmall, color = m.muted,
                modifier = Modifier.padding(bottom = 3.dp))
        }
        val diff = b.diff
        Text(
            if (diff == null) "baseline building · ${b.baseDays} days"
            else "${if (diff > 0) "↑" else if (diff < 0) "↓" else "→"} ${def.format(kotlin.math.abs(diff))}${if (def.unit.isNotEmpty() && def.unit != "/100") " ${def.unit}" else ""} · ${if (b.outside) "outside usual" else "within usual"}",
            style = MaterialTheme.typography.labelSmall, color = toneColor, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val band = if (b.low != null && b.high != null) b.low to b.high else null
        if (series.any { it != null }) TrendSpark(series, def.color, Modifier.fillMaxWidth().height(30.dp).padding(top = 6.dp), band)
    }
}

@Composable
private fun TrainingPanel(d: Dashboard, modifier: Modifier, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val week = sessions(d.workouts.filter { !it.ignored && it.start >= d.now - 7 * 86400 })
    PhCard(modifier, onClick) {
        CardHeader("Training", Icons.Rounded.FitnessCenter, m.intensity, if (d.trainingStatus != "ready") "Building baseline" else null)
        StatRow("Sessions" to "${week.size}", "Time" to Fmt.hm(week.sumOf { it.seconds }),
            "Intensity" to "${Fmt.int(d.intensityWeek)} min")
        val days = d.intensityDays.takeLast(7)
        if (days.isNotEmpty()) {
            BarChart(days.map { Bar(Fmt.weekday(LocalDate.parse(it.date)), it.total) }, m.intensity, Modifier.padding(top = 8.dp),
                height = 60.dp, valueText = { "${Fmt.int(it)} min" })
        }
        if (d.trainingStatus == "ready") StatRow("Acute" to Fmt.int(d.acuteLoad), "Chronic" to Fmt.int(d.chronicLoad), "VO₂max" to Fmt.dec(d.vo2max))
        else Note("Training load needs 42 confirmed days. VO₂max estimate ${Fmt.dec(d.vo2max)}.")
    }
}

/** Phone and watch often record the same session; overlapping recordings count once, preferring the phone one. */
fun sessions(ws: List<Workout>): List<Workout> {
    val groups = mutableListOf<MutableList<Workout>>()
    var groupEnd = Long.MIN_VALUE
    ws.sortedBy { it.start }.forEach { w ->
        if (groups.isNotEmpty() && w.start < groupEnd) {
            groups.last().add(w); groupEnd = maxOf(groupEnd, w.start + w.seconds)
        } else {
            groups.add(mutableListOf(w)); groupEnd = w.start + w.seconds
        }
    }
    return groups.map { g -> g.firstOrNull { it.id.startsWith("phone:") } ?: g.first() }
}

@Composable
private fun LatestActivity(d: Dashboard, modifier: Modifier, open: (String) -> Unit) {
    val m = LocalMetrics.current
    val all = sessions(d.workouts.filter { !it.ignored && it.start <= d.now }).sortedByDescending { it.start }
    val w = all.firstOrNull()
    PhCard(modifier, onClick = { if (w != null) open("activity/${w.id}") else open("activities") }) {
        CardHeader("Latest activity", Icons.Rounded.Favorite, m.intensity, w?.let { Fmt.dateTime(it.start, d.tz) })
        Spacer(Modifier.height(8.dp))
        if (w == null) { Text("No workouts yet", style = MaterialTheme.typography.bodyMedium, color = m.muted); return@PhCard }
        BigValue(Fmt.kind(w.kind), sub = listOfNotNull(Fmt.hm(w.activeSeconds ?: w.seconds), w.distanceM?.let { Fmt.km(it) },
            w.rpe?.let { "effort ${Fmt.int(it)}/10" }).joinToString(" · "))
        val hr = d.observations.filter { it.hr != null && it.ts in w.start..(w.start + w.seconds) }.map { it.hr!!.toDouble() }
        if (hr.size > 5) {
            Sparkline(hr, m.heart, Modifier.fillMaxWidth().height(36.dp).padding(top = 8.dp))
            Text("Avg ${Fmt.int(hr.average())} · max ${Fmt.int(hr.max())} bpm", style = MaterialTheme.typography.labelSmall, color = m.muted)
        }
        all.drop(1).take(3).forEach { o ->
            Row(Modifier.fillMaxWidth().clickable { open("activity/${o.id}") }.padding(top = 6.dp)) {
                Text(Fmt.kind(o.kind), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text("${Fmt.dateTime(o.start, d.tz)} · ${Fmt.hm(o.seconds)}", style = MaterialTheme.typography.labelSmall, color = m.muted)
            }
        }
    }
}

@Composable
private fun YearPanel(year: List<DaySummary>?, today: LocalDate, onClick: () -> Unit) {
    val m = LocalMetrics.current
    var metric by remember { mutableIntStateOf(0) }
    val colors = listOf(m.steps, m.intensity, m.heart)
    val pick: (DaySummary) -> Double? = when (metric) {
        1 -> { s -> s.activeMinutes?.toDouble() }
        2 -> { s -> s.restingHr?.toDouble() }
        else -> { s -> s.steps?.toDouble() }
    }
    PhCard {
        CardHeader("Your year", Icons.Rounded.CalendarMonth, colors[metric], chevron = false)
        Spacer(Modifier.height(8.dp))
        Segmented(listOf("Steps", "Active min", "Resting HR"), metric, { metric = it })
        Spacer(Modifier.height(10.dp))
        if (year == null) { Loading(80); return@PhCard }
        val byDate = year.associateBy { it.date }
        val days = (364 downTo 0).map { today.minusDays(it.toLong()) }.map { it to byDate[it.toString()]?.let(pick) }
        Heatmap(days, colors[metric], onPick = { onClick() })
        val vals = days.mapNotNull { it.second }
        StatRow("Days recorded" to "${vals.size}", "Average" to Fmt.int(vals.average().takeIf { vals.isNotEmpty() }),
            (if (metric == 2) "Lowest" else "Best") to Fmt.int(if (metric == 2) vals.minOrNull() else vals.maxOrNull()))
        Note("Darker is higher, relative to your own year. Empty days had no recorded minutes.")
    }
}

private fun LazyListScope.coverageHint(d: Dashboard) {
    val pct = d.hrCoveragePct ?: return
    if (pct >= 50) return
    item {
        val m = LocalMetrics.current
        PhCard {
            CardHeader("Heart rate coverage", Icons.Rounded.Info, m.stress, "${Fmt.dec(pct, 0)}% of minutes", chevron = false)
            Text(
                "Only ${Fmt.dec(pct, 0)}% of today's minutes carry a heart-rate reading. On the watch, open Settings → Health → " +
                    "Heart rate monitoring and choose the most frequent interval; scores and stress need continuous readings.",
                style = MaterialTheme.typography.bodySmall, color = m.muted, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
