package org.tdgs.ph.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.tdgs.ph.data.BriefingPage
import org.tdgs.ph.data.InsightMetric
import org.tdgs.ph.data.InsightsReport
import org.tdgs.ph.data.Repository
import org.tdgs.ph.ui.Fmt
import org.tdgs.ph.ui.components.Bar
import org.tdgs.ph.ui.components.BarChart
import org.tdgs.ph.ui.components.CardHeader
import org.tdgs.ph.ui.components.Note
import org.tdgs.ph.ui.components.PhCard
import org.tdgs.ph.ui.components.Pill
import org.tdgs.ph.ui.components.Segmented
import org.tdgs.ph.ui.components.StatRow
import org.tdgs.ph.ui.theme.LocalMetrics
import java.time.LocalDate

@Composable
fun InsightsScreen(repo: Repository, open: (String) -> Unit) {
    val state by repo.state.collectAsState()
    val insights by repo.insights.collectAsState()
    val dashboard = state.dashboard
    val report = insights.report
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var expandedMetric by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.fetchedAt) { repo.refreshInsights() }
    Page(repo, "Insights") {
        item { Segmented(listOf("Patterns", "AI briefing"), tab, { tab = it }) }
        if (tab == 0) {
            insights.error?.let { message ->
                item {
                    PhCard {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        if (report != null) Note("Showing saved patterns through ${insightDate(report.through)}.")
                        TextButton(onClick = { scope.launch { repo.refreshInsights() } }) { Text("Retry") }
                    }
                }
            }
            if (report == null) {
                if (insights.error == null) item { Loading() }
                return@Page
            }
            item { CoverageCard(report) }
            item { ActivityPlanningCard(report) }
            item {
                PhCard {
                    CardHeader("Personal baselines", Icons.Rounded.Timeline, LocalMetrics.current.steps, chevron = false)
                    Note("Compare the last 7 completed days with the preceding 28. Step and active-minute totals require at least 75% recording coverage for that field.")
                    report.metrics.forEach { metric ->
                        HorizontalDivider(Modifier.padding(top = 10.dp))
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { expandedMetric = metric.key.takeUnless { it == expandedMetric } },
                        ) {
                            val action = if (expandedMetric == metric.key) "Hide" else "View"
                            Text("$action ${metric.label.lowercase()}")
                        }
                        if (expandedMetric == metric.key) MetricComparison(report, metric)
                    }
                }
            }
            item { AssociationsCard(report) }
            if (report.actions.isNotEmpty()) item { ActionsCard(report, open) }
        } else {
            if (dashboard == null) {
                item { if (state.loading) Loading() else Empty(state.error ?: "Pull to load your briefing.") }
                return@Page
            }
            val briefingDay = dashboard.briefingDay
            if (dashboard.briefingNeedsRefresh) {
                item { Empty("The hub has updated its briefing rules. The next scheduled briefing will use the new analysis.") }
                return@Page
            }
            if (dashboard.briefing.isEmpty()) {
                item { Empty("No briefing yet. Your hub writes one when a model is configured.") }
                return@Page
            }
            if (briefingDay != null) item {
                val label = if (briefingDay == dashboard.today) "Briefing for" else "Saved briefing for"
                Text("$label ${insightDate(briefingDay)}", style = MaterialTheme.typography.bodySmall,
                    color = LocalMetrics.current.muted, modifier = Modifier.padding(horizontal = 4.dp))
            }
            if (dashboard.patternsBriefingStatus == "error") item {
                Text("The hub’s Patterns briefing was unavailable on this run. Recorded patterns are available in the Patterns tab.",
                    style = MaterialTheme.typography.bodySmall, color = LocalMetrics.current.muted,
                    modifier = Modifier.padding(horizontal = 4.dp))
            }
            items(dashboard.briefing.sortedBy { it.key != "patterns" }, key = { it.key }) { page -> BriefingCard(page) }
        }
    }
}

@Composable
private fun CoverageCard(report: InsightsReport) {
    val m = LocalMetrics.current
    val coverage = report.coverage
    PhCard {
        CardHeader("Recording coverage", Icons.Rounded.Info, m.stress, chevron = false)
        Note("Last 7 completed days through ${insightDate(report.through)}.")
        StatRow(
            "Minute coverage" to (coverage.minutePercent?.let { "${Fmt.dec(it, 0)}%" } ?: "—"),
            "Usable step days" to (coverage.stepDays?.let { "$it / 7" } ?: "—"),
        )
        StatRow(
            "Recorded nights" to (coverage.sleepNights?.let { "$it / 7" } ?: "—"),
            "Confirmed training" to (coverage.trainingDays?.let { "$it / 7" } ?: "—"),
        )
    }
}

