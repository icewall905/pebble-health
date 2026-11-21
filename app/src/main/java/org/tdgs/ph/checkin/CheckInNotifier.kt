package org.tdgs.ph.checkin

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.tdgs.ph.MainActivity
import org.tdgs.ph.R
import org.tdgs.ph.data.Api
import org.tdgs.ph.data.Dashboard
import org.tdgs.ph.data.HubConfig
import org.tdgs.ph.data.Repository
import org.tdgs.ph.planner.PlannerApi
import org.tdgs.ph.ui.Fmt
import java.time.Instant
import java.time.ZonedDateTime

internal object CheckInNotifier {
    const val CHANNEL = "trainer_check_ins"
    private const val NOTIFICATION_ID = 8712

    fun permitted(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    suspend fun afterRefresh(context: Context, repo: Repository, hub: HubConfig, dashboard: Dashboard) {
        val now = ZonedDateTime.now()
        val date = now.toLocalDate()
        val scheduled = dueCheckIn(now, repo.settings.checkInsFor(date)) ?: return
        if (!permitted(context) || repo.settings.current() != hub) return
        val hubClock = now.withZoneSameInstant(Fmt.zone(dashboard.tz))
        val currentDay = dashboard.today == hubClock.toLocalDate().toString()
        val received = dashboard.lastReceived?.let(Instant::ofEpochSecond)
        val fresh = received != null && !received.isBefore(now.toInstant().minusSeconds(90 * 60)) &&
            received.atZone(hubClock.zone).toLocalDate() == hubClock.toLocalDate()
        val progress = CheckInProgress(
            dashboard.stepsToday?.takeIf { it >= 0 && currentDay && fresh },
            repo.settings.prefs.first().stepGoal,
            dashboard.intensityWeek?.takeIf { it.isFinite() && it >= 0 && currentDay },
            dashboard.intensityGoal?.takeIf { it.isFinite() && it > 0 } ?: 150.0,
            hubClock.hour * 60 + hubClock.minute,
        )
        val message = agentMessage(repo, hub, progress) ?: progress.fallback()
        val deliveryTime = ZonedDateTime.now()
        if (deliveryTime.toLocalDate() != date || !permitted(context) ||
            dueCheckIn(deliveryTime, repo.settings.checkInsFor(date)) != scheduled ||
            !repo.settings.claimCheckIn(hub, date, scheduled)
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Trainer check-ins", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = listOf(message, progress.contextLine(Fmt::int)).filter(String::isNotBlank).joinToString("\n")
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle("Trainer check-in")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private suspend fun agentMessage(repo: Repository, hub: HubConfig, progress: CheckInProgress): String? {
        if (progress.steps == null && progress.weeklyMinutes == null) return null
        val endpoint = Api.normalize(hub.baseUrl) ?: return null
        val origin = endpoint.newBuilder().encodedPath("/").build().toString()
        if (repo.settings.plannerCookie(origin) == null) return null
        return try {
            PlannerApi(endpoint, repo.settings).checkIn(JSONObject().apply {
                put("minute", progress.minuteOfDay)
                put("steps", progress.steps)
                put("step_goal", progress.stepGoal)
                put("move_behind", progress.moveBehind)
                put("weekly_minutes", progress.weeklyMinutes)
                put("weekly_goal", progress.weeklyGoal)
            }).optString("message").trim().takeIf { it.length in 1..180 && '\n' !in it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
