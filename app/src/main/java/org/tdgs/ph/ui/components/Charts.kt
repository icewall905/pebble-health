package org.tdgs.ph.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.tdgs.ph.ui.theme.LocalMetrics
import kotlin.math.max
import kotlin.math.min

/** Progress ring; [progress] may exceed 1 (drawn full). */
@Composable
fun Ring(progress: Float, color: Color, modifier: Modifier = Modifier, size: Dp = 132.dp, stroke: Dp = 12.dp,
         content: @Composable () -> Unit = {}) {
    val track = LocalMetrics.current.track
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(900), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = stroke.toPx()
            val inset = s / 2
            val arcSize = Size(this.size.width - s, this.size.height - s)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round))
            if (animated > 0f) drawArc(color, 135f, 270f * animated, false, Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Small inline trend line for cards. */
@Composable
fun Sparkline(values: List<Double?>, color: Color, modifier: Modifier = Modifier, bars: Boolean = false) {
    val track = LocalMetrics.current.track
    Canvas(modifier) {
        val present = values.filterNotNull()
        if (present.isEmpty()) return@Canvas
        val hi = present.max(); val lo = if (bars) 0.0 else present.min()
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val w = size.width / max(1, values.size)
        if (bars) {
            values.forEachIndexed { i, v ->
                val h = if (v == null) 0f else ((v - lo) / span * size.height).toFloat().coerceAtLeast(2f)
                drawRoundRect(if (v == null) track else color, Offset(i * w + w * 0.15f, size.height - h),
                    Size(w * 0.7f, h), CornerRadius(w * 0.3f))
            }
        } else {
            val path = Path(); var started = false
            values.forEachIndexed { i, v ->
                if (v == null) { started = false; return@forEachIndexed }
                val x = i * w + w / 2; val y = size.height - ((v - lo) / span * size.height).toFloat()
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

data class Bar(val label: String, val value: Double?, val detail: String? = null)

/** Vertical bars with an optional goal line; tap a bar to read its value. */
@Composable
fun BarChart(bars: List<Bar>, color: Color, modifier: Modifier = Modifier, goal: Double? = null, height: Dp = 180.dp,
             valueText: (Double) -> String = { org.tdgs.ph.ui.Fmt.int(it) }, labelEvery: Int = 1, stacked: List<Double?>? = null,
             stackedColor: Color = color) {
    val m = LocalMetrics.current
    var selected by remember(bars) { mutableStateOf(bars.indexOfLast { it.value != null }) }
    val hi = max(bars.mapNotNull { it.value }.maxOrNull() ?: 0.0, goal ?: 0.0).takeIf { it > 0 } ?: 1.0
    Column(modifier) {
        val sel = bars.getOrNull(selected)
        Text(
            if (sel?.value != null) "${sel.label}  ·  ${valueText(sel.value)}${sel.detail?.let { "  ·  $it" } ?: ""}" else " ",
            style = MaterialTheme.typography.bodySmall, color = m.muted, modifier = Modifier.padding(bottom = 6.dp),
        )
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(bars) {
                detectTapGestures { o -> selected = (o.x / (size.width / max(1, bars.size))).toInt().coerceIn(0, bars.lastIndex) }
            }
        ) {
            val w = size.width / max(1, bars.size)
            val bw = min(w * 0.64f, 28.dp.toPx())
            bars.forEachIndexed { i, b ->
                val x = i * w + (w - bw) / 2
                val v = b.value
                if (v == null) {
                    drawRoundRect(m.track, Offset(x, size.height - 3.dp.toPx()), Size(bw, 3.dp.toPx()), CornerRadius(2f))
                } else {
                    val h = (v / hi * size.height).toFloat().coerceAtLeast(3.dp.toPx())
                    val c = if (i == selected) color else color.copy(alpha = 0.72f)
                    drawRoundRect(c, Offset(x, size.height - h), Size(bw, h), CornerRadius(bw / 3))
                    stacked?.getOrNull(i)?.let { sv ->
                        val sh = (sv / hi * size.height).toFloat().coerceIn(0f, h)
                        drawRoundRect(stackedColor, Offset(x, size.height - sh), Size(bw, sh), CornerRadius(bw / 3))
                    }
                }
            }
            goal?.let {
                val y = size.height - (it / hi * size.height).toFloat()
                drawLine(m.muted, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }
        }
        AxisLabels(bars.map { it.label }, labelEvery)
    }
}

@Composable
fun AxisLabels(labels: List<String>, every: Int) {
    val m = LocalMetrics.current
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        labels.forEachIndexed { i, l ->
            Text(if (i % every == 0 || i == labels.lastIndex) l else "", Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, color = m.muted, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

/** Daily ranges (low..high) with an average dot, e.g. heart rate per day. */
@Composable
fun RangeChart(items: List<Triple<Double?, Double?, Double?>>, labels: List<String>, color: Color, modifier: Modifier = Modifier,
               height: Dp = 180.dp, labelEvery: Int = 1, unit: String = "") {
    val m = LocalMetrics.current
    var selected by remember(items) { mutableStateOf(items.indexOfLast { it.second != null }) }
    val lo = items.mapNotNull { it.first }.minOrNull() ?: 0.0
    val hi = items.mapNotNull { it.third }.maxOrNull() ?: 1.0
    val span = (hi - lo).takeIf { it > 0 } ?: 1.0
    Column(modifier) {
        val s = items.getOrNull(selected)
        Text(if (s?.second != null) "${labels[selected]}  ·  avg ${org.tdgs.ph.ui.Fmt.int(s.second)} $unit  ·  ${org.tdgs.ph.ui.Fmt.int(s.first)}–${org.tdgs.ph.ui.Fmt.int(s.third)}" else " ",
            style = MaterialTheme.typography.bodySmall, color = m.muted, modifier = Modifier.padding(bottom = 6.dp))
        Canvas(Modifier.fillMaxWidth().height(height).pointerInput(items) {
            detectTapGestures { o -> selected = (o.x / (size.width / max(1, items.size))).toInt().coerceIn(0, items.lastIndex) }
        }) {
            val w = size.width / max(1, items.size)
            val bw = min(w * 0.5f, 14.dp.toPx())
            fun y(v: Double) = (size.height - (v - lo) / span * (size.height - 8.dp.toPx()) - 4.dp.toPx()).toFloat()
            items.forEachIndexed { i, (a, avg, b) ->
                if (a == null || b == null) return@forEachIndexed
                val x = i * w + w / 2
                val alpha = if (i == selected) 0.55f else 0.3f
                drawRoundRect(color.copy(alpha = alpha), Offset(x - bw / 2, y(b)), Size(bw, max(y(a) - y(b), 3f)), CornerRadius(bw / 2))
                avg?.let { drawCircle(color, if (i == selected) 5.dp.toPx() else 3.5.dp.toPx(), Offset(x, y(it))) }
            }
        }
        AxisLabels(labels, labelEvery)
    }
}

/** Time series over a fixed window [start, end] in epoch seconds; gaps longer than [gapS] break the line. */
@Composable
fun TimeLineChart(points: List<Pair<Long, Double>>, start: Long, end: Long, color: Color, modifier: Modifier = Modifier,
                  height: Dp = 180.dp, yMin: Double? = null, yMax: Double? = null, gapS: Long = 15 * 60, fill: Boolean = true,
                  dots: Boolean = false, axis: List<Pair<Long, String>> = emptyList(), bands: List<Pair<Long, Long>> = emptyList(),
                  bandColor: Color = Color.Unspecified) {
    val m = LocalMetrics.current
    val lo = yMin ?: points.minOfOrNull { it.second } ?: 0.0
    val hi = yMax ?: points.maxOfOrNull { it.second } ?: 1.0
    val span = (hi - lo).takeIf { it > 0 } ?: 1.0
    Column(modifier) {
        Box {
            Canvas(Modifier.fillMaxWidth().height(height)) {
                val tw = (end - start).toFloat().coerceAtLeast(1f)
                fun x(t: Long) = (t - start) / tw * size.width
                fun y(v: Double) = (size.height - (v - lo) / span * (size.height - 8.dp.toPx()) - 4.dp.toPx()).toFloat()
                bands.forEach { (a, b) ->
                    drawRect(if (bandColor == Color.Unspecified) m.track else bandColor, Offset(x(a), 0f), Size(x(b) - x(a), size.height))
                }
                for (k in 1..3) {
                    val gy = size.height * k / 4
                    drawLine(m.track, Offset(0f, gy), Offset(size.width, gy), 1f)
                }
                if (points.isEmpty()) return@Canvas
                val segments = mutableListOf<MutableList<Pair<Long, Double>>>()
                points.forEach { p ->
                    val last = segments.lastOrNull()?.lastOrNull()
                    if (last == null || p.first - last.first > gapS) segments.add(mutableListOf(p)) else segments.last().add(p)
                }
                segments.forEach { seg ->
                    if (seg.size == 1 || dots) {
                        seg.forEach { drawCircle(color, 2.5.dp.toPx(), Offset(x(it.first), y(it.second))) }
                        if (dots) return@forEach
                    }
                    val path = Path()
                    seg.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.first), y(p.second)) else path.lineTo(x(p.first), y(p.second)) }
                    if (fill) {
                        val area = Path().apply {
                            addPath(path)
                            lineTo(x(seg.last().first), size.height); lineTo(x(seg.first().first), size.height); close()
                        }
                        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
                    }
                    drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            Text(org.tdgs.ph.ui.Fmt.int(hi), Modifier.align(Alignment.TopEnd), style = MaterialTheme.typography.labelSmall, color = m.muted)
            Text(org.tdgs.ph.ui.Fmt.int(lo), Modifier.align(Alignment.BottomEnd), style = MaterialTheme.typography.labelSmall, color = m.muted)
        }
        if (axis.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    axis.forEach { (_, l) ->
                        Text(l, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = m.muted, textAlign = TextAlign.Start)
                    }
                }
            }
        }
    }
}

/** Night timeline: sleep blocks light, restful blocks dark, gaps as track. */
@Composable
fun SleepTimeline(start: Long, end: Long, sleep: List<Pair<Long, Long>>, restful: List<Pair<Long, Long>>, modifier: Modifier = Modifier,
                  height: Dp = 56.dp) {
    val m = LocalMetrics.current
    Canvas(modifier.fillMaxWidth().height(height)) {
        val tw = (end - start).toFloat().coerceAtLeast(1f)
        fun x(t: Long) = ((t - start) / tw * size.width).coerceIn(0f, size.width)
        val r = CornerRadius(6.dp.toPx())
        drawRoundRect(m.track, Offset(0f, size.height * 0.35f), Size(size.width, size.height * 0.3f), r)
        sleep.forEach { (a, b) -> drawRoundRect(m.sleep.copy(alpha = 0.55f), Offset(x(a), size.height * 0.2f), Size(x(b) - x(a), size.height * 0.6f), r) }
        restful.forEach { (a, b) -> drawRoundRect(m.restful, Offset(x(a), 0f), Size(x(b) - x(a), size.height), r) }
    }
}
