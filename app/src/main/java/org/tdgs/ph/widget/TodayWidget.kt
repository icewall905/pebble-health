package org.tdgs.ph.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.appwidget.updateAll
import org.tdgs.ph.MainActivity
import org.tdgs.ph.PhApp
import org.tdgs.ph.data.Dashboard
import org.tdgs.ph.ui.Fmt

class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = (context.applicationContext as PhApp).repository
        repo.loadCached()
        val d = repo.state.value.dashboard
        provideContent { Content(d) }
    }

    @Composable
    private fun Content(d: Dashboard?) {
        val bg = ColorProvider(Color(0xFF0E1116))
        val fg = ColorProvider(Color(0xFFE8ECF2))
        val muted = ColorProvider(Color(0xFF8A93A3))
        Column(
            GlanceModifier.fillMaxSize().background(bg).cornerRadius(20.dp).padding(14.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            if (d == null) {
                Text("Open Pebble Health to connect", style = TextStyle(color = muted))
                return@Column
            }
            Text(Fmt.int(d.stepsToday ?: d.todayDay?.steps), style = TextStyle(color = ColorProvider(Color(0xFF34D3A6)),
                fontSize = 28.sp, fontWeight = FontWeight.Bold))
            Text("steps today", style = TextStyle(color = muted, fontSize = 12.sp))
            Spacer(GlanceModifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Metric("Sleep", d.lastNight?.let { Fmt.hm(it.sleepS) } ?: "—", Color(0xFF8C7CF0), muted)
                Spacer(GlanceModifier.width(14.dp))
                Metric("HR", d.latestHr?.toString() ?: "—", Color(0xFFFF6B6B), muted)
                Spacer(GlanceModifier.width(14.dp))
                Metric("Watch", (d.battery.percent ?: d.battery.lastPercent)?.let { "$it%" } ?: "—", Color(0xFF9BE15D), muted)
            }
            Spacer(GlanceModifier.height(6.dp))
            Text("Updated ${Fmt.clock(d.now, d.tz)}", style = TextStyle(color = fg, fontSize = 10.sp))
        }
    }

    @Composable
    private fun Metric(label: String, value: String, color: Color, muted: ColorProvider) {
        Column {
            Text(value, style = TextStyle(color = ColorProvider(color), fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Text(label, style = TextStyle(color = muted, fontSize = 11.sp))
        }
    }

    companion object {
        suspend fun refreshAll(context: Context) {
            if (GlanceAppWidgetManager(context).getGlanceIds(TodayWidget::class.java).isNotEmpty()) TodayWidget().updateAll(context)
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
