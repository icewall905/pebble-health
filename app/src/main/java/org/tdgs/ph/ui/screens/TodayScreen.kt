package org.tdgs.ph.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Info
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.tdgs.ph.data.Dashboard
import org.tdgs.ph.data.Intraday
import org.tdgs.ph.data.Prefs
import org.tdgs.ph.data.Repository
import org.tdgs.ph.ui.Fmt
import org.tdgs.ph.ui.components.BigValue
import org.tdgs.ph.ui.components.Note
import org.tdgs.ph.ui.components.CardHeader
import org.tdgs.ph.ui.components.PhCard
import org.tdgs.ph.ui.components.Pill
import org.tdgs.ph.ui.components.Ring
import org.tdgs.ph.ui.components.Sparkline
import org.tdgs.ph.ui.components.Stat
import org.tdgs.ph.ui.components.StatRow
import org.tdgs.ph.ui.components.SleepTimeline
import org.tdgs.ph.ui.theme.LocalMetrics
import java.time.LocalDate

@Composable
fun TodayScreen(repo: Repository, open: (String) -> Unit) {
    val state by repo.state.collectAsState()
    val prefs by repo.settings.prefs.collectAsState(initial = Prefs(8000, 8, "system"))
    val d = state.dashboard
    var intraday by remember { mutableStateOf<Intraday?>(null) }
    LaunchedEffect(d?.today, state.fetchedAt) {
        val day = d?.today?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@LaunchedEffect
        repo.intraday(day).onSuccess { intraday = it }
    }
    val title = d?.today?.let { runCatching { Fmt.dayLong(LocalDate.parse(it)) }.getOrNull() } ?: "Today"
    val sub = d?.let { "Synced ${Fmt.ago(it.lastReceived?.let { r -> it.now - r })}" + (it.wearer?.let { w -> " · $w" } ?: "") }
    Page(repo, title, sub) {
        if (d == null) {
            item { if (state.loading) Loading() else Empty(state.error ?: "Pull to load your data") }
            return@Page
        }
        item { StepsHero(d, prefs.stepGoal) { open("metric/steps") } }
        item {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReadinessCard(d, Modifier.weight(1f).fillMaxHeight()) { open("metric/readiness") }
                StressCard(d, Modifier.weight(1f).fillMaxHeight()) { open("metric/stress") }
            }
        }
        item { SleepCard(d) { open("metric/sleep") } }
        item { HeartCard(d, intraday) { open("metric/heart") } }
        coverageHint(d)
        item {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IntensityCard(d, Modifier.weight(1f).fillMaxHeight()) { open("metric/intensity") }
                EnergyCard(d, Modifier.weight(1f).fillMaxHeight()) { open("metric/energy") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OxygenCard(d, Modifier.weight(1f).fillMaxHeight()) { open("metric/oxygen") }
                BatteryCard(d, Modifier.weight(1f).fillMaxHeight()) { open("metric/battery") }
            }
        }
        if (d.weight.isNotEmpty()) item { WeightCard(d) { open("metric/weight") } }
        d.workouts.firstOrNull()?.let { w ->
            item {
                val m = LocalMetrics.current
                PhCard(onClick = { open("activities") }) {
                    CardHeader("Last activity", Icons.AutoMirrored.Rounded.DirectionsWalk, m.steps, Fmt.dateTime(w.start, d.tz))
                    Spacer(Modifier.height(10.dp))
                    BigValue(Fmt.hm(w.seconds), sub = Fmt.kind(w.kind))
                }
            }
        }
        d.briefing.firstOrNull()?.let { b ->
            item {
                val m = LocalMetrics.current
                PhCard(onClick = { open("insights") }) {
                    CardHeader("Insight", Icons.Rounded.AutoAwesome, m.readiness, d.briefingDay)
                    Spacer(Modifier.height(8.dp))
                    b.headline?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    b.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = m.muted, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.coverageHint(d: Dashboard) {
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

@Composable
private fun StepsHero(d: Dashboard, goal: Int, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val steps = d.stepsToday ?: d.todayDay?.steps
    PhCard(onClick = onClick) {
        CardHeader("Steps", Icons.AutoMirrored.Rounded.DirectionsWalk, m.steps, "Goal ${Fmt.int(goal)}")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Ring((steps ?: 0) / goal.toFloat(), m.steps, size = 128.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Fmt.int(steps), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("${((steps ?: 0) * 100 / goal)}%", style = MaterialTheme.typography.bodySmall, color = m.muted)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("Distance", Fmt.km(d.distanceTodayM))
                Stat("Active", Fmt.hm(d.activeSecondsToday))
                Stat("Calories", "${Fmt.int((d.activeKcalToday ?: 0.0) + (d.restingKcalToday ?: 0.0))} kcal")
            }
        }
        val week = d.days.takeLast(7).map { it.steps?.toDouble() }
        if (week.any { it != null }) {
            Text("Last 7 days", style = MaterialTheme.typography.labelSmall, color = m.muted, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Sparkline(week, m.steps, Modifier.fillMaxWidth().height(36.dp), bars = true)
        }
    }
}

@Composable
private fun ReadinessCard(d: Dashboard, modifier: Modifier, onClick: () -> Unit) {
    val m = LocalMetrics.current
    PhCard(modifier, onClick) {
        CardHeader("Readiness", Icons.Rounded.Bolt, m.readiness, chevron = false)
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Ring((d.readiness.score ?: 0) / 100f, m.readiness, size = 64.dp, stroke = 7.dp) {
                Text(d.readiness.score?.toString() ?: "–", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(10.dp))
            Text(Fmt.readinessWord(d.readiness.score), style = MaterialTheme.typography.bodySmall, color = m.muted)
        }
    }
}

@Composable
private fun StressCard(d: Dashboard, modifier: Modifier, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val latest = d.stress.score ?: d.stressPoints.lastOrNull { it.score != null }?.score
    PhCard(modifier, onClick) {
        CardHeader("Stress", Icons.Rounded.SelfImprovement, m.stress, chevron = false)
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Ring((latest ?: 0) / 100f, m.stress, size = 64.dp, stroke = 7.dp) {
                Text(latest?.toString() ?: "–", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(10.dp))
            Text(Fmt.stressWord(latest), style = MaterialTheme.typography.bodySmall, color = m.muted)
        }
    }
}

@Composable
private fun SleepCard(d: Dashboard, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val n = d.lastNight
    PhCard(onClick = onClick) {
        CardHeader("Sleep", Icons.Rounded.Bedtime, m.sleep, d.sleep.score?.let { "Score $it · ${Fmt.sleepWord(it)}" })
        Spacer(Modifier.height(10.dp))
        if (n == null) {
            Text("No sleep recorded for last night yet", style = MaterialTheme.typography.bodyMedium, color = m.muted)
            return@PhCard
        }
        BigValue(Fmt.hm(n.sleepS), sub = "${Fmt.clock(n.onset, d.tz)} – ${Fmt.clock(n.wake, d.tz)}")
        if (n.onset != null && n.wake != null) {
            SleepTimeline(n.onset, n.wake, n.sleepIntervals, n.restfulIntervals, Modifier.padding(top = 12.dp), height = 28.dp)
        }
        StatRow("Restful" to Fmt.hm(n.restfulS), "Light" to Fmt.hm(n.restfulS?.let { (n.sleepS - it).coerceAtLeast(0) }),
            "Regularity" to (d.sleep.regularity?.let { "$it" } ?: "—"))
        if (n.estimated) Note("Estimated from quiet minutes: the watch exported no complete sleep session for this night.")
    }
}

@Composable
private fun HeartCard(d: Dashboard, intraday: Intraday?, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val today = d.todayDay
    PhCard(onClick = onClick) {
        CardHeader("Heart rate", Icons.Rounded.Favorite, m.heart, d.latestHrAt?.let { "Last ${Fmt.clock(it, d.tz)}" })
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            BigValue(d.latestHr?.toString() ?: "—", "bpm")
            Spacer(Modifier.weight(1f))
            Pill("Resting ${Fmt.int(d.restingHr)}", m.heart)
        }
        val pts = intraday?.points?.mapNotNull { p -> p.hr?.toDouble() }.orEmpty()
        if (pts.size >= 3) Sparkline(pts, m.heart, Modifier.fillMaxWidth().height(44.dp).padding(top = 10.dp))
        StatRow("Low" to Fmt.int(today?.hrMin), "Avg" to Fmt.int(today?.hrAvg), "High" to Fmt.int(today?.hrMax))
    }
}

@Composable
private fun IntensityCard(d: Dashboard, modifier: Modifier, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val goal = d.intensityGoal ?: 150.0
    PhCard(modifier, onClick) {
        CardHeader("Intensity", Icons.Rounded.Speed, m.intensity, chevron = false)
        Spacer(Modifier.height(10.dp))
        BigValue(Fmt.int(d.intensityWeek), "min", "of ${Fmt.int(goal)} this week")
    }
}

@Composable
private fun EnergyCard(d: Dashboard, modifier: Modifier, onClick: () -> Unit) {
    val m = LocalMetrics.current
    PhCard(modifier, onClick) {
        CardHeader("Active kcal", Icons.Rounded.LocalFireDepartment, m.energy, chevron = false)
        Spacer(Modifier.height(10.dp))
        BigValue(Fmt.int(d.activeKcalToday), "kcal", "Resting ${Fmt.int(d.restingKcalToday)}")
    }
}

@Composable
private fun OxygenCard(d: Dashboard, modifier: Modifier, onClick: () -> Unit) {
    val m = LocalMetrics.current
    PhCard(modifier, onClick) {
        CardHeader("Pulse Ox", Icons.Rounded.Air, m.oxygen, chevron = false)
        Spacer(Modifier.height(10.dp))
        BigValue(d.oxygenLatest?.percent?.let { Fmt.int(it) } ?: "—", "%", d.oxygenLatest?.ts?.let { Fmt.dateTime(it, d.tz) })
    }
}

@Composable
private fun BatteryCard(d: Dashboard, modifier: Modifier, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val b = d.battery
    PhCard(modifier, onClick) {
        CardHeader("Watch", Icons.Rounded.Battery5Bar, m.battery, chevron = false)
        Spacer(Modifier.height(10.dp))
        BigValue((b.percent ?: b.lastPercent)?.toString() ?: "—", "%",
            b.runtimeHours?.let { "≈ ${Fmt.int(it / 24)} days per charge" } ?: b.status)
    }
}

@Composable
private fun WeightCard(d: Dashboard, onClick: () -> Unit) {
    val m = LocalMetrics.current
    val last = d.weight.last()
    PhCard(onClick = onClick) {
        CardHeader("Weight", Icons.Rounded.MonitorWeight, m.weight, last.date)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            BigValue(Fmt.dec(last.kg), "kg", last.trendKg?.let { "Trend ${Fmt.dec(it)} kg" })
            Spacer(Modifier.weight(1f))
            d.weightChange7d?.let { Pill("${if (it >= 0) "+" else ""}${Fmt.dec(it)} kg / 7d", m.weight) }
        }
        Sparkline(d.weight.takeLast(30).map { it.trendKg ?: it.kg }, m.weight, Modifier.fillMaxWidth().height(40.dp).padding(top = 10.dp))
    }
}
