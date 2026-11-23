package org.tdgs.ph.workout

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.cancel
import java.util.UUID

internal val watchSports = listOf(Sport.run, Sport.walk, Sport.hike, Sport.cycle, Sport.strength,
    Sport.tennis, Sport.rowing, Sport.yoga, Sport.beat_saber, Sport.workout)

class WatchWorkoutCommands : BasePebbleListenerService() {
    private var lastAccepted: Pair<WatchIdentifier, UInt>? = null

    override fun onCreate() {
        super.onCreate()
        DefaultPebbleAndroidAppPicker.getInstance(applicationContext).enableAutoSelect = false
    }

    override fun onDestroy() {
        coroutineScope.cancel()
        super.onDestroy()
    }

    override suspend fun onMessageReceived(watchappUUID: UUID, data: PebbleDictionary, watch: WatchIdentifier): ReceiveResult {
        if (watchappUUID != WORKOUT_WATCH_UUID) return ReceiveResult.Nack
        val request = watch to (data.number(23u) ?: return ReceiveResult.Nack)
        if (request == lastAccepted) return ReceiveResult.Ack
        val command = data.number(20u)
        val argument = data.number(21u)
        val state = WorkoutRecorder.live.value
        if (command == 1u) {
            if (state != null) return ReceiveResult.Nack
            val sport = argument?.toInt()?.let(watchSports::getOrNull) ?: return ReceiveResult.Nack
            if (gpsGranted(this, sport) && runCatching {
                if (WorkoutRecorder.armed.value) startService(startIntent(this, sport))
                else startForegroundService(startIntent(this, sport))
            }.getOrNull() != null) {
                lastAccepted = request
                return ReceiveResult.Ack
            }
            requestOnPhone(this, sport)
            return ReceiveResult.Nack
        }
        if (state == null || !state.mirrorWatch || data.number(22u) != state.watchToken) return ReceiveResult.Nack
        val action = when (command) {
            2u -> WorkoutRecorder.PAUSE
            3u -> WorkoutRecorder.RESUME
            4u -> WorkoutRecorder.LAP.takeIf { !state.paused && state.workout.sport != Sport.strength }
            5u -> WorkoutRecorder.REST.takeIf { !state.paused && state.workout.sport == Sport.strength }
            6u -> WorkoutRecorder.FINISH
            7u -> WorkoutRecorder.DISCARD
            8u -> WorkoutRecorder.REPEAT_SET.takeIf { !state.paused && state.lastSet != null }
            else -> null
        } ?: return ReceiveResult.Nack
        return if (runCatching {
            WorkoutRecorder.command(this, action) {
                putExtra("watch_token", state.watchToken.toInt())
                if (action == WorkoutRecorder.REST) {
                    val rest = getSharedPreferences("strength_recording", 0).getInt("rest_seconds", 60)
                    putExtra("seconds", rest.takeIf { it in 30..90 } ?: 60)
                }
            }
        }.isSuccess) {
            lastAccepted = request
            ReceiveResult.Ack
        } else ReceiveResult.Nack
    }

    companion object {
        private const val CHANNEL = "watch_workout_start"
        private const val NOTIFICATION = 0x3714

        private fun PebbleDictionary.number(key: UInt): UInt? = when (val item = this[key]) {
            is PebbleDictionaryItem.UInt32 -> item.value
            is PebbleDictionaryItem.Int32 -> item.value.toUInt()
            else -> null
        }

        private fun gpsGranted(context: Context, sport: Sport) =
            !sport.gpsDefault || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        private fun startIntent(context: Context, sport: Sport) =
            Intent(context, WorkoutRecorder::class.java).setAction(WorkoutRecorder.START).apply {
                putExtra("sport", sport.name)
                putExtra("gps", sport.gpsDefault)
                putExtra("mirror_watch", true)
                putExtra("from_watch", true)
                putExtra("strap", context.getSharedPreferences("workout_setup", 0).getString("strap_address", null))
            }

        internal fun clearRequest(context: Context) =
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)

        internal fun requestOnPhone(context: Context, sport: Sport) {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Watch workout requests", NotificationManager.IMPORTANCE_HIGH))
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val open = if (gpsGranted(context, sport)) {
                PendingIntent.getForegroundService(context, NOTIFICATION, startIntent(context, sport), flags)
            } else {
                PendingIntent.getActivity(context, NOTIFICATION,
                    Intent(context, WorkoutActivity::class.java).putExtra("watch_sport", sport.name), flags)
            }
            manager.notify(NOTIFICATION, Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Start ${sport.label.lowercase()}")
                .setContentText("Requested from Pebble Health Workout on your Pebble.")
                .setContentIntent(open)
                .setTimeoutAfter(120_000L)
                .setAutoCancel(true)
                .build())
        }
    }
}