@Composable
private fun ActivityPlanningCard(report: InsightsReport) {
    val m = LocalMetrics.current
    PhCard {
        CardHeader("Activity planning", Icons.Rounded.Speed, m.intensity, chevron = false)
        report.stepEstimate?.date?.let { Note("Plan as of ${insightDate(it)}.") }
        val plan = report.intensityPlan
        if (plan != null) {
            StatRow("Weekly intensity" to "${Fmt.int(plan.observed)} / ${Fmt.int(plan.target)} min",
                "Left to record" to "${Fmt.int(plan.remaining)} min")
            if (plan.remaining > 0) {
                val days = if (plan.daysLeft == 1) "the final day" else "the remaining ${plan.daysLeft} days"
                Note("An even split over $days through Sunday is ${plan.minutesPerDay} intensity min/day.")
            }
        } else {
            Note("Set a maximum heart rate on your hub to enable weekly intensity planning.")
        }
        val forecast = report.stepEstimate
        if (forecast?.status == "available" && forecast.steps != null) {
            Note("Estimated steps with typical recording coverage: ${Fmt.int(forecast.steps)}.")
            forecast.typicalError?.let { error ->
                Note("The typical error was ${Fmt.int(error)} steps across ${forecast.replayDays} historical test days.")
            }
        } else if (forecast?.status == "no_skill_over_persistence") {
            Note("The step model did not improve on the last eligible day in historical testing.")
        } else {
            Note("A step estimate needs more days with adequate coverage.")
        }
    }
}

@Composable
private fun MetricComparison(report: InsightsReport, metric: InsightMetric) {
    val m = LocalMetrics.current
    StatRow(
        "Recent median · ${metric.recentDays} days" to metricValue(metric.recentMedian, metric.unit),
        "Baseline · ${metric.baselineDays} days" to metricValue(metric.baselineMedian, metric.unit),
    )
    metric.baselineRange?.let { (low, high) ->
        Note("Middle 80% of baseline readings: ${metricValue(low, metric.unit)} to ${metricValue(high, metric.unit)}.")
    }
    metric.latest?.let { reading -> Note("Latest usable reading: ${metricValue(reading.value, metric.unit)} on ${insightDate(reading.date)}.") }
    if (report.days.any { metric.key in it.values }) {
        Note("Tap a bar to inspect one of the last ${report.windowDays} completed days.")
        BarChart(
            report.days.map { day -> Bar(insightDate(day.date, compact = true), day.values[metric.key]) },
            m.steps, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), height = 92.dp,
            valueText = { metricValue(it, metric.unit) }, labelEvery = 10,
        )
    } else {
        Note("There are no usable observations in this window.")
    }
}

@Composable
private fun AssociationsCard(report: InsightsReport) {
    val labels = report.metrics.associate { it.key to it.label }
    val links = report.associations.filter { it.status == "exploratory_pattern" && it.correlation != null }
    PhCard {
        CardHeader("Links between metrics", Icons.Rounded.Timeline, LocalMetrics.current.sleep, chevron = false)
        if (links.isEmpty()) {
            Note("No relationship met the sample and consistency checks.")
        }
        links.forEach { link ->
            val predictor = labels[link.predictor] ?: link.predictor
            val outcome = labels[link.outcome]?.lowercase() ?: link.outcome
            val timing = if (link.lag > 0) "on the next date" else "on the same date"
            val direction = if ((link.correlation ?: 0.0) > 0) "varied together" else "varied in opposite directions"
            Note("$predictor and $outcome $timing $direction across ${link.pairs} paired dates.")
        }
        if (links.isNotEmpty()) Note("These are exploratory associations. Recording gaps and other factors can affect them.")
    }
}

@Composable
private fun ActionsCard(report: InsightsReport, open: (String) -> Unit) {
    PhCard {
        Text("Data and goals to review", style = MaterialTheme.typography.titleSmall)
        report.actions.forEach { action ->
            when (action) {
                "check_sync" -> TextButton(onClick = { open("settings") }) { Text("Check watch and phone sync") }
                "review_step_coverage" -> Note("Check watch wear and sync before comparing step totals.")
                "review_training_days" -> Note("Review unconfirmed training days in the hub’s Training page.")
                "review_weekly_goal" -> TextButton(onClick = { open("metric/intensity") }) { Text("Open intensity history") }
            }
        }
    }
}

@Composable
private fun BriefingCard(page: BriefingPage) {
    val m = LocalMetrics.current
    val color = when (page.key) {
        "sleep" -> m.sleep
        "recovery" -> m.readiness
        "training" -> m.intensity
        "more" -> m.battery
        else -> m.steps
    }
    PhCard {
        val label = if (page.key == "more") "Collection" else page.key.replaceFirstChar { it.uppercase() }
        Pill(label, color)
        Spacer(Modifier.height(8.dp))
        page.headline?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        page.summary?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = m.muted, modifier = Modifier.padding(top = 6.dp))
        }
        page.highlights.forEach { highlight ->
            Row(Modifier.padding(top = 6.dp)) {
                Text("•", color = color)
                Spacer(Modifier.width(8.dp))
                Text(highlight, style = MaterialTheme.typography.bodyMedium)
            }
        }
        page.focus?.let {
            Text("Focus", style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(top = 12.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        page.dataNotes.forEach { Note(it) }
    }
}

private fun insightDate(value: String, compact: Boolean = false): String =
    runCatching { LocalDate.parse(value).let { if (compact) Fmt.dayTiny(it) else Fmt.dayShort(it) } }.getOrDefault(value)

private fun metricValue(value: Double?, unit: String): String {
    if (value == null) return "—"
    val display = if (value % 1 == 0.0) Fmt.int(value) else Fmt.dec(value)
    return "$display $unit".trimEnd()
}
