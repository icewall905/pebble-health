package org.tdgs.ph.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tdgs.ph.data.Night
import org.tdgs.ph.data.Observation
import org.tdgs.ph.data.OxygenReading
import org.tdgs.ph.data.StressPoint
import org.tdgs.ph.data.Workout
import org.tdgs.ph.ui.Fmt
import org.tdgs.ph.ui.theme.LocalMetrics
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private fun DrawScope.label(tm: TextMeasurer, text: String, x: Float, y: Float, color: Color, size: Float = 10f,
                            bold: Boolean = false, alignEnd: Boolean = false, center: Boolean = false) {
    val layout = tm.measure(text, TextStyle(fontSize = size.sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
    val dx = when { alignEnd -> layout.size.width.toFloat(); center -> layout.size.width / 2f; else -> 0f }
    drawText(layout, topLeft = Offset(x - dx, y - layout.size.height / 2f))
}

/**
 * Swim-lane view of a stretch of time: sleep (asleep / restful / unrecorded), workouts,
 * minute heart rate over moderate and vigorous zone bands, steps per 15 minutes, the
 * stress estimate and SpO2 spot readings. Tap or drag to read every lane at one moment.
 * Gaps stay gaps: nothing is interpolated across missing readings.
 */
@Composable
fun DayRibbon(
    start: Long, end: Long, now: Long, tz: String,
    observations: List<Observation>, nights: List<Night>, workouts: List<Workout>,
    stress: List<StressPoint>, spo2: List<OxygenReading>, hrMax: Int?,
    modifier: Modifier = Modifier, hrHeight: Dp = 110.dp, onWorkout: ((Workout) -> Unit)? = null,
) {
    val m = LocalMetrics.current
    val tm = rememberTextMeasurer()
    var selected by remember(start, end) { mutableStateOf<Long?>(null) }
    val hr = remember(observations, start, end) { observations.filter { it.hr != null && it.ts in start..end }.sortedBy { it.ts } }
    val buckets = remember(observations, start, end) {
        observations.filter { !it.snapshot && (it.steps ?: 0) > 0 && it.ts in start until end }
            .groupBy { start + (it.ts - start) / 900 * 900 }.mapValues { (_, v) -> v.sumOf { it.steps ?: 0 } }
    }
    val sleepBlocks = remember(nights, start, end) {
        nights.flatMap { n -> n.sleepIntervals.ifEmpty { listOfNotNull(if (n.onset != null && n.wake != null) n.onset to n.wake else null) } }
            .filter { (a, b) -> b > start && a < end }
    }
    val restful = remember(nights) { nights.flatMap { it.restfulIntervals } }
    val gaps = remember(nights) { nights.flatMap { it.gaps } }
    val visibleWorkouts = workouts.filter { !it.ignored && it.start + it.seconds > start && it.start < end }

    fun readout(ts: Long): String {
        val parts = mutableListOf(Fmt.clock(ts, tz))
        hr.minByOrNull { abs(it.ts - ts) }?.takeIf { abs(it.ts - ts) <= 300 }?.let { parts += "${it.hr} bpm" }
        buckets[start + (ts - start) / 900 * 900]?.let { parts += "${Fmt.int(it)} steps/15 min" }
        stress.firstOrNull { it.score != null && ts >= it.ts && ts < it.ts + 900 }?.let { parts += "stress ${it.score} (est.)" }
        spo2.minByOrNull { abs(it.ts - ts) }?.takeIf { abs(it.ts - ts) <= 900 }?.let { parts += "SpO₂ ${Fmt.dec(it.percent, 0)}%" }
        if (sleepBlocks.any { (a, b) -> ts in a until b }) parts += "asleep"
        visibleWorkouts.firstOrNull { ts >= it.start && ts < it.start + it.seconds }?.let { parts += Fmt.kind(it.kind) }
        return parts.joinToString("  ·  ")
    }

    val laneSleep = 18.dp; val laneWork = 12.dp; val laneSteps = 30.dp; val laneStress = 8.dp; val laneOx = 10.dp
    val gap = 6.dp; val axisH = 16.dp; val gutter = 46.dp
    val total = axisH + laneSleep + gap + laneWork + gap + hrHeight + gap + laneSteps + gap + laneStress + gap + laneOx
    Column(modifier) {
        Text(selected?.let(::readout) ?: "Tap or drag across the timeline to read every lane",
            style = MaterialTheme.typography.bodySmall, color = if (selected != null) MaterialTheme.colorScheme.onSurface else m.muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
        Canvas(
            Modifier.fillMaxWidth().height(total)
                .pointerInput(start, end, visibleWorkouts) {
                    detectTapGestures { o ->
                        val g = gutter.toPx()
                        if (o.x < g) { selected = null; return@detectTapGestures }
                        val ts = start + ((o.x - g) / (size.width - g) * (end - start)).toLong()
                        // A tap on the workout lane opens that workout; anywhere else reads the lanes.
                        val laneTop = (axisH + laneSleep + gap).toPx()
                        val w = visibleWorkouts.firstOrNull { ts >= it.start && ts < it.start + it.seconds }
                        if (w != null && onWorkout != null && o.y in (laneTop - 4f)..(laneTop + laneWork.toPx() + 4f)) {
                            onWorkout(w); return@detectTapGestures
                        }
                        selected = ts.coerceIn(start, end)
                    }
                }
                .pointerInput(start, end) {
                    detectHorizontalDragGestures(onDragStart = { o ->
                        val g = gutter.toPx()
                        selected = (start + ((o.x - g) / (size.width - g) * (end - start)).toLong()).coerceIn(start, end)
                    }) { change, _ ->
                        val g = gutter.toPx()
                        selected = (start + ((change.position.x - g) / (size.width - g) * (end - start)).toLong()).coerceIn(start, end)
                    }
                }
        ) {
            val g = gutter.toPx(); val pw = size.width - g
            val span = (end - start).toFloat().coerceAtLeast(1f)
            fun x(t: Long) = g + ((t.coerceIn(start, end) - start) / span * pw)
            var y = axisH.toPx()
            val ySleep = y; y += laneSleep.toPx() + gap.toPx()
            val yWork = y; y += laneWork.toPx() + gap.toPx()
            val yHr = y; val hrH = hrHeight.toPx(); y += hrH + gap.toPx()
            val ySteps = y; y += laneSteps.toPx() + gap.toPx()
            val yStress = y; y += laneStress.toPx() + gap.toPx()
            val yOx = y
            val r = CornerRadius(4.dp.toPx())

            // Hour grid, labelled so labels never collide on a narrow phone.
            val perHour = pw / (span / 3600f)
            val step = listOf(1, 2, 3, 4, 6, 12).firstOrNull { it * perHour >= 34.dp.toPx() } ?: 12
            val zone = Fmt.zone(tz)
            var t = (start / 3600 + 1) * 3600
            while (t <= end) {
                val h = Instant.ofEpochSecond(t).atZone(zone).hour
                if (h % step == 0) {
                    val gx = x(t)
                    drawLine(if (h == 0) m.muted.copy(alpha = 0.5f) else m.track, Offset(gx, axisH.toPx() - 2), Offset(gx, size.height), 1f)
                    val text = if (h == 0) Fmt.weekday(Instant.ofEpochSecond(t).atZone(zone).toLocalDate()) else "%02d".format(h)
                    if (gx > g + 8 && gx < size.width - 8) label(tm, text, gx, axisH.toPx() / 2, m.muted, bold = h == 0, center = true)
                }
                t += 3600
            }
            if (now in start until end) drawRect(m.track.copy(alpha = 0.45f), Offset(x(now), axisH.toPx()), Size(x(end) - x(now), size.height))

            // Lane labels.
            label(tm, "Sleep", 0f, ySleep + laneSleep.toPx() / 2, m.muted)
            label(tm, "Activity", 0f, yWork + laneWork.toPx() / 2, m.muted)
            label(tm, "Heart", 0f, yHr + 10.dp.toPx(), m.muted)
            label(tm, "Steps", 0f, ySteps + laneSteps.toPx() / 2, m.muted)
            label(tm, "Stress", 0f, yStress + laneStress.toPx() / 2, m.muted)
            label(tm, "SpO₂", 0f, yOx + laneOx.toPx() / 2, m.muted)

            // Sleep.
            sleepBlocks.forEach { (a, b) -> drawRoundRect(m.sleep.copy(alpha = 0.45f), Offset(x(a), ySleep), Size(max(2f, x(b) - x(a)), laneSleep.toPx()), r) }
            restful.filter { (a, b) -> b > start && a < end }.forEach { (a, b) ->
                drawRoundRect(m.restful, Offset(x(a), ySleep + 3.dp.toPx()), Size(max(1.5f, x(b) - x(a)), laneSleep.toPx() - 6.dp.toPx()), r)
            }
            gaps.filter { (a, b) -> b > start && a < end }.forEach { (a, b) ->
                drawRect(m.muted.copy(alpha = 0.35f), Offset(x(a), ySleep), Size(max(1.5f, x(b) - x(a)), laneSleep.toPx()))
            }

            // Workouts.
            visibleWorkouts.forEach { w ->
                drawRoundRect(m.intensity, Offset(x(w.start), yWork), Size(max(3f, x(w.start + w.seconds) - x(w.start)), laneWork.toPx()), r)
            }

            // Heart rate with zone bands.
            val vals = hr.mapNotNull { it.hr }
            var lo = (vals.minOrNull() ?: 50) - 6; var hi = max(vals.maxOrNull() ?: 120, lo + 40) + 6
            lo = (floor(lo / 10.0) * 10).toInt().coerceAtLeast(30); hi = (ceil(hi / 10.0) * 10).toInt()
            fun yv(v: Double) = yHr + hrH - ((v.coerceIn(lo.toDouble(), hi.toDouble()) - lo) / (hi - lo) * hrH).toFloat()
            hrMax?.let { mx ->
                listOf(Triple(0.64, 0.77, m.stress), Triple(0.77, 9.0, m.heart)).forEach { (a, b, c) ->
                    val va = mx * a; val vb = min(mx * b, hi.toDouble())
                    if (va < hi) drawRect(c.copy(alpha = 0.08f), Offset(g, yv(vb)), Size(pw, yv(max(va, lo.toDouble())) - yv(vb)))
                }
            }
            listOf(lo, (lo + hi) / 2, hi).forEach { v ->
                drawLine(m.track, Offset(g, yv(v.toDouble())), Offset(size.width, yv(v.toDouble())), 1f)
                label(tm, v.toString(), g - 4.dp.toPx(), yv(v.toDouble()), m.muted, size = 9f, alignEnd = true)
            }
            val path = Path(); var prev: Observation? = null
            hr.forEach { p ->
                val px = x(p.ts); val py = yv(p.hr!!.toDouble())
                val broken = prev == null || p.ts - prev!!.ts > 360 || p.snapshot || prev!!.snapshot
                if (broken) path.moveTo(px, py) else path.lineTo(px, py)
                if (p.snapshot) drawCircle(m.heart, 2.dp.toPx(), Offset(px, py), style = Stroke(1.5f))
                prev = p
            }
            drawPath(path, m.heart, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
            if (hr.isNotEmpty()) {
                listOf(hr.minBy { it.hr!! } to 12.dp.toPx(), hr.maxBy { it.hr!! } to -10.dp.toPx()).forEach { (p, dy) ->
                    drawCircle(m.heart, 3.dp.toPx(), Offset(x(p.ts), yv(p.hr!!.toDouble())))
                    label(tm, p.hr.toString(), x(p.ts), yv(p.hr.toDouble()) + dy, ink(m), size = 10f, bold = true, center = true)
                }
            }

            // Steps per 15 minutes.
            val smax = max(100, buckets.values.maxOrNull() ?: 0).toFloat()
            val bw = max(1f, pw * 900 / span - 1f)
            buckets.forEach { (k, v) ->
                val h = max(1.5f, v / smax * laneSteps.toPx())
                drawRoundRect(m.steps, Offset(x(k), ySteps + laneSteps.toPx() - h), Size(bw, h), CornerRadius(min(2f, bw / 3)))
            }
            drawLine(m.track, Offset(g, ySteps + laneSteps.toPx()), Offset(size.width, ySteps + laneSteps.toPx()), 1f)

            // Stress strip and SpO2 dots.
            drawRoundRect(m.track, Offset(g, yStress), Size(pw, laneStress.toPx()), CornerRadius(2f))
            stress.filter { it.score != null && it.ts in start until end }.forEach { p ->
                drawRect(m.stress.copy(alpha = 0.18f + 0.82f * (p.score!!.coerceIn(0, 100) / 100f)), Offset(x(p.ts), yStress),
                    Size(max(1f, pw * 900 / span), laneStress.toPx()))
            }
            spo2.filter { it.ts in start until end }.forEach { drawCircle(m.oxygen, 3.dp.toPx(), Offset(x(it.ts), yOx + laneOx.toPx() / 2)) }

            if (now in start..end) drawLine(m.readiness, Offset(x(now), axisH.toPx()), Offset(x(now), size.height), 1.5.dp.toPx())
            selected?.let { s ->
                drawLine(ink(m), Offset(x(s), axisH.toPx()), Offset(x(s), size.height), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
        }
    }
}

/** Readable ink inside canvases, which cannot read MaterialTheme: the card colour decides light or dark. */
private fun ink(m: org.tdgs.ph.ui.theme.Metrics): Color =
    if (m.card.luminance() > 0.5f) Color(0xFF12161D) else Color(0xFFE8ECF2)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RibbonLegend() {
    val m = LocalMetrics.current
    val items = listOf(m.sleep.copy(alpha = 0.45f) to "Asleep", m.restful to "Restful", m.intensity to "Workout", m.heart to "Heart rate",
        m.steps to "Steps", m.stress to "Stress (est.)", m.oxygen to "SpO₂")
    androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (c, t) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                Text(" $t", style = MaterialTheme.typography.labelSmall, color = m.muted)
            }
        }
    }
}

/** Line sparkline over a shaded usual range; the newest reading is a larger dot. Missing days break the line. */
@Composable
fun TrendSpark(values: List<Double?>, color: Color, modifier: Modifier = Modifier, band: Pair<Double, Double>? = null) {
    Canvas(modifier) {
        val present = values.filterNotNull()
        if (present.isEmpty()) return@Canvas
        var lo = (present + listOfNotNull(band?.first)).min(); var hi = (present + listOfNotNull(band?.second)).max()
        if (hi - lo < 1e-6) { lo -= 1; hi += 1 }
        val pad = (hi - lo) * 0.14; lo -= pad; hi += pad
        val w = size.width / max(1, values.size)
        fun y(v: Double) = (size.height - (v - lo) / (hi - lo) * size.height).toFloat()
        band?.let { (a, b) -> drawRect(color.copy(alpha = 0.14f), Offset(0f, y(b)), Size(size.width, y(a) - y(b))) }
        val path = Path(); var open = false
        values.forEachIndexed { i, v ->
            if (v == null) { open = false; return@forEachIndexed }
            val px = i * w + w / 2
            if (open) path.lineTo(px, y(v)) else path.moveTo(px, y(v))
            open = true
        }
        drawPath(path, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
        val last = values.indexOfLast { it != null }
        if (last >= 0) drawCircle(color, 3.dp.toPx(), Offset(last * w + w / 2, y(values[last]!!)))
    }
}

/** Calendar heatmap, Monday-first columns, darker is higher relative to the shown period. Tap a day to pick it. */
@Composable
fun Heatmap(days: List<Pair<LocalDate, Double?>>, color: Color, modifier: Modifier = Modifier, onPick: ((LocalDate) -> Unit)? = null) {
    val m = LocalMetrics.current
    if (days.isEmpty()) return
    val lead = days.first().first.dayOfWeek.value - 1
    val cols = (days.size + lead + 6) / 7
    val sorted = days.mapNotNull { it.second }.sorted()
    val qs = listOf(0.2, 0.4, 0.6, 0.8).map { q -> if (sorted.isEmpty()) 0.0 else sorted[(q * (sorted.size - 1)).toInt()] }
    BoxWithAspect(modifier, cols) { cell, gapPx ->
        Canvas(Modifier.fillMaxWidth().height(with(androidx.compose.ui.platform.LocalDensity.current) { (7 * (cell + gapPx)).toDp() })
            .pointerInput(days) {
                detectTapGestures { o ->
                    val col = (o.x / (cell + gapPx)).toInt(); val row = (o.y / (cell + gapPx)).toInt()
                    days.getOrNull(col * 7 + row - lead)?.takeIf { it.second != null }?.let { onPick?.invoke(it.first) }
                }
            }) {
            days.forEachIndexed { i, (_, v) ->
                val k = i + lead
                val level = if (v == null) 0 else 1 + qs.count { v > it }
                val c = if (level == 0) m.track else color.copy(alpha = listOf(0f, 0.2f, 0.38f, 0.58f, 0.8f, 1f)[level])
                drawRoundRect(c, Offset((k / 7) * (cell + gapPx), (k % 7) * (cell + gapPx)), Size(cell, cell), CornerRadius(cell / 4))
            }
        }
    }
}

/** Measures the available width and hands a square cell size to [content]. */
@Composable
private fun BoxWithAspect(modifier: Modifier, cols: Int, content: @Composable (cell: Float, gap: Float) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val gapPx = with(density) { 2.dp.toPx() }
        val widthPx = with(density) { maxWidth.toPx() }
        val cell = min(with(density) { 14.dp.toPx() }, widthPx / cols - gapPx).coerceAtLeast(2f)
        content(cell, gapPx)
    }
}

/** One row per night on an evening-to-noon axis: asleep, restful, unrecorded, with the usual bed and wake times dashed. */
@Composable
fun SleepNightsChart(nights: List<Night>, tz: String, modifier: Modifier = Modifier) {
    val m = LocalMetrics.current
    val tm = rememberTextMeasurer()
    val rows = nights.filter { it.onset != null && it.wake != null }
    if (rows.isEmpty()) return
    val rowH = if (rows.size > 14) 12.dp else 18.dp
    val zone = Fmt.zone(tz)
    fun base(n: Night) = LocalDate.parse(n.night).atStartOfDay(zone).toEpochSecond() - 4 * 3600
    val span = 16 * 3600f
    val usualOn = org.tdgs.ph.data.Baselines.median(rows.map { (it.onset!! - base(it)).toDouble() })
    val usualOff = org.tdgs.ph.data.Baselines.median(rows.map { (it.wake!! - base(it)).toDouble() })
    Canvas(modifier.fillMaxWidth().height(rowH * rows.size + 18.dp)) {
        val g = 44.dp.toPx(); val pw = size.width - g; val rh = rowH.toPx()
        for (h in 0..16 step 4) {
            val gx = g + h / 16f * pw
            drawLine(m.track, Offset(gx, 0f), Offset(gx, rh * rows.size), 1f)
            label(tm, "%02d".format((20 + h) % 24), gx, rh * rows.size + 9.dp.toPx(), m.muted, size = 9f, center = true)
        }
        rows.forEachIndexed { i, n ->
            val b = base(n); val y = i * rh
            fun x(t: Long) = g + ((t - b) / span).coerceIn(0f, 1f) * pw
            val bh = rh - 4.dp.toPx(); val r = CornerRadius(bh / 2.5f)
            n.sleepIntervals.ifEmpty { listOf(n.onset!! to n.wake!!) }.forEach { (a, c) ->
                drawRoundRect(m.sleep.copy(alpha = 0.45f), Offset(x(a), y + 2.dp.toPx()), Size(max(1.5f, x(c) - x(a)), bh), r)
            }
            n.restfulIntervals.forEach { (a, c) -> drawRoundRect(m.restful, Offset(x(a), y + 4.dp.toPx()), Size(max(1f, x(c) - x(a)), bh - 4.dp.toPx()), CornerRadius(2f)) }
            n.gaps.forEach { (a, c) -> drawRect(m.muted.copy(alpha = 0.35f), Offset(x(a), y + 2.dp.toPx()), Size(max(1f, x(c) - x(a)), bh)) }
            label(tm, Fmt.dayTiny(LocalDate.parse(n.night)), g - 6.dp.toPx(), y + rh / 2, m.muted, size = 9f, alignEnd = true)
        }
        listOfNotNull(usualOn, usualOff).forEach { v ->
            val gx = g + (v / span).toFloat().coerceIn(0f, 1f) * pw
            drawLine(m.muted, Offset(gx, 0f), Offset(gx, rh * rows.size), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }
    }
}

/** A readiness or sleep input: name, weight, score, a meter with the 50 midpoint, and the hub's detail line. */
@Composable
fun ComponentMeter(name: String, score: Int?, color: Color, detail: String? = null, weight: Double? = null) {
    val m = LocalMetrics.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            weight?.let { Text("${Fmt.int(it * 100)}%  ", style = MaterialTheme.typography.labelSmall, color = m.muted) }
            Text(score?.toString() ?: "Missing", style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (score != null) FontWeight.SemiBold else FontWeight.Normal, color = if (score != null) MaterialTheme.colorScheme.onSurface else m.muted)
        }
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(m.track)) {
            if (score != null) Box(Modifier.fillMaxWidth(score.coerceIn(0, 100) / 100f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
        detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = m.muted, modifier = Modifier.padding(top = 2.dp)) }
    }
}

/** Thin progress bar for goals inside cards. */
@Composable
fun ProgressBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val m = LocalMetrics.current
    Box(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(m.track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

/** Small rounded status chip: dot, label, value. */
@Composable
fun StatusChip(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    val m = LocalMetrics.current
    Row(modifier.clip(RoundedCornerShape(50)).background(m.card).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text("  $label ", style = MaterialTheme.typography.labelSmall, color = m.muted)
        Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}
